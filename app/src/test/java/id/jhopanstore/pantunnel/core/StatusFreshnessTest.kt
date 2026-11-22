package id.jhopanstore.pantunnel.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the two stuck-state bugs users hit on MIUI:
 *
 *  1. The phone ran out of battery mid-tunnel. After powering on, the card still claimed the VPN
 *     was up, because staleness was only checked for [TunnelState.Connected].
 *  2. Tapping connect left the button reading DISCONNECT but doing nothing, so the connect
 *     attempt could never be cancelled.
 */
class StatusFreshnessTest {

    private val now = 1_000_000_000_000L

    @Test
    fun `fresh connected is alive`() {
        assertTrue(StatusFreshness.isAlive(TunnelState.Connected, now - 1_000, now))
    }

    @Test
    fun `stale connected is not alive`() {
        assertFalse(StatusFreshness.isAlive(TunnelState.Connected, now - 60_000, now))
    }

    // ---- bug 1: a phone that died mid-connect must not look alive ----

    @Test
    fun `stale connecting is not alive`() {
        assertFalse(StatusFreshness.isAlive(TunnelState.Connecting, now - 60_000, now))
    }

    @Test
    fun `stale checking is not alive`() {
        assertFalse(StatusFreshness.isAlive(TunnelState.Checking, now - 60_000, now))
    }

    @Test
    fun `stale reconnecting is not alive`() {
        assertFalse(StatusFreshness.isAlive(TunnelState.Reconnecting, now - 60_000, now))
    }

    @Test
    fun `stale busy states are reported stale so the card can be cleared`() {
        assertTrue(StatusFreshness.isStale(TunnelState.Connecting, now - 60_000, now))
        assertTrue(StatusFreshness.isStale(TunnelState.Checking, now - 60_000, now))
        assertTrue(StatusFreshness.isStale(TunnelState.Reconnecting, now - 60_000, now))
        assertTrue(StatusFreshness.isStale(TunnelState.Connected, now - 60_000, now))
    }

    // ---- idle and failed are terminal: never "stale" ----

    @Test
    fun `idle is never stale`() {
        assertFalse(StatusFreshness.isStale(TunnelState.Idle, 0L, now))
        assertFalse(StatusFreshness.isAlive(TunnelState.Idle, now, now))
    }

    @Test
    fun `failed is never stale`() {
        assertFalse(StatusFreshness.isStale(TunnelState.Failed, 0L, now))
    }

    // ---- boundary ----

    @Test
    fun `exactly at the threshold counts as gone`() {
        assertFalse(StatusFreshness.isAlive(TunnelState.Connected, now - 35_000, now))
        assertTrue(StatusFreshness.isAlive(TunnelState.Connected, now - 34_999, now))
    }

    @Test
    fun `threshold matches the heartbeat budget`() {
        // heartbeat every 15 s; three missed beats => gone
        assertTrue(StatusFreshness.STALE_AFTER_MS > 15_000L * 2)
        assertTrue(StatusFreshness.STALE_AFTER_MS < 15_000L * 3)
    }
}
