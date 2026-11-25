package app.snapask

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import app.snapask.ui.AppNav
import app.snapask.ui.theme.SnapAskTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as SnapAskApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            lifecycleScope.launch { container.images.clearStalePending() }
            handleShare(intent)
        }

        setContent {
            SnapAskTheme {
                AppNav(container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** 共有メニューから渡されたスクショを取り込み、質問画面を開く合図を出す。 */
    private fun handleShare(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND) return
        if (intent.type?.startsWith("image/") != true) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return

        lifecycleScope.launch {
            val file = container.images.importShared(uri)
            if (file != null) {
                container.inbox.value = file.name
            } else {
                Toast.makeText(this@MainActivity, "画像を読み込めませんでした", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
