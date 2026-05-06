# Android Standalone — セッションログ / 再開ガイド

最終更新: 2026-05-05

---

## 現在の状態（2026-05-04）

| 項目 | 詳細 |
|------|------|
| Androidプロジェクト作成 | Android Studio で `com.xtrust.standalone` パッケージ作成済み |
| Gradle設定 | AGP 9.1.1 / Kotlin 2.2.10 / Compose BOM 2025.05.00 |
| LiteRT-LM 依存追加 | `com.google.ai.edge.litertlm:litertlm-android:latest.release` |
| INTERNET権限なし確認 | APKダンプで `INTERNET` パーミッション不在を確認済み |
| アーキテクチャ骨格 | LLM / データ / UI 実装済み（ローカル DB は `SQLiteOpenHelper` で接続済み） |
| LiteRT-LM API実装 | `LiteRtGemmaEngine.kt` に Engine/Conversation API 実装済み |
| Gemma 4 E2B ダウンロード | `~/models/gemma4-e2b/gemma-4-E2B-it.litertlm` (2.4GB) |
| **モデルロード成功** | **Xiaomi 4GB RAM で 17秒ロード・Engine ready 確認済み** |
| 起動時自動ロード | モデルファイルが既定パスにありサイズが十分なら自動で `Load` 実行 |
| 手動要約テストUI | Home 画面に transcript 入力欄と `Summarize locally` ボタンを追加 |
| multi-turn チャット | `Conversation` を保持してローカル会話を継続できるように変更 |
| メモリ可視化 | Home 画面に Device RAM / App heap / Native heap の使用量表示を追加 |
| ASR 作業計画 | `docs/asr-plan.md` に `sherpa-onnx` 前提の段階計画を追加 |
| VAD 足場実装 | `ThresholdVadEngine` + `MicrophoneVadMonitor` でローカル発話検知デバッグ UI を追加 |
| VAD セグメント保存 | 発話終了ごとに `wav` をローカル保存し、Home 画面に直近セグメント一覧を表示 |
| ファイル入力 ASR | `sherpa-onnx-1.12.39.aar` と SenseVoice 前提で保存済み `wav` を `Transcribe` 可能にした |
| VAD 再調整 | 発話継続側を甘くし、900ms 未満の短片を破棄して分割過多を抑制 |
| ASR パス自動検出 | `files/asr/` 配下の既知 SenseVoice フォルダを自動検出し、2024/2025 両配置を拾えるように変更 |
| ASR 権限診断 | Settings に `exists/read/exec` と `chmod 777` 手順を表示し、LLM と同種の shell-owner 問題を切り分け可能にした |
| VAD 無音猶予延長 | 分割判定の無音時間を約 2.4 秒まで延ばし、1 秒前後の自然な間では切れにくくした |
| 自動 ASR | 保存済み `wav` セグメントを自動で順次文字起こしするように変更 |
| ローカル DB | `SQLiteOpenHelper` ベースで `sessions / cards / topics` をローカル保存する構造に切り替え |
| セッション単位 | `Start VAD` から `Stop VAD` を 1 セッションとして扱い、Home に履歴を表示 |

---

## プロジェクト構成

```
android-standalone/
├── app/build.gradle.kts
├── gradle/libs.versions.toml
├── app/src/main/
│   ├── AndroidManifest.xml           INTERNET なし / RECORD_AUDIO あり
│   └── java/com/xtrust/standalone/
│       ├── MainActivity.kt           BottomNav (Home / Settings)
│       ├── ui/
│       │   ├── HomeScreen.kt         トピック一覧
│       │   ├── SettingsScreen.kt     モデルロード UI
│       │   └── XtrustViewModel.kt    ファイル存在チェック + LLMロード
│       ├── llm/
│       │   ├── LocalLlmEngine.kt     interface
│       │   └── LiteRtGemmaEngine.kt  LiteRT-LM 実装
│       └── data/
│           ├── AppDatabaseHelper.kt
│           ├── RecordingSessionEntity.kt
│           ├── CardEntity.kt
│           ├── TopicEntity.kt
│           └── TranscriptRepository.kt
└── docs/
    ├── current-state.md
    ├── session-log.md
    └── asr-plan.md
```

---

## モデルファイルの配置

```
Mac: ~/models/gemma4-e2b/gemma-4-E2B-it.litertlm  (2.4GB)

デバイス（gemmaDebug）: /sdcard/Android/data/com.xtrust.standalone.gemma/files/models/gemma-4-E2B-it.litertlm
```

