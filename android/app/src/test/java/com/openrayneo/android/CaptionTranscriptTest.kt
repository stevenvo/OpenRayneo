package com.openrayneo.android
import org.junit.Assert.*
import org.junit.Test
class CaptionTranscriptTest {
    @Test fun preservesRepeatedSentencesAndBoundsHistory() {
        val t = CaptionTranscript(); t.append("hello"); t.append("hello")
        assertEquals("hello\nhello", t.text)
        repeat(20) { t.append("x".repeat(4000)) }
        assertTrue(t.text.length <= 60000)
    }
    @Test fun resamplingCarriesAcrossPacketBoundaries() {
        val r = Pcm16k()
        assertArrayEquals(shortArrayOf(), r.accept(shortArrayOf(3,6)))
        assertArrayEquals(shortArrayOf(6,12), r.accept(shortArrayOf(9,9,12,15)))
        r.accept(shortArrayOf(300)); r.reset()
        assertArrayEquals(shortArrayOf(0),r.accept(shortArrayOf(-30,0,30)))
    }
}
