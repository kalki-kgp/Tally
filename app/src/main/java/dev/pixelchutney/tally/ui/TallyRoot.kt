package dev.pixelchutney.tally.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import dev.pixelchutney.tally.R
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.pixelchutney.tally.capture.ui.CaptureActivity
import dev.pixelchutney.tally.ui.activity.ActivityScreen
import dev.pixelchutney.tally.ui.ask.AskScreen
import dev.pixelchutney.tally.ui.home.HomeScreen
import dev.pixelchutney.tally.ui.inbox.InboxScreen
import dev.pixelchutney.tally.ui.insights.InsightsScreen
import dev.pixelchutney.tally.ui.settings.SettingsScreen
import dev.pixelchutney.tally.ui.theme.Tally

private const val ROUTE_INBOX = "inbox"

private enum class Destination(val route: String, val label: String, val iconRes: Int) {
    Home("home", "Home", R.drawable.ic_nav_home),
    Activity("activity", "Activity", R.drawable.ic_nav_activity),
    Insights("insights", "Insights", R.drawable.ic_nav_insights),
    Ask("ask", "Ask", R.drawable.ic_nav_ask),
}

@Composable
fun TallyRoot(
    pendingRoute: String? = null,
    onRouteHandled: () -> Unit = {},
) {
    val colors = Tally.colors
    val navController = rememberNavController()
    val context = LocalContext.current
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination

    // A notification asked for a screen — "To sort", from a logged payment.
    androidx.compose.runtime.LaunchedEffect(pendingRoute) {
        if (pendingRoute == ROUTE_INBOX) {
            navController.navigate(ROUTE_INBOX) { launchSingleTop = true }
        }
        if (pendingRoute != null) onRouteHandled()
    }

    Scaffold(
        containerColor = colors.paper,
        bottomBar = {
            val fullScreen = currentRoute?.route == "settings" || currentRoute?.route == ROUTE_INBOX
            if (!fullScreen) {
                TallyBottomBar(
                    isSelected = { destination ->
                        currentRoute?.hierarchy?.any { it.route == destination.route } == true
                    },
                    onSelect = { destination ->
                        navController.navigate(destination.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onAdd = { context.startActivity(CaptureActivity.intent(context)) },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Home.route,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Destination.Home.route) {
                HomeScreen(
                    contentPadding = padding,
                    onSeeAll = { navController.navigate(Destination.Activity.route) },
                    onOpenInsights = { navController.navigate(Destination.Insights.route) },
                    onOpenSettings = { navController.navigate("settings") },
                    onOpenInbox = { navController.navigate(ROUTE_INBOX) { launchSingleTop = true } },
                )
            }
            composable(ROUTE_INBOX) {
                InboxScreen(onBack = { navController.popBackStack() })
            }
            composable(Destination.Activity.route) {
                ActivityScreen(contentPadding = padding)
            }
            composable(Destination.Insights.route) {
                InsightsScreen(contentPadding = padding)
            }
            composable(Destination.Ask.route) {
                AskScreen(
                    contentPadding = padding,
                    onOpenSettings = { navController.navigate("settings") },
                )
            }
            composable("settings") {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

/**
 * Four destinations around a raised amber button. Logging is the one action the
 * app exists for, so it sits in the middle of the thumb's reach on every screen.
 */
@Composable
private fun TallyBottomBar(
    isSelected: (Destination) -> Boolean,
    onSelect: (Destination) -> Unit,
    onAdd: () -> Unit,
) {
    val colors = Tally.colors
    val haptics = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.paper)
            .navigationBarsPadding()
            .padding(horizontal = 8.dp)
            .height(66.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem(Destination.Home, isSelected(Destination.Home), Modifier.weight(1f)) {
                onSelect(Destination.Home)
            }
            NavItem(Destination.Activity, isSelected(Destination.Activity), Modifier.weight(1f)) {
                onSelect(Destination.Activity)
            }
            Spacer(Modifier.weight(1f))
            NavItem(Destination.Insights, isSelected(Destination.Insights), Modifier.weight(1f)) {
                onSelect(Destination.Insights)
            }
            NavItem(Destination.Ask, isSelected(Destination.Ask), Modifier.weight(1f)) {
                onSelect(Destination.Ask)
            }
        }

        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.92f else 1f,
            animationSpec = spring(),
            label = "fab",
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-14).dp)
                .scale(scale)
                .shadow(10.dp, CircleShape, spotColor = colors.amber.copy(alpha = 0.6f))
                .size(58.dp)
                .clip(CircleShape)
                .background(colors.amber)
                .clickable(interactionSource = interaction, indication = null) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onAdd()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = "Log a payment",
                tint = Color(0xFF1B1508),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun NavItem(
    destination: Destination,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = Tally.colors
    val tint = if (selected) colors.ink else colors.faint
    Column(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(destination.iconRes),
            contentDescription = destination.label,
            tint = tint,
            modifier = Modifier.size(21.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = destination.label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
    }
}