### adb push 手順（再転送・新端末セットアップ時）

```bash
# 1. ディレクトリ作成
adb shell mkdir -p /sdcard/Android/data/com.xtrust.standalone.gemma/files/models/

# 2. ファイル転送（2.4GB、USB で 5〜10 分）
adb push ~/models/gemma4-e2b/gemma-4-E2B-it.litertlm \
  /sdcard/Android/data/com.xtrust.standalone.gemma/files/models/gemma-4-E2B-it.litertlm

# 3. 転送完了確認（2500000000 以上になれば完了）
adb shell stat -c%s /sdcard/Android/data/com.xtrust.standalone.gemma/files/models/gemma-4-E2B-it.litertlm

# 4. 【必須】ディレクトリ権限修正（これをしないとアプリがファイルを読めない）
adb shell chmod 777 /sdcard/Android/data/com.xtrust.standalone.gemma/files/models/
```

---

## LiteRT-LM API（実装済み）

```kotlin
// Engine 初期化（IO スレッド必須、約 17 秒）
val engine = Engine(EngineConfig(modelPath = "/path/to/model.litertlm", backend = Backend.CPU()))
engine.initialize()

// 推論（Flow ストリーミング）
engine.createConversation(ConversationConfig(
    systemInstruction = Contents.of("You are a concise assistant.")
)).use { conversation ->
    conversation.sendMessageAsync(Contents.of(prompt)).collect { append(it) }
}
```

## 現在のUI動作

- Settings 画面は起動時の自動ロードに加えて `Reload Gemma 4 E2B` ボタンを保持
- Home 画面で ChatGPT 風のローカルチャットを実行可能
- Home 画面で `Device RAM x / y MB`、`App heap`、`Native heap` を約 1.5 秒ごとに更新表示
- `New chat` で会話コンテキストをリセット可能
- モデルファイルが無い、または 1GB 未満で不完全な場合は自動ロードせず待機
- Home 画面で `Recording sessions` として直近セッション履歴を確認できる

## ASR の次フェーズ

- `docs/asr-plan.md` に edge-only ASR PoC の段階計画を追加
- 第一候補は `sherpa-onnx`
- まずは `Stage 0: 公式 APK による端末ベンチ` と `Stage 1: ファイル入力 ASR` から進める

## 現在の VAD 実装

- `vad/LocalVadEngine.kt` を追加して VAD 実装を差し替え可能な構造にした
- `vad/ThresholdVadEngine.kt` で `ZeroTouch` 由来の閾値ベース VAD を standalone 用に実装
- `audio/MicrophoneVadMonitor.kt` でマイクから 16kHz mono PCM を読み取り、VAD に渡す形を追加
- Home 画面に `Start VAD` / `Stop VAD`、`Speech detected`、`dBFS`、検出セグメント数、最後の発話長を表示
- これは `sherpa-onnx` 導入前の足場であり、本命の VAD 実装とは切り替え前提
- 発話終了時に `audio-segments/segment-*.wav` として保存し、直近 12 件を Home 画面で確認できる
- `Clear list` で画面上の直近セグメント一覧だけをリセットできる（DB と wav は保持）
- 継続判定の閾値を開始判定より低くするヒステリシスを追加し、短い無音で分割されにくくした
- 900ms 未満の短い断片は保存しないようにして、1語ごとの細切れ wav を減らした
- 無音が約 2.4 秒続いたときだけ分割するように変更し、通常会話の短い間では継続扱いにした

## 現在の ASR 実装

- `app/libs/sherpa-onnx-1.12.39.aar` を公式 release から追加
- `asr/SherpaOnnxAsrEngine.kt` で `OfflineRecognizer` を使うファイル入力 ASR を実装
- 保存済み `wav` セグメントごとに `Transcribe` ボタンを追加
- 結果テキスト、推論時間、`RTF` を Home 画面に表示
- 既定のモデル配置先は `files/asr/` 配下で、次を自動検出する:
  - `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/`
  - `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/`
  - そのほか `model.int8.onnx` と `tokens.txt` を持つ任意の子ディレクトリ
- 現在は `SenseVoice` を `language = "ja"`、`provider = "cpu"` でロードする
- Settings に ASR モデルディレクトリの `exists/read/exec`、モデルファイル読取可否、サイズを表示
- shell 所有ディレクトリに `adb push` した場合に備え、`chmod 777` の回復コマンドも Settings に表示
- 保存されたセグメントは手動ボタンを待たず、そのまま自動で ASR に投入する

