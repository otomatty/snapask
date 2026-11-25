package app.snapask.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.snapask.data.ChatMessage
import app.snapask.data.Note
import app.snapask.data.NoteRepository
import app.snapask.ui.components.MessageItem
import app.snapask.ui.components.RichText
import app.snapask.ui.components.ScreenshotImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class NoteDetailViewModel(
    private val noteId: String,
    private val repository: NoteRepository,
) : ViewModel() {

    val note: StateFlow<Note?> = repository.observeNote(noteId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    init {
        viewModelScope.launch { _messages.value = repository.loadMessages(noteId) }
    }

    fun saveText(title: String, summary: String) {
        viewModelScope.launch { repository.updateText(noteId, title, summary) }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.delete(noteId)
            onDeleted()
        }
    }
}

@Composable
fun NoteDetailScreen(viewModel: NoteDetailViewModel, onBack: () -> Unit) {
    val note by viewModel.note.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val current = note
    if (editing && current != null) {
        EditNote(
            note = current,
            onCancel = { editing = false },
            onSave = { title, summary ->
                viewModel.saveText(title, summary)
                editing = false
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { editing = true }, enabled = current != null) {
                        Icon(Icons.Filled.Edit, contentDescription = "編集")
                    }
                    IconButton(onClick = { confirmDelete = true }, enabled = current != null) {
                        Icon(Icons.Filled.Delete, contentDescription = "削除")
                    }
                },
            )
        },
    ) { inner ->
        if (current == null) return@Scaffold

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 40.dp),
        ) {
            Text(
                text = formatDate(current.createdAt),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = current.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (current.summary.isNotBlank()) {
                Spacer(Modifier.height(20.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionLabel("要点")
                        Spacer(Modifier.height(8.dp))
                        RichText(text = current.summary)
                    }
                }
            }

            val screenshot = current.screenshotPath?.let { File(it) }?.takeIf { it.exists() }
            if (screenshot != null) {
                var expanded by remember { mutableStateOf(false) }
                Spacer(Modifier.height(24.dp))
                SectionLabel("スクリーンショット")
                Spacer(Modifier.height(8.dp))
                ScreenshotImage(file = screenshot, expanded = expanded, onToggle = { expanded = !expanded })
            }

            if (messages.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                SectionLabel("質問と回答")
                for (message in messages) {
                    Spacer(Modifier.height(16.dp))
                    MessageItem(message = message)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("このメモを削除しますか?") },
            text = { Text("スクショも一緒に削除されます。元に戻せません。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.delete(onDeleted = onBack)
                    },
                ) { Text("削除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("キャンセル") }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** タイトルと要点を自分の言葉に直す。 */
@Composable
private fun EditNote(
    note: Note,
    onCancel: () -> Unit,
    onSave: (title: String, summary: String) -> Unit,
) {
    var title by rememberSaveable(note.id) { mutableStateOf(note.title) }
    var summary by rememberSaveable(note.id) { mutableStateOf(note.summary) }
    BackHandler(onBack = onCancel)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("メモを編集", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = "編集をやめる")
                    }
                },
                actions = {
                    TextButton(onClick = { onSave(title, summary) }, enabled = title.isNotBlank()) {
                        Text("保存", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.width(4.dp))
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("タイトル") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = summary,
                onValueChange = { summary = it },
                label = { Text("要点") },
                minLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
