package id.jhopanstore.pantunnel.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingboxConfigTest {

    private val config = VlessConfig(
        address = "1.2.3.4",
        port = 8443,
        uuid = "11111111-2222-3333-4444-555555555555",
        path = "/ws",
        sni = "cdn.example.com",
        host = "cdn.example.com",
        allowInsecure = false,
    )

    private fun build(verbose: Boolean = false) =
        JSONObject(SingboxConfig.build(config, "/data/cache", verbose))

    @Test
    fun `emits a vless ws tls outbound with the profile values`() {
        val root = build()
        val outbounds = root.getJSONArray("outbounds")
        val proxy = outbounds.getJSONObject(0)

        assertEquals("vless", proxy.getString("type"))
        assertEquals("proxy", proxy.getString("tag"))
        assertEquals("1.2.3.4", proxy.getString("server"))
        assertEquals(8443, proxy.getInt("server_port"))
        assertEquals(config.uuid, proxy.getString("uuid"))

        val tls = proxy.getJSONObject("tls")
        assertTrue(tls.getBoolean("enabled"))
        assertEquals("cdn.example.com", tls.getString("server_name"))
        assertEquals(false, tls.getBoolean("insecure"))

        val transport = proxy.getJSONObject("transport")
        assertEquals("ws", transport.getString("type"))
        assertEquals("/ws", transport.getString("path"))
        assertEquals("cdn.example.com", transport.getJSONObject("headers").getString("Host"))
    }

    @Test
    fun `tun inbound uses gvisor and the expected address`() {
        val tun = build().getJSONArray("inbounds").getJSONObject(0)
        assertEquals("tun", tun.getString("type"))
        assertEquals("gvisor", tun.getString("stack"))
        assertEquals("172.19.0.1/30", tun.getString("inet4_address"))
        assertEquals(1280, tun.getInt("mtu"))
        assertTrue(tun.getBoolean("auto_route"))
    }

    @Test
    fun `mixed inbound listens on the probe port`() {
        val mixed = build().getJSONArray("inbounds").getJSONObject(1)
        assertEquals("mixed", mixed.getString("type"))
        assertEquals("127.0.0.1", mixed.getString("listen"))
        assertEquals(SingboxConfig.PROXY_PORT, mixed.getInt("listen_port"))
    }

    @Test
    fun `route sends everything through the proxy`() {
        val route = build().getJSONObject("route")
        assertEquals("proxy", route.getString("final"))
        assertTrue(route.getBoolean("override_android_vpn"))
    }

    @Test
    fun `log level follows the verbose flag`() {
        assertEquals("warn", build().getJSONObject("log").getString("level"))
        assertEquals("debug", build(verbose = true).getJSONObject("log").getString("level"))
    }

    @Test
    fun `cache file path is under the provided directory`() {
        val cache = build().getJSONObject("experimental").getJSONObject("cache_file")
        assertTrue(cache.getBoolean("enabled"))
        assertEquals("/data/cache/cache.db", cache.getString("path"))
    }
}
