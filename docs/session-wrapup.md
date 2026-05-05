# セッションラップアップ機能（ASR → AI要約）

最終更新: 2026-05-05

## 概要

録音セッション（Start VAD 〜 Stop VAD）が終了したあと、ローカル LLM（Bonsai）が
セッション内の全 ASR 結果をまとめて以下を自動生成する機能。

- **タイトル**: 会議のタイトル（15文字以内）
- **テーマ**: 主なテーマ・目的（50文字以内）
- **アジェンダ**: 議論されたトピックのリスト（3〜5個）

クラウド通信なし、端末ローカルで完結する。

---

## 動作フロー

```
[録音中]
  Start VAD → マイク → VAD → wav 保存 → ASR (SenseVoice) → transcript を DB 保存
  ↓
[Stop VAD を押す]
  ↓
session を "completed" に確定
  ↓ (transcript が 1 件以上あれば)
WrapupJob を DB に登録 (status: pending)
  ↓
SessionWrapupService 起動（Foreground Service）
  ↓
[Step 1/4] transcript 収集
  cards テーブルから session の transcript を時系列で結合
  ↓
[Step 2/4] LLM 準備確認
  app.llmEngine が ready か確認
  ↓
[Step 3/4] 要約生成
  Bonsai に 1-shot JSON プロンプトを投げる
  通知バーに "要約生成中… (N秒経過)" を 1 秒刻みで更新
  タイムアウト: 5 分
  ↓
[Step 4/4] 保存
  session_summaries テーブルに title / theme / agenda_json を保存
  WrapupJob を "completed" に更新
  ↓
"議事録ができました" 通知
```

---

## プロンプト

```
以下の会議の発言録を読んで、必ず次のJSON形式のみで回答してください。日本語で回答し、余分な説明や前置きは不要です。

{"title":"...","theme":"...","agenda":["...","..."]}

- title: 会議のタイトル（15文字以内）
- theme: 会議の主なテーマや目的（50文字以内）
- agenda: 議論されたトピックのリスト（3〜5個）

発言録:
{transcript の末尾 3000 文字}
```

**注意**: transcript が 3000 文字を超える場合は末尾だけを使う（PoC 割り切り、長文対応は Phase 3）。

---

## UI

### Home 画面（議事録一覧）

各セッション行に要約ステータスバッジが表示される:

| バッジ | 意味 |
|---|---|
| `要約待ち` (グレー) | ジョブがキューに入った |
| `● 要約中… (34秒)` (青・ドット点灯) | LLM 推論中。経過秒を更新 |
| `要約完了` (緑) | 正常に保存された |
| `要約失敗: <エラー>` (赤) | 失敗。再試行は ViewModel.retryWrapup() |
| `キャンセル済み` (グレー) | ユーザーがキャンセルした |

要約中のセッション行の右端に **× ボタン** が表示され、キャンセルできる。

### 通知バー（Foreground Service）

| タイミング | 通知内容 |
|---|---|
| 開始 | 「議事録を作成中 (1/4)」準備中… |
| 生成中 | 「議事録を作成中 (3/4)」要約生成中… (N秒経過) |
| 完了 | 「議事録ができました」＜タイトル＞ |
| 失敗 | 「議事録の作成に失敗しました」＜エラー＞ |

---

## 堅牢性

| 状況 | 動作 |
|---|---|
| 要約中にアプリをバックグラウンド | Service は継続して動く |
| 要約中にアプリを閉じる（スワイプ） | Service は継続して動く |
| 端末再起動（予定外） | 次回アプリ起動時に pending/running ジョブを検知して再開 |
| キャンセルボタン押下 | llama.cpp の stop flag を立てて推論を即時中断 |
| transcript が 0 件のセッション | ジョブを作成しない（スキップ） |
| LLM 未ロード | 「LLMがロードされていません」でジョブを失敗にする |
| 5 分タイムアウト | 「タイムアウト」でジョブを失敗にする |

---

## DB スキーマ（追加分）

### session_wrapup_jobs
```sql
id           INTEGER PK
session_id   INTEGER UNIQUE (sessions.id への FK)
status       TEXT   -- pending / running / completed / failed / canceled
current_step TEXT   -- collect / prepare / generate / save / done
step_detail  TEXT   -- 通知バーと同じ詳細テキスト
attempts     INTEGER
last_error   TEXT
llm_model    TEXT
enqueued_at  INTEGER
started_at   INTEGER
finished_at  INTEGER
```

### session_summaries
```sql
session_id      INTEGER PK (sessions.id への FK)
generated_title TEXT
theme           TEXT
agenda_json     TEXT   -- JSON array: ["議題1", "議題2"]
llm_provider    TEXT
llm_model       TEXT
generated_at    INTEGER
```

---

## 権限（追加分）

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

初回起動時に POST_NOTIFICATIONS のダイアログが出る（Android 13+）。

---

## 検証チェックリスト

### 基本動作

- [ ] `Start VAD` → 話す → `Stop VAD` でセッションが保存される
- [ ] ASR が完了していれば要約ジョブが自動でキューに入る
- [ ] 通知バーに「議事録を作成中」が出る
- [ ] 「(N秒経過)」が 1 秒刻みで増える
- [ ] 完了通知が出る
- [ ] Home 画面のバッジが「要約完了」（緑）になる

### キャンセル

- [ ] 生成中に × ボタンを押すとキャンセルされる
- [ ] バッジが「キャンセル済み」になる
- [ ] 通知が消える

### 堅牢性

- [ ] 生成中にアプリをスワイプして閉じても Service が動き続ける
- [ ] 完了後にアプリを開くと「要約完了」バッジが表示されている
- [ ] アプリ再起動後にジョブが自動で再開される（pending が残っている場合）

### エラーケース

- [ ] LLM 未ロードで Stop VAD → 「LLMがロードされていません」で失敗
- [ ] transcript 0 件のセッション → ジョブが作成されない
- [ ] 生成結果が JSON でない → フォールバックで保存される（title=null、theme に先頭 80 文字）

### 品質確認

- [ ] Bonsai が返した JSON のタイトルが会議内容に対して適切か
- [ ] アジェンダが 3〜5 件返るか
- [ ] 発言録が短い（1〜2 文）の場合でも動くか
- [ ] 処理時間を logcat で記録しておく

---

## ログ確認コマンド

```bash
# Service と要約処理のログ
adb logcat -s SessionWrapupService XtrustVM

# 全ログ（ノイズ多め）
adb logcat | grep -E "Wrapup|wrapup|SessionSummary"
```

---

## 既知の制約・次フェーズ

| 制約 | フェーズ |
|---|---|
| transcript 3000 文字超えで末尾のみを使う | Phase 3: map-reduce で長文対応 |
| タスク抽出（誰が・何を・期限）がない | Phase 2: 別プロンプトで追加 |
| 要約結果を Home のセッション詳細ドロワーに表示していない | 近日追加 |
| 再試行ボタンの UI がない（ViewModel に retryWrapup() はある） | 近日追加 |
| Gemma flavor は未対応（bonsai のみ） | Gemma 側も同様に接続可能 |
| 処理時間: Bonsai 8B / Xiaomi 4GB で 2〜5 分が想定 | 高性能端末で改善 |
