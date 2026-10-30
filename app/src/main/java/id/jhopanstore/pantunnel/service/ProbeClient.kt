package id.jhopanstore.pantunnel.service

import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL

/**
 * HTTP probes issued **through the local sing-box mixed inbound** (127.0.0.1:10808).
 *
 * This is the only honest way to know the tunnel works: if the request reaches the internet
 * with a 204, real traffic is flowing through VLESS -> server. A started process proves nothing.
 */
class ProbeClient(
    private val proxyPort: Int,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) {

    sealed interface Result {
        data class Success(val code: Int, val phrase: String, val elapsedMs: Long) : Result
        data class Failure(val code: Int, val reason: String, val elapsedMs: Long) : Result
    }

    /** HEAD request used for the connection gate; 204/200/202 count as healthy. */
    fun check(url: String): Result = request(url, method = "HEAD", acceptStatus = { it == 204 || it == 200 || it == 202 })

    /** GET used for the keep-alive ping; any 2xx/3xx counts as alive. */
    fun ping(url: String): Result = request(url, method = "GET", acceptStatus = { it in 200..399 })

    private fun request(
        url: String,
        method: String,
        acceptStatus: (Int) -> Boolean,
    ): Result {
        val started = System.currentTimeMillis()
        var connection: HttpURLConnection? = null
        return try {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))
            connection = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                useCaches = false
            }
            val code = connection.responseCode
            val phrase = connection.responseMessage.orEmpty()
            val elapsed = System.currentTimeMillis() - started
            if (acceptStatus(code)) {
                Result.Success(code, phrase, elapsed)
            } else {
                Result.Failure(code, "HTTP $code${if (phrase.isEmpty()) "" else " $phrase"}", elapsed)
            }
        } catch (error: SocketTimeoutException) {
            Result.Failure(-1, "Timeout ${timeoutMs}ms", System.currentTimeMillis() - started)
        } catch (error: IOException) {
            val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
            Result.Failure(-1, message, System.currentTimeMillis() - started)
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000
    }
}
