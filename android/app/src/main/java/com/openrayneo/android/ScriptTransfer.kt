// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Teleprompter type 2 can report both acceptance (1) and script receipt (7).
 * Neither replaces the separate matching 0x11 file-transfer completion. */
internal object TeleprompterReply {
    fun failed(type: Int, code: Int?) = code != null && code != 1 && !(type == 2 && code == 7)
}

/** File-transfer messages use a different protobuf schema from display messages. */
internal object ProtoFields {
    data class Field(val number: Int, val integer: Long? = null, val bytes: ByteArray? = null)
    private fun variable(value: Long): ByteArray {
        require(value >= 0)
        var n = value
        val out = ByteArrayOutputStream()
        while (n >= 128) { out.write(((n and 127) or 128).toInt()); n = n ushr 7 }
        out.write(n.toInt()); return out.toByteArray()
    }
    fun number(field: Int, value: Long) = variable(field.toLong() shl 3) + variable(value)
    fun bytes(field: Int, value: ByteArray) = variable((field.toLong() shl 3) or 2) + variable(value.size.toLong()) + value
    fun text(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))
    fun decode(data: ByteArray): List<Field> {
        var at = 0
        fun read(): Long {
            var result = 0L
            for (shift in 0..56 step 7) {
                require(at < data.size) { "Incomplete protobuf integer" }
                val b = data[at++].toInt() and 255
                result = result or ((b and 127).toLong() shl shift)
                if (b and 128 == 0) return result
            }
            error("Protobuf integer too large")
        }
        val result = mutableListOf<Field>()
        while (at < data.size) {
            val tag = read(); val field = tag ushr 3
            require(field in 1..536870911)
            when ((tag and 7).toInt()) {
                0 -> result += Field(field.toInt(), integer = read())
                2 -> {
                    val size = read(); require(size <= data.size - at)
                    result += Field(field.toInt(), bytes = data.copyOfRange(at, at + size.toInt()))
                    at += size.toInt()
                }
                1 -> { require(data.size - at >= 8); at += 8 }
                5 -> { require(data.size - at >= 4); at += 4 }
                else -> error("Unsupported protobuf wire type")
            }
        }
        return result
    }
}

internal class ScriptTransfer(val taskId: String, val documentId: String, val text: String) {
    companion object { const val MAX_BYTES = 32_000 }
    val content = text.toByteArray(Charsets.UTF_8)
    private val sent = BooleanArray(content.size)
    private var requests = 0
    init { require(content.isNotEmpty() && content.size <= MAX_BYTES) { "Script must contain 1–32,000 UTF-8 bytes." } }
    val complete get() = sent.all { it }
    val checksum: String get() {
        var hash = 0x811c9dc5.toInt()
        content.forEach { hash = (hash xor (it.toInt() and 255)) * 0x01000193 }
        return "%08x".format(hash)
    }
    fun descriptor(): ByteArray {
        val md5 = MessageDigest.getInstance("MD5").digest(content).joinToString("") { "%02x".format(it) }
        return ProtoFields.bytes(1, ProtoFields.text(1, taskId) + ProtoFields.text(2, documentId) +
            ProtoFields.number(3, content.size.toLong()) + ProtoFields.text(4, md5) + ProtoFields.number(5, 17))
    }
    /** Null means another transfer; unrelated packets never change this task. */
    fun chunk(request: ByteArray): ByteArray? {
        val fields = ProtoFields.decode(request)
        fun number(n: Int) = fields.firstOrNull { it.number == n }?.integer ?: 0
        if (fields.firstOrNull { it.number == 1 }?.bytes?.toString(Charsets.UTF_8) != taskId) return null
        require(++requests <= 128) { "Too many script chunk requests" }
        val offset = number(3); val wanted = number(4)
        require(offset >= 0 && offset < content.size && wanted > 0) { "Invalid script chunk range" }
        val size = minOf(wanted, content.size.toLong() - offset).toInt()
        val start = offset.toInt(); val end = start + size
        for (i in start until end) sent[i] = true
        return ProtoFields.bytes(4, ProtoFields.text(1, taskId) + ProtoFields.number(2, number(2)) +
            ProtoFields.number(3, offset) + ProtoFields.number(4, size.toLong()) +
            ProtoFields.bytes(5, content.copyOfRange(start, end)))
    }
    fun result(body: ByteArray): Boolean? {
        val fields = ProtoFields.decode(body)
        if (fields.firstOrNull { it.number == 1 }?.bytes?.toString(Charsets.UTF_8) != taskId) return null
        return complete && (fields.firstOrNull { it.number == 2 }?.integer ?: 0) == 0L
    }
}
