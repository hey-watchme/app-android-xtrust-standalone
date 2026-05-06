# Android Standalone ZeroTouch PoC Handoff

Date: 2026-05-05 JST

This directory is reserved for a new standalone Android PoC that is independent
from WatchMe / ZeroTouch cloud infrastructure.

## Current status

Current implementation status and restart notes are tracked in:

- `docs/current-state.md`
- `docs/session-log.md`
- `docs/asr-plan.md`

## Validation Conclusion (2026-05-07)

現時点の edge-device 検証は、いったんここまでとする。

- 4GB RAM クラス端末では `Gemma 4 E2B` の常用は厳しい
  - モデルロード自体は可能でも、端末全体のメモリ圧迫が強い
  - swap 使用量が大きく、UI / IME / install まで重くなりやすい
- `Bonsai` は 4GB 端末向け runtime としては成立するが、
  `AI議事録` の wrap-up / summary 用途では思考力が不足する
- したがって、この PoC の次の選択肢は次のいずれかになる
  - より高メモリ・高性能な Android 端末へ乗り換える
  - laptop / desktop を使うローカル版へ寄せる
  - 当面は edge-only を見送り、クラウド API を使う構成へ戻す

今回の結論としては、**この 4GB 端末では standalone local LLM 議事録の継続検証は
優先度を下げる**。

次の開発フェーズでは、この端末上では `ZeroTouch` 側へ戻し、**API ベースの議事録ツール**
として継続実装する。

## Build Flavors (LLM runtime)

この PoC は「同一 APK に複数ランタイム同梱」を避けるため、LLM runtime を build flavor で分離します（4GB 端末前提）。

Android Studio の `Build Variants` では、次の 4 variant として見えます。

- `gemmaDebug`
- `gemmaRelease`
- `bonsaiDebug`
- `bonsaiRelease`

意味としては `flavor (gemma / bonsai) x build type (debug / release)` です。
ここで切り替えているのは「同じアプリの内部設定」ではなく、`applicationIdSuffix`
が異なる **別アプリ** です。

- `gemmaDebug`
  - `applicationId`: `com.xtrust.standalone.gemma`
  - runtime: LiteRT-LM
  - model path: `/sdcard/Android/data/com.xtrust.standalone.gemma/files/models/gemma-4-E2B-it.litertlm`
- `bonsaiDebug`
  - `applicationId`: `com.xtrust.standalone.bonsai`
  - runtime: llama.cpp (GGUF)
  - default model path: `/sdcard/Android/data/com.xtrust.standalone.bonsai/files/models/Bonsai-8B.gguf`
  - fallback model path: `/sdcard/Android/data/com.xtrust.standalone.bonsai/files/models/Bonsai-1.7B-Q1_0.gguf`

Important: `gemma` / `bonsai` は **別アプリ** です（`applicationIdSuffix`）。
そのため、次は **相互に共有されません**（見えません）。

- 録音セッション（DB）
- チャットスレッド（DB）
- ASR モデル配置（`files/asr/`）
- LLM モデル配置（`files/models/`）
- 収録 wav（`files/audio-segments/`）

ただし、これは「どちらか片方しか使えない」という意味ではありません。
`gemma` と `bonsai` は side-by-side でインストール・起動できます。

- `bonsai` を試した後に `gemma` へ戻す
  - Android Studio なら `Build Variants` で `gemmaDebug` を選んで Run
  - adb なら `app-gemma-debug.apk` を install して `xtrust Gemma` を起動
- `gemma` を使った後に `bonsai` へ戻す
  - 同様に `bonsaiDebug` を選んで Run

切り替え時に必要なのは「対象 flavor の APK」と「その flavor 用のモデルファイル」です。
もう片方のアプリやモデルを消さない限り、再導入は不要です。

ビルド:

```bash
./gradlew :app:assembleGemmaDebug
./gradlew :app:assembleBonsaiDebug
```

Bonsai の導入と実機ベンチ手順は `docs/bonsai-runtime.md` を参照してください。

## Local Data Locations

