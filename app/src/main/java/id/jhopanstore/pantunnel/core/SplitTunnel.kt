package id.jhopanstore.pantunnel.core

/**
 * Which apps go through the tunnel.
 *
 * Implemented at the **VpnService level** (`addAllowedApplication` / `addDisallowedApplication`),
 * so an excluded app's packets never enter the TUN device at all. That is cheaper than routing
 * them through a sing-box `direct` outbound, which would still pay for gVisor + userspace copying.
 */
data class SplitTunnel(
    val enabled: Boolean = false,
    /** true = only [packages] are tunnelled (allowlist); false = [packages] are excluded (blocklist). */
    val allowMode: Boolean = true,
    val packages: Set<String> = emptySet(),
) {
    /** True when a package list should actually be applied to the TUN builder. */
    val isActive: Boolean get() = enabled && packages.isNotEmpty()

    fun toggled(pkg: String): SplitTunnel {
        val next = packages.toMutableSet()
        if (!next.add(pkg)) next.remove(pkg)
        return copy(packages = next)
    }

    fun without(pkg: String): SplitTunnel = copy(packages = packages - pkg)

    /**
     * Packages that must never be tunnelled even when the user picks allowlist mode:
     * our own app (so the probe can reach the proxy) and the system UI.
     */
    fun sanitized(selfPackage: String): SplitTunnel = copy(
        packages = packages - selfPackage - ALWAYS_EXCLUDED,
    )

    companion object {
        val ALWAYS_EXCLUDED = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
        )
    }
}

/**
 * The exact package sets to hand to `VpnService.Builder`.
 *
 * Kept separate from [SplitTunnel] and free of Android APIs so the exclusivity rule can be
 * unit-tested. The previous implementation called `addDisallowedApplication` unconditionally and
 * then `addAllowedApplication`, which always threw `UnsupportedOperationException`; the exception
 * was swallowed and every app stayed tunnelled while the UI claimed otherwise.
 */
data class VpnAppRules(
    val allowed: Set<String>,
    val disallowed: Set<String>,
) {
    /** Never true at the same time — Android forbids mixing the two lists. */
    val isValid: Boolean get() = allowed.isEmpty() || disallowed.isEmpty()

    companion object {
        /**
         * @param split       user selection
         * @param selfPackage our own package. It stays outside the tunnel in **both** modes: the
         *   health probe talks to the core over 127.0.0.1, which never enters the tun, while an
         *   included package would pull the core's own dial to the server into the tunnel and it
         *   would re-enter the proxy in a loop (observed as `dial tcp ... i/o timeout` flood).
         */
        fun forSplit(split: SplitTunnel, selfPackage: String): VpnAppRules {
            if (!split.isActive) {
                // No split tunnel: everything tunnelled, except ourselves so probes keep working.
                return VpnAppRules(allowed = emptySet(), disallowed = setOf(selfPackage))
            }
            return if (split.allowMode) {
                VpnAppRules(
                    allowed = split.packages - SplitTunnel.ALWAYS_EXCLUDED - selfPackage,
                    disallowed = emptySet(),
                )
            } else {
                VpnAppRules(
                    allowed = emptySet(),
                    disallowed = split.packages - SplitTunnel.ALWAYS_EXCLUDED + selfPackage,
                )
            }
        }
    }
}
