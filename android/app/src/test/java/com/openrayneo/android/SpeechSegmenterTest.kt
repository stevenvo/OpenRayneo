// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpeechSegmenterTest {
    private fun samples(ms: Int, level: Int = 0) = ShortArray(ms * 48) { if (it % 2 == 0) level.toShort() else (-level).toShort() }

    @Test fun silenceAndBriefClicksNeverProduceAnUpload() {
        val s = SpeechSegmenter()
        assertNull(s.accept(samples(30_000)))
        assertNull(s.accept(samples(80, 8000)))
        assertNull(s.accept(samples(1500)))
        assertFalse(s.speaking)
        assertNull(s.finish())
    }
    @Test fun voiceIncludesPrerollAndEndsAfterPause() {
        val s = SpeechSegmenter()
        s.accept(samples(1000, 30))
        assertNull(s.accept(samples(600, 1500)))
        assertTrue(s.speaking)
        assertNull(s.accept(samples(600)))
        val result = s.accept(samples(100))!!
        assertEquals(1600 * 48, result.size)
        assertEquals(30.toShort(), result[0])
        assertEquals(1500.toShort(), result[300 * 48])
        assertFalse(s.speaking)
    }
    @Test fun shortPausesDoNotSplitAQuestion() {
        val s = SpeechSegmenter()
        s.accept(samples(400, 1800))
        assertNull(s.accept(samples(400)))
        assertNull(s.accept(samples(400, 1800)))
        assertEquals(1900 * 48, s.accept(samples(700))!!.size)
    }
    @Test fun longSpeechIsCappedAtTwentySeconds() {
        val s = SpeechSegmenter()
        assertEquals(SpeechSegmenter.MAX_SAMPLES, s.accept(samples(25_000, 2000))!!.size)
        assertFalse(s.speaking)
    }
    @Test fun explicitSubmitRequiresEnoughVoicedAudio() {
        val s = SpeechSegmenter()
        s.accept(samples(100, 4000)); assertNull(s.finish())
        s.accept(samples(500, 4000)); assertEquals(500 * 48, s.finish()!!.size)
    }
    @Test fun resetDiscardsAPartialQuestion() {
        val s = SpeechSegmenter()
        s.accept(samples(600, 2000)); s.reset()
        assertNull(s.accept(samples(2000)))
        assertNull(s.finish())
    }
    @Test fun captureDropsAudioWhileATurnIsInFlight() {
        val c = UtteranceCapture()
        assertNull(c.push(samples(400, 2000))) // off by default
        c.arm()
        c.push(samples(400, 2000))
        val clip = c.push(samples(1200))!!
        assertTrue(c.isCurrent(clip.generation))
        assertNull(c.push(samples(400, 2000)))
        assertNull(c.finish())
    }
    @Test fun stopAndRestartInvalidateLateTranscriptionCompletions() {
        val c = UtteranceCapture()
        c.arm(); c.push(samples(400, 2000))
        val clip = c.finish()!!
        c.stop()
        assertFalse(c.isCurrent(clip.generation))
        c.arm()
        assertFalse(c.isCurrent(clip.generation))
        assertNull(c.finish())
    }
    @Test fun wavHasCorrectPcmFormatAndLittleEndianSamples() {
        val bytes = SpeechPcm.wav(shortArrayOf(0x1234, -2))
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(40, b.getInt(4))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals(1, b.getShort(20).toInt())
        assertEquals(1, b.getShort(22).toInt())
        assertEquals(48000, b.getInt(24))
        assertEquals(96000, b.getInt(28))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(4, b.getInt(40))
        assertEquals(0x1234, b.getShort(44).toInt())
        assertEquals(-2, b.getShort(46).toInt())
    }
    @Test fun silentOrInvertedStereoDoesNotCancelSpeech() {
        val voice = shortArrayOf(1000, -1000)
        assertArrayEquals(voice, SpeechPcm.mono(shortArrayOf(0, 0), voice))
        assertArrayEquals(voice, SpeechPcm.mono(voice, shortArrayOf(-1000, 1000)))
        assertArrayEquals(voice, SpeechPcm.mono(voice, shortArrayOf()))
    }
}
