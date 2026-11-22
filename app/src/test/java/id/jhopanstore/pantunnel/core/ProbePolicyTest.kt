package id.jhopanstore.pantunnel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the probe failure policy.
 *
 * The bug this guards: the old inline code did `failedProbes++` and then reset it to 0 in the
 * same `else` branch, so `PROBE_FAIL_LIMIT` was unreachable and a single failed probe killed the
 * tunnel — users saw the VPN die roughly every probe interval (~30 s).
 */
class ProbePolicyTest {

    private val limit = 3
    private val maxReconnects = 3

    @Test
    fun `single failure keeps the tunnel alive`() {
        val d = ProbePolicy.onFailure(0, 0, limit, maxReconnects)
        assertEquals(ProbeVerdict.KEEP_RETRYING, d.verdict)
        assertEquals("counter must accumulate, not reset", 1, d.failedProbes)
    }

    @Test
    fun `counter accumulates across consecutive failures`() {
        var failed = 0
        var reconnects = 0
        val verdicts = mutableListOf<ProbeVerdict>()

        repeat(2) {
            val d = ProbePolicy.onFailure(failed, reconnects, limit, maxReconnects)
            failed = d.failedProbes
            reconnects = d.autoReconnects
            verdicts += d.verdict
        }

        assertEquals(listOf(ProbeVerdict.KEEP_RETRYING, ProbeVerdict.KEEP_RETRYING), verdicts)
        assertEquals(2, failed)
    }

    @Test
    fun `third consecutive failure triggers reconnect`() {
        val d = ProbePolicy.onFailure(failedProbes = 2, autoReconnects = 0, limit = limit, maxReconnects = maxReconnects)
        assertEquals(ProbeVerdict.RECONNECT, d.verdict)
        assertEquals("counter resets after acting", 0, d.failedProbes)
        assertEquals(1, d.autoReconnects)
    }

    @Test
    fun `reconnects are capped then give up`() {
        var reconnects = 0
        var failed = 0
        var verdict = ProbeVerdict.KEEP_RETRYING

        // exhaust the reconnect budget
        while (verdict != ProbeVerdict.GIVE_UP) {
            val d = ProbePolicy.onFailure(failed, reconnects, limit, maxReconnects)
            failed = d.failedProbes
            reconnects = d.autoReconnects
            verdict = d.verdict
            if (reconnects > maxReconnects) break
        }

        assertEquals(ProbeVerdict.GIVE_UP, verdict)
        assertEquals("budget must not be exceeded", maxReconnects, reconnects)
    }

    @Test
    fun `success clears both counters`() {
        val d = ProbePolicy.onSuccess()
        assertEquals(0, d.failedProbes)
        assertEquals(0, d.autoReconnects)
        assertEquals(ProbeVerdict.KEEP_RETRYING, d.verdict)
    }

    @Test
    fun `success after failures restores the budget`() {
        // 2 failures then success -> counters cleared
        var failed = 0
        repeat(2) { failed = ProbePolicy.onFailure(failed, 0, limit, maxReconnects).failedProbes }
        assertEquals(2, failed)

        val afterSuccess = ProbePolicy.onSuccess()
        assertEquals(0, afterSuccess.failedProbes)
        assertEquals(0, afterSuccess.autoReconnects)
    }

    @Test
    fun `failures mark the tunnel unstable`() {
        assertTrue(ProbePolicy.onFailure(0, 0, limit, maxReconnects).markUnstable)
    }

    @Test
    fun `success marks the tunnel stable`() {
        assertTrue(!ProbePolicy.onSuccess().markUnstable)
    }

    @Test
    fun `limit of one reconnects immediately`() {
        val d = ProbePolicy.onFailure(0, 0, limit = 1, maxReconnects = 3)
        assertEquals(ProbeVerdict.RECONNECT, d.verdict)
    }

    @Test
    fun `no reconnect budget left gives up immediately`() {
        val d = ProbePolicy.onFailure(2, maxReconnects, limit, maxReconnects)
        assertEquals(ProbeVerdict.GIVE_UP, d.verdict)
    }
}
