import { createRemoteJWKSet, jwtVerify, SignJWT } from "jose";
import { isAllowed } from "./db";
import { HttpError } from "./http";

const GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
const GOOGLE_ISSUERS = ["https://accounts.google.com", "accounts.google.com"];
const SESSION_ISSUER = "snapask-api";
const SESSION_DAYS = 30;

// Google の公開鍵。取得した鍵は jose が保持して再利用する(リクエストごとの状態ではない)。
const googleKeys = createRemoteJWKSet(new URL(GOOGLE_JWKS_URL));

function sessionKey(env: Env): Uint8Array {
  if (!env.SESSION_SECRET || env.SESSION_SECRET.length < 32) {
    throw new HttpError(500, "misconfigured", "SESSION_SECRET が未設定か短すぎます");
  }
  return new TextEncoder().encode(env.SESSION_SECRET);
}

/**
 * アプリから受け取った Google の ID トークンを検証し、メールアドレスを返す。
 * 署名・発行元・宛先(このアプリ用に発行されたか)・有効期限・メール確認済みを確かめる。
 */
export async function verifyGoogleIdToken(idToken: string, env: Env): Promise<string> {
  let payload;
  try {
    ({ payload } = await jwtVerify(idToken, googleKeys, {
      issuer: GOOGLE_ISSUERS,
      audience: env.GOOGLE_WEB_CLIENT_ID,
      algorithms: ["RS256"],
    }));
  } catch {
    throw new HttpError(401, "invalid_token", "Google のログイン情報を確認できませんでした");
  }
  const email = typeof payload.email === "string" ? payload.email.toLowerCase() : "";
  if (!email || payload.email_verified !== true) {
    throw new HttpError(401, "invalid_token", "確認済みのメールアドレスがありません");
  }
  return email;
}

/** このサーバー専用のセッショントークンを発行する。 */
export async function issueSession(email: string, env: Env): Promise<{ token: string; expiresAt: number }> {
  const expiresAt = Math.floor(Date.now() / 1000) + SESSION_DAYS * 24 * 60 * 60;
  const token = await new SignJWT({ email })
    .setProtectedHeader({ alg: "HS256" })
    .setIssuer(SESSION_ISSUER)
    .setSubject(email)
    .setIssuedAt()
    .setExpirationTime(expiresAt)
    .sign(sessionKey(env));
  return { token, expiresAt };
}

/**
 * リクエストの送り主を確かめる。
 * トークンが正しくても、毎回許可リストを引き直す。リストから外した時点で使えなくなる。
 */
export async function requireUser(request: Request, env: Env): Promise<string> {
  const header = request.headers.get("authorization") ?? "";
  const token = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  if (!token) {
    throw new HttpError(401, "unauthorized", "ログインが必要です");
  }
  let email = "";
  try {
    const { payload } = await jwtVerify(token, sessionKey(env), {
      issuer: SESSION_ISSUER,
      algorithms: ["HS256"],
    });
    email = typeof payload.email === "string" ? payload.email : "";
  } catch (error) {
    if (error instanceof HttpError) throw error;
    throw new HttpError(401, "unauthorized", "ログインの有効期限が切れました");
  }
  if (!email) {
    throw new HttpError(401, "unauthorized", "ログインが必要です");
  }
  if (!(await isAllowed(env.DB, email))) {
    throw new HttpError(403, "not_allowed", "このアカウントは利用が許可されていません");
  }
  return email;
}
