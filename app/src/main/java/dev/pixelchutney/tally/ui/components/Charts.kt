package dev.pixelchutney.tally.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pixelchutney.tally.insights.StrokeDatum
import dev.pixelchutney.tally.ui.theme.Tally
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The tally strip: one upright stroke per transaction, in the order they happened,
 * height scaled to the amount.
 *
 * It is the app's namesake and its most honest chart — nothing is averaged away,
 * so a fortnight of small strokes and one tall one look exactly like what they
 * were. Heights use a square-root scale, otherwise a single ₹40,000 payment
 * flattens a month of real spending into a baseline.
 */
@Composable
fun TallyStrip(
    strokes: List<StrokeDatum>,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
    highlightAbovePaise: Long? = null,
    onStrokeTap: ((StrokeDatum) -> Unit)? = null,
) {
    val colors = Tally.colors
    val progress = remember(strokes.size) { Animatable(0f) }
    LaunchedEffect(strokes.size) {
        progress.animateTo(1f, tween(durationMillis = 700, easing = FastOutSlowInEasing))
    }

    val maxAmount = remember(strokes) { strokes.maxOfOrNull { it.amountPaise } ?: 1L }
    val threshold = highlightAbovePaise ?: (maxAmount / 2)

    Box(modifier = modifier.fillMaxWidth().height(height)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .then(
                    if (onStrokeTap == null) Modifier else Modifier.pointerInput(strokes) {
                        detectTapGestures { offset ->
                            if (strokes.isEmpty()) return@detectTapGestures
                            val slot = size.width.toFloat() / strokes.size
                            val index = (offset.x / slot).toInt().coerceIn(0, strokes.lastIndex)
                            onStrokeTap(strokes[index])
                        }
                    }
                )
        ) {
            val baseline = size.height - 1f
            drawLine(
                color = colors.hairline,
                start = Offset(0f, baseline),
                end = Offset(size.width, baseline),
                strokeWidth = 1.5f,
            )
            if (strokes.isEmpty()) return@Canvas

            val slot = size.width / strokes.size
            val barWidth = max(1.5f, min(slot * 0.55f, 7f))
            val maxRoot = sqrt(maxAmount.toDouble()).toFloat().coerceAtLeast(1f)
            val usable = size.height - 6f

            strokes.forEachIndexed { index, stroke ->
                val root = sqrt(stroke.amountPaise.toDouble()).toFloat()
                val ratio = (root / maxRoot).coerceIn(0.06f, 1f)
                val barHeight = usable * ratio * progress.value
                val x = slot * index + slot / 2f
                val big = stroke.amountPaise >= threshold
                drawLine(
                    color = if (big) colors.amberDeep else colors.amber,
                    start = Offset(x, baseline),
                    end = Offset(x, baseline - barHeight),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/** Donut with flat gaps between slices, drawn clockwise from the top. */
@Composable
fun DonutChart(
    slices: List<Pair<Color, Long>>,
    modifier: Modifier = Modifier,
    thickness: Dp = 22.dp,
    gapDegrees: Float = 2.5f,
    center: @Composable () -> Unit = {},
) {
    val colors = Tally.colors
    val total = slices.sumOf { it.second }.coerceAtLeast(1L)
    val progress = remember(slices) { Animatable(0f) }
    LaunchedEffect(slices) {
        progress.animateTo(1f, tween(durationMillis = 850, easing = FastOutSlowInEasing))
    }

    Box(modifier = modifier, contentAlignment = androidx.compose.ui.Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val strokePx = thickness.toPx()
            val diameter = min(size.width, size.height) - strokePx
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)

            drawArc(
                color = colors.paperSunk,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx),
            )

            var start = -90f
            slices.forEach { (color, value) ->
                val sweep = (value.toFloat() / total) * 360f * progress.value
                if (sweep > 0.4f) {
                    drawArc(
                        color = color,
                        startAngle = start + gapDegrees / 2f,
                        sweepAngle = (sweep - gapDegrees).coerceAtLeast(0.4f),
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Butt),
                    )
                }
                start += (value.toFloat() / total) * 360f
            }
        }
        center()
    }
}

