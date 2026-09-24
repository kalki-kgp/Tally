package dev.pixelchutney.tally.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pixelchutney.tally.ai.ChatEngine
import dev.pixelchutney.tally.ai.ChatTurn
import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.data.db.ChatDao
import dev.pixelchutney.tally.data.db.ChatEntity
import dev.pixelchutney.tally.data.db.ChatMessageEntity
import dev.pixelchutney.tally.data.db.ChatSummary
import dev.pixelchutney.tally.data.settings.SecretStore
import dev.pixelchutney.tally.data.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AskUiState(
    val turns: List<ChatTurn> = emptyList(),
    val draft: String = "",
    val thinking: Boolean = false,
    val error: String? = null,
    val ready: Boolean = false,
    /** Saved conversations, newest first. */
    val chats: List<ChatSummary> = emptyList(),
    val currentChatId: Long? = null,
    val showHistory: Boolean = false,
)

@HiltViewModel
class AskViewModel @Inject constructor(
    private val chat: ChatEngine,
    private val chats: ChatDao,
    private val settings: SettingsStore,
    private val secrets: SecretStore,
) : ViewModel() {

    private val turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    private val draft = MutableStateFlow("")
    private val thinking = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val currentChatId = MutableStateFlow<Long?>(null)
    private val showHistory = MutableStateFlow(false)

    init {
        // A chat row is created when a question is asked, not when the screen is
        // opened, but a crash between the two can still leave an empty one behind.
        viewModelScope.launch { runCatching { chats.deleteEmptyChats() } }
    }

    private val conversation = combine(
        turns, draft, thinking, error, currentChatId,
    ) { history, text, busy, failure, chatId ->
        Conversation(history, text, busy, failure, chatId)
    }

    val state: StateFlow<AskUiState> = combine(
        conversation,
        settings.settings,
        chats.summariesFlow(),
        showHistory,
    ) { current, config, saved, historyOpen ->
        AskUiState(
            turns = current.turns,
            draft = current.draft,
            thinking = current.thinking,
            error = current.error,
            ready = config.aiEnabled && secrets.hasKey(),
            chats = saved,
            currentChatId = current.chatId,
            showHistory = historyOpen,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AskUiState())

    private data class Conversation(
        val turns: List<ChatTurn>,
        val draft: String,
        val thinking: Boolean,
        val error: String?,
        val chatId: Long?,
    )

    val suggestions = listOf(
        "How much did I spend on food this month?",
        "What changed vs last month?",
        "Which merchants am I paying most often?",
        "Where is my money actually leaking?",
    )

    fun setDraft(value: String) { draft.value = value }

    fun toggleHistory() { showHistory.value = !showHistory.value }

    fun send(question: String = draft.value) {
        val text = question.trim()
        if (text.isEmpty() || thinking.value) return

        turns.value = turns.value + ChatTurn(fromUser = true, text = text)
        draft.value = ""
        error.value = null
        thinking.value = true

        viewModelScope.launch {
            val history = turns.value.dropLast(1)
            // Created on the first question so the chat can be named after it.
            val chatId = currentChatId.value ?: startChat(text).also { currentChatId.value = it }
            runCatching { save(chatId, ChatTurn(fromUser = true, text = text)) }

            chat.ask(history, text)
                .onSuccess { answer ->
                    turns.value = turns.value + answer
                    runCatching { save(chatId, answer) }
                }
                .onFailure { error.value = it.message ?: "Something went wrong." }
            thinking.value = false
        }
    }

    /**
     * Starts over. The chat that was open is already saved, so this only lets go
     * of it — the next question begins a new one with no history attached.
     */
    fun newChat() {
        turns.value = emptyList()
        draft.value = ""
        error.value = null
        currentChatId.value = null
        showHistory.value = false
    }

    /** Reopens a saved conversation so it can be carried on where it stopped. */
    fun openChat(chatId: Long) {
        viewModelScope.launch {
            val stored = runCatching { chats.messages(chatId) }.getOrDefault(emptyList())
            turns.value = stored.map {
                ChatTurn(
                    fromUser = it.fromUser,
                    text = it.text,
                    toolsUsed = it.toolsUsed.lines().filter { line -> line.isNotBlank() },
                )
            }
            currentChatId.value = chatId
            error.value = null
            showHistory.value = false
        }
    }

    fun deleteChat(chatId: Long) {
        viewModelScope.launch {
            runCatching { chats.deleteChat(chatId) }
            // Deleting the conversation on screen should also clear the screen.
            if (currentChatId.value == chatId) newChat()
        }
    }

    private suspend fun startChat(firstQuestion: String): Long {
        val now = Time.now()
        return chats.insertChat(
            ChatEntity(title = titleFor(firstQuestion), createdAt = now, updatedAt = now)
        )
    }

    private suspend fun save(chatId: Long, turn: ChatTurn) {
        val now = Time.now()
        chats.insertMessage(
            ChatMessageEntity(
                chatId = chatId,
                fromUser = turn.fromUser,
                text = turn.text,
                toolsUsed = turn.toolsUsed.joinToString("\n"),
                createdAt = now,
            )
        )
        chats.touch(chatId, now)
    }

    /** The question itself, trimmed to something that fits one line in a list. */
    private fun titleFor(question: String): String {
        val cleaned = question.trim().replace(Regex("\\s+"), " ")
        return if (cleaned.length <= TITLE_LENGTH) cleaned
        else cleaned.take(TITLE_LENGTH).trimEnd().plus("…")
    }

    private companion object {
        const val TITLE_LENGTH = 48
    }
}
