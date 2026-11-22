package id.jhopanstore.pantunnel.core

/**
 * Decides what to do after a health probe fails.
 *
 * Extracted from the service because the previous inline version had a bug that made
 * `PROBE_FAIL_LIMIT` unreachable: it incremented the counter and then reset it to 0 in the same
 * `else` branch, so a single failed probe killed the tunnel immediately. Users saw the VPN drop
 * roughly every probe interval (~30 s).
 */
enum class ProbeVerdict {
    /** Stay connected; another failure is needed before acting. */
    KEEP_RETRYING,

    /** Reconnect the tunnel. */
    RECONNECT,

    /** Give up and report a real failure. */
    GIVE_UP,
}

data class ProbeDecision(
    val verdict: ProbeVerdict,
    /** Consecutive failures after this one. */
    val failedProbes: Int,
    /** Auto-reconnects already used. */
    val autoReconnects: Int,
    /** True when the tunnel should be treated as unstable again. */
    val markUnstable: Boolean,
)

object ProbePolicy {

    /**
     * @param failedProbes   consecutive failures *before* this one
     * @param autoReconnects reconnects already performed in this session
     * @param limit          failures needed before acting
     * @param maxReconnects  reconnect budget
     */
    fun onFailure(
        failedProbes: Int,
        autoReconnects: Int,
        limit: Int,
        maxReconnects: Int,
    ): ProbeDecision {
        val consecutive = failedProbes + 1
        return when {
            consecutive < limit -> ProbeDecision(
                verdict = ProbeVerdict.KEEP_RETRYING,
                failedProbes = consecutive,
                autoReconnects = autoReconnects,
                markUnstable = true,
            )

            autoReconnects < maxReconnects -> ProbeDecision(
                verdict = ProbeVerdict.RECONNECT,
                failedProbes = 0,
                autoReconnects = autoReconnects + 1,
                markUnstable = true,
            )

            else -> ProbeDecision(
                verdict = ProbeVerdict.GIVE_UP,
                failedProbes = 0,
                autoReconnects = autoReconnects,
                markUnstable = true,
            )
        }
    }

    /** A successful probe always clears both counters. */
    fun onSuccess(): ProbeDecision = ProbeDecision(
        verdict = ProbeVerdict.KEEP_RETRYING,
        failedProbes = 0,
        autoReconnects = 0,
        markUnstable = false,
    )
}
