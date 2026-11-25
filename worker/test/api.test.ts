import { env } from "cloudflare:test";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { aiRequests, aiResponses, aiStream, allow, call, googleIdToken, IMAGE, login, readEvents, reset, setup } from "./helpers";

beforeAll(setup);
beforeEach(reset);

const question = { image: IMAGE, messages: [{ role: "user", text: "これは何ですか?" }] };

/** 検索を1回行い、出典付きで答える応答。 */
const searchedAnswer = aiStream([
  { type: "message_start", message: { id: "msg_1", role: "assistant", content: [] } },
  { type: "content_block_start", index: 0, content_block: { type: "thinking", thinking: "", signature: "" } },
  { type: "content_block_delta", index: 0, delta: { type: "signature_delta", signature: "sig" } },
  { type: "content_block_stop", index: 0 },
  { type: "content_block_start", index: 1, content_block: { type: "server_tool_use", id: "srvtoolu_1", name: "web_search" } },
  { type: "content_block_delta", index: 1, delta: { type: "input_json_delta", partial_json: '{"query":"量子' } },
  { type: "content_block_delta", index: 1, delta: { type: "input_json_delta", partial_json: 'コンピュータ とは"}' } },
  { type: "content_block_stop", index: 1 },
  {
    type: "content_block_start",
    index: 2,
    content_block: {
      type: "web_search_tool_result",
      tool_use_id: "srvtoolu_1",
      content: [{ type: "web_search_result", url: "https://example.com/a", title: "記事A", encrypted_content: "enc" }],
    },
  },
  { type: "content_block_stop", index: 2 },
  { type: "content_block_start", index: 3, content_block: { type: "text", text: "" } },
  { type: "content_block_delta", index: 3, delta: { type: "text_delta", text: "結論から言うと、" } },
  { type: "content_block_stop", index: 3 },
  { type: "content_block_start", index: 4, content_block: { type: "text", text: "", citations: [] } },
  {
    type: "content_block_delta",
    index: 4,
    delta: {
      type: "citations_delta",
      citation: { type: "web_search_result_location", url: "https://example.com/a", title: "記事A", encrypted_index: "i1", cited_text: "..." },
    },
  },
  {
    type: "content_block_delta",
    index: 4,
    delta: {
      type: "citations_delta",
      citation: { type: "web_search_result_location", url: "https://example.com/b", title: "記事B", encrypted_index: "i2", cited_text: "..." },
    },
  },
  { type: "content_block_delta", index: 4, delta: { type: "text_delta", text: "量子ビットを使う計算機です。" } },
  { type: "content_block_stop", index: 4 },
  { type: "content_block_start", index: 5, content_block: { type: "text", text: "", citations: [] } },
  {
    type: "content_block_delta",
    index: 5,
    delta: {
      type: "citations_delta",
      citation: { type: "web_search_result_location", url: "https://example.com/a", title: "記事A", encrypted_index: "i3", cited_text: "..." },
    },
  },
  { type: "content_block_delta", index: 5, delta: { type: "text_delta", text: "従来機とは仕組みが異なります。" } },
  { type: "content_block_stop", index: 5 },
  { type: "message_delta", delta: { stop_reason: "end_turn" }, usage: { output_tokens: 50 } },
  { type: "message_stop" },
]);

