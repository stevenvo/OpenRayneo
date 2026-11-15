// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun OnboardingScreen(
    model: AppModel,
    actions: UiActions,
    connect: () -> Unit,
    configure: (String) -> Unit,
) {
    val step = model.onboardingStep
    val scroll = rememberScrollState()
    LaunchedEffect(step) { scroll.scrollTo(0) }
    val titles = listOf("Get started with OpenRayneo", "Connect glasses", "Set up navigation", "Set up assistant", "Check setup")
    val subtitles =
        listOf(
            "Welcome to OpenRayneo. Set up in a few steps, or come back later.",
            "Connect your phone to RayNeo iO to see navigation and answers on the glasses.",
            "Gaode plans your route. OpenRayneo reads its navigation notifications and displays directions, distance and road names.",
            "Use a model directly on your phone, or send tasks to Codex on an online computer. You can configure both later.",
            "Here is your setup status. You can finish any remaining steps in Settings.",
        )
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("OpenRayneo", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = model::finishSetup) { Text("Set up later") }
        }
        LinearProgressIndicator(
            progress = { (step + 1) / 5f },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        Column(
            Modifier.weight(1f).verticalScroll(scroll).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Get started · ${step + 1} / 5",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(titles[step], style = MaterialTheme.typography.headlineLarge)
            Note(subtitles[step])
            when (step) {
                0 -> {
                    GlassesArtwork(
                        Modifier.fillMaxWidth().height(90.dp),
                        MaterialTheme.colorScheme.primary,
                    )
                    SetupRow(Icons.Outlined.NearMe, "Navigation at a glance", "Follow navigation notifications from Gaode")
                    SetupRow(Icons.Outlined.AutoAwesome, "Ask your assistant", "Connect your model and computer Codex")
                    SetupRow(Icons.Outlined.Tune, "You are in control", "Pause the display and audio at any time")
                }
                1 -> {
                    ContentCard {
                        GlassesArtwork(
                            Modifier.fillMaxWidth().height(110.dp),
                            MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            if (model.state.glasses.link.phase == LinkPhase.FULL) "Glasses connected"
                            else "Before connecting",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Note("1. Wake the glasses and keep them near the phone\n2. Disconnect the glasses from the official app or computer\n3. Confirm the pairing prompt on your phone")
                        ToolButton(onClick = connect, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (model.state.glasses.link.phase == LinkPhase.FULL) "View connection"
                                else "Connect glasses"
                            )
                        }
                    }
                    Note("Nearby devices permission is requested when connecting. You can configure the assistant first.")
                }
                2 -> {
                    ContentCard {
                        Text(
                            if (model.notificationAllowed) "Notification access enabled" else "Allow Gaode navigation notifications",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (model.notificationAllowed) Note(model.state.notificationReadStatus)
                        Note("Android grants notification access. This app processes only Gaode navigation notifications; their text is not sent to the model.")
                        ToolOutlinedButton(
                            onClick = actions.notificationAccess,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (model.notificationAllowed) "View notification access" else "Enable notification access")
                        }
                    }
                    var example by rememberSaveable { mutableIntStateOf(0) }
                    val samples =
                        listOf(
                            Triple("Straight", 300, "Zhongshan Rd"),
                            Triple("Right", 80, "Renmin Rd"),
                            Triple("Left", 20, "Binhe Rd"),
                        )
                    val sample = samples[example]
                    GridPreview(
                        NavigationFrame.render(sample.first, sample.second, sample.third),
                        "Demo · Glasses navigation grid",
                    )
                    TextButton(onClick = { example = (example + 1) % samples.size }) {
                        Text("Next demo instruction")
                    }
                    Note("This is a preview. It does not send to the glasses or start navigation. Start real navigation in Gaode.")
                }
                3 -> {
                    SetupOption(
                        "Phone model",
                        "Enter your service URL, model name and API key to use your own model.",
                        model.config.optString("modelKey").isNotBlank(),
                        Icons.Outlined.Smartphone,
                    ) {
                        configure("model")
                    }
                    SetupOption(
                        "Computer Codex",
                        "Import the configuration from your computer or enter connection details. USB mode needs port forwarding.",
                        model.config.optString("codexToken").isNotBlank(),
                        Icons.Outlined.Computer,
                    ) {
                        configure("computer")
                    }
                    Note("Saved means settings are stored. Connection results appear in Assistant. Voice input currently uses the phone speech service.")
                }
                4 -> {
                    ContentCard {
                        CompletionRow(
                            "Glasses connection",
                            model.state.glasses.link.phase == LinkPhase.FULL,
                            "Connected",
                            "Connect later",
                        )
                        CompletionRow("Navigation notification access", model.notificationAllowed, "Enabled", "Allow later")
                        CompletionRow(
                            "Phone model",
                            model.config.optString("modelKey").isNotBlank(),
                            "Settings saved",
                            "Optional; configure later",
                        )
                        CompletionRow(
                            "Computer Codex",
                            model.config.optString("codexToken").isNotBlank(),
                            "Settings saved",
                            "Optional; configure later",
                        )
                    }
                    if (!model.backgroundNotifications)
                        ContentCard {
                            Text("See connection status in the background", style = MaterialTheme.typography.titleMedium)
                            Note("Status notifications let you check the connection and stop the service from Android notifications.")
                            ToolOutlinedButton(onClick = actions.notificationPermission) {
                                Text("Allow status notifications")
                            }
                        }
                    Note("Return here through Settings → Show setup guide.")
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step > 0) ToolOutlinedButton(onClick = { model.step(step - 1) }) { Text("Back") }
            ToolButton(
                onClick = { if (step == 4) model.finishSetup() else model.step(step + 1) },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) {
                Text(if (step == 0) "Start setup" else if (step == 4) "Open OpenRayneo" else "Continue")
            }
        }
    }
}

