package app.snapask.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.snapask.AppContainer
import app.snapask.data.ApiException
import app.snapask.data.AskEvent
import app.snapask.data.Author
import app.snapask.data.ChatMessage
import app.snapask.data.DEFAULT_QUESTION
import app.snapask.data.NoteDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class AskError(val message: String, val canRetry: Boolean)

data class AskUiState(
    val imageFile: File? = null,
    val input: String = "",
    val messages: List<ChatMessage> = emptyList(),
    /** 回答を受信中 */
    val streaming: Boolean = false,
    /** いま調べている検索語(回答の文章が届き始めるまで表示する) */
    val searchingQuery: String? = null,
    val error: AskError? = null,
    val saving: Boolean = false,
    val savedNoteId: String? = null,
) {
    val hasAnswer: Boolean
        get() = messages.any { it.author == Author.ASSISTANT && it.text.isNotBlank() }

    val canSave: Boolean
        get() = hasAnswer && !streaming && !saving
}

/** 1枚のスクショについての質問と回答。保存しないまま閉じると、やり取りもスクショも破棄する。 */
class AskViewModel(
    private val imageFile: File,
    private val container: AppContainer,
) : ViewModel() {

    private val _state = MutableStateFlow(AskUiState(imageFile = imageFile.takeIf { it.exists() }))
    val state: StateFlow<AskUiState> = _state.asStateFlow()

    /** true のあいだは、画面を閉じてもスクショを消さない(保存済み、またはログインし直して続ける場合)。 */
    private var keepImage = false

    fun onInputChange(value: String) {
        _state.update { it.copy(input = value) }
    }

    /** 質問を送る。最初の質問は空のままでも送れる。 */
    fun send() {
        val current = _state.value
        if (current.streaming || current.saving) return
        val typed = current.input.trim()
        if (typed.isEmpty() && current.messages.isNotEmpty()) return
        val question = typed.ifEmpty { DEFAULT_QUESTION }

        _state.update {
            it.copy(
                input = "",
                error = null,
                messages = it.messages + ChatMessage(author = Author.USER, text = question),
            )
        }
        requestAnswer()
    }

    /** 失敗した質問をもう一度送る。 */
    fun retry() {
        val current = _state.value
        if (current.streaming || current.messages.lastOrNull()?.author != Author.USER) return
        _state.update { it.copy(error = null) }
        requestAnswer()
    }

    private fun requestAnswer() {
        val history = _state.value.messages
        val answer = ChatMessage(author = Author.ASSISTANT, text = "")
        _state.update { it.copy(streaming = true, searchingQuery = null, messages = it.messages + answer) }

        viewModelScope.launch {
            try {
                val image = withContext(Dispatchers.IO) {
                    if (imageFile.exists()) imageFile.readBytes() else null
                }
                container.api.ask(image, history).collect { event ->
                    when (event) {
                        is AskEvent.Searching -> _state.update { it.copy(searchingQuery = event.query) }

                        is AskEvent.Delta -> updateAnswer(answer.id) { it.copy(text = it.text + event.text) }

                        is AskEvent.SourceFound -> updateAnswer(answer.id) { it.copy(sources = it.sources + event.source) }

                        is AskEvent.Done -> _state.update { it.copy(streaming = false, searchingQuery = null) }
                    }
                }
                // 完了の合図なしに終わることは通常ないが、念のため受信中の表示を解除する
                _state.update { it.copy(streaming = false, searchingQuery = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                onFailure(answer.id, e)
            } catch (e: Exception) {
                onFailure(answer.id, ApiException("internal", "問題が起きました。もう一度お試しください"))
            }
        }
    }

    private fun updateAnswer(id: String, transform: (ChatMessage) -> ChatMessage) {
        _state.update { state ->
            state.copy(
                searchingQuery = null,
                messages = state.messages.map { if (it.id == id) transform(it) else it },
            )
        }
    }

    private fun onFailure(answerId: String, error: ApiException) {
        // 途中まで届いた回答は不完全なので捨て、質問だけを残して再試行できるようにする
        _state.update { state ->
            state.copy(
                streaming = false,
                searchingQuery = null,
                messages = state.messages.filterNot { it.id == answerId },
                error = AskError(error.message, canRetry = error.code !in NOT_RETRYABLE),
            )
        }
        if (error.code == "unauthorized") {
            // ログインの期限切れ。ログインし直したあと、同じスクショで質問画面を開き直す
            keepImage = true
            container.inbox.value = imageFile.name
            container.sessions.clear()
        }
    }

    /** やり取りをメモとして保存する。タイトルと要点は AI に下書きしてもらう。 */
    fun save() {
        val current = _state.value
        if (!current.canSave) return
        _state.update { it.copy(saving = true, error = null) }

        viewModelScope.launch {
            val messages = current.messages.filter { it.text.isNotBlank() }
            val draft = try {
                container.api.summarize(messages).takeIf { it.title.isNotBlank() } ?: localDraft(messages)
            } catch (e: ApiException) {
                // 下書きが作れなくても、メモ自体は保存する(あとで編集できる)
                localDraft(messages)
            }
            try {
                val noteId = container.notes.create(
                    title = draft.title,
                    summary = draft.summary,
                    pendingScreenshot = imageFile.takeIf { it.exists() },
                    messages = messages,
                )
                keepImage = true
                _state.update { it.copy(saving = false, savedNoteId = noteId) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(saving = false, error = AskError("メモを保存できませんでした", canRetry = false))
                }
            }
        }
    }

    private fun localDraft(messages: List<ChatMessage>): NoteDraft {
        val question = messages.firstOrNull { it.author == Author.USER }?.text.orEmpty()
        return NoteDraft(title = question.replace("\n", " ").take(30).ifBlank { "メモ" }, summary = "")
    }

    override fun onCleared() {
        if (!keepImage) container.images.discard(imageFile)
    }

    private companion object {
        val NOT_RETRYABLE = setOf("not_allowed", "limit_reached", "misconfigured", "unauthorized")
    }
}
