package dev.pixelchutney.tally.ui.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pixelchutney.tally.R
import dev.pixelchutney.tally.ai.ChatTurn
import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.data.db.ChatSummary
import dev.pixelchutney.tally.ui.components.EyebrowLabel
import dev.pixelchutney.tally.ui.components.TallyCard
import dev.pixelchutney.tally.ui.theme.Tally

/**
 * Questions in, answers grounded in the actual database. The tool names are shown
 * under each answer so it is always visible which numbers were looked up rather
 * than recalled.
 */
@Composable
fun AskScreen(
    contentPadding: PaddingValues,
    onOpenSettings: () -> Unit,
    viewModel: AskViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = Tally.colors
    val listState = rememberLazyListState()

    LaunchedEffect(state.turns.size, state.thinking) {
        if (state.turns.isNotEmpty()) listState.animateScrollToItem(state.turns.size)
    }

    Column(
        Modifier
            .background(colors.paper)
            .imePadding()
    ) {
        Row(
            Modifier
                .statusBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Ask",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.ink,
                modifier = Modifier.weight(1f),
            )
            if (state.chats.isNotEmpty()) {
                Text(
                    if (state.showHistory) "Close" else "Chats (${state.chats.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.graphite,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { viewModel.toggleHistory() }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
                Spacer(Modifier.size(4.dp))
            }
            if (state.turns.isNotEmpty()) {
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(colors.card)
                        .clickable { viewModel.newChat() }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_new_chat),
                        contentDescription = null,
                        tint = colors.graphite,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "New chat",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.graphite,
                    )
                }
            }
        }

        if (state.showHistory) {
            ChatHistory(
                chats = state.chats,
                currentChatId = state.currentChatId,
                onOpen = viewModel::openChat,
                onDelete = viewModel::deleteChat,
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.turns.isEmpty()) {
                item { Intro(ready = state.ready, onOpenSettings = onOpenSettings) }
                if (state.ready) {
                    items(viewModel.suggestions) { suggestion ->
                        SuggestionRow(suggestion) { viewModel.send(suggestion) }
                    }
                }
            }

            items(state.turns.size) { index ->
                Bubble(state.turns[index])
            }

            if (state.thinking) {
                item {
                    Text(
                        "Looking it up…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.faint,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }

            state.error?.let { message ->
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.claySoft)
                            .padding(14.dp)
                    ) {
                        Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.clay)
                    }
                }
            }
        }

        Composer(
            value = state.draft,
            enabled = state.ready && !state.thinking,
            onValueChange = viewModel::setDraft,
            onSend = { viewModel.send() },
            bottomPadding = contentPadding.calculateBottomPadding(),
        )
    }
}

/**
 * Saved conversations. Each row is the question that started it, because that is
 * what anyone actually remembers a chat by.
 */
@Composable
private fun ChatHistory(
    chats: List<ChatSummary>,
    currentChatId: Long?,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val colors = Tally.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(bottom = 8.dp)
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chats.forEach { summary ->
            val open = summary.id == currentChatId
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (open) colors.amberSoft else colors.card)
                    .clickable { onOpen(summary.id) }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        summary.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${summary.messageCount} messages · ${Time.relative(summary.updatedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.faint,
                    )
                }
                Spacer(Modifier.size(10.dp))
                Text(
                    "Delete",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.clay,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onDelete(summary.id) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun Intro(ready: Boolean, onOpenSettings: () -> Unit) {
    val colors = Tally.colors
    TallyCard {
        Column {
            EyebrowLabel(if (ready) "Ask about your own spending" else "Not switched on yet")
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (ready) {
                    "Questions are answered by querying this phone's database. Only the " +
                        "results of those queries are sent — your transactions stay here."
                } else {
                    "Add a Claude API key and turn on AI features to use Ask."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = colors.graphite,
            )
            if (!ready) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Open Settings",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.amberDeep,
                    modifier = Modifier.clickable(onClick = onOpenSettings),
                )
            }
        }
    }
}

@Composable
private fun SuggestionRow(text: String, onClick: () -> Unit) {
    val colors = Tally.colors
    TallyCard(
        padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        radius = 18.dp,
        onClick = onClick,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
    }
}

@Composable
private fun Bubble(turn: ChatTurn) {
    val colors = Tally.colors
    val shape = if (turn.fromUser) {
        RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
    } else {
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (turn.fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.88f)
                .clip(shape)
                .background(if (turn.fromUser) colors.ink else colors.card)
                .padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            Text(
                text = turn.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (turn.fromUser) colors.card else colors.ink,
            )
            if (turn.toolsUsed.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Looked up: " + turn.toolsUsed.joinToString(", ") {
                        it.replace('_', ' ')
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.faint,
                )
            }
        }
    }
}

@Composable
private fun Composer(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    val colors = Tally.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(top = 8.dp, bottom = bottomPadding + 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.card)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            if (value.isEmpty()) {
                Text(
                    "Ask about your spending",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.faint,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.amber),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.size(10.dp))
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(if (enabled && value.isNotBlank()) colors.amber else colors.paperSunk)
                .clickable(enabled = enabled && value.isNotBlank(), onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Send,
                contentDescription = "Send",
                tint = if (enabled && value.isNotBlank()) colors.ink else colors.faint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
