package app.snapask

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.snapask.data.ApiClient
import app.snapask.data.AuthRepository
import app.snapask.data.ImageStore
import app.snapask.data.NoteRepository
import app.snapask.data.SessionStore
import app.snapask.data.db.AppDatabase
import kotlinx.coroutines.flow.MutableStateFlow

/** アプリ全体で1つずつ持つものをまとめたもの。 */
class AppContainer(context: Context) {
    val sessions = SessionStore(context)
    val images = ImageStore(context)
    val api = ApiClient(BuildConfig.API_BASE_URL, sessions)
    val auth = AuthRepository(api, sessions, BuildConfig.GOOGLE_WEB_CLIENT_ID)

    private val database: AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "snapask.db").build()
    val notes = NoteRepository(database, images)

    /** 共有で受け取り、まだ質問画面を開いていないスクショのファイル名。 */
    val inbox = MutableStateFlow<String?>(null)
}

class SnapAskApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
