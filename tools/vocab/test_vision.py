"""Check vocabulary matching on real photos, using the same steps as the app.

Preprocessing mirrors the Kotlin code: resize shortest edge to 256 (bilinear),
center crop 256x256, scale to 0..1, CHW float32. Matching mirrors it too:
softmax over all rows, then sum probabilities per label / tripwire group.

Usage: python -I test_vision.py <vision_model.onnx> <assets_dir> <image>...
"""
import json
import os
import sys
import time
from collections import defaultdict

import numpy as np
import onnxruntime as ort
from PIL import Image


def preprocess(path, size):
    im = Image.open(path).convert("RGB")
    w, h = im.size
    s = size / min(w, h)
    im = im.resize((max(size, round(w * s)), max(size, round(h * s))), Image.BILINEAR)
    w, h = im.size
    l, t = (w - size) // 2, (h - size) // 2
    im = im.crop((l, t, l + size, t + size))
    x = np.asarray(im, dtype=np.float32) / 255.0
    return x.transpose(2, 0, 1)[None]


def decide(probs, rows, params):
    by_label = defaultdict(float)
    trip = defaultdict(float)
    for p, r in zip(probs, rows):
        if r["kind"] == "tripwire":
            trip[r["label"]] += p
        else:
            by_label[(r["label"], r["kind"])] += p
    top_row = rows[int(np.argmax(probs))]
    worst = max(trip.items(), key=lambda kv: kv[1]) if trip else ("", 0.0)
    ranked = sorted(by_label.items(), key=lambda kv: -kv[1])
    (label, kind), conf = ranked[0]
    if top_row["kind"] == "tripwire" or worst[1] >= params["tripwire_group_min"]:
        verdict = f"TRIPWIRE:{worst[0]}"
    elif kind == "lowinfo":
        verdict = f"lowinfo:{label}"
    elif conf < params["unsure_below"]:
        verdict = f"unsure:{label}"
    else:
        verdict = f"{kind}:{label}"
    return verdict, ranked[:5], worst


def main():
    model, assets = sys.argv[1], sys.argv[2]
    meta = json.load(open(os.path.join(assets, "vocab.json")))
    rows, params = meta["rows"], meta["params"]
    mat = np.fromfile(os.path.join(assets, "vocab_emb.bin"), dtype="<f4").reshape(len(rows), meta["dim"])
    sess = ort.InferenceSession(model, providers=["CPUExecutionProvider"])
    for path in sys.argv[3:]:
        x = preprocess(path, params["image_size"])
        t = time.time()
        e = sess.run(None, {"pixel_values": x})[0][0]
        dt = time.time() - t
        e = e / np.linalg.norm(e)
        logits = params["logit_scale"] * (mat @ e)
        p = np.exp(logits - logits.max())
        p /= p.sum()
        verdict, top, worst = decide(p, rows, params)
        tops = ", ".join(f"{l}={v:.2f}" for (l, _), v in top)
        print(f"{os.path.basename(path):34s} {verdict:22s} [{tops}] trip:{worst[0]}={worst[1]:.2f} ({dt*1000:.0f}ms)")


if __name__ == "__main__":
    main()