describe("ログイン", () => {
  it("許可リストにあるアカウントにはセッションを発行する", async () => {
    await allow("friend@example.com");
    const response = await call("/auth/google", { body: { idToken: await googleIdToken("Friend@Example.com") } });
    expect(response.status).toBe(200);
    const data = (await response.json()) as { token: string; email: string; expiresAt: number };
    expect(data.email).toBe("friend@example.com");
    expect(data.token.split(".")).toHaveLength(3);
    expect(data.expiresAt).toBeGreaterThan(Date.now() / 1000 + 29 * 24 * 3600);
  });

  it("許可リストにないアカウントは断る", async () => {
    const response = await call("/auth/google", { body: { idToken: await googleIdToken("stranger@example.com") } });
    expect(response.status).toBe(403);
    expect(((await response.json()) as any).error.code).toBe("not_allowed");
  });

  it("停止中のアカウントは断る", async () => {
    await allow("paused@example.com", "suspended");
    const response = await call("/auth/google", { body: { idToken: await googleIdToken("paused@example.com") } });
    expect(response.status).toBe(403);
  });

  it("別のアプリ向けに発行されたトークンは受け付けない", async () => {
    await allow("aud@example.com");
    const idToken = await googleIdToken("aud@example.com", { audience: "other-app.apps.googleusercontent.com" });
    const response = await call("/auth/google", { body: { idToken } });
    expect(response.status).toBe(401);
    expect(((await response.json()) as any).error.code).toBe("invalid_token");
  });

  it("Google 以外が発行したトークン・メール未確認・改ざんされたトークンは受け付けない", async () => {
    await allow("x@example.com");
    const wrongIssuer = await googleIdToken("x@example.com", { issuer: "https://evil.example" });
    const unverified = await googleIdToken("x@example.com", { emailVerified: false });
    const valid = await googleIdToken("x@example.com");
    const tampered = valid.slice(0, -6) + "AAAAAA";
    for (const idToken of [wrongIssuer, unverified, tampered, "not-a-jwt"]) {
      const response = await call("/auth/google", { body: { idToken } });
      expect(response.status).toBe(401);
    }
  });
});

describe("アクセス制御", () => {
  it("ログインしていないと質問できない", async () => {
    const response = await call("/ask", { body: question });
    expect(response.status).toBe(401);
    expect(aiRequests).toHaveLength(0);
  });

  it("でたらめなトークンや Google の ID トークンそのものでは質問できない", async () => {
    await allow("raw@example.com");
    for (const token of ["abc.def.ghi", await googleIdToken("raw@example.com")]) {
      const response = await call("/ask", { token, body: question });
      expect(response.status).toBe(401);
    }
    expect(aiRequests).toHaveLength(0);
  });

  it("許可リストから外すと、発行済みのセッションでも直ちに使えなくなる", async () => {
    const token = await login("revoked@example.com");
    await allow("revoked@example.com", "suspended");
    const response = await call("/ask", { token, body: question });
    expect(response.status).toBe(403);
    expect(((await response.json()) as any).error.code).toBe("not_allowed");

    await env.DB.prepare("DELETE FROM allowed_users WHERE email = ?1").bind("revoked@example.com").run();
    expect((await call("/summarize", { token, body: { messages: question.messages } })).status).toBe(403);
    expect(aiRequests).toHaveLength(0);
  });
});

