package id.jhopanstore.pantunnel.core

/**
 * A single VLESS-over-WebSocket-TLS endpoint.
 *
 * PanTunnel intentionally supports exactly one transport. Everything else (gRPC, HTTP/2,
 * Reality, OneRing SNI) is rejected at parse time so the tunnel never silently half-works.
 */
data class VlessConfig(
    val address: String,
    val port: Int = DEFAULT_PORT,
    val uuid: String,
    val path: String = "/",
    val sni: String,
    val host: String,
    val allowInsecure: Boolean = true,
) {
    /** "host:port" — the shape the profile editor edits. */
    val hostPort: String get() = "$address:$port"

    companion object {
        const val DEFAULT_PORT = 443
    }
}
