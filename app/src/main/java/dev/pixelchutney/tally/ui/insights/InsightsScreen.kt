package dev.pixelchutney.tally.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pixelchutney.tally.core.Money
import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.insights.Metrics
import dev.pixelchutney.tally.ui.components.AmountTicker
import dev.pixelchutney.tally.ui.components.BarChart
import dev.pixelchutney.tally.ui.components.CategoryTile
import dev.pixelchutney.tally.ui.components.ColorDot
import dev.pixelchutney.tally.ui.components.DeltaPill
import dev.pixelchutney.tally.ui.components.DonutChart
import dev.pixelchutney.tally.ui.components.EyebrowLabel
import dev.pixelchutney.tally.ui.components.MonthHeatmap
import dev.pixelchutney.tally.ui.components.SplitBar
import dev.pixelchutney.tally.ui.components.TallyCard
import dev.pixelchutney.tally.ui.components.TallyChip
import dev.pixelchutney.tally.ui.home.TransactionRowItem
import dev.pixelchutney.tally.ui.theme.CategoryPalette
import dev.pixelchutney.tally.ui.theme.LedgerFigure
import dev.pixelchutney.tally.ui.theme.Tally
import java.time.YearMonth
import kotlin.math.abs

private val weekdayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
private val calendarHeadings = listOf("M", "T", "W", "T", "F", "S", "S")

@Composable
fun InsightsScreen(
    contentPadding: PaddingValues,
    viewModel: InsightsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = Tally.colors

    Column(Modifier.background(colors.paper)) {
        Column(
            Modifier
                .statusBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(top = 10.dp, bottom = 12.dp),
        ) {
            MonthSwitcher(
                month = state.month,
                onPrevious = viewModel::previousMonth,
                onNext = viewModel::nextMonth,
            )
            Spacer(Modifier.height(14.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(InsightsTab.entries.toList()) { tab ->
                    TallyChip(
                        label = tab.label,
                        selected = state.tab == tab,
                        onClick = { viewModel.selectTab(tab) },
                    )
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start = 18.dp,
                end = 18.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (state.tab) {
                InsightsTab.Trends -> trendsTab(state)
                InsightsTab.Categories -> categoriesTab(state)
                InsightsTab.Merchants -> merchantsTab(state)
                InsightsTab.Patterns -> patternsTab(state, viewModel)
                InsightsTab.Calendar -> calendarTab(state, viewModel)
            }
        }
    }
}

@Composable
private fun MonthSwitcher(month: YearMonth, onPrevious: () -> Unit, onNext: () -> Unit) {
    val colors = Tally.colors
    val atCurrent = month >= YearMonth.now()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = Time.formatMonth(month),
            style = MaterialTheme.typography.headlineMedium,
            color = colors.ink,
            modifier = Modifier.weight(1f),
        )
        ArrowButton(Icons.Rounded.KeyboardArrowLeft, "Previous month", enabled = true, onClick = onPrevious)
        Spacer(Modifier.size(8.dp))
        ArrowButton(Icons.Rounded.KeyboardArrowRight, "Next month", enabled = !atCurrent, onClick = onNext)
    }
}

@Composable
private fun ArrowButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = Tally.colors
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(colors.card)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) colors.graphite else colors.hairline,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ── Trends ────────────────────────────────────────────────────────────────────

