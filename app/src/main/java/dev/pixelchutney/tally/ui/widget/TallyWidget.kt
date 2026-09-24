package dev.pixelchutney.tally.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.pixelchutney.tally.MainActivity
import dev.pixelchutney.tally.capture.ui.CaptureActivity
import dev.pixelchutney.tally.core.Money
import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.data.db.TransactionDao
import dev.pixelchutney.tally.data.repo.TallyRepository
import dev.pixelchutney.tally.insights.Metrics

/**
 * Today's spend and a shortcut to log one. Half the manual entries start here,
 * so the tap target for "+" is deliberately the largest thing on the widget.
 */
class TallyWidget : GlanceAppWidget() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetDependencies {
        fun transactions(): TransactionDao
        fun repository(): TallyRepository
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetDependencies::class.java,
        )

        val today = Time.dayRange(Time.today())
        val month = Time.monthToDate()
        val todayPaise = deps.transactions().spendBetween(today.first, today.last)
        val monthPaise = deps.transactions().spendBetween(month.first, month.last)
        val budget = deps.repository().overallBudget()

        val safeToSpend = budget?.let {
            Metrics.safeToSpendPerDay(
                limitPaise = it.monthlyLimitPaise,
                spentThisMonthPaise = monthPaise - todayPaise,
                daysRemaining = Time.daysRemainingInMonth(),
            ) - todayPaise
        }

        provideContent {
            WidgetContent(
                context = context,
                todayPaise = todayPaise,
                safeToSpendPaise = safeToSpend,
            )
        }
    }

    @Composable
    private fun WidgetContent(context: Context, todayPaise: Long, safeToSpendPaise: Long?) {
        val paper = Color(0xFFF2EEE4)
        val ink = Color(0xFF191612)
        val graphite = Color(0xFF7C766B)
        val amber = Color(0xFFE39B2E)
        val clay = Color(0xFFC1452C)

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(paper)
                .cornerRadius(24.dp)
                .padding(16.dp)
                .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
        ) {
            Text(
                text = "TODAY",
                style = TextStyle(color = androidx.glance.unit.ColorProvider(graphite), fontSize = 10.sp, fontWeight = FontWeight.Medium),
            )
            Spacer(GlanceModifier.height(4.dp))
            Text(
                text = Money.format(todayPaise),
                style = TextStyle(
                    color = androidx.glance.unit.ColorProvider(ink),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(GlanceModifier.height(6.dp))
            Text(
                text = when {
                    safeToSpendPaise == null -> "No budget set"
                    safeToSpendPaise < 0 -> "${Money.format(-safeToSpendPaise)} over today"
                    else -> "${Money.format(safeToSpendPaise)} left today"
                },
                style = TextStyle(
                    color = androidx.glance.unit.ColorProvider(
                        if (safeToSpendPaise != null && safeToSpendPaise < 0) clay else graphite
                    ),
                    fontSize = 12.sp,
                ),
            )
            Spacer(GlanceModifier.height(10.dp))
            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .background(amber)
                    .cornerRadius(18.dp)
                    .padding(vertical = 10.dp)
                    .clickable(
                        actionStartActivity(CaptureActivity.intent(context))
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "+  Log a payment",
                    style = TextStyle(
                        color = androidx.glance.unit.ColorProvider(ink),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

class TallyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TallyWidget()
}
