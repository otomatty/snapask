package app.snapask.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 回答の受信中に届く出来事。 */
sealed interface AskEvent {
    data class Searching(val query: String) : AskEvent
    data class Delta(val text: String) : AskEvent
    data class SourceFound(val source: Source) : AskEvent
    data class Done(val sourceCount: Int) : AskEvent
}

/**
 * 中継サーバーとの通信。
 * AI のキーはサーバーにあり、アプリはサーバーが発行したセッションだけを持つ。
 */
class ApiClient(
    baseUrl: String,
    private val sessions: SessionStore,
) {
    private val baseUrl = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // 検索中は次のデータが届くまで間が空くので、読み取りの待ち時間は長めにする
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Google の ID トークンをサーバーに渡し、セッションを受け取る。 */
    suspend fun signIn(idToken: String): Session = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("idToken", idToken) }
        execute(post("/auth/google", body, token = null)).use { response ->
            val data = parseObject(readBody(response))
            Session(
                token = data.string("token") ?: throw malformed(),
                email = data.string("email") ?: throw malformed(),
                expiresAt = data["expiresAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: throw malformed(),
            )
        }
    }

    /** スクショと会話を送り、回答を少しずつ受け取る。 */
    fun ask(imageJpeg: ByteArray?, messages: List<ChatMessage>): Flow<AskEvent> = flow<AskEvent> {
        val body = buildJsonObject {
            if (imageJpeg != null) {
                putJsonObject("image") {
                    put("mediaType", "image/jpeg")
                    put("data", Base64.encodeToString(imageJpeg, Base64.NO_WRAP))
                }
            }
            put("messages", messagesJson(messages))
        }
        val call = http.newCall(post("/ask", body, token = requireToken()))
        // 画面を閉じるなどして受信をやめたら、通信も止める
        val cancelOnCompletion = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            val response = try {
                call.execute()
            } catch (e: IOException) {
                throw network()
            }
            response.use {
                if (!response.isSuccessful) throw errorFrom(response)
                val source = response.body?.source() ?: throw malformed()
                var finished = false
                try {
                    while (!finished) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val event = parseObject(line.removePrefix("data:").trim())
                        when (event.string("type")) {
                            "status" -> emit(AskEvent.Searching(event.string("query").orEmpty()))
                            "delta" -> emit(AskEvent.Delta(event.string("text").orEmpty()))
                            "source" -> emit(
                                AskEvent.SourceFound(
                                    Source(
                                        number = event["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                                        title = event.string("title").orEmpty(),
                                        url = event.string("url").orEmpty(),
                                    ),
                                ),
                            )
                            "done" -> {
                                emit(AskEvent.Done(event["sources"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0))
                                finished = true
                            }
                            "error" -> throw ApiException(
                                event.string("code") ?: "internal",
                                event.string("message") ?: "回答の途中で問題が起きました",
                            )
                        }
                    }
                } catch (e: IOException) {
                    throw network()
                }
                if (!finished) throw ApiException("interrupted", "回答の途中で通信が切れました")
            }
        } finally {
            cancelOnCompletion.dispose()
        }
    }.flowOn(Dispatchers.IO)

    /** メモのタイトルと要点の下書きを作ってもらう。 */
    suspend fun summarize(messages: List<ChatMessage>): NoteDraft = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("messages", messagesJson(messages)) }
        execute(post("/summarize", body, token = requireToken())).use { response ->
            val data = parseObject(readBody(response))
            NoteDraft(
                title = data.string("title").orEmpty(),
                summary = data.string("summary").orEmpty(),
            )
        }
    }

    private fun messagesJson(messages: List<ChatMessage>) = buildJsonArray {
        for (message in messages) {
            // 出典番号は表示用に差し込まれたものなので、送り返すときは外す
            val text = if (message.author == Author.ASSISTANT) {
                message.text.replace(CITATION_MARK, "").trim()
            } else {
                message.text
            }
            if (message.author == Author.ASSISTANT && text.isEmpty()) continue
            add(
                buildJsonObject {
                    put("role", if (message.author == Author.USER) "user" else "assistant")
                    put("text", text.take(MAX_TEXT_CHARS))
                },
            )
        }
    }

    private fun post(path: String, body: JsonObject, token: String?): Request {
        if (baseUrl.isEmpty()) {
            throw ApiException("misconfigured", "サーバーのURLが設定されていません(local.properties を確認してください)")
        }
        val builder = Request.Builder()
            .url(baseUrl + path)
            .post(body.toString().toRequestBody(JSON_TYPE))
        if (token != null) builder.header("Authorization", "Bearer $token")
        return builder.build()
    }

    private fun requireToken(): String =
        sessions.current()?.token ?: throw ApiException("unauthorized", "ログインが必要です")

    private fun execute(request: Request): Response {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw network()
        }
        if (!response.isSuccessful) {
            response.use { throw errorFrom(it) }
        }
        return response
    }

    private fun readBody(response: Response): String =
        try {
            response.body?.string().orEmpty()
        } catch (e: IOException) {
            throw network()
        }

    private fun errorFrom(response: Response): ApiException {
        val fallback = ApiException("http_${response.code}", "サーバーでエラーが起きました(${response.code})")
        return try {
            val error = parseObject(response.body?.string().orEmpty())["error"]?.jsonObject ?: return fallback
            ApiException(error.string("code") ?: fallback.code, error.string("message") ?: fallback.message)
        } catch (e: Exception) {
            fallback
        }
    }

    private fun parseObject(text: String): JsonObject =
        try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw malformed()
        }

    private fun JsonObject.string(key: String): String? {
        val element = this[key] ?: return null
        return try {
            element.jsonPrimitive.takeIf { it.isString }?.content
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun network() = ApiException("network", "通信できませんでした。電波の状態を確認して、もう一度お試しください")

    private fun malformed() = ApiException("malformed", "サーバーからの応答を読み取れませんでした")

    private companion object {
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        val CITATION_MARK = Regex("""\s?\[\d+\]""")
        const val MAX_TEXT_CHARS = 8000
    }
}
