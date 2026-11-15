// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded, in-memory PCM utterances. No audio files or Android microphone access. */
internal class SpeechSegmenter(private val onChunk: (ShortArray) -> Unit = {}) {
    companion object {
        const val RATE = 48_000
        const val MAX_SAMPLES = RATE * 20
        private const val PRE_ROLL = RATE * 3 / 10
        private const val END_SILENCE = RATE * 7 / 10
        private const val MIN_VOICE = RATE * 3 / 10
        private const val FRAME = RATE / 50
    }
    private val pre = ShortArray(PRE_ROLL)
    private val clip = ShortArray(MAX_SAMPLES)
    private var preCount = 0
    private var preIndex = 0
    private var count = 0
    private var voiced = 0
    private var quiet = 0
    private var streamed = 0
    val speaking get() = count > 0

    fun accept(samples: ShortArray): ShortArray? {
        var offset = 0
        while (offset < samples.size) {
            val size = minOf(FRAME, samples.size - offset)
            var energy = 0.0
            for (i in offset until offset + size) energy += samples[i].toDouble() * samples[i]
            // Approximately -40 dBFS. Duration filtering rejects brief knocks and clicks.
            val speech = energy / size >= 330.0 * 330.0
            if (speech && count == 0) {
                val start = if (preCount == pre.size) preIndex else 0
                for (i in 0 until preCount) clip[count++] = pre[(start + i) % pre.size]
            }
            if (speech || count > 0) {
                val added = minOf(size, clip.size - count)
                samples.copyInto(clip, count, offset, offset + added)
                count += added
                if (speech) { voiced += added; quiet = 0 } else quiet += added
                if (voiced >= MIN_VOICE && count > streamed) {
                    onChunk(clip.copyOfRange(streamed, count))
                    streamed = count
                }
                if (count == clip.size || quiet >= END_SILENCE) {
                    val result = finish()
                    if (result != null) return result
                }
            } else {
                for (i in offset until offset + size) {
                    pre[preIndex] = samples[i]
                    preIndex = (preIndex + 1) % pre.size
                    preCount = minOf(pre.size, preCount + 1)
                }
            }
            offset += size
        }
        return null
    }

    fun finish(): ShortArray? {
        val result = if (voiced >= MIN_VOICE) clip.copyOf(count) else null
        reset()
        return result
    }

    fun reset() {
        clip.fill(0, 0, count); pre.fill(0)
        count = 0; voiced = 0; quiet = 0; preCount = 0; preIndex = 0; streamed = 0
    }
}

/** Invalidates queued audio and network completions whenever capture is stopped or rearmed. */
internal class UtteranceCapture(onChunk: (ShortArray) -> Unit = {}) {
    data class Clip(val generation: Long, val samples: ShortArray)
    private val segmenter = SpeechSegmenter(onChunk)
    private var generation = 0L
    private var accepting = false
    @Synchronized fun arm(): Long { generation++; segmenter.reset(); accepting = true; return generation }
    @Synchronized fun stop() { generation++; accepting = false; segmenter.reset() }
    @Synchronized fun isCurrent(token: Long) = token == generation
    @Synchronized fun isSpeaking() = accepting && segmenter.speaking
    @Synchronized fun push(samples: ShortArray): Clip? {
        if (!accepting) return null
        val clip = segmenter.accept(samples) ?: return null
        accepting = false
        return Clip(generation, clip)
    }
    @Synchronized fun finish(): Clip? {
        if (!accepting) return null
        val clip = segmenter.finish() ?: return null
        accepting = false
        return Clip(generation, clip)
    }
}

internal object SpeechPcm {
    // One channel on the tested Strix firmware is silent. Preserve the stronger channel
    // without attenuating it or cancelling opposite-phase stereo signals.
    fun mono(left: ShortArray, right: ShortArray): ShortArray {
        if (right.isEmpty()) return left
        fun energy(a: ShortArray) = a.sumOf { it.toDouble() * it }
        return if (energy(right) > energy(left)) right else left
    }
    fun wav(samples: ShortArray): ByteArray {
        val size = samples.size * 2
        return ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1); putInt(SpeechSegmenter.RATE)
            putInt(SpeechSegmenter.RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(size)
            samples.forEach { putShort(it) }
        }.array()
    }
}
