-- 利用を許可した Google アカウント。
-- ここに載っていて status が 'active' のアカウントだけが質問できる。
CREATE TABLE allowed_users (
  email    TEXT PRIMARY KEY NOT NULL CHECK (email = lower(email)),
  status   TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended')),
  note     TEXT,
  added_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))
);

-- 1日あたりの質問回数(利用上限の判定用)。質問や画像の内容は保存しない。
CREATE TABLE usage_log (
  email   TEXT NOT NULL,
  used_on TEXT NOT NULL, -- 日本時間の日付 (YYYY-MM-DD)
  count   INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (email, used_on)
);