private fun androidx.compose.foundation.lazy.LazyListScope.trendsTab(state: InsightsUiState) {
    item {
        TallyCard {
            Column {
                EyebrowLabel("Daily spending")
                Spacer(Modifier.height(4.dp))
                AmountTicker(
                    paise = state.dailyThisMonth.sum(),
                    style = MaterialTheme.typography.displayMedium,
                )
                Spacer(Modifier.height(16.dp))
                BarChart(
                    values = state.dailyThisMonth,
                    highlightIndex = if (state.month == YearMonth.now()) {
                        state.dailyThisMonth.lastIndex
                    } else {
                        null
                    },
                    averageLine = true,
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    AxisLabel("1")
                    Spacer(Modifier.weight(1f))
                    AxisLabel("Dashed line is your average day")
                    Spacer(Modifier.weight(1f))
                    AxisLabel("${state.month.lengthOfMonth()}")
                }
            }
        }
    }

    item {
        TallyCard {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EyebrowLabel("Month by month")
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Projected ${Money.compact(state.projectionPaise)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tally.colors.graphite,
                    )
                }
                Spacer(Modifier.height(16.dp))
                BarChart(values = state.monthlyHistory.map { it.second }, height = 120.dp)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    state.monthlyHistory.forEach { (ym, _) ->
                        Text(
                            text = Time.formatShortMonth(ym.atDay(1)),
                            style = MaterialTheme.typography.bodySmall,
                            color = Tally.colors.faint,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    item {
        val colors = Tally.colors
        TallyCard {
            Column {
                EyebrowLabel("Needs vs wants, six months")
                Spacer(Modifier.height(6.dp))
                Text(
                    "The want line is the part that can actually move.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.graphite,
                )
                Spacer(Modifier.height(16.dp))
                state.necessityHistory.forEach { (ym, split) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 10.dp),
                    ) {
                        Text(
                            text = Time.formatShortMonth(ym.atDay(1)),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.faint,
                            modifier = Modifier.size(width = 34.dp, height = 16.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        SplitBar(
                            needPaise = split.needPaise,
                            wantPaise = split.wantPaise,
                            unsortedPaise = split.unsortedPaise,
                            barHeight = 12.dp,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            text = Money.compact(split.wantPaise),
                            style = LedgerFigure,
                            color = colors.amberDeep,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AxisLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = Tally.colors.faint,
    )
}

// ── Categories ────────────────────────────────────────────────────────────────

private fun androidx.compose.foundation.lazy.LazyListScope.categoriesTab(state: InsightsUiState) {
    val total = state.categories.sumOf { it.totalPaise }

    item {
        TallyCard {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DonutChart(
                    slices = state.categories.map {
                        CategoryPalette.at(it.colorIndex) to it.totalPaise
                    },
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .aspectRatio(1f),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        EyebrowLabel("Spent")
                        Spacer(Modifier.height(4.dp))
                        AmountTicker(
                            paise = total,
                            style = MaterialTheme.typography.displaySmall,
                        )
                    }
                }
            }
        }
    }

    items(state.categories, key = { it.categoryId }) { category ->
        val previous = state.previousCategories[category.name] ?: 0L
        val change = category.totalPaise - previous
        TallyCard(padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp), radius = 18.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryTile(
                    emoji = category.emoji,
                    tint = CategoryPalette.at(category.colorIndex),
                    size = 38.dp,
                )
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        category.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = Tally.colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${category.txnCount} payments · ${
                            if (total > 0) (category.totalPaise * 100 / total) else 0
                        }%",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tally.colors.faint,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        Money.format(category.totalPaise),
                        style = LedgerFigure,
                        color = Tally.colors.ink,
                    )
                    if (previous > 0 && abs(change) > 5_000) {
                        Spacer(Modifier.height(4.dp))
                        DeltaPill(label = Money.compact(abs(change)), up = change > 0)
                    }
                }
            }
        }
    }
}

// ── Merchants ─────────────────────────────────────────────────────────────────

private fun androidx.compose.foundation.lazy.LazyListScope.merchantsTab(state: InsightsUiState) {
    item {
        TallyCard {
            Column {
                EyebrowLabel("Where the money actually goes")
                Spacer(Modifier.height(6.dp))
                Text(
                    "Visit count matters as much as the total: twenty small orders is a " +
                        "habit, one big payment is a decision.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tally.colors.graphite,
                )
            }
        }
    }

    items(state.merchantsBySpend, key = { it.merchantName }) { merchant ->
        val max = state.merchantsBySpend.firstOrNull()?.totalPaise ?: 1L
        TallyCard(padding = PaddingValues(16.dp), radius = 18.dp) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        merchant.merchantName,
                        style = MaterialTheme.typography.titleSmall,
                        color = Tally.colors.ink,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        Money.format(merchant.totalPaise),
                        style = LedgerFigure,
                        color = Tally.colors.ink,
                    )
                }
                Spacer(Modifier.height(8.dp))
                BarChart(
                    values = listOf(merchant.totalPaise, max - merchant.totalPaise),
                    height = 4.dp,
                    barColor = Tally.colors.amber,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${merchant.txnCount} payments · about ${
                        Money.format(merchant.totalPaise / merchant.txnCount.coerceAtLeast(1))
                    } each · last on ${Time.toLocalDate(merchant.lastAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Tally.colors.faint,
                )
            }
        }
    }
}

