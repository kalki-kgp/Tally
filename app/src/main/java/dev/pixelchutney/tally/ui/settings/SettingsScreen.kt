package dev.pixelchutney.tally.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pixelchutney.tally.core.Money
import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.data.settings.CaptureMode
import dev.pixelchutney.tally.update.UpdateState
import dev.pixelchutney.tally.ui.components.EyebrowLabel
import dev.pixelchutney.tally.ui.components.TallyCard
import dev.pixelchutney.tally.ui.theme.LedgerFigureSmall
import dev.pixelchutney.tally.ui.theme.Tally

/** When a watched app has been unhelpful often enough to be worth mentioning. */
private const val NO_PAYMENT_HINT = 4

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = Tally.colors

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::restore) }

    // The watcher's liveness has to be sampled, not observed, so refresh it while
    // this screen is open.
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshWatcherHealth()
            kotlinx.coroutines.delay(2_000)
        }
    }

    // Coming back from a system settings screen is exactly when these change.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.paper),
        contentPadding = PaddingValues(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                Modifier
                    .statusBarsPadding()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(colors.card)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = colors.graphite,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.size(12.dp))
                Text(
                    "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.ink,
                )
            }
        }

        state.diagnostics.message?.let { message ->
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (message.isError) colors.claySoft else colors.mossSoft)
                        .clickable { viewModel.dismissMessage() }
                        .padding(14.dp),
                ) {
                    Text(
                        message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (message.isError) colors.clay else colors.moss,
                    )
                }
            }
        }

        // ── Detection ─────────────────────────────────────────────────────────
        item {
            SectionCard("Detection") {
                PermissionRow(
                    label = "Usage access",
                    description = "Lets Tally see which app is in front, so it knows when you " +
                        "leave a payment app. Not an accessibility service, so banking apps " +
                        "will not object to it.",
                    granted = state.diagnostics.usageAccessOn,
                    onClick = viewModel::openUsageAccessSettings,
                )
                Divider()
                PermissionRow(
                    label = "Tally may post notifications",
                    description = "The prompt is a notification. Without this permission it is " +
                        "created and then silently dropped.",
                    granted = state.diagnostics.canPostNotifications,
                    onClick = viewModel::openAppNotificationSettings,
                )
                Divider()
                SettingRow(
                    label = "Battery optimisation",
                    description = "Exempt Tally so detection survives overnight.",
                    onClick = viewModel::openBatterySettings,
                )
                Divider()
                PermissionRow(
                    label = "Notification access",
                    description = "Reads the amount from UPI app notifications and bank texts, " +
                        "so you never type it. Not an accessibility service. If the switch is " +
                        "greyed out: App info → ⋮ → Allow restricted settings, then try again.",
                    granted = state.diagnostics.notificationAccessOn,
                    onClick = viewModel::openNotificationAccessSettings,
                )
                Divider()
                ToggleRow(
                    label = "Read payment notifications",
                    description = "Off: Tally ignores payment notifications even with access granted.",
                    checked = state.config.readPaymentNotifications,
                    onChange = viewModel::setReadNotifications,
                )
                Divider()
                ToggleRow(
                    label = "Sort later",
                    description = if (state.config.captureMode == CaptureMode.SORT_LATER) {
                        "Payments are logged quietly and wait in To sort. Nothing pops up."
                    } else {
                        "Off: a heads-up after every payment, as before."
                    },
                    checked = state.config.captureMode == CaptureMode.SORT_LATER,
                    onChange = viewModel::setSortLater,
                )
                Divider()
                ToggleRow(
                    label = "Follow up on payment apps",
                    description = "Off: leaving a payment app with no amount found does nothing.",
                    checked = state.config.promptsEnabled,
                    onChange = viewModel::setPrompts,
                )
                Divider()
                WatcherRow(watcher = state.diagnostics.watcher, viewModel = viewModel)
            }
        }

        // ── Payment context ───────────────────────────────────────────────────
        item {
            SectionCard("Payment context") {
                Text(
                    "Recorded with each payment and kept on this phone. It is what ranks " +
                        "the category buttons: payments made at the same place, time or " +
                        "from the same app tend to be the same kind of spending.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.graphite,
                )
                Spacer(Modifier.height(6.dp))
                PermissionRow(
                    label = "Location",
                    description = "Where you were when you left the payment app, plus the " +
                        "place name. Sampled once per payment, never in between.",
                    granted = state.diagnostics.locationOn,
                    onClick = viewModel::openAppPermissions,
                )
                Divider()
                PermissionRow(
                    label = "Calendar",
                    description = "The title of any event running at the time. Off unless you " +
                        "turn it on — it is the most revealing of these and the least certain " +
                        "to be useful.",
                    granted = state.diagnostics.calendarOn,
                    onClick = viewModel::openAppPermissions,
                )
            }
        }

        item { AddAppSection(state = state, viewModel = viewModel) }

        // ── Budget ────────────────────────────────────────────────────────────
        item {
            SectionCard("Budget") {
                AmountField(
                    label = "Monthly budget",
                    description = "Drives safe-to-spend, pace, and the two budget alerts.",
                    valuePaise = state.monthlyBudgetPaise,
                    onCommit = viewModel::setBudget,
                )
                Divider()
                AmountField(
                    label = "Small payment threshold",
                    description = "Payments under this are counted in the leak report.",
                    valuePaise = state.config.smallLeakThresholdPaise,
                    onCommit = viewModel::setSmallLeakThreshold,
                )
            }
        }

        // ── Watched apps ──────────────────────────────────────────────────────
        item {
            SectionCard("Watched apps") {
                Text(
                    "Leaving one of these prompts you to log a payment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.graphite,
                )
                Spacer(Modifier.height(6.dp))
                state.watchedApps.forEach { app ->
                    ToggleRow(
                        label = app.label,
                        // A long "no payment" streak is worth saying out loud, but
                        // it is yours to act on. Tally will not quietly stop asking.
                        description = if (app.consecutiveNoPayment >= NO_PAYMENT_HINT) {
                            "${app.consecutiveNoPayment} visits in a row weren't payments. " +
                                "Switch it off if it isn't worth asking about."
                        } else {
                            app.packageName
                        },
                        checked = app.enabled,
                        onChange = { viewModel.toggleWatchedApp(app) },
                    )
                }
            }
        }

        // ── AI ────────────────────────────────────────────────────────────────
        item {
            AiSection(state = state, viewModel = viewModel)
        }

        // ── Data ──────────────────────────────────────────────────────────────
        item {
            SectionCard("Your data") {
                SettingRow(
                    label = "Export to CSV",
                    description = "${state.diagnostics.transactionCount} transactions, opens in the share sheet.",
                    onClick = viewModel::exportCsv,
                )
                Divider()
                ToggleRow(
                    label = "Nightly backup",
                    description = if (state.config.lastBackupAt > 0) {
                        "Last backup ${Time.relative(state.config.lastBackupAt)}."
                    } else {
                        "Keeps the last 14 JSON snapshots on this phone."
                    },
                    checked = state.config.autoBackupEnabled,
                    onChange = viewModel::setAutoBackup,
                )
                Divider()
                SettingRow(
                    label = "Back up now",
                    description = "Write a snapshot immediately.",
                    onClick = viewModel::backupNow,
                )
                Divider()
                SettingRow(
                    label = "Restore from a backup",
                    description = "Merges by timestamp — restoring twice is harmless.",
                    onClick = { restoreLauncher.launch(arrayOf("application/json")) },
                )
            }
        }

        // ── Updates ───────────────────────────────────────────────────────────
        item { UpdateSection(viewModel = viewModel) }

        // ── How it's going ────────────────────────────────────────────────────
        item {
            SectionCard("How Tally is doing") {
                StatLine(
                    "Capture rate",
                    state.diagnostics.captureRatePercent?.let { "$it%" } ?: "Not enough data",
                    "Prompts that became an entry, last 30 days. Answering \"no payment\" " +
                        "counts as correct, not as a miss.",
                )
                Divider()
                StatLine(
                    "Time to log",
                    state.diagnostics.medianLogSeconds?.let { "${it}s" } ?: "Not enough data",
                    "Median gap between paying and saving.",
                )
            }
        }

        item { Spacer(Modifier.navigationBarsPadding().height(24.dp)) }
    }
}

