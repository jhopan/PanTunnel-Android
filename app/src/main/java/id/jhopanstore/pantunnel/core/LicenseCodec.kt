package id.jhopanstore.pantunnel.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Offline AES-256-GCM license codec, keyed by the device HWID.
 *
 * Wrong device -> GCM authentication fails, so a copied file is useless. Format:
 * `PNT1:` + base64(version || 12-byte IV || ciphertext+tag).
 */
object LicenseCodec {

    const val PREFIX = "PNT1:"

    private const val SALT = "pantunnel-license-v1"
    private const val VERSION: Byte = 0x01
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun isEncoded(text: String?): Boolean = text != null && text.startsWith(PREFIX)

    fun encode(license: License): String {
        val payload = JSONObject()
            .put("v", license.vless)
            .put("n", license.name)
            .put("h", license.hwid)
            .put("l", license.lock)
            .put("e", license.expiry)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, keyFor(license.hwid), GCMParameterSpec(TAG_BITS, iv))
        }
        val cipherText = cipher.doFinal(payload)

        val out = ByteArray(1 + IV_LEN + cipherText.size)
        out[0] = VERSION
        iv.copyInto(out, destinationOffset = 1)
        cipherText.copyInto(out, destinationOffset = 1 + IV_LEN)
        return PREFIX + Base64.getEncoder().encodeToString(out)
    }

    /**
     * @throws javax.crypto.AEADBadTagException when the payload belongs to another device.
     */
    fun decode(text: String, deviceHwid: String): License {
        require(isEncoded(text)) { "Not a locked config" }
        val payload = Base64.getDecoder().decode(text.substring(PREFIX.length))
        require(payload.size >= 1 + IV_LEN) { "Corrupt license" }
        require(payload[0] == VERSION) { "Unsupported license version" }

        val iv = payload.copyOfRange(1, 1 + IV_LEN)
        val cipherText = payload.copyOfRange(1 + IV_LEN, payload.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, keyFor(deviceHwid), GCMParameterSpec(TAG_BITS, iv))
        }
        val plain = cipher.doFinal(cipherText) // AEADBadTagException on HWID mismatch
        val root = JSONObject(String(plain, Charsets.UTF_8))

        val vless = root.optString("v", "")
        require(vless.isNotEmpty()) { "Corrupt license" }
        return License(
            vless = vless,
            name = root.optString("n", ""),
            hwid = deviceHwid,
            lock = root.optBoolean("l", true),
            expiry = root.optLong("e", 0L),
        )
    }

    fun expired(license: License): Boolean = license.isExpired

    private fun keyFor(hwid: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((hwid + SALT).toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest, "AES")
    }
}
