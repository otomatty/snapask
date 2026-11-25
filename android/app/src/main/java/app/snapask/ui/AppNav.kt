package app.snapask.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.snapask.AppContainer

private const val ROUTE_NOTES = "notes"
private const val ROUTE_SETTINGS = "settings"

/**
 * 画面のつながり。
 *   未ログイン → ログイン
 *   起動 → メモ一覧 → メモ詳細 / 設定
 *   スクショの共有 → 質問と回答 → (保存) → メモ詳細
 */
@Composable
fun AppNav(container: AppContainer) {
    val session by container.sessions.session.collectAsStateWithLifecycle()
    if (session == null) {
        LoginScreen(container.auth)
        return
    }

    val navController = rememberNavController()
    val backToNotes: () -> Unit = { navController.popBackStack(ROUTE_NOTES, inclusive = false) }

    NavHost(navController = navController, startDestination = ROUTE_NOTES) {
        composable(ROUTE_NOTES) {
            val viewModel: NotesViewModel = viewModel { NotesViewModel(container.notes) }
            NotesScreen(
                viewModel = viewModel,
                onOpenNote = { id -> navController.navigate("note/$id") },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
            )
        }

        composable("ask/{name}") { entry ->
            val name = entry.arguments?.getString("name").orEmpty()
            val viewModel: AskViewModel = viewModel {
                AskViewModel(container.images.pendingFile(name), container)
            }
            AskScreen(
                viewModel = viewModel,
                onClose = backToNotes,
                onSaved = { id ->
                    navController.navigate("note/$id") { popUpTo(ROUTE_NOTES) }
                },
            )
        }

        composable("note/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val viewModel: NoteDetailViewModel = viewModel { NoteDetailViewModel(id, container.notes) }
            NoteDetailScreen(viewModel = viewModel, onBack = backToNotes)
        }

        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                email = session?.email.orEmpty(),
                auth = container.auth,
                onBack = backToNotes,
            )
        }
    }

    // 共有で受け取ったスクショがあれば、質問画面を開く。
    // 開いている質問画面があれば閉じてから開く(保存していないやり取りは破棄される)。
    val pending by container.inbox.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        val name = pending ?: return@LaunchedEffect
        container.inbox.value = null
        navController.navigate("ask/$name") { popUpTo(ROUTE_NOTES) }
    }
}
