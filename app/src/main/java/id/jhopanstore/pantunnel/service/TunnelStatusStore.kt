package id.jhopanstore.pantunnel.service

import android.content.Context
import android.content.SharedPreferences
import id.jhopanstore.pantunnel.core.TunnelState

/**
 * Small, observable status board shared between the VPN service and the UI.
 *
 * The service is the only writer; activities read (and re-read on resume). Keeping it in
 * SharedPreferences means a freshly launched activity can render the true state even when the
 * service started in another process lifetime.
 */
class TunnelStatusStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Snapshot(
        val state: TunnelState,
        val detail: String,
        val profileName: String,
        val lastSeen: Long,
        val sessionRx: Long,
        val sessionTx: Long,
        val meterBaseRx: Long,
        val meterBaseTx: Long,
        val logLines: List<String>,
    ) {
        /** True when the service heartbeated recently; guards against a killed process. */
        fun isAlive(now: Long = System.currentTimeMillis()): Boolean =
            state == TunnelState.Connected && now - lastSeen < STALE_AFTER_MS

        companion object {
            const val STALE_AFTER_MS = 35_000L
        }
    }

    fun snapshot(): Snapshot {
        val state = runCatching {
            TunnelState.valueOf(prefs.getString(KEY_STATE, null) ?: TunnelState.Idle.name)
        }.getOrDefault(TunnelState.Idle)
        val log = prefs.getString(KEY_LOG, null)
            ?.lineSequence()?.filter { it.isNotBlank() }?.toList().orEmpty()
        return Snapshot(
            state = state,
            detail = prefs.getString(KEY_DETAIL, "").orEmpty(),
            profileName = prefs.getString(KEY_PROFILE_NAME, "").orEmpty(),
            lastSeen = prefs.getLong(KEY_LAST_SEEN, 0L),
            sessionRx = prefs.getLong(KEY_SESSION_RX, 0L),
            sessionTx = prefs.getLong(KEY_SESSION_TX, 0L),
            meterBaseRx = prefs.getLong(KEY_METER_BASE_RX, -1L),
            meterBaseTx = prefs.getLong(KEY_METER_BASE_TX, -1L),
            logLines = log,
        )
    }

    fun writeState(state: TunnelState, detail: String = "", profileName: String? = null) {
        val editor = prefs.edit()
            .putString(KEY_STATE, state.name)
            .putString(KEY_DETAIL, detail)
            .putLong(KEY_LAST_SEEN, System.currentTimeMillis())
        if (profileName != null) editor.putString(KEY_PROFILE_NAME, profileName)
        editor.apply()
    }

    fun heartbeat() {
        prefs.edit().putLong(KEY_LAST_SEEN, System.currentTimeMillis()).apply()
    }

    fun writeLog(lines: List<String>) {
        prefs.edit().putString(KEY_LOG, lines.joinToString("\n")).apply()
    }

    fun writeMeter(sessionRx: Long, sessionTx: Long, baseRx: Long, baseTx: Long) {
        prefs.edit()
            .putLong(KEY_SESSION_RX, sessionRx)
            .putLong(KEY_SESSION_TX, sessionTx)
            .putLong(KEY_METER_BASE_RX, baseRx)
            .putLong(KEY_METER_BASE_TX, baseTx)
            .apply()
    }

    fun clearSession() {
        prefs.edit()
            .putString(KEY_STATE, TunnelState.Idle.name)
            .putString(KEY_DETAIL, "")
            .putString(KEY_LOG, "")
            .putLong(KEY_LAST_SEEN, 0L)
            .putLong(KEY_SESSION_RX, 0L)
            .putLong(KEY_SESSION_TX, 0L)
            .putLong(KEY_METER_BASE_RX, -1L)
            .putLong(KEY_METER_BASE_TX, -1L)
            .apply()
    }

    companion object {
        const val PREFS = "pantunnel_status"
        private const val KEY_STATE = "state"
        private const val KEY_DETAIL = "detail"
        private const val KEY_PROFILE_NAME = "profile_name"
        private const val KEY_LAST_SEEN = "last_seen"
        private const val KEY_SESSION_RX = "session_rx"
        private const val KEY_SESSION_TX = "session_tx"
        private const val KEY_METER_BASE_RX = "meter_base_rx"
        private const val KEY_METER_BASE_TX = "meter_base_tx"
        private const val KEY_LOG = "log"
    }
}
