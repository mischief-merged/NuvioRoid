package com.nuvio.app.core.network

import com.nuvio.app.core.storage.CredentialCodec
import com.nuvio.app.core.storage.DesktopCredentials
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.SecureStorageUnavailable
import com.nuvio.app.core.storage.credentialAccess
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
    override val keys: Set<String>
        get() = store.exclusive { store.keys() + legacy.keys }

    override val size: Int
        get() = keys.size

    override fun hasKey(key: String): Boolean = store.exclusive {
        store.contains(key) || legacy.hasKey(key)
    }

    override fun getStringOrNull(key: String): String? = store.exclusive {
        val saved = try {
            store.getFreshString(key)?.takeUnless(CredentialCodec::isReference)
        } catch (_: SecureStorageUnavailable) {
            val fallback = legacy.getStringOrNull(key)
            if (fallback != null) credentialAccess.retry()
            return@exclusive fallback
        } catch (_: Exception) {
            null
        }
        if (saved != null) {
            // Only drop the Java-prefs copy after a later read has returned the session
            // from the new store without using that copy.
            cleanLegacy(key)
            return@exclusive saved
        }
        val fallback = legacy.getStringOrNull(key) ?: return@exclusive null
        try {
            store.putString(key, fallback)
        } catch (_: SecureStorageUnavailable) {
            DesktopCredentials.warning()
            credentialAccess.retry()
        } catch (_: Exception) {
            DesktopCredentials.warning()
        }
        fallback
    }

    override fun getString(key: String, defaultValue: String): String = getStringOrNull(key) ?: defaultValue

    override fun putString(key: String, value: String) = store.exclusive {
        store.putString(key, value)
    }

    override fun remove(key: String) = store.exclusive {
        store.remove(key)
        val legacyResult = runCatching { legacy.remove(key); flushLegacy() }
        legacyResult.getOrThrow()
    }

    override fun clear() = store.exclusive {
        store.keys().toList().forEach { store.remove(it) }
        legacy.keys.filter(::isLegacyAccountKey).forEach(legacy::remove)
        runCatching(flushLegacy)
        Unit
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
