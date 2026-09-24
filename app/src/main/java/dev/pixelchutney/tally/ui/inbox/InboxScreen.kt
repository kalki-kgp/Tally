package dev.pixelchutney.tally.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pixelchutney.tally.capture.ui.CaptureActivity
import dev.pixelchutney.tally.core.Money
import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.ui.components.EyebrowLabel
import dev.pixelchutney.tally.ui.components.TallyCard
import dev.pixelchutney.tally.ui.components.TallyChip
import dev.pixelchutney.tally.ui.theme.CategoryPalette
import dev.pixelchutney.tally.ui.theme.LedgerFigure
import dev.pixelchutney.tally.ui.theme.Tally
import kotlin.math.roundToInt

/**
 * Sorting, in batches, whenever there is time. Each card already knows the amount
 * and where it was paid; the owner adds what it was for and taps a category.
 */
@Composable
fun InboxScreen(
    onBack: () -> Unit,
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = Tally.colors
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.paper),
        contentPadding = PaddingValues(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier
                    .statusBarsPadding()
                    .padding(top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(colors.card)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = colors.graphite,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.size(12.dp))
                Column {
                    Text("To sort", style = MaterialTheme.typography.headlineMedium, color = colors.ink)
                    if (state.items.isNotEmpty()) {
                        Text(
                            "${state.items.size} payment${if (state.items.size == 1) "" else "s"} · " +
                                Money.format(state.totalPaise),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.graphite,
                        )
                    }
                }
            }
        }

        if (state.isEmpty && !state.loading) {
            item {
                TallyCard(Modifier.fillMaxWidth()) {
                    Column {
                        Text("Nothing to sort", style = MaterialTheme.typography.titleSmall, color = colors.ink)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Payments Tally reads from UPI apps and bank texts land here with " +
                                "the amount already filled in. Add what they were for whenever " +
                                "you have a minute.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.graphite,
                        )
                    }
                }
            }
        }

        items(state.items, key = { it.row.txn.id }) { item ->
            SortCard(
                item = item,
                onNote = { viewModel.setNote(item.row.txn.id, it) },
                onCategory = { viewModel.sort(item.row.txn.id, it) },
                onDiscard = { viewModel.discard(item.row.txn.id) },
            )
        }

        if (state.visits.isNotEmpty()) {
            item {
                EyebrowLabel("No amount found", modifier = Modifier.padding(top = 8.dp))
            }
            items(state.visits, key = { "visit-${it.session.id}" }) { visit ->
                VisitCard(
                    visit = visit,
                    onEnter = {
                        context.startActivity(
                            CaptureActivity.intent(
                                context,
                                sessionId = visit.session.id,
                                packageName = visit.session.packageName,
                            )
                        )
                    },
                    onNoPayment = { viewModel.noPayment(visit.session) },
                )
            }
        }

        item { Spacer(Modifier.navigationBarsPadding().height(24.dp)) }
    }
}

@Composable
private fun SortCard(
    item: SortItem,
    onNote: (String) -> Unit,
    onCategory: (Long) -> Unit,
    onDiscard: () -> Unit,
) {
    val colors = Tally.colors
    val haptics = LocalHapticFeedback.current
    val txn = item.row.txn

    TallyCard(
        modifier = Modifier.fillMaxWidth(),
        padding = PaddingValues(16.dp),
        radius = 20.dp,
    ) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = txn.merchantName ?: txn.payee ?: "Payment",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = listOfNotNull(
                            Time.formatDay(Time.toLocalDate(txn.timestamp)) + ", " + Time.formatTime(txn.timestamp),
                            item.appLabel,
                            txn.placeName,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.faint,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.size(10.dp))
                Text(Money.format(txn.amountPaise), style = LedgerFigure, color = colors.ink)
            }

            Spacer(Modifier.height(12.dp))

            BasicTextField(
                value = item.note,
                onValueChange = onNote,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.amber),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.paperSunk)
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                    ) {
                        if (item.note.isEmpty()) {
                            Text("What was it for?", style = MaterialTheme.typography.bodyLarge, color = colors.faint)
                        }
                        inner()
                    }
                },
            )

            Spacer(Modifier.height(10.dp))

            val ranked = item.ranking?.ranked.orEmpty()
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ranked, key = { it.category.id }) { entry ->
                    val first = entry === ranked.first()
                    val showConfidence = item.ranking?.fromModel == true && entry.confidence >= 0.05f
                    TallyChip(
                        label = entry.category.name +
                            if (showConfidence) " ${(entry.confidence * 100).roundToInt()}%" else "",
                        leading = entry.category.emoji,
                        // The best guess is filled in: one tap on it confirms.
                        selected = first,
                        accent = CategoryPalette.at(entry.category.colorIndex),
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onCategory(entry.category.id)
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        item.thinking -> "Reading your note…"
                        item.ranking?.fromModel == true -> "Ranked by Haiku"
                        else -> "Ranked from your history"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.faint,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Not a payment",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.clay,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onDiscard)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun VisitCard(visit: VisitItem, onEnter: () -> Unit, onNoPayment: () -> Unit) {
    val colors = Tally.colors
    val at = visit.session.endedAt ?: visit.session.startedAt
    TallyCard(
        modifier = Modifier.fillMaxWidth(),
        padding = PaddingValues(16.dp),
        radius = 20.dp,
    ) {
        Column {
            Text(
                "Opened ${visit.appLabel}",
                style = MaterialTheme.typography.titleSmall,
                color = colors.ink,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                Time.formatDay(Time.toLocalDate(at)) + ", " + Time.formatTime(at) +
                    " · no payment notification followed",
                style = MaterialTheme.typography.bodySmall,
                color = colors.faint,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .border(1.dp, colors.hairline, CircleShape)
                        .clickable(onClick = onNoPayment)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No payment", style = MaterialTheme.typography.labelLarge, color = colors.graphite)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(colors.amber)
                        .clickable(onClick = onEnter)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Enter amount", style = MaterialTheme.typography.labelLarge, color = colors.ink)
                }
            }
        }
    }
}
