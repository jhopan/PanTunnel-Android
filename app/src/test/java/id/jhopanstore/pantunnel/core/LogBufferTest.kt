package id.jhopanstore.pantunnel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the fix for "the log is permanent, it piles up and never clears": entries now age out so
 * a stale error cannot sit in the status box looking like a live fault.
 */
class LogBufferTest {

    @Test
    fun `entries are returned oldest first`() {
        val b = LogBuffer(10)
        b.add("first", 0)
        b.add("second", 0)
        assertEquals(listOf("first", "second"), b.recent(0, 60_000))
    }

    @Test
    fun `old entries expire`() {
        val b = LogBuffer(10)
        b.add("old error", 1_000)
        b.add("new line", 70_000)
        assertEquals(listOf("new line"), b.recent(70_000, ttlMs = 60_000))
    }

    @Test
    fun `everything expires when nothing is recent`() {
        val b = LogBuffer(10)
        b.add("old", 1_000)
        b.add("also old", 2_000)
        assertTrue(b.recent(500_000, ttlMs = 60_000).isEmpty())
    }

    @Test
    fun `hard cap drops the oldest`() {
        val b = LogBuffer(3)
        repeat(5) { b.add("line $it", 0) }
        assertEquals(listOf("line 2", "line 3", "line 4"), b.recent(0, 60_000))
    }

    @Test
    fun `blank entries are ignored`() {
        val b = LogBuffer(10)
        b.add("", 0)
        b.add("   ", 0)
        assertEquals(0, b.size())
    }

    @Test
    fun `clear empties the buffer`() {
        val b = LogBuffer(10)
        b.add("x", 0)
        b.clear()
        assertEquals(0, b.size())
    }

    @Test
    fun `expiry runs on read so the box shrinks over time`() {
        val b = LogBuffer(10)
        b.add("a", 0)
        b.add("b", 40_000)
        assertEquals(2, b.recent(40_000, ttlMs = 60_000).size)
        assertEquals(1, b.recent(100_000, ttlMs = 60_000).size)
        assertEquals(0, b.recent(200_000, ttlMs = 60_000).size)
    }
}