// ── Patterns ──────────────────────────────────────────────────────────────────

private fun androidx.compose.foundation.lazy.LazyListScope.patternsTab(
    state: InsightsUiState,
    viewModel: InsightsViewModel,
) {
    val patterns = state.patterns

    item {
        TallyCard {
            Column {
                EyebrowLabel("Small payments add up")
                Spacer(Modifier.height(8.dp))
                AmountTicker(
                    paise = patterns.smallLeakPaise,
                    style = MaterialTheme.typography.displayMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${patterns.smallLeakCount} payments under " +
                        "${Money.format(state.smallLeakThresholdPaise)} over the last 12 weeks.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tally.colors.graphite,
                )
                Spacer(Modifier.height(14.dp))
                TallyMarks(count = patterns.smallLeakCount)
            }
        }
    }

    item {
        TallyCard {
            Column {
                EyebrowLabel("By day of the week")
                Spacer(Modifier.height(6.dp))
                patterns.priciestWeekday?.let {
                    Text(
                        "${weekdayNames[it]} is your most expensive day.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tally.colors.graphite,
                    )
                }
                Spacer(Modifier.height(16.dp))
                BarChart(values = patterns.weekday, height = 120.dp)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    weekdayNames.forEach {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = Tally.colors.faint,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    item {
        TallyCard {
            Column {
                EyebrowLabel("By hour")
                Spacer(Modifier.height(6.dp))
                patterns.busiestHour?.let {
                    Text(
                        "Most of your payments happen around ${formatHour(it)}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tally.colors.graphite,
                    )
                }
                Spacer(Modifier.height(16.dp))
                BarChart(values = patterns.hourly, height = 110.dp)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    listOf("12am", "6am", "12pm", "6pm", "11pm").forEach {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = Tally.colors.faint,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    item {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            FactCard(
                modifier = Modifier.weight(1f),
                label = "Typical payment",
                value = Money.format(patterns.medianTxnPaise),
                footnote = "Median, 12 weeks",
            )
            FactCard(
                modifier = Modifier.weight(1f),
                label = "Payments a day",
                value = "%.1f".format(patterns.txnPerDay),
                footnote = "Rising means habit",
            )
        }
    }

    if (state.recurring.isNotEmpty()) {
        item {
            TallyCard {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EyebrowLabel("Repeats on its own")
                        Spacer(Modifier.weight(1f))
                        Text(
                            Money.format(state.subscriptionsMonthlyPaise) + " / month",
                            style = MaterialTheme.typography.labelMedium,
                            color = Tally.colors.amberDeep,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    state.recurring.take(8).forEach { rule ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 10.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    rule.merchantName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Tally.colors.ink,
                                )
                                Text(
                                    "${rule.cadence.name.lowercase()
                                        .replaceFirstChar { it.uppercase() }} · " +
                                        "seen ${rule.occurrences} times" +
                                        if (!rule.confirmed) " · unconfirmed" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Tally.colors.faint,
                                )
                            }
                            Text(
                                Money.format(rule.expectedAmountPaise),
                                style = LedgerFigure,
                                color = Tally.colors.ink,
                            )
                        }
                        // Detected rules stay out of the committed-spend total until
                        // a human agrees they really are a subscription.
                        if (!rule.confirmed) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(bottom = 12.dp),
                            ) {
                                TallyChip(
                                    label = "It's a subscription",
                                    selected = false,
                                    onClick = { viewModel.confirmRecurring(rule) },
                                )
                                TallyChip(
                                    label = "It isn't",
                                    selected = false,
                                    onClick = { viewModel.dismissRecurring(rule) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FactCard(modifier: Modifier, label: String, value: String, footnote: String) {
    TallyCard(modifier = modifier, padding = PaddingValues(16.dp)) {
        Column {
            EyebrowLabel(label)
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.displaySmall, color = Tally.colors.ink)
            Spacer(Modifier.height(4.dp))
            Text(footnote, style = MaterialTheme.typography.bodySmall, color = Tally.colors.faint)
        }
    }
}

/**
 * The count drawn the way the app is named: groups of five, four uprights and a
 * crossing stroke. A number you can feel rather than read past.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TallyMarks(count: Int, maxGroups: Int = 30) {
    val colors = Tally.colors
    val groups = (count / 5).coerceAtMost(maxGroups)
    val remainder = if (groups < maxGroups) count % 5 else 0

    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(groups) { TallyGroup(strokes = 5) }
        if (remainder > 0) TallyGroup(strokes = remainder)
        if (count / 5 > maxGroups) {
            Text(
                "+${count - maxGroups * 5}",
                style = MaterialTheme.typography.labelMedium,
                color = colors.faint,
            )
        }
    }
}

@Composable
private fun TallyGroup(strokes: Int) {
    val colors = Tally.colors
    androidx.compose.foundation.Canvas(
        modifier = Modifier.size(width = 20.dp, height = 22.dp)
    ) {
        val gap = size.width / 4.4f
        repeat(minOf(strokes, 4)) { index ->
            val x = gap * index + 1.5f
            drawLine(
                color = colors.amber,
                start = androidx.compose.ui.geometry.Offset(x, 0f),
                end = androidx.compose.ui.geometry.Offset(x, size.height),
                strokeWidth = 2.4f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
        if (strokes >= 5) {
            drawLine(
                color = colors.amberDeep,
                start = androidx.compose.ui.geometry.Offset(0f, size.height - 1f),
                end = androidx.compose.ui.geometry.Offset(size.width, 1f),
                strokeWidth = 2.4f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
    }
}

private fun formatHour(hour: Int): String = when {
    hour == 0 -> "midnight"
    hour < 12 -> "${hour}am"
    hour == 12 -> "noon"
    else -> "${hour - 12}pm"
}

// ── Calendar ──────────────────────────────────────────────────────────────────

private fun androidx.compose.foundation.lazy.LazyListScope.calendarTab(
    state: InsightsUiState,
    viewModel: InsightsViewModel,
) {
    item {
        TallyCard {
            Column {
                EyebrowLabel("Every day this month")
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth()) {
                    calendarHeadings.forEach {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = Tally.colors.faint,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                MonthHeatmap(
                    cells = state.calendarCells.map { cell ->
                        if (cell.date == null) null else cell.paise
                    },
                    maxValue = state.calendarMax,
                    onCellTap = { index ->
                        state.calendarCells.getOrNull(index)?.date?.let(viewModel::selectDay)
                    },
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Quiet",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tally.colors.faint,
                    )
                    Spacer(Modifier.size(8.dp))
                    listOf(0.25f, 0.5f, 0.75f, 1f).forEach { alpha ->
                        ColorDot(Tally.colors.amber.copy(alpha = alpha), size = 11.dp)
                        Spacer(Modifier.size(4.dp))
                    }
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "Heavy · up to ${Money.compact(state.calendarMax)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tally.colors.faint,
                    )
                }
            }
        }
    }

    state.selectedDay?.let { day ->
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EyebrowLabel(Time.formatDay(day), color = Tally.colors.graphite)
                Spacer(Modifier.weight(1f))
                Text(
                    text = Money.format(
                        state.selectedDayRows
                            .filter { !it.txn.excluded && it.txn.amountPaise > 0 }
                            .sumOf { it.txn.amountPaise }
                    ),
                    style = LedgerFigure,
                    color = Tally.colors.ink,
                )
            }
        }
        if (state.selectedDayRows.isEmpty()) {
            item {
                Text(
                    "Nothing spent. A rare and beautiful thing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tally.colors.faint,
                )
            }
        }
        items(state.selectedDayRows, key = { it.txn.id }) { row ->
            TransactionRowItem(row)
        }
    }
}
