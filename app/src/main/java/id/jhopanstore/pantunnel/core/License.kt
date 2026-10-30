package id.jhopanstore.pantunnel.core

/**
 * Offline license payload embedded in a `.pantunnel` file.
 *
 * The seller (this same app, "Export Locked Config") encrypts the config with a key derived
 * from the buyer's HWID, so only that device can decrypt it. No server, no remote revocation.
 */
data class License(
    val vless: String,
    val name: String = "",
    val hwid: String = "",
    val lock: Boolean = true,
    val expiry: Long = 0L,
) {
    val isExpired: Boolean
        get() = expiry > 0 && System.currentTimeMillis() > expiry
}
