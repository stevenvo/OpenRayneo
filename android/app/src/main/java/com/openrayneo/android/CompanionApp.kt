// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompanionApp(model: AppModel, actions: UiActions) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var route by rememberSaveable { mutableStateOf("") }
    var devices by rememberSaveable { mutableStateOf(false) }
    var sources by rememberSaveable { mutableStateOf(false) }
    val snackbars = remember { SnackbarHostState() }
    val pages = rememberSaveableStateHolder()
    val s = model.state
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler(
        enabled =
            !devices &&
                !sources &&
                (route.isNotEmpty() || model.onboarding && model.onboardingStep > 0 || tab != 0)
    ) {
        if (route.isNotEmpty())
            route =
                if (route in listOf("model", "computer", "diagnostics") && !model.onboarding)
                    "settings"
                else ""
        else if (model.onboarding) model.step(model.onboardingStep - 1) else tab = 0
    }
    LaunchedEffect(model.notice) {
        model.notice?.let {
            model.notice = null
            snackbars.showSnackbar(it)
        }
    }
    Scaffold(
        topBar = {
            if (!model.onboarding || route.isNotEmpty())
                TopAppBar(
                    title = {
                        Text(
                            when (route) {
                                "settings" -> "Settings"
                                "model" -> "Phone model"
                                "computer" -> "Computer connection"
                                "diagnostics" -> "Devices and diagnostics"
                                else -> listOf("OpenRayneo", "Navigation", "Assistant", "Features")[tab]
                            }
                        )
                    },
                    navigationIcon = {
                        if (route.isNotEmpty())
                            IconButton(
                                onClick = {
                                    route =
                                        if (route == "settings" || model.onboarding) ""
                                        else "settings"
                                }
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                            }
                    },
                    actions = {
                        if (route.isEmpty()) {
                            if (s.glasses.link.phase == LinkPhase.FULL) StatusPill("Glasses connected")
                            IconButton(onClick = { route = "settings" }) {
                                Icon(Icons.Outlined.Settings, "Settings")
                            }
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        ),
                )
        },
        bottomBar = {
            if (route.isEmpty() && !model.onboarding && !keyboardVisible)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    val icons =
                        listOf(
                            Icons.Outlined.Visibility,
                            Icons.Outlined.NearMe,
                            Icons.AutoMirrored.Outlined.Chat,
                            Icons.Outlined.Apps,
                        )
                    listOf("Glasses", "Navigation", "Assistant", "Features").forEachIndexed { index, title ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            colors =
                                NavigationBarItemDefaults.colors(
                                    indicatorColor = Color.Transparent
                                ),
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (index == 2 && s.approvals.isNotEmpty())
                                            Badge { Text(s.approvals.size.toString()) }
                                    }
                                ) {
                                    Icon(icons[index], null)
                                }
                            },
                            label = { Text(title) },
                        )
                    }
                }
        },
        snackbarHost = { SnackbarHost(snackbars) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            when (route) {
                "settings" ->
                    SettingsScreen(
                        model,
                        actions,
                        { route = it },
                        {
                            model.replaySetup()
                            route = ""
                        },
                    )
                "model",
                "computer" -> ConfigurationScreen(model, route == "model", actions.importConfig)
                "diagnostics" -> DiagnosticsScreen(model, actions)
                else ->
                    if (model.onboarding)
                        OnboardingScreen(model, actions, { devices = true }, { route = it })
                    else
                        pages.SaveableStateProvider(tab) {
                            when (tab) {
                                0 ->
                                    GlassesScreen(
                                        model,
                                        { devices = true },
                                        { tab = it },
                                        { route = "diagnostics" },
                                    )
                                1 ->
                                    NavigationScreen(
                                        model,
                                        actions,
                                        { sources = true },
                                        { devices = true },
                                    )
                                2 -> AssistantScreen(model, actions, { route = it })
                                else -> FeaturesScreen(model)
                            }
                        }
            }
        }
    }
    if (devices)
        ModalBottomSheet(onDismissRequest = { devices = false }) { DevicePicker(model, actions) }
    if (sources)
        ModalBottomSheet(onDismissRequest = { sources = false }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text("Navigation notification source", style = MaterialTheme.typography.headlineSmall)
                Note("Choose a notification with a turn and distance. A navigation-in-progress message has no directions.", Modifier.padding(vertical = 12.dp))
                if (s.navigationEntries.isEmpty()) Note("No Gaode navigation notifications yet")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(s.navigationEntries, key = { it.key }) { entry ->
                        OutlinedCard(
                            onClick = {
                                model.attempt {
                                    model.service().followNavigation(entry.key)
                                    sources = false
                                }
                            }
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    entry.title.ifBlank { "Gaode navigation" },
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Note(entry.texts.filter { it != entry.title }.joinToString("\n"))
                                Text(
                                    if (
                                        entry.key == s.glasses.navigation.key &&
                                            s.glasses.navigation.enabled
                                    )
                                        "Following"
                                    else "Follow this notification",
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    model.error?.let { message ->
        AlertDialog(
            onDismissRequest = { model.error = null },
            title = { Text("Notice") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { model.error = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun GlassesScreen(
    model: AppModel,
    connect: () -> Unit,
    navigate: (Int) -> Unit,
    diagnostics: () -> Unit,
) {
    val state = model.state.glasses
    val linked = state.link.phase == LinkPhase.FULL
    val busy =
        state.link.phase in
            setOf(LinkPhase.CONNECTING, LinkPhase.SCANNING, LinkPhase.RETRY_WAIT, LinkPhase.CLOSING)
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ContentCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(model.deviceName, style = MaterialTheme.typography.titleMedium)
                        Note(if (linked) "Connected · Direct phone link" else state.link.detail)
                    }
                    ToolOutlinedButton(
                        onClick =
                            if (linked || state.link.phase == LinkPhase.BLE_ONLY) diagnostics
                            else connect
                    ) {
                        Text(
                            if (linked || state.link.phase == LinkPhase.BLE_ONLY) "Manage"
                            else if (busy) "Connection progress" else "Connect"
                        )
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        item { SectionLabel("Glasses display") }
        item { GlassesOutputPreview(state) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (linked) {
                    Note(state.displayDescription)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToolOutlinedButton(onClick = { navigate(1) }) { Text("Navigation") }
                        ToolOutlinedButton(
                            onClick = { model.attempt { model.service().openStandby() } }
                        ) {
                            Text("Live prompts")
                        }
                    }
                    if (state.intent is DisplayIntent.Answer)
                        TextButton(
                            onClick = { model.attempt { model.service().dismissAnswer() } }
                        ) {
                            Text("Close answer on glasses")
                        }
                    if (state.session != null)
                        TextButton(onClick = { model.attempt { model.service().pausePrompt() } }) {
                            Text("Pause display and audio")
                        }
                }
            }
        }
        item {
            ContentCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text("Glasses audio", style = MaterialTheme.typography.titleSmall)
                        Note(state.audio.detail)
                    }
                }
                if (state.audio.phase == AudioPhase.RECEIVING) {
                    val levels = model.state.audioLevels
                    val channels =
                        if (levels.channels == 1) listOf("Mono" to levels.left)
                        else listOf("Channel 1" to levels.left, "Channel 2" to levels.right)
                    channels.forEach { (label, rms) ->
                        Text(label, style = MaterialTheme.typography.labelSmall)
                        LinearProgressIndicator(
                            progress = {
                                ((20 * kotlin.math.log10(rms.coerceAtLeast(.00001)) + 60) / 60)
                                    .toFloat()
                                    .coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Note("Audio levels are calculated on the phone. With Glasses voice enabled, speech is sent to OpenAI for transcription. This app does not save audio recordings.")
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureTile("Navigation", "Gaode notifications", Icons.Outlined.NearMe, Modifier.weight(1f)) {
                    navigate(1)
                }
                FeatureTile(
                    "Assistant",
                    "Phone · Computer",
                    Icons.AutoMirrored.Outlined.Chat,
                    Modifier.weight(1f),
                ) {
                    navigate(2)
                }
            }
        }
    }
}

@Composable
private fun FeatureTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier,
    click: () -> Unit,
) {
    Card(
        onClick = click,
        modifier = modifier,
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Note(subtitle)
        }
    }
}

@Composable
private fun DevicePicker(model: AppModel, actions: UiActions) {
    val s = model.state
    val ready = s.glasses.link.phase in setOf(LinkPhase.FULL, LinkPhase.BLE_ONLY)
    val connecting =
        s.glasses.link.phase in setOf(LinkPhase.CONNECTING, LinkPhase.RETRY_WAIT, LinkPhase.CLOSING)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Connect your glasses", style = MaterialTheme.typography.headlineSmall)
        Note("Keep the glasses awake and disconnect the official app or computer first. Confirm the pairing prompt on your phone.")
        Text(s.glasses.link.detail, color = MaterialTheme.colorScheme.primary)
        if (!model.bluetoothAllowed) {
            ToolButton(onClick = actions.scan, modifier = Modifier.fillMaxWidth()) {
                Text("Allow nearby devices and scan")
            }
            TextButton(onClick = actions.appSettings) { Text("Open app permissions") }
        } else {
            ToolButton(
                onClick = actions.scan,
                enabled = !ready && !connecting && s.available,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Outlined.BluetoothSearching, null)
                Spacer(Modifier.width(8.dp))
                Text("Scan for glasses")
            }
        }
        if (connecting || s.glasses.link.phase == LinkPhase.SCANNING)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(
            Modifier.heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(s.devices, key = { it.bluetooth.address }) { device ->
                OutlinedCard(
                    onClick = { model.connect(device, false) },
                    enabled = !ready && !connecting,
                ) {
                    ListItem(
                        headlineContent = { Text(device.name) },
                        supportingContent = { Text(if (ready) "Connection established" else "Tap to connect") },
                        leadingContent = { Icon(Icons.Outlined.Visibility, null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
        if (s.devices.isEmpty() && model.bluetoothAllowed && !connecting) Note("If the glasses are not found, wake them and scan again.")
        if (ready || connecting)
            ToolOutlinedButton(onClick = { model.attempt { model.service().disconnect() } }) {
                Text(if (ready) "Disconnect glasses" else "Cancel connection")
            }
    }
}

@Composable
private fun NavigationScreen(
    model: AppModel,
    actions: UiActions,
    sources: () -> Unit,
    connect: () -> Unit,
) {
    val s = model.state
    val nav = s.glasses.navigation
    val entry = s.navigationEntries.firstOrNull { it.key == nav.key }
    val instruction = entry?.instruction?.takeIf { nav.enabled && s.notificationConnected }
    var time by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(entry?.updatedAt) {
        while (true) {
            time = System.currentTimeMillis()
            delay(10_000)
        }
    }
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { SectionLabel("Glasses display") }
        item { GlassesOutputPreview(s.glasses) }
        item {
            ContentCard {
                Text("Gaode notifications", style = MaterialTheme.typography.titleMedium)
                if (instruction != null) {
                    Text(
                        "${instruction.direction} · ${instruction.meters} m",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Note(entry!!.title)
                } else Note(s.navigationStatus)
                val frame = nav.frame
                if (
                    frame != null &&
                        (s.glasses.session?.mode != PageMode.NAVIGATION ||
                            s.glasses.session?.writtenContent?.text != frame)
                ) {
                    GridPreview(frame, "Navigation layout to send")
                }
                Note("The full road name appears here. The glasses show an eight-cell road name at the bottom right.")
            }
        }
        item {
            ContentCard {
                Text(
                    if (nav.enabled) "Following Gaode notifications" else "Ready to start",
                    style = MaterialTheme.typography.titleMedium,
                )
                Note(s.notificationReadStatus)
                if (
                    s.notificationReadPhase == NotificationReadPhase.FAILED &&
                        android.os.Build.MANUFACTURER.equals("xiaomi", true)
                ) {
                    Note("On Xiaomi / HyperOS, allow background autostart for OpenRayneo, then reconnect the notification service.")
                    TextButton(onClick = actions.appSettings) { Text("Check app settings") }
                }
                Note(s.navigationStatus)
                if (entry != null && nav.enabled) {
                    val age = ((time - entry.updatedAt).coerceAtLeast(0) / 1000)
                    Note("Last notification: ${if (age < 60) "${age} s ago" else "${age / 60} min ago"} · Follow the current directions in Gaode")
                }
                if (!model.notificationAllowed)
                    ToolButton(
                        onClick = actions.notificationAccess,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Enable notification access")
                    }
                else if (!nav.enabled)
                    ToolButton(onClick = actions.openGaode, modifier = Modifier.fillMaxWidth()) {
                        Text("Open Gaode navigation")
                    }
                if (nav.enabled) {
                    if (s.glasses.link.phase != LinkPhase.FULL)
                        ToolButton(onClick = connect, modifier = Modifier.fillMaxWidth()) {
                            Text("Connect glasses")
                        }
                    else {
                        Note(s.glasses.displayDescription)
                        if (
                            s.glasses.intent != DisplayIntent.Navigation ||
                                s.glasses.ownership != DisplayOwnership.APP ||
                                s.glasses.error != null
                        )
                            ToolButton(
                                onClick = { model.attempt { model.service().reopenNavigation() } },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Resume glasses navigation")
                            }
                    }
                    ToolOutlinedButton(
                        onClick = { model.attempt { model.service().stopNavigation() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Stop following on glasses")
                    }
                    Note("This stops the glasses display. Gaode navigation continues.")
                }
            }
        }
        item {
            OutlinedCard(onClick = sources) {
                ListItem(
                    headlineContent = { Text("Notification source") },
                    supportingContent = {
                        Text(
                            if (s.navigationEntries.isEmpty()) "Waiting for Gaode navigation notifications"
                            else "${s.navigationEntries.size} notifications · Tap to choose"
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.NotificationsNone, null) },
                    trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
        item {
            TextButton(onClick = actions.openGaode) { Text("View route in Gaode") }
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
            Note("Reconnect and refresh notifications while keeping permissions and your selection.")
            Note("The preview updates after sending to the glasses. Gaode notifications appear separately. Disconnected or closed sessions do not show a stale screen as current.")
        }
    }
}
