/**
 * AI サービス(Claude API)とのやり取りをこのファイルに閉じ込めている。
 * 別のサービスに替えるときは、このファイルの startAnswer / summarize を差し替えればよい。
 *
 * 参照した仕様:
 *   https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-search-tool
 *   https://platform.claude.com/docs/en/build-with-claude/streaming
 */
import { HttpError, logError } from "./http";
import { ASK_SYSTEM_PROMPT, DEFAULT_QUESTION, SUMMARY_SYSTEM_PROMPT } from "./prompts";

const API_URL = "https://api.anthropic.com/v1/messages";
const API_VERSION = "2026-10-21";
/** 検索が長引いて途中で区切られたとき(pause_turn)に、続きを取りに行く回数の上限。 */
const MAX_CONTINUATIONS = 3;

export interface ChatTurn {
  role: "user" | "assistant";
  text: string;
}

export interface ImageInput {
  mediaType: string;
  /** base64 */
  data: string;
}

/** アプリへ流すイベント。1行のJSONとして SSE の data に載せる。 */
export type ClientEvent =
  | { type: "status"; state: "searching"; query: string }
  | { type: "delta"; text: string }
  | { type: "source"; index: number; title: string; url: string }
  | { type: "done"; sources: number }
  | { type: "error"; code: string; message: string };

type Block = { type: string; [key: string]: unknown };
type ApiMessage = { role: "user" | "assistant"; content: string | Block[] };

/** 会話を API の形にする。スクショは最初の質問にだけ添える。 */
export function buildMessages(image: ImageInput | null, turns: ChatTurn[]): ApiMessage[] {
  const messages: ApiMessage[] = [];
  let imageAttached = false;
  for (const turn of turns) {
    const text = turn.text.trim();
    if (turn.role === "assistant") {
      if (text) messages.push({ role: "assistant", content: text });
      continue;
    }
    const question = text || DEFAULT_QUESTION;
    if (image && !imageAttached) {
      imageAttached = true;
      messages.push({
        role: "user",
        content: [
          { type: "image", source: { type: "base64", media_type: image.mediaType, data: image.data } },
          { type: "text", text: question },
        ],
      });
    } else {
      messages.push({ role: "user", content: question });
    }
  }
  return messages;
}

async function callApi(env: Env, body: Record<string, unknown>): Promise<Response> {
  let response: Response;
  try {
    response = await fetch(API_URL, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-api-key": env.ANTHROPIC_API_KEY,
        "anthropic-version": API_VERSION,
      },
      body: JSON.stringify(body),
    });
  } catch (error) {
    logError("ai request failed", { error: error instanceof Error ? error.message : String(error) });
    throw new HttpError(503, "busy", "AIサービスに接続できませんでした。少し待ってからもう一度お試しください");
  }
  if (response.ok) return response;

  // エラー本文は小さく、ユーザーの入力は含まれない。原因調査のために種類と文言だけ記録する。
  let detail: { type?: string; message?: string } = {};
  try {
    const parsed = (await response.json()) as { error?: { type?: string; message?: string } };
    detail = parsed.error ?? {};
  } catch {
    // 本文が読めなくても、ステータスだけで振り分ける
  }
  logError("ai request rejected", { status: response.status, type: detail.type, detail: detail.message });

  if (response.status === 401 || response.status === 403) {
    throw new HttpError(502, "upstream_auth", "AIサービスの設定に問題があります。管理者に連絡してください");
  }
  if (response.status === 429 || response.status >= 500) {
    throw new HttpError(503, "busy", "AIサービスが混み合っています。少し待ってからもう一度お試しください");
  }
  throw new HttpError(502, "upstream_rejected", "AIサービスがこの内容を処理できませんでした");
}

/** SSE のストリームから data の中身を1件ずつ取り出す。 */
async function* sseData(body: ReadableStream<Uint8Array>): AsyncGenerator<string> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, "\n");
      let boundary = buffer.indexOf("\n\n");
      while (boundary !== -1) {
        const rawEvent = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        const data = rawEvent
          .split("\n")
          .filter((line) => line.startsWith("data:"))
          .map((line) => line.slice(5).trimStart())
          .join("\n");
        if (data) yield data;
        boundary = buffer.indexOf("\n\n");
      }
    }
  } finally {
    await reader.cancel().catch(() => undefined);
  }
}

