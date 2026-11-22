package id.jhopanstore.pantunnel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTunnelTest {

    private val self = "id.jhopanstore.pantunnel"

    @Test
    fun `inactive unless enabled and non-empty`() {
        assertFalse(SplitTunnel().isActive)
        assertFalse(SplitTunnel(enabled = true).isActive)
        assertFalse(SplitTunnel(packages = setOf("com.x")).isActive)
        assertTrue(SplitTunnel(enabled = true, packages = setOf("com.x")).isActive)
    }

    @Test
    fun `toggled adds then removes`() {
        val once = SplitTunnel().toggled("com.whatsapp")
        assertTrue("com.whatsapp" in once.packages)
        val twice = once.toggled("com.whatsapp")
        assertFalse("com.whatsapp" in twice.packages)
    }

    @Test
    fun `sanitized drops our own package`() {
        val split = SplitTunnel(enabled = true, packages = setOf(self, "com.whatsapp"))
            .sanitized(self)
        assertFalse(self in split.packages)
        assertTrue("com.whatsapp" in split.packages)
    }

    @Test
    fun `sanitized drops system packages that must never be tunnelled`() {
        val split = SplitTunnel(
            enabled = true,
            packages = setOf("android", "com.android.systemui", "com.android.settings", "com.bank"),
        ).sanitized(self)
        assertEquals(setOf("com.bank"), split.packages)
    }

    @Test
    fun `sanitized keeps allowMode and enabled flags`() {
        val split = SplitTunnel(enabled = true, allowMode = false, packages = setOf(self, "com.x"))
            .sanitized(self)
        assertTrue(split.enabled)
        assertFalse(split.allowMode)
        assertTrue(split.isActive)
    }

    @Test
    fun `without removes a single package`() {
        val split = SplitTunnel(packages = setOf("a", "b")).without("a")
        assertEquals(setOf("b"), split.packages)
    }

    @Test
    fun `sanitized result with only our package becomes inactive`() {
        val split = SplitTunnel(enabled = true, packages = setOf(self)).sanitized(self)
        assertFalse(split.isActive)
    }
}
