# Claude の外で行う作業(セットアップ手順書)

コードはすべてこのリポジトリに入っています。ここに書いてあるのは、アカウントや鍵が必要で、Claude の作業環境からは実行できなかった作業です。上から順に進めれば、自分の端末でアプリが動き、仲間に配れる状態になります。

- 作成日: 2026-11-25
- 所要時間の目安: 1〜2時間(初回)

## この手順書の前提

| 項目 | 状態 |
|---|---|
| 中継サーバー(`worker/`) | 実装済み。型チェックと自動テスト20件が通過 |
| Android アプリ(`android/`) | 実装済み。**ビルドは未確認**(作業環境から Android のビルドに必要なサイトへ接続できなかったため)。初回ビルドでコンパイルエラーが出る可能性がある。対処は「8. うまくいかないとき」を参照 |
| 実際の AI との通信 | 未確認(APIキーがないため)。仕様書に沿って実装し、応答を模したテストで確認している |

## 全体の流れ

| # | 作業 | 場所 | 得られるもの |
|---|---|---|---|
| 1 | 道具とアカウントの用意 | 手元のPC | — |
| 2 | 署名鍵を作る | 手元のPC | `snapask.jks`、SHA-1 |
| 3 | Google ログインの設定 | Google Cloud | ウェブ用クライアントID |
| 4 | AI のAPIキーを作る | Claude Console | APIキー |
| 5 | 中継サーバーを公開する | Cloudflare | サーバーのURL |
| 6 | アプリをビルドして入れる | Android Studio | APK |
| 7 | 動作確認 | スマホ | — |

途中で控える値が4つあります。メモしながら進めてください。

| 控える値 | どこで得るか | どこで使うか |
|---|---|---|
| SHA-1 | 手順2 | 手順3(Android用クライアント) |
| ウェブ用クライアントID | 手順3 | 手順5(サーバー)、手順6(アプリ) |
| APIキー | 手順4 | 手順5(サーバー) |
| サーバーのURL | 手順5 | 手順6(アプリ) |

---

## 1. 道具とアカウントの用意

**手元のPCに入れるもの**

- Node.js 22 以上
- Android Studio(JDK 17 と Android SDK が一緒に入る)

**アカウント**

- Google Cloud(Google アカウントがあれば無料で使える)
- Cloudflare
- Claude Console(Claude API の利用。クレジットカードの登録が必要)

**コードの置き場所(任意)**

zip を展開したフォルダで `git init` し、GitHub の非公開リポジトリに置いておくと、`.github/workflows/ci.yml` によってサーバーのテストとアプリのビルドが自動で走ります(手順9)。

---

## 2. 署名鍵を作る

Google ログインは「どの鍵で署名されたアプリか」を見て許可します。そのため、最初に鍵を1つ作り、以後ずっと同じ鍵でビルドします。

```bash
cd android
keytool -genkeypair -v -keystore snapask.jks -alias snapask \
  -keyalg RSA -keysize 2048 -validity 10000
```

パスワードを聞かれるので決めて控えます(名前や組織名は空欄のままで構いません)。続けて SHA-1 を表示します。

```bash
keytool -list -v -keystore snapask.jks -alias snapask
```

出力の `SHA1:` の行(`AB:CD:...` の形)を控えます。

> **注意**: `snapask.jks` とパスワードを失うと、同じアプリとして更新を配れなくなります。安全な場所に控えを置いてください。このファイルは `.gitignore` 済みで、リポジトリには入りません。

---

## 3. Google ログインの設定(Google Cloud)

