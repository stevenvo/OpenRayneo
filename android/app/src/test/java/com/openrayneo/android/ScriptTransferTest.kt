package com.openrayneo.android

import org.junit.Assert.*
import org.junit.Test

class ScriptTransferTest {
    private fun request(task: String = "task", offset: Long? = null, count: Long = 99) =
        ProtoFields.text(1, task) + (offset?.let { ProtoFields.number(3, it) } ?: byteArrayOf()) + ProtoFields.number(4, count)
    @Test fun omittedZeroOffsetAndResultAreAccepted() {
        val t = ScriptTransfer("task", "doc", "Hello 🌍")
        val data = ProtoFields.decode(ProtoFields.decode(t.chunk(request())!!).single().bytes!!).first { it.number == 5 }.bytes!!
        assertArrayEquals("Hello 🌍".toByteArray(), data)
        assertTrue(t.result(ProtoFields.text(1, "task")) == true)
    }
    @Test fun outOfOrderChunksDoNotHideGaps() {
        val t = ScriptTransfer("task", "doc", "abcdef")
        t.chunk(request(offset = 3, count = 3))
        assertFalse(t.complete)
        assertFalse(t.result(ProtoFields.text(1,"task"))!!)
        t.chunk(request(offset = 0, count = 3)); assertTrue(t.complete)
    }
    @Test fun ignoresUnrelatedTasksAndAcceptsDuplicateChunks() {
        val t = ScriptTransfer("task", "doc", "abc")
        assertNull(t.chunk(request("other")))
        repeat(2) { assertNotNull(t.chunk(request())) }
        assertNull(t.result(ProtoFields.text(1, "other")))
        assertTrue(t.complete)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsOutOfRange() { ScriptTransfer("task","doc","abc").chunk(request(offset=3)) }
    @Test(expected = IllegalArgumentException::class) fun rejectsEmptyChunk() { ScriptTransfer("task","doc","abc").chunk(request(count=0)) }
    @Test(expected = IllegalArgumentException::class) fun boundsUtf8BytesNotCharacters() { ScriptTransfer("task","doc","🌍".repeat(8001)) }
    @Test(expected = IllegalArgumentException::class) fun rejectsTruncatedProtobuf() { ProtoFields.decode(byteArrayOf(10, 5, 1)) }
    @Test fun knownFnvVector() { assertEquals("4f9f2cab", ScriptTransfer("t","d","hello").checksum) }
    @Test fun scriptReceiptDoesNotAbortOrCompleteTheFileTransfer() {
        val pending = ScriptTransfer("task", "doc", "hello")
        assertFalse(TeleprompterReply.failed(2, 7))
        assertFalse(pending.result(ProtoFields.text(1, "task"))!!)
        assertTrue(TeleprompterReply.failed(2, 4))
        assertTrue(TeleprompterReply.failed(3, 7))
        assertFalse(TeleprompterReply.failed(3, 1))
    }
}
