import { startAnswer, summarize, type ChatTurn, type ImageInput } from "./ai";
import { issueSession, requireUser, verifyGoogleIdToken } from "./auth";
import { consumeDailyQuota, isAllowed } from "./db";
import { errorResponse, HttpError, json, log, logError, readJson } from "./http";

const MAX_ASK_BYTES = 8 * 1024 * 1024; // スクショ(base64)を含む
const MAX_SMALL_BYTES = 256 * 1024;
const MAX_IMAGE_CHARS = 7_000_000; // base64 で約5MBの画像まで
const MAX_TURNS = 40;
const MAX_TEXT_CHARS = 8000;
const IMAGE_TYPES = new Set(["image/jpeg", "image/png", "image/webp"]);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function parseTurns(value: unknown): ChatTurn[] {
  if (!Array.isArray(value) || value.length === 0 || value.length > MAX_TURNS) {
    throw new HttpError(400, "bad_request", "messages の形式が正しくありません");
  }
  const turns = value.map((item): ChatTurn => {
    if (!isRecord(item) || (item.role !== "user" && item.role !== "assistant") || typeof item.text !== "string") {
      throw new HttpError(400, "bad_request", "messages の形式が正しくありません");
    }
    if (item.text.length > MAX_TEXT_CHARS) {
      throw new HttpError(400, "bad_request", "メッセージが長すぎます");
    }
    return { role: item.role, text: item.text };
  });
  return turns;
}

function parseImage(value: unknown): ImageInput | null {
  if (value === undefined || value === null) return null;
  if (!isRecord(value) || typeof value.mediaType !== "string" || typeof value.data !== "string") {
    throw new HttpError(400, "bad_request", "image の形式が正しくありません");
  }
  if (!IMAGE_TYPES.has(value.mediaType)) {
    throw new HttpError(400, "bad_request", "対応していない画像形式です");
  }
  if (value.data.length === 0 || value.data.length > MAX_IMAGE_CHARS) {
    throw new HttpError(413, "too_large", "画像が大きすぎます");
  }
  // 全体を正規表現にかけると画像の大きさに比例して時間がかかるので、先頭と末尾だけ確かめる。
  // 途中が壊れている場合は AI サービス側で弾かれる。
  if (!/^[A-Za-z0-9+/]+$/.test(value.data.slice(0, 256)) || !/^[A-Za-z0-9+/]*={0,2}$/.test(value.data.slice(-8))) {
    throw new HttpError(400, "bad_request", "image の形式が正しくありません");
  }
  return { mediaType: value.mediaType, data: value.data };
}

/** POST /auth/google — Google の ID トークンを確かめ、許可されたアカウントにセッションを発行する。 */
async function handleAuth(request: Request, env: Env): Promise<Response> {
  const body = await readJson(request, MAX_SMALL_BYTES);
  if (!isRecord(body) || typeof body.idToken !== "string" || !body.idToken) {
    throw new HttpError(400, "bad_request", "idToken が必要です");
  }
  const email = await verifyGoogleIdToken(body.idToken, env);
  if (!(await isAllowed(env.DB, email))) {
    log("login denied", { reason: "not_allowed" });
    throw new HttpError(403, "not_allowed", "このアカウントは利用が許可されていません");
  }
  const session = await issueSession(email, env);
  log("login ok");
  return json({ token: session.token, email, expiresAt: session.expiresAt });
}

/** POST /ask — スクショと質問を受け取り、出典付きの回答を少しずつ返す。 */
async function handleAsk(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const email = await requireUser(request, env);
  const body = await readJson(request, MAX_ASK_BYTES);
  if (!isRecord(body)) throw new HttpError(400, "bad_request", "リクエストの形式が正しくありません");
  const turns = parseTurns(body.messages);
  if (turns[turns.length - 1]?.role !== "user") {
    throw new HttpError(400, "bad_request", "最後のメッセージは質問である必要があります");
  }
  const image = parseImage(body.image);

  const limit = Number.parseInt(env.DAILY_LIMIT, 10) || 0;
  const quota = await consumeDailyQuota(env.DB, email, limit);
  if (!quota.allowed) {
    throw new HttpError(429, "limit_reached", `本日の利用上限(${limit}回)に達しました。明日また利用できます`);
  }

  const { stream, finished } = await startAnswer(env, image, turns);
  ctx.waitUntil(finished);
  log("ask started", { turns: turns.length, hasImage: image !== null, usedToday: quota.count });
  return new Response(stream, {
    headers: {
      "content-type": "text/event-stream; charset=utf-8",
      "cache-control": "no-store",
      "x-accel-buffering": "no",
    },
  });
}

/** POST /summarize — メモのタイトルと要点の下書きを返す。 */
async function handleSummarize(request: Request, env: Env): Promise<Response> {
  await requireUser(request, env);
  const body = await readJson(request, MAX_SMALL_BYTES);
  if (!isRecord(body)) throw new HttpError(400, "bad_request", "リクエストの形式が正しくありません");
  const turns = parseTurns(body.messages);
  return json(await summarize(env, turns));
}

async function route(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const { pathname } = new URL(request.url);
  if (request.method === "GET" && pathname === "/health") return json({ ok: true });
  if (request.method === "POST") {
    if (pathname === "/auth/google") return handleAuth(request, env);
    if (pathname === "/ask") return handleAsk(request, env, ctx);
    if (pathname === "/summarize") return handleSummarize(request, env);
  }
  throw new HttpError(404, "not_found", "見つかりません");
}

export default {
  async fetch(request, env, ctx): Promise<Response> {
    try {
      return await route(request, env, ctx);
    } catch (error) {
      if (error instanceof HttpError) {
        if (error.status >= 500) logError("request failed", { code: error.code, status: error.status });
        return errorResponse(error);
      }
      logError("unexpected error", { error: error instanceof Error ? error.message : String(error) });
      return errorResponse(new HttpError(500, "internal", "サーバーで問題が起きました"));
    }
  },
} satisfies ExportedHandler<Env>;
