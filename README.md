# SnapAsk(仮称)

[![CI](https://github.com/otomatty/snapask/actions/workflows/ci.yml/badge.svg)](https://github.com/otomatty/snapask/actions/workflows/ci.yml)
[![Secret scan](https://github.com/otomatty/snapask/actions/workflows/secret-scan.yml/badge.svg)](https://github.com/otomatty/snapask/actions/workflows/secret-scan.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

スクショを撮ったら、そのまま AI に質問できる Android アプリ。AI は Web を調べ、出典を付けて、分かる言葉で説明する。理解できたことはメモとして端末に残す。

自分と近しい仲間で使うアプリ。コードは公開しているが、使えるのはサーバーの許可リストに載せた Google アカウントだけ。

## 構成

```
android/   Android アプリ(Kotlin, Jetpack Compose)
worker/    中継サーバー(Cloudflare Workers + D1)
docs/      PRD、セットアップ手順書、図
.github/   CI、機密情報の検出、デプロイ、リリース、Dependabot
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

## GitHub Actions

| ワークフロー | 動くとき | 内容 |
|---|---|---|
| `ci.yml` | プッシュ、プルリクエスト | サーバーの型チェックとテスト、アプリのビルド、ワークフロー自体の検査 |
| `secret-scan.yml` | プッシュ、プルリクエスト、毎週月曜 | gitleaks で全履歴から API キーや鍵を探す。鍵ファイルや設定ファイルがコミットされていないかも調べる |
| `deploy-worker.yml` | `main` の `worker/` が変わったとき、手動 | テストのあとデータベースの更新とサーバーの公開(設定したときだけ動く) |
| `release.yml` | `v0.1.0` のようなタグを付けたとき | 署名済み APK を作り、GitHub のリリースに添付 |

必要な Secrets と Variables は [docs/SETUP.md の 9 章](docs/SETUP.md#9-github-actions-を使う)にある。

## 機密情報の扱い

このリポジトリは公開されている。API キー、セッション用の秘密の値、署名鍵、`local.properties`、`.dev.vars` はコミットしない(`.gitignore` 済み)。万一コミットしても CI の検出で止まる。秘密の値は Cloudflare の secret と GitHub の Secrets にだけ置く。

検出されたときは、値を履歴から消すだけでは足りない。まずその値を無効にして作り直す(手順は [docs/SETUP.md](docs/SETUP.md#機密情報が検出されたとき))。

## ライセンス

[MIT](LICENSE)