/**
 * Daily / weekday / hourly bars. `highlightIndex` is drawn in ink so today (or the
 * selected bar) separates from its neighbours without needing a second colour.
 */
@Composable
fun BarChart(
    values: List<Long>,
    modifier: Modifier = Modifier,
    height: Dp = 132.dp,
    highlightIndex: Int? = null,
    averageLine: Boolean = false,
    barColor: Color? = null,
    onBarTap: ((Int) -> Unit)? = null,
) {
    val colors = Tally.colors
    val progress = remember(values) { Animatable(0f) }
    LaunchedEffect(values) {
        progress.animateTo(1f, tween(durationMillis = 650, easing = FastOutSlowInEasing))
    }
    val maxValue = remember(values) { values.maxOrNull()?.coerceAtLeast(1L) ?: 1L }
    val average = remember(values) {
        val nonZero = values.filter { it > 0 }
        if (nonZero.isEmpty()) 0L else nonZero.sum() / nonZero.size
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .then(
                if (onBarTap == null) Modifier else Modifier.pointerInput(values) {
                    detectTapGestures { offset ->
                        if (values.isEmpty()) return@detectTapGestures
                        val slot = size.width.toFloat() / values.size
                        onBarTap((offset.x / slot).toInt().coerceIn(0, values.lastIndex))
                    }
                }
            )
    ) {
        if (values.isEmpty()) return@Canvas
        val slot = size.width / values.size
        val barWidth = max(2f, min(slot * 0.62f, 26f))
        val corner = CornerRadius(barWidth / 2.4f, barWidth / 2.4f)
        val floor = size.height

        values.forEachIndexed { index, value ->
            val ratio = value.toFloat() / maxValue
            val barHeight = (floor - 2f) * ratio * progress.value
            val x = slot * index + (slot - barWidth) / 2f
            val color = when {
                barColor != null && index != highlightIndex -> barColor
                index == highlightIndex -> colors.ink
                else -> colors.amber
            }
            if (barHeight <= 0.5f) {
                drawRoundRect(
                    color = colors.paperSunk,
                    topLeft = Offset(x, floor - 2.5f),
                    size = Size(barWidth, 2.5f),
                    cornerRadius = corner,
                )
            } else {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, floor - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = corner,
                )
            }
        }

        if (averageLine && average > 0) {
            val y = floor - (floor - 2f) * (average.toFloat() / maxValue)
            drawDashedLine(colors.faint, y, size.width)
        }
    }
}

private fun DrawScope.drawDashedLine(color: Color, y: Float, width: Float) {
    var x = 0f
    while (x < width) {
        drawLine(
            color = color,
            start = Offset(x, y),
            end = Offset(min(x + 6f, width), y),
            strokeWidth = 1.5f,
        )
        x += 12f
    }
}

/** Trend line with a soft fill beneath. Used for burn rate and merchant history. */
@Composable
fun Sparkline(
    values: List<Long>,
    modifier: Modifier = Modifier,
    height: Dp = 90.dp,
    lineColor: Color? = null,
    filled: Boolean = true,
) {
    val colors = Tally.colors
    val stroke = lineColor ?: colors.amber
    val progress = remember(values) { Animatable(0f) }
    LaunchedEffect(values) {
        progress.animateTo(1f, tween(durationMillis = 800, easing = FastOutSlowInEasing))
    }

    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        if (values.size < 2) return@Canvas
        val maxValue = values.max().coerceAtLeast(1L)
        val minValue = values.min()
        val span = (maxValue - minValue).coerceAtLeast(1L).toFloat()
        val stepX = size.width / (values.size - 1)
        val usable = size.height - 8f

        val points = values.mapIndexed { index, value ->
            val ratio = (value - minValue) / span
            Offset(stepX * index, size.height - 4f - usable * ratio)
        }

        val visible = (points.size * progress.value).toInt().coerceIn(2, points.size)
        val slice = points.take(visible)

        val line = Path().apply {
            moveTo(slice.first().x, slice.first().y)
            slice.drop(1).forEach { lineTo(it.x, it.y) }
        }

        if (filled) {
            val area = Path().apply {
                addPath(line)
                lineTo(slice.last().x, size.height)
                lineTo(slice.first().x, size.height)
                close()
            }
            drawPath(
                path = area,
                brush = Brush.verticalGradient(
                    colors = listOf(stroke.copy(alpha = 0.22f), stroke.copy(alpha = 0f)),
                    startY = 0f,
                    endY = size.height,
                ),
            )
        }
        drawPath(path = line, color = stroke, style = Stroke(width = 2.6f, cap = StrokeCap.Round))
    }
}

