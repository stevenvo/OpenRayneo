// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StreamingSpeechTest {
    @Test fun speechStreamsBeforeEndOfTurnAndPreservesPreroll() {
        val chunks = mutableListOf<ShortArray>()
        val s = SpeechSegmenter { chunks.add(it) }
        s.accept(ShortArray(48_000) { 20 })
        assertNull(s.accept(ShortArray(48_000) { 1000 }))
        assertTrue(chunks.isNotEmpty())
        assertEquals(20.toShort(), chunks.first().first())
        assertEquals(62_400, chunks.sumOf { it.size })
        val complete = s.accept(ShortArray(33_600))!!
        assertArrayEquals(complete, chunks.flatMap { it.toList() }.toShortArray())
    }
    @Test fun clickIsNotStreamedBeforeVoiceDurationThreshold() {
        val chunks = mutableListOf<ShortArray>()
        val s = SpeechSegmenter { chunks.add(it) }
        s.accept(ShortArray(4800) { 3000 }); s.accept(ShortArray(48_000))
        assertTrue(chunks.isEmpty())
    }
    @Test fun pcmConversionPreservesSamplesAcrossOddSizedInputBlocks() {
        val chunks = mutableListOf<ByteArray>()
        val pcm = StreamingPcm { chunks.add(it) }
        pcm.accept(shortArrayOf(100, 300, -100))
        pcm.accept(shortArrayOf(-300, 1000, 1000)); pcm.flush()
        val data = chunks.flatMap { it.toList() }.toByteArray()
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(6, data.size)
        assertEquals(200, b.short.toInt()); assertEquals(-200, b.short.toInt()); assertEquals(1000, b.short.toInt())
    }
    @Test fun packetizationHasExpectedRateAndBoundedPackets() {
        val chunks = mutableListOf<ByteArray>()
        val pcm = StreamingPcm { chunks.add(it) }
        pcm.accept(ShortArray(96_000) { 500 }); pcm.flush()
        assertEquals(20, chunks.size)
        assertTrue(chunks.all { it.size == 4800 })
        pcm.accept(shortArrayOf(100)); pcm.reset(); pcm.accept(shortArrayOf(300, 500)); pcm.flush()
        assertEquals(400, ByteBuffer.wrap(chunks.last()).order(ByteOrder.LITTLE_ENDIAN).short.toInt())
    }
    @Test fun partialsAppearBeforeCommitButFinalNeedsMatchingCommit() {
        val t = StreamingTranscript()
        assertEquals("What is ", t.delta("a", "What is "))
        assertEquals("What is the weather?", t.delta("a", "the weather?"))
        assertNull(t.complete("a", "premature"))
        assertFalse(t.committed("wrong")); assertTrue(t.committed("a"))
        assertNull(t.complete("wrong", "wrong answer"))
        assertEquals("What is the weather?", t.complete("a", "What is the weather?"))
        assertNull(t.complete("a", "duplicate")); assertNull(t.delta("a", "late"))
    }
    @Test fun finalWithoutPartialsStillWorksAfterCommit() {
        val t = StreamingTranscript()
        assertTrue(t.committed("a"))
        assertEquals("Hello", t.complete("a", " Hello "))
    }
}
