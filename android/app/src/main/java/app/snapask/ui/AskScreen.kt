package app.snapask.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.snapask.data.Author
import app.snapask.ui.components.MessageItem
import app.snapask.ui.components.ScreenshotImage

/**
 * 質問と回答の画面(PRD の S3・S4)。
 * 最初はスクショと入力欄だけを見せ、送信後は同じ画面で回答と追加質問を続ける。
 */
@Composable
fun AskScreen(
    viewModel: AskViewModel,
    onClose: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasConversation = state.messages.isNotEmpty()
    var confirmClose by remember { mutableStateOf(false) }
    var screenshotExpanded by remember { mutableStateOf<Boolean?>(null) }

    val requestClose: () -> Unit = {
        if (hasConversation) {
            confirmClose = true
        } else {
            onClose()
        }
    }
    BackHandler(onBack = requestClose)

    val savedNoteId = state.savedNoteId
    LaunchedEffect(savedNoteId) {
        if (savedNoteId != null) onSaved(savedNoteId)
    }

    // 新しい質問を送ったら、その質問が画面の上端に来るように動かす(回答はその下に流れてくる)
    val listState = rememberLazyListState()
    val questionCount = state.messages.count { it.author == Author.USER }
    LaunchedEffect(questionCount) {
        val lastQuestion = state.messages.indexOfLast { it.author == Author.USER }
        if (lastQuestion >= 0) {
            val headerItems = if (state.imageFile != null) 1 else 0
            listState.animateScrollToItem(headerItems + lastQuestion)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = requestClose) {
                        Icon(Icons.Filled.Close, contentDescription = "閉じる")
                    }
                },
                actions = {
                    if (state.saving) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(20.dp))
                    } else if (state.hasAnswer) {
                        TextButton(onClick = viewModel::save, enabled = state.canSave) {
                            Text("メモに保存", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .imePadding(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                val image = state.imageFile
                if (image != null) {
                    item(key = "screenshot") {
                        ScreenshotImage(
                            file = image,
                            expanded = screenshotExpanded ?: !hasConversation,
                            onToggle = { screenshotExpanded = !(screenshotExpanded ?: !hasConversation) },
                        )
                    }
                }

                val lastId = state.messages.lastOrNull()?.id
                items(state.messages, key = { it.id }) { message ->
                    MessageItem(
                        message = message,
                        inProgress = state.streaming && message.id == lastId,
                    )
                }

                val waiting = state.streaming && state.messages.lastOrNull()?.text.isNullOrEmpty()
                if (waiting) {
                    item(key = "status") { StatusRow(query = state.searchingQuery) }
                }

                val error = state.error
                if (error != null) {
                    item(key = "error") { ErrorCard(error = error, onRetry = viewModel::retry) }
                }
            }

            InputBar(
                value = state.input,
                onValueChange = viewModel::onInputChange,
                onSend = viewModel::send,
                placeholder = if (hasConversation) "続けて質問する" else "質問を入力(空のままでも送れます)",
                enabled = !state.streaming && !state.saving && (!hasConversation || state.input.isNotBlank()),
            )
        }
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("保存せずに閉じますか?") },
            text = { Text("このやり取りとスクショは残りません。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClose = false
                        onClose()
                    },
                ) { Text("閉じる") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClose = false }) { Text("続ける") }
            },
        )
    }
}

@Composable
private fun StatusRow(query: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (query.isNullOrBlank()) "読み取っています…" else "Webで調べています:$query",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorCard(error: AskError, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = error.message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 6.dp),
            )
            if (error.canRetry) {
                TextButton(onClick = onRetry) { Text("もう一度") }
            }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    placeholder: String,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder) },
            maxLines = 4,
            shape = RoundedCornerShape(26.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = onSend,
            enabled = enabled,
            modifier = Modifier.size(52.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "送信")
        }
    }
}