describe("質問と回答", () => {
  it("回答を順に流し、出典と番号を付ける", async () => {
    const token = await login("ask@example.com");
    aiResponses.push(searchedAnswer);
    const response = await call("/ask", { token, body: question });
    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toContain("text/event-stream");

    const events = await readEvents(response);
    expect(events).toEqual([
      { type: "status", state: "searching", query: "量子コンピュータ とは" },
      { type: "delta", text: "結論から言うと、" },
      { type: "source", index: 1, title: "記事A", url: "https://example.com/a" },
      { type: "source", index: 2, title: "記事B", url: "https://example.com/b" },
      { type: "delta", text: "量子ビットを使う計算機です。" },
      { type: "delta", text: " [1][2]" },
      { type: "delta", text: "従来機とは仕組みが異なります。" },
      { type: "delta", text: " [1]" },
      { type: "done", sources: 2 },
    ]);
  });

  it("AI サービスには、スクショ・質問・Web検索の設定を送る。キーはサーバー側で付ける", async () => {
    const token = await login("payload@example.com");
    aiResponses.push(searchedAnswer);
    await call("/ask", {
      token,
      body: {
        image: IMAGE,
        messages: [
          { role: "user", text: "" },
          { role: "assistant", text: "説明です。" },
          { role: "user", text: "もう少し詳しく" },
        ],
      },
    });
    const sent = aiRequests[0];
    expect(sent.headers.get("x-api-key")).toBe("test-api-key");
    expect(sent.body.model).toBe("claude-sonnet-5-5");
    expect(sent.body.stream).toBe(true);
    expect(sent.body.tools).toEqual([
      {
        type: "web_search_20250305",
        name: "web_search",
        max_uses: 3,
        user_location: { type: "approximate", country: "JP", timezone: "Asia/Tokyo" },
      },
    ]);
    expect(sent.body.messages).toEqual([
      {
        role: "user",
        content: [
          { type: "image", source: { type: "base64", media_type: "image/jpeg", data: "AAAA" } },
          { type: "text", text: "この画面に写っている内容を説明してください。" },
        ],
      },
      { role: "assistant", content: "説明です。" },
      { role: "user", content: "もう少し詳しく" },
    ]);
  });

  it("検索が途中で区切られたら、受け取った内容をそのまま送り返して続きを取りに行く", async () => {
    const token = await login("pause@example.com");
    aiResponses.push(
      aiStream([
        { type: "content_block_start", index: 0, content_block: { type: "text", text: "" } },
        { type: "content_block_stop", index: 0 },
        { type: "content_block_start", index: 1, content_block: { type: "server_tool_use", id: "srvtoolu_9", name: "web_search" } },
        { type: "content_block_delta", index: 1, delta: { type: "input_json_delta", partial_json: '{"query":"q"}' } },
        { type: "content_block_stop", index: 1 },
        { type: "message_delta", delta: { stop_reason: "pause_turn" } },
        { type: "message_stop" },
      ]),
      aiStream([
        { type: "content_block_start", index: 0, content_block: { type: "text", text: "" } },
        { type: "content_block_delta", index: 0, delta: { type: "text_delta", text: "続きの回答です。" } },
        { type: "content_block_stop", index: 0 },
        { type: "message_delta", delta: { stop_reason: "end_turn" } },
        { type: "message_stop" },
      ]),
    );
    const events = await readEvents(await call("/ask", { token, body: question }));
    expect(events).toEqual([
      { type: "status", state: "searching", query: "q" },
      { type: "delta", text: "続きの回答です。" },
      { type: "done", sources: 0 },
    ]);
    expect(aiRequests).toHaveLength(2);
    const continued = aiRequests[1].body.messages;
    expect(continued).toHaveLength(2);
    expect(continued[1]).toEqual({
      role: "assistant",
      content: [{ type: "server_tool_use", id: "srvtoolu_9", name: "web_search", input: { query: "q" } }],
    });
  });

  it("回答の途中で AI サービスがエラーを返したら、エラーとして伝える", async () => {
    const token = await login("midfail@example.com");
    aiResponses.push(
      aiStream([
        { type: "content_block_start", index: 0, content_block: { type: "text", text: "" } },
        { type: "content_block_delta", index: 0, delta: { type: "text_delta", text: "途中まで" } },
        { type: "error", error: { type: "overloaded_error", message: "Overloaded" } },
      ]),
    );
    const events = await readEvents(await call("/ask", { token, body: question }));
    expect(events[0]).toEqual({ type: "delta", text: "途中まで" });
    expect(events[1].type).toBe("error");
    expect(events[1].code).toBe("busy");
    expect(events).toHaveLength(2);
  });

  it("文章が1文字も返らなかったら、完了ではなくエラーにする", async () => {
    const token = await login("empty@example.com");
    aiResponses.push(aiStream([{ type: "message_delta", delta: { stop_reason: "refusal" } }, { type: "message_stop" }]));
    const events = await readEvents(await call("/ask", { token, body: question }));
    expect(events).toHaveLength(1);
    expect(events[0].code).toBe("empty");
  });

  it("AI サービスが最初から失敗したら、通常のエラー応答を返す", async () => {
    const token = await login("upstream@example.com");
    aiResponses.push(() => Response.json({ type: "error", error: { type: "overloaded_error", message: "Overloaded" } }, { status: 529 }));
    const busy = await call("/ask", { token, body: question });
    expect(busy.status).toBe(503);
    expect(((await busy.json()) as any).error.code).toBe("busy");

    aiResponses.push(() => Response.json({ type: "error", error: { type: "authentication_error", message: "invalid x-api-key" } }, { status: 401 }));
    const auth = await call("/ask", { token, body: question });
    expect(auth.status).toBe(502);
    const body = JSON.stringify(await auth.json());
    expect(body).toContain("upstream_auth");
    expect(body).not.toContain("x-api-key");
  });

  it("1日の上限を超えると断り、AI サービスは呼ばない", async () => {
    const token = await login("limit@example.com");
    for (let i = 0; i < 3; i++) {
      aiResponses.push(searchedAnswer);
      expect((await call("/ask", { token, body: question })).status).toBe(200);
    }
    const over = await call("/ask", { token, body: question });
    expect(over.status).toBe(429);
    expect(((await over.json()) as any).error.code).toBe("limit_reached");
    expect(aiRequests).toHaveLength(3);

    // 上限は人ごと
    const other = await login("limit-other@example.com");
    aiResponses.push(searchedAnswer);
    expect((await call("/ask", { token: other, body: question })).status).toBe(200);

    // "0" は無制限
    aiResponses.push(searchedAnswer);
    expect((await call("/ask", { token, body: question, envOverrides: { DAILY_LIMIT: "0" } as any })).status).toBe(200);
  });

  it("形式の正しくないリクエストは断る", async () => {
    const token = await login("invalid@example.com");
    const cases: unknown[] = [
      { image: IMAGE, messages: [] },
      { image: IMAGE, messages: [{ role: "assistant", text: "回答で終わっている" }] },
      { image: IMAGE, messages: [{ role: "system", text: "x" }] },
      { image: { mediaType: "image/gif", data: "AAAA" }, messages: question.messages },
      { image: { mediaType: "image/jpeg", data: "これはbase64ではない" }, messages: question.messages },
      { image: IMAGE, messages: [{ role: "user", text: "あ".repeat(8001) }] },
      "文字列",
    ];
    for (const body of cases) {
      const response = await call("/ask", { token, body });
      expect(response.status).toBe(400);
    }
    expect(aiRequests).toHaveLength(0);
  });
});