@Composable
private fun UpdateSection(viewModel: SettingsViewModel) {
    val update by viewModel.update.collectAsStateWithLifecycle()
    SectionCard("Updates") {
        when (val current = update) {
            is UpdateState.Available -> SettingRow(
                label = "Install ${current.manifest.versionName}",
                description = current.manifest.releaseNotes
                    ?: "Downloads from GitHub, checks it, then Android asks before installing.",
                onClick = viewModel::installUpdate,
            )
            is UpdateState.Downloading -> StatLine(
                "Downloading ${current.manifest.versionName}",
                "…",
                "Android will ask before installing it.",
            )
            is UpdateState.Installing -> SettingRow(
                label = "Installing ${current.manifest.versionName}",
                description = "Finish in Android's installer. Tap to open it again.",
                onClick = viewModel::installUpdate,
            )
            else -> SettingRow(
                label = "Check for updates",
                description = when (current) {
                    UpdateState.Checking -> "Checking…"
                    UpdateState.UpToDate -> "You have the latest version, ${viewModel.versionLabel}."
                    is UpdateState.Failed -> current.message
                    else -> "Installed: ${viewModel.versionLabel}."
                },
                onClick = viewModel::checkForUpdate,
            )
        }
    }
}

@Composable
private fun AiSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val colors = Tally.colors
    var keyDraft by remember { mutableStateOf("") }

    SectionCard("AI") {
        Text(
            "Off by default. Nothing leaves the phone until you add a key and turn this on, " +
                "and even then only totals and merchant names are sent — never your full history.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.graphite,
        )
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.cardAlt)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                if (keyDraft.isEmpty()) {
                    Text(
                        if (state.hasApiKey) "Key saved · enter a new one to replace" else "sk-ant-…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.faint,
                    )
                }
                BasicTextField(
                    value = keyDraft,
                    onValueChange = { keyDraft = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.amber),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.size(10.dp))
            Text(
                text = if (keyDraft.isNotBlank()) "Save" else if (state.hasApiKey) "Clear" else "Save",
                style = MaterialTheme.typography.labelLarge,
                color = if (keyDraft.isNotBlank()) colors.amberDeep else colors.faint,
                modifier = Modifier.clickable {
                    if (keyDraft.isNotBlank()) {
                        viewModel.saveApiKey(keyDraft)
                        keyDraft = ""
                    } else if (state.hasApiKey) {
                        viewModel.clearApiKey()
                    }
                },
            )
        }

        Divider()
        ToggleRow(
            label = "AI features",
            description = "Categorisation, weekly reviews and Ask.",
            checked = state.config.aiEnabled,
            onChange = viewModel::setAiEnabled,
        )
        Divider()
        ToggleRow(
            label = "Name and file new merchants",
            description = "One cheap call the first time a merchant appears, then it's remembered.",
            checked = state.config.autoCategorise,
            onChange = viewModel::setAutoCategorise,
        )
        Divider()
        ToggleRow(
            label = "Weekly review",
            description = "Sunday evening, so there's still a day left to act on it.",
            checked = state.config.weeklyDigestEnabled,
            onChange = viewModel::setWeeklyDigest,
        )
        Divider()
        AmountField(
            label = "Monthly API budget",
            description = "Spent so far: ${Money.format(state.config.aiSpentThisMonthPaise)}. " +
                "AI stops when this runs out.",
            valuePaise = state.config.aiMonthlyCapPaise,
            onCommit = viewModel::setAiCap,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column {
        EyebrowLabel(title, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        TallyCard { Column { content() } }
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .padding(vertical = 0.dp)
            .background(Tally.colors.hairline)
    )
}

@Composable
private fun SettingRow(label: String, description: String, onClick: () -> Unit) {
    val colors = Tally.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = colors.ink)
        Spacer(Modifier.height(3.dp))
        Text(description, style = MaterialTheme.typography.bodySmall, color = colors.graphite)
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val colors = Tally.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = colors.ink)
            Spacer(Modifier.height(3.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = colors.graphite,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.card,
                checkedTrackColor = colors.amber,
                checkedBorderColor = colors.amber,
                uncheckedThumbColor = colors.card,
                uncheckedTrackColor = colors.paperSunk,
                uncheckedBorderColor = colors.hairline,
            ),
        )
    }
}

