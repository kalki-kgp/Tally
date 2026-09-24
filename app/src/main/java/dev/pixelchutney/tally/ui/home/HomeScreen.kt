package dev.pixelchutney.tally.ui.home

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pixelchutney.tally.core.Money
import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.data.db.TransactionRow
import dev.pixelchutney.tally.insights.HomeSnapshot
import dev.pixelchutney.tally.insights.InsightsEngine
import dev.pixelchutney.tally.ui.components.AmountTicker
import dev.pixelchutney.tally.ui.components.CategoryTile
import dev.pixelchutney.tally.ui.components.ColorDot
import dev.pixelchutney.tally.ui.components.DeltaPill
import dev.pixelchutney.tally.ui.components.EyebrowLabel
import dev.pixelchutney.tally.ui.components.PaceBar
import dev.pixelchutney.tally.ui.components.SplitBar
import dev.pixelchutney.tally.ui.components.TallyCard
import dev.pixelchutney.tally.ui.components.TallyStrip
import dev.pixelchutney.tally.ui.theme.CategoryPalette
import dev.pixelchutney.tally.ui.theme.LedgerFigure
import dev.pixelchutney.tally.ui.theme.Tally
import dev.pixelchutney.tally.update.UpdateState
import java.time.YearMonth
import kotlin.math.abs

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    onSeeAll: () -> Unit,
    onOpenInsights: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenInbox: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val toSort by viewModel.toSort.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val colors = Tally.colors

    // Permissions can be revoked while the app is in the background, so the banner
    // is re-checked every time this screen comes back into view.
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshHealth()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.paper),
        contentPadding = PaddingValues(
            start = 18.dp,
            end = 18.dp,
            top = 0.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Header(onOpenSettings = onOpenSettings) }

        if (!state.health.allGood) {
            item { HealthBanner(message = state.health.message, onClick = onOpenSettings) }
        }

        updateBannerText(update)?.let { (title, body) ->
            item {
                UpdateBanner(
                    title = title,
                    body = body,
                    onClick = if (update is UpdateState.Available || update is UpdateState.Failed) {
                        viewModel::installUpdate
                    } else null,
                )
            }
        }

        if (toSort > 0) {
            item { ToSortBanner(count = toSort, onClick = onOpenInbox) }
        }

        item { HeroCard(state.snapshot) }

        item { TodayAndWeek(state.snapshot) }

        if (state.snapshot.monthStrokes.isNotEmpty()) {
            item { TallyStripCard(state.snapshot) }
        }

        state.insight?.let { insight ->
            item {
                InsightCard(
                    title = insight.title,
                    body = insight.body,
                    onDismiss = { viewModel.dismissInsight(insight.id) },
                )
            }
        }

        if (state.anomalies.isNotEmpty()) {
            item { AnomalyCard(state.anomalies.first(), onOpenInsights) }
        }

        if (state.snapshot.topCategories.isNotEmpty()) {
            item { CategoriesCard(state.snapshot, onOpenInsights) }
        }

        item { NeedWantCard(state.snapshot) }

        if (state.snapshot.movers.isNotEmpty()) {
            item { MoversCard(state.snapshot) }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EyebrowLabel("Latest")
                Text(
                    "See all",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.graphite,
                    modifier = Modifier.clickable(onClick = onSeeAll),
                )
            }
        }

        if (state.recent.isEmpty() && !state.loading) {
            item { EmptyState() }
        }

        items(state.recent, key = { it.txn.id }) { row ->
            TransactionRowItem(row)
        }
    }
}

@Composable
private fun Header(onOpenSettings: () -> Unit) {
    val colors = Tally.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Tally",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.ink,
            )
            Text(
                text = Time.formatMonth(YearMonth.from(Time.today())),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.graphite,
            )
        }
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.card)
                .clickable(onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Settings,
                contentDescription = "Settings",
                tint = colors.graphite,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun HealthBanner(message: String, onClick: () -> Unit) {
    val colors = Tally.colors
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.claySoft)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Column {
            Text(
                "Detection is off",
                style = MaterialTheme.typography.titleSmall,
                color = colors.clay,
            )
            Spacer(Modifier.height(3.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = colors.clay)
        }
    }
}

private fun updateBannerText(update: UpdateState): Pair<String, String>? = when (update) {
    is UpdateState.Available -> "Tally ${update.manifest.versionName} is ready" to
        (update.manifest.releaseNotes?.lineSequence()?.firstOrNull { it.isNotBlank() }
            ?: "Tap to download and install.")
    is UpdateState.Downloading -> "Downloading ${update.manifest.versionName}…" to
        "Android will ask before installing it."
    is UpdateState.Failed -> "Update didn't install" to "${update.message} Tap to try again."
    else -> null
}

@Composable
private fun UpdateBanner(title: String, body: String, onClick: (() -> Unit)?) {
    val colors = Tally.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.mossSoft)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.moss)
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = colors.moss, maxLines = 2)
        }
        if (onClick != null) {
            Icon(Icons.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.moss)
        }
    }
}

