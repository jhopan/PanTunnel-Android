package id.jhopanstore.pantunnel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VlessParserTest {

    private val sample =
        "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
            "?type=ws&security=tls&path=%2Fws&sni=cdn.example.com&host=cdn.example.com&allowInsecure=false#VpnService"

    @Test
    fun `parses full link`() {
        val config = VlessParser.parse(sample)
        assertEquals("example.com", config.address)
        assertEquals(443, config.port)
        assertEquals("11111111-2222-3333-4444-555555555555", config.uuid)
        assertEquals("/ws", config.path)
        assertEquals("cdn.example.com", config.sni)
        assertEquals("cdn.example.com", config.host)
        assertFalse(config.allowInsecure)
    }

    @Test
    fun `defaults port path sni host and allowInsecure`() {
        val config = VlessParser.parse(
            "vless://11111111-2222-3333-4444-555555555555@example.com?type=ws&security=tls",
        )
        assertEquals(443, config.port)
        assertEquals("/", config.path)
        assertEquals("example.com", config.sni)
        assertEquals("example.com", config.host)
        assertTrue(config.allowInsecure)
    }

    @Test
    fun `export round trips`() {
        val original = VlessParser.parse(sample)
        assertEquals(original, VlessParser.parse(VlessParser.export(original)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non vless scheme`() {
        VlessParser.parse("trojan://uuid@example.com:443")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non uuid userinfo`() {
        VlessParser.parse("vless://not-a-uuid@example.com:443?type=ws&security=tls")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects grpc transport`() {
        VlessParser.parse(
            "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=grpc&security=tls",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects onering sni`() {
        VlessParser.parse(
            "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=ws&security=tls&sni=onering:abc",
        )
    }

    @Test
    fun `splits host and port variants`() {
        assertEquals("example.com" to 8443, VlessParser.splitHostPort("example.com:8443"))
        assertEquals("example.com" to 443, VlessParser.splitHostPort("example.com"))
        assertEquals("::1" to 443, VlessParser.splitHostPort("[::1]:443"))
        assertEquals("example.com:notaport" to 443, VlessParser.splitHostPort("example.com:notaport"))
    }

    @Test
    fun `invalid link is not valid`() {
        assertFalse(VlessParser.isValid("hello"))
        assertTrue(VlessParser.isValid(sample))
        assertNull(runCatching { VlessParser.parse(null) }.getOrNull())
    }
}
