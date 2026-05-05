# Android Standalone — 現在地と次マイルストーン

最終更新: 2026-05-05

## 現在できること

- `INTERNET` 権限なしで起動する
- `Gemma 4 E2B` / `Bonsai 8B (GGUF)` を端末ローカルでロードして会話する（build flavor で分離）
- マイク入力をローカル `VAD` で監視する
- 発話区間を `wav` に切り出して端末内へ保存する
- 保存された `wav` を `sherpa-onnx SenseVoice` でローカル文字起こしする
- 新しいセグメントは自動で ASR に流れる
- `Start VAD` から `Stop VAD` までを 1 セッションとしてローカル DB に保存する
- セッションごとのセグメント件数と文字起こし件数を Home で確認する
- チャットをスレッド型で保存し、アプリ再起動後に復元して再開できる

## 今の設計判断

- セッション単位は当面 `Start VAD` / `Stop VAD` の手動区切りでよい
- DB はまず `SQLiteOpenHelper` を使う
- `Room` は後回しにする
- セグメントを処理の最小単位にする
- LLM は全文常時処理ではなく、後段の要約専用に置く

## ここまでの学び

### 1. edge-only は成立する

- 4GB クラス端末でも `Gemma 4 E2B` と `SenseVoice int8` の両方が動く
- 快適ではないが、PoC としての成立性は確認できた

### 2. 先に縦串を通す方が正しい

- UI を磨くより先に `録音 -> VAD -> ASR -> 保存` を通した方が、ボトルネックと成立条件が早く見えた
- モデル未配置や権限問題は、後半で気づくより先に潰した方が安い

### 3. Android の外部ストレージ権限は落とし穴

- `adb push` しただけでは読めない場合がある
- `shell` 所有ディレクトリ問題は、LLM でも ASR でも同じ
- app 側で先にディレクトリを作る方が安定する

### 4. 会話用途の VAD はかなり緩くしてよい

- 短い無音で切ると、人間の自然な間でセグメントが増えすぎる
- 現在は約 `2.4s` 無音で分割する設定にしている
- これは議事録 PoC では妥当

### 5. 自動 ASR は体験改善に効く

- 手動 `Transcribe` より、自動で結果が付く方が PoC 評価しやすい
- 次は自動要約まで進めるべき段階に来ている

## 現在の DB スコープ

### `sessions`

- 録音セッション本体
- `started_at`
- `ended_at`
- `status` (`recording` / `completed` / `error`)

### `cards`

- 発話セグメント単位の保存
- `session_id`
- `audio_path`
- `transcript`
- `duration_ms`
- `size_bytes`
- `transcription_ms`
- `real_time_factor`

### `topics`

- LLM 要約結果
- 現時点では手動要約の保存先

### `chat_threads` / `chat_messages`

- チャットスレッドとメッセージ保存
- スレッド UI は「最後に更新された順」で並ぶ

## 注意（flavor = 別アプリ）

`gemma` と `bonsai` は `applicationIdSuffix` により別アプリ扱い。
そのため次は共有されない（見えない）。

- DB（録音/チャット/要約）
- `files/models/`（LLM）
- `files/asr/`（ASR）
- `files/audio-segments/`（録音 wav）

## 既知の制約

- ASR は `CPU` 実行で速くはない
- `Room` は未導入
- 録音セッションの再生 UI はまだない
- セッションごとの自動要約はまだない
- 話者分離、キーワード検出、VAD の本命差し替えは未着手

## 次のマイルストーン

`セッション要約` 実装済み（2026-05-05）。詳細は `docs/session-wrapup.md` 参照。

次は **検証と品質確認**:

- 実機で Stop VAD → 要約完了までの一連フローを動かす
- 処理時間・品質・通知動作を確認する
- 失敗ケース（LLM 未ロード・transcript 短すぎ）を確認する

その後、追加候補:

- Home セッション詳細ドロワーに要約（title / theme / agenda）を表示
- 要約失敗時の再試行ボタン
- Phase 2: タスク抽出（誰が・何を）
