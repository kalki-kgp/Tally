package dev.pixelchutney.tally.ui.activity

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pixelchutney.tally.core.Money
import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.data.model.Necessity
import dev.pixelchutney.tally.data.model.TxnFilterRange
import dev.pixelchutney.tally.ui.components.EyebrowLabel
import dev.pixelchutney.tally.ui.components.TallyChip
import dev.pixelchutney.tally.ui.home.TransactionRowItem
import dev.pixelchutney.tally.ui.theme.LedgerFigure
import dev.pixelchutney.tally.ui.theme.Tally
import java.time.LocalDate

@Composable
fun ActivityScreen(
    contentPadding: PaddingValues,
    viewModel: ActivityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = Tally.colors

    // Grouped by day, with a per-day subtotal — the shape a ledger wants to be read in.
    val grouped = remember(state.rows) {
        state.rows.groupBy { Time.toLocalDate(it.txn.timestamp) }.toSortedMap(compareByDescending { it })
    }

    Column(Modifier.background(colors.paper)) {
        Column(
            Modifier
                .statusBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(top = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Activity",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        Money.format(state.totalPaise),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.ink,
                    )
                    Text(
                        "${state.rows.size} shown",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.faint,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            SearchField(
                value = state.filters.query,
                onValueChange = viewModel::setQuery,
            )
            Spacer(Modifier.height(12.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rangeOptions) { (label, range) ->
                    TallyChip(
                        label = label,
                        selected = state.filters.range == range,
                        onClick = { viewModel.setRange(range) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    TallyChip(
                        label = "All categories",
                        selected = state.filters.categoryId == null &&
                            state.filters.necessity == null,
                        onClick = {
                            viewModel.setCategory(null)
                            viewModel.setNecessity(null)
                        },
                    )
                }
                item {
                    TallyChip(
                        label = "Wants",
                        selected = state.filters.necessity == Necessity.WANT,
                        accent = colors.amber,
                        onClick = {
                            viewModel.setNecessity(
                                if (state.filters.necessity == Necessity.WANT) null else Necessity.WANT
                            )
                        },
                    )
                }
                item {
                    TallyChip(
                        label = "Needs",
                        selected = state.filters.necessity == Necessity.NEED,
                        accent = colors.moss,
                        onClick = {
                            viewModel.setNecessity(
                                if (state.filters.necessity == Necessity.NEED) null else Necessity.NEED
                            )
                        },
                    )
                }
                items(state.categories, key = { it.id }) { category ->
                    TallyChip(
                        label = category.name,
                        leading = category.emoji,
                        selected = state.filters.categoryId == category.id,
                        onClick = {
                            viewModel.setCategory(
                                if (state.filters.categoryId == category.id) null else category.id
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start = 18.dp,
                end = 18.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.rows.isEmpty()) {
                item {
                    Text(
                        text = "Nothing matches that.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.faint,
                        modifier = Modifier.padding(top = 40.dp),
                    )
                }
            }

            grouped.forEach { (date, rows) ->
                item(key = "header-$date") {
                    DayHeader(
                        date = date,
                        totalPaise = rows.filter { !it.txn.excluded && it.txn.amountPaise > 0 }
                            .sumOf { it.txn.amountPaise },
                    )
                }
                items(rows, key = { it.txn.id }) { row ->
                    TransactionRowItem(row)
                }
            }
        }
    }
}

private val rangeOptions = listOf(
    "Today" to TxnFilterRange.TODAY,
    "This week" to TxnFilterRange.WEEK,
    "This month" to TxnFilterRange.MONTH,
    "Last month" to TxnFilterRange.LAST_MONTH,
    "Everything" to TxnFilterRange.ALL,
)

@Composable
private fun DayHeader(date: LocalDate, totalPaise: Long) {
    val colors = Tally.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EyebrowLabel(Time.formatDay(date), color = colors.graphite)
        Spacer(Modifier.weight(1f))
        Text(
            text = Money.format(totalPaise),
            style = LedgerFigure,
            color = colors.faint,
        )
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    val colors = Tally.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(colors.card)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            tint = colors.faint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    "Search merchants, notes, amounts",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.faint,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.amber),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Clear search",
                tint = colors.faint,
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onValueChange("") },
            )
        }
    }
}
