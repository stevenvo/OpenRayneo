// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Stateful 48 -> 24 kHz mono PCM conversion and 100 ms packetization. */
internal class StreamingPcm(private val output: (ByteArray) -> Unit) {
    private val bytes = ByteArray(4800)
    private var count = 0
    private var first: Short? = null
    fun accept(samples: ShortArray) {
        for (sample in samples) {
            val a = first
            if (a == null) first = sample else {
                val value = (a.toInt() + sample.toInt()) / 2
                bytes[count++] = value.toByte(); bytes[count++] = (value shr 8).toByte()
                first = null
                if (count == bytes.size) flush()
            }
        }
    }
    fun flush() { if (count > 0) { output(bytes.copyOf(count)); bytes.fill(0); count = 0 } }
    fun reset() { bytes.fill(0); count = 0; first = null }
}

/** One committed question per stream. Reject late, duplicate or mismatched item results. */
internal class StreamingTranscript {
    private var activeItem: String? = null
    private var committedItem: String? = null
    private var finished = false
    private var text = ""
    fun delta(item: String, delta: String): String? {
        if (finished || item.isBlank() || (activeItem != null && activeItem != item)) return null
        activeItem = item
        text = (text + delta).take(4000)
        return text
    }
    fun committed(item: String): Boolean {
        if (finished || item.isBlank() || (activeItem != null && activeItem != item)) return false
        activeItem = item; committedItem = item
        return true
    }
    fun complete(item: String, transcript: String): String? {
        if (finished || committedItem == null || committedItem != item) return null
        finished = true
        return transcript.trim().take(4000)
    }
}
