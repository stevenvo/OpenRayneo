// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

internal class CaptionTranscript {
    private val lines = ArrayDeque<String>()
    val text get() = lines.joinToString("\n")
    fun append(value: String) {
        val line = value.trim().take(4000)
        if (line.isBlank()) return
        lines.addLast(line)
        while (lines.sumOf { it.length + 1 } > 60_000) lines.removeFirst()
    }
    fun clear() = lines.clear()
}

/** 48 kHz -> 16 kHz box-filter decimation, carrying incomplete groups across packets. */
internal class Pcm16k {
    private var count = 0
    private var sum = 0
    fun accept(samples: ShortArray): ShortArray {
        val out = ShortArray((count + samples.size) / 3)
        var at = 0
        samples.forEach { sample ->
            sum += sample; count++
            if (count == 3) { out[at++] = (sum / 3).toShort(); count = 0; sum = 0 }
        }
        return out
    }
    fun reset() { count = 0; sum = 0 }
}