- External files root: `/sdcard/Android/data/<package>/files/`
  - LLM models: `files/models/`
  - ASR models: `files/asr/`
  - audio segments: `files/audio-segments/`
- SQLite DB: `xtrust-standalone.db`（各アプリの内部領域に保存）
  - debug build でのみ確認する例:

```bash
adb shell run-as com.xtrust.standalone.bonsai ls -la databases
adb shell run-as com.xtrust.standalone.gemma ls -la databases
```

## Model Quality Check on Mac (llama.cpp)

Android 端末が低スペックでも「モデル品質だけ」先に確認したい場合は、Mac 上で `llama.cpp` を直接実行できます。

前提:

- Mac: `~/models/bonsai-8b/Bonsai-8B.gguf`
- Mac: `~/models/bonsai-1.7b-q1_0/Bonsai-1.7B-Q1_0.gguf`

例（Metal を使ってビルドして実行）:

```bash
git clone https://github.com/ggml-org/llama.cpp.git
cd llama.cpp
cmake -B build -DGGML_METAL=ON
cmake --build build -j
./build/bin/llama-cli -m ~/models/bonsai-8b/Bonsai-8B.gguf -n 320 --temp 0.5 --top-k 20 --top-p 0.9 -p "こんにちは。3行で自己紹介してください。"
```

## UI Design (2026-05-04)

Applied Notion-style modern design system to the app:

### Design System
- **Color palette**: Monochrome with semantic tokens (`Sidebar*`, `Surface*`, `Text*`, `Divider*`, `Status*`)
- **Typography**: Tight line heights and letter spacing for a clean, modern look
- **Spacing & Radius**: Semantic constants (`Spacing.*`, `Radius.*`, `Sizes.*`) for consistency
- **Dynamic color**: Disabled in favor of a consistent design language

### UI Components
- **Navigation**: 64dp black icon-only sidebar with rounded selection highlight (located at left edge)
- **App Shell**: Custom `XtrustSidebar` with primary items (Home, Chat) and secondary items (Settings) pinned at bottom
- **Lists**: Hairline-divided Notion-style lists (no card borders; structure via whitespace and dividers)
- **Buttons**: Rounded primary buttons with `AccentPrimary` (dark); outlined buttons with hairline borders
- **Cards & Status**: Subtle background colors with muted dividers; status indicators using color dots (red for recording, green for ready)

### Screens
- **HomeScreen (AI議事録)**: 
  - Recording control with status indicator
  - Utterance list with time badges and metadata
  - Minutes list with item counts and chevrons
  - Detail drawer for session inspection
- **ChatScreen**: 
  - User bubbles in dark (`AccentPrimary`) background
  - Assistant bubbles in subtle gray
  - Outlined text input with hairline border
- **SettingsScreen**: 
  - Semantic sections with small uppercase labels
  - Monospace code blocks for paths and commands
  - Status indicators (green dot = ready, gray = not ready)

### Implementation Notes
- All hardcoded colors and dimensions replaced with theme tokens
- Material 3 components retained but restyled (colors, shapes, elevation adjusted)
- Composable structure and state management unchanged; styling-only refactor
- Build verified: `./gradlew :app:assembleBonsaiDebug` successful

## Goal

Build a local-first Android app for strict-security sites where audio,
transcription, summarization, and local knowledge extraction can run on the
device without sending data to cloud APIs.

This should be treated as a separate product line from the current WatchMe
ZeroTouch Android app.

## Decision

Create a clean Android app here:

```text
/Users/kaya.matsumoto/projects/xtrust/app/android-standalone/
```

Use the existing WatchMe ZeroTouch Android project as a reference only:

```text
/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/
```

Do not extend the existing ZeroTouch app for this PoC. The current app already
depends on Supabase Auth, ZeroTouch backend APIs, S3 upload, realtime
transcription, translation, WebView surfaces, and cloud-backed topic/wiki flows.
Mixing local-only execution into that app would increase complexity and make it
harder to prove that the standalone version never communicates externally.

## Product Boundary

The standalone app should be able to prove a stricter security posture than the
cloud app.

