// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.util.Base64
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject

/** A single streaming question; no audio files, credentials or transcript logging. */
internal class OpenAiSpeechStream(
    http: OkHttpClient,
    key: String,
    private val ready: () -> Unit,
    private val partial: (String) -> Unit,
    private val complete: (String) -> Unit,
    private val error: (String) -> Unit,
) {
    private var socket: WebSocket? = null
    private var closed = false
    private var configured = false
    private var committed = false
    private val transcript = StreamingTranscript()
    private val pcm = StreamingPcm { bytes ->
        send(JSONObject().put("type", "input_audio_buffer.append")
            .put("audio", Base64.encodeToString(bytes, Base64.NO_WRAP)))
        bytes.fill(0)
    }
    init {
        val request = Request.Builder().url("wss://api.openai.com/v1/realtime?intent=transcription")
            .header("Authorization", "Bearer $key").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(this@OpenAiSpeechStream) {
                    if (closed) { webSocket.cancel(); return }
                    socket = webSocket
                    val input = JSONObject().put("format", JSONObject().put("type", "audio/pcm").put("rate", 24000))
                        .put("transcription", JSONObject().put("model", "gpt-live-transcribe")
                            .put("languages", JSONArray().put("en")).put("delay", "low"))
                        .put("turn_detection", JSONObject.NULL)
                    send(JSONObject().put("type", "session.update").put("session", JSONObject()
                        .put("type", "transcription").put("audio", JSONObject().put("input", input))))
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                synchronized(this@OpenAiSpeechStream) {
                    if (closed) return
                    runCatching {
                        val event = JSONObject(text)
                        when (event.optString("type")) {
                            "session.updated" -> if (!configured) { configured = true; ready() }
                            "input_audio_buffer.committed" -> {
                                check(committed && transcript.committed(event.optString("item_id"))) { "Unexpected transcription item" }
                            }
                            "conversation.item.input_audio_transcription.delta" ->
                                transcript.delta(event.optString("item_id"), event.optString("delta"))?.let(partial)
                            "conversation.item.input_audio_transcription.completed" ->
                                transcript.complete(event.optString("item_id"), event.optString("transcript"))?.let(complete)
                            "conversation.item.input_audio_transcription.failed", "error" -> {
                                val code = event.optJSONObject("error")?.optString("code").orEmpty()
                                fail("Streaming transcription failed${if (code.matches(Regex("[a-z_]{1,60}"))) " ($code)" else ""}. Check API access and start again.")
                            }
                        }
                    }.onFailure { fail("Invalid streaming transcription response. Start again.") }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(this@OpenAiSpeechStream) {
                    if (!closed) fail("Speech streaming connection failed${response?.let { " (HTTP ${it.code})" }.orEmpty()}. Check internet and API access.")
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(this@OpenAiSpeechStream) { if (!closed) fail("Speech streaming connection closed. Start again.") }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(this@OpenAiSpeechStream) { if (!closed) fail("Speech streaming connection closed. Start again.") }
            }
        })
    }
    @Synchronized fun append(samples: ShortArray) {
        if (!closed && configured && !committed) pcm.accept(samples)
        samples.fill(0)
    }
    @Synchronized fun commit() {
        if (closed || !configured || committed) return
        pcm.flush(); committed = true
        send(JSONObject().put("type", "input_audio_buffer.commit"))
    }
    private fun send(value: JSONObject) {
        if (closed) return
        val ws = socket ?: return
        if (ws.queueSize() > 256_000 || !ws.send(value.toString())) fail("Speech upload cannot keep up. Check internet and start again.")
    }
    private fun fail(message: String) { if (!closed) { close(); error(message) } }
    @Synchronized fun close() { closed = true; pcm.reset(); socket?.cancel(); socket = null }
}
