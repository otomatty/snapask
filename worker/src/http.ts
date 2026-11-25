/** アプリに返すエラー。code はアプリ側の表示の出し分けに使う。 */
export class HttpError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

export function errorResponse(error: HttpError): Response {
  return json({ error: { code: error.code, message: error.message } }, error.status);
}

/**
 * JSON の本文を読む。サイズの上限を超えるものは読み込む前に断る
 * (スクショを含むため上限は大きめだが、無制限には受け付けない)。
 */
export async function readJson(request: Request, maxBytes: number): Promise<unknown> {
  const declared = Number(request.headers.get("content-length"));
  if (!Number.isFinite(declared) || declared <= 0) {
    throw new HttpError(411, "length_required", "Content-Length が必要です");
  }
  if (declared > maxBytes) {
    throw new HttpError(413, "too_large", "送信データが大きすぎます");
  }
  const buffer = await request.arrayBuffer();
  if (buffer.byteLength > maxBytes) {
    throw new HttpError(413, "too_large", "送信データが大きすぎます");
  }
  try {
    return JSON.parse(new TextDecoder().decode(buffer));
  } catch {
    throw new HttpError(400, "bad_request", "JSON として読み取れません");
  }
}

/** 本文の内容は記録しない。記録するのは経路・結果・件数などのメタ情報だけ。 */
export function log(message: string, fields: Record<string, unknown> = {}): void {
  console.log(JSON.stringify({ message, ...fields }));
}

export function logError(message: string, fields: Record<string, unknown> = {}): void {
  console.error(JSON.stringify({ message, ...fields }));
}