## 現在の DB 実装

- `Room` ではなく `SQLiteOpenHelper` を使ってローカル DB を実装
- テーブルは `sessions` / `cards` / `topics`
- `Start VAD` 時に `recording` セッションを作成
- `Stop VAD` 時にそのセッションを `completed` に更新
- セグメント保存時に `cards` へ保存し、ASR 完了後に transcript / RTF / 推論時間を更新
- アプリ再起動時には `recording` のまま残った空セッションは破棄し、発言があるセッションは `completed` に回復する

## 次のマイルストーン

- セッション単位でセグメント群をまとめてローカル要約する
- `topics` をセッションに紐づけて Home に表示する
- 1回の録音終了後に、要約まで自動で完走する縦串を作る

---

## 苦戦した知見・トラブルシューティング

### 1. adb push したファイルをアプリが読めない（最大の落とし穴）

**症状**: `adb push` が成功し、ファイルも存在するのにアプリが `exists=false` を返す。

**原因**: `adb shell mkdir` や `adb push` はシェルユーザー（`shell`）として実行される。
作成されたディレクトリのオーナーが `shell` になり、Androidのスコープドストレージ + SELinux の組み合わせで
アプリユーザー（`u0_a3xx`）がディレクトリをトラバースできなくなる。

ファイル自体のパーミッションは `rw-rw-rw-`（誰でも読める）でも、
**親ディレクトリのパーミッションが `drwxrws---`（others 禁止）** だとアクセス不可。

```bash
# 確認コマンド（owner が shell になっていたらアウト）
adb shell ls -la /sdcard/Android/data/com.xtrust.standalone.gemma/files/

# drwxrws--- 2 shell ext_data_rw ... models  ← これがダメ
```

**解決策**: push 後に必ず chmod 777 を実行する。

```bash
adb shell chmod 777 /sdcard/Android/data/com.xtrust.standalone.gemma/files/models/
```

**将来の恒久対策案**:
- アプリ起動時に自分でディレクトリを作成するコードを入れる（オーナーがアプリになる）
- その後 `adb push` するとファイルは shell 所有になるが、ディレクトリは app 所有なので通れる

```kotlin
// XtrustViewModel の初期化時に実行すれば push 先ディレクトリが app 所有になる
File(getExternalFilesDir(null), "models").mkdirs()
```

---

### 2. Gradle / Kotlin プラグインのハマりどころ

#### `org.jetbrains.kotlin.android` は AGP 9.x では不要（むしろ競合する）

**症状**: `Cannot add extension with name 'kotlin', as there is an extension already registered`

**原因**: AGP 9.x の `com.android.application` が Kotlin コンパイルを内包している。
`org.jetbrains.kotlin.android` を追加すると二重登録でクラッシュ。

**解決策**: `app/build.gradle.kts` に `kotlin.compose` だけ書く。`kotlin.android` は書かない。

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)  // これだけでOK
    // alias(libs.plugins.kotlin.android)  ← 書かない
}
```

#### `kotlinOptions` は `kotlin.android` なしでは使えない

**症状**: `Unresolved reference 'kotlinOptions'`

**解決策**: `compileOptions` で Java バージョンだけ指定する。

```kotlin
compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
// kotlinOptions { jvmTarget = "17" }  ← 書かない
```

#### KSP のバージョンは Kotlin バージョンに連動している

**症状**: `Plugin [id: 'com.google.devtools.ksp', version: '2.2.10-1.0.29'] was not found`

**原因**: KSP のバージョン番号は `<Kotlin バージョン>-<KSP パッチ>` の形式。
Kotlin 2.2.10 に対応する KSP がまだ存在しないか、バージョン番号が違う。

**暫定対応**: Room / KSP を一旦外してビルドを通す。データはインメモリで代替。
次回再開時に `https://github.com/google/ksp/releases` で Kotlin 2.2.10 対応版を確認する。

---

### 3. XML テーマの参照エラー

**症状**: `resource style/Theme.Material3.DayNight.NoActionBar not found`

**原因**: Compose アプリで XML テーマに `Theme.Material3.*` を使うと、
Material3 の View ライブラリが必要になるが、Compose 専用の build.gradle.kts には含まれていない。

**解決策**: XML テーマは Android 組み込みテーマを使う。Compose 側のカラー設定は `Theme.kt` で行う。

```xml
<!-- values/themes.xml -->
<style name="Theme.XtrustStandalone" parent="android:Theme.Material.Light.NoActionBar" />
```