@Composable
private fun SetupRow(icon: ImageVector, title: String, description: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Note(description)
        }
    }
}

@Composable
private fun SetupOption(
    title: String,
    description: String,
    saved: Boolean,
    icon: ImageVector,
    click: () -> Unit,
) {
    ContentCard {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Note(description)
        if (saved) StatusPill("Settings saved")
        ToolOutlinedButton(onClick = click, modifier = Modifier.fillMaxWidth()) {
            Text(if (saved) "View settings" else "Set up $title")
        }
    }
}

@Composable
private fun CompletionRow(title: String, done: Boolean, complete: String, incomplete: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            null,
            tint =
                if (done) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Note(if (done) complete else incomplete)
        }
    }
}

@Composable
internal fun SettingsScreen(
    model: AppModel,
    actions: UiActions,
    open: (String) -> Unit,
    replay: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionLabel("Connect your services")
        SettingsRow(
            "Phone model",
            if (model.config.optString("modelKey").isBlank()) "Not configured"
            else model.config.optString("modelName").ifBlank { "Settings saved" },
            Icons.Outlined.Smartphone,
        ) {
            open("model")
        }
        SettingsRow(
            "Computer Codex",
            if (model.state.computerConnected) "Connected" else "Connection details and import",
            Icons.Outlined.Computer,
        ) {
            open("computer")
        }
        SectionLabel("Permissions and devices")
        SettingsRow(
            "Nearby devices",
            if (model.bluetoothAllowed) "Granted" else "Required to connect glasses",
            Icons.Outlined.Bluetooth,
        ) {
            actions.appSettings()
        }
        SettingsRow(
            "Gaode notification access",
            if (model.notificationAllowed) "Granted · ${model.state.notificationReadStatus}"
            else "Required to follow navigation",
            Icons.Outlined.NotificationsNone,
            actions.notificationAccess,
        )
        SettingsRow(
            "Background status notifications",
            if (model.backgroundNotifications) "Allowed" else "Optional; see background connection status",
            Icons.Outlined.NotificationsActive,
        ) {
            if (model.backgroundNotifications) actions.appSettings()
            else actions.notificationPermission()
        }
        SettingsRow("Devices and diagnostics", "Connection logs, test notification and audio recovery", Icons.Outlined.Tune) { open("diagnostics") }
        SectionLabel("Appearance")
        ContentCard {
            listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (key, title) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = model.appearance == key,
                        onClick = { model.changeAppearance(key) },
                    )
                    TextButton(onClick = { model.changeAppearance(key) }) { Text(title) }
                }
            }
        }
        SectionLabel("About OpenRayneo")
        SettingsRow("Show setup guide", "Glasses connection, navigation demo and assistant setup", Icons.Outlined.Explore, replay)
        Note(
            "OpenRayneo ${BuildConfig.VERSION_NAME}\nAudio is processed only in phone memory. Phone speech input does not yet use glasses audio.",
            Modifier.padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun SettingsRow(title: String, summary: String, icon: ImageVector, click: () -> Unit) {
    Card(
        onClick = click,
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(summary) },
            leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
            colors =
                ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
        )
    }
}

