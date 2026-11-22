package id.jhopanstore.pantunnel.core

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diagnostic strings are the only thing the user sees when a connect fails, so the mapping
 * from exception/HTTP status to a message must stay stable and actionable.
 */
class TunnelDiagnosticsTest {

    // ---------------------------------------------------------------- fromThrowable

    @Test
    fun `unknown host maps to DNS message`() {
        val message = TunnelDiagnostics.fromThrowable(UnknownHostException("nope"))
        assertTrue(message.contains("DNS", ignoreCase = true))
    }

    @Test
    fun `tls errors map to SNI or certificate hint`() {
        val message = TunnelDiagnostics.fromThrowable(IllegalStateException("tls handshake failed"))
        assertTrue(message.contains("TLS", ignoreCase = true))
        assertTrue(message.contains("SNI", ignoreCase = true))
    }

    @Test
    fun `certificate error also maps to tls`() {
        val message = TunnelDiagnostics.fromThrowable(IllegalStateException("bad certificate"))
        assertTrue(message.contains("TLS", ignoreCase = true))
    }

    @Test
    fun `websocket error maps to path or host hint`() {
        val message = TunnelDiagnostics.fromThrowable(IllegalStateException("websocket closed"))
        assertTrue(message.contains("WebSocket", ignoreCase = true))
    }

    @Test
    fun `refused maps to connection refused`() {
        val message = TunnelDiagnostics.fromThrowable(ConnectException("Connection refused"))
        assertTrue(message.contains("refused", ignoreCase = true))
    }

    @Test
    fun `timeout maps to timeout message`() {
        val message = TunnelDiagnostics.fromThrowable(SocketTimeoutException("timed out"))
        assertTrue(message.contains("Timeout", ignoreCase = true))
    }

    @Test
    fun `reset maps to reset message`() {
        val message = TunnelDiagnostics.fromThrowable(IllegalStateException("reset by peer"))
        assertTrue(message.contains("reset", ignoreCase = true))
    }

    @Test
    fun `unknown error still returns something actionable`() {
        val message = TunnelDiagnostics.fromThrowable(IllegalStateException("weird"))
        assertTrue(message.isNotBlank())
        assertTrue(message.contains("cek server", ignoreCase = true))
    }

    @Test
    fun `empty message does not crash`() {
        val message = TunnelDiagnostics.fromThrowable(IllegalStateException())
        assertTrue(message.isNotBlank())
    }

    // ---------------------------------------------------------------- fromHttpStatus

    @Test
    fun `known http codes map to specific hints`() {
        assertTrue(TunnelDiagnostics.fromHttpStatus(401).contains("UUID"))
        assertTrue(TunnelDiagnostics.fromHttpStatus(403).contains("Cloudflare"))
        assertTrue(TunnelDiagnostics.fromHttpStatus(404).contains("path"))
        assertTrue(TunnelDiagnostics.fromHttpStatus(429).contains("rate limit"))
        assertTrue(TunnelDiagnostics.fromHttpStatus(502).contains("proxy"))
        assertTrue(TunnelDiagnostics.fromHttpStatus(503).contains("overload"))
    }

    @Test
    fun `unknown 5xx falls back to server error`() {
        val message = TunnelDiagnostics.fromHttpStatus(599)
        assertTrue(message.contains("server error", ignoreCase = true))
    }

    @Test
    fun `unknown 4xx falls back to config hint`() {
        val message = TunnelDiagnostics.fromHttpStatus(418)
        assertTrue(message.contains("config", ignoreCase = true))
    }

    @Test
    fun `every status produces a non-empty message`() {
        for (code in listOf(400, 401, 403, 404, 408, 429, 500, 502, 503, 504, 599, 418, 301)) {
            assertTrue("code $code produced empty", TunnelDiagnostics.fromHttpStatus(code).isNotBlank())
        }
    }

    // ---------------------------------------------------------------- fromConfigError

    @Test
    fun `config errors are translated to friendly text`() {
        assertTrue(TunnelDiagnostics.fromConfigError("Invalid UUID").contains("UUID"))
        assertTrue(TunnelDiagnostics.fromConfigError("Link must start with vless://").contains("vless://"))
        assertTrue(TunnelDiagnostics.fromConfigError("Only VLESS WebSocket TLS is supported").contains("VLESS"))
        assertTrue(TunnelDiagnostics.fromConfigError("OneRing SNI is not supported").contains("OneRing"))
    }

    @Test
    fun `blank config error returns generic message`() {
        assertEquals("Config tidak valid.", TunnelDiagnostics.fromConfigError(null))
        assertEquals("Config tidak valid.", TunnelDiagnostics.fromConfigError("   "))
    }
}
