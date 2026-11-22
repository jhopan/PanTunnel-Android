package id.jhopanstore.pantunnel.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the sing-box runtime config for a single VLESS + WS + TLS endpoint.
 *
 * Shape is intentionally identical to the proven JPS Tunnel config: gVisor TUN stack,
 * DNS through the tunnel, a local mixed inbound for health probes, `final: proxy`.
 */
object SingboxConfig {

    const val PROXY_PORT = 10808
    const val TUN_ADDRESS = "172.19.0.1"
    const val TUN_PREFIX = 30
    const val MTU = 1280

    /** gVisor is slower than `system` but stable across Qualcomm/MediaTek/Exynos. */
    const val STACK = "gvisor"

    private const val DNS_PRIMARY = "1.1.1.1"
    private const val DNS_BACKUP = "8.8.8.8"

    /** Private ranges that must never enter the tunnel. */
    private val LAN_CIDRS = listOf(
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "169.254.0.0/16",
    )

    /** sing-box `<domain>_strategy` values; `ipv4_only` also stops AAAA lookups. */
    private object DNSStrategy {
        const val IPV4_ONLY = "ipv4_only"
    }

    /**
     * @param verboseLogging raises the core log level to `debug` so dial/TLS/WS steps reach
     *   the in-app log. Production runs at `warn` so nothing accumulates on disk.
     */
    fun build(config: VlessConfig, cachePath: String, verboseLogging: Boolean = false): String {
        val root = JSONObject()

        root.put("log", JSONObject().put("level", if (verboseLogging) "debug" else "warn"))

        root.put(
            "dns",
            JSONObject()
                .put(
                    "servers",
                    JSONArray()
                        .put(dnsServer("dns-main", DNS_PRIMARY))
                        .put(dnsServer("dns-backup", DNS_BACKUP))
                        .put(JSONObject().put("tag", "dns-local").put("address", "local")),
                )
                .put(
                    "rules",
                    JSONArray().put(
                        JSONObject().put("outbound", "direct").put("server", "dns-local"),
                    ),
                ),
        )

        root.put(
            "inbounds",
            JSONArray()
                .put(
                    JSONObject()
                        .put("type", "tun")
                        .put("tag", "tun-in")
                        .put("inet4_address", "$TUN_ADDRESS/$TUN_PREFIX")
                        .put("mtu", MTU)
                        .put("auto_route", true)
                        .put("strict_route", false)
                        .put("sniff", true)
                        .put("sniff_override_destination", false)
                        .put("stack", STACK),
                )
                .put(
                    JSONObject()
                        .put("type", "mixed")
                        .put("tag", "mixed-in")
                        .put("listen", "127.0.0.1")
                        .put("listen_port", PROXY_PORT),
                ),
        )

        val tls = JSONObject()
            .put("enabled", true)
            .put("server_name", config.sni)
            .put("insecure", config.allowInsecure)

        val ws = JSONObject()
            .put("type", "ws")
            .put("path", config.path)
            .put("headers", JSONObject().put("Host", config.host))

        root.put(
            "outbounds",
            JSONArray()
                .put(
                    JSONObject()
                        .put("type", "vless")
                        .put("tag", "proxy")
                        .put("server", config.address)
                        .put("server_port", config.port)
                        .put("uuid", config.uuid)
                        .put("domain_strategy", DNSStrategy.IPV4_ONLY)
                        // Explicit, shorter than the 15 s default: fail fast so the probe and the
                        // core agree, instead of the probe timing out while the dial still hangs.
                        .put("connect_timeout", "8s")
                        .put("tls", tls)
                        .put("transport", ws),
                )
                .put(JSONObject().put("type", "direct").put("tag", "direct"))
                .put(JSONObject().put("type", "block").put("tag", "block"))
                .put(JSONObject().put("type", "dns").put("tag", "dns-out")),
        )

        root.put(
            "route",
            JSONObject()
                // Left off on purpose: it is a Linux/Windows/macOS option, and on Android it also
                // pairs with override_android_vpn to bind dials to the VPN NIC, which loops when
                // our own UID is inside the tunnel (allowlist split tunnel). See VpnAppRules: our
                // package is kept outside the VPN in both split modes instead.
                .put("auto_detect_interface", false)
                .put("override_android_vpn", true)
                .put(
                    "rules",
                    JSONArray()
                        .put(JSONObject().put("protocol", "dns").put("outbound", "dns-out"))
                        // RFC1918 + link-local stay on the physical interface. Without this the
                        // router admin page, printers, Chromecast and IP cameras are sent to the
                        // VLESS server and simply time out while the VPN is up.
                        .put(
                            JSONObject()
                                .put("ip_cidr", JSONArray(LAN_CIDRS.toList()))
                                .put("outbound", "direct"),
                        ),
                )
                .put("final", "proxy"),
        )

        root.put(
            "experimental",
            JSONObject().put(
                "cache_file",
                JSONObject().put("enabled", true).put("path", "$cachePath/cache.db"),
            ),
        )

        return root.toString()
    }

    /**
     * [DNSStrategy.IPV4_ONLY] is deliberate: it stops AAAA records from being returned at all.
     * With `prefer_ipv4` the resolver still answers AAAA, apps happily open IPv6 connections, and
     * because the TUN only claims `0.0.0.0/0` those connections bypass the tunnel (leak) or hang
     * until the fallback timer expires. Answering A only keeps every flow inside the tunnel.
     */
    private fun dnsServer(tag: String, address: String): JSONObject = JSONObject()
        .put("tag", tag)
        .put("address", address)
        .put("strategy", DNSStrategy.IPV4_ONLY)
}
