package com.nuvio.app.core.network

import com.nuvio.app.core.storage.DesktopCredentials
import com.nuvio.app.core.storage.DesktopStorage
import com.russhwolf.settings.Settings
import io.github.jan.supabase.auth.AuthConfig
import io.github.jan.supabase.auth.SettingsCodeVerifierCache
import io.github.jan.supabase.auth.SettingsSessionManager
import java.util.prefs.Preferences

internal actual fun AuthConfig.configurePlatformCredentialStorage(backendUrl: String) {
    val settings = DesktopSessionSettings()
    val key = "sb-${backendUrl.removeSuffix("/").replace('/', '-').replace('.', '-')}"
    sessionManager = SettingsSessionManager(settings = settings, key = "$key-session")
    codeVerifierCache = SettingsCodeVerifierCache(settings = settings, key = "$key-supabase_code_verifier")
}

/** Keeps the SDK's exact legacy keys and serialization, including its old unscoped session migration. */
internal class DesktopSessionSettings(
    private val legacy: Settings = Settings(),
    private val store: DesktopStorage.Store = DesktopStorage.store("nuvio_account_sessions"),
    private val flushLegacy: () -> Unit = { Preferences.userRoot().flush() },
) : Settings by legacy {
    override fun getStringOrNull(key: String): String? = store.exclusive {
        val saved = store.getFreshString(key)
        val value = saved ?: legacy.getStringOrNull(key)
        if (value != null) {
            try {
                if (saved == null) store.putString(key, value)
                if (store.isSecured(key)) cleanLegacy(key)
            } catch (_: Exception) {
                // Import must never hide a usable legacy session when a file write is interrupted.
                DesktopCredentials.warning()
            }
        }
        value
    }

    override fun getString(key: String, defaultValue: String): String = getStringOrNull(key) ?: defaultValue

    override fun putString(key: String, value: String) = store.exclusive {
        store.putString(key, value)
        if (store.isSecured(key)) cleanLegacy(key)
    }

    override fun remove(key: String) = store.exclusive {
        val legacyResult = runCatching { legacy.remove(key); flushLegacy() }
        val currentResult = runCatching { store.remove(key) }
        legacyResult.getOrThrow()
        currentResult.getOrThrow()
    }

    private fun cleanLegacy(key: String) {
        try { legacy.remove(key); flushLegacy() } catch (_: Exception) { DesktopCredentials.warning() }
    }

    fun migrate() {
        legacy.keys.filter(::isLegacyAccountKey).forEach(::getStringOrNull)
    }
}

private fun isLegacyAccountKey(key: String): Boolean = key == "session" || key == "supabase_code_verifier" ||
    key.startsWith("sb-") && (key.endsWith("-session") || key.endsWith("-supabase_code_verifier"))

internal fun clearLegacyAccountSessions(legacy: Settings = Settings(), flush: () -> Unit = { Preferences.userRoot().flush() }) {
    legacy.keys.filter(::isLegacyAccountKey).forEach(legacy::remove)
    flush()
}