describe("メモの下書き", () => {
  const messages = [
    { role: "user", text: "量子コンピュータとは?" },
    { role: "assistant", text: "量子ビットを使う計算機です。" },
  ];

  it("タイトルと要点を返す", async () => {
    const token = await login("memo@example.com");
    aiResponses.push(() =>
      Response.json({
        content: [
          { type: "thinking", thinking: "", signature: "s" },
          { type: "text", text: '```json\n{"title": "量子コンピュータの基本", "summary": "- 量子ビットを使う\\n- 従来機と仕組みが違う"}\n```' },
        ],
        stop_reason: "end_turn",
      }),
    );
    const response = await call("/summarize", { token, body: { messages } });
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ title: "量子コンピュータの基本", summary: "- 量子ビットを使う\n- 従来機と仕組みが違う" });
    expect(aiRequests[0].body.model).toBe("claude-haiku-5-5");
    expect(aiRequests[0].body.tools).toBeUndefined();
  });

  it("下書きが読み取れないときは、やり取りの冒頭から組み立てる", async () => {
    const token = await login("memo-fallback@example.com");
    aiResponses.push(() => Response.json({ content: [{ type: "text", text: "JSONではない返事" }] }));
    const response = await call("/summarize", { token, body: { messages } });
    expect(await response.json()).toEqual({ title: "量子コンピュータとは?", summary: "量子ビットを使う計算機です。" });
  });
});

describe("その他", () => {
  it("存在しない経路は 404", async () => {
    expect((await call("/nope", { body: {} })).status).toBe(404);
    expect((await call("/ask", { method: "GET" })).status).toBe(404);
  });

  it("稼働確認", async () => {
    const response = await call("/health", { method: "GET" });
    expect(await response.json()).toEqual({ ok: true });
  });
});
