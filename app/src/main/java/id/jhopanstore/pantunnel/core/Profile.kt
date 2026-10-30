package id.jhopanstore.pantunnel.core

import java.security.MessageDigest
import java.util.UUID

/**
 * A saved profile. [address] holds "host:port"; [path], [sni] and [host] mirror the URI fields.
 * An empty [address] means "slot exists but has no config yet".
 */
data class Profile(
    val id: String,
    val name: String = "",
    val address: String = "",
    val uuid: String = "",
    val path: String = "/",
    val sni: String = "",
    val host: String = "",
) {
    val hasConfig: Boolean get() = address.isNotBlank() && uuid.isNotBlank()

    val displayName: String get() = name.ifBlank { "Untitled" }

    /** Best-effort VLESS link for this profile (never throws; empty when the slot is blank). */
    fun toUri(): String {
        if (!hasConfig) return ""
        val (server, port) = VlessParser.splitHostPort(address)
        val serverName = sni.ifBlank { server }
        return VlessParser.export(
            VlessConfig(
                address = server,
                port = port,
                uuid = uuid,
                path = path.ifBlank { "/" },
                sni = serverName,
                host = host.ifBlank { serverName },
                allowInsecure = true,
            ),
        )
    }

    companion object {
        fun fromConfig(id: String, name: String, config: VlessConfig): Profile = Profile(
            id = id,
            name = name,
            address = config.hostPort,
            uuid = config.uuid,
            path = config.path,
            sni = config.sni,
            host = config.host,
        )

        /** Short, collision-resistant id (base36 millis + random suffix). */
        fun newId(): String =
            System.currentTimeMillis().toString(36) + (100..999).random().toString(36)
    }
}

/**
 * Pure-Kotlin profile list logic: ordering, active selection and the "pick a sane successor
 * after delete" rule. Persistence lives in [ProfileRepository].
 */
class ProfileBook(initial: List<Profile> = emptyList(), activeId: String? = null) {

    private val items = initial.toMutableList()
    private var active: String? = activeId

    val all: List<Profile> get() = items.toList()
    val count: Int get() = items.size
    val countWithConfig: Int get() = items.count { it.hasConfig }

    fun get(id: String?): Profile? = items.firstOrNull { it.id == id }

    /** Active profile, falling back to the first slot that actually holds a config. */
    fun active(): Profile? {
        get(active)?.let { return it }
        return items.firstOrNull { it.hasConfig } ?: items.firstOrNull()
    }

    fun activeId(): String? = active()?.id

    fun setActive(id: String?) {
        active = id
    }

    /** Insert or replace by id. Returns false when the slot limit is reached. */
    fun put(profile: Profile): Boolean {
        val index = items.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            items[index] = profile
            return true
        }
        if (items.size >= MAX_PROFILES) return false
        items.add(profile)
        return true
    }

    fun remove(id: String): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        items.removeAt(index)
        if (active == id) active = successorId(index)
        return true
    }

    /**
     * Nearest surviving neighbour first, but prefer one that holds a config so the user never
     * lands on an empty slot and loses the ability to connect.
     */
    private fun successorId(removedAt: Int): String? {
        if (items.isEmpty()) return null
        val nearest = removedAt.coerceIn(0, items.size - 1)
        for (offset in items.indices) {
            val before = nearest - offset
            if (before >= 0 && items[before].hasConfig) return items[before].id
            val after = nearest + offset
            if (after < items.size && items[after].hasConfig) return items[after].id
        }
        return items[nearest].id
    }

    /** Guarantee at least one (possibly empty) slot exists. */
    fun ensureDefault(name: String = "Default"): Profile {
        if (items.isEmpty()) items.add(Profile(id = Profile.newId(), name = name))
        val current = active() ?: items.first()
        active = current.id
        return current
    }

    companion object {
        const val MAX_PROFILES = 10
    }
}

/** Stable per-install HWID: SHA-256 of a random install id, first 24 hex chars (upper case). */
object Hwid {
    fun fromInstallId(installId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(installId.toByteArray(Charsets.UTF_8))
        return buildString {
            for (byte in digest) append("%02X".format(byte))
        }.take(24)
    }

    fun randomInstallId(): String = UUID.randomUUID().toString()
}
