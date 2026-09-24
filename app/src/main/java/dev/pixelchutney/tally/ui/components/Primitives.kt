package dev.pixelchutney.tally.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pixelchutney.tally.core.Money
import dev.pixelchutney.tally.ui.theme.Eyebrow
import dev.pixelchutney.tally.ui.theme.Tally
import kotlin.math.roundToLong

/** The one container in the app: white card, generous radius, sitting on paper. */
@Composable
fun TallyCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(18.dp),
    radius: Dp = 24.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = Tally.colors
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = modifier
            .shadow(
                elevation = if (colors.isDark) 0.dp else 1.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.10f),
                spotColor = Color.Black.copy(alpha = 0.10f),
            )
            .clip(shape)
            .background(colors.card)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
    ) { content() }
}

/** Small-caps section label. Used above every block so the page reads as a ledger. */
@Composable
fun EyebrowLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Tally.colors.faint,
) {
    Text(
        text = text.uppercase(),
        style = Eyebrow,
        color = color,
        modifier = modifier,
    )
}

/**
 * Money that counts up when it changes. The animation is the point: watching
 * today's figure climb as you log is what makes the number feel real.
 */
@Composable
fun AmountTicker(
    paise: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displayLarge,
    color: Color = Tally.colors.ink,
    animate: Boolean = true,
) {
    val target = paise.toFloat()
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = if (animate) 620 else 0),
        label = "amount",
    )
    Text(
        text = Money.format(animated.roundToLong()),
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Visible,
        modifier = modifier,
    )
}

/** Pill chip. Selected is solid ink — the reference's strongest, simplest signal. */
@Composable
fun TallyChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: String? = null,
    accent: Color? = null,
) {
    val colors = Tally.colors
    val background = when {
        selected && accent != null -> accent
        selected -> colors.ink
        else -> colors.card
    }
    val content = if (selected) colors.card else colors.ink

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(background)
            .then(
                if (selected) Modifier
                else Modifier.border(1.dp, colors.hairline, CircleShape)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leading != null) Text(leading, style = MaterialTheme.typography.labelLarge)
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
        )
    }
}

/** Week / Month / Year. One track, one dark thumb. */
@Composable
fun SegmentedToggle(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Tally.colors
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(colors.paperSunk)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (selected) colors.ink else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) colors.card else colors.graphite,
                )
            }
        }
    }
}

/** Category dot used in legends and rows. */
@Composable
fun ColorDot(color: Color, size: Dp = 9.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}

/** Emoji tile standing in for a merchant logo. */
@Composable
fun CategoryTile(
    emoji: String,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3))
            .background(tint.copy(alpha = if (Tally.colors.isDark) 0.22f else 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, style = LocalTextStyle.current.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize))
    }
}

/** Delta pill: "+22% vs July". Green means less spending, which is the good direction. */
@Composable
fun DeltaPill(
    label: String,
    positiveIsGood: Boolean = false,
    up: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = Tally.colors
    val good = if (positiveIsGood) up else !up
    val tint = if (good) colors.moss else colors.clay
    val background = if (good) colors.mossSoft else colors.claySoft
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = if (up) "▲" else "▼",
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

/**
 * Budget pace. The bar fills with what has been spent; the dark notch marks where
 * the calendar says you should be. Bar past notch is the whole message.
 */
@Composable
fun PaceBar(
    fraction: Float,
    expectedFraction: Float,
    modifier: Modifier = Modifier,
    barHeight: Dp = 10.dp,
    over: Boolean = false,
) {
    val colors = Tally.colors
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(700),
        label = "pace",
    )
    val fill = if (over) colors.clay else colors.amber

    Canvas(modifier = modifier.height(barHeight)) {
        val radius = size.height / 2f
        drawRoundRect(
            color = colors.paperSunk,
            cornerRadius = CornerRadius(radius, radius),
        )
        if (animated > 0f) {
            drawRoundRect(
                color = fill,
                size = Size(size.width * animated, size.height),
                cornerRadius = CornerRadius(radius, radius),
            )
        }
        val notchX = (size.width * expectedFraction.coerceIn(0f, 1f))
            .coerceIn(1.5f, size.width - 1.5f)
        drawLine(
            color = colors.ink.copy(alpha = 0.4f),
            start = Offset(notchX, 0f),
            end = Offset(notchX, size.height),
            strokeWidth = 3f,
        )
    }
}