interface PumpResult {
  stopReason: string | null;
  /** 受け取った内容ブロック。続きを取りに行くとき、そのまま送り返す。 */
  blocks: Block[];
  wroteText: boolean;
}

/**
 * AI からのストリームを読み、アプリ向けのイベントに変換する。
 * 出典は URL ごとに通し番号を振り、引用された文の直後に [1] の形で番号を差し込む。
 */
async function pump(
  body: ReadableStream<Uint8Array>,
  sources: Map<string, number>,
  emit: (event: ClientEvent) => Promise<void>,
): Promise<PumpResult> {
  const blocks: Block[] = [];
  const partialJson = new Map<number, string>();
  const pendingMarks = new Map<number, number[]>();
  let stopReason: string | null = null;
  let wroteText = false;

  for await (const data of sseData(body)) {
    let event: any;
    try {
      event = JSON.parse(data);
    } catch {
      continue;
    }
    const index: number = event.index;

    switch (event.type) {
      case "content_block_start":
        blocks[index] = { ...event.content_block };
        break;

      case "content_block_delta": {
        const block = blocks[index];
        const delta = event.delta;
        if (!block || !delta) break;
        if (delta.type === "text_delta" && typeof delta.text === "string") {
          block.text = ((block.text as string | undefined) ?? "") + delta.text;
          if (delta.text) {
            wroteText = true;
            await emit({ type: "delta", text: delta.text });
          }
        } else if (delta.type === "citations_delta" && delta.citation) {
          const citations = (block.citations as unknown[] | undefined) ?? [];
          citations.push(delta.citation);
          block.citations = citations;
          const url: unknown = delta.citation.url;
          if (typeof url === "string" && url) {
            let number = sources.get(url);
            if (number === undefined) {
              number = sources.size + 1;
              sources.set(url, number);
              const title = typeof delta.citation.title === "string" && delta.citation.title ? delta.citation.title : url;
              await emit({ type: "source", index: number, title, url });
            }
            const marks = pendingMarks.get(index) ?? [];
            if (!marks.includes(number)) marks.push(number);
            pendingMarks.set(index, marks);
          }
        } else if (delta.type === "input_json_delta" && typeof delta.partial_json === "string") {
          partialJson.set(index, (partialJson.get(index) ?? "") + delta.partial_json);
        } else if (delta.type === "thinking_delta" && typeof delta.thinking === "string") {
          block.thinking = ((block.thinking as string | undefined) ?? "") + delta.thinking;
        } else if (delta.type === "signature_delta") {
          block.signature = delta.signature;
        }
        break;
      }

      case "content_block_stop": {
        const block = blocks[index];
        if (!block) break;
        const raw = partialJson.get(index);
        if (raw !== undefined) {
          try {
            block.input = JSON.parse(raw || "{}");
          } catch {
            block.input = {};
          }
          partialJson.delete(index);
        }
        if (block.type === "server_tool_use" && block.name === "web_search") {
          const query = (block.input as { query?: unknown } | undefined)?.query;
          if (typeof query === "string" && query) {
            await emit({ type: "status", state: "searching", query });
          }
        }
        const marks = pendingMarks.get(index);
        if (marks?.length) {
          // 番号は表示用。block.text には足さない(送り返す内容を変えないため)。
          await emit({ type: "delta", text: " " + marks.map((n) => `[${n}]`).join("") });
          pendingMarks.delete(index);
        }
        break;
      }

      case "message_delta":
        if (typeof event.delta?.stop_reason === "string") stopReason = event.delta.stop_reason;
        break;

      case "error":
        logError("ai stream error", { type: event.error?.type, detail: event.error?.message });
        throw new HttpError(503, "busy", "AIサービスが混み合っています。少し待ってからもう一度お試しください");

      default:
        break;
    }
  }

  // 空の文章ブロックは送り返すと拒否されるので除く。
  const kept = blocks.filter((block) => block && !(block.type === "text" && !block.text));
  return { stopReason, blocks: kept, wroteText };
}

