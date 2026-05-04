# Android Standalone ASR Plan

Date: 2026-05-04 JST

## Objective

Add local ASR to the standalone Android PoC so the app can evaluate what is
possible on-device for XTRUST without any cloud dependency.

This ASR work is not only for transcription quality. It is the entry point for
an edge-only speech pipeline:

```text
microphone
  -> VAD
  -> short speech segments
  -> local ASR
  -> local cards
  -> keyword / rule filtering
  -> optional local LLM summarization
  -> on-device display / export
```

The first goal is to prove that the pipeline is viable on target tablets, not
to maximize transcript quality on day one.

## Decision

Use `sherpa-onnx` as the first ASR integration target.

Why:

- It supports Android and offline / on-device execution.
- It covers more than ASR: `VAD`, `keyword spotting`, `speaker diarization`,
  `speaker identification`, `speaker verification`, and `TTS`.
- It matches the likely XTRUST pipeline better than a single-purpose ASR engine.
- It already provides Android examples and prebuilt APKs that can be used to
  benchmark feasibility before deep integration.

Official references:

- https://k2-fsa.github.io/sherpa/intro.html
- https://k2-fsa.github.io/sherpa/onnx/index.html
- https://k2-fsa.github.io/sherpa/onnx/android/prebuilt-apk.html
- https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html

## Product Intent

This app should be treated as an edge speech / LLM validation workbench.

Key product assumptions:

- Edge-only processing is more important than perfect accuracy.
- The app should make it easy to compare latency, RAM, thermal, and usability
  across model / device combinations.
- Heavier LLM processing should run only on selected segments, not all audio.
- The app should evolve toward a multi-stage local speech pipeline.

## Scope Boundaries

In scope for the next ASR phase:

- Offline microphone capture
- Offline local VAD
- Offline local ASR
- Local storage of audio and transcript segments
- Local metrics display
- Manual transcript review in the app

Out of scope for the first ASR phase:

- Cloud fallback
- Diarization in production quality
- Perfect punctuation / formatting
- Long-session end-to-end automation
- Final export workflow
- Connector / sharing / sync behavior

## Recommended Rollout

### Stage 0: Feasibility benchmark

Goal:

- Confirm that a target device can run a local Japanese ASR model at acceptable
  speed and memory.

Tasks:

- Use official `sherpa-onnx` Android APKs to benchmark before coding.
- Prepare 3 reference Japanese audio files:
  - short clean sample
  - longer meeting-like sample
  - noisy real-site sample
- Record baseline metrics for each sample on the target tablet.

Output:

- A simple comparison table by device and model.

### Stage 1: File-based ASR integration

Goal:

- Add one local ASR engine to this app and transcribe a saved local audio file.

Tasks:

- Add `asr/LocalAsrEngine.kt`
- Add `asr/SherpaOnnxAsrEngine.kt`
- Add minimal model-path configuration in Settings
- Add one debug action that runs ASR on a local test file
- Display transcript text and metrics in the app

Success criteria:

- One local audio file can be transcribed offline.
- Transcript and metrics appear in UI.
- No network permission is added.

### Stage 2: Microphone capture and VAD

Goal:

- Capture live audio and cut it into speech-only segments.

Tasks:

- Add `audio/Recorder.kt`
- Add `vad/LocalVadEngine.kt`
- Add `vad/SherpaOnnxVadEngine.kt`
- Save raw audio and VAD segments locally
- Display segment start / end timestamps in UI

Success criteria:

- Audio recording works on target device.
- VAD suppresses most silence.
- Short speech segments can be passed to ASR.

### Stage 3: Segment cards

Goal:

- Represent speech as local segment cards rather than one giant transcript.

Tasks:

- Add `AsrSegment` model
- Save `audioPath`, `startAt`, `endAt`, `durationMs`, `transcript`, `engine`,
  `model`, and metrics
- Display cards in timeline order

Success criteria:

- A session produces multiple local cards.
- Cards can be inspected before LLM summarization.

### Stage 4: Lightweight filtering and summarization handoff

Goal:

- Avoid sending every segment into the LLM.

Tasks:

- Add rule filters:
  - minimum segment length
  - minimum text length
  - keyword match
  - manual pin / keep
- Batch selected cards into local LLM summarization

Success criteria:

- The app can choose only selected local ASR segments for Gemma summarization.

### Stage 5: Extended edge speech features

Candidates:

- Keyword spotting
- Speaker identification / diarization
- TTS playback
- Audio tagging
- Printed / screen-safe summary layouts

These should stay behind experiment flags until the ASR baseline is stable.

## Model Strategy

### ASR engine priority

1. `sherpa-onnx`
2. `whisper.cpp` as fallback comparison
3. Android on-device speech recognizer only as a device-dependent fallback