[Google Cloud コンソール](https://console.cloud.google.com/) で行います。画面の名称は変わることがあるので、見当たらないときは近い名前の項目を探してください。

1. **プロジェクトを作る**(名前は `snapask` など)。
2. **OAuth 同意画面を設定する**(「APIとサービス」→「OAuth 同意画面」)。
   - アプリ名: `SnapAsk`
   - サポートメール: 自分のアドレス
   - 対象: **外部**
3. **テストユーザーを追加する**。公開ステータスが「テスト中」のあいだは、ここに登録した Google アカウントしかログインできません。自分と、使ってもらう仲間のアカウントを追加します。
4. **クライアントを2つ作る**(「クライアント」→「クライアントを作成」)。

| 種類 | 入力する内容 | 用途 |
|---|---|---|
| ウェブ アプリケーション | 名前だけ(生成元やリダイレクトURIは空欄) | **このクライアントIDを控える**。サーバーとアプリの両方に設定する |
| Android | パッケージ名 `app.snapask`、手順2の SHA-1 | 作るだけでよい(値はどこにも設定しない) |

> よくある間違い: アプリやサーバーに **Android 用**のクライアントIDを設定してしまう。設定するのは**ウェブ用**のIDです。

---

## 4. AI のAPIキーを作る(Claude Console)

1. [Claude Console](https://platform.claude.com/) にログインし、支払い方法を登録してクレジットを購入する。
2. APIキーを作成して控える(表示は一度きり)。
3. 使いすぎを防ぐため、Console で月の利用上限を設定しておく。

**費用の目安**(2026年11月時点の公開価格。最新は[価格表](https://platform.claude.com/docs/en/about-claude/pricing)を確認)

| 項目 | 価格 |
|---|---|
| 回答(Claude Sonnet 5.5) | 入力 $2 / 出力 $10(100万トークンあたり) |
| メモの下書き(Claude Haiku 5.5) | 入力 $0.10〜 / 出力 $0.50〜(同上) |
| Web検索 | $10(1,000回あたり)。1回の質問で最大3回 |

検索結果の文章も入力として課金されるため、1回の質問は数円〜20円程度になる見込みです。これは概算なので、使い始めたら Console の利用額で実際の値を確かめてください。

Web検索は、組織の管理者が無効にしていなければ最初から使えます。無効の場合は Console の設定(Capabilities)で有効にします。

---

## 5. 中継サーバーを公開する(Cloudflare)

`worker/` フォルダで作業します。

```bash
cd worker
npm install
npx wrangler login
```

### 5-1. データベースを作る

```bash
npx wrangler d1 create snapask
```

出力に `database_id` が表示されます。`wrangler.jsonc` を開き、次の2か所を書き換えます。

| 項目 | 書き換える値 |
|---|---|
| `database_id` | いま表示された値 |
| `GOOGLE_WEB_CLIENT_ID` | 手順3で控えたウェブ用クライアントID |

テーブルを作ります。

```bash
npm run db:migrate:remote
```

### 5-2. 秘密の値を登録する

「Worker がまだ無いので作成しますか」と聞かれたら Yes と答えます。

```bash
# 手順4のAPIキーを貼り付ける
npx wrangler secret put ANTHROPIC_API_KEY

# セッションの署名に使う値。次のコマンドで生成した文字列を貼り付ける
openssl rand -base64 48
npx wrangler secret put SESSION_SECRET
```

### 5-3. 公開する

```bash
npm run deploy
```

表示される URL(`https://snapask-api.<サブドメイン>.workers.dev`)を控え、動いていることを確かめます。

```bash
curl https://snapask-api.<サブドメイン>.workers.dev/health
# {"ok":true} が返れば成功
```

### 5-4. 自分を許可リストに入れる

メールアドレスは**小文字**で入れます。

```bash
npx wrangler d1 execute snapask --remote \
  --command "INSERT INTO allowed_users (email, note) VALUES ('you@gmail.com', '自分')"
```

### Cloudflare の料金プランについて

無料プランでは、1リクエストあたりの CPU 時間が 10 ミリ秒に制限されています([Workers の制限](https://developers.cloudflare.com/workers/platform/limits/))。スクショを含む質問はこの制限に近い処理量になるため、「CPU 時間の超過」で失敗することがあれば、Workers 有料プラン(月 $5〜)に切り替えてください。まずは無料プランで試して問題ありません。

---

## 6. アプリをビルドして入れる

### 6-1. 設定ファイルを作る

`android/local.properties.example` を `android/local.properties` という名前でコピーし、値を入れます。

| 項目 | 値 |
|---|---|
| `SNAPASK_API_BASE_URL` | 手順5のサーバーURL(末尾の `/` なし) |
| `SNAPASK_GOOGLE_WEB_CLIENT_ID` | 手順3のウェブ用クライアントID |
| `SNAPASK_KEYSTORE_FILE` | `snapask.jks` |
| `SNAPASK_KEYSTORE_PASSWORD` / `SNAPASK_KEY_PASSWORD` | 手順2で決めたパスワード |
| `SNAPASK_KEY_ALIAS` | `snapask` |

### 6-2. ビルドする

Android Studio で `android/` フォルダを開き、同期が終わったら実機をつないで実行します。コマンドで APK を作る場合は次のとおりです。

```bash
cd android
./gradlew assembleRelease
# 出力: app/build/outputs/apk/release/app-release.apk
```

### 6-3. 仲間に配る

1. 仲間の Google アカウントを、**許可リスト**(手順5-4と同じコマンド)と、Google Cloud の**テストユーザー**(手順3)の両方に追加する。
2. `app-release.apk` を送る。
3. インストール時に「提供元不明のアプリ」の許可を求められたら、許可してもらう。

---

## 7. 動作確認

| # | 操作 | 期待する結果 |
|---|---|---|
| 1 | アプリを起動し、Google でログイン | メモ一覧(空の状態の案内)が表示される |
| 2 | 許可リストにないアカウントでログイン | 「利用が許可されていません」と表示される |
| 3 | 何かのスクショを撮り、共有 →「SnapAskで質問」 | スクショと入力欄が表示される |
| 4 | 何も入力せずに送信 | 「Webで調べています」の後、回答が少しずつ表示され、出典の一覧が付く |
| 5 | 本文中の `[1]` や出典の行をタップ | そのページが開く |
| 6 | 続けて質問する | 前のやり取りを踏まえた回答が返る |
| 7 | 「メモに保存」 | タイトルと要点が付いたメモの詳細が開く |
| 8 | メモ一覧で検索、メモを編集、削除 | それぞれ反映される |
| 9 | 機内モードで質問 | エラーと「もう一度」が表示される |
| 10 | 設定からログアウト | ログイン画面に戻る |

---

## 8. うまくいかないとき

| 症状 | 確認すること |
|---|---|
| ビルドでコンパイルエラーが出る | アプリは未ビルドの状態で納品しています。エラーの全文を Claude(または Codex など)に渡して直してください。依存の版は `android/gradle/libs.versions.toml` にまとめてあります |
| Google のアカウント選択が出ずに失敗する | ① Android 用クライアントのパッケージ名が `app.snapask` か ② SHA-1 が `snapask.jks` のものか ③ `local.properties` の鍵の設定が入っているか(入っていないと別の鍵で署名される) ④ テストユーザーに登録したか |
| 「Google のログイン情報を確認できませんでした」 | アプリとサーバーの `GOOGLE_WEB_CLIENT_ID` が同じ**ウェブ用**のIDになっているか |
| 「利用が許可されていません」 | 許可リストにそのアドレスが小文字で入っているか、`status` が `active` か |
| 「AIサービスの設定に問題があります」 | `ANTHROPIC_API_KEY` が正しいか、クレジット残高があるか、Web検索が無効になっていないか |
| 回答が始まるまで遅い | `wrangler.jsonc` の `ASK_EFFORT` を `low` にして再デプロイする |
| 原因が分からない | `npx wrangler tail` でサーバーのログを見る(質問や画像の内容は記録していません。記録するのは結果と件数だけです) |

---

## 9. GitHub Actions を使う場合(任意)

リポジトリの Settings → Secrets and variables → Actions に登録すると、プッシュのたびに署名済みの APK が作られます(Actions の実行結果からダウンロードできます)。

| 種類 | 名前 | 値 |
|---|---|---|
| Variables | `SNAPASK_API_BASE_URL` | サーバーのURL |
| Variables | `SNAPASK_GOOGLE_WEB_CLIENT_ID` | ウェブ用クライアントID |
| Secrets | `SNAPASK_KEYSTORE_BASE64` | `base64 -w0 android/snapask.jks` の出力(macOS は `base64 -i android/snapask.jks`) |
| Secrets | `SNAPASK_KEYSTORE_PASSWORD` | 鍵ストアのパスワード |
| Secrets | `SNAPASK_KEY_ALIAS` | `snapask` |
| Secrets | `SNAPASK_KEY_PASSWORD` | 鍵のパスワード |

登録しなくてもビルド自体は走るので、コンパイルが通るかどうかの確認には使えます(その場合の APK では Google ログインは通りません)。

---

## 10. 日々の運用

すべて `worker/` フォルダで実行します。

**利用者の管理**

```bash
# 追加(メールアドレスは小文字)
npx wrangler d1 execute snapask --remote \
  --command "INSERT INTO allowed_users (email, note) VALUES ('friend@gmail.com', '名前')"

# 一時停止 / 再開(すぐに反映される)
npx wrangler d1 execute snapask --remote \
  --command "UPDATE allowed_users SET status = 'suspended' WHERE email = 'friend@gmail.com'"
npx wrangler d1 execute snapask --remote \
  --command "UPDATE allowed_users SET status = 'active' WHERE email = 'friend@gmail.com'"

# 削除
npx wrangler d1 execute snapask --remote \
  --command "DELETE FROM allowed_users WHERE email = 'friend@gmail.com'"

# 一覧
npx wrangler d1 execute snapask --remote --command "SELECT * FROM allowed_users"
```

**利用回数を見る**

```bash
npx wrangler d1 execute snapask --remote \
  --command "SELECT * FROM usage_log ORDER BY used_on DESC, count DESC LIMIT 30"
```

**設定を変える**(`wrangler.jsonc` の `vars` を書き換えて `npm run deploy`)

| 項目 | 内容 | 初期値 |
|---|---|---|
| `DAILY_LIMIT` | 1人あたり1日の質問回数。`0` で無制限 | `50` |
| `MAX_SEARCHES` | 1回の質問で行うWeb検索の上限 | `3` |
| `ASK_MODEL` | 回答に使うモデル | `claude-sonnet-5-5` |
| `ASK_EFFORT` | 考える深さ(`low` / `medium` / `high`)。低いほど速く安い | `medium` |
| `SUMMARY_MODEL` | メモの下書きに使うモデル | `claude-haiku-5-5` |

**全員をログアウトさせる**: `SESSION_SECRET` を新しい値で登録し直すと、発行済みのセッションがすべて無効になります。

---

## 11. 今回入れていないもの

| 項目 | 理由 |
|---|---|
| スクショ撮影の自動検知(バックログ US-08) | Android のバージョンごとの制約と権限の調査が先に必要。現在は共有メニューから開く |
| メモの書き出し(US-21) | 第1リリースの対象外(Could) |
| 許可リストの管理画面 | 当面は上記のコマンドで管理する方針 |

アプリ名(`SnapAsk`)とパッケージ名(`app.snapask`)は仮のものです。変える場合は `android/app/build.gradle.kts` の `namespace` と `applicationId`、ソースのパッケージ、`strings.xml`、Google Cloud の Android 用クライアントを合わせて変更してください。
