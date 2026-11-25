package app.snapask.data

import java.util.UUID

enum class Author { USER, ASSISTANT }

/** 回答の根拠になったWebページ。number は回答本文中の [1] などの番号に対応する。 */
data class Source(
    val number: Int,
    val title: String,
    val url: String,
)

/** 質問または回答の1件。出典は回答ごとに持つ。 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val author: Author,
    val text: String,
    val sources: List<Source> = emptyList(),
)

data class Note(
    val id: String,
    val title: String,
    val summary: String,
    val screenshotPath: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class NoteDraft(val title: String, val summary: String)

data class Session(
    val token: String,
    val email: String,
    /** 有効期限(UNIX秒) */
    val expiresAt: Long,
)

/** サーバーや通信のエラー。code で表示や対処を出し分ける。 */
class ApiException(
    val code: String,
    override val message: String,
) : Exception(message)

/** 質問を空のまま送ったときに使う文。 */
const val DEFAULT_QUESTION = "この内容を説明して"
