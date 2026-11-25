import { applyD1Migrations, createExecutionContext, env, waitOnExecutionContext } from "cloudflare:test";
import { exportJWK, generateKeyPair, SignJWT } from "jose";
import { vi } from "vitest";
import worker from "../src/index";

export const BASE = "https://api.test";
const GOOGLE_CERTS = "https://www.googleapis.com/oauth2/v3/certs";
const ANTHROPIC = "https://api.anthropic.com/v1/messages";
const KID = "test-key-1";

let signingKey: CryptoKey;
let jwks: { keys: unknown[] };

/** AI サービスへ送られたリクエスト本文(テストで中身を確かめる)。 */
export const aiRequests: any[] = [];
/** AI サービスの応答。テストごとに積み、先頭から順に返す。 */
export const aiResponses: Array<() => Response> = [];

export async function setup(): Promise<void> {
  await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
  if (!signingKey) {
    const pair = await generateKeyPair("RS256", { extractable: true });
    signingKey = pair.privateKey;
    jwks = { keys: [{ ...(await exportJWK(pair.publicKey)), kid: KID, alg: "RS256", use: "sig" }] };
  }
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      if (url === GOOGLE_CERTS) return Response.json(jwks);
      if (url === ANTHROPIC) {
        aiRequests.push({ headers: new Headers(init?.headers), body: JSON.parse(String(init?.body)) });
        const next = aiResponses.shift();
        if (!next) throw new Error("AI の応答が用意されていません");
        return next();
      }
      throw new Error(`想定外の通信: ${url}`);
    }),
  );
}

export function reset(): void {
  aiRequests.length = 0;
  aiResponses.length = 0;
}

export async function googleIdToken(
  email: string,
  overrides: { audience?: string; issuer?: string; emailVerified?: boolean; expiresIn?: string } = {},
): Promise<string> {
  return new SignJWT({ email, email_verified: overrides.emailVerified ?? true })
    .setProtectedHeader({ alg: "RS256", kid: KID })
    .setIssuer(overrides.issuer ?? "https://accounts.google.com")
    .setAudience(overrides.audience ?? env.GOOGLE_WEB_CLIENT_ID)
    .setSubject("1234567890")
    .setIssuedAt()
    .setExpirationTime(overrides.expiresIn ?? "1h")
    .sign(signingKey);
}

export async function allow(email: string, status: "active" | "suspended" = "active"): Promise<void> {
  await env.DB.prepare(
    "INSERT INTO allowed_users (email, status) VALUES (?1, ?2) ON CONFLICT (email) DO UPDATE SET status = ?2",
  )
    .bind(email, status)
    .run();
}

export async function call(
  path: string,
  options: { method?: string; token?: string; body?: unknown; envOverrides?: Partial<Env> } = {},
): Promise<Response> {
  const headers = new Headers();
  let body: string | undefined;
  if (options.body !== undefined) {
    body = JSON.stringify(options.body);
    headers.set("content-type", "application/json");
    headers.set("content-length", String(new TextEncoder().encode(body).byteLength));
  }
  if (options.token) headers.set("authorization", `Bearer ${options.token}`);
  const request = new Request(`${BASE}${path}`, { method: options.method ?? "POST", headers, body });
  const ctx = createExecutionContext();
  const response = await worker.fetch(request as any, { ...env, ...options.envOverrides }, ctx);
  // 本文を読み切ってから、バックグラウンド処理の完了を待つ
  const buffered = await response.arrayBuffer();
  await waitOnExecutionContext(ctx);
  return new Response(buffered, { status: response.status, headers: response.headers });
}

export async function login(email: string): Promise<string> {
  await allow(email);
  const response = await call("/auth/google", { body: { idToken: await googleIdToken(email) } });
  const data = (await response.json()) as { token: string };
  return data.token;
}

/** AI サービスのストリーミング応答を組み立てる。 */
export function aiStream(events: unknown[]): () => Response {
  return () =>
    new Response(events.map((event: any) => `event: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`).join(""), {
      headers: { "content-type": "text/event-stream" },
    });
}

/** アプリ向けのストリームをイベントの配列に戻す。 */
export async function readEvents(response: Response): Promise<any[]> {
  const text = await response.text();
  return text
    .split("\n\n")
    .filter((chunk) => chunk.startsWith("data: "))
    .map((chunk) => JSON.parse(chunk.slice(6)));
}

export const IMAGE = { mediaType: "image/jpeg", data: "AAAA" };
