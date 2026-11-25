# SnapAsk(仮称)

スクショを撮ったら、そのまま AI に質問できる Android アプリ。AI は Web を調べ、出典を付けて、分かる言葉で説明する。理解できたことはメモとして端末に残す。

自分と近しい仲間で使う、クローズドなアプリ。

## 構成

```
android/   Android アプリ(Kotlin, Jetpack Compose)
worker/    中継サーバー(Cloudflare Workers + D1)
docs/      PRD、セットアップ手順書、図
```

```
アプリ ──(Google の ID トークン)──▶ 中継サーバー ──▶ Google の公開鍵で検証
       ◀──(セッション)────────────           └─▶ 許可リスト(D1)

アプリ ──(スクショ + 質問)────────▶ 中継サーバー ──(APIキーを付与)──▶ AI(Web検索つき)
       ◀──(回答を少しずつ + 出典)──           ◀──────────────────────
```

- AI の API キーはサーバーにだけ置く。アプリには含まれない。
- 利用できるのは、サーバーの許可リストに載っている Google アカウントだけ。リストから外すと、その時点で使えなくなる。
- メモとスクショは端末の中にだけ保存する。サーバーは質問や画像を保存しない。

## はじめかた

[docs/SETUP.md](docs/SETUP.md) の手順に沿って進める。

## 開発

**サーバー**

```bash
cd worker
npm install
npm run typecheck   # 型チェック
npm test            # 自動テスト(Workers のランタイム上で実行)
npm run dev         # 手元で起動(.dev.vars が必要。.dev.vars.example を参照)
```

**アプリ**

Android Studio で `android/` を開く。設定は `android/local.properties`(`local.properties.example` を参照)。

## アプリとサーバーの通信

| 経路 | 内容 |
|---|---|
| `POST /auth/google` | `{ idToken }` → `{ token, email, expiresAt }` |
| `POST /ask` | `{ image?: { mediaType, data }, messages: [{ role, text }] }` → SSE |
| `POST /summarize` | `{ messages }` → `{ title, summary }` |
| `GET /health` | `{ ok: true }` |

`/ask` の応答は、1行ずつの JSON を `data:` に載せた SSE。

| type | 内容 |
|---|---|
| `status` | Web検索を始めた(`query`) |
| `delta` | 回答の文章の続き(`text`)。出典番号は ` [1]` の形で文中に入る |
| `source` | 出典(`index`, `title`, `url`) |
| `done` | 完了(`sources` は出典の数) |
| `error` | 失敗(`code`, `message`) |

エラーは `{ "error": { "code", "message" } }` の形で返る。主な `code`: `unauthorized`(要ログイン)、`not_allowed`(許可リスト外)、`limit_reached`(1日の上限)、`busy`(AI が混雑)。

## 資料

- [docs/PRD.md](docs/PRD.md) — 要件
- [docs/SETUP.md](docs/SETUP.md) — セットアップと運用
