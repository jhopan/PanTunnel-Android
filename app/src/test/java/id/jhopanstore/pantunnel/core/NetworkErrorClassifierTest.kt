package id.jhopanstore.pantunnel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkErrorClassifierTest {

    @Test
    fun `read tcp on an established connection is transient`() {
        val line = "connection: open outbound connection: read tcp 192.168.43.217:49338->170.114.45.2:443: read: connection reset by peer"
        assertEquals(ErrorKind.TRANSIENT, NetworkErrorClassifier.classify(line))
    }

    @Test
    fun `bare read tcp is transient even without reset wording`() {
        // the exact shape seen in the user's report, truncated
        val line = "connection: open outbound connection: read tcp 192.168.43.217:49338->170.114.45"
        assertEquals(ErrorKind.TRANSIENT, NetworkErrorClassifier.classify(line))
    }

    @Test
    fun `broken pipe and eof are transient`() {
        assertEquals(ErrorKind.TRANSIENT, NetworkErrorClassifier.classify("write tcp: broken pipe"))
        assertEquals(ErrorKind.TRANSIENT, NetworkErrorClassifier.classify("unexpected EOF"))
    }

    @Test
    fun `connection refused is fatal`() {
        assertEquals(ErrorKind.FATAL, NetworkErrorClassifier.classify("dial tcp: connection refused"))
    }

    @Test
    fun `tls handshake failure is fatal`() {
        assertEquals(ErrorKind.FATAL, NetworkErrorClassifier.classify("tls handshake failed: certificate"))
    }

    @Test
    fun `authentication failure is fatal`() {
        assertEquals(ErrorKind.FATAL, NetworkErrorClassifier.classify("authentication failed: invalid uuid"))
    }

    @Test
    fun `no route to host is fatal`() {
        assertEquals(ErrorKind.FATAL, NetworkErrorClassifier.classify("no route to host"))
    }

    @Test
    fun `dial timeout while connecting is ignored`() {
        val line = "dial tcp: i/o timeout"
        assertEquals(ErrorKind.IGNORE, NetworkErrorClassifier.classify(line, connecting = true))
    }

    @Test
    fun `dial timeout after connecting is transient`() {
        val line = "dial tcp: i/o timeout"
        assertEquals(ErrorKind.TRANSIENT, NetworkErrorClassifier.classify(line, connecting = false))
    }

    @Test
    fun `plain info lines are ignored`() {
        assertEquals(ErrorKind.IGNORE, NetworkErrorClassifier.classify("inbound/mixed: started at 127.0.0.1:10808"))
    }

    @Test
    fun `labels are short and indonesian`() {
        assertEquals("sementara", NetworkErrorClassifier.label(ErrorKind.TRANSIENT))
        assertEquals("fatal", NetworkErrorClassifier.label(ErrorKind.FATAL))
        assertEquals("", NetworkErrorClassifier.label(ErrorKind.IGNORE))
    }
}
