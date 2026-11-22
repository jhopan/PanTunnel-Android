package id.jhopanstore.pantunnel.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards against surfacing a coroutine cancellation as a profile problem.
 *
 * A DISCONNECT or service teardown cancels the connect coroutine; the catch block used to feed
 * that straight into the mapper and the user saw "JobCancellationException — cek server, port,
 * path, SNI, Host." for a perfectly valid profile.
 */
class CancellationDiagnosticsTest {

    /** Captures the real exception a cancelled coroutine throws (a JobCancellationException). */
    private fun cancellationFromCancelledCoroutine(): Throwable {
        var captured: Throwable? = null
        runBlocking {
            // UNDISPATCHED runs the body up to the first suspension before launch() returns, so
            // delay() is definitely registered when cancel() lands.
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    delay(60_000)
                } catch (error: Throwable) {
                    captured = error
                    throw error
                }
            }
            job.cancel()
            job.join()
        }
        return captured ?: throw AssertionError("coroutine was not cancelled")
    }

    @Test
    fun `a cancelled coroutine yields a CancellationException`() {
        assertTrue(
            cancellationFromCancelledCoroutine() is java.util.concurrent.CancellationException,
        )
    }

    @Test
    fun `job cancellation is not reported as a config error`() {
        val message = TunnelDiagnostics.fromThrowable(cancellationFromCancelledCoroutine())
        assertEquals("Koneksi dibatalkan.", message)
        assertFalse(message.contains("Cancellation"))
        assertFalse(message.contains("cek server"))
    }

    @Test
    fun `plain jdk cancellation is handled too`() {
        val message = TunnelDiagnostics.fromThrowable(
            java.util.concurrent.CancellationException("cancelled"),
        )
        assertEquals("Koneksi dibatalkan.", message)
    }

    @Test
    fun `real config errors still map to their own message`() {
        assertEquals(
            "DNS gagal — domain server salah atau DNS mati.",
            TunnelDiagnostics.fromThrowable(java.net.UnknownHostException("nope")),
        )
        assertEquals(
            "Connection refused — server down atau port tertutup.",
            TunnelDiagnostics.fromThrowable(java.net.ConnectException("connection refused")),
        )
        assertEquals(
            "Timeout — server tidak merespons atau jaringan lambat.",
            TunnelDiagnostics.fromThrowable(java.net.SocketTimeoutException("read timed out")),
        )
    }
}