@Composable
private fun PermissionRow(
    label: String,
    description: String,
    granted: Boolean,
    onClick: () -> Unit,
) {
    val colors = Tally.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = colors.ink)
            Spacer(Modifier.height(3.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = colors.graphite)
        }
        Spacer(Modifier.size(12.dp))
        Box(
            Modifier
                .clip(CircleShape)
                .background(if (granted) colors.mossSoft else colors.claySoft)
                .padding(horizontal = 11.dp, vertical = 6.dp),
        ) {
            Text(
                text = if (granted) "On" else "Off",
                style = MaterialTheme.typography.labelMedium,
                color = if (granted) colors.moss else colors.clay,
            )
        }
    }
}

@Composable
private fun AmountField(
    label: String,
    description: String,
    valuePaise: Long,
    onCommit: (String) -> Unit,
) {
    val colors = Tally.colors
    var draft by remember(valuePaise) {
        mutableStateOf(if (valuePaise > 0) (valuePaise / 100).toString() else "")
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = colors.ink)
            Spacer(Modifier.height(3.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = colors.graphite,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(12.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(colors.cardAlt)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("₹", style = MaterialTheme.typography.titleSmall, color = colors.faint)
            Spacer(Modifier.size(3.dp))
            BasicTextField(
                value = draft,
                onValueChange = {
                    draft = it.filter { char -> char.isDigit() }.take(8)
                    onCommit(draft)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.titleSmall.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.amber),
                modifier = Modifier.size(width = 72.dp, height = 20.dp),
            )
        }
    }
}

@Composable
private fun StatLine(label: String, value: String, description: String) {
    val colors = Tally.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = colors.ink)
            Spacer(Modifier.height(3.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = colors.graphite)
        }
        Spacer(Modifier.size(12.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = colors.ink)
    }
}


