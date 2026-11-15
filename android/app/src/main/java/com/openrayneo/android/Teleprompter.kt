// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.os.Handler
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal class Teleprompter(private val main: Handler,
    private val write: (ByteArray, (Result<Unit>) -> Unit) -> Unit,
    private val changed: () -> Unit) {
    data class State(val active: Boolean = false, val transferring: Boolean = false,
        val title: String = "", val detail: String = "Choose a script to display on your glasses.")
    var state = State(); private set
    private var transfer: ScriptTransfer? = null
    private var generation = 0
    private var sequence = 0x7000
    private var title = ""
    private var speed = 120
    private var document: String? = null
    private var customScriptCount = 0
    private val timeout = Runnable { fail("Script transfer timed out. Return to the glasses home screen and try again.") }
    fun start(name: String, text: String, wordsPerMinute: Int) {
        check(!state.active) { "Stop the current teleprompter first." }
        require(wordsPerMinute in 30..300) { "Speed must be 30–300." }
        val random = UUID.randomUUID()
        val id = UUID((System.currentTimeMillis() shl 16) or 0x7000L or (random.mostSignificantBits and 0xfffL),
            (random.leastSignificantBits and 0x3fffffffffffffffL) or Long.MIN_VALUE).toString()
        val next = ScriptTransfer("task_2_${System.currentTimeMillis()}", id, text)
        generation++; title = name.trim().take(80).ifBlank { "Untitled" }; speed = wordsPerMinute
        transfer = next; document = id
        state = State(true, true, title, "Preparing script transfer…"); changed()
        main.postDelayed(timeout, 20_000)
        send(2, 1, scriptBody(next).put("action", 1)) {
            raw(ProtocolCodec.frame(0x11, 0, next.descriptor())) {
                state = state.copy(detail = "Waiting for glasses to request the script…"); changed()
            }
        }
    }
    private fun scriptBody(t: ScriptTransfer) = JSONObject().put("countdown", 3).put("scroll", 1)
        .put("speed", speed).put("gear", 1).put("depth", 1).put("size", 18).put("width", 492)
        .put("leading", 4).put("did", t.documentId).put("total", t.text.length)
        .put("pageOffset", 0).put("highLightOffset", 0)
    fun receive(frame: ProtocolCodec.Frame): Boolean {
        if (!state.active || frame.protocol !in setOf(0x11, 0x14)) return false
        val current = transfer
        if (frame.protocol == 0x11 && current != null && state.transferring) {
            try {
                for (field in ProtoFields.decode(frame.payload)) {
                    if (field.number == 3 && field.bytes != null) {
                        current.chunk(field.bytes)?.let { raw(ProtocolCodec.frame(0x11, 0, it)) {} }
                    } else if (field.number == 5 && field.bytes != null) {
                        when (current.result(field.bytes)) {
                            true -> finishTransfer(current)
                            false -> fail("The glasses did not confirm a complete script transfer.")
                            null -> Unit
                        }
                    }
                }
            } catch (_: Exception) { fail("The glasses requested an invalid script transfer.") }
            return true
        }
        if (frame.protocol == 0x14) {
            val message = ProtocolCodec.message(frame.payload)
            val body = message.json?.let(::JSONObject) ?: return true
            if (body.optString("did") != document) return false
            if (TeleprompterReply.failed(message.type, if (body.has("code")) body.optInt("code") else null)) fail("The glasses rejected teleprompter control (${body.optInt("code")}).")
            else if (message.type == 6) reset("Teleprompter ended on the glasses.")
            return true
        }
        return false
    }
    private fun finishTransfer(t: ScriptTransfer) {
        if (!state.transferring) return
        state = state.copy(transferring = false, detail = "Script received. Opening teleprompter…"); changed()
        val item = JSONObject().put("did", t.documentId).put("pin", false).put("title", title)
            .put("offset", 0).put("scroll", 1).put("total", t.text.length)
            .put("timestamp", System.currentTimeMillis() / 1000).put("preview", t.text.take(80))
            .put("timezone", java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000)
        send(1, 2, JSONObject().put("action", 2).put("type", 3).put("total", customScriptCount + 2)
            .put("chunkinfo", JSONObject().put("chunkid", 1).put("total_items", 1).put("islast", true))
            .put("items", JSONArray().put(item))) {
            send(3, 1, scriptBody(t).put("action", 1).put("code", 1).put("checksum", t.checksum)) {
                main.removeCallbacks(timeout); customScriptCount++
                state = state.copy(detail = "Script sent. Use the crown to start/pause and scroll; check the lenses."); changed()
            }
        }
    }
    fun control(action: String, completion: () -> Unit = {}) {
        val id = document ?: run { completion(); return }
        require(action in setOf("pause", "resume", "stop"))
        if (action == "stop") {
            generation++; transfer = null; main.removeCallbacks(timeout)
            state = state.copy(transferring = false, detail = "Stopping teleprompter…"); changed()
        }
        val type = when (action) { "pause" -> 4; "resume" -> 5; else -> 6 }
        val body = JSONObject().put("action", 1).put("did", id)
        if (action == "pause") body.put("offset", 0).put("code", 1).put("isCompleted", false)
        send(type, 1, body) {
            if (action == "stop") reset("Teleprompter stopped.")
            else { state = state.copy(detail = "${action.replaceFirstChar { it.uppercase() }} command sent. Check playback on the glasses."); changed() }
            completion()
        }
    }
    private fun send(type: Int, action: Int, body: JSONObject, done: () -> Unit) = raw(
        ProtocolCodec.frame(0x14, sequence++ and 65535, ProtoFields.number(1, action.toLong()) +
            ProtoFields.number(2, type.toLong()) + ProtoFields.text(3, body.toString()) + ProtoFields.bytes(4, byteArrayOf())), done)
    private fun raw(bytes: ByteArray, done: () -> Unit) {
        val token = generation
        try { write(bytes) { result -> if (token == generation) {
            if (result.isSuccess) done() else fail("Teleprompter write failed. Reconnect and retry.")
        } } } catch (_: Exception) { fail("Teleprompter write failed. Reconnect and retry.") }
    }
    private fun fail(message: String) {
        val id = document
        reset(message)
        if (id != null) runCatching {
            val body = JSONObject().put("action", 1).put("did", id)
            write(ProtocolCodec.frame(0x14, sequence++ and 65535, ProtoFields.number(1, 1) +
                ProtoFields.number(2, 6) + ProtoFields.text(3, body.toString()))) { }
        }
    }
    fun reset(message: String = "Teleprompter disconnected.") {
        generation++; main.removeCallbacks(timeout); transfer = null; document = null
        state = State(detail = message); changed()
    }
}
