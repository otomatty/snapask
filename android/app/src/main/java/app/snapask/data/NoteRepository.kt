package app.snapask.data

import androidx.room.withTransaction
import app.snapask.data.db.AppDatabase
import app.snapask.data.db.MessageEntity
import app.snapask.data.db.NoteEntity
import app.snapask.data.db.SourceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID

/** メモの保存と読み出し。メモはこの端末の中にだけ置く。 */
class NoteRepository(
    private val database: AppDatabase,
    private val images: ImageStore,
) {
    private val dao = database.noteDao()

    fun observeNotes(query: String): Flow<List<Note>> {
        val trimmed = query.trim()
        val source = if (trimmed.isEmpty()) dao.observeAll() else dao.search(trimmed)
        return source.map { list -> list.map { it.toNote() } }
    }

    fun observeNote(id: String): Flow<Note?> = dao.observe(id).map { it?.toNote() }

    suspend fun loadMessages(noteId: String): List<ChatMessage> {
        val sourcesByMessage = dao.sourcesOf(noteId).groupBy { it.messageId }
        return dao.messagesOf(noteId).map { message ->
            ChatMessage(
                id = message.id,
                author = if (message.role == ROLE_USER) Author.USER else Author.ASSISTANT,
                text = message.body,
                sources = sourcesByMessage[message.id].orEmpty().map { Source(it.number, it.title, it.url) },
            )
        }
    }

    /** 質問と回答のやり取りをメモとして保存し、メモのIDを返す。 */
    suspend fun create(
        title: String,
        summary: String,
        pendingScreenshot: File?,
        messages: List<ChatMessage>,
    ): String {
        val noteId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val screenshot = pendingScreenshot?.let { images.keep(it) }
        val kept = messages.filter { it.text.isNotBlank() }

        database.withTransaction {
            dao.insertNote(
                NoteEntity(
                    id = noteId,
                    title = title,
                    summary = summary,
                    screenshotPath = screenshot?.absolutePath,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            dao.insertMessages(
                kept.mapIndexed { index, message ->
                    MessageEntity(
                        id = message.id,
                        noteId = noteId,
                        role = if (message.author == Author.USER) ROLE_USER else ROLE_ASSISTANT,
                        body = message.text,
                        position = index,
                    )
                },
            )
            dao.insertSources(
                kept.flatMap { message ->
                    message.sources.map { source ->
                        SourceEntity(
                            id = UUID.randomUUID().toString(),
                            messageId = message.id,
                            number = source.number,
                            title = source.title,
                            url = source.url,
                        )
                    }
                },
            )
        }
        return noteId
    }

    suspend fun updateText(id: String, title: String, summary: String) {
        dao.updateText(id, title.trim(), summary.trim(), System.currentTimeMillis())
    }

    /** メモを削除する。スクショの画像も一緒に消す。 */
    suspend fun delete(id: String) {
        val note = dao.find(id) ?: return
        dao.delete(id)
        note.screenshotPath?.let { images.discard(File(it)) }
    }

    private fun NoteEntity.toNote() = Note(id, title, summary, screenshotPath, createdAt, updatedAt)

    private companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}
