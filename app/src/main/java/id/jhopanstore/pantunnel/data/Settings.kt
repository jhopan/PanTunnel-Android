package id.jhopanstore.pantunnel.data

import android.content.Context
import android.content.SharedPreferences

/** User-tunable behaviour, persisted next to the profiles. */
data class Settings(
    val httpPingEnabled: Boolean = true,
    val httpPingIntervalSeconds: Int = 3,
    val httpPingUrl: String = DEFAULT_PING_URL,
    val showTraffic: Boolean = true,
    val debugMode: Boolean = false,
    val autoReconnect: Boolean = true,
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
        debugMode = prefs.getBoolean(KEY_DEBUG, false),
        autoReconnect = prefs.getBoolean(KEY_AUTO_RECONNECT, true),
    )

    fun save(settings: Settings) {
        prefs.edit()
            .putBoolean(KEY_PING_ENABLED, settings.httpPingEnabled)
            .putInt(KEY_PING_INTERVAL, settings.pingInterval)
            .putString(KEY_PING_URL, settings.httpPingUrl)
            .putBoolean(KEY_SHOW_TRAFFIC, settings.showTraffic)
            .putBoolean(KEY_DEBUG, settings.debugMode)
            .putBoolean(KEY_AUTO_RECONNECT, settings.autoReconnect)
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
        const val KEY_DEBUG = "debug_mode"
        const val KEY_AUTO_RECONNECT = "auto_reconnect"

        /** One-time onboarding flags (battery guard / autostart prompt). */
        const val KEY_BATTERY_ACK = "battery_optimization_acknowledged"
        const val KEY_AUTOSTART_DONE = "autostart_done"
    }
}