Initial target:

- No Supabase Auth
- No S3 upload
- No `https://api.hey-watch.me`
- No Google login
- No cloud ASR provider
- No cloud LLM provider
- Prefer no `INTERNET` permission in the standalone build
- Store data locally with Room / SQLite
- Store audio files locally on-device

The existing ZeroTouch app remains the cloud-connected reference product.

## Existing Project References

Read these files from the current project before implementation:

```text
/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/README.md
/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/docs/README.md
/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/docs/conversation-visualization-pipeline.md
/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/docs/live-support.md
```

Likely useful implementation references:

```text
/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/app/src/main/java/com/subbrain/zerotouch/audio/ambient/
/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/app/src/main/java/com/subbrain/zerotouch/ui/SettingsScreen.kt
/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/app/src/main/java/com/subbrain/zerotouch/ui/HomeDashboardScreen.kt
/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/app/src/main/java/com/subbrain/zerotouch/ui/ZeroTouchViewModel.kt
```

Reference, but do not copy cloud dependencies:

```text
/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/app/src/main/java/com/subbrain/zerotouch/api/
/Users/kaya.matsumoto/projects/watchme/app/android-zero-touch/backend/
```

## First PoC Scope

Build only one vertical path first:

```text
Record audio
  -> local ASR
  -> save Card locally
  -> local LLM topic summary
  -> display on-device timeline/topic UI
```

Do not start with Wiki, Action Candidate, Connector, account/workspace
management, Web dashboard, sharing, or cloud sync.

The purpose of the first PoC is to answer:

- Can a target Android tablet run local ASR fast enough?
- Can a target Android tablet run local Gemma summarization fast enough?
- Is the output quality acceptable for the Conversation path?
- Is the battery/thermal behavior acceptable for real site usage?
- Can the app operate without network permission?

## Local LLM Findings

Local LLM on Android is feasible.

Preferred path:

- Runtime: Google LiteRT-LM
- Model family: Gemma 4, starting with E2B
- Test next: Gemma 4 E4B on higher-memory devices

Google released Gemma 4 on 2026-03-31 in E2B, E4B, 31B, and 26B A4B sizes.
The small E2B/E4B models are intended for ultra-mobile, edge, and browser
deployment.

Approximate Gemma 4 memory requirements from Google documentation:

| Model | Q4_0 memory estimate |
| --- | ---: |
| Gemma 4 E2B | 3.2 GB |
| Gemma 4 E4B | 5 GB |
| Gemma 4 31B | 17.4 GB |
| Gemma 4 26B A4B | 15.6 GB |

Practical implication:

- Start with E2B on Android.
- Use E4B only on high-memory tablets.
- Do not target 31B or 26B A4B for normal Android tablets.

Official references:

- Gemma overview: https://ai.google.dev/gemma/docs
- Gemma 4 overview: https://ai.google.dev/gemma/docs/core
- Gemma releases: https://ai.google.dev/gemma/docs/releases
- LiteRT-LM Android guide: https://ai.google.dev/edge/litert-lm/android
- Gemma terms: https://ai.google.dev/gemma/terms

Important licensing note:

Gemma distributions need to comply with Google's terms. The terms require a
notice file for distributions other than hosted service distribution. Confirm
the latest terms before bundling models in an APK or distributing model files.

## Runtime Options Considered

Preferred:

- LiteRT-LM: Google-supported Android Kotlin API, supports Android/JVM, GPU/NPU
  options, multimodality, and tool use.

Possible fallback:

- llama.cpp: flexible GGUF ecosystem, Android builds are possible, but native
  integration and performance tuning are more manual.
- MLC LLM: Android deployment exists, but operationally heavier for this PoC.

Avoid as the primary path:

- MediaPipe LLM Inference API for Android/iOS. Google documentation now marks
  the mobile implementation deprecated and recommends LiteRT-LM.

References:

- LiteRT-LM GitHub: https://github.com/google-ai-edge/LiteRT-LM
- llama.cpp Android docs: https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md
- MLC LLM Android docs: https://llm.mlc.ai/docs/deploy/android
- MediaPipe LLM Inference: https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference

