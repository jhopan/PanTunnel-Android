package id.jhopanstore.pantunnel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the split-tunnel package sets.
 *
 * The bug this guards: `VpnService.Builder` throws `UnsupportedOperationException` when
 * `addAllowedApplication` is called after `addDisallowedApplication`. The old code always called
 * `addDisallowedApplication(self)` first, so allowlist mode silently applied nothing and every
 * app stayed tunnelled while the UI claimed otherwise.
 */
class VpnAppRulesTest {

    private val self = "id.jhopanstore.pantunnel"

    // ---------------------------------------------------------------- exclusivity

    @Test
    fun `allow and disallow are never both non-empty`() {
        val cases = listOf(
            SplitTunnel(),
            SplitTunnel(enabled = true, allowMode = true, packages = setOf("com.game")),
            SplitTunnel(enabled = true, allowMode = false, packages = setOf("com.game")),
            SplitTunnel(enabled = false, packages = setOf("com.game")),
        )
        for (split in cases) {
            val rules = VpnAppRules.forSplit(split, self)
            assertTrue("both lists filled for $split", rules.isValid)
        }
    }

    // ---------------------------------------------------------------- no split

    @Test
    fun `without split everything is tunnelled except ourselves`() {
        val rules = VpnAppRules.forSplit(SplitTunnel(), self)
        assertTrue("allowlist must stay empty", rules.allowed.isEmpty())
        assertEquals(setOf(self), rules.disallowed)
    }

    @Test
    fun `disabled split still keeps self out of the tunnel`() {
        val rules = VpnAppRules.forSplit(
            SplitTunnel(enabled = false, packages = setOf("com.game")),
            self,
        )
        assertTrue(rules.allowed.isEmpty())
        assertTrue(self in rules.disallowed)
        assertFalse("user packages ignored while disabled", "com.game" in rules.disallowed)
    }

    // ---------------------------------------------------------------- blocklist (game bypasses VPN)

    @Test
    fun `blocklist excludes the chosen apps and keeps self excluded`() {
        val rules = VpnAppRules.forSplit(
            SplitTunnel(enabled = true, allowMode = false, packages = setOf("com.game")),
            self,
        )
        assertTrue("allowlist must stay empty", rules.allowed.isEmpty())
        assertTrue("chosen app must bypass", "com.game" in rules.disallowed)
        assertTrue("self must bypass for probe", self in rules.disallowed)
    }

    @Test
    fun `blocklist never includes system apps the user cannot control`() {
        val rules = VpnAppRules.forSplit(
            SplitTunnel(
                enabled = true,
                allowMode = false,
                packages = setOf("com.game", "com.android.systemui"),
            ),
            self,
        )
        assertFalse("com.android.systemui" in rules.disallowed)
    }

    // ---------------------------------------------------------------- allowlist

    @Test
    fun `allowlist keeps our own package out so the core dial cannot loop`() {
        val rules = VpnAppRules.forSplit(
            SplitTunnel(enabled = true, allowMode = true, packages = setOf("com.game")),
            self,
        )
        assertTrue("disallowlist must stay empty", rules.disallowed.isEmpty())
        assertTrue("chosen app must be tunnelled", "com.game" in rules.allowed)
        assertFalse("self must stay outside or the proxy dial re-enters the tun", self in rules.allowed)
    }

    @Test
    fun `allowlist drops system apps that must never be captured`() {
        val rules = VpnAppRules.forSplit(
            SplitTunnel(
                enabled = true,
                allowMode = true,
                packages = setOf("com.game", "android", "com.android.settings"),
            ),
            self,
        )
        assertFalse("android" in rules.allowed)
        assertFalse("com.android.settings" in rules.allowed)
        assertTrue("com.game" in rules.allowed)
    }

    // ---------------------------------------------------------------- edge cases

    @Test
    fun `sanitized removes self from the user selection`() {
        val split = SplitTunnel(enabled = true, packages = setOf(self, "com.game")).sanitized(self)
        assertFalse(self in split.packages)
        assertTrue("com.game" in split.packages)
    }

    @Test
    fun `empty selection is treated as no split`() {
        val rules = VpnAppRules.forSplit(SplitTunnel(enabled = true, packages = emptySet()), self)
        assertTrue(rules.allowed.isEmpty())
        assertEquals(setOf(self), rules.disallowed)
    }

    @Test
    fun `rules remain valid for a realistic mixed selection`() {
        val split = SplitTunnel(
            enabled = true,
            allowMode = true,
            packages = setOf("com.whatsapp", "com.game", "android"),
        )
        val rules = VpnAppRules.forSplit(split, self)
        assertTrue(rules.isValid)
        assertEquals(setOf("com.whatsapp", "com.game"), rules.allowed)
    }
}
