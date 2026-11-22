package com.nuvio.app.core.network

import com.nuvio.app.core.auth.AuthSessionRetention
import com.nuvio.app.core.auth.SessionClearMode
import com.nuvio.app.core.storage.CredentialCodec
import com.nuvio.app.core.storage.DesktopCredentials
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.SecureStorageUnavailable
import com.russhwolf.settings.Settings
import io.github.jan.supabase.auth.AuthConfig
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.SettingsCodeVerifierCache
import io.github.jan.supabase.auth.SettingsSessionManager
import io.github.jan.supabase.auth.user.UserSession
import java.util.prefs.Preferences

internal actual fun AuthConfig.configurePlatformCredentialStorage(backendUrl: String) {
    val settings = DesktopSessionSettings()
    val key = "sb-${backendUrl.removeSuffix("/").replace('/', '-').replace('.', '-')}"
    sessionManager = RetainedSessionManager(SettingsSessionManager(settings = settings, key = "$key-session"))
    codeVerifierCache = SettingsCodeVerifierCache(settings = settings, key = "$key-supabase_code_verifier")
}

/**
 * Forwards a stored session, and ignores a delete unless sign-out was explicit or the
 * server said the session is gone. A refused delete leaves both copies where they are.
 */
internal class RetainedSessionManager(
    private val delegate: SessionManager,
) : SessionManager {
    override suspend fun saveSession(session: UserSession) = delegate.saveSession(session)

    override suspend fun loadSession(): UserSession? = delegate.loadSession()

    override suspend fun deleteSession() {
        if (!SessionClearMode.explicit && !AuthSessionRetention.consumeStoredClear()) return
        delegate.deleteSession()
    }
}

/** Keeps the SDK's exact legacy keys and serialization, including its old unscoped session migration. */
internal class DesktopSessionSettings(
    private val legacy: Settings = Settings(),
    private val store: DesktopStorage.Store = DesktopStorage.store("nuvio_account_sessions"),
    private val flushLegacy: () -> Unit = { Preferences.userRoot().flush() },
) : Settings by legacy {
    override val keys: Set<String>
        get() = try {
            store.exclusive { store.keys() + legacy.keys }
        } catch (_: Exception) {
            legacy.keys
        }

    override val size: Int
        get() = keys.size

    override fun hasKey(key: String): Boolean = try {
        store.exclusive { store.contains(key) || legacy.hasKey(key) }
    } catch (_: Exception) {
        legacy.hasKey(key)
    }

    override fun getStringOrNull(key: String): String? {
        val saved = try {
            store.exclusive {
                try {
                    store.getFreshString(key)?.takeUnless(CredentialCodec::isReference)
                } catch (_: SecureStorageUnavailable) {
                    null
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: DesktopStorage.StoreLockUnavailable) {
            return legacyUnlessRetired(key)
        } catch (_: Exception) {
            return legacyUnlessRetired(key)
        }
        if (saved != null) return saved
        // A rejected session is marked retired and left in the legacy copy. Do not load it again.
        // A locked or denied read never sets that mark, so the legacy copy still signs the app in.
        if (isRetired(key)) return null
        val fallback = legacy.getStringOrNull(key) ?: return null
        try {
            store.exclusive { store.putString(key, fallback) }
        } catch (_: Exception) {
            DesktopCredentials.warning()
        }
        return fallback
    }

    override fun getString(key: String, defaultValue: String): String = getStringOrNull(key) ?: defaultValue

    override fun putString(key: String, value: String) {
        clearRetired(key)
        try {
            store.exclusive { store.putString(key, value) }
        } catch (_: SecureStorageUnavailable) {
            keepLegacy(key, value)
        } catch (_: DesktopStorage.StoreLockUnavailable) {
            keepLegacy(key, value)
        } catch (_: Exception) {
            keepLegacy(key, value)
        }
    }

    override fun remove(key: String) {
        val storeFailure = runCatching {
            store.exclusive { store.remove(key) }
        }.exceptionOrNull()
        // A locked store must not block startup, and an involuntary clear keeps the legacy copy.
        if (!SessionClearMode.explicit && isLegacyAccountKey(key)) {
            if (storeFailure != null) DesktopCredentials.warning()
            retire(key)
            return
        }
        legacy.remove(key)
        clearRetired(key)
        flushLegacy()
        if (storeFailure != null) throw storeFailure
    }

    override fun clear() {
        try {
            store.exclusive {
                store.keys().toList().forEach { store.remove(it) }
            }
        } catch (_: Exception) {
            DesktopCredentials.warning()
        }
        if (!SessionClearMode.explicit) return
        legacy.keys.filter(::isLegacyAccountKey).forEach(legacy::remove)
        runCatching(flushLegacy)
    }

    private fun legacyUnlessRetired(key: String): String? =
        if (isRetired(key)) null else legacy.getStringOrNull(key)

    private fun isRetired(key: String): Boolean = legacy.hasKey(retiredMarker(key))

    private fun retire(key: String) {
        try {
            legacy.putString(retiredMarker(key), "1")
            flushLegacy()
        } catch (_: Exception) {
            DesktopCredentials.warning()
        }
    }

    private fun clearRetired(key: String) {
        if (!legacy.hasKey(retiredMarker(key))) return
        try {
            legacy.remove(retiredMarker(key))
            flushLegacy()
        } catch (_: Exception) {
            DesktopCredentials.warning()
        }
    }

    private fun keepLegacy(key: String, value: String) {
        try {
            legacy.putString(key, value)
            flushLegacy()
        } catch (_: Exception) {
            DesktopCredentials.warning()
        }
    }

    fun migrate() {
        legacy.keys.filter(::isLegacyAccountKey).forEach(::getStringOrNull)
    }
}

private const val UNSCOPED_SESSION = "session"

private fun retiredMarker(key: String) = "__retired_$key"

private fun isLegacyAccountKey(key: String): Boolean = key == UNSCOPED_SESSION || key == "supabase_code_verifier" ||
    key.startsWith("sb-") && (key.endsWith("-session") || key.endsWith("-supabase_code_verifier"))

internal fun clearLegacyAccountSessions(legacy: Settings = Settings(), flush: () -> Unit = { Preferences.userRoot().flush() }) {
    legacy.keys.filter(::isLegacyAccountKey).forEach(legacy::remove)
    flush()
}