/**
 * Quiet by design: payments are already counted, this only says some still need
 * a category. Amber, not clay — nothing is wrong.
 */
@Composable
private fun ToSortBanner(count: Int, onClick: () -> Unit) {
    val colors = Tally.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.amberSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "$count to sort",
                style = MaterialTheme.typography.titleSmall,
                color = colors.ink,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Amounts are in. Say what they were for when you have a minute.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.graphite,
            )
        }
        Icon(
            Icons.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.graphite,
        )
    }
}

/**
 * The hero answers one question: can I spend today. When no budget is set it
 * falls back to the month total, because a number with no reference point is
 * still better than an empty card asking you to configure something.
 */
@Composable
private fun HeroCard(snapshot: HomeSnapshot) {
    val colors = Tally.colors
    val budget = snapshot.budget

    TallyCard(padding = PaddingValues(20.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EyebrowLabel(if (budget != null) "Safe to spend today" else "Spent this month")
                Spacer(Modifier.weight(1f))
                InsightsEngine.percentLabel(snapshot.momDelta)?.let { label ->
                    DeltaPill(
                        label = "$label vs last month",
                        up = snapshot.momDelta.absolutePaise >= 0,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            val heroAmount = budget?.safeToSpendTodayPaise ?: snapshot.monthPaise
            val heroColor = when {
                budget == null -> colors.ink
                heroAmount < 0 -> colors.clay
                budget.pace > 1.05 -> colors.amberDeep
                else -> colors.ink
            }
            AmountTicker(paise = heroAmount, color = heroColor)

            if (budget != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (heroAmount < 0) {
                        "You're ${Money.format(abs(heroAmount))} past today's share of the budget."
                    } else {
                        "${Money.format(budget.remainingPaise)} left for " +
                            "${Time.daysRemainingInMonth()} days."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.graphite,
                )

                Spacer(Modifier.height(16.dp))
                PaceBar(
                    fraction = budget.fraction,
                    expectedFraction = Time.today().dayOfMonth.toFloat() / Time.daysInMonth(),
                    over = budget.pace > 1f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        text = "${Money.format(budget.spentPaise)} of ${Money.format(budget.limitPaise)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.graphite,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "On track for ${Money.compact(snapshot.trimmedProjectionPaise)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (budget.limitPaise in 1 until snapshot.trimmedProjectionPaise) {
                            colors.clay
                        } else {
                            colors.moss
                        },
                    )
                }
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Projected ${Money.format(snapshot.trimmedProjectionPaise)} by month end. " +
                        "Set a budget in Settings to see what's safe to spend.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.graphite,
                )
            }
        }
    }
}

@Composable
private fun TodayAndWeek(snapshot: HomeSnapshot) {
    val colors = Tally.colors
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        StatCard(
            modifier = Modifier.weight(1f),
            label = "Today",
            paise = snapshot.todayPaise,
            footnote = when (snapshot.todayCount) {
                0 -> "Nothing yet"
                1 -> "1 payment"
                else -> "${snapshot.todayCount} payments"
            },
        )
        StatCard(
            modifier = Modifier.weight(1f),
            label = "This week",
            paise = snapshot.weekPaise,
            footnote = "${Money.compact(snapshot.burnRatePaise)} a day",
            accent = if (snapshot.streak.current > 0) {
                "🔥 ${snapshot.streak.current} no-spend ${if (snapshot.streak.current == 1) "day" else "days"}"
            } else {
                null
            },
        )
    }
}

