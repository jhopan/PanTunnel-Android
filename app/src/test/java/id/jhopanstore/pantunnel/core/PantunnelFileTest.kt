package id.jhopanstore.pantunnel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the plain `.pantunnel` export/import contract: the file content is exactly a
 * `vless://` link, so any device can re-import it (no HWID involved).
 */
class PantunnelFileTest {

    private val link =
        "vless://11111111-2222-3333-4444-555555555555@example.com:8443" +
            "?type=ws&security=tls&path=%2Fws&sni=cdn.example.com&host=cdn.example.com" +
            "&allowInsecure=true#VpnService"

    @Test
    fun `plain file content is a re-importable vless link`() {
        val config = VlessParser.parse(link)
        val fileContent = VlessParser.export(config)

        assertTrue(fileContent.startsWith("vless://"))
        assertEquals(config, VlessParser.parse(fileContent))
    }

    @Test
    fun `plain file is not mistaken for a locked payload`() {
        val fileContent = VlessParser.export(VlessParser.parse(link))
        assertTrue(!LicenseCodec.isEncoded(fileContent))
    }

    @Test
    fun `locked payload is detected separately from a plain link`() {
        val locked = LicenseCodec.encode(
            License(vless = link, name = "Budi", hwid = "AABBCCDDEEFF001122334455"),
        )
        assertTrue(LicenseCodec.isEncoded(locked))
        assertTrue(!LicenseCodec.isEncoded(link))
    }

    @Test
    fun `import can pick the link out of a multi line file`() {
        val file = """
            Catatan: ini config dari JhopanStore
            jangan dibagikan sembarangan

            ${VlessParser.export(VlessParser.parse(link))}

        """.trimIndent()

        val picked = file.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("vless://", true) || LicenseCodec.isEncoded(it) }

        assertEquals(VlessParser.parse(link), VlessParser.parse(picked!!))
    }

    @Test
    fun `exported file survives a blank first line`() {
        val file = "\n\n" + VlessParser.export(VlessParser.parse(link)) + "\n"
        val picked = file.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("vless://", true) }
        assertTrue(picked != null)
        assertEquals("example.com", VlessParser.parse(picked!!).address)
    }
}