### Japanese-first recommendation

The first PoC should prefer small or quantized Japanese-capable models over
maximum quality models.

Reason:

- 4 GB devices are already under pressure from local Gemma.
- ASR must leave headroom for UI, storage, and optional LLM usage.
- A faster rough transcript is more useful for pipeline testing than a slower
  higher-quality model that destabilizes the device.

Model candidates should be validated from current official sherpa-onnx model
pages before integration. Start with a small Japanese-capable option first, then
compare against a larger or higher-quality candidate only after baseline metrics
exist.

## App Architecture Plan

Recommended next structure:

```text
ui/
  HomeScreen.kt
  SettingsScreen.kt
  SessionScreen.kt
  MetricsCard.kt

audio/
  Recorder.kt
  WavWriter.kt

vad/
  LocalVadEngine.kt
  SherpaOnnxVadEngine.kt

asr/
  LocalAsrEngine.kt
  SherpaOnnxAsrEngine.kt
  AsrMetrics.kt

pipeline/
  EdgeConversationPipeline.kt

data/
  CardEntity.kt
  TopicEntity.kt
  AsrSegment.kt
  TranscriptRepository.kt
```

Design rules:

- Keep engines behind interfaces so models can be swapped.
- Separate recording, VAD, ASR, and summarization into distinct modules.
- Track metrics as first-class data, not logs only.
- Prefer short local segments as the central unit of processing.

## Metrics To Capture

Every ASR run should store or display:

- Device name
- Android version
- ASR engine name
- ASR model name
- Model size if known
- Model load time
- Audio duration
- Processing duration
- Real-time factor `RTF`
- Device RAM used / available
- App heap used / max
- Native heap used
- Peak memory observed during session
- Low-memory signal
- Battery level before / after long run
- Thermal notes if visible

## UI Requirements

The app should expose enough observability to evaluate edge viability.

Add the following UI blocks in the ASR phase:

- ASR engine / model selector
- Model path display
- Record / stop button
- Current state: idle / recording / segmenting / transcribing
- Transcript segment list
- Metrics panel
- Error panel

Future optional UI:

- Segment keep / discard toggle
- Keyword hits
- Manual summarize selected segments

## Technical Risks

- 4 GB devices may not sustain both local ASR and Gemma in one long session.
- Model load / unload behavior may fragment memory over time.
- Real-time microphone streaming may be harder than file-based transcription.
- Japanese accuracy may be acceptable only after model comparison.
- Packaging native libs and model assets may increase APK or installation
  complexity.

## Current Tuning Note

### Japanese spacing issue

Current `sherpa-onnx` + `SenseVoice` output for Japanese sometimes contains
unnatural spaces between short word or character units.

Current assessment:

- This does **not** look like a primary `utterance` / VAD segmentation problem.
- Segmentation can cause sentence-level fragmentation, but it is less likely to
  be the main cause of character-level or short-word spacing.
- The more likely cause is ASR output formatting and missing Japanese-specific
  post-processing after decode.
- In the current app, `result.text` is almost passed through as-is except for
  `trim()`, so any spacing produced by the recognizer is preserved.

Likely causes to verify next:

- `SenseVoice` / `sherpa-onnx` output characteristics for `cjkchar`-style
  modeling units
- Missing equivalent of SenseVoice rich transcription post-process
- Need for Japanese-specific whitespace normalization on-device

Recommended next fixes in order:

1. Add a minimal Japanese whitespace cleanup step after ASR decode.
2. Compare output before / after cleanup on several real recordings.
3. Investigate whether SenseVoice post-processing can be reproduced in the
   Android path.
4. Only after that, revisit VAD / utterance tuning if sentence fragmentation
   still harms readability.

Success criteria for this subtask:

- Japanese transcript no longer contains excessive internal spaces.
- Cleanup does not collapse punctuation or mixed-language content incorrectly.
- Readability improves without materially changing recognition latency.

## Immediate Next Tasks

1. Benchmark official `sherpa-onnx` Android APKs on the target tablet.
2. Choose the first Japanese-capable small / quantized model candidate.
3. Add `LocalAsrEngine` and `SherpaOnnxAsrEngine` interfaces and skeletons.
4. Implement file-based offline transcription first.
5. Add ASR metrics display to the current home / debug workflow.
6. Only after Stage 1 succeeds, add microphone recording and VAD.

## Definition of Done for First ASR Milestone

The first milestone is done when:

- The app transcribes one local Japanese audio file offline.
- Transcript text is visible in the app.
- Load time, runtime, and memory are visible in the app.
- No cloud API or network permission is added.
- The result is stable enough to compare across devices and models.