@Composable
private fun StatCard(
    modifier: Modifier,
    label: String,
    paise: Long,
    footnote: String,
    accent: String? = null,
) {
    val colors = Tally.colors
    TallyCard(modifier = modifier, padding = PaddingValues(16.dp)) {
        Column {
            EyebrowLabel(label)
            Spacer(Modifier.height(8.dp))
            AmountTicker(
                paise = paise,
                style = MaterialTheme.typography.displaySmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = accent ?: footnote,
                style = MaterialTheme.typography.bodySmall,
                color = if (accent != null) colors.amberDeep else colors.graphite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The namesake view: this month, one stroke per payment. */
@Composable
private fun TallyStripCard(snapshot: HomeSnapshot) {
    val colors = Tally.colors
    TallyCard(padding = PaddingValues(18.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EyebrowLabel("Every payment this month")
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${snapshot.monthCount}",
                    style = LedgerFigure,
                    color = colors.graphite,
                )
            }
            Spacer(Modifier.height(14.dp))
            TallyStrip(strokes = snapshot.monthStrokes)
            Spacer(Modifier.height(10.dp))
            Row {
                Text(
                    text = "1 ${Time.formatShortMonth(Time.today())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.faint,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Tallest is ${Money.compact(snapshot.monthStrokes.maxOf { it.amountPaise })}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.faint,
                )
            }
        }
    }
}

@Composable
private fun InsightCard(title: String, body: String, onDismiss: () -> Unit) {
    val colors = Tally.colors
    TallyCard(padding = PaddingValues(18.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EyebrowLabel("Review · $title", color = colors.amberDeep)
                Spacer(Modifier.weight(1f))
                Text(
                    "Dismiss",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.faint,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = body.trim(),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.ink,
            )
        }
    }
}

@Composable
private fun AnomalyCard(anomaly: dev.pixelchutney.tally.insights.Anomaly, onOpen: () -> Unit) {
    val colors = Tally.colors
    TallyCard(padding = PaddingValues(18.dp), onClick = onOpen) {
        Column {
            EyebrowLabel("Worth a look", color = colors.amberDeep)
            Spacer(Modifier.height(8.dp))
            Text(anomaly.headline, style = MaterialTheme.typography.titleMedium, color = colors.ink)
            Spacer(Modifier.height(3.dp))
            Text(anomaly.detail, style = MaterialTheme.typography.bodyMedium, color = colors.graphite)
        }
    }
}

@Composable
private fun CategoriesCard(snapshot: HomeSnapshot, onOpen: () -> Unit) {
    val colors = Tally.colors
    val total = snapshot.topCategories.sumOf { it.totalPaise }.coerceAtLeast(1L)

    TallyCard(padding = PaddingValues(18.dp), onClick = onOpen) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EyebrowLabel("Where it went")
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.faint,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            snapshot.topCategories.forEachIndexed { index, category ->
                if (index > 0) Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorDot(CategoryPalette.at(category.colorIndex))
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = category.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.ink,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${(category.totalPaise * 100 / total)}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.faint,
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        text = Money.format(category.totalPaise),
                        style = LedgerFigure,
                        color = colors.ink,
                    )
                }
            }
        }
    }
}

@Composable
private fun NeedWantCard(snapshot: HomeSnapshot) {
    val colors = Tally.colors
    val split = snapshot.necessity
    if (split.total == 0L) return

    TallyCard(padding = PaddingValues(18.dp)) {
        Column {
            EyebrowLabel("Needs vs wants")
            Spacer(Modifier.height(12.dp))
            SplitBar(
                needPaise = split.needPaise,
                wantPaise = split.wantPaise,
                unsortedPaise = split.unsortedPaise,
            )
            Spacer(Modifier.height(12.dp))
            Row {
                LegendItem("Needs", Money.format(split.needPaise), colors.moss)
                Spacer(Modifier.weight(1f))
                LegendItem("Wants", Money.format(split.wantPaise), colors.amber)
                if (split.unsortedPaise > 0) {
                    Spacer(Modifier.weight(1f))
                    LegendItem("Unsorted", Money.format(split.unsortedPaise), colors.faint)
                }
            }
            if (split.wantPaise > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "${(split.wantShare * 100).toInt()}% of this month was optional — " +
                        "that's the part you can actually move.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.graphite,
                )
            }
        }
    }
}

@Composable
private fun LegendItem(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    val colors = Tally.colors
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColorDot(color, size = 7.dp)
            Spacer(Modifier.size(6.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = colors.graphite)
        }
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleSmall, color = colors.ink)
    }
}

@Composable
private fun MoversCard(snapshot: HomeSnapshot) {
    val colors = Tally.colors
    TallyCard(padding = PaddingValues(18.dp)) {
        Column {
            EyebrowLabel("What changed since last month")
            Spacer(Modifier.height(12.dp))
            snapshot.movers.forEachIndexed { index, mover ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = mover.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.ink,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DeltaPill(
                        label = Money.compact(abs(mover.changePaise)),
                        up = mover.changePaise > 0,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    val colors = Tally.colors
    TallyCard(padding = PaddingValues(22.dp)) {
        Column {
            Text(
                "Nothing logged yet",
                style = MaterialTheme.typography.titleMedium,
                color = colors.ink,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Pay for something with a UPI app and Tally will ask you about it. " +
                    "Or tap + to add one now.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.graphite,
            )
        }
    }
}

/**
 * Tapping a row reopens the capture sheet on that entry, so a mistyped amount is
 * two taps from being right instead of being wrong forever.
 */
@Composable
fun TransactionRowItem(
    row: TransactionRow,
    onClick: (() -> Unit)? = null,
) {
    val colors = Tally.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val open = onClick ?: {
        context.startActivity(
            dev.pixelchutney.tally.capture.ui.CaptureActivity.editIntent(context, row.txn.id)
        )
    }
    TallyCard(
        modifier = Modifier.fillMaxWidth(),
        padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        radius = 18.dp,
        onClick = open,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryTile(
                emoji = row.categoryEmoji,
                tint = CategoryPalette.at(row.categoryColorIndex),
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.txn.merchantName ?: row.categoryName,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(row.categoryName)
                        append(" · ")
                        append(Time.formatTime(row.txn.timestamp))
                        row.txn.note?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(10.dp))
            Text(
                text = Money.format(row.txn.amountPaise),
                style = LedgerFigure,
                color = if (row.txn.amountPaise < 0) colors.moss else colors.ink,
            )
        }
    }
}
