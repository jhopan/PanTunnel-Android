package id.jhopanstore.pantunnel.data

import android.content.Context
import android.content.SharedPreferences
import id.jhopanstore.pantunnel.core.Hwid
import id.jhopanstore.pantunnel.core.License
import id.jhopanstore.pantunnel.core.LicenseCodec
import id.jhopanstore.pantunnel.core.Profile
import id.jhopanstore.pantunnel.core.ProfileBook
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Single source of truth for profiles, license and the stable device HWID.
 *
 * Backed by one `SharedPreferences` file so the VPN service and the UI see the same data
 * without IPC. All writes are synchronous (`commit`) because the service reads right after
 * the activity writes.
 */
class ProfileRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- profiles

    fun loadBook(): ProfileBook = ProfileBook(readProfiles(), prefs.getString(KEY_ACTIVE, null))

    fun save(book: ProfileBook) {
        val array = JSONArray()
        book.all.forEach { array.put(it.toJson()) }
        val editor = prefs.edit().putString(KEY_PROFILES, array.toString())
        val activeId = book.activeId()
        if (activeId != null) editor.putString(KEY_ACTIVE, activeId) else editor.remove(KEY_ACTIVE)
        editor.apply()
    }

    /** Load -> mutate -> persist, so callers never have to remember the two-step dance. */
    fun edit(block: (ProfileBook) -> Unit): ProfileBook {
        val book = loadBook()
        block(book)
        save(book)
        return book
    }

    fun activeProfile(): Profile? = loadBook().active()

    fun activeUri(): String = activeProfile()?.toUri().orEmpty()

    private fun readProfiles(): List<Profile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    add(Profile.fromJson(array.getJSONObject(index)))
                }
            }
        }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------- license

    fun storedLicense(): License? {
        val payload = prefs.getString(KEY_LICENSE, null) ?: return null
        return runCatching { LicenseCodec.decode(payload, hwid()) }.getOrNull()
    }

    /** @return the decoded license, or null when the file is not for this device. */
    fun installLicense(payload: String): License? {
        val license = runCatching { LicenseCodec.decode(payload, hwid()) }.getOrNull() ?: return null
        prefs.edit().putString(KEY_LICENSE, payload).apply()
        return license
    }

    fun clearLicense() {
        prefs.edit().remove(KEY_LICENSE).apply()
    }

    // ---------------------------------------------------------------- hwid

    /** SHA-256 of a random install id; stable for the life of the install. */
    fun hwid(): String {
        val existing = prefs.getString(KEY_INSTALL_ID, null)
        val id = existing ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_INSTALL_ID, it).apply()
        }
        return Hwid.fromInstallId(id)
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    /**
     * Wipe config + license but keep the install id, so a device that already imported a
     * license does not lose its HWID to a "Clear config".
     */
    fun clearConfig() {
        val installId = prefs.getString(KEY_INSTALL_ID, null)
        prefs.edit().clear().apply()
        if (installId != null) prefs.edit().putString(KEY_INSTALL_ID, installId).apply()
    }

    companion object {
        const val PREFS = "pantunnel"
        const val KEY_PROFILES = "profiles"
        const val KEY_ACTIVE = "active_profile"
        const val KEY_LICENSE = "license_payload"
        const val KEY_INSTALL_ID = "install_id"

        private fun Profile.toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("address", address)
            .put("uuid", uuid)
            .put("path", path)
            .put("sni", sni)
            .put("host", host)

        private fun Profile.Companion.fromJson(json: JSONObject): Profile = Profile(
            id = json.optString("id"),
            name = json.optString("name"),
            address = json.optString("address"),
            uuid = json.optString("uuid"),
            path = json.optString("path", "/").ifBlank { "/" },
            sni = json.optString("sni"),
            host = json.optString("host"),
        )
    }
}