@Composable
internal fun ConfigurationScreen(model: AppModel, mobile: Boolean, importConfig: () -> Unit) {
    val urlKey = if (mobile) "modelUrl" else "codexUrl"
    val nameKey = if (mobile) "modelName" else "codexCwd"
    val secretKey = if (mobile) "modelKey" else "codexToken"
    val url = model.formDrafts[urlKey].orEmpty()
    val name = model.formDrafts[nameKey].orEmpty()
    val secret = model.formDrafts[secretKey].orEmpty()
    var showSecret by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val ready = url.isNotBlank() && secret.isNotBlank() && (!mobile || name.isNotBlank())
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Icon(
            if (mobile) Icons.Outlined.Smartphone else Icons.Outlined.Computer,
            null,
            Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(if (mobile) "Model service settings" else "Computer connection settings", style = MaterialTheme.typography.headlineMedium)
        Note(
            if (mobile) "Use a Chat Completions-compatible service with tool calling. Get the URL, model name and API key from your provider."
            else "Run the OpenRayneo Codex bridge on your computer and import its configuration. USB mode also needs port forwarding."
        )
        if (!mobile) {
            ToolButton(onClick = importConfig, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.FileOpen, null)
                Spacer(Modifier.width(8.dp))
                Text("Import computer configuration")
            }
            Note("Choose OpenRayneo-setup.json. It contains credentials; delete the Downloads copy after importing.")
            HorizontalDivider()
            Text("Or enter manually", style = MaterialTheme.typography.titleMedium)
        }
        OutlinedTextField(
            url,
            { model.formDrafts[urlKey] = it },
            Modifier.fillMaxWidth(),
            label = { Text(if (mobile) "Service URL" else "Computer service URL") },
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            placeholder = { Text(if (mobile) "https://provider.example/v1" else "ws://127.0.0.1:4500") },
            singleLine = true,
            supportingText = {
                Text(if (mobile) "Use HTTPS. Do not put secrets in the URL." else "USB: localhost address. Wireless: use WSS.")
            },
        )
        OutlinedTextField(
            name,
            { model.formDrafts[nameKey] = it },
            Modifier.fillMaxWidth(),
            label = { Text(if (mobile) "Model name" else "Computer working directory (optional)") },
            singleLine = true,
        )
        OutlinedTextField(
            secret,
            { model.formDrafts[secretKey] = it },
            Modifier.fillMaxWidth(),
            label = { Text(if (mobile) "API Key" else "Connection token") },
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            visualTransformation =
                if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { showSecret = !showSecret }) {
                    Icon(
                        if (showSecret) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        if (showSecret) "Hide secret" else "Show secret",
                    )
                }
            },
        )
        ToolButton(
            onClick = {
                model.saveConfig(
                    if (mobile) mapOf("modelUrl" to url, "modelName" to name, "modelKey" to secret)
                    else mapOf("codexUrl" to url, "codexCwd" to name, "codexToken" to secret)
                )
            },
            enabled = ready,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            Text("Save settings")
        }
        Note("Credentials are encrypted with Android Keystore. Saving does not start requests or tasks. Check connections in Assistant.")
        if (!mobile)
            ContentCard {
                Text("USB connection steps", style = MaterialTheme.typography.titleMedium)
                Note(
                    "1. Install and sign in to Codex CLI on the computer\n2. Run scripts/phone-codex.py from the repository\n3. Run adb reverse tcp:4500 tcp:4500 on the computer\n4. Select Computer Codex in Assistant and tap Connect"
                )
            }
    }
}

