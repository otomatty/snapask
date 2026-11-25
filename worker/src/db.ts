/** 許可リストに載っていて、有効なアカウントかどうか。 */
export async function isAllowed(db: D1Database, email: string): Promise<boolean> {
  const row = await db
    .prepare("SELECT status FROM allowed_users WHERE email = ?1")
    .bind(email)
    .first<{ status: string }>();
  return row?.status === "active";
}

/** 日本時間での日付 (YYYY-MM-DD)。利用上限はこの日付ごとに数える。 */
export function jstDate(now: Date = new Date()): string {
  return new Date(now.getTime() + 9 * 60 * 60 * 1000).toISOString().slice(0, 10);
}

/**
 * 今日の質問回数を1つ増やし、上限内かどうかを返す。
 * 増やすのと数えるのを1つのSQLで行うので、同時に質問しても数え漏れない。
 */
export async function consumeDailyQuota(
  db: D1Database,
  email: string,
  limit: number,
  now: Date = new Date(),
): Promise<{ count: number; allowed: boolean }> {
  const row = await db
    .prepare(
      `INSERT INTO usage_log (email, used_on, count) VALUES (?1, ?2, 1)
       ON CONFLICT (email, used_on) DO UPDATE SET count = count + 1
       RETURNING count`,
    )
    .bind(email, jstDate(now))
    .first<{ count: number }>();
  const count = row?.count ?? 1;
  return { count, allowed: limit <= 0 || count <= limit };
}