## Local ASR Findings

The current WatchMe ZeroTouch app uses cloud ASR providers through backend APIs.
That must be replaced for standalone.

Candidate options:

- sherpa-onnx: strong candidate for real-time local ASR on Android.
- whisper.cpp: viable local ASR option with Android sample, but integration and
  performance tuning need testing.
- Android `SpeechRecognizer.createOnDeviceSpeechRecognizer`: useful fallback
  only when device support is available, but it is OS/device/language-pack
  dependent.

References:

- sherpa-onnx Android docs: https://k2-fsa.github.io/sherpa/onnx/android/index.html
- whisper.cpp Android sample: https://github.com/ggml-org/whisper.cpp/tree/master/examples/whisper.android
- Android SpeechRecognizer: https://developer.android.com/reference/android/speech/SpeechRecognizer.html

Working plan for the standalone ASR phase:

- `docs/asr-plan.md`

## Suggested Architecture

Keep the first implementation intentionally small:

```text
ui/
  HomeScreen.kt
  SettingsScreen.kt

audio/
  Recorder.kt
  VadSegmenter.kt

asr/
  LocalAsrEngine.kt
  SherpaOnnxAsrEngine.kt
  WhisperCppAsrEngine.kt

llm/
  LocalLlmEngine.kt
  LiteRtGemmaEngine.kt

data/
  AppDatabase.kt
  CardEntity.kt
  TopicEntity.kt
  TranscriptRepository.kt

pipeline/
  ConversationPipeline.kt
```

Initial local schema:

```text
cards
  id
  local_recording_id
  audio_path
  transcript
  asr_provider
  asr_model
  recorded_at
  created_at

topics
  id
  title
  summary
  start_at
  end_at
  llm_provider
  llm_model
  created_at
```

## Settings UI Direction

The existing app already has provider chips in the settings sheet. The
standalone app can use the same concept, but the available choices should be
local-only.

Initial settings:

- ASR engine: `sherpa-onnx`, `whisper.cpp`, `Android on-device`
- LLM engine: `Gemma 4 E2B`, `Gemma 4 E4B`
- Model path: local file picker or managed app model directory
- VAD engine: `Threshold VAD` as the default and required local path
- Data mode: local-only

In a strict standalone build, do not show cloud providers as disabled options.
The absence of cloud choices is part of the security story.

VAD policy for this standalone app:

- Do **not** add an `ONNX`-based VAD into this app process.
- `ASR` already uses `sherpa-onnx`, and sharing or coexisting multiple `ONNX Runtime`
  stacks in one Android app created native/JNI conflicts in practice.
- Keep `VAD` independent from `ASR` runtime choices so `ASR` model or runtime
  changes do not destabilize microphone segmentation.
- If a stronger VAD is needed later, prefer a non-`ONNX` local implementation or
  a strictly separate process / separate app boundary.

## What To Avoid Importing

Do not copy these concepts from the existing Android app into the first PoC:

- Supabase authentication
- Google login
- Account/workspace organization flows
- `ZeroTouchApi`
- S3 upload
- `/api/transcribe/*`
- `/api/translate/*`
- `/api/query-wiki`
- Web dashboard integration
- Remote device settings sync

The standalone version should own local settings directly.

## Open Questions

- Target tablet model and RAM size
- Whether the deployment site allows installing model files separately
- Whether the model must be bundled in APK/AAB or can be side-loaded
- Required Japanese ASR quality threshold
- Expected recording duration per session
- Whether diarization is required in the first PoC
- Whether `INTERNET` permission must be absent, or merely unused

## Recommended Next Step

Create a minimal Android project in this directory and verify these in order:

1. App builds and runs with no `INTERNET` permission.
2. Local recording creates audio files on-device.
3. Local ASR produces Japanese transcript for a short sample.
4. LiteRT-LM loads Gemma 4 E2B from local storage.
5. Local LLM summarizes one transcript into a Topic title and summary.
6. Room/SQLite persists Card and Topic records.
