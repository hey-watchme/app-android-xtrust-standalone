# Bonsai Runtime Bring-up

最終更新: 2026-05-05

## 方針

- `gemma` と `bonsai` は build flavor で分離する
- 運用時のモデル切替 UI は前提にしない
- `gemma` build には `LiteRT-LM` だけを含める
- `bonsai` build には `llama.cpp` 系 runtime だけを含める
- 4GB 端末を前提に、同一 APK に 2 系統の runtime を同梱しない

## 現在の flavor

- `gemmaDebug`
  - `applicationId`: `com.xtrust.standalone.gemma`
  - runtime: `LiteRT-LM`
  - model path: `/sdcard/Android/data/com.xtrust.standalone.gemma/files/models/gemma-4-E2B-it.litertlm`

- `bonsaiDebug`
  - `applicationId`: `com.xtrust.standalone.bonsai`
  - runtime: `llama.cpp` を想定
  - default model path: `/sdcard/Android/data/com.xtrust.standalone.bonsai/files/models/Bonsai-8B.gguf`
  - fallback model path: `/sdcard/Android/data/com.xtrust.standalone.bonsai/files/models/Bonsai-1.7B-Q1_0.gguf`

## 取得物

- Hugging Face model: `prism-ml/Bonsai-8B-gguf`
- 推奨ファイル: `Bonsai-8B.gguf`
- 比較用 fallback: `prism-ml/Bonsai-1.7B-gguf` / `Bonsai-1.7B-Q1_0.gguf`
- 配布時に同梱または別添で保持するファイル: `NOTICE.txt`

ローカルへ取得:

```bash
./scripts/download-bonsai-model.sh
./scripts/download-bonsai-model.sh 1.7b
```

## ビルド

```bash
./gradlew assembleGemmaDebug
./gradlew assembleBonsaiDebug
```

## 現在できていること

- Gemma と Bonsai を flavor 単位で分離した
- `bonsaiDebug` は LiteRT 依存なしでビルドできる
- UI/ViewModel は runtime 非依存の共通層へ寄せた
- `bonsai-runtime` module を追加し、`llama.cpp` JNI bridge で GGUF をロードできる
- `Bonsai-8B.gguf` を既定としてチャット送受信まで接続した
- `Bonsai-1.7B-Q1_0.gguf` は比較用 fallback として残した
- 設定画面から `llama.cpp` の system info と簡易ベンチを実行できる
- `<think>...</think>` は system prompt と後処理で抑止する

## 実装メモ

- `app/build.gradle.kts` で `ndk.abiFilters = ["arm64-v8a"]` を指定し、`bonsaiDebug` の APK サイズを約 `544MB -> 262MB` に削減
- `packaging.jniLibs.useLegacyPackaging = true` が必要
  - これがないと Android 13 実機では `nativeLibraryDir` が空になり、`ggml_backend_load_all_from_path()` が CPU backend を見つけられない
  - 症状は `llama_model_load_from_file_impl: no backends are loaded`
- 実機確認では `libggml-cpu-android_armv8.0_1.so` がロードされ、GGUF の初回ロードまで到達する

## 生成設定（現状）

Android 側（Bonsai / llama.cpp）の主なデフォルト:

- context: `4096`
- max tokens: `320`
- sampler: `temp=0.5`, `top_k=20`, `top_p=0.9`
- threads: 端末コア数から `2..4` にクランプ

会話:

- 同一スレッド内は増分チャット（履歴を毎回再プロンプトしない）
- スレッド切替や再起動直後は、必要なら履歴を 1 回だけブートストラップして再開する

## 実機メモ

- 検証日: `2026-05-04`
- 端末: `23073RPBFG`
- モデルロード:
  - logcat 上は backend load 開始が `18:43:47`
  - context 構築完了が `18:43:49`
  - 初回ロードは約 `2.6s`
- 設定画面ベンチ:
  - `pp 256`: `9.68 t/s`
  - `tg 128`: `7.36 t/s`

## まだ未実装

- token streaming を現行チャット UI に逐次表示する
- ベンチ結果の保存と Gemma 側比較 UI
- 実機でのロード時間・応答時間の計測ログ整備

## 次の実装順

1. `bonsaiDebug` APK を実機へ入れる
2. `Bonsai-8B.gguf` を端末へ配置する
3. 設定画面でモデルロードと速度ベンチを実行する
4. チャット画面で応答速度と品質を確認する
5. 必要なら token streaming と比較ログ保存を追加する

## 参照

- Hugging Face 8B: `https://huggingface.co/prism-ml/Bonsai-8B-gguf`
- Hugging Face 1.7B: `https://huggingface.co/prism-ml/Bonsai-1.7B-gguf`
- llama.cpp Android docs: `https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md`
- llama.android sample: `https://github.com/ggml-org/llama.cpp/tree/master/examples/llama.android`
