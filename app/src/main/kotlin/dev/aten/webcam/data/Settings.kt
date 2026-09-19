package dev.aten.webcam.data

import android.content.Context
import androidx.core.content.edit
import dev.aten.webcam.auth.Passwords

enum class StandbyMode {
    /** Hold a wake lock in standby only while charging, where it costs nothing that matters. */
    AUTO,

    /** Never hold a wake lock in standby; relies on the Wi-Fi chip waking the device for connections. */
    MAX_BATTERY,

    /** Always hold a wake lock in standby, for devices whose firmware misses connections while asleep. */
    ALWAYS_RELIABLE,
}

/** Synchronous preferences: the service needs values immediately at start, outside any coroutine. */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit { putInt(KEY_PORT, value) }

    var password: String
        get() = prefs.getString(KEY_PASSWORD, null) ?: Passwords.generate().also { password = it }
        set(value) = prefs.edit { putString(KEY_PASSWORD, value) }

    var standbyMode: StandbyMode
        get() = StandbyMode.entries.firstOrNull { it.name == prefs.getString(KEY_STANDBY, null) } ?: StandbyMode.AUTO
        set(value) = prefs.edit { putString(KEY_STANDBY, value.name) }

    var quality: String
        get() = prefs.getString(KEY_QUALITY, null) ?: "medium"
        set(value) = prefs.edit { putString(KEY_QUALITY, value) }

    /** Extra DNS names for the certificate, e.g. a dynamic-DNS or tunnel hostname. */
    var hostnames: List<String>
        get() = prefs.getString(KEY_HOSTNAMES, null).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        set(value) = prefs.edit { putString(KEY_HOSTNAMES, value.joinToString(",")) }

    var trustProxyHeaders: Boolean
        get() = prefs.getBoolean(KEY_TRUST_PROXY, false)
        set(value) = prefs.edit { putBoolean(KEY_TRUST_PROXY, value) }

    /** Streaming stops below this battery percentage while unplugged; 0 disables the check. */
    var minBatteryPercent: Int
        get() = prefs.getInt(KEY_MIN_BATTERY, 0)
        set(value) = prefs.edit { putInt(KEY_MIN_BATTERY, value) }

    /** Remembered so a reboot can offer to resume listening. */
    var listenerEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    companion object {
        const val DEFAULT_PORT = 8443
        val PORT_RANGE = 1024..65535
        private const val KEY_PORT = "port"
        private const val KEY_PASSWORD = "password"
        private const val KEY_STANDBY = "standby_mode"
        private const val KEY_QUALITY = "quality"
        private const val KEY_HOSTNAMES = "hostnames"
        private const val KEY_TRUST_PROXY = "trust_proxy"
        private const val KEY_MIN_BATTERY = "min_battery"
        private const val KEY_ENABLED = "listener_enabled"
    }
}
