package roro.stellar.manager.hernas

/**
 * Hernas relay configuration, resolved from runtime settings (HernasSettings,
 * editable in Settings) with build-time values as defaults. Secrets are never
 * hardcoded in the repository.
 */
object HernasConfig {

    val enabled: Boolean get() = HernasSettings.enabled

    /** Trailing slash trimmed; `/api/...` paths are appended by callers. */
    val endpoint: String get() = HernasSettings.endpoint

    val deviceId: String get() = HernasSettings.deviceId
    val relayToken: String get() = HernasSettings.relayToken
    val requireConfirmation: Boolean get() = HernasSettings.requireConfirmation
    val auditLog: Boolean get() = HernasSettings.auditLog

    /** Plaintext HTTP is only accepted for the loopback fallback (local Hernas server). */
    fun isEndpointAllowed(): Boolean {
        if (endpoint.startsWith("https://")) return true
        return endpoint.startsWith("http://127.0.0.1:") || endpoint.startsWith("http://localhost:")
    }

    fun isConfigured(): Boolean = enabled && deviceId.isNotEmpty() && relayToken.isNotEmpty() && isEndpointAllowed()

    /** True when everything but the enable flag is ready (used by the settings UI). */
    fun isConfigComplete(): Boolean = deviceId.isNotEmpty() && relayToken.isNotEmpty() && isEndpointAllowed()
}
