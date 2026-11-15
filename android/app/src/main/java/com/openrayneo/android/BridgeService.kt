// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import org.json.JSONObject
import java.time.LocalTime

internal class BridgeService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@BridgeService }
    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    val observers = mutableSetOf<() -> Unit>()
    val devices = linkedMapOf<String, RayneoConnection.Device>()
    val logs = ArrayDeque<String>()
    lateinit var settings: AppSettings; private set
    private lateinit var supervisor: ConnectionSupervisor
    val connection get() = supervisor.transport
    private lateinit var display: GlassesDisplay
    private lateinit var audio: PromptAudio
    private lateinit var voice: GlassesVoice
    val glassesVoice get() = voice.state
    val navigation get() = NavigationFeed
    lateinit var agent: MobileAgent; private set
    lateinit var codex: CodexClient; private set
    var state = GlassesState(); private set
    val status get() = state.link.detail
    val ready get() = state.link.phase in setOf(LinkPhase.FULL, LinkPhase.BLE_ONLY)
    val busy get() = state.link.phase in setOf(LinkPhase.SCANNING, LinkPhase.CONNECTING, LinkPhase.RETRY_WAIT, LinkPhase.CLOSING)
    val displayStatus get() = state.displayDescription
    val audioStatus get() = state.audio.detail
    val audioLevels get() = audio.state
    private val events = ArrayDeque<GlassesEvent>()
    private var dispatching = false
    private var destroyed = false
    private var initialized = false
    private var foreground = false
    private var stopAfterDisconnect = false
    private var restoreTask: Runnable? = null
    private var closeTask: Runnable? = null
    private var audioOwner: Long? = null
    private var nextAnswer = 0L
    private val pendingAnswers = mutableMapOf<AnswerSource, AnswerPermit>()
    private var notificationText = ""
    private var notificationReadStatus = ""
    private val navigationObserver: () -> Unit = {
        if (notificationReadStatus != navigation.listenerState.detail) {
            notificationReadStatus = navigation.listenerState.detail
            onLog(notificationReadStatus)
        }
        dispatch(GlassesEvent.NavigationChanged(navigationSnapshot()))
    }
    private val notificationRefresh = object : Runnable {
        override fun run() {
            if (navigation.enabled) NavigationNotifications.refresh(this@BridgeService)
            main.postDelayed(this, 30_000)
        }
    }
    private val audioRefresh = object : Runnable {
        override fun run() {
            audioOwner?.let { dispatch(GlassesEvent.AudioChanged(it, audio.state.coordinatorState())) }
            voice.tick(if (voice.state.source == AnswerSource.MOBILE_AGENT) agent.running else codex.running)
            main.postDelayed(this, 1_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        supervisor = ConnectionSupervisor(this, main,
            { dispatch(GlassesEvent.LinkChanged(it)) }, { devices[it.bluetooth.address] = it; notifyUI() },
            { frame, source -> runCatching { display.receive(frame, source) }.onFailure { onLog("Unable to parse glasses message: $source") } }, ::onLog)
        voice = GlassesVoice(settings, { updateNotification(); notifyUI() }, ::onLog) { source, text ->
            submitAnswer(source, text, fromGlassesVoice = true)
        }
        audio = PromptAudio(voice::consume)
        display = GlassesDisplay(connection::sendFrame,
            { task, delay -> main.postDelayed(task, delay) }, { main.removeCallbacks(it) }, ::onLog,
            { id, signal -> dispatch(GlassesEvent.SessionSignal(id, signal)) },
            { id, packet -> if (audioOwner == id && state.session?.id == id) audio.accept(packet) },
            { dispatch(GlassesEvent.ExternalPage(it)) })
        navigation.initialize(this)
        navigation.observers.add(navigationObserver)
        agent = MobileAgent(settings, ::notifyUI, ::executeTool) { question, answer -> completed(AnswerSource.MOBILE_AGENT, question, answer) }
        codex = CodexClient(settings, ::notifyUI) { question, answer -> completed(AnswerSource.COMPUTER_CODEX, question, answer) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("bridge", "Glasses connection and navigation", NotificationManager.IMPORTANCE_LOW))
        initialized = true
        dispatch(GlassesEvent.NavigationChanged(navigationSnapshot()))
        NavigationNotifications.refresh(this)
        main.postDelayed(notificationRefresh, 30_000)
        main.postDelayed(audioRefresh, 1_000)
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_VOICE") {
            stopGlassesVoice()
        } else if (intent?.action == "STOP") {
            stopGlassesVoice()
            cancelAnswer(AnswerSource.MOBILE_AGENT); closeCodex()
            dispatch(GlassesEvent.StopNavigation)
            stopAfterDisconnect = true
            disconnect()
        } else {
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            foreground = true
        }
        return START_NOT_STICKY
    }

    private fun dispatch(event: GlassesEvent) {
        if (destroyed) return
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { dispatch(event) }; return }
        events.addLast(event)
        if (dispatching) return
        dispatching = true
        try {
            while (events.isNotEmpty()) {
                val transition = GlassesCoordinator.reduce(state, events.removeFirst())
                val previous = state
                state = transition.state
                if (state.displayDescription != previous.displayDescription) onLog(state.displayDescription)
                transition.effects.forEach { effect ->
                    runCatching { execute(effect) }.onFailure { error ->
                        val message = error.message ?: "Glasses operation failed"
                        onLog(message)
                        when (effect) {
                            is GlassesEffect.StartPage -> events.add(GlassesEvent.SessionSignal(effect.session.id, PageSignal.Failed(message)))
                            is GlassesEffect.UpdatePage -> events.add(GlassesEvent.SessionSignal(effect.id, PageSignal.Failed(message)))
                            is GlassesEffect.ClosePage -> events.add(GlassesEvent.SessionSignal(effect.id, PageSignal.Closed(message)))
                            is GlassesEffect.StartAudio -> events.add(GlassesEvent.AudioChanged(effect.id, AudioState(AudioPhase.FAILED, message)))
                            is GlassesEffect.FollowNavigation -> events.add(GlassesEvent.StopNavigation)
                            else -> Unit
                        }
                    }
                }
            }
        } finally { dispatching = false }
        if (voice.state.enabled && (state.link.phase != LinkPhase.FULL || state.disconnecting ||
            state.ownership != DisplayOwnership.APP || state.intent == DisplayIntent.Idle ||
            state.error != null || state.audio.phase == AudioPhase.FAILED || state.audio.phase == AudioPhase.STALLED)) {
            stopGlassesVoice("Glasses voice stopped because the glasses session ended or audio was interrupted.")
        }
        updateNotification(); notifyUI()
        if (stopAfterDisconnect && state.link.phase == LinkPhase.DISCONNECTED && !state.disconnecting) {
            stopAfterDisconnect = false
            foreground = false
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }
    private fun execute(effect: GlassesEffect) {
        when (effect) {
            is GlassesEffect.StartPage -> display.start(effect.session)
            is GlassesEffect.UpdatePage -> display.update(effect.id, effect.content)
            is GlassesEffect.ClosePage -> display.stop(effect.id)
            GlassesEffect.ResetPage -> display.reset()
            is GlassesEffect.StartAudio -> { audioOwner = effect.id; audio.start(effect.channels) }
            GlassesEffect.StopAudio -> { audioOwner = null; audio.stop() }
            is GlassesEffect.FollowNavigation -> navigation.follow(effect.key)
            GlassesEffect.StopFollowing -> navigation.stop()
            is GlassesEffect.ScheduleRestore -> {
                restoreTask?.let(main::removeCallbacks)
                restoreTask = Runnable { dispatch(GlassesEvent.RestoreAnswer(effect.id, effect.revision, effect.ref)) }
                    .also { main.postDelayed(it, 12_000) }
            }
            GlassesEffect.CancelRestore -> { restoreTask?.let(main::removeCallbacks); restoreTask = null }
            is GlassesEffect.ScheduleCloseTimeout -> {
                closeTask?.let(main::removeCallbacks)
                closeTask = Runnable { dispatch(GlassesEvent.CloseTimedOut(effect.id)) }.also { main.postDelayed(it, 1_500) }
            }
            GlassesEffect.CancelCloseTimeout -> { closeTask?.let(main::removeCallbacks); closeTask = null }
            GlassesEffect.DisconnectTransport -> supervisor.disconnect()
        }
    }

    fun scan() {
        check(!ready && state.link.phase != LinkPhase.CONNECTING && !state.disconnecting) { "Disconnect the current connection first" }
        devices.clear(); supervisor.scan()
    }
    fun connect(device: RayneoConnection.Device, bleOnly: Boolean) {
        check(!ready && !state.disconnecting) { "Disconnect the current connection first" }
        startForegroundService(Intent(this, BridgeService::class.java))
        dispatch(GlassesEvent.BeginConnection)
        runCatching { supervisor.connect(device, bleOnly) }.onFailure {
            supervisor.disconnect(); onLog(it.message ?: "Connection failed")
        }
    }
    fun disconnect() {
        stopGlassesVoice()
        supervisor.cancelRetries()
        dispatch(GlassesEvent.Disconnect)
    }
    fun followNavigation(key: String) {
        stopGlassesVoice()
        check(!state.disconnecting) { "Wait for disconnection to finish" }
        val entry = checkNotNull(navigation.entries[key]) { "This notification is gone. Start Gaode navigation and refresh." }
        val frame = entry.instruction?.let { NavigationFrame.render(it.direction, it.meters, entry.title) }
            ?: NavigationFrame.status("Instruction not recognized", "Check Gaode")
        dispatch(GlassesEvent.SelectNavigation(NavigationState(true, key, frame)))
    }
    fun stopNavigation() = dispatch(GlassesEvent.StopNavigation)
    fun reopenNavigation() {
        val key = navigation.selectedKey ?: error("Select a Gaode notification to follow first")
        followNavigation(key)
    }
    fun pausePrompt() { stopGlassesVoice(); dispatch(GlassesEvent.PauseDisplay) }
    fun openStandby() = dispatch(GlassesEvent.OpenStandby)
    fun retryAudio() {
        check(state.link.phase == LinkPhase.FULL && state.session?.phase in setOf(PagePhase.LAYOUT, PagePhase.SETTLING, PagePhase.READY)) {
            "Start navigation or Live prompts and wait for the glasses to confirm"
        }
        dispatch(GlassesEvent.RetryAudio)
    }
    fun dismissAnswer() = dispatch(GlassesEvent.DismissAnswer)

    fun startGlassesVoice(source: AnswerSource) {
        check(state.link.phase == LinkPhase.FULL && !state.disconnecting) { "Connect the glasses fully before starting glasses voice." }
        check(!agent.running && !codex.running) { "Wait for the current assistant request to finish." }
        if (source == AnswerSource.COMPUTER_CODEX) check(codex.connected) { "Connect Computer Codex first." }
        voice.checkConfiguration()
        stopGlassesVoice()
        dispatch(GlassesEvent.StopNavigation)
        openStandby()
        check(state.error == null && state.intent != DisplayIntent.Idle) { "Reopen Live prompts before starting glasses voice." }
        voice.start(source)
    }
    fun stopGlassesVoice(reason: String = "Glasses voice is off.") {
        if (!::voice.isInitialized) return
        val pending = voice.state.takeIf { it.phase == GlassesVoice.Phase.ANSWER }?.source
        voice.stop(reason)
        // Only cancel an answer owned by this voice session, never an unrelated typed task.
        if (pending != null) {
            invalidateAnswer(pending)
            if (pending == AnswerSource.MOBILE_AGENT) agent.cancel() else codex.cancel()
        }
    }
    fun sendGlassesSpeech() = voice.sendNow()

    fun submitAnswer(source: AnswerSource, text: String, fromGlassesVoice: Boolean = false) {
        if (!fromGlassesVoice && voice.state.enabled) stopGlassesVoice()
        when (source) {
            AnswerSource.MOBILE_AGENT -> check(!agent.running) { "Wait for the answer or stop the current request" }
            AnswerSource.COMPUTER_CODEX -> check(codex.connected && !codex.running) { "Connect to your computer and wait for the current task to finish" }
        }
        val ref = AnswerRef(source, ++nextAnswer)
        dispatch(GlassesEvent.AnswerRequested(ref))
        pendingAnswers[source] = checkNotNull(state.answerPermit)
        try {
            when (source) {
                AnswerSource.MOBILE_AGENT -> agent.submit(text)
                AnswerSource.COMPUTER_CODEX -> codex.submit(text)
            }
        } catch (error: Exception) {
            pendingAnswers.remove(source); dispatch(GlassesEvent.CancelAnswer(source)); throw error
        }
    }
    fun cancelAnswer(source: AnswerSource) {
        if (voice.state.enabled && voice.state.source == source) stopGlassesVoice()
        invalidateAnswer(source)
        when (source) {
            AnswerSource.MOBILE_AGENT -> agent.cancel()
            AnswerSource.COMPUTER_CODEX -> codex.cancel()
        }
    }
    private fun invalidateAnswer(source: AnswerSource) {
        pendingAnswers.remove(source)
        dispatch(GlassesEvent.CancelAnswer(source))
    }
    fun clearAgent() { stopGlassesVoice(); invalidateAnswer(AnswerSource.MOBILE_AGENT); agent.clear() }
    fun connectCodex() { invalidateAnswer(AnswerSource.COMPUTER_CODEX); codex.connect() }
    fun closeCodex() {
        if (voice.state.enabled && voice.state.source == AnswerSource.COMPUTER_CODEX) stopGlassesVoice()
        invalidateAnswer(AnswerSource.COMPUTER_CODEX); codex.close()
    }
    fun newCodexConversation() {
        check(!codex.running) { "Finish the current computer task first" }
        invalidateAnswer(AnswerSource.COMPUTER_CODEX); codex.newConversation()
    }
    private fun completed(source: AnswerSource, question: String, text: String) {
        val permit = pendingAnswers.remove(source) ?: return
        voice.answerFinished(source)
        if (text.isNotBlank()) dispatch(GlassesEvent.AnswerCompleted(AnswerContent(permit.ref, question, text), permit))
    }
    fun revealAnswer(source: AnswerSource) {
        check(state.link.phase == LinkPhase.FULL && !state.disconnecting) { "Establish a full glasses connection first" }
        val (question, answer) = when (source) {
            AnswerSource.MOBILE_AGENT -> agent.question to agent.answer
            AnswerSource.COMPUTER_CODEX -> codex.question to codex.answer
        }
        require(answer.isNotBlank()) { "No answer to display yet" }
        dispatch(GlassesEvent.RevealAnswer(AnswerContent(AnswerRef(source, ++nextAnswer), question, answer)))
    }
    private fun navigationSnapshot() = NavigationState(navigation.enabled, navigation.selectedKey, navigation.frame)
    private fun executeTool(name: String, args: JSONObject, completion: (Result<JSONObject>) -> Unit) {
        when (name) {
            "navigation_status" -> completion(Result.success(JSONObject().put("following", state.navigation.enabled)
                .put("notificationPresent", navigation.selected != null).put("status", navigation.status)))
            "stop_navigation_mirroring" -> { stopNavigation(); completion(Result.success(JSONObject().put("following", false).put("gaodeNavigationChanged", false))) }
            "show_notification" -> {
                connection.sendNotification(args.getString("title"), args.getString("body"))
                completion(Result.success(JSONObject().put("queued", true).put("visible", "unverified")))
            }
            else -> completion(Result.failure(IllegalArgumentException("Unknown tool")))
        }
    }
    private fun onLog(text: String) {
        android.util.Log.i("OpenRayneo", text)
        logs.addLast("${LocalTime.now().withNano(0)} $text")
        while (logs.size > 80) logs.removeFirst()
        if (!dispatching) notifyUI()
    }
    private fun notifyUI() { if (initialized && !destroyed) observers.toList().forEach { it() } }
    private fun summary() = if (::voice.isInitialized && voice.state.enabled) "Glasses voice · ${voice.state.detail}" else state.displayDescription + when (state.audio.phase) {
        AudioPhase.RECEIVING -> " · Receiving audio"
        AudioPhase.WAITING_PACKET, AudioPhase.WAITING_PCM -> " · Waiting for audio"
        AudioPhase.STALLED -> " · Audio interrupted"
        AudioPhase.FAILED -> " · Audio error"
        AudioPhase.STOPPED -> ""
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, BridgeService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, "bridge").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("OpenRayneo").setContentText(summary()).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop service", stop).build())
        if (::voice.isInitialized && voice.state.enabled) {
            val stopVoice = PendingIntent.getService(this, 2, Intent(this, BridgeService::class.java).setAction("STOP_VOICE"), PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, "Stop voice", stopVoice).build())
        }
        return builder.build()
    }
    private fun updateNotification() {
        if (!initialized || destroyed || !foreground || stopAfterDisconnect) return
        val text = summary()
        if (text != notificationText) { notificationText = text; getSystemService(NotificationManager::class.java).notify(1, notification()) }
    }
    override fun onDestroy() {
        destroyed = true; observers.clear(); main.removeCallbacksAndMessages(null)
        navigation.observers.remove(navigationObserver)
        voice.close(); agent.close(); codex.close(); display.reset(); audio.close(); supervisor.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
