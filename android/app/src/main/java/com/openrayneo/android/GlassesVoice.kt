// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.OkHttpClient
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Explicitly armed glasses audio -> streaming English ASR -> existing assistant. */
internal class GlassesVoice(
    private val settings: AppSettings,
    private val changed: () -> Unit,
    private val log: (String) -> Unit,
    private val submit: (AnswerSource, String) -> Unit,
) {
    enum class Phase { OFF, CONNECTING, LISTENING, SPEAKING, TRANSCRIBING, ANSWER, COOLDOWN }
    data class State(
        val phase: Phase = Phase.OFF,
        val source: AnswerSource = AnswerSource.MOBILE_AGENT,
        val detail: String = "Use the glasses microphone for your questions.",
        val transcript: String = "",
    ) {
        val enabled get() = phase != Phase.OFF
        val target get() = if (source == AnswerSource.MOBILE_AGENT) "Phone model" else "Computer Codex"
    }
    @Volatile var state = State(); private set
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stream: OpenAiSpeechStream? = null
    private val capture = UtteranceCapture { stream?.append(it) ?: it.fill(0) }
    private val updatePending = AtomicBoolean()
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build()
    @Volatile private var lastAudio = 0L
    private var generation = 0L
    private var phaseStarted = 0L
    private var resumeAt = 0L
    private var keepAnswerWhileConnecting = false
    private var emptyTurns = 0

    private fun apiKey(): String {
        val config = settings.read()
        val url = runCatching { URI(config.optString("modelUrl")) }.getOrNull()
        require(url?.scheme == "https" && url.host == "api.openai.com" && url.userInfo == null) {
            "Glasses voice needs an OpenAI API key in Phone model settings."
        }
        return config.optString("modelKey").also { require(it.isNotBlank()) { "Add your OpenAI API key in Phone model settings." } }
    }
    fun checkConfiguration() { apiKey() }

    fun start(source: AnswerSource) {
        checkConfiguration(); stop(); emptyTurns = 0
        lastAudio = SystemClock.elapsedRealtime()
        state = State(source = source)
        connectStream(false)
        log("Glasses streaming voice enabled for ${state.target}")
    }
    private fun connectStream(keepAnswer: Boolean) {
        val token = ++generation
        capture.stop(); stream?.close(); stream = null
        keepAnswerWhileConnecting = keepAnswer
        publish(state.copy(phase = Phase.CONNECTING, detail = "Connecting live transcription…", transcript = ""))
        fun current() = token == generation && state.enabled
        stream = OpenAiSpeechStream(http, apiKey(),
            ready = { main.post {
                if (current()) {
                    capture.arm()
                    publish(state.copy(phase = Phase.LISTENING, detail = "Listening through glasses. Speak, then pause."))
                    log("Glasses voice: streaming connection ready")
                }
            } },
            partial = { text -> main.post {
                if (current() && state.phase in setOf(Phase.LISTENING, Phase.SPEAKING, Phase.TRANSCRIBING)) {
                    publish(state.copy(transcript = text))
                }
            } },
            complete = { text -> main.post {
                if (current() && state.phase == Phase.TRANSCRIBING) {
                    stream?.close(); stream = null
                    log("Glasses voice: final streaming transcript received")
                    if (text.isBlank()) {
                        emptyTurns++
                        if (emptyTurns >= 5) stop("Repeated background noise without clear speech. Move somewhere quieter, then start voice again.")
                        else {
                            resumeAt = SystemClock.elapsedRealtime() + 750
                            publish(state.copy(phase = Phase.COOLDOWN, detail = "No speech detected. Listening resumes shortly.", transcript = ""))
                        }
                    } else {
                        emptyTurns = 0
                        publish(state.copy(phase = Phase.ANSWER, detail = "${state.target} is answering…", transcript = text))
                        runCatching { submit(state.source, text) }.onFailure {
                            stop("Unable to send the question. Check the assistant connection, then start again.")
                        }
                    }
                }
            } },
            error = { message -> main.post { if (current()) { log(message); stop(message) } } },
        )
    }
    fun stop(reason: String = "Start Glasses voice on your phone to ask a question.") {
        generation++; capture.stop(); stream?.close(); stream = null; resumeAt = 0
        val wasEnabled = state.enabled
        publish(state.copy(phase = Phase.OFF, detail = reason, transcript = ""))
        if (wasEnabled) log("Glasses voice stopped")
    }

    /** Decoder worker: bounded PCM processing and non-blocking WebSocket sends only. */
    fun consume(left: ShortArray, right: ShortArray) {
        if (!state.enabled) return
        lastAudio = SystemClock.elapsedRealtime()
        if (state.phase !in setOf(Phase.LISTENING, Phase.SPEAKING)) return
        val clip = capture.push(SpeechPcm.mono(left, right))
        if (clip != null) main.post { commit(clip) }
        if (capture.isSpeaking() != (state.phase == Phase.SPEAKING) && updatePending.compareAndSet(false, true)) {
            main.post {
                updatePending.set(false)
                if (state.phase in setOf(Phase.LISTENING, Phase.SPEAKING)) {
                    val speaking = capture.isSpeaking()
                    publish(state.copy(phase = if (speaking) Phase.SPEAKING else Phase.LISTENING,
                        detail = if (speaking) "Hearing speech. Pause to send your question." else "Listening through glasses. Speak, then pause."))
                }
            }
        }
    }
    fun sendNow() { capture.finish()?.let(::commit) }
    private fun commit(clip: UtteranceCapture.Clip) {
        val duration = clip.samples.size / 48_000.0
        clip.samples.fill(0)
        if (!state.enabled || !capture.isCurrent(clip.generation)) return
        publish(state.copy(phase = Phase.TRANSCRIBING, detail = "Finishing your English transcript…"))
        stream?.commit()
        log("Glasses voice: committed $duration seconds of streamed audio")
    }
    fun answerFinished(source: AnswerSource) {
        if (state.phase != Phase.ANSWER || state.source != source) return
        resumeAt = SystemClock.elapsedRealtime() + 750
        publish(state.copy(phase = Phase.COOLDOWN, detail = "Answer ready. Listening resumes shortly."))
    }
    fun tick(assistantRunning: Boolean) {
        if (!state.enabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAudio > 15_000) { stop("Glasses audio stopped. Reopen Live prompts, then start again."); return }
        if (state.phase == Phase.CONNECTING && now - phaseStarted > 20_000) stop("Live transcription did not connect. Check internet and start again.")
        else if (state.phase == Phase.TRANSCRIBING && now - phaseStarted > 30_000) stop("Live transcription timed out. Start again.")
        else if (state.phase == Phase.ANSWER && !assistantRunning) stop("Assistant request ended without an answer. Check its status, then start again.")
        else if (state.phase == Phase.COOLDOWN && now >= resumeAt) runCatching { connectStream(true) }
            .onFailure { stop("Unable to reconnect live transcription. Check OpenAI settings.") }
    }
    fun feedback(answer: String?): PageContent? {
        val title: String
        val body: String
        when (state.phase) {
            Phase.OFF -> { title = "Voice is off"; body = state.detail }
            Phase.CONNECTING -> {
                if (keepAnswerWhileConnecting && !answer.isNullOrBlank()) return null
                title = "Connecting voice"; body = "Opening live English transcription…"
            }
            Phase.LISTENING -> { title = "Listening"; body = answer?.takeIf { it.isNotBlank() } ?: "Speak, then pause to ask a question." }
            Phase.SPEAKING -> { title = "Hearing you"; body = state.transcript.ifBlank { "Pause briefly to send your question." } }
            Phase.TRANSCRIBING -> { title = "Transcribing"; body = state.transcript.ifBlank { "Finishing your English question…" } }
            Phase.ANSWER -> { title = "Thinking"; body = state.transcript }
            Phase.COOLDOWN -> return null
        }
        return PageContent("voice:${state.phase}:$body", title, body)
    }
    private fun publish(next: State) {
        if (next != state) {
            if (next.phase != state.phase) phaseStarted = SystemClock.elapsedRealtime()
            state = next; changed()
        }
    }
    fun close() { stop(); main.removeCallbacksAndMessages(null); http.dispatcher.cancelAll(); http.connectionPool.evictAll() }
}
