package roro.stellar.manager.hernas

import roro.stellar.manager.BuildConfig
import roro.stellar.manager.StellarSettings

/**
 * Runtime-editable Hernas relay configuration, stored in the app settings
 * SharedPreferences (device-protected storage, app-private — secrets never
 * leave the device and are never committed). Build-time values (BuildConfig)
 * act as first-run defaults.
 */
object HernasSettings {

    private const val PREFIX = "hernas_"
    private const val KEY_ENABLED = PREFIX + "enabled"
    private const val KEY_ENDPOINT = PREFIX + "endpoint"
    private const val KEY_DEVICE_ID = PREFIX + "device_id"
    private const val KEY_RELAY_TOKEN = PREFIX + "relay_token"
    private const val KEY_REQUIRE_CONFIRMATION = PREFIX + "require_confirmation"
    private const val KEY_AUDIT_LOG = PREFIX + "audit_log"

    private val prefs get() = StellarSettings.getPreferences()

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, BuildConfig.HERNAS_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var endpoint: String
        get() = prefs.getString(KEY_ENDPOINT, null) ?: BuildConfig.HERNAS_ENDPOINT
        set(value) = prefs.edit().putString(KEY_ENDPOINT, value.trim().trimEnd('/')).apply()

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: BuildConfig.HERNAS_DEVICE_ID
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value.trim()).apply()

    var relayToken: String
        get() = prefs.getString(KEY_RELAY_TOKEN, null) ?: BuildConfig.HERNAS_RELAY_TOKEN
        set(value) = prefs.edit().putString(KEY_RELAY_TOKEN, value.trim()).apply()

    var requireConfirmation: Boolean
        get() = prefs.getBoolean(KEY_REQUIRE_CONFIRMATION, BuildConfig.HERNAS_REQUIRE_CONFIRMATION)
        set(value) = prefs.edit().putBoolean(KEY_REQUIRE_CONFIRMATION, value).apply()

    var auditLog: Boolean
        get() = prefs.getBoolean(KEY_AUDIT_LOG, BuildConfig.HERNAS_AUDIT_LOG)
        set(value) = prefs.edit().putBoolean(KEY_AUDIT_LOG, value).apply()
}
