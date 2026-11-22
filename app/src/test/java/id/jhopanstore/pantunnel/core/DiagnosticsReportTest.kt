package id.jhopanstore.pantunnel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diagnostic report is meant to be pasted into a support chat, so it must never leak the
 * account: the UUID is truncated and the full vless:// link is absent.
 */
class DiagnosticsReportTest {

    private val uuid = "a4bd481b-335c-41c9-b876-d56ed17f5cbd"

    @Test
    fun `redact keeps a short prefix and suffix only`() {
        val redacted = DiagnosticsReport.redactUuid(uuid)
        assertEquals("a4bd481b…5cbd", redacted)
        assertFalse(redacted.contains("335c-41c9"))
    }

    @Test
    fun `redact handles short and empty values`() {
        assertEquals("****", DiagnosticsReport.redactUuid(""))
        assertEquals("****", DiagnosticsReport.redactUuid("short"))
    }

    @Test
    fun `redacted uuid cannot be used to rebuild the link`() {
        val redacted = DiagnosticsReport.redactUuid(uuid)
        val rebuilt = "vless://$redacted@example.com:443"
        assertTrue(runCatching { VlessParser.parse(rebuilt) }.isFailure)
    }

    @Test
    fun `redact never returns the original value for a real uuid`() {
        assertFalse(DiagnosticsReport.redactUuid(uuid) == uuid)
    }
}
