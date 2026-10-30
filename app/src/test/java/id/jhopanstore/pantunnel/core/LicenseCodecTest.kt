package id.jhopanstore.pantunnel.core

import javax.crypto.AEADBadTagException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenseCodecTest {

    private val hwid = "AABBCCDDEEFF001122334455"
    private val vless =
        "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=ws&security=tls&path=%2F&sni=example.com&host=example.com&allowInsecure=true#VpnService"

    @Test
    fun `round trips for the matching device`() {
        val license = License(vless = vless, name = "Budi", hwid = hwid, lock = true, expiry = 0L)
        val payload = LicenseCodec.encode(license)

        assertTrue(LicenseCodec.isEncoded(payload))
        assertTrue(payload.startsWith(LicenseCodec.PREFIX))

        val decoded = LicenseCodec.decode(payload, hwid)
        assertEquals(vless, decoded.vless)
        assertEquals("Budi", decoded.name)
        assertEquals(hwid, decoded.hwid)
        assertTrue(decoded.lock)
        assertEquals(0L, decoded.expiry)
    }

    @Test(expected = AEADBadTagException::class)
    fun `rejects another device`() {
        val payload = LicenseCodec.encode(License(vless = vless, hwid = hwid))
        LicenseCodec.decode(payload, "000000000000000000000000")
    }

    @Test
    fun `each encode uses a fresh iv`() {
        val license = License(vless = vless, hwid = hwid)
        assertFalse(LicenseCodec.encode(license) == LicenseCodec.encode(license))
    }

    @Test
    fun `expiry is evaluated against now`() {
        assertFalse(License(vless = vless, expiry = 0L).isExpired)
        assertFalse(License(vless = vless, expiry = System.currentTimeMillis() + 60_000).isExpired)
        assertTrue(License(vless = vless, expiry = System.currentTimeMillis() - 60_000).isExpired)
    }

    @Test
    fun `rejects plain text`() {
        assertFalse(LicenseCodec.isEncoded("vless://x"))
        val error = runCatching { LicenseCodec.decode("vless://x", hwid) }
        assertTrue(error.isFailure)
    }
}
