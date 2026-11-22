package id.jhopanstore.pantunnel.core

/**
 * Detects a burst of dead-connection errors and decides when the tunnel must be rebuilt.
 *
 * Why this exists: when the WebSocket under a VLESS outbound dies, sing-box reports a `read tcp`
 * error and the *existing* flows through that socket hang forever. New connections — including the
 * HTTP probe to 127.0.0.1 — open a fresh WebSocket and succeed, so the probe keeps reporting
 * "Connected" while every app on the device has lost internet. A single error is harmless noise;
 * several within a short window mean the transport is dead and only a reconnect clears the stuck
 * flows.
 *
 * The rule: [threshold] transient errors inside [windowMs] ⇒ rebuild the tunnel.
 */
class TransientErrorMonitor(
    private val threshold: Int = DEFAULT_THRESHOLD,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {

    private val timestamps = ArrayDeque<Long>()

    /** @return true when the tunnel should be rebuilt. */
    fun record(now: Long): Boolean {
        timestamps.addLast(now)
        while (timestamps.isNotEmpty() && now - timestamps.first() > windowMs) {
            timestamps.removeFirst()
        }
        return timestamps.size >= threshold
    }

    /** Called after a successful probe or a reconnect. */
    fun reset() = timestamps.clear()

    /** Visible for the status line: how many recent dead-connection errors are queued. */
    fun pending(now: Long): Int {
        while (timestamps.isNotEmpty() && now - timestamps.first() > windowMs) {
            timestamps.removeFirst()
        }
        return timestamps.size
    }

    companion object {
        /**
         * Three errors inside fifteen seconds. A lone stale pooled connection produces one error
         * and heals itself, so it never trips this; a dead WebSocket produces one error per open
         * flow and trips it immediately.
         */
        const val DEFAULT_THRESHOLD = 3
        const val DEFAULT_WINDOW_MS = 15_000L
    }
}