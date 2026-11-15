// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Explicitly armed glasses audio -> English ASR -> existing assistant. Main-thread lifecycle. */
internal class GlassesVoice(
    private val settings: AppSettings,
    private val changed: () -> Unit,
    private val log: (String) -> Unit,
    private val submit: (AnswerSource, String) -> Unit,
) {
    enum class Phase { OFF, LISTENING, SPEAKING, TRANSCRIBING, ANSWER, COOLDOWN }
    data class State(
        val phase: Phase = Phase.OFF,
        val source: AnswerSource = AnswerSource.MOBILE_AGENT,
        val detail: String = "Use the glasses microphone for your questions.",
    ) {
        val enabled get() = phase != Phase.OFF
        val target get() = if (source == AnswerSource.MOBILE_AGENT) "Phone model" else "Computer Codex"
    }
    @Volatile var state = State(); private set
    private val main = Handler(Looper.getMainLooper())
    private val capture = UtteranceCapture()
    private val updatePending = AtomicBoolean()
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
    private var call: Call? = null
    @Volatile private var lastAudio = 0L
    private var resumeAt = 0L

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
        checkConfiguration()
        stop()
        lastAudio = SystemClock.elapsedRealtime()
        capture.arm()
        publish(State(Phase.LISTENING, source, "Listening through glasses. Speak, then pause."))
        log("Glasses voice enabled for ${state.target}")
    }

    fun stop(reason: String = "Glasses voice is off.") {
        capture.stop(); call?.cancel(); call = null; resumeAt = 0
        val wasEnabled = state.enabled
        publish(state.copy(phase = Phase.OFF, detail = reason))
        if (wasEnabled) log("Glasses voice stopped")
    }

    /** Runs on the Opus decoder worker; never blocks that worker on network or UI. */
    fun consume(left: ShortArray, right: ShortArray) {
        if (!state.enabled) return
        lastAudio = SystemClock.elapsedRealtime()
        val clip = capture.push(SpeechPcm.mono(left, right))
        if (clip != null) main.post { transcribe(clip) }
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

    fun sendNow() {
        capture.finish()?.let(::transcribe)
    }

    private fun transcribe(clip: UtteranceCapture.Clip) {
        if (!state.enabled || !capture.isCurrent(clip.generation)) { clip.samples.fill(0); return }
        try {
            val token = apiKey()
            val wav = SpeechPcm.wav(clip.samples)
            clip.samples.fill(0)
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", "gpt-transcribe")
                .addFormDataPart("languages[]", "en")
                .addFormDataPart("file", "glasses.wav", wav.toRequestBody("audio/wav".toMediaType()))
                .build()
            val request = Request.Builder().url("https://api.openai.com/v1/audio/transcriptions")
                .header("Authorization", "Bearer $token").post(body).build()
            publish(state.copy(phase = Phase.TRANSCRIBING, detail = "Transcribing glasses speech in English…"))
            log("Glasses voice: transcribing ${(wav.size - 44) / 96_000.0} seconds")
            val activeCall = http.newCall(request)
            call = activeCall
            activeCall.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    wav.fill(0)
                    main.post { fail(clip.generation, "Transcription connection failed. Check internet, then start again.") }
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            check(it.isSuccessful) { "OpenAI transcription failed (HTTP ${it.code}). Check your key and quota, then start again." }
                            val json = JSONObject(it.body?.string().orEmpty())
                            val text = json.optString("text").trim()
                            val languages = json.optJSONArray("languages")
                            check(text.isNotBlank() && text != "null" && (languages == null || languages.length() > 0)) {
                                "No clear speech recognized. Start again and speak closer to the glasses microphone."
                            }
                            text
                        }
                    }
                    wav.fill(0)
                    main.post {
                        if (!state.enabled || !capture.isCurrent(clip.generation)) return@post
                        this@GlassesVoice.call = null
                        result.onSuccess { text ->
                            log("Glasses voice: transcription received")
                            publish(state.copy(phase = Phase.ANSWER, detail = "${state.target} is answering…"))
                            runCatching { submit(state.source, text) }.onFailure {
                                stop("Unable to send the question. Check the assistant connection, then start again.")
                            }
                        }.onFailure {
                            // HTTP status is useful; response bodies and audio/transcripts are never logged.
                            val message = if (it is IllegalStateException) it.message.orEmpty() else "Invalid transcription response. Start again."
                            fail(clip.generation, message)
                        }
                    }
                }
            })
        } catch (error: Exception) {
            clip.samples.fill(0)
            fail(clip.generation, "Unable to start transcription. Check OpenAI settings, then start again.")
        }
    }

    private fun fail(generation: Long, message: String) {
        if (state.enabled && capture.isCurrent(generation)) { log(message); stop(message) }
    }

    fun answerFinished(source: AnswerSource) {
        if (state.phase != Phase.ANSWER || state.source != source) return
        resumeAt = SystemClock.elapsedRealtime() + 2_500
        publish(state.copy(phase = Phase.COOLDOWN, detail = "Answer ready. Listening resumes shortly."))
    }

    fun tick(assistantRunning: Boolean) {
        if (!state.enabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAudio > 15_000) { stop("Glasses audio stopped. Reconnect or reopen Live prompts, then start again."); return }
        if (state.phase == Phase.ANSWER && !assistantRunning) {
            stop("Assistant request ended without an answer. Check its status, then start again.")
        } else if (state.phase == Phase.COOLDOWN && now >= resumeAt) {
            capture.arm()
            publish(state.copy(phase = Phase.LISTENING, detail = "Listening through glasses. Speak, then pause."))
        }
    }

    private fun publish(next: State) { if (next != state) { state = next; changed() } }
    fun close() { stop(); main.removeCallbacksAndMessages(null); http.dispatcher.cancelAll(); http.connectionPool.evictAll() }
}
