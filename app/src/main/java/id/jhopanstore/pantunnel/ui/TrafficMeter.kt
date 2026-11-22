package id.jhopanstore.pantunnel.ui

import android.os.Process
import id.jhopanstore.pantunnel.service.TunnelStatusStore
import java.util.Locale

/**
 * Traffic meter for one VPN session.
 *
 * Extracted from MainActivity because the baseline rules are easy to get wrong: resetting on
 * every repaint makes the speed read "0 B/s" forever, so the baseline is seeded only when a
 * session actually starts.
 */
class TrafficMeter(private val statusStore: TunnelStatusStore) {

    private var baselineSeeded = false
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastSampleAt = 0L

    data class Reading(
        val sessionRx: Long,
        val sessionTx: Long,
        val downRate: Long,
        val upRate: Long,
    ) {
        fun format(): String =
            "↓ ${bytes(sessionRx)} (${bytes(downRate)}/s)" +
                "   ↑ ${bytes(sessionTx)} (${bytes(upRate)}/s)"
    }

    /** Call when a new session starts (transition into Connected) so rates restart from zero. */
    fun reset() {
        baselineSeeded = false
    }

    /** Call when the tunnel is gone: clears the counters and drops the baseline. */
    fun clear() {
        baselineSeeded = false
        lastRx = 0
        lastTx = 0
        lastSampleAt = 0
    }

    /** @return null when TrafficStats is unavailable (older devices return -1). */
    fun sample(): Reading? {
        val uid = Process.myUid()
        val rx = android.net.TrafficStats.getUidRxBytes(uid)
        val tx = android.net.TrafficStats.getUidTxBytes(uid)
        if (rx < 0 || tx < 0) return null

        val snapshot = statusStore.snapshot()
        val now = System.currentTimeMillis()

        val baseRx = snapshot.meterBaseRx
        val baseTx = snapshot.meterBaseTx
        val sessionRx = if (baseRx >= 0 && rx >= baseRx) rx - baseRx else snapshot.sessionRx
        val sessionTx = if (baseTx >= 0 && tx >= baseTx) tx - baseTx else snapshot.sessionTx

        var downRate = 0L
        var upRate = 0L
        if (!baselineSeeded) {
            lastRx = rx
            lastTx = tx
            lastSampleAt = now
            baselineSeeded = true
        } else {
            val elapsed = (now - lastSampleAt).coerceAtLeast(1L)
            downRate = (rx - lastRx).coerceAtLeast(0L) * 1000 / elapsed
            upRate = (tx - lastTx).coerceAtLeast(0L) * 1000 / elapsed
            lastRx = rx
            lastTx = tx
            lastSampleAt = now
        }

        return Reading(sessionRx, sessionTx, downRate, upRate)
    }

    companion object {
        fun bytes(value: Long): String = when {
            value < 1024 -> "$value B"
            value < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", value / 1024.0)
            else -> String.format(Locale.US, "%.2f MB", value / (1024.0 * 1024.0))
        }
    }
}
