"""Precompute the creature's vocabulary embeddings for the app.

Runs the MobileCLIP-S0 text encoder once, here, so the phone only needs the
small image encoder. Writes two assets:

  vocab.json      row metadata (label, kind, category, group) + matching params
  vocab_emb.bin   float32 little-endian, rows x 512, L2-normalized

Usage: python -I build_vocab.py <mobileclip_dir> <assets_out_dir>
"""
import json
import os
import sys

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vocab_source as V  # noqa: E402

CONTEXT = 77
EOT = 49407

# Matching parameters the app reads from vocab.json (kept together with the data
# they were tuned on).
PARAMS = {
    "logit_scale": 100.0,
    "image_size": 256,
    "tripwire_group_min": 0.25,   # summed prob of a tripwire group that trips it
    "unsure_below": 0.15,         # top label prob under this = creature is unsure
}


def encode_texts(model_dir, texts):
    tok = Tokenizer.from_file(os.path.join(model_dir, "tokenizer.json"))
    sess = ort.InferenceSession(os.path.join(model_dir, "onnx", "text_model.onnx"),
                                providers=["CPUExecutionProvider"])
    out = []
    for i in range(0, len(texts), 64):
        batch = texts[i:i + 64]
        ids = np.zeros((len(batch), CONTEXT), dtype=np.int64)
        for j, t in enumerate(batch):
            e = tok.encode(t.lower()).ids[:CONTEXT]
            if e[-1] != EOT:  # keep end-of-text, which the model pools on
                e = e[:CONTEXT - 1] + [EOT]
            ids[j, :len(e)] = e
        emb = sess.run(None, {"input_ids": ids})[0]
        out.append(emb)
    emb = np.concatenate(out).astype(np.float32)
    return emb / np.linalg.norm(emb, axis=1, keepdims=True)


def build(model_dir):
    rows = []      # metadata per output row
    prompts = []   # list of prompt lists; normal words are averaged into one row

    for word, cat, ps in V.normal_entries():
        rows.append({"label": word, "kind": "normal", "cat": cat})
        prompts.append(ps)
    for p in V.PERSON:
        rows.append({"label": "person", "kind": "person", "cat": "person"})
        prompts.append([p])
    rows.append({"label": "hand", "kind": "normal", "cat": "person"})
    prompts.append(list(V.HAND))
    for label, ps in V.LOWINFO.items():
        for p in ps:
            rows.append({"label": label, "kind": "lowinfo", "cat": "lowinfo"})
            prompts.append([p])
    for group, ps in V.TRIPWIRES.items():
        for p in ps:
            rows.append({"label": group, "kind": "tripwire", "cat": "tripwire", "prompt": p})
            prompts.append([p])

    flat = [p for ps in prompts for p in ps]
    emb = encode_texts(model_dir, flat)
    mat = np.zeros((len(rows), emb.shape[1]), dtype=np.float32)
    k = 0
    for i, ps in enumerate(prompts):
        v = emb[k:k + len(ps)].mean(axis=0)
        mat[i] = v / np.linalg.norm(v)
        k += len(ps)

    labels = [r["label"] for r in rows if r["kind"] == "normal"]
    dupes = {l for l in labels if labels.count(l) > 1}
    assert not dupes, f"duplicate words: {dupes}"
    return rows, mat


def main():
    model_dir, out_dir = sys.argv[1], sys.argv[2]
    rows, mat = build(model_dir)
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "vocab.json"), "w") as f:
        json.dump({"model": "mobileclip_s0", "dim": int(mat.shape[1]), "rows": rows,
                   "params": PARAMS}, f, indent=0)
    mat.astype("<f4").tofile(os.path.join(out_dir, "vocab_emb.bin"))
    kinds = {}
    for r in rows:
        kinds[r["kind"]] = kinds.get(r["kind"], 0) + 1
    print(f"rows={len(rows)} dim={mat.shape[1]} kinds={kinds}")


if __name__ == "__main__":
    main()
