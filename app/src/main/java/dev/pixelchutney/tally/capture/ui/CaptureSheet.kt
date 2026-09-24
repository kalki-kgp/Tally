package dev.pixelchutney.tally.capture.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Create
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import dev.pixelchutney.tally.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pixelchutney.tally.core.Money
import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.data.model.Necessity
import dev.pixelchutney.tally.ui.components.CategoryTile
import dev.pixelchutney.tally.ui.components.EyebrowLabel
import dev.pixelchutney.tally.ui.components.TallyChip
import dev.pixelchutney.tally.ui.theme.CategoryPalette
import dev.pixelchutney.tally.ui.theme.Tally

/**
 * One screen, no scrolling, no keyboard wait. Amount, category, done.
 *
 * The layout is ordered by how often each field is touched: the number pad is
 * always reachable with a thumb, the category chips sit right above it, and the
 * note hides behind an icon because it gets used perhaps once a week.
 */
@Composable
fun CaptureSheet(
    state: CaptureState,
    onDigit: (String) -> Unit,
    onDecimal: () -> Unit,
    onBackspace: () -> Unit,
    onCategory: (Long) -> Unit,
    onNecessity: (Necessity) -> Unit,
    onMerchant: (String) -> Unit,
    onSuggestion: (String) -> Unit,
    onNote: (String) -> Unit,
    onToggleNote: () -> Unit,
    onSave: () -> Unit,
    onNoPayment: () -> Unit,
    onDelete: () -> Unit = {},
    onClose: () -> Unit,
) {
    val colors = Tally.colors
    val haptics = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
            .background(colors.paper)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .width(38.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(colors.hairline)
        )

        Spacer(Modifier.height(18.dp))

        EyebrowLabel(
            when {
                state.editingId != null -> "Editing"
                state.prefilled -> "Detected payment"
                state.sessionId != null -> "You just used a payment app"
                else -> "New payment"
            }
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = Money.formatEntry(state.amountEntry),
            style = MaterialTheme.typography.displayLarge,
            color = if (state.amountEntry.isEmpty()) colors.faint else colors.ink,
        )

        Spacer(Modifier.height(6.dp))

        MerchantField(
            value = state.merchant,
            suggestions = state.merchantSuggestions,
            onValueChange = onMerchant,
            onSuggestion = onSuggestion,
        )

        if (state.duplicateWarning) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "You logged this same amount minutes ago. Save again only if you paid twice.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.clay,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(16.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.categories, key = { it.id }) { category ->
                TallyChip(
                    label = category.name,
                    leading = category.emoji,
                    selected = category.id == state.selectedCategoryId,
                    accent = CategoryPalette.at(category.colorIndex),
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onCategory(category.id)
                    },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TallyChip(
                label = "Need",
                selected = state.necessity == Necessity.NEED,
                accent = colors.moss,
                onClick = { onNecessity(Necessity.NEED) },
            )
            TallyChip(
                label = "Want",
                selected = state.necessity == Necessity.WANT,
                accent = colors.amber,
                onClick = { onNecessity(Necessity.WANT) },
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (state.showNote) colors.ink else colors.card)
                    .clickable { onToggleNote() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Create,
                    contentDescription = "Add a note",
                    tint = if (state.showNote) colors.card else colors.graphite,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        AnimatedVisibility(visible = state.showNote) {
            Column {
                Spacer(Modifier.height(10.dp))
                BasicTextField(
                    value = state.note,
                    onValueChange = onNote,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.amber),
                    decorationBox = { inner ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.card)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            if (state.note.isEmpty()) {
                                Text(
                                    "What was it for?",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.faint,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Numpad(
            onDigit = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onDigit(it)
            },
            onDecimal = onDecimal,
            onBackspace = onBackspace,
        )

        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.editingId != null) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .border(1.dp, colors.hairline, CircleShape)
                        .clickable { onDelete() }
                        .padding(vertical = 17.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Delete",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.clay,
                    )
                }
            } else if (state.sessionId != null) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .border(1.dp, colors.hairline, CircleShape)
                        .clickable { onNoPayment() }
                        .padding(vertical = 17.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No payment",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.graphite,
                    )
                }
            }

            Box(
                Modifier
                    .weight(if (state.sessionId != null || state.editingId != null) 1.4f else 1f)
                    .clip(CircleShape)
                    .background(if (state.canSave) colors.amber else colors.paperSunk)
                    .clickable(enabled = state.canSave) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSave()
                    }
                    .padding(vertical = 17.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (state.editingId != null) "Update" else "Save",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (state.canSave) colors.ink else colors.faint,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = Time.formatDay(Time.toLocalDate(state.timestamp)) +
                " · " + Time.formatTime(state.timestamp),
            style = MaterialTheme.typography.bodySmall,
            color = colors.faint,
            modifier = Modifier.clickable { onClose() },
        )
    }
}

@Composable
private fun MerchantField(
    value: String,
    suggestions: List<String>,
    onValueChange: (String) -> Unit,
    onSuggestion: (String) -> Unit,
) {
    val colors = Tally.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(
                color = colors.graphite,
                textAlign = TextAlign.Center,
            ),
            cursorBrush = SolidColor(colors.amber),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.Center) {
                    if (value.isEmpty()) {
                        Text(
                            "Who did you pay?",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.faint,
                        )
                    }
                    inner()
                }
            },
        )
        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(suggestions) { suggestion ->
                    TallyChip(
                        label = suggestion,
                        selected = false,
                        onClick = { onSuggestion(suggestion) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Numpad(
    onDigit: (String) -> Unit,
    onDecimal: () -> Unit,
    onBackspace: () -> Unit,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(".", "0", "⌫"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { key ->
                    NumpadKey(
                        label = key,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            when (key) {
                                "⌫" -> onBackspace()
                                "." -> onDecimal()
                                else -> onDigit(key)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NumpadKey(label: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = Tally.colors
    Box(
        modifier = modifier
            .aspectRatio(1.9f)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.card)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (label == "⌫") {
            Icon(
                painter = painterResource(R.drawable.ic_backspace),
                contentDescription = "Delete last digit",
                tint = colors.graphite,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.headlineMedium,
                color = colors.ink,
            )
        }
    }
}
