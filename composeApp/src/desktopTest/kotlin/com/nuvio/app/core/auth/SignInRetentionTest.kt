@file:OptIn(io.github.jan.supabase.annotations.SupabaseInternal::class)

package com.nuvio.app.core.auth

import com.nuvio.app.core.network.DesktopSessionSettings
import com.nuvio.app.core.network.RetainedSessionManager
import com.nuvio.app.core.network.installRetainedAuthGuard
import com.nuvio.app.core.storage.CredentialVault
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.sync.SyncManager
import com.russhwolf.settings.PropertiesSettings
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SettingsCodeVerifierCache
import io.github.jan.supabase.auth.SettingsSessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class SignInRetentionTest {
    @Test
    fun a_denied_keychain_still_reaches_a_signed_in_session() {
        runBlocking {
            assertStartup("denied-read", vault = denied("user canceled"), legacyKey = SESSION_KEY, lockParent = false)
        }
    }

    @Test
    fun a_canceled_keychain_write_still_reaches_a_signed_in_session() {
        runBlocking {
            assertStartup("denied-write", vault = denied("user canceled"), legacyKey = SESSION_KEY, lockParent = false)
        }
    }

    @Test
    fun a_locked_store_still_reaches_a_signed_in_session() {
        runBlocking {
            assertStartup("locked", vault = memoryVault(), legacyKey = SESSION_KEY, lockParent = true)
        }
    }

    @Test
    fun an_unscoped_legacy_session_survives_a_denied_keychain_migration() {
        runBlocking {
        val legacy = assertStartup("unscoped", vault = denied("user canceled"), legacyKey = "session", lockParent = false)
        assertTrue(legacy.getStringOrNull("session").orEmpty().contains("access-token"))
        assertTrue(legacy.getStringOrNull(SESSION_KEY).orEmpty().contains("access-token"))
        }
    }

    @Test
    fun a_transient_refresh_failure_keeps_the_session_and_the_local_app() {
        runBlocking {
        val outcome = refresh(HttpStatusCode.TooManyRequests, """{"error_code":"session_not_found"}""")
        try {
            assertIs<SessionStatus.RefreshFailure>(outcome.status)
            assertEquals("access-token", outcome.loaded?.accessToken)
            assertTrue(outcome.legacy.getStringOrNull(SESSION_KEY).orEmpty().contains("access-token"))
            assertEquals("library=kept", Files.readString(outcome.library))
            assertTrue(AuthRepository.applyOfflineSession(outcome.loaded))
            assertTrue(SyncManager.syncPaused)
            assertEquals("user-1", (AuthRepository.state.value as AuthState.Authenticated).userId)
        } finally {
            outcome.close()
        }
        }
    }

    @Test
    fun a_network_failure_during_refresh_keeps_the_stored_session() {
        runBlocking {
            val outcome = refresh(HttpStatusCode.OK, "{}", MockEngine { throw IOException("network unreachable") })
            try {
                assertIs<SessionStatus.RefreshFailure>(outcome.status)
                assertEquals("access-token", outcome.loaded?.accessToken)
                assertTrue(outcome.legacy.getStringOrNull(SESSION_KEY).orEmpty().contains("access-token"))
            } finally {
                outcome.close()
            }
        }
    }

    @Test
    fun a_timeout_or_server_error_does_not_drop_the_stored_session() {
        runBlocking {
        val timeout = refresh(HttpStatusCode.RequestTimeout, """{"error_code":"session_not_found"}""")
        try {
            assertIs<SessionStatus.RefreshFailure>(timeout.status)
            assertEquals("access-token", timeout.loaded?.accessToken)
        } finally {
            timeout.close()
        }
        val server = refresh(HttpStatusCode.InternalServerError, """{"message":"unavailable"}""")
        try {
            assertIs<SessionStatus.RefreshFailure>(server.status)
            assertTrue(server.legacy.getStringOrNull(SESSION_KEY).orEmpty().contains("access-token"))
        } finally {
            server.close()
        }
        }
    }

    @Test
    fun an_unclassified_rejection_does_not_drop_the_stored_session() {
        runBlocking {
        val outcome = refresh(HttpStatusCode.Unauthorized, """{"message":"unauthorized"}""")
        try {
            assertIs<SessionStatus.RefreshFailure>(outcome.status)
            assertEquals("access-token", outcome.loaded?.accessToken)
            assertTrue(outcome.legacy.getStringOrNull(SESSION_KEY).orEmpty().contains("access-token"))
        } finally {
            outcome.close()
        }
        }
    }

    @Test
    fun a_revoked_session_drops_the_new_copy_only_and_leaves_everything_else() {
        runBlocking {
        val outcome = refresh(HttpStatusCode.BadRequest, """{"error_code":"session_not_found","msg":"Session not found"}""")
        try {
            assertIs<SessionStatus.NotAuthenticated>(outcome.status)
            assertTrue(outcome.legacy.getStringOrNull(SESSION_KEY).orEmpty().contains("access-token"))
            assertNull(outcome.store.getFreshString(SESSION_KEY))
            assertEquals("library=kept", Files.readString(outcome.library))
            AuthRepository.onInvoluntarySignOut()
            assertEquals("library=kept", Files.readString(outcome.library))
            assertIs<AuthState.Unauthenticated>(AuthRepository.state.value)
        } finally {
            outcome.close()
        }
        }
    }

    @Test
    fun an_involuntary_remove_keeps_the_legacy_copy_and_sign_out_drops_it() {
        runBlocking {
        val directory = Files.createTempDirectory("session-remove-mode")
        try {
            val legacy = PropertiesSettings(Properties().apply { setProperty(SESSION_KEY, freshSession()) })
            val settings = DesktopSessionSettings(legacy, DesktopStorage.Store(directory.resolve("session.properties"), memoryVault(), { true }, {})) {}
            settings.remove(SESSION_KEY)
            assertTrue(legacy.getStringOrNull(SESSION_KEY).orEmpty().contains("access-token"))
            SessionClearMode.explicit { settings.remove(SESSION_KEY) }
            assertNull(legacy.getStringOrNull(SESSION_KEY))
        } finally {
            directory.toFile().deleteRecursively()
        }
        }
    }

    private suspend fun assertStartup(
        name: String,
        vault: CredentialVault,
        legacyKey: String,
        lockParent: Boolean,
    ): PropertiesSettings {
        val directory = Files.createTempDirectory("session-$name")
        val legacy = PropertiesSettings(Properties().apply { setProperty(legacyKey, freshSession()) })
        val parent = if (lockParent) {
            directory.resolve("not-a-directory").also { Files.writeString(it, "blocked") }
        } else {
            directory
        }
        val store = DesktopStorage.Store(parent.resolve("session.properties"), vault, { true }, {})
        val settings = DesktopSessionSettings(legacy, store) {}
        val client = client(settings, engine(HttpStatusCode.OK, "{}"))
        try {
            withTimeout(5.seconds) { client.auth.awaitInitialization() }
            assertIs<SessionStatus.Authenticated>(client.auth.sessionStatus.value)
            assertTrue(legacy.getStringOrNull(legacyKey).orEmpty().contains("access-token"))
            if (lockParent) assertFalse(Files.exists(parent.resolve("session.properties")))
            return legacy
        } finally {
            client.close()
            SyncManager.resumeSync()
            AuthRepository.releaseHeldSessionState()
            while (AuthSessionRetention.consumeStoredClear()) {
            }
            directory.toFile().deleteRecursively()
        }
    }

    private suspend fun refresh(
        status: HttpStatusCode,
        body: String,
        transport: MockEngine = engine(status, body),
    ): RefreshOutcome {
        val directory = Files.createTempDirectory("session-refresh")
        val legacy = PropertiesSettings(Properties().apply { setProperty(SESSION_KEY, expiredSession()) })
        val store = DesktopStorage.Store(directory.resolve("session.properties"), memoryVault(), { true }, {})
        val library = directory.resolve("nuvio_library.properties")
        Files.writeString(library, "library=kept")
        val settings = DesktopSessionSettings(legacy, store) {}
        val client = client(settings, transport)
        return try {
            withTimeout(5.seconds) { client.auth.awaitInitialization() }
            RefreshOutcome(
                status = client.auth.sessionStatus.value,
                loaded = client.auth.sessionManager.loadSession(),
                legacy = legacy,
                store = store,
                library = library,
                close = {
                    client.close()
                    SyncManager.resumeSync()
                    AuthRepository.releaseHeldSessionState()
                    while (AuthSessionRetention.consumeStoredClear()) {
                    }
                    directory.toFile().deleteRecursively()
                },
            )
        } catch (error: Throwable) {
            client.close()
            SyncManager.resumeSync()
            AuthRepository.releaseHeldSessionState()
            directory.toFile().deleteRecursively()
            throw error
        }
    }

    private fun client(settings: DesktopSessionSettings, engine: MockEngine) = createSupabaseClient(
        supabaseUrl = "https://example.supabase.co",
        supabaseKey = "publishable-key",
    ) {
        httpEngine = engine
        httpConfig { installRetainedAuthGuard() }
        install(Auth) {
            autoSetupPlatform = false
            alwaysAutoRefresh = true
            autoLoadFromStorage = true
            sessionManager = RetainedSessionManager(SettingsSessionManager(settings = settings, key = SESSION_KEY))
            codeVerifierCache = SettingsCodeVerifierCache(settings = settings, key = VERIFIER_KEY)
            retryDelay = 50.milliseconds
        }
    }

    private fun engine(status: HttpStatusCode, body: String) = MockEngine {
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private fun freshSession(): String =
        """{"access_token":"access-token","refresh_token":"refresh-token","expires_in":3600,"token_type":"mac","user":{"id":"user-1","aud":"authenticated"}}"""

    private fun expiredSession(): String =
        """{"access_token":"access-token","refresh_token":"refresh-token","expires_in":3600,"token_type":"mac","expiresAt":"2020-01-01T00:00:00Z","user":{"id":"user-1","aud":"authenticated"}}"""

    private fun denied(message: String) = object : CredentialVault {
        override fun read(account: String): String? = error(message)
        override fun write(account: String, value: String) = error(message)
        override fun remove(account: String) = Unit
    }

    private fun memoryVault() = object : CredentialVault {
        private val saved = mutableMapOf<String, String>()
        override fun read(account: String): String? = saved[account]
        override fun write(account: String, value: String) { saved[account] = value }
        override fun remove(account: String) { saved.remove(account) }
    }

    private class RefreshOutcome(
        val status: SessionStatus,
        val loaded: io.github.jan.supabase.auth.user.UserSession?,
        val legacy: PropertiesSettings,
        val store: DesktopStorage.Store,
        val library: java.nio.file.Path,
        val close: suspend () -> Unit,
    )

    private companion object {
        const val SESSION_KEY = "sb-example-session"
        const val VERIFIER_KEY = "sb-example-supabase_code_verifier"
    }
}
