// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Explicitly started continuous English captions. PCM stays on the phone. */
internal class LocalCaptions(private val context: Context, private val changed: () -> Unit) {
    data class State(val active: Boolean = false, val ready: Boolean = false,
        val detail: String = "Continuous English captions run locally on your phone.",
        val transcript: String = "", val partial: String = "")
    @Volatile var state = State(); private set
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val queued = AtomicInteger()
    @Volatile private var generation = 0
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private val transcript = CaptionTranscript()
    private val downsample = Pcm16k()
    fun start() {
        stop(); val token = generation
        state = State(active = true, detail = "Loading offline English recognition…"); changed()
        worker.execute {
            try {
                if (model == null) {
                    val name = "vosk-model-small-en-us-0.15"
                    val target = File(context.filesDir, name)
                    val marker = File(target, ".ready")
                    if (!marker.exists()) {
                        fun copy(path: String, destination: File) {
                            val children = context.assets.list(path).orEmpty()
                            if (children.isEmpty()) context.assets.open(path).use { input ->
                                destination.parentFile?.mkdirs(); destination.outputStream().use(input::copyTo)
                            } else { destination.mkdirs(); children.forEach { copy("$path/$it", File(destination, it)) } }
                        }
                        copy(name, target); marker.writeText("0.15")
                    }
                    model = Model(target.absolutePath)
                }
                if (token != generation) return@execute
                recognizer = Recognizer(model, 16000f)
                transcript.clear(); downsample.reset()
                main.post { if (token == generation) { state = State(true, true, "Listening locally through the glasses. No audio is uploaded."); changed() } }
            } catch (_: Exception) { main.post { if (token == generation) stop("Offline speech model could not load. Reinstall a build containing the English model.") } }
        }
    }
    fun consume(left: ShortArray, right: ShortArray) {
        if (!state.active || !state.ready) return
        val token = generation
        if (queued.incrementAndGet() > 32) {
            queued.decrementAndGet()
            main.post { if (token == generation) stop("Offline recognition could not keep up. Stop other heavy apps and retry.") }
            return
        }
        val samples = SpeechPcm.mono(left, right).copyOf()
        worker.execute {
            try {
                if (token != generation) return@execute
                val active = recognizer ?: return@execute
                val pcm = downsample.accept(samples)
                val final = active.acceptWaveForm(pcm, pcm.size)
                pcm.fill(0)
                val json = JSONObject(if (final) active.result else active.partialResult)
                val text = json.optString(if (final) "text" else "partial").trim()
                if (final) transcript.append(text)
                val completed = transcript.text
                main.post { if (token == generation && state.active) {
                    val next = state.copy(transcript = completed, partial = if (final) "" else text)
                    if (next != state) { state = next; changed() }
                } }
            } catch (_: Exception) { main.post { if (token == generation) stop("Offline transcription stopped after an audio error. Start again.") } }
            finally { samples.fill(0); queued.decrementAndGet() }
        }
    }
    fun stop(detail: String = "Transcription stopped. You can copy or save the text.") {
        generation++
        state = state.copy(active = false, ready = false, detail = detail,
            transcript = listOf(state.transcript, state.partial).filter { it.isNotBlank() }.joinToString("\n"), partial = "")
        worker.execute { recognizer?.close(); recognizer = null; downsample.reset() }
        changed()
    }
    fun close() {
        stop(); worker.execute { model?.close(); model = null }; worker.shutdown()
    }
}
