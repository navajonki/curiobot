# Curiobot

A fully offline, camera-fed pocket creature. This is the Android prototype of a
small device with a 1-bit screen, three buttons and a camera: you show it things,
it gets curious, and it slowly becomes its own odd self. Design notes live in
Notion; this repo is the code.

## How it works

```
camera ─► Eye (MobileCLIP-S0 image encoder + fixed vocabulary) ─► a word, or "look away"
                                                                     │
buttons ─► Game ─► Mind (seed traits, needs, reactions, journal) ◄───┘
                     │
                     ├─► Prompt ─► TextModel (Qwen3-0.6B, LiteRT-LM) ─► line + 4 labels
                     │                                                  │
                     └────────────── Safety checks ◄────────────────────┘
                                         │ (retry once, then a written fallback)
                                         ▼
                              400×240 1-bit screen (Lcd)
```

- **Everything runs on the phone.** The text model downloads once on first launch
  (347 MB, resumable); after that the creature works in airplane mode.
- **The creature lives in code, not in the model.** `brain/` holds its seed-born
  traits, needs, memories and reactions as plain data. The text model only turns
  that state into a short line each turn and never writes to it.
- **Closed input.** The camera can only produce words from `tools/vocab/vocab_source.py`.
  Nothing reads text in photos, and photos are never stored (only embeddings, and
  never for people).
- **No guilt.** Neglect makes it bored and quiet, never sick. Low energy brings a
  nap, which is also the natural end of a session.

## Layout

| Path | What |
|---|---|
| `app/src/main/java/dev/curiobot/brain/` | Portable creature logic (no Android imports): `Genome`, `CreatureState`, `Mind`, `Prompt`, `Safety`, `Lines`, `EmbeddingMemory` |
| `.../vision/Eye.kt` | Image encoder + vocabulary matching and tripwires |
| `.../text/` | `TextModel` (LiteRT-LM, GPU then CPU) and `ModelStore` (download) |
| `.../ui/` | 1-bit renderer, procedural sprites, dithered viewfinder |
| `.../log/Telemetry.kt` | Structured dev logging with batched upload |
| `Game.kt` | The state machine that ties it together |
| `tools/vocab/` | Vocabulary source, embedding builder, photo test |
| `tools/model/` | Runs the app's prompts through the real model for evaluation |

## Build

Needs JDK 21 and the Android SDK (platform 36). `local.properties` points at the SDK.

```
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # brain, parser and safety tests
```

Builds are signed with `keys/dev.keystore`, a throwaway dev key kept in this
private repo so each build installs over the last one and the creature survives
updates. Never use it for a store release.

## Logs

Dev builds stream structured events (button presses, perception scores, prompts,
raw model output, every safety check, timings, vitals, crashes) to the
`curiobot_logs` table in Supabase via the insert-only `curiobot_ingest` function.
Events queue on the phone and upload when online. Toggle upload in the dev panel
(hold the screen).

## Rebuilding the vocabulary

```
python -I tools/vocab/build_vocab.py <mobileclip_s0 dir> app/src/main/assets
python -I tools/vocab/test_vision.py <vision_model.onnx> app/src/main/assets photos/*.jpg
```

## Checking prompts against the real model

```
./gradlew :app:testDebugUnitTest --tests '*ModelLoopTest.exportFixtures'
python -I tools/model/run_fixtures.py <model.litertlm> app/build/prompt_fixtures.json app/build/model_outputs.json
./gradlew :app:testDebugUnitTest --tests '*ModelLoopTest.evaluateOutputs' --rerun
cat app/build/model_eval.txt
```

## Models and licenses

- Text: [Qwen3-0.6B INT4 LiteRT-LM](https://huggingface.co/litert-community/Qwen3-0.6B-int4) (Apache 2.0)
- Vision: [MobileCLIP-S0 ONNX](https://huggingface.co/Xenova/mobileclip_s0) (Apple sample code license; see the model card)
