package id.jhopanstore.pantunnel.core

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/**
 * `vless://` URI <-> [VlessConfig].
 *
 * Deliberately free of Android APIs so the whole import/export path is unit-testable on the JVM.
 */
object VlessParser {

    private const val SCHEME = "vless://"
    private const val FRAGMENT_TAG = "VpnService"

    class InvalidConfig(message: String) : IllegalArgumentException(message)

    fun parse(raw: String?): VlessConfig {
        val value = raw?.trim().orEmpty()
        if (!value.startsWith(SCHEME, ignoreCase = true)) {
            throw InvalidConfig("Link must start with vless://")
        }

        val withoutScheme = value.substring(SCHEME.length)
        val withoutFragment = withoutScheme.substringBefore('#')
        val authority = withoutFragment.substringBefore('?')
        val query = if (withoutFragment.contains('?')) withoutFragment.substringAfter('?') else ""

        val at = authority.lastIndexOf('@')
        if (at <= 0) throw InvalidConfig("Invalid VLESS address")
        val uuid = decode(authority.substring(0, at))
        val hostPort = authority.substring(at + 1)

        val (address, port) = splitHostPort(hostPort)
        if (address.isEmpty() || port !in 1..65535) throw InvalidConfig("Invalid VLESS address")
        runCatching { UUID.fromString(uuid) }.onFailure { throw InvalidConfig("Invalid UUID") }

        val params = parseQuery(query)
        val type = params["type"].orDefault("ws")
        val security = params["security"].orDefault("tls")
        if (!type.equals("ws", true) || !security.equals("tls", true)) {
            throw InvalidConfig("Only VLESS WebSocket TLS is supported")
        }

        val sni = params["sni"].orDefault(address)
        if (sni.startsWith("onering:", ignoreCase = true)) {
            throw InvalidConfig("OneRing SNI is not supported")
        }

        return VlessConfig(
            address = address,
            port = port,
            uuid = uuid,
            path = params["path"].orDefault("/"),
            sni = sni,
            host = params["host"].orDefault(sni),
            allowInsecure = params["allowInsecure"].orDefault("true").toBoolean(),
        )
    }

    fun export(config: VlessConfig): String = buildString {
        append(SCHEME)
        append(config.uuid)
        append('@')
        append(config.address)
        append(':')
        append(config.port)
        append("?type=ws&security=tls&path=")
        append(encode(config.path))
        append("&sni=")
        append(encode(config.sni))
        append("&host=")
        append(encode(config.host))
        append("&allowInsecure=")
        append(config.allowInsecure)
        append('#')
        append(FRAGMENT_TAG)
    }

    /** True when [raw] is a complete, importable link (used to gate the CONNECT button). */
    fun isValid(raw: String?): Boolean = runCatching { parse(raw) }.isSuccess

    /** "host:port", "[::1]:443" or bare "host" -> (host, port). */
    fun splitHostPort(hostPort: String): Pair<String, Int> {
        val value = hostPort.trim()
        if (value.isEmpty()) return "" to VlessConfig.DEFAULT_PORT

        if (value.startsWith("[")) { // IPv6 literal
            val close = value.indexOf(']')
            if (close < 0) return value to VlessConfig.DEFAULT_PORT
            val host = value.substring(1, close)
            val port = value.substring(close + 1).removePrefix(":").toPortOrNull()
            return host to (port ?: VlessConfig.DEFAULT_PORT)
        }

        val colon = value.lastIndexOf(':')
        if (colon <= 0) return value to VlessConfig.DEFAULT_PORT
        // "host:not-a-port" is a hostname, not host + port — keep it whole.
        val port = value.substring(colon + 1).toPortOrNull()
            ?: return value to VlessConfig.DEFAULT_PORT
        return value.substring(0, colon) to port
    }

    private fun String.toPortOrNull(): Int? = toIntOrNull()?.takeIf { it in 1..65535 }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = decode(pair.substringBefore('='))
            val value = decode(pair.substringAfter('=', ""))
            if (key.isNotEmpty() && value.isNotEmpty()) out[key] = value
        }
        return out
    }

    private fun String?.orDefault(fallback: String): String =
        if (this.isNullOrEmpty()) fallback else this

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(value)

    private fun encode(value: String): String = runCatching {
        URLEncoder.encode(value, "UTF-8")
    }.getOrDefault(value)
}