/**
 * A dead watcher looks exactly like a quiet week, so it has to say so itself.
 * Tapping restarts it, which is the fix whenever the phone's battery manager has
 * killed the service.
 */
@Composable
private fun WatcherRow(watcher: WatcherHealth, viewModel: SettingsViewModel) {
    val colors = Tally.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { viewModel.restartWatcher() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (watcher.healthy) "Detection is running" else "Detection has stopped",
                style = MaterialTheme.typography.titleSmall,
                color = colors.ink,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = watcher.error
                    ?: if (watcher.healthy) "Tap to restart it." else "Tap to start it.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.graphite,
            )
        }
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (watcher.healthy) colors.moss else colors.clay)
        )
    }
}

/**
 * The seeded package names are guesses for anything outside the big four. This
 * lets a real one be found on the phone itself rather than shipped in a constant.
 */
@Composable
private fun AddAppSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val colors = Tally.colors
    var query by remember { mutableStateOf("") }
    val watched = state.watchedApps.map { it.packageName }.toSet()

    val matches = remember(query, state.diagnostics.installedApps, watched) {
        if (query.isBlank()) emptyList()
        else state.diagnostics.installedApps
            .filter { it.packageName !in watched }
            .filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
            .take(8)
    }

    SectionCard("Add a payment app") {
        Text(
            "Search everything installed. Use this for any app Tally does not already " +
                "know about.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.graphite,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.cardAlt)
                .padding(horizontal = 14.dp, vertical = 13.dp),
        ) {
            if (query.isEmpty()) {
                Text(
                    "Search installed apps",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.faint,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.amber),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        matches.forEach { app ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        viewModel.addWatchedApp(app)
                        query = ""
                    }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = MaterialTheme.typography.titleSmall, color = colors.ink)
                    Text(
                        app.packageName,
                        style = LedgerFigureSmall,
                        color = colors.faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("Add", style = MaterialTheme.typography.labelMedium, color = colors.amberDeep)
            }
        }
    }
}
