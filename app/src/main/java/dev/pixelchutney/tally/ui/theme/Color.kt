package dev.pixelchutney.tally.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Tally's palette is deliberately small: paper, ink, and one accent.
 *
 * Amber is the only chromatic colour in the chrome — it marks money leaving and
 * nothing else. Moss and clay appear solely as verdicts (under budget / over
 * budget), never as decoration, so a green number always means the same thing.
 */
@Immutable
data class TallyColors(
    val paper: Color,
    val paperSunk: Color,
    val card: Color,
    val cardAlt: Color,
    val ink: Color,
    val graphite: Color,
    val faint: Color,
    val hairline: Color,
    val amber: Color,
    val amberSoft: Color,
    val amberDeep: Color,
    val moss: Color,
    val mossSoft: Color,
    val clay: Color,
    val claySoft: Color,
    val isDark: Boolean,
)

val PaperColors = TallyColors(
    paper = Color(0xFFF2EEE4),
    paperSunk = Color(0xFFE7E1D3),
    card = Color(0xFFFFFFFF),
    cardAlt = Color(0xFFFBF9F4),
    ink = Color(0xFF191612),
    graphite = Color(0xFF7C766B),
    faint = Color(0xFFA9A296),
    hairline = Color(0xFFE6E0D1),
    amber = Color(0xFFE39B2E),
    amberSoft = Color(0xFFF7E7C6),
    amberDeep = Color(0xFFB0701A),
    moss = Color(0xFF2F7D5D),
    mossSoft = Color(0xFFDCEBE1),
    clay = Color(0xFFC1452C),
    claySoft = Color(0xFFF7DDD6),
    isDark = false,
)

val NightColors = TallyColors(
    paper = Color(0xFF131110),
    paperSunk = Color(0xFF1B1917),
    card = Color(0xFF201D1A),
    cardAlt = Color(0xFF262220),
    ink = Color(0xFFF4F0E8),
    graphite = Color(0xFFA29A8C),
    faint = Color(0xFF756E63),
    hairline = Color(0xFF332E29),
    amber = Color(0xFFF0AC45),
    amberSoft = Color(0xFF3B2C14),
    amberDeep = Color(0xFFF6C579),
    moss = Color(0xFF58B98A),
    mossSoft = Color(0xFF17291F),
    clay = Color(0xFFE2725A),
    claySoft = Color(0xFF321713),
    isDark = true,
)

val LocalTallyColors = staticCompositionLocalOf { PaperColors }

/**
 * Category hues. Warm-leaning so they sit on paper without vibrating, and
 * distinguishable from each other at donut-slice size.
 */
object CategoryPalette {
    val hues = listOf(
        Color(0xFFE0642F), // food & dining
        Color(0xFF4E9E5B), // groceries
        Color(0xFF3E7FA8), // transport
        Color(0xFFC64F86), // shopping
        Color(0xFF7A63C4), // bills & utilities
        Color(0xFFD9A128), // entertainment
        Color(0xFF2FA098), // health
        Color(0xFF8A6E4B), // subscriptions
        Color(0xFF6B7B8C), // transfers
        Color(0xFFC2743B), // travel
        Color(0xFF9A9186), // other
    )

    fun at(index: Int): Color = hues[((index % hues.size) + hues.size) % hues.size]
}