function positiveInt(value: string | undefined, fallback: number): number {
  const parsed = Number.parseInt(value ?? "", 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

/**
 * 回答の生成を始め、アプリへ流すストリームを返す。
 * 最初の呼び出しが失敗した場合は、ストリームを返す前に例外を投げる(通常のエラー応答になる)。
 */
export async function startAnswer(
  env: Env,
  image: ImageInput | null,
  turns: ChatTurn[],
): Promise<{ stream: ReadableStream<Uint8Array>; finished: Promise<void> }> {
  const messages = buildMessages(image, turns);
  const request = {
    model: env.ASK_MODEL,
    max_tokens: 8000,
    system: ASK_SYSTEM_PROMPT,
    output_config: { effort: env.ASK_EFFORT },
    tools: [
      {
        type: "web_search_20250305",
        name: "web_search",
        max_uses: positiveInt(env.MAX_SEARCHES, 3),
        user_location: { type: "approximate", country: "JP", timezone: "Asia/Tokyo" },
      },
    ],
    stream: true,
  };

  const first = await callApi(env, { ...request, messages });

  const { readable, writable } = new TransformStream<Uint8Array, Uint8Array>();
  const writer = writable.getWriter();
  const encoder = new TextEncoder();
  const emit = async (event: ClientEvent): Promise<void> => {
    await writer.write(encoder.encode(`data: ${JSON.stringify(event)}\n\n`));
  };

  const finished = (async () => {
    const sources = new Map<string, number>();
    let wroteText = false;
    try {
      let response = first;
      for (let round = 0; ; round++) {
        if (!response.body) throw new HttpError(502, "upstream_rejected", "AIサービスから応答がありませんでした");
        const result = await pump(response.body, sources, emit);
        wroteText ||= result.wroteText;
        if (result.stopReason !== "pause_turn" || round >= MAX_CONTINUATIONS) break;
        messages.push({ role: "assistant", content: result.blocks });
        response = await callApi(env, { ...request, messages });
      }
      if (wroteText) {
        await emit({ type: "done", sources: sources.size });
      } else {
        await emit({ type: "error", code: "empty", message: "回答を作成できませんでした。質問を変えてもう一度お試しください" });
      }
    } catch (error) {
      const known = error instanceof HttpError ? error : null;
      if (!known) {
        logError("answer stream failed", { error: error instanceof Error ? error.message : String(error) });
      }
      // アプリ側が切断していると書き込めないが、そのときは伝える相手がいないので無視する。
      await emit({
        type: "error",
        code: known?.code ?? "internal",
        message: known?.message ?? "回答の途中で問題が起きました。もう一度お試しください",
      }).catch(() => undefined);
    } finally {
      await writer.close().catch(() => undefined);
    }
  })();

  return { stream: readable, finished };
}

function fallbackSummary(turns: ChatTurn[]): { title: string; summary: string } {
  const question = turns.find((turn) => turn.role === "user" && turn.text.trim())?.text.trim() ?? "";
  const answer = [...turns].reverse().find((turn) => turn.role === "assistant")?.text.trim() ?? "";
  return {
    title: (question || answer || "メモ").replace(/\s+/g, " ").slice(0, 30),
    summary: answer.slice(0, 300),
  };
}

/** メモのタイトルと要点の下書きを作る。うまく作れないときは、やり取りの冒頭から組み立てる。 */
export async function summarize(env: Env, turns: ChatTurn[]): Promise<{ title: string; summary: string }> {
  const transcript = turns
    .filter((turn) => turn.text.trim())
    .map((turn) => `${turn.role === "user" ? "質問" : "回答"}: ${turn.text.trim()}`)
    .join("\n\n");

  const response = await callApi(env, {
    model: env.SUMMARY_MODEL,
    max_tokens: 2000,
    system: SUMMARY_SYSTEM_PROMPT,
    output_config: { effort: "low" },
    messages: [{ role: "user", content: transcript }],
  });

  const fallback = fallbackSummary(turns);
  try {
    const result = (await response.json()) as { content?: Block[] };
    const text = (result.content ?? [])
      .filter((block) => block.type === "text" && typeof block.text === "string")
      .map((block) => block.text as string)
      .join("");
    const start = text.indexOf("{");
    const end = text.lastIndexOf("}");
    if (start === -1 || end <= start) return fallback;
    const parsed = JSON.parse(text.slice(start, end + 1)) as { title?: unknown; summary?: unknown };
    const title = typeof parsed.title === "string" ? parsed.title.trim().slice(0, 60) : "";
    const summary = typeof parsed.summary === "string" ? parsed.summary.trim() : "";
    return { title: title || fallback.title, summary: summary || fallback.summary };
  } catch {
    return fallback;
  }
}
