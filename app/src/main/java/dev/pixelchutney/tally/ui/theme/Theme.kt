package dev.pixelchutney.tally.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

val TallyShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
)

@Composable
fun TallyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val tally = if (darkTheme) NightColors else PaperColors

    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = tally.amber,
            onPrimary = tally.paper,
            secondary = tally.ink,
            background = tally.paper,
            onBackground = tally.ink,
            surface = tally.card,
            onSurface = tally.ink,
            surfaceVariant = tally.cardAlt,
            onSurfaceVariant = tally.graphite,
            outline = tally.hairline,
            error = tally.clay,
        )
    } else {
        lightColorScheme(
            primary = tally.amber,
            onPrimary = tally.ink,
            secondary = tally.ink,
            background = tally.paper,
            onBackground = tally.ink,
            surface = tally.card,
            onSurface = tally.ink,
            surfaceVariant = tally.cardAlt,
            onSurfaceVariant = tally.graphite,
            outline = tally.hairline,
            error = tally.clay,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalTallyColors provides tally) {
        MaterialTheme(
            colorScheme = scheme,
            typography = TallyTypography,
            shapes = TallyShapes,
            content = content,
        )
    }
}

/** Shorthand: `Tally.colors.amber` reads better than the full local lookup. */
object Tally {
    val colors: TallyColors
        @Composable get() = LocalTallyColors.current
}
