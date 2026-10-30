package id.jhopanstore.pantunnel.core

/**
 * Connection lifecycle. The UI never guesses: it only renders what the service reports, and
 * [Connected] is only emitted after a real HTTP 204 came back *through* the tunnel.
 */
enum class TunnelState {
    Idle,
    Connecting,
    Checking,
    Connected,
    Reconnecting,
    Failed,
    ;

    val isBusy: Boolean get() = this == Connecting || this == Checking || this == Reconnecting
    val isActive: Boolean get() = this == Connected || isBusy
}

/** Human-readable diagnosis for the failures users actually hit. */
object TunnelDiagnostics {

    fun fromThrowable(error: Throwable): String {
        val message = error.message.orEmpty().lowercase()
        return when {
            error is java.net.UnknownHostException ->
                "DNS gagal — domain server salah atau DNS mati."
            message.contains("tls") || message.contains("certificate") ->
                "TLS gagal — SNI salah atau sertifikat tidak cocok."
            message.contains("websocket") || message.contains("ws ") ->
                "WebSocket gagal — path atau Host header salah."
            message.contains("refused") ->
                "Connection refused — server down atau port tertutup."
            message.contains("timeout") || message.contains("timed out") ->
                "Timeout — server tidak merespons atau jaringan lambat."
            message.contains("reset") || message.contains("broken pipe") ->
                "Connection reset — server/CDN menutup koneksi paksa."
            else -> "${error.javaClass.simpleName} — cek server, port, path, SNI, Host."
        }
    }

    /** Translate a probe HTTP status into something actionable. */
    fun fromHttpStatus(code: Int): String = when (code) {
        400 -> "HTTP 400 — format request salah, cek path/Host."
        401 -> "HTTP 401 — UUID salah, server tidak mengenali."
        403 -> "HTTP 403 — Cloudflare/CDN memblokir akses."
        404 -> "HTTP 404 — path salah, endpoint tidak dikenal."
        408 -> "HTTP 408 — server lambat merespons."
        429 -> "HTTP 429 — kena rate limit, tunggu beberapa menit."
        500 -> "HTTP 500 — server crash."
        502 -> "HTTP 502 — server di belakang proxy mati."
        503 -> "HTTP 503 — server overload / maintenance."
        504 -> "HTTP 504 — gateway timeout."
        else -> when {
            code >= 500 -> "HTTP $code — server error."
            code >= 400 -> "HTTP $code — config salah, cek profil."
            else -> "HTTP $code — coba reconnect."
        }
    }

    /** Friendly text for parser rejections, so the UI never shows a raw exception message. */
    fun fromConfigError(message: String?): String {
        val text = message.orEmpty()
        return when {
            text.contains("Invalid VLESS address") -> "Config tidak lengkap: address atau UUID kosong/salah."
            text.contains("Invalid UUID") -> "UUID tidak valid."
            text.contains("Link must start") -> "Config harus diawali vless://"
            text.contains("Only VLESS") -> "Hanya VLESS + WebSocket + TLS yang didukung."
            text.contains("OneRing") -> "SNI OneRing tidak didukung."
            text.isBlank() -> "Config tidak valid."
            else -> text
        }
    }
}