@Composable
internal fun DiagnosticsScreen(model: AppModel, actions: UiActions) {
    val context = LocalContext.current
    val s = model.state
    var bleOnly by rememberSaveable { mutableStateOf(false) }
    val ready = s.glasses.link.phase in setOf(LinkPhase.FULL, LinkPhase.BLE_ONLY)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ContentCard {
            Text("Notification access", style = MaterialTheme.typography.titleMedium)
            Note(if (model.notificationAllowed) "System permission granted" else "System permission not granted")
            Note(s.notificationReadStatus)
            Note("Gaode notifications: ${s.navigationEntries.size}")
            if (s.notificationSnapshotAt > 0)
                Note(
                    "Last notification refresh: " +
                        java.text
                            .SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                            .format(java.util.Date(s.notificationSnapshotAt))
                )
            ToolOutlinedButton(
                onClick = {
                    model.attempt {
                        NavigationNotifications.requestConnection(model.getApplication())
                    }
                },
                enabled = model.notificationAllowed,
            ) {
                Text("Reconnect notification service")
            }
        }
        ContentCard {
            Text(model.deviceName, style = MaterialTheme.typography.titleLarge)
            Note(s.glasses.link.detail)
            Note(s.glasses.displayDescription)
            Note(s.glasses.audio.detail)
            ToolOutlinedButton(
                onClick = { model.attempt { model.service().connection.sendTestNotification() } },
                enabled = ready,
            ) {
                Text("Send test notification")
            }
            ToolOutlinedButton(
                onClick = { model.attempt { model.service().retryAudio() } },
                enabled =
                    s.glasses.session?.phase in
                        setOf(PagePhase.LAYOUT, PagePhase.SETTLING, PagePhase.READY),
            ) {
                Text("Restart audio decoder")
            }
            ToolOutlinedButton(
                onClick = { model.attempt { model.service().disconnect() } },
                enabled = ready || s.glasses.link.phase == LinkPhase.CONNECTING,
            ) {
                Text("Disconnect glasses")
            }
        }
        ContentCard {
            Text("Advanced connection diagnostics", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(bleOnly, { bleOnly = it }, enabled = !ready)
                Text("BLE-only diagnostics", Modifier.padding(start = 12.dp))
            }
            Note("For connection diagnostics only. Navigation and assistant displays require a full connection.")
            if (bleOnly) {
                ToolOutlinedButton(onClick = actions.scan, enabled = !ready) { Text("Scan devices") }
                s.devices.forEach { device ->
                    TextButton(onClick = { model.connect(device, true) }, enabled = !ready) {
                        Text("Diagnostic connection ${device.name}")
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SectionLabel("Connection log")
            TextButton(
                onClick = {
                    context
                        .getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(
                            ClipData.newPlainText("OpenRayneo", s.logs.joinToString("\n"))
                        )
                    model.notice = "Log copied"
                }
            ) {
                Text("Copy log")
            }
        }
        ContentCard { Note(s.logs.joinToString("\n").ifBlank { "No connection logs yet" }) }
    }
}
