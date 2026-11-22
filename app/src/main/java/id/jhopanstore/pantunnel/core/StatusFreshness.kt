package id.jhopanstore.pantunnel.core

/**
 * Decides whether a persisted tunnel status still reflects a live service.
 *
 * The service heartbeats while it runs. If the phone dies, is force-stopped, or MIUI kills the
 * service, the last written status survives in SharedPreferences — and a freshly opened activity
 * would happily show "Connected" for a tunnel that no longer exists.
 */
object StatusFreshness {

    /** The service heartbeats every 15 s; three missed beats means it is gone. */
    const val STALE_AFTER_MS = 35_000L

    /**
     * True when [lastSeen] is recent enough to trust [state].
     *
     * Every active state counts — not only [TunnelState.Connected]. A phone that died mid-connect
     * left "Menghubungkan..." persisted, and since the connect button is the only escape hatch,
     * a stale busy state trapped the user with no way to clear it.
     */
    fun isAlive(state: TunnelState, lastSeen: Long, now: Long): Boolean =
        state.isActive && now - lastSeen < STALE_AFTER_MS

    /** True when the stored status describes a service that is no longer running. */
    fun isStale(state: TunnelState, lastSeen: Long, now: Long): Boolean =
        state.isActive && !isAlive(state, lastSeen, now)
}