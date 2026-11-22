package id.jhopanstore.pantunnel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the fix for "internet dies after that error appears": the probe cannot see a dead
 * transport (it opens a fresh socket), so a burst of read errors must force a reconnect.
 */
class TransientErrorMonitorTest {

    @Test
    fun `single error does not trigger a reconnect`() {
        val m = TransientErrorMonitor(threshold = 3, windowMs = 15_000)
        assertFalse(m.record(1_000))
    }

    @Test
    fun `two errors still do not trigger`() {
        val m = TransientErrorMonitor(threshold = 3, windowMs = 15_000)
        assertFalse(m.record(1_000))
        assertFalse(m.record(2_000))
    }

    @Test
    fun `third error inside the window triggers`() {
        val m = TransientErrorMonitor(threshold = 3, windowMs = 15_000)
        m.record(1_000)
        m.record(2_000)
        assertTrue(m.record(3_000))
    }

    @Test
    fun `spread out errors do not trigger`() {
        val m = TransientErrorMonitor(threshold = 3, windowMs = 15_000)
        assertFalse(m.record(1_000))
        assertFalse(m.record(20_000))
        assertFalse(m.record(40_000))
    }

    @Test
    fun `burst after a quiet period triggers on the third`() {
        val m = TransientErrorMonitor(threshold = 3, windowMs = 15_000)
        m.record(1_000)
        m.record(2_000)
        m.record(30_000)   // earlier two expired
        m.record(31_000)
        assertTrue(m.record(32_000))
    }

    @Test
    fun `reset clears the window`() {
        val m = TransientErrorMonitor(threshold = 3, windowMs = 15_000)
        m.record(1_000)
        m.record(2_000)
        m.reset()
        assertFalse(m.record(3_000))
        assertFalse(m.record(4_000))
    }

    @Test
    fun `pending counts only errors inside the window`() {
        val m = TransientErrorMonitor(threshold = 3, windowMs = 15_000)
        m.record(1_000)
        m.record(2_000)
        assertEquals(2, m.pending(3_000))
        assertEquals(0, m.pending(30_000))
    }

    @Test
    fun `defaults are three errors in fifteen seconds`() {
        assertEquals(3, TransientErrorMonitor.DEFAULT_THRESHOLD)
        assertEquals(15_000L, TransientErrorMonitor.DEFAULT_WINDOW_MS)
    }
}
