package roro.stellar.manager.hernas

import roro.stellar.manager.BuildConfig

/**
 * Hernas relay configuration, injected at build time via Gradle properties
 * (`-PHERNAS_*` or `ORG_GRADLE_PROJECT_HERNAS_*` environment variables) so no
 * secret is ever committed to the repository. Defaults are safe: relay disabled.
 */
object HernasConfig {

    val enabled: Boolean get() = BuildConfig.HERNAS_ENABLED

    /** Trailing slash trimmed; `/mcp` and `/api/...` paths are appended by callers. */
    val endpoint: String get() = BuildConfig.HERNAS_ENDPOINT.trim().trimEnd('/')

    val deviceId: String get() = BuildConfig.HERNAS_DEVICE_ID.trim()
    val relayToken: String get() = BuildConfig.HERNAS_RELAY_TOKEN.trim()
    val requireConfirmation: Boolean get() = BuildConfig.HERNAS_REQUIRE_CONFIRMATION
    val auditLog: Boolean get() = BuildConfig.HERNAS_AUDIT_LOG

    /** Plaintext HTTP is only accepted for the loopback fallback (local Hernas server). */
    fun isEndpointAllowed(): Boolean {
        if (endpoint.startsWith("https://")) return true
        return endpoint.startsWith("http://127.0.0.1:") || endpoint.startsWith("http://localhost:")
    }

    fun isConfigured(): Boolean = enabled && deviceId.isNotEmpty() && relayToken.isNotEmpty() && isEndpointAllowed()
}
