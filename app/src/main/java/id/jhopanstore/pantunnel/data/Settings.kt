package id.jhopanstore.pantunnel.data

import android.content.Context
import android.content.SharedPreferences
import id.jhopanstore.pantunnel.core.SplitTunnel

/** User-tunable behaviour, persisted next to the profiles. */
data class Settings(
    val httpPingEnabled: Boolean = true,
    val httpPingIntervalSeconds: Int = 3,
    val httpPingUrl: String = DEFAULT_PING_URL,
    val showTraffic: Boolean = true,
    val autoReconnect: Boolean = true,
    val splitTunnel: SplitTunnel = SplitTunnel(),
) {
    val pingInterval: Int get() = httpPingIntervalSeconds.coerceAtLeast(1)

    companion object {
        const val DEFAULT_PING_URL = "https://connectivitycheck.gstatic.com/generate_204"
    }
}

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(ProfileRepository.PREFS, Context.MODE_PRIVATE)

    fun load(): Settings = Settings(
        httpPingEnabled = prefs.getBoolean(KEY_PING_ENABLED, true),
        httpPingIntervalSeconds = prefs.getInt(KEY_PING_INTERVAL, 3),
        httpPingUrl = prefs.getString(KEY_PING_URL, null)?.takeIf { it.isNotBlank() }
            ?: Settings.DEFAULT_PING_URL,
        showTraffic = prefs.getBoolean(KEY_SHOW_TRAFFIC, true),
        autoReconnect = prefs.getBoolean(KEY_AUTO_RECONNECT, true),
        splitTunnel = SplitTunnel(
            enabled = prefs.getBoolean(KEY_SPLIT_ENABLED, false),
            allowMode = prefs.getBoolean(KEY_SPLIT_ALLOW_MODE, true),
            packages = prefs.getStringSet(KEY_SPLIT_PACKAGES, emptySet()).orEmpty(),
        ),
    )

    fun save(settings: Settings) {
        prefs.edit()
            .putBoolean(KEY_PING_ENABLED, settings.httpPingEnabled)
            .putInt(KEY_PING_INTERVAL, settings.pingInterval)
            .putString(KEY_PING_URL, settings.httpPingUrl)
            .putBoolean(KEY_SHOW_TRAFFIC, settings.showTraffic)
            .putBoolean(KEY_AUTO_RECONNECT, settings.autoReconnect)
            .putBoolean(KEY_SPLIT_ENABLED, settings.splitTunnel.enabled)
            .putBoolean(KEY_SPLIT_ALLOW_MODE, settings.splitTunnel.allowMode)
            .putStringSet(KEY_SPLIT_PACKAGES, settings.splitTunnel.packages)
            .apply()
    }

    fun setFlag(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun flag(key: String, default: Boolean = false): Boolean = prefs.getBoolean(key, default)

    companion object {
        const val KEY_PING_ENABLED = "http_ping"
        const val KEY_PING_INTERVAL = "http_ping_interval"
        const val KEY_PING_URL = "http_ping_url"
        const val KEY_SHOW_TRAFFIC = "show_traffic"
        const val KEY_AUTO_RECONNECT = "auto_reconnect"
        const val KEY_SPLIT_ENABLED = "split_enabled"
        const val KEY_SPLIT_ALLOW_MODE = "split_allow_mode"
        const val KEY_SPLIT_PACKAGES = "split_packages"

        /** One-time onboarding flag for the battery guard prompt. */
        const val KEY_BATTERY_ACK = "battery_optimization_acknowledged"
    }
}