/**
 * Calendar heatmap. Empty days stay visible as faint wells so a quiet week reads
 * as a quiet week rather than a gap in the data.
 */
@Composable
fun MonthHeatmap(
    /** Seven columns per row, starting Monday. Null means "not part of this month". */
    cells: List<Long?>,
    modifier: Modifier = Modifier,
    maxValue: Long,
    onCellTap: ((Int) -> Unit)? = null,
) {
    val colors = Tally.colors
    val progress = remember(cells) { Animatable(0f) }
    LaunchedEffect(cells) {
        progress.animateTo(1f, tween(durationMillis = 600, easing = FastOutSlowInEasing))
    }

    val rows = (cells.size + 6) / 7
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height((rows * 46).dp)
            .then(
                if (onCellTap == null) Modifier else Modifier.pointerInput(cells) {
                    detectTapGestures { offset ->
                        val cell = size.width / 7f
                        val column = (offset.x / cell).toInt().coerceIn(0, 6)
                        val row = (offset.y / cell).toInt()
                        val index = row * 7 + column
                        if (index in cells.indices) onCellTap(index)
                    }
                }
            )
    ) {
        val cell = size.width / 7f
        val inset = cell * 0.12f
        val side = cell - inset * 2

        cells.forEachIndexed { index, value ->
            val column = index % 7
            val row = index / 7
            val topLeft = Offset(column * cell + inset, row * cell + inset)
            val corner = CornerRadius(side * 0.30f, side * 0.30f)

            val color = when {
                value == null -> Color.Transparent
                value == 0L -> colors.paperSunk
                else -> {
                    val ratio = (value.toFloat() / maxValue.coerceAtLeast(1L))
                        .coerceIn(0.12f, 1f) * progress.value
                    colors.amber.copy(alpha = 0.25f + ratio * 0.75f)
                }
            }
            if (value != null) {
                drawRoundRect(
                    color = color,
                    topLeft = topLeft,
                    size = Size(side, side),
                    cornerRadius = corner,
                )
            }
        }
    }
}

/** Two-tone stacked bar for the need / want split. */
@Composable
fun SplitBar(
    needPaise: Long,
    wantPaise: Long,
    unsortedPaise: Long,
    modifier: Modifier = Modifier,
    barHeight: Dp = 14.dp,
) {
    val colors = Tally.colors
    val total = (needPaise + wantPaise + unsortedPaise).coerceAtLeast(1L)
    val progress = remember(needPaise, wantPaise, unsortedPaise) { Animatable(0f) }
    LaunchedEffect(needPaise, wantPaise, unsortedPaise) {
        progress.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
    }

    Canvas(modifier = modifier.fillMaxWidth().height(barHeight)) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = colors.paperSunk, cornerRadius = radius)

        val segments = listOf(
            colors.moss to needPaise,
            colors.amber to wantPaise,
            colors.faint to unsortedPaise,
        )
        var x = 0f
        clipRoundRect(radius) {
            segments.forEach { (color, value) ->
                val width = size.width * (value.toFloat() / total) * progress.value
                if (width > 0f) {
                    drawRect(color = color, topLeft = Offset(x, 0f), size = Size(width, size.height))
                }
                x += size.width * (value.toFloat() / total)
            }
        }
    }
}

private inline fun DrawScope.clipRoundRect(
    radius: CornerRadius,
    block: DrawScope.() -> Unit,
) {
    val path = Path().apply {
        addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                rect = Rect(Offset.Zero, size),
                cornerRadius = radius,
            )
        )
    }
    clipPath(path) { block() }
}