---

### 4. モデル転送中にロードしてしまう問題

**症状**: `adb push` 完了前にアプリで「Load」を押すと「ファイルが見つかりません」エラー（転送中はファイルサイズが小さく見える）。

**解決策**: ViewModel でファイルサイズを確認してからロードする。

```kotlin
when {
    !file.exists() -> "モデルファイルが見つかりません。管理者に配置を依頼してください。"
    file.length() < 1_000_000_000L -> "モデルファイルが不完全です（${file.length() / 1_000_000}MB）。転送完了後に再試行してください。"
    else -> { /* ロード実行 */ }
}
```

---

## 次にやること

### 優先度 高

1. **推論テスト**
   - テキスト入力 → `summarizeTranscript()` を呼ぶ UI を Home 画面に追加
   - LLM がトピック要約を返し、Home にカードが表示されることを確認

2. **GPU バックエンドの試行**
   - `Backend.CPU()` → `Backend.GPU()` に変更してビルド・速度比較

3. **アプリ初回起動時にモデルディレクトリを自作する** ✅ 完了
   - `XtrustViewModel` 初期化時に `File(dir, "models").mkdirs()` 追加済み
   - アプリ起動後に `adb push` すれば `chmod 777` は不要

### 優先度 中

4. **Room / KSP 追加（永続化）**
   - `https://github.com/google/ksp/releases` で Kotlin 2.2.10 対応 KSP バージョンを確認
   - `CardEntity` / `TopicEntity` に `@Entity` 追加、`AppDatabase` 作成

5. **音声録音の実装**
   - `audio/Recorder.kt` — MediaRecorder で録音
   - 録音完了 → `CardEntity` に保存 → ASR → LLM 要約の縦串パイプライン

6. **ローカル ASR の実装**
   - sherpa-onnx（第一候補）または Android on-device SpeechRecognizer

### 優先度 低

7. NPU バックエンドの検討（Xiaomi の SoC が対応している場合）
8. Gemma 4 E4B テスト（RAM 6GB 以上の端末）
9. Wiki / Action Candidate / Connector 機能（PoC 完了後）

---

## 環境情報

| 項目 | 値 |
|------|-----|
| Gradle | 9.3.1 |
| AGP | 9.1.1 |
| Kotlin | 2.2.10 |
| compileSdk | 36 (Android 16) |
| minSdk | 31 (Android 12) |
| テスト端末 | Xiaomi 23073RPBFG (4GB RAM) |
| モデル | Gemma 4 E2B IT (`litert-community/gemma-4-E2B-it-litert-lm`) |
| モデル形式 | `.litertlm` (LiteRT-LM 専用) |
| ランタイム | `com.google.ai.edge.litertlm:litertlm-android:latest.release` |
| ロード時間 | 約 17 秒（CPU バックエンド） |

---

## 再開時のコマンド集

```bash
cd /Users/kaya.matsumoto/projects/xtrust/app/android-standalone

# ビルド
./gradlew :app:assembleGemmaDebug

# デバイス確認（WiFi ADB）
adb devices

# インストール
adb install -r app/build/outputs/apk/gemma/debug/app-gemma-debug.apk

# ログ確認（LLMエンジン + ViewModel）
adb logcat -s LiteRtGemmaEngine XtrustVM

# モデルファイル確認
adb shell stat -c%s /sdcard/Android/data/com.xtrust.standalone.gemma/files/models/gemma-4-E2B-it.litertlm

# 権限修正（新端末セットアップ時や push し直した後に必要）
adb shell chmod 777 /sdcard/Android/data/com.xtrust.standalone.gemma/files/models/
```

---

## 追記（2026-05-05）: flavor は別アプリ

`gemma` と `bonsai` は `applicationIdSuffix` により別アプリ扱い。
そのため、モデル配置先もデータ保存も別々になる。

- `gemmaDebug`: `com.xtrust.standalone.gemma`
- `bonsaiDebug`: `com.xtrust.standalone.bonsai`

例（Bonsai 8B 配置）:

```bash
adb shell mkdir -p /sdcard/Android/data/com.xtrust.standalone.bonsai/files/models/
adb push ~/models/bonsai-8b/Bonsai-8B.gguf \
  /sdcard/Android/data/com.xtrust.standalone.bonsai/files/models/Bonsai-8B.gguf
adb shell chmod 777 /sdcard/Android/data/com.xtrust.standalone.bonsai/files/models/
```
