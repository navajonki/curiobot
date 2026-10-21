"""Run the app's exported prompts through the shipped text model, on CPU.

Usage:
  ./gradlew :app:testDebugUnitTest --tests '*ModelLoopTest.exportFixtures'
  python -I tools/model/run_fixtures.py <model.litertlm> app/build/prompt_fixtures.json app/build/model_outputs.json
  ./gradlew :app:testDebugUnitTest --tests '*ModelLoopTest.evaluateOutputs' --rerun

Each prompt is a fresh conversation (system + two worked examples + state),
the same as the app. Sampling matches the app's first attempt.
"""
import json
import sys
import time

import litert_lm as lm


def text_of(msg):
    if isinstance(msg, str):
        return msg
    if hasattr(msg, "text"):
        return msg.text
    try:
        return "".join(c.text for c in msg.contents if hasattr(c, "text"))
    except Exception:
        return str(msg)


def main():
    model, fixtures, out_path = sys.argv[1:4]
    items = json.load(open(fixtures))
    engine = lm.Engine(model, backend=lm.Backend.CPU(), cache_dir="/home/claude/models/cache")
    results = []
    for i, it in enumerate(items):
        msgs = []
        for u, a in it["examples"]:
            msgs += [{"role": "user", "content": u}, {"role": "assistant", "content": a}]
        conv = engine.create_conversation(
            messages=msgs,
            system_message=it["system"],
            sampler_config=lm.SamplerConfig(top_k=40, top_p=0.9, temperature=0.8, seed=1000 + i),
            max_output_tokens=80,
        )
        t = time.time()
        raw = text_of(conv.send_message(it["user"])).strip()
        it = dict(it, raw=raw, ms=int((time.time() - t) * 1000))
        results.append(it)
        print(f"[{i + 1}/{len(items)}] {it['ms']}ms {raw.splitlines()[0] if raw else '<empty>'}", flush=True)
    json.dump(results, open(out_path, "w"), indent=1)


if __name__ == "__main__":
    main()
