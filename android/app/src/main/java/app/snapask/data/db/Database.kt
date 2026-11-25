package app.snapask.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val summary: String,
    val screenshotPath: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("noteId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    /** "user" または "assistant" */
    val role: String,
    val body: String,
    val position: Int,
)

@Entity(
    tableName = "sources",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("messageId")],
)
data class SourceEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val number: Int,
    val title: String,
    val url: String,
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    /** タイトル・要点・質問と回答の本文を対象に探す。 */
    @Query(
        """
        SELECT * FROM notes
        WHERE title LIKE '%' || :query || '%'
           OR summary LIKE '%' || :query || '%'
           OR id IN (SELECT noteId FROM messages WHERE body LIKE '%' || :query || '%')
        ORDER BY createdAt DESC
        """,
    )
    fun search(query: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observe(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun find(id: String): NoteEntity?

    @Query("SELECT * FROM messages WHERE noteId = :noteId ORDER BY position")
    suspend fun messagesOf(noteId: String): List<MessageEntity>

    @Query(
        """
        SELECT * FROM sources
        WHERE messageId IN (SELECT id FROM messages WHERE noteId = :noteId)
        ORDER BY number
        """,
    )
    suspend fun sourcesOf(noteId: String): List<SourceEntity>

    @Insert
    suspend fun insertNote(note: NoteEntity)

    @Insert
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Insert
    suspend fun insertSources(sources: List<SourceEntity>)

    @Query("UPDATE notes SET title = :title, summary = :summary, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateText(id: String, title: String, summary: String, updatedAt: Long)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(
    entities = [NoteEntity::class, MessageEntity::class, SourceEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
}
