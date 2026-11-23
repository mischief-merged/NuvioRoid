package com.nuvio.app.core.storage

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.nuvio.app.AppEnvironment
import com.nuvio.app.AppGate
import com.nuvio.app.AppScreenTab
import com.nuvio.app.QuitWithDownloadsDialog
import com.nuvio.app.rememberDesktopQuitCoordinator
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.AuthStorage
import com.nuvio.app.core.auth.DeviceLinkAuthRepository
import com.nuvio.app.core.auth.DeviceLinkAuthState
import com.nuvio.app.core.network.ServerCapabilities
import com.nuvio.app.core.network.ServerConfiguration
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.ServerConfigurationStorage
import com.nuvio.app.core.network.ServerDiscoveryPolicy
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.network.TrustedHomeServers
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonStorage
import com.nuvio.app.features.auth.ServerConnectionController
import com.nuvio.app.features.auth.ServerSwitchFailure
import com.nuvio.app.features.auth.ServerSwitchResult
import com.nuvio.app.features.downloads.ConnectionHold
import com.nuvio.app.features.downloads.DesktopDownloads
import com.nuvio.app.features.downloads.DownloadCompanions
import com.nuvio.app.features.downloads.AutoDownloadRemovalResult
import com.nuvio.app.features.downloads.DownloadDeleteResult
import com.nuvio.app.features.downloads.DownloadEnqueueResult
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.FileLookup
import com.nuvio.app.features.downloads.FileOwnership
import com.nuvio.app.features.downloads.DownloadMover
import com.nuvio.app.features.downloads.DownloadPlatformRequest
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadedFileFacts
import com.nuvio.app.features.downloads.DownloadsBackend
import com.nuvio.app.features.downloads.DownloadsCodec
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.DownloadsTaskHandle
import com.nuvio.app.features.downloads.downloadRuntimeLookup
import com.nuvio.app.features.downloads.EntryState
import com.nuvio.app.features.downloads.FrameSamples
import com.nuvio.app.features.downloads.ItemOutcome
import com.nuvio.app.features.downloads.MoveEntry
import com.nuvio.app.features.downloads.MoveItem
import com.nuvio.app.features.downloads.MoveJournal
import com.nuvio.app.features.downloads.MoveKind
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.party.PartyApi
import com.nuvio.app.features.party.PartyMedia
import com.nuvio.app.features.party.PartyRepository
import com.nuvio.app.features.player.AudioTrack
import com.nuvio.app.features.player.BindPlayerQuitAndDisposeEffects
import com.nuvio.app.features.player.BindPlayerUiVisibilityEffects
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerScreenArgs
import com.nuvio.app.features.player.PlayerScreenRuntime
import com.nuvio.app.features.player.SubtitleTrack
import com.nuvio.app.features.player.persistPlaybackProgressTick
import com.nuvio.app.features.player.updatePlaybackSnapshot
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.profiles.AvatarRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.ProfileStorage
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressStorage
import com.nuvio.app.navigation.TabsRoute
import com.sun.net.httpserver.HttpServer
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAccountDataLifecycleTest {
    private lateinit var tempBase: Path
    private lateinit var rootDir: Path
    private lateinit var cacheDir: Path

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        tempBase = Files.createTempDirectory("nuvio-account-lifecycle-test").toRealPath()
        val scratchDir = tempBase.resolve("scratch").also { it.createDirectories() }
        rootDir = tempBase.resolve("data").also { it.createDirectories() }
        cacheDir = tempBase.resolve("cache").also { it.createDirectories() }
        DesktopStorage.setRootDirForTest(scratchDir, cacheDir)
        ServerConnectionController.resetDiscovery()
        DeviceLinkAuthRepository.cancel()
        AuthRepository.resetForTest()
        ProfileRepository.clearInMemory()
        AddonRepository.clearLocalState()
        PluginRepository.setLocalPluginSearchPaused(false)
        LibraryRepository.clearLocalState()
        WatchProgressRepository.resetForTest()
        DesktopDownloads.resetForTest()
        DesktopAccountDataLifecycle.resetForTest()
        SupabaseProvider.resetForTest()
        DesktopStorage.setRootDirForTest(rootDir, cacheDir)
        ServerConfigurationRepository.saveCustom(
            ServerConfiguration(
                backendUrl = "https://default.nuvio.example",
                publishableKey = "default-test-key",
                capabilities = ServerCapabilities(emailPasswordAuth = true, tvLogin = true, watchParty = true),
                isCustom = true,
            ),
        )
        allowLocalFixtureServer()
    }

    /** The server-switch cases discover a server on this computer. The list follows the current data folder. */
    private fun allowLocalFixtureServer() {
        listOf("127.0.0.1", "localhost", "::1").forEach { host ->
            TrustedHomeServers.add(if (':' in host) "http://[$host]" else host)
        }
    }

    @AfterTest
    fun tearDown(): Unit = runBlocking {
        val scratchDir = if (::tempBase.isInitialized) {
            tempBase.resolve("scratch").also { it.createDirectories() }
        } else {
            null
        }
        if (scratchDir != null) {
            DesktopStorage.setRootDirForTest(scratchDir, cacheDir)
        }
        ServerConnectionController.resetDiscovery()
        DeviceLinkAuthRepository.cancel()
        PartyRepository.stopForSignOut()
        AuthRepository.resetForTest()
        ProfileRepository.clearInMemory()
        AddonRepository.clearLocalState()
        PluginRepository.setLocalPluginSearchPaused(false)
        LibraryRepository.clearLocalState()
        WatchProgressRepository.resetForTest()
        DesktopDownloads.resetForTest()
        DesktopAccountDataLifecycle.resetForTest()
        SupabaseProvider.resetForTest()
        ServerConfigurationRepository.useOfficial()
        ServerConnectionController.resetDiscovery()
        DesktopStorage.setRootDirForTest(null, null)
        if (::tempBase.isInitialized) {
            tempBase.toFile().deleteRecursively()
        }
    }

    @Test
    fun mismatch_email_sign_in_never_places_b_token_in_shared_auth_or_storage(): Unit = runBlocking {
        val logoutTokens = Collections.synchronizedList(mutableListOf<String?>())
        val savedTokens = Collections.synchronizedList(mutableListOf<String>())
        val sessionManager = TrackingSessionManager(onSave = { savedTokens += it.accessToken })
        val activeUser = AtomicReference(TestUser("user-a", "a@example.com", "token-a"))
        sessionManager.seed(
            UserSession(
                accessToken = "token-a",
                refreshToken = "refresh-a",
                expiresIn = 3_600L,
                tokenType = "bearer",
                user = UserInfo(id = "user-a", aud = "authenticated", email = "a@example.com"),
            ),
        )
        SupabaseProvider.engineFactory = {
            MockEngine { request ->
                val path = request.url.encodedPath
                val auth = request.headers[HttpHeaders.Authorization]
                when {
                    path.endsWith("/auth/v1/token") -> respondJson(tokenSessionJson(activeUser.get()))
                    path.endsWith("/auth/v1/user") -> {
                        val u = if (auth == "Bearer token-b") {
                            TestUser("user-b", "b@example.com", "token-b")
                        } else {
                            TestUser("user-a", "a@example.com", "token-a")
                        }
                        respondJson(userJson(u))
                    }
                    path.endsWith("/auth/v1/logout") -> {
                        logoutTokens += auth
                        respond("", HttpStatusCode.NoContent)
                    }
                    else -> respondJson("[]")
                }
            }
        }
        SupabaseProvider.sessionManagerOverride = sessionManager
        Files.writeString(rootDir.resolve("nuvio_library.properties"), "library_payload_1=user-a-items")
        val exits = AtomicInteger(0)
        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { rootDir },
            storedSessionUserId = { sessionManager.loadSession()?.user?.id },
            exitApp = { exits.incrementAndGet() },
        )
        AuthRepository.initialize()
        awaitAuthenticatedUser("user-a")
        assertTrue(AuthRepository.signOut().isSuccess)
        savedTokens.clear()

        activeUser.set(TestUser("user-b", "b@example.com", "token-b"))
        assertTrue(AuthRepository.signInWithEmail("b@example.com", "pw-b").isSuccess)

        assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
        assertEquals(
            "The data on this Mac belongs to another account. Sign in with that account, or choose Start fresh.",
            AuthRepository.error.value,
        )
        assertNull(SupabaseProvider.client.auth.currentSessionOrNull())
        assertNull(sessionManager.loadSession())
        assertFalse(savedTokens.contains("token-b"), "B's token must never be written to shared session storage")
        assertTrue(logoutTokens.contains("Bearer token-b"), "Throwaway client must revoke B's session")
        assertEquals(0, exits.get())
        assertEquals("library_payload_1=user-a-items", Files.readString(rootDir.resolve("nuvio_library.properties")))
        assertEquals(
            StampState.Valid(StampOwner.Remote("https://default.nuvio.example", "user-a")),
            DesktopAccountDataLifecycle.readStamp(rootDir),
        )
    }

    @Test
    fun mismatch_device_link_never_places_b_token_in_shared_auth_or_storage(): Unit = runBlocking {
        val logoutTokens = Collections.synchronizedList(mutableListOf<String?>())
        val savedTokens = Collections.synchronizedList(mutableListOf<String>())
        val recordedCalls = Collections.synchronizedList(mutableListOf<RecordedCall>())
        val sessionManager = TrackingSessionManager(onSave = { savedTokens += it.accessToken })
        sessionManager.seed(
            UserSession(
                accessToken = "token-a",
                refreshToken = "refresh-a",
                expiresIn = 3_600L,
                tokenType = "bearer",
                user = UserInfo(id = "user-a", aud = "authenticated", email = "a@example.com"),
            ),
        )
        SupabaseProvider.engineFactory = {
            MockEngine { request ->
                val path = request.url.encodedPath
                val auth = request.headers[HttpHeaders.Authorization]
                recordedCalls += RecordedCall(path = path, authorization = auth)
                when {
                    path.endsWith("/auth/v1/user") -> {
                        val u = if (auth == "Bearer token-b") {
                            TestUser("user-b", "b@example.com", "token-b")
                        } else {
                            TestUser("user-a", "a@example.com", "token-a")
                        }
                        respondJson(userJson(u))
                    }
                    path.endsWith("/auth/v1/logout") -> {
                        logoutTokens += auth
                        respond("", HttpStatusCode.NoContent)
                    }
                    path.contains("/rpc/start_device_login_session") -> respondJson(
                        """[{"device_code":"dev-123","user_code":"ABCDEF","verification_uri_complete":"https://nuvio.tv/link?c=ABCDEF","poll_interval_seconds":2}]""",
                    )
                    path.contains("/rpc/poll_tv_login_session") -> respondJson("""[{"status":"approved"}]""")
                    path.contains("/functions/v1/tv-logins-exchange") -> respondJson(
                        """{"access_token":"token-b","refresh_token":"refresh-b","token_type":"bearer","expires_in":3600,"user":{"id":"user-b","aud":"authenticated","email":"b@example.com"}}""",
                    )
                    else -> respondJson("[]")
                }
            }
        }
        SupabaseProvider.sessionManagerOverride = sessionManager
        val exits = AtomicInteger(0)
        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { rootDir },
            storedSessionUserId = { sessionManager.loadSession()?.user?.id },
            exitApp = { exits.incrementAndGet() },
        )
        AuthRepository.initialize()
        awaitAuthenticatedUser("user-a")
        assertTrue(AuthRepository.signOut().isSuccess)
        savedTokens.clear()

        DeviceLinkAuthRepository.start()
        withTimeout(6_000) {
            AuthRepository.error.first { it != null }
        }

        assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
        assertEquals(
            "The data on this Mac belongs to another account. Sign in with that account, or choose Start fresh.",
            AuthRepository.error.value,
        )
        assertNull(SupabaseProvider.client.auth.currentSessionOrNull())
        assertNull(sessionManager.loadSession())
        assertFalse(savedTokens.contains("token-b"), "B's token must never reach shared session storage")
        assertTrue(logoutTokens.contains("Bearer token-b"), "Throwaway device-link client must revoke B's session")
        assertTrue(recordedCalls.any { it.path.contains("/rpc/start_device_login_session") })
        assertTrue(recordedCalls.any { it.path.contains("/rpc/poll_tv_login_session") })
        assertTrue(recordedCalls.any { it.path.contains("/functions/v1/tv-logins-exchange") })
        assertEquals(DeviceLinkAuthState.Idle, DeviceLinkAuthRepository.state.value)
        assertEquals(0, exits.get())
    }

    @Test
    fun mismatch_startup_restore_never_places_b_token_in_shared_auth_or_storage(): Unit = runBlocking {
        Files.writeString(
            rootDir.resolve(DesktopStorage.OwnerStampFileName),
            """{"type":"remote","serverUrl":"https://default.nuvio.example","userId":"user-a"}""",
        )
        Files.writeString(rootDir.resolve("nuvio_library.properties"), "library_payload_1=user-a-items")
        val logoutTokens = Collections.synchronizedList(mutableListOf<String?>())
        val savedTokens = Collections.synchronizedList(mutableListOf<String>())
        val sessionManager = TrackingSessionManager(onSave = { savedTokens += it.accessToken })
        sessionManager.seed(
            UserSession(
                accessToken = "token-b",
                refreshToken = "refresh-b",
                expiresIn = 3_600L,
                tokenType = "bearer",
                user = UserInfo(id = "user-b", aud = "authenticated", email = "b@example.com"),
            ),
        )
        SupabaseProvider.engineFactory = {
            MockEngine { request ->
                val path = request.url.encodedPath
                val auth = request.headers[HttpHeaders.Authorization]
                when {
                    path.endsWith("/auth/v1/user") -> respondJson(userJson(TestUser("user-b", "b@example.com", "token-b")))
                    path.endsWith("/auth/v1/logout") -> {
                        logoutTokens += auth
                        respond("", HttpStatusCode.NoContent)
                    }
                    else -> respondJson("[]")
                }
            }
        }
        SupabaseProvider.sessionManagerOverride = sessionManager
        val exits = AtomicInteger(0)
        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { rootDir },
            storedSessionUserId = { sessionManager.loadSession()?.user?.id },
            exitApp = { exits.incrementAndGet() },
        )
        AuthRepository.initialize()
        withTimeout(5_000) {
            AuthRepository.state.first { it is AuthState.Unauthenticated }
        }

        assertNull(SupabaseProvider.client.auth.currentSessionOrNull())
        assertNull(sessionManager.loadSession())
        assertTrue(savedTokens.isEmpty(), "Shared client must never re-save B's session during startup check")
        assertEquals(
            "The data on this Mac belongs to another account. Sign in with that account, or choose Start fresh.",
            AuthRepository.error.value,
        )
        assertTrue(logoutTokens.contains("Bearer token-b"))
        assertEquals(0, exits.get())
        assertEquals("library_payload_1=user-a-items", Files.readString(rootDir.resolve("nuvio_library.properties")))
    }

    @Test
    fun sign_up_without_session_stays_signed_out_with_notice_and_stamps_nothing_while_autoconfirmed_stamps_before_import(): Unit = runBlocking {
        val autoConfirm = AtomicBoolean(false)
        val stampOnImport = AtomicReference<StampState?>(null)
        val logoutTokens = Collections.synchronizedList(mutableListOf<String?>())
        val sessionManager = TrackingSessionManager(
            onSave = {
                stampOnImport.set(DesktopAccountDataLifecycle.readStamp(rootDir))
            },
        )
        SupabaseProvider.engineFactory = {
            MockEngine { request ->
                val path = request.url.encodedPath
                val auth = request.headers[HttpHeaders.Authorization]
                when {
                    path.endsWith("/auth/v1/signup") -> {
                        if (!autoConfirm.get()) {
                            respondJson("""{"id":"pending-user","aud":"authenticated","email":"new@example.com"}""")
                        } else {
                            respondJson(tokenSessionJson(TestUser("user-a", "a@example.com", "token-signup-a")))
                        }
                    }
                    path.endsWith("/auth/v1/user") -> respondJson(userJson(TestUser("user-a", "a@example.com", "token-signup-a")))
                    path.endsWith("/auth/v1/logout") -> {
                        logoutTokens += auth
                        respond("", HttpStatusCode.NoContent)
                    }
                    else -> respondJson("[]")
                }
            }
        }
        SupabaseProvider.sessionManagerOverride = sessionManager
        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { rootDir },
            storedSessionUserId = { null },
            exitApp = {},
        )
        AuthRepository.initialize()
        withTimeout(5_000) { AuthRepository.state.first { it is AuthState.Unauthenticated } }
        assertEquals(
            StampState.Valid(StampOwner.Vacant),
            DesktopAccountDataLifecycle.readStamp(rootDir),
            "Unexpected entries in empty rootDir: ${DesktopAccountDataLifecycle.listArchivableEntries(rootDir)}",
        )

        // 1. Sign-up with email confirmation required (no session returned): stays signed out, stamps nothing, sets notice.
        assertTrue(AuthRepository.signUpWithEmail("new@example.com", "secret123").isSuccess)
        assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
        assertEquals("Check your email to confirm your account, then sign in.", AuthRepository.notice.value)
        assertNull(AuthRepository.error.value)
        assertNull(SupabaseProvider.client.auth.currentSessionOrNull())
        assertNull(sessionManager.loadSession())
        assertEquals(StampState.Valid(StampOwner.Vacant), DesktopAccountDataLifecycle.readStamp(rootDir))

        // 2. Auto-confirmed sign-up returns a session: stamps owner BEFORE importSession writes to sessionManager, never calls signOut on throwaway.
        autoConfirm.set(true)
        assertTrue(AuthRepository.signUpWithEmail("a@example.com", "secret123").isSuccess)
        awaitAuthenticatedUser("user-a")
        assertEquals(
            StampState.Valid(StampOwner.Remote("https://default.nuvio.example", "user-a")),
            stampOnImport.get(),
            "Owner stamp must already be durable on disk before importSession saves the session",
        )
        assertTrue(logoutTokens.isEmpty(), "Approved throwaway sign-up must close without calling signOut")
    }

    @Test
    fun same_owner_after_lost_session_and_sign_out_keeps_every_file_and_runs_real_sync(): Unit = runBlocking {
        val currentToken = AtomicReference("token-a-initial")
        val returnUpdatedSyncData = AtomicBoolean(false)
        val sessionManager = TrackingSessionManager()
        sessionManager.seed(
            UserSession(
                accessToken = "token-a-initial",
                refreshToken = "refresh-a",
                expiresIn = 3_600L,
                tokenType = "bearer",
                user = UserInfo(id = "user-a", aud = "authenticated", email = "a@example.com"),
            ),
        )
        SupabaseProvider.engineFactory = {
            MockEngine { request ->
                val path = request.url.encodedPath
                val auth = request.headers[HttpHeaders.Authorization]
                when {
                    path.endsWith("/auth/v1/token") -> respondJson(
                        tokenSessionJson(TestUser("user-a", "a@example.com", currentToken.get())),
                    )
                    path.endsWith("/auth/v1/user") -> respondJson(
                        userJson(TestUser("user-a", "a@example.com", currentToken.get())),
                    )
                    path.endsWith("/auth/v1/logout") -> respond("", HttpStatusCode.NoContent)
                    path.endsWith("/rest/v1/addons") -> {
                        if (returnUpdatedSyncData.get() && auth == "Bearer token-a-reauthenticated") {
                            respondJson("""[{"url":"https://synced.example/manifest.json","name":"Synced Addon","enabled":false,"sort_order":0}]""")
                        } else {
                            respondJson("""[{"url":"https://addon.example/manifest.json","name":"Initial Addon","enabled":false,"sort_order":0}]""")
                        }
                    }
                    path.endsWith("/rpc/sync_get_library_delta_cursor") -> respondJson("1")
                    path.endsWith("/rpc/sync_pull_library_delta") -> {
                        if (returnUpdatedSyncData.get() && auth == "Bearer token-a-reauthenticated") {
                            respondJson("""[{"event_id":2,"operation":"UPSERT","content_id":"tt0816692","content_type":"movie","name":"Interstellar","added_at":1700000005000}]""")
                        } else {
                            respondJson("[]")
                        }
                    }
                    path.endsWith("/rpc/sync_pull_library") -> {
                        if (returnUpdatedSyncData.get() && auth == "Bearer token-a-reauthenticated") {
                            respondJson("""[{"content_id":"tt0816692","content_type":"movie","name":"Interstellar","added_at":1700000005000}]""")
                        } else {
                            respondJson("[]")
                        }
                    }
                    else -> respondJson("[]")
                }
            }
        }
        SupabaseProvider.sessionManagerOverride = sessionManager

        val expectedFiles = seedAllAccountDataFiles(
            userId = "user-a",
            profilesJson = """{"userId":"user-a","activeProfileIndex":1,"profiles":[{"id":"p1","user_id":"user-a","profile_index":1,"name":"Owner A","avatar_color_hex":"#112233"}]}""",
        )
        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { rootDir },
            storedSessionUserId = { sessionManager.loadSession()?.user?.id },
            exitApp = {},
        )
        ProfileRepository.loadCachedProfiles()
        AuthRepository.initialize()
        awaitAuthenticatedUser("user-a")
        ProfileRepository.ensureLoaded("user-a")
        assertEquals(1, ProfileRepository.state.value.profiles.size)

        // 1. Lose session remotely: every file and loaded profiles remain intact.
        val invalidated = AuthRepository.signOutIfSessionInvalid(
            error = IllegalStateException("User from sub claim in JWT does not exist"),
            source = "library-sync",
        )
        assertTrue(invalidated)
        assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
        assertTrue(AuthStorage.loadFlag("signed_out_notice"))
        assertEquals(1, ProfileRepository.state.value.profiles.size, "Lost session on desktop must not clear loaded profiles")
        expectedFiles.forEach { (name, expectedContent) ->
            val file = rootDir.resolve(name)
            assertTrue(file.exists(), "Expected $name to remain on disk after lost session")
            assertEquals(expectedContent, Files.readString(file), "Expected $name contents to remain intact")
        }

        // 2. Re-sign in as the same owner and run a real sync with the fresh session token.
        currentToken.set("token-a-reauthenticated")
        returnUpdatedSyncData.set(true)
        assertTrue(AuthRepository.signInWithEmail("a@example.com", "pw-a").isSuccess)
        awaitAuthenticatedUser("user-a")
        assertFalse(AuthStorage.loadFlag("signed_out_notice"))

        AddonRepository.initialize()
        LibraryRepository.ensureLoaded()
        AddonRepository.pullFromServer(1)
        LibraryRepository.pullFromServer(1)

        assertEquals(
            listOf("https://synced.example/manifest.json"),
            AddonRepository.uiState.value.addons.map { it.manifestUrl },
        )
        assertTrue(LibraryRepository.isSaved("tt0816692", "movie"), "Real library sync must apply server item after re-authentication")

        // 3. Explicit sign-out also preserves all local files and in-memory profiles.
        assertTrue(AuthRepository.signOut().isSuccess)
        assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
        assertEquals(1, ProfileRepository.state.value.profiles.size)
        assertTrue(LibraryRepository.isSaved("tt0816692", "movie"))
    }

    @Test
    fun server_canonical_url_preserves_path_case_and_desktop_server_switch_stages_until_restart(): Unit = runBlocking {
        // 1. Canonical URL normalization matches ServerDiscoveryPolicy: scheme/host lowercased, default port stripped, path case preserved.
        assertEquals(
            "https://example.com/Team/%2FSub",
            ServerDiscoveryPolicy.canonicalBackendUrlOrNull("HTTPS://Example.COM:443/Team/%2FSub/"),
        )
        assertEquals(
            "http://example.com/team",
            ServerDiscoveryPolicy.canonicalBackendUrlOrNull("http://EXAMPLE.COM:80/team/"),
        )

        val teamUpperServer = ServerConfiguration(
            backendUrl = ServerDiscoveryPolicy.canonicalBackendUrlOrNull("HTTPS://Example.COM:443/Team/")!!,
            publishableKey = "team-key",
            capabilities = ServerCapabilities(emailPasswordAuth = true, tvLogin = true),
            isCustom = true,
        )
        assertTrue(ServerConfigurationRepository.saveCustom(teamUpperServer))
        val harness = installMockBackend(activeUser = TestUser("shared-id", "u@example.com", "tok-1"))
        val exits = installDesktopLifecycle(harness)
        AuthRepository.initialize()
        awaitAuthenticatedUser("shared-id")
        Files.writeString(rootDir.resolve("nuvio_library.properties"), "library_payload_1=team-upper-data")

        // Distinct path case (/team vs /Team) is a distinct owner.
        val lowerDecision = AccountDataLifecycle.evaluateRemoteSignIn(
            AccountOwnerCandidate.Remote(serverUrl = "https://example.com/team", userId = "shared-id"),
        )
        assertTrue(lowerDecision is RemoteSignInDecision.Mismatch)
        val sameCaseWithPortDecision = AccountDataLifecycle.evaluateRemoteSignIn(
            AccountOwnerCandidate.Remote(serverUrl = "HTTPS://EXAMPLE.COM:443/Team/", userId = "shared-id"),
        )
        assertEquals(RemoteSignInDecision.Approved, sameCaseWithPortDecision)

        // 2. Real ServerConnectionController.discover + connectDiscovered on desktop stages the new server, clears session, keeps active in-process server and data, and quits normally.
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = httpServer.address.port
        val newBackendUrl = "http://127.0.0.1:$port/NextServer"
        httpServer.createContext("/.well-known/nuvio") { exchange ->
            val body = """
                {
                  "version": 1,
                  "service": "nuvio",
                  "self_hosted": true,
                  "backend_url": "$newBackendUrl/",
                  "publishable_key": "next-pub-key",
                  "capabilities": { "email_password_auth": true, "tv_login": false, "watch_party": false }
                }
            """.trimIndent().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        httpServer.start()
        try {
            ServerConnectionController.discover("http://127.0.0.1:$port")
            withTimeout(5_000) {
                ServerConnectionController.state.first { it.discoveredServer != null }
            }
            ServerConnectionController.connectDiscovered()
            withTimeout(5_000) {
                while (exits.get() == 0) delay(20)
            }

            // Running process kept active server untouched, cleared session, wrote NO archive transition journal, kept owner stamp & data.
            assertEquals("https://example.com/Team", ServerConfigurationRepository.active.value.backendUrl)
            assertEquals(newBackendUrl, ServerConfigurationStorage.loadCustom()?.backendUrl)
            assertNull(harness.sessionManager.loadSession())
            assertFalse(rootDir.resolve(DesktopStorage.TransitionJournalFileName).exists())
            assertEquals("library_payload_1=team-upper-data", Files.readString(rootDir.resolve("nuvio_library.properties")))
            assertEquals(
                StampState.Valid(StampOwner.Remote("https://example.com/Team", "shared-id")),
                DesktopAccountDataLifecycle.readStamp(rootDir),
            )

            // Reopen on next launch: active server reloads to newBackendUrl; signing in with same userId on new server mismatches until Start fresh.
            simulateNextLaunch(harness)
            assertEquals(newBackendUrl, ServerConfigurationRepository.active.value.backendUrl)
            harness.activeUser.set(TestUser("shared-id", "u@example.com", "tok-2"))
            assertTrue(AuthRepository.signInWithEmail("u@example.com", "pw").isSuccess)
            assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
            assertEquals(
                "The data on this Mac belongs to another account. Sign in with that account, or choose Start fresh.",
                AuthRepository.error.value,
            )
        } finally {
            httpServer.stop(0)
        }
    }

    @Test
    fun unstamped_folder_own_sign_in_adopts_after_yes_and_survives_restart_while_no_archives(): Unit = runBlocking {
        // Part A: Unstamped folder with real data and no owner evidence -> prompts once; Yes adopts and survives restart.
        Files.writeString(rootDir.resolve("nuvio_library.properties"), "library_payload_1=existing-mac-library")
        ProfileStorage.savePayload(
            """{"userId":"","activeProfileIndex":1,"profiles":[{"id":"p1","userId":"","profileIndex":1,"name":"Local Profile","avatarColorHex":"#112233"}]}""",
        )
        val logoutTokensA = Collections.synchronizedList(mutableListOf<String?>())
        val savedTokensA = Collections.synchronizedList(mutableListOf<String>())
        val sessionManagerA = TrackingSessionManager(onSave = { savedTokensA += it.accessToken })
        SupabaseProvider.engineFactory = {
            MockEngine { request ->
                val path = request.url.encodedPath
                val auth = request.headers[HttpHeaders.Authorization]
                when {
                    path.endsWith("/auth/v1/token") -> respondJson(tokenSessionJson(TestUser("user-a", "a@example.com", "token-a")))
                    path.endsWith("/auth/v1/user") -> respondJson(userJson(TestUser("user-a", "a@example.com", "token-a")))
                    path.endsWith("/auth/v1/logout") -> {
                        logoutTokensA += auth
                        respond("", HttpStatusCode.NoContent)
                    }
                    else -> respondJson("[]")
                }
            }
        }
        SupabaseProvider.sessionManagerOverride = sessionManagerA
        val exitsA = AtomicInteger(0)
        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { rootDir },
            storedSessionUserId = { null },
            exitApp = { exitsA.incrementAndGet() },
        )
        assertEquals(StampState.Valid(StampOwner.Unknown), DesktopAccountDataLifecycle.readStamp(rootDir))
        ProfileRepository.loadCachedProfiles()
        AuthRepository.initialize()
        withTimeout(5_000) { AuthRepository.state.first { it is AuthState.Unauthenticated } }

        assertTrue(AuthRepository.signInWithEmail("a@example.com", "pw-a").isSuccess)
        assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
        assertNotNull(AuthRepository.pendingAdoption.value, "First sign-in over unknown real data must ask for adoption")
        assertNull(SupabaseProvider.client.auth.currentSessionOrNull(), "Token must remain quarantined until Yes is chosen")
        assertTrue(savedTokensA.isEmpty())

        assertTrue(AuthRepository.confirmPendingAdoption().isSuccess)
        awaitAuthenticatedUser("user-a")
        assertNull(AuthRepository.pendingAdoption.value)
        assertTrue(logoutTokensA.isEmpty(), "Adopted throwaway session must close without signOut")
        assertEquals(
            StampState.Valid(StampOwner.Remote("https://default.nuvio.example", "user-a")),
            DesktopAccountDataLifecycle.readStamp(rootDir),
        )
        assertEquals("library_payload_1=existing-mac-library", Files.readString(rootDir.resolve("nuvio_library.properties")))
        assertTrue("\"userId\":\"user-a\"" in ProfileStorage.loadPayload().orEmpty())

        // Survives restart without prompting again.
        AuthRepository.resetForTest()
        ProfileRepository.clearInMemory()
        DesktopAccountDataLifecycle.resetForTest()
        DesktopStorage.resetInMemoryStores()
        SupabaseProvider.reset()
        SupabaseProvider.sessionManagerOverride = sessionManagerA
        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { rootDir },
            storedSessionUserId = { sessionManagerA.loadSession()?.user?.id },
            exitApp = { exitsA.incrementAndGet() },
        )
        ProfileRepository.loadCachedProfiles()
        AuthRepository.initialize()
        awaitAuthenticatedUser("user-a")
        assertNull(AuthRepository.pendingAdoption.value)
        assertEquals("library_payload_1=existing-mac-library", Files.readString(rootDir.resolve("nuvio_library.properties")))

        // Part B: Unstamped folder with real data -> No declines adoption, revokes throwaway session, and archives via Start fresh.
        val rootNo = tempBase.resolve("data-adopt-no").also { it.createDirectories() }
        AuthRepository.resetForTest()
        ProfileRepository.clearInMemory()
        DesktopAccountDataLifecycle.resetForTest()
        SupabaseProvider.resetForTest()
        DesktopStorage.setRootDirForTest(rootNo, cacheDir)
        ServerConfigurationRepository.saveCustom(
            ServerConfiguration(
                backendUrl = "https://default.nuvio.example",
                publishableKey = "default-test-key",
                capabilities = ServerCapabilities(emailPasswordAuth = true, tvLogin = true),
                isCustom = true,
            ),
        )
        Files.writeString(rootNo.resolve("nuvio_library.properties"), "library_payload_1=decline-me")
        val logoutTokensNo = Collections.synchronizedList(mutableListOf<String?>())
        val sessionManagerNo = TrackingSessionManager()
        SupabaseProvider.engineFactory = {
            MockEngine { request ->
                val path = request.url.encodedPath
                val auth = request.headers[HttpHeaders.Authorization]
                when {
                    path.endsWith("/auth/v1/token") -> respondJson(tokenSessionJson(TestUser("user-a", "a@example.com", "token-decline")))
                    path.endsWith("/auth/v1/user") -> respondJson(userJson(TestUser("user-a", "a@example.com", "token-decline")))
                    path.endsWith("/auth/v1/logout") -> {
                        logoutTokensNo += auth
                        respond("", HttpStatusCode.NoContent)
                    }
                    else -> respondJson("[]")
                }
            }
        }
        SupabaseProvider.sessionManagerOverride = sessionManagerNo
        val exitsNo = AtomicInteger(0)
        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { rootNo },
            storedSessionUserId = { null },
            exitApp = { exitsNo.incrementAndGet() },
        )
        AuthRepository.initialize()
        withTimeout(5_000) { AuthRepository.state.first { it is AuthState.Unauthenticated } }

        assertTrue(AuthRepository.signInWithEmail("a@example.com", "pw-a").isSuccess)
        assertNotNull(AuthRepository.pendingAdoption.value)
        assertTrue(AuthRepository.declinePendingAdoption().isSuccess)
        assertTrue(logoutTokensNo.contains("Bearer token-decline"))
        assertEquals(1, exitsNo.get())
        assertTrue(rootNo.resolve(DesktopStorage.TransitionJournalFileName).exists())

        // Next launch archives into account-unknown-<ts>/ and stamps Vacant.
        AuthRepository.resetForTest()
        DesktopAccountDataLifecycle.resetForTest()
        DesktopStorage.resetInMemoryStores()
        SupabaseProvider.reset()
        SupabaseProvider.sessionManagerOverride = sessionManagerNo
        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { rootNo },
            storedSessionUserId = { null },
            nowEpochMs = { 1_700_000_000_000L },
            exitApp = {},
        )
        assertTrue(DesktopAccountDataLifecycle.didArchiveOnStartup)
        assertEquals(StampState.Valid(StampOwner.Vacant), DesktopAccountDataLifecycle.readStamp(rootNo))
        assertFalse(rootNo.resolve("nuvio_library.properties").exists())
        val archive = listArchiveDirs(rootNo).single()
        assertTrue(archive.fileName.toString().startsWith("account-unknown-"))
        assertEquals("library_payload_1=decline-me", Files.readString(archive.resolve("nuvio_library.properties")))
    }

    @Test
    fun upgrade_with_saved_login_or_profile_payload_conflict_empty_and_corrupt_stamp_rules(): Unit = runBlocking {
        // 1. Saved session adopts owner (even when expired), and an unreadable profile file beside a valid saved login does NOT create Unknown or Conflict.
        Files.writeString(rootDir.resolve("nuvio_library.properties"), "library_payload_1=legacy-a")
        ProfileStorage.savePayload("{unreadable-profile-json")
        val harness1 = installMockBackend(activeUser = null, rejectRefresh = true)
        harness1.sessionManager.seed(
            UserSession(
                accessToken = "expired-token-a",
                refreshToken = "expired-refresh-a",
                expiresIn = 0L,
                tokenType = "bearer",
                user = UserInfo(id = "user-a", aud = "authenticated", email = "a@example.com"),
            ),
        )
        installDesktopLifecycle(harness1)
        assertEquals(
            StampState.Valid(StampOwner.Remote("https://default.nuvio.example", "user-a")),
            DesktopAccountDataLifecycle.readStamp(rootDir),
            "Valid saved login must win even when the profile file is unreadable",
        )

        // 2. Conflicting saved session (user-a) vs profile payload (user-b) -> stamps Conflicted, preserves saved session, refuses ALL sign-ins without offering adoption, and recovers via Start fresh.
        AuthRepository.resetForTest()
        DesktopAccountDataLifecycle.resetForTest()
        SupabaseProvider.resetForTest()
        val rootConflict = tempBase.resolve("data-conflict").also { it.createDirectories() }
        DesktopStorage.setRootDirForTest(rootConflict, cacheDir)
        Files.writeString(rootConflict.resolve("nuvio_library.properties"), "library_payload_1=conflicted")
        ProfileStorage.savePayload("""{"userId":"user-b","activeProfileIndex":1,"profiles":[]}""")
        ServerConfigurationRepository.saveCustom(
            ServerConfiguration(
                backendUrl = "https://default.nuvio.example",
                publishableKey = "default-test-key",
                capabilities = ServerCapabilities(emailPasswordAuth = true, tvLogin = true),
                isCustom = true,
            ),
        )
        val harnessConflict = installMockBackend(activeUser = TestUser("user-a", "a@example.com", "token-a-conflict"))
        val conflictExits = installDesktopLifecycle(harnessConflict, customRoot = rootConflict)
        assertEquals(
            StampState.Valid(StampOwner.Conflicted(sessionUserId = "user-a", profileUserId = "user-b")),
            DesktopAccountDataLifecycle.readStamp(rootConflict),
        )
        AuthRepository.initialize()
        withTimeout(5_000) { AuthRepository.state.first { it is AuthState.Unauthenticated } }
        assertNull(AuthRepository.pendingAdoption.value, "Conflicting evidence must never offer adoption")
        assertNotNull(harnessConflict.sessionManager.loadSession(), "Startup refusal on conflict must not delete the saved session")

        for (candidate in listOf(
            TestUser("user-a", "a@example.com", "tok-a"),
            TestUser("user-b", "b@example.com", "tok-b"),
            TestUser("user-c", "c@example.com", "tok-c"),
        )) {
            harnessConflict.activeUser.set(candidate)
            assertTrue(AuthRepository.signInWithEmail(candidate.email, "pw").isSuccess)
            assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
            assertNull(AuthRepository.pendingAdoption.value, "Account ${candidate.id} must not be offered adoption on conflicted folder")
            assertNotNull(AuthRepository.error.value)
        }
        assertTrue(AuthRepository.startFresh().isSuccess)
        assertEquals(1, conflictExits.get())
        simulateNextLaunchWithClock(harnessConflict, fixedEpochMs = 1_700_000_000_000L, customRoot = rootConflict)
        assertEquals(StampState.Valid(StampOwner.Vacant), DesktopAccountDataLifecycle.readStamp(rootConflict))
        assertTrue(listArchiveDirs(rootConflict).single().fileName.toString().startsWith("account-conflict-"))

        // 3. Profile userId with NO saved login (e.g. after server switch on an older build): stamps ProfileUser (no server bound at launch),
        //    rejects other accounts as Mismatch, does not silently adopt on the switched server, and preserves any stored session while AskAdoption is open.
        AuthRepository.resetForTest()
        DesktopAccountDataLifecycle.resetForTest()
        SupabaseProvider.resetForTest()
        val rootProfileOnly = tempBase.resolve("data-profile-only").also { it.createDirectories() }
        DesktopStorage.setRootDirForTest(rootProfileOnly, cacheDir)
        Files.writeString(rootProfileOnly.resolve("nuvio_library.properties"), "library_payload_1=profile-owner-lib")
        ProfileStorage.savePayload(
            """{"userId":"user-a","activeProfileIndex":1,"profiles":[{"id":"p1","user_id":"user-a","profile_index":1,"name":"Owner A","avatar_color_hex":"#112233"}]}""",
        )
        ServerConfigurationRepository.saveCustom(
            ServerConfiguration(
                backendUrl = "https://switched-before-upgrade.example",
                publishableKey = "switched-key",
                capabilities = ServerCapabilities(emailPasswordAuth = true, tvLogin = true),
                isCustom = true,
            ),
        )
        val harnessProfile = installMockBackend(activeUser = null)
        installDesktopLifecycle(harnessProfile, customRoot = rootProfileOnly)
        assertEquals(
            StampState.Valid(StampOwner.ProfileUser("user-a")),
            DesktopAccountDataLifecycle.readStamp(rootProfileOnly),
            "Unstamped folder with profile userId and no saved login must not bind to whatever server was active at launch",
        )
        AuthRepository.initialize()
        withTimeout(5_000) { AuthRepository.state.first { it is AuthState.Unauthenticated } }

        // Another user (user-b) is immediately rejected with Mismatch, never offered adoption.
        harnessProfile.activeUser.set(TestUser("user-b", "b@example.com", "token-b"))
        assertTrue(AuthRepository.signInWithEmail("b@example.com", "pw-b").isSuccess)
        assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
        assertNull(AuthRepository.pendingAdoption.value)
        assertEquals(
            "The data on this Mac belongs to another account. Sign in with that account, or choose Start fresh.",
            AuthRepository.error.value,
        )

        // Even user-a on switched-before-upgrade.example is asked once (not silently bound to the switched server);
        // if startup restore encounters AskAdoption, it also keeps the stored session intact until Yes/No is chosen.
        harnessProfile.activeUser.set(TestUser("user-a", "a@example.com", "token-a-startup"))
        harnessProfile.sessionManager.seed(
            UserSession(
                accessToken = "token-a-startup",
                refreshToken = "refresh-a-startup",
                expiresIn = 3_600L,
                tokenType = "bearer",
                user = UserInfo(id = "user-a", aud = "authenticated", email = "a@example.com"),
            ),
        )
        AuthRepository.reinitialize()
        withTimeout(5_000) { AuthRepository.pendingAdoption.first { it != null } }
        assertNotNull(
            harnessProfile.sessionManager.loadSession(),
            "Showing adoption question on startup must not delete the saved session before Yes/No",
        )
        assertTrue(AuthRepository.confirmPendingAdoption().isSuccess)
        awaitAuthenticatedUser("user-a")
        assertEquals(
            StampState.Valid(StampOwner.Remote("https://switched-before-upgrade.example", "user-a")),
            DesktopAccountDataLifecycle.readStamp(rootProfileOnly),
        )

        // 4. Empty folder containing only nuvio_auth.properties adopts directly without asking.
        AuthRepository.resetForTest()
        WatchProgressRepository.resetForTest()
        DesktopAccountDataLifecycle.resetForTest()
        SupabaseProvider.resetForTest()
        val rootAuthOnly = tempBase.resolve("data-auth-only").also { it.createDirectories() }
        DesktopStorage.setRootDirForTest(rootAuthOnly, cacheDir)
        AuthStorage.saveFlag("signed_out_notice", true)
        assertTrue(rootAuthOnly.resolve("nuvio_auth.properties").exists())
        ServerConfigurationRepository.saveCustom(
            ServerConfiguration(
                backendUrl = "https://default.nuvio.example",
                publishableKey = "default-test-key",
                capabilities = ServerCapabilities(emailPasswordAuth = true, tvLogin = true),
                isCustom = true,
            ),
        )
        val harnessAuthOnly = installMockBackend(activeUser = null)
        installDesktopLifecycle(harnessAuthOnly, customRoot = rootAuthOnly)
        AuthRepository.initialize()
        withTimeout(5_000) { AuthRepository.state.first { it is AuthState.Unauthenticated } }
        harnessAuthOnly.activeUser.set(TestUser("user-c", "c@example.com", "token-c"))
        assertTrue(AuthRepository.signInWithEmail("c@example.com", "pw-c").isSuccess)
        awaitAuthenticatedUser("user-c")
        assertNull(AuthRepository.pendingAdoption.value, "Auth-flag store files alone must be treated as an empty folder")
        assertEquals(
            StampState.Valid(StampOwner.Remote("https://default.nuvio.example", "user-c")),
            DesktopAccountDataLifecycle.readStamp(rootAuthOnly),
        )

        // 5. Corrupt stamp blocks startup and refuses sign-in without modifying files.
        AuthRepository.resetForTest()
        WatchProgressRepository.resetForTest()
        DesktopAccountDataLifecycle.resetForTest()
        val rootCorrupt = tempBase.resolve("data-corrupt").also { it.createDirectories() }
        DesktopStorage.setRootDirForTest(rootCorrupt, cacheDir)
        Files.writeString(rootCorrupt.resolve("nuvio_library.properties"), "library_payload_1=precious")
        Files.writeString(rootCorrupt.resolve(DesktopStorage.OwnerStampFileName), "{corrupt-json")
        ServerConfigurationRepository.saveCustom(
            ServerConfiguration(
                backendUrl = "https://default.nuvio.example",
                publishableKey = "default-test-key",
                capabilities = ServerCapabilities(emailPasswordAuth = true, tvLogin = true),
                isCustom = true,
            ),
        )
        installDesktopLifecycle(harnessAuthOnly, customRoot = rootCorrupt)
        assertTrue(DesktopAccountDataLifecycle.uiState.value is DesktopLifecycleUiState.Blocked)
        AuthRepository.initialize()
        assertTrue(AuthRepository.signInWithEmail("c@example.com", "pw-c").isSuccess)
        assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
        assertNotNull(AuthRepository.error.value)
        assertEquals("library_payload_1=precious", Files.readString(rootCorrupt.resolve("nuvio_library.properties")))
        assertEquals("{corrupt-json", Files.readString(rootCorrupt.resolve(DesktopStorage.OwnerStampFileName)))
    }

    @Test
    fun guest_keeps_loaded_profiles_does_not_reown_stored_profiles_and_public_rpcs_work_signed_out(): Unit = runBlocking {
        val sessionManager = TrackingSessionManager()
        sessionManager.seed(
            UserSession(
                accessToken = "token-a",
                refreshToken = "refresh-a",
                expiresIn = 3_600L,
                tokenType = "bearer",
                user = UserInfo(id = "user-a", aud = "authenticated", email = "a@example.com"),
            ),
        )
        SupabaseProvider.engineFactory = {
            MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path.endsWith("/auth/v1/user") -> respondJson(userJson(TestUser("user-a", "a@example.com", "token-a")))
                    path.endsWith("/auth/v1/logout") -> respond("", HttpStatusCode.NoContent)
                    path.contains("/rpc/get_avatar_catalog") -> respondJson(
                        """[{"id":"av-1","display_name":"Fox","storage_path":"avatars/fox.png","category":"animals","sort_order":1}]""",
                    )
                    path.contains("/rpc/watch_party_enabled") -> respondJson("true")
                    path.contains("/rpc/watch_party_preview") -> respondJson(
                        """{"members":2,"max_members":8,"host":{"id":"m1","display_name":"Host"}}""",
                    )
                    else -> respondJson("[]")
                }
            }
        }
        SupabaseProvider.sessionManagerOverride = sessionManager
        ProfileStorage.savePayload(
            """{"userId":"user-a","activeProfileIndex":2,"hasEverSelectedProfile":true,"rememberLastProfileEnabled":false,"profiles":[{"id":"p1","user_id":"user-a","profile_index":1,"name":"Main","avatar_color_hex":"#112233"},{"id":"p2","user_id":"user-a","profile_index":2,"name":"Cinema","avatar_color_hex":"#445566"}]}""",
        )
        val exits = AtomicInteger(0)
        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { rootDir },
            storedSessionUserId = { sessionManager.loadSession()?.user?.id },
            exitApp = { exits.incrementAndGet() },
        )
        ProfileRepository.loadCachedProfiles()
        AuthRepository.initialize()
        awaitAuthenticatedUser("user-a")
        assertEquals(2, ProfileRepository.state.value.profiles.size)
        assertEquals(2, ProfileRepository.activeProfileId)

        assertTrue(AuthRepository.signOut().isSuccess)
        assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
        assertEquals(2, ProfileRepository.state.value.profiles.size)

        // Public RPCs succeed while signed out even though shared client has requireValidSession = true.
        AvatarRepository.fetchAvatars()
        assertTrue(AvatarRepository.avatars.value.any { it.id == "av-1" })
        assertTrue(PartyApi.enabled())
        val preview = PartyApi.preview("ABCD12")
        assertEquals(2, preview.members)
        assertEquals(8, preview.maxMembers)

        // Enter guest mode: no owner change, no exit, keeps loaded profiles, does not overwrite stored userId.
        AuthRepository.signInAnonymously()
        val guestState = AuthRepository.state.value as? AuthState.Authenticated
        assertNotNull(guestState)
        assertTrue(guestState.isAnonymous)
        ProfileRepository.ensureLoaded(guestState.userId)
        ProfileRepository.setRememberLastProfileEnabled(true)

        assertEquals(2, ProfileRepository.state.value.profiles.size)
        assertEquals(2, ProfileRepository.activeProfileId)
        assertEquals(0, exits.get())
        assertEquals(
            StampState.Valid(StampOwner.Remote("https://default.nuvio.example", "user-a")),
            DesktopAccountDataLifecycle.readStamp(rootDir),
        )
        val storedPayload = ProfileStorage.loadPayload().orEmpty()
        assertTrue("\"userId\":\"user-a\"" in storedPayload, "Guest must not overwrite stored profile owner userId: $storedPayload")
        assertFalse(guestState.userId in storedPayload)
    }

    @Test
    fun delayed_refresh_and_sdk_auto_refresh_cannot_undo_sign_out_and_party_stops(): Unit = runBlocking {
        // Part 1: App-initiated refresh in signOutIfSessionInvalid racing with signOut() + active Watch Party stopped on signOut().
        val refreshEntered = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val sessionManager = TrackingSessionManager()
        sessionManager.seed(
            UserSession(
                accessToken = "token-a",
                refreshToken = "refresh-a",
                expiresIn = 3_600L,
                tokenType = "bearer",
                user = UserInfo(id = "user-a", aud = "authenticated", email = "a@example.com"),
            ),
        )
        SupabaseProvider.engineFactory = {
            MockEngine { request ->
                val path = request.url.encodedPath
                val query = request.url.encodedQuery
                when {
                    path.endsWith("/auth/v1/token") && "refresh_token" in query -> {
                        refreshEntered.complete(Unit)
                        releaseRefresh.await()
                        respondJson(tokenSessionJson(TestUser("user-a", "a@example.com", "token-a-refreshed")))
                    }
                    path.endsWith("/auth/v1/user") -> respondJson(userJson(TestUser("user-a", "a@example.com", "token-a")))
                    path.endsWith("/auth/v1/logout") -> respond("", HttpStatusCode.NoContent)
                    path.contains("/rpc/watch_party_create") -> respondJson(
                        """{"party":{"id":"party-1","code":"PARTY1","host_member_id":"m1","host_only":false,"max_members":8,"media":{"type":"movie","meta_id":"tt1","meta_type":"movie","video_id":"tt1","title":"Film"}},"member_id":"m1","members":[{"id":"m1","display_name":"Owner A"}],"server_time_ms":1700000000000}""",
                    )
                    path.contains("/rpc/watch_party_list_messages") -> respondJson("""{"messages":[],"has_more":false}""")
                    else -> respondJson("[]")
                }
            }
        }
        SupabaseProvider.sessionManagerOverride = sessionManager
        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { rootDir },
            storedSessionUserId = { sessionManager.loadSession()?.user?.id },
            exitApp = {},
        )
        AuthRepository.initialize()
        awaitAuthenticatedUser("user-a")
        assertNull(PartyRepository.create(PartyMedia(type = "movie", metaId = "tt1", metaType = "movie", title = "Film")))
        assertNotNull(PartyRepository.state.value.party)

        val refreshJob = async(Dispatchers.Default) {
            AuthRepository.signOutIfSessionInvalid(RuntimeException("JWT expired"), "delayed-test")
        }
        withTimeout(5_000) { refreshEntered.await() }

        val signOutJob = async(Dispatchers.Default) { AuthRepository.signOut() }
        delay(50)
        releaseRefresh.complete(Unit)

        assertTrue(signOutJob.await().isSuccess)
        refreshJob.await()
        delay(50)

        assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
        assertNull(PartyRepository.state.value.party, "Active Watch Party must be stopped on sign-out")
        assertNull(SupabaseProvider.client.auth.currentSessionOrNull())
        assertNull(sessionManager.loadSession())

        // Part 2: SDK internal auto-refresh (short expiresIn = 1s) in-flight across signOut() cannot re-authenticate shared client.
        val sdkRefreshEntered = CompletableDeferred<Unit>()
        val releaseSdkRefresh = CompletableDeferred<Unit>()
        val sessionManager2 = TrackingSessionManager()
        AuthRepository.resetForTest()
        SupabaseProvider.resetForTest()
        SupabaseProvider.engineFactory = {
            MockEngine { request ->
                val path = request.url.encodedPath
                val query = request.url.encodedQuery
                when {
                    path.endsWith("/auth/v1/token") && "refresh_token" in query -> {
                        sdkRefreshEntered.complete(Unit)
                        releaseSdkRefresh.await()
                        respondJson(tokenSessionJson(TestUser("user-a", "a@example.com", "token-sdk-late")))
                    }
                    path.endsWith("/auth/v1/token") -> respondJson(
                        """{"access_token":"token-short","token_type":"bearer","expires_in":1,"refresh_token":"refresh-short","user":${userJson(TestUser("user-a", "a@example.com", "token-short"))}}""",
                    )
                    path.endsWith("/auth/v1/user") -> respondJson(userJson(TestUser("user-a", "a@example.com", "token-short")))
                    path.endsWith("/auth/v1/logout") -> respond("", HttpStatusCode.NoContent)
                    else -> respondJson("[]")
                }
            }
        }
        SupabaseProvider.sessionManagerOverride = sessionManager2
        AuthRepository.initialize()
        assertTrue(AuthRepository.signInWithEmail("a@example.com", "pw-a").isSuccess)
        awaitAuthenticatedUser("user-a")

        withTimeout(5_000) { sdkRefreshEntered.await() }
        val sdkSignOut = async(Dispatchers.Default) { AuthRepository.signOut() }
        delay(50)
        releaseSdkRefresh.complete(Unit)
        assertTrue(sdkSignOut.await().isSuccess)
        delay(80)

        assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
        assertNull(SupabaseProvider.client.auth.currentSessionOrNull())
        assertNull(sessionManager2.loadSession())
    }

    @Test
    fun anonymous_and_signed_out_empty_pull_does_not_overwrite_addon_list_or_library(): Unit = runBlocking {
        val privatePullCallsWhileSignedOut = Collections.synchronizedList(mutableListOf<String>())
        val inFlightPullEntered = CompletableDeferred<Unit>()
        val releaseInFlightPull = CompletableDeferred<Unit>()
        val holdPull = AtomicBoolean(false)
        val sessionManager = TrackingSessionManager()

        SupabaseProvider.engineFactory = {
            MockEngine { request ->
                val path = request.url.encodedPath
                val auth = request.headers[HttpHeaders.Authorization]
                when {
                    path.endsWith("/auth/v1/token") -> respondJson(tokenSessionJson(TestUser("user-a", "a@example.com", "token-a")))
                    path.endsWith("/auth/v1/user") -> respondJson(userJson(TestUser("user-a", "a@example.com", "token-a")))
                    path.endsWith("/auth/v1/logout") -> respond("", HttpStatusCode.NoContent)
                    path.endsWith("/rest/v1/addons") || path.contains("/rpc/sync_pull_library") || path.contains("/rpc/sync_get_library_delta_cursor") -> {
                        if (auth != "Bearer token-a") {
                            privatePullCallsWhileSignedOut += "$path ($auth)"
                        }
                        if (holdPull.get() && path.endsWith("/rest/v1/addons")) {
                            inFlightPullEntered.complete(Unit)
                            releaseInFlightPull.await()
                        }
                        if (path.contains("/rpc/sync_get_library_delta_cursor")) respondJson("0") else respondJson("[]")
                    }
                    else -> respondJson("[]")
                }
            }
        }
        Files.writeString(
            rootDir.resolve(DesktopStorage.OwnerStampFileName),
            """{"type":"remote","serverUrl":"https://default.nuvio.example","userId":"user-a"}""",
        )
        SupabaseProvider.sessionManagerOverride = sessionManager
        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { rootDir },
            storedSessionUserId = { null },
            exitApp = {},
        )
        AuthRepository.initialize()
        withTimeout(5_000) { AuthRepository.state.first { it is AuthState.Unauthenticated } }

        val addonUrl = "https://cinemeta.example/manifest.json"
        AddonStorage.saveInstalledAddonUrls(1, listOf(addonUrl))
        AddonStorage.saveAddonEnabledStates(1, mapOf(addonUrl to false))
        AddonRepository.initialize()
        LibraryRepository.save(
            LibraryItem(
                id = "tt0133093",
                type = "movie",
                name = "The Matrix",
                savedAtEpochMs = 1_700_000_000_000L,
            ),
        )

        // 1. Signed-out pull attempts never reach the server and never overwrite local add-on list or library.
        AddonRepository.pullFromServer(1)
        LibraryRepository.pullFromServer(1)
        assertTrue(privatePullCallsWhileSignedOut.isEmpty(), "No unauthenticated private pull may hit the network: $privatePullCallsWhileSignedOut")
        assertEquals(listOf(addonUrl), AddonRepository.uiState.value.addons.map { it.manifestUrl })
        assertEquals(listOf(addonUrl), AddonStorage.loadInstalledAddonUrls(1))
        assertTrue(LibraryRepository.isSaved("tt0133093", "movie"))

        // 2. Guest mode pull attempts also do nothing and preserve local data.
        AuthRepository.signInAnonymously()
        AddonRepository.pullFromServer(1)
        LibraryRepository.pullFromServer(1)
        assertTrue(privatePullCallsWhileSignedOut.isEmpty())
        assertEquals(listOf(addonUrl), AddonRepository.uiState.value.addons.map { it.manifestUrl })
        assertTrue(LibraryRepository.isSaved("tt0133093", "movie"))
        assertTrue(AuthRepository.signOut().isSuccess)

        // 3. An in-flight pull that started right before sign-out drops its empty response after sign-out completes.
        assertTrue(AuthRepository.signInWithEmail("a@example.com", "pw-a").isSuccess)
        awaitAuthenticatedUser("user-a")
        holdPull.set(true)
        val inFlightAddonPull = async(Dispatchers.Default) {
            AddonRepository.pullFromServer(1)
        }
        withTimeout(5_000) { inFlightPullEntered.await() }
        assertTrue(AuthRepository.signOut().isSuccess)
        releaseInFlightPull.complete(Unit)
        inFlightAddonPull.await()

        assertEquals(listOf(addonUrl), AddonRepository.uiState.value.addons.map { it.manifestUrl })
        assertEquals(listOf(addonUrl), AddonStorage.loadInstalledAddonUrls(1))
        assertTrue(LibraryRepository.isSaved("tt0133093", "movie"))
    }

    @Test
    fun start_fresh_and_delete_account_blocked_by_unresolved_move_journal_and_post_archive_skips_leftover_replay(): Unit = runBlocking {
        val deleteFunctionCalls = AtomicInteger(0)
        val sessionManager = TrackingSessionManager()
        sessionManager.seed(
            UserSession(
                accessToken = "token-a",
                refreshToken = "refresh-a",
                expiresIn = 3_600L,
                tokenType = "bearer",
                user = UserInfo(id = "user-a", aud = "authenticated", email = "a@example.com"),
            ),
        )
        SupabaseProvider.engineFactory = {
            MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path.contains("/functions/v1/delete-account") -> {
                        deleteFunctionCalls.incrementAndGet()
                        respondJson("{}")
                    }
                    path.endsWith("/auth/v1/user") -> respondJson(userJson(TestUser("user-a", "a@example.com", "token-a")))
                    path.endsWith("/auth/v1/logout") -> respond("", HttpStatusCode.NoContent)
                    else -> respondJson("[]")
                }
            }
        }
        SupabaseProvider.sessionManagerOverride = sessionManager
        seedAllAccountDataFiles(userId = "user-a")

        // Write a real open move journal targeting a disconnected external location ("disconnected-usb").
        val movesDir = rootDir.resolve("moves").also { it.createDirectories() }
        val openJournal = MoveJournal(
            txId = "deadbeef01",
            destinationLocationId = "disconnected-usb",
            items = listOf(
                MoveItem(
                    profileId = 1,
                    itemId = "dl-1",
                    recordLocationId = "app",
                    recordUri = rootDir.resolve("downloads/Movie.mkv").toUri().toString(),
                    recordFileName = "Movie.mkv",
                    completed = true,
                    entries = listOf(
                        MoveEntry(
                            srcLocationId = "app",
                            srcRel = "Movie.mkv",
                            dstLocationId = "disconnected-usb",
                            dstRel = "Movie.mkv",
                            kind = MoveKind.File,
                            temp = "Movie.mkv.nuvio-move-deadbeef01",
                            tempKey = "key-temp-1",
                            state = EntryState.TempCreated,
                        ),
                    ),
                    outcome = null,
                ),
            ),
        )
        Files.writeString(
            movesDir.resolve("move-deadbeef01-00000001.journal"),
            Json.encodeToString(openJournal),
        )

        val exits = AtomicInteger(0)
        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { rootDir },
            storedSessionUserId = { sessionManager.loadSession()?.user?.id },
            exitApp = { exits.incrementAndGet() },
        )
        AuthRepository.initialize()
        awaitAuthenticatedUser("user-a")

        // 1. Delete account refuses BEFORE invoking remote delete-account function when an unresolved move journal exists.
        val blockedDelete = AuthRepository.deleteAccount()
        assertTrue(blockedDelete.isFailure)
        assertEquals(0, deleteFunctionCalls.get(), "Remote account deletion must not run when local move journal is unresolved")
        assertEquals(0, exits.get())

        // 2. Sign out and try Start fresh: blocked by unresolved move journal, writes no transition journal, does not exit.
        assertTrue(AuthRepository.signOut().isSuccess)
        val blockedStartFresh = AuthRepository.startFresh()
        assertTrue(blockedStartFresh.isFailure)
        assertNotNull(AuthRepository.error.value)
        assertFalse(rootDir.resolve(DesktopStorage.TransitionJournalFileName).exists())
        assertEquals(0, exits.get())

        // 3. Set aside the unresolved move via real DownloadMover.setAside, leaving a settled Leftover journal in moves/.
        val mover = DownloadMover(
            journalDir = movesDir,
            resolve = { null },
            trash = { false },
        )
        assertTrue(mover.setAside("journal:deadbeef01"))
        val settledLeftoverJournal = MoveJournal(
            txId = "settledleftover02",
            destinationLocationId = "app",
            items = listOf(
                MoveItem(
                    profileId = 1,
                    itemId = "dl-2",
                    recordLocationId = "app",
                    recordUri = null,
                    recordFileName = "Extra.mkv",
                    completed = true,
                    entries = emptyList(),
                    outcome = ItemOutcome.Leftover,
                ),
            ),
        )
        val settledFile = movesDir.resolve("move-settledleftover02-00000001.journal")
        val settledJsonText = Json.encodeToString(settledLeftoverJournal)
        Files.writeString(settledFile, settledJsonText)

        // Start fresh now succeeds, clears stored session, writes account_transition.json on confirmed quit, and exits.
        val freshOk = AuthRepository.startFresh()
        assertTrue(freshOk.isSuccess)
        assertEquals(1, exits.get())
        assertNull(sessionManager.loadSession())
        assertTrue(rootDir.resolve(DesktopStorage.TransitionJournalFileName).exists())

        // Next launch archives into account-user-a-<ts>/, stamps Vacant, sets didArchiveOnStartup = true, and skips leftover replay.
        AuthRepository.resetForTest()
        DesktopAccountDataLifecycle.resetForTest()
        DesktopStorage.resetInMemoryStores()
        SupabaseProvider.reset()
        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { rootDir },
            storedSessionUserId = { null },
            nowEpochMs = { 1_700_000_000_000L },
            exitApp = {},
        )
        assertTrue(DesktopAccountDataLifecycle.didArchiveOnStartup)
        assertEquals(StampState.Valid(StampOwner.Vacant), DesktopAccountDataLifecycle.readStamp(rootDir))
        // Seed a Done journal alongside settledFile: if recoverInterrupted() ran on post-archive launch, settle() would delete it.
        val doneSentinel = MoveJournal(
            txId = "donesentinel03",
            destinationLocationId = "app",
            items = listOf(
                MoveItem(
                    profileId = 1,
                    itemId = "dl-3",
                    recordLocationId = "app",
                    recordUri = null,
                    recordFileName = "Done.mkv",
                    completed = true,
                    entries = emptyList(),
                    outcome = ItemOutcome.Done,
                ),
            ),
        )
        val doneSentinelFile = movesDir.resolve("move-donesentinel03-00000001.journal")
        Files.writeString(doneSentinelFile, Json.encodeToString(doneSentinel))
        DesktopDownloads.install(skipInitialRecovery = DesktopAccountDataLifecycle.didArchiveOnStartup)
        assertEquals(settledJsonText, Files.readString(settledFile))
        assertTrue(doneSentinelFile.exists(), "Post-archive launch must skip initial move-journal replay")
    }

    @Test
    fun interrupted_archive_resumes_across_all_steps_and_directory_flush_failure_blocks_safely(): Unit = runBlocking {
        data class InterruptionCase(
            val name: String,
            val targetStep: ArchiveStep,
            val occurrence: Int = 1,
            val expectsVacantStampBeforeResume: Boolean = false,
        )

        val cases = listOf(
            InterruptionCase("after_stage_written", ArchiveStep.AfterStageWritten),
            InterruptionCase("after_archive_dir_created", ArchiveStep.AfterArchiveDirCreated),
            InterruptionCase("after_manifest_written", ArchiveStep.AfterManifestWritten),
            InterruptionCase("after_first_entry_moved", ArchiveStep.AfterEachEntryMoved, occurrence = 1),
            InterruptionCase("after_second_entry_moved", ArchiveStep.AfterEachEntryMoved, occurrence = 2),
            InterruptionCase("after_all_entries_moved", ArchiveStep.AfterAllEntriesMoved),
            InterruptionCase(
                "after_owner_stamped",
                ArchiveStep.AfterOwnerStamped,
                expectsVacantStampBeforeResume = true,
            ),
        )

        for (case in cases) {
            val caseRoot = tempBase.resolve("interrupt-${case.name}").also { it.createDirectories() }
            AuthRepository.resetForTest()
            ProfileRepository.clearInMemory()
            WatchProgressRepository.resetForTest()
            DesktopAccountDataLifecycle.resetForTest()
            SupabaseProvider.resetForTest()
            DesktopStorage.setRootDirForTest(caseRoot, cacheDir)
            ServerConfigurationRepository.saveCustom(
                ServerConfiguration(
                    backendUrl = "https://default.nuvio.example",
                    publishableKey = "default-test-key",
                    capabilities = ServerCapabilities(emailPasswordAuth = true, tvLogin = true),
                    isCustom = true,
                ),
            )

            val harness = installMockBackend(activeUser = TestUser("user-a", "a@example.com", "token-a"))
            val expectedFiles = linkedMapOf(
                "nuvio_library.properties" to "library_payload_1=case-${case.name}\n",
                "nuvio_addons.properties" to "installed_manifest_urls_1=[\"https://a.example\"]\n",
                "nuvio_trakt_auth.properties" to "trakt_auth_payload_1=tok-${case.name}\n",
            )
            expectedFiles.forEach { (name, body) ->
                Files.writeString(caseRoot.resolve(name), body)
            }
            Files.writeString(caseRoot.resolve("downloads/Keep.mkv").also { it.parent.createDirectories() }, "keep-video")
            Files.writeString(caseRoot.resolve("instance.lock"), "held")

            val stageInterrupt = case.targetStep == ArchiveStep.AfterStageWritten
            DesktopAccountDataLifecycle.bootstrapAndInstall(
                rootDirProvider = { caseRoot },
                storedSessionUserId = { harness.sessionManager.loadSession()?.user?.id },
                nowEpochMs = { 1_700_000_000_000L },
                exitApp = {},
                onArchiveStep = { step ->
                    if (stageInterrupt && step == ArchiveStep.AfterStageWritten) {
                        throw SimulatedInterruptionException(case.name)
                    }
                },
            )
            AuthRepository.initialize()
            awaitAuthenticatedUser("user-a")
            assertTrue(AuthRepository.signOut().isSuccess)

            val startFreshCall = runCatching { AuthRepository.startFresh() }
            if (stageInterrupt) {
                assertTrue(startFreshCall.exceptionOrNull() is SimulatedInterruptionException)
            } else {
                assertTrue(startFreshCall.getOrThrow().isSuccess)
                AuthRepository.resetForTest()
                DesktopAccountDataLifecycle.resetForTest()
                var seen = 0
                val crashed = runCatching {
                    DesktopAccountDataLifecycle.bootstrapAndInstall(
                        rootDirProvider = { caseRoot },
                        storedSessionUserId = { null },
                        nowEpochMs = { 1_700_000_000_000L },
                        exitApp = {},
                        onArchiveStep = { step ->
                            if (step == case.targetStep) {
                                seen += 1
                                if (seen == case.occurrence) {
                                    throw SimulatedInterruptionException(case.name)
                                }
                            }
                        },
                    )
                }
                assertTrue(crashed.exceptionOrNull() is SimulatedInterruptionException, "Expected simulated crash for ${case.name}")
            }

            val midStamp = DesktopAccountDataLifecycle.readStamp(caseRoot)
            if (!case.expectsVacantStampBeforeResume) {
                assertEquals(
                    StampState.Valid(StampOwner.Remote("https://default.nuvio.example", "user-a")),
                    midStamp,
                    "Owner stamp must remain user-a until archive finishes in ${case.name}",
                )
            }

            // Simulate next launch with a directory-flush failure first: must block instead of claiming Ready!
            DesktopAccountDataLifecycle.resetForTest()
            DesktopAccountDataLifecycle.bootstrapAndInstall(
                rootDirProvider = { caseRoot },
                storedSessionUserId = { null },
                nowEpochMs = { 1_700_000_000_000L },
                exitApp = {},
                syncDirectory = { false },
            )
            assertTrue(
                DesktopAccountDataLifecycle.uiState.value is DesktopLifecycleUiState.Blocked,
                "Directory flush failure during archive recovery must enter Blocked state (${case.name})",
            )

            // Clean launch completes recovery and stamps Vacant.
            simulateNextLaunchWithClock(harness, fixedEpochMs = 1_700_000_000_000L, customRoot = caseRoot)
            assertEquals(DesktopLifecycleUiState.Ready, DesktopAccountDataLifecycle.uiState.value)
            assertFalse(
                caseRoot.resolve(DesktopStorage.TransitionJournalFileName).exists(),
                "Transition journal should be archived out of root after recovery (${case.name})",
            )
            assertEquals(StampState.Valid(StampOwner.Vacant), DesktopAccountDataLifecycle.readStamp(caseRoot))

            val archives = listArchiveDirs(caseRoot).filter { archive ->
                expectedFiles.keys.any { archive.resolve(it).exists() }
            }
            assertEquals(1, archives.size, "Expected one completed archive folder in ${case.name}")
            val archive = archives.single()
            expectedFiles.forEach { (name, body) ->
                assertFalse(caseRoot.resolve(name).exists(), "Expected $name moved out of root in ${case.name}")
                assertEquals(body, Files.readString(archive.resolve(name)), "Archive content mismatch for $name in ${case.name}")
            }
            assertEquals("keep-video", Files.readString(caseRoot.resolve("downloads/Keep.mkv")))
            assertEquals("held", Files.readString(caseRoot.resolve("instance.lock")))
        }
    }

    @Test
    fun pending_watch_progress_and_active_player_flush_are_saved_before_quit_and_failed_drain_blocks_quit(): Unit = runBlocking {
        val harness = installMockBackend(activeUser = TestUser("user-a", "a@example.com", "token-a"))
        var payloadAtExit: String? = null
        val persistStarted = CompletableDeferred<Unit>()
        val exits = AtomicInteger(0)

        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { rootDir },
            storedSessionUserId = { harness.sessionManager.loadSession()?.user?.id },
            exitApp = {
                payloadAtExit = DesktopStorage.Store(rootDir.resolve("nuvio_watch_progress.properties"))
                    .getFreshString("watch_progress_1")
                exits.incrementAndGet()
            },
        )
        AuthRepository.initialize()
        awaitAuthenticatedUser("user-a")
        WatchProgressRepository.ensureLoaded()

        // 1. Register an active player flush hook AND trigger an asynchronous background persistence write.
        val unregisterPlayerFlush = WatchProgressRepository.registerActivePlayerFlushHook {
            WatchProgressRepository.flushPlaybackProgress(
                session = WatchProgressPlaybackSession(
                    profileId = 1,
                    contentType = "movie",
                    parentMetaId = "tt1375666",
                    parentMetaType = "movie",
                    videoId = "tt1375666",
                    title = "Inception",
                ),
                snapshot = PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = true,
                    isEnded = false,
                    durationMs = 8_880_000L,
                    positionMs = 3_600_000L,
                ),
                syncRemote = false,
            )
        }
        try {
            WatchProgressRepository.beforePersistWriteForTest = {
                if (!persistStarted.isCompleted) {
                    persistStarted.complete(Unit)
                }
                delay(100)
            }
            WatchProgressRepository.flushPlaybackProgress(
                session = WatchProgressPlaybackSession(
                    profileId = 1,
                    contentType = "movie",
                    parentMetaId = "tt1234567",
                    parentMetaType = "movie",
                    videoId = "tt1234567",
                    title = "First Film",
                ),
                snapshot = PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = false,
                    isEnded = false,
                    durationMs = 7_200_000L,
                    positionMs = 1_800_000L,
                ),
                syncRemote = false,
            )
            withTimeout(5_000) { persistStarted.await() }

            // 2. Block disk rename with a directory at .tmp: quit must be blocked with quitError and sequence must NOT advance!
            WatchProgressRepository.beforePersistWriteForTest = null
            val blockingTmpDir = rootDir.resolve("nuvio_watch_progress.properties.tmp").also { it.createDirectories() }
            assertTrue(AuthRepository.signOut().isSuccess)
            val failedQuit = AuthRepository.startFresh()
            assertTrue(failedQuit.isFailure, "Start fresh quit must fail when watch-progress drain fails")
            assertEquals(0, exits.get(), "Failed watch-progress drain must block quit")
            assertNotNull(DesktopAccountDataLifecycle.quitError.value)
            assertFalse(rootDir.resolve(DesktopStorage.TransitionJournalFileName).exists())

            // 3. Move the blocking directory aside and retry Start fresh: both film saves land on disk BEFORE exitApp runs!
            Files.move(blockingTmpDir, tempBase.resolve("retired-blocking-dir"))
            val succeededQuit = AuthRepository.startFresh()
            assertTrue(succeededQuit.isSuccess)
            assertEquals(1, exits.get())
            assertNull(DesktopAccountDataLifecycle.quitError.value)
            assertNotNull(payloadAtExit, "Both pending and player-flushed progress must be on disk before exitApp runs")
            assertTrue("tt1234567" in payloadAtExit!! && "1800000" in payloadAtExit!!)
            assertTrue("tt1375666" in payloadAtExit!! && "3600000" in payloadAtExit!!)
        } finally {
            unregisterPlayerFlush()
        }
    }

    @Test
    fun delete_account_and_start_fresh_archive_all_profiles_preserve_excluded_files_and_handle_cancelled_quit(): Unit = runBlocking {
        val customServer = ServerConfiguration(
            backendUrl = "https://custom.nuvio.example",
            publishableKey = "pub-key",
            capabilities = ServerCapabilities(emailPasswordAuth = true, tvLogin = true),
            isCustom = true,
        )
        assertTrue(ServerConfigurationRepository.saveCustom(customServer))

        val video = rootDir.resolve("downloads/Movie.mkv").also {
            it.parent.createDirectories()
            Files.writeString(it, "video-bytes")
        }
        val instanceLock = rootDir.resolve("instance.lock").also { Files.writeString(it, "locked") }
        val windowState = rootDir.resolve("nuvio_window_state.properties").also { Files.writeString(it, "fullscreen=false") }
        val updaterProps = rootDir.resolve("nuvio_updater.properties").also { Files.writeString(it, "ignored_release_tag=v1") }
        val updatePkg = rootDir.resolve("updates/Nuvio.dmg").also {
            it.parent.createDirectories()
            Files.writeString(it, "dmg-bytes")
        }
        val rendererProps = rootDir.resolve("nuvio_desktop_renderer.properties").also { Files.writeString(it, "renderer=METAL") }

        val functionCalls = Collections.synchronizedList(mutableListOf<String>())
        val sessionManager = TrackingSessionManager()
        val activeUser = AtomicReference<TestUser?>(TestUser("user-a", "a@example.com", "token-a"))
        sessionManager.seed(
            UserSession(
                accessToken = "token-a",
                refreshToken = "refresh-a",
                expiresIn = 3_600L,
                tokenType = "bearer",
                user = UserInfo(id = "user-a", aud = "authenticated", email = "a@example.com"),
            ),
        )
        SupabaseProvider.engineFactory = {
            MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path.contains("/functions/v1/") -> {
                        functionCalls += path
                        respondJson("{}")
                    }
                    path.endsWith("/auth/v1/token") -> respondJson(tokenSessionJson(activeUser.get()!!))
                    path.endsWith("/auth/v1/user") -> respondJson(userJson(activeUser.get()!!))
                    path.endsWith("/auth/v1/logout") -> respond("{}", HttpStatusCode.Unauthorized)
                    else -> respondJson("[]")
                }
            }
        }
        SupabaseProvider.sessionManagerOverride = sessionManager

        val expectedFiles = seedAllAccountDataFiles(
            userId = "user-a",
            profilesJson = """{"userId":"user-a","activeProfileIndex":2,"profiles":[{"id":"p1","user_id":"user-a","profile_index":1,"name":"Main","avatar_color_hex":"#112233"},{"id":"p2","user_id":"user-a","profile_index":2,"name":"Kids","avatar_color_hex":"#445566"},{"id":"p3","user_id":"user-a","profile_index":3,"name":"Guest","avatar_color_hex":"#778899"},{"id":"p4","user_id":"user-a","profile_index":4,"name":"Cinema","avatar_color_hex":"#AABBCC"}]}""",
        )
        val libraryStore = DesktopStorage.store("nuvio_library")
        for (p in 1..4) {
            libraryStore.putString("library_payload_$p", "lib-profile-$p")
        }

        val exits = AtomicInteger(0)
        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { rootDir },
            storedSessionUserId = { sessionManager.loadSession()?.user?.id },
            nowEpochMs = { 1_700_000_000_000L },
            exitApp = { exits.incrementAndGet() },
        )
        AuthRepository.initialize()
        awaitAuthenticatedUser("user-a")

        // Cancelled window quit during Start fresh leaves no transition journal in rootDir.
        val observedAllowCancel = AtomicReference<Boolean?>(null)
        DesktopAccountDataLifecycle.bindWindowRequestQuitHandler { allowCancel ->
            observedAllowCancel.set(allowCancel)
            if (allowCancel) {
                // Simulate user clicking "Keep downloading" on quit dialog -> cancelPendingQuit().
                DesktopAccountDataLifecycle.cancelPendingQuit()
            } else {
                launch(Dispatchers.Default) {
                    DesktopAccountDataLifecycle.executeConfirmedQuit {
                        exits.incrementAndGet()
                    }
                }
            }
        }
        assertTrue(AuthRepository.signOut().isSuccess)
        assertTrue(AuthRepository.startFresh().isSuccess)
        assertEquals(true, observedAllowCancel.get())
        assertEquals(0, exits.get())
        assertFalse(rootDir.resolve(DesktopStorage.TransitionJournalFileName).exists())

        // Sign back in as user-a, then delete account remotely -> passes allowCancel=false so quit cannot be cancelled!
        assertTrue(AuthRepository.signInWithEmail("a@example.com", "pw-a").isSuccess)
        awaitAuthenticatedUser("user-a")
        val deleteResult = AuthRepository.deleteAccount()
        assertTrue(deleteResult.isSuccess)
        assertEquals(false, observedAllowCancel.get(), "Post-delete Start fresh must not allow cancelling quit")
        DesktopAccountDataLifecycle.bindWindowRequestQuitHandler(null)
        assertTrue(functionCalls.any { it.endsWith("/functions/v1/delete-account") })
        assertEquals(1, exits.get())
        assertNull(sessionManager.loadSession())
        assertTrue(expectedFiles.keys.all { rootDir.resolve(it).exists() }, "No file is removed in the running process")

        // Next launch archives all 4 profiles and all account files, preserves excluded files, and stamps Vacant.
        AuthRepository.resetForTest()
        DesktopAccountDataLifecycle.resetForTest()
        DesktopStorage.resetInMemoryStores()
        SupabaseProvider.reset()
        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { rootDir },
            storedSessionUserId = { null },
            nowEpochMs = { 1_700_000_000_000L },
            exitApp = { exits.incrementAndGet() },
        )
        assertEquals(StampState.Valid(StampOwner.Vacant), DesktopAccountDataLifecycle.readStamp(rootDir))
        val archive = listArchiveDirs(rootDir).single()
        val archivedLibrary = DesktopStorage.Store(archive.resolve("nuvio_library.properties"))
        for (p in 1..4) {
            assertEquals("lib-profile-$p", archivedLibrary.getString("library_payload_$p"))
            assertNull(DesktopStorage.store("nuvio_library").getString("library_payload_$p"))
        }
        assertEquals("video-bytes", Files.readString(video))
        assertEquals("locked", Files.readString(instanceLock))
        assertEquals("https://custom.nuvio.example", ServerConfigurationStorage.loadCustom()?.backendUrl)
        assertEquals("fullscreen=false", Files.readString(windowState))
        assertEquals("ignored_release_tag=v1", Files.readString(updaterProps))
        assertEquals("dmg-bytes", Files.readString(updatePkg))
        assertEquals("renderer=METAL", Files.readString(rendererProps))
        assertFalse(archive.resolve("downloads").exists())
        assertFalse(archive.resolve("instance.lock").exists())
        assertFalse(archive.resolve("server_configuration.properties").exists())
        assertFalse(archive.resolve("updates").exists())

        // Subsequent sign-in as user-b claims the Vacant folder directly without restart.
        activeUser.set(TestUser("user-b", "b@example.com", "token-b"))
        SupabaseProvider.sessionManagerOverride = sessionManager
        AuthRepository.initialize()
        assertTrue(AuthRepository.signInWithEmail("b@example.com", "pw-b").isSuccess)
        awaitAuthenticatedUser("user-b")
        assertEquals(1, exits.get())
        assertEquals(
            StampState.Valid(StampOwner.Remote("https://custom.nuvio.example", "user-b")),
            DesktopAccountDataLifecycle.readStamp(rootDir),
        )
    }

    @Suppress("DEPRECATION")
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun app_gate_and_auth_screen_reach_sign_in_without_clearing_profiles_and_drive_adoption_and_start_fresh(): Unit = runComposeUiTest {
        val activeUser = AtomicReference<TestUser?>(null)
        val sessionManager = TrackingSessionManager()
        val engineFactory = {
            MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path.endsWith("/auth/v1/token") -> respondJson(tokenSessionJson(activeUser.get()!!))
                    path.endsWith("/auth/v1/user") -> respondJson(userJson(activeUser.get()!!))
                    path.endsWith("/auth/v1/logout") -> respond("", HttpStatusCode.NoContent)
                    path.endsWith("/rpc/sync_pull_profiles") -> respondJson(
                        """[{"id":"p1","user_id":"user-a","profile_index":1,"name":"Main","avatar_color_hex":"#112233"},{"id":"p2","user_id":"user-a","profile_index":2,"name":"Cinema","avatar_color_hex":"#445566"}]""",
                    )
                    else -> respondJson("[]")
                }
            }
        }
        val harness = MockBackendHarness(
            engineFactory = engineFactory,
            sessionManager = sessionManager,
            activeUser = activeUser,
            rejectRefresh = AtomicReference(false),
        )
        Files.writeString(rootDir.resolve("nuvio_library.properties"), "library_payload_1=keep-through-ui")
        ProfileStorage.savePayload(
            """{"userId":"","activeProfileIndex":2,"hasEverSelectedProfile":true,"rememberLastProfileEnabled":true,"profiles":[{"id":"p1","user_id":"","profile_index":1,"name":"Main","avatar_color_hex":"#112233"},{"id":"p2","user_id":"","profile_index":2,"name":"Cinema","avatar_color_hex":"#445566"}]}""",
        )
        val exits = installDesktopLifecycle(harness)
        ProfileRepository.loadCachedProfiles()
        AuthRepository.initialize()
        runBlocking {
            withTimeout(5_000) { AuthRepository.state.first { it is AuthState.Unauthenticated } }
        }

        val mainMounted = AtomicBoolean(false)
        setContent {
            AppEnvironment {
                AppGate(
                    initialTab = AppScreenTab.Home,
                    initialRoute = TabsRoute,
                    useNativeNavigation = false,
                    useNativeTabBar = false,
                    useTabletFloatingTabBar = false,
                    ownsAppRuntime = false,
                    bypassAppGate = false,
                    renderMainContent = false,
                    onNavigate = null,
                    onGoBack = null,
                    onReplace = null,
                    onActivate = null,
                    onAppReady = null,
                    onMainContentMountChanged = { mainMounted.set(it) },
                    onMainContentVisibleChanged = null,
                    onTabTitles = null,
                    nativeProfileSwitcherController = null,
                    appGateController = null,
                )
            }
        }
        waitForIdle()

        // 1. Signed-out desktop reaches AuthScreen (showing Start fresh) without clearing loaded profiles.
        onNodeWithText("Start fresh").assertIsDisplayed()
        assertEquals(2, ProfileRepository.state.value.profiles.size)

        // 2. Sign in on unstamped/Unknown folder with real data -> AuthScreen renders the Yes/No adoption dialog.
        harness.activeUser.set(TestUser("user-a", "a@example.com", "token-a"))
        runBlocking {
            assertTrue(AuthRepository.signInWithEmail("a@example.com", "pw-a").isSuccess)
        }
        waitForIdle()
        onNodeWithText("Keep the library and settings already on this Mac for this account?").assertIsDisplayed()

        // Clicking Yes adopts local data, stamps user-a, and AppGate mounts Main.
        onNodeWithText("Yes").performClick()
        waitUntil(timeoutMillis = 5_000) { mainMounted.get() }
        assertEquals(
            StampState.Valid(StampOwner.Remote("https://default.nuvio.example", "user-a")),
            DesktopAccountDataLifecycle.readStamp(rootDir),
        )

        // 3. Signing out from Main returns AppGate to AuthScreen while keeping loaded profiles and activeProfileId = 2!
        runBlocking {
            assertTrue(AuthRepository.signOut().isSuccess)
        }
        waitUntil(timeoutMillis = 5_000) { !mainMounted.get() }
        waitForIdle()
        onNodeWithText("Start fresh").assertIsDisplayed()
        assertEquals(2, ProfileRepository.state.value.profiles.size)
        assertEquals(2, ProfileRepository.activeProfileId)

        // 4. Clicking Start fresh and confirming triggers the Start-fresh quit and writes account_transition.json.
        onNodeWithText("Start fresh").performClick()
        waitForIdle()
        onNodeWithText("Start fresh?").assertIsDisplayed()
        onAllNodesWithText("Start fresh").onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { exits.get() == 1 }
        assertTrue(rootDir.resolve(DesktopStorage.TransitionJournalFileName).exists())
    }

    @Test
    fun start_fresh_async_window_quit_blocks_concurrent_sign_in_until_exit(): Unit = runBlocking {
        val savedTokens = Collections.synchronizedList(mutableListOf<String>())
        val logoutTokens = Collections.synchronizedList(mutableListOf<String?>())
        val sessionManager = TrackingSessionManager(onSave = { savedTokens += it.accessToken })
        sessionManager.seed(
            UserSession(
                accessToken = "token-a-initial",
                refreshToken = "refresh-a-initial",
                expiresIn = 3_600L,
                tokenType = "bearer",
                user = UserInfo(id = "user-a", aud = "authenticated", email = "a@example.com"),
            ),
        )
        SupabaseProvider.engineFactory = {
            MockEngine { request ->
                val path = request.url.encodedPath
                val auth = request.headers[HttpHeaders.Authorization]
                when {
                    path.endsWith("/auth/v1/token") -> respondJson(tokenSessionJson(TestUser("user-a", "a@example.com", "token-a-during-quit")))
                    path.endsWith("/auth/v1/user") -> respondJson(userJson(TestUser("user-a", "a@example.com", auth?.removePrefix("Bearer ") ?: "token-a-initial")))
                    path.endsWith("/auth/v1/logout") -> {
                        logoutTokens += auth
                        respond("", HttpStatusCode.NoContent)
                    }
                    else -> respondJson("[]")
                }
            }
        }
        SupabaseProvider.sessionManagerOverride = sessionManager
        Files.writeString(rootDir.resolve("nuvio_library.properties"), "library_payload_1=user-a-items")
        val exits = AtomicInteger(0)
        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { rootDir },
            storedSessionUserId = { sessionManager.loadSession()?.user?.id },
            exitApp = { exits.incrementAndGet() },
        )
        AuthRepository.initialize()
        awaitAuthenticatedUser("user-a")
        WatchProgressRepository.ensureLoaded()
        assertTrue(AuthRepository.signOut().isSuccess)
        savedTokens.clear()
        logoutTokens.clear()

        // Bind an asynchronous window quit handler matching Main.kt's appScope.launch { executeConfirmedQuit ... }.
        val drainEntered = CompletableDeferred<Unit>()
        val releaseDrain = CompletableDeferred<Unit>()
        DesktopAccountDataLifecycle.bindWindowRequestQuitHandler { _ ->
            launch(Dispatchers.Default) {
                DesktopAccountDataLifecycle.executeConfirmedQuit {
                    exits.incrementAndGet()
                }
            }
        }
        val unregisterFlush = WatchProgressRepository.registerActivePlayerFlushHook {
            WatchProgressRepository.flushPlaybackProgress(
                session = WatchProgressPlaybackSession(
                    profileId = 1,
                    contentType = "movie",
                    parentMetaId = "tt9999999",
                    parentMetaType = "movie",
                    videoId = "tt9999999",
                    title = "Slow Quit Save",
                ),
                snapshot = PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = true,
                    isEnded = false,
                    durationMs = 6_000_000L,
                    positionMs = 2_400_000L,
                ),
                syncRemote = false,
            )
        }
        try {
            WatchProgressRepository.beforePersistWriteForTest = {
                drainEntered.complete(Unit)
                releaseDrain.await()
            }

            val startFreshJob = async(Dispatchers.Default) { AuthRepository.startFresh() }
            withTimeout(5_000) { drainEntered.await() }
            assertFalse(startFreshJob.isCompleted, "Start fresh must remain in flight while async window shutdown drains saves")

            // Attempt email sign-in and guest entry while Start fresh shutdown is draining: both must be refused and store nothing!
            assertTrue(AuthRepository.signInWithEmail("a@example.com", "pw-a").isSuccess)
            assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
            assertNull(SupabaseProvider.client.auth.currentSessionOrNull())
            assertNull(sessionManager.loadSession())
            assertTrue(savedTokens.isEmpty(), "No session may be saved while Start fresh shutdown is in progress")
            assertTrue(logoutTokens.contains("Bearer token-a-during-quit"), "Throwaway session during shutdown must be signed out")

            AuthRepository.signInAnonymously()
            assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
            assertNull(AuthStorage.loadAnonymousUserId())

            releaseDrain.complete(Unit)
            assertTrue(startFreshJob.await().isSuccess)
            assertEquals(1, exits.get())
            assertTrue(rootDir.resolve(DesktopStorage.TransitionJournalFileName).exists())
            assertNull(sessionManager.loadSession())

            // Next launch archives and stays Unauthenticated on Vacant folder.
            WatchProgressRepository.beforePersistWriteForTest = null
            DesktopAccountDataLifecycle.bindWindowRequestQuitHandler(null)
            AuthRepository.resetForTest()
            DesktopAccountDataLifecycle.resetForTest()
            DesktopStorage.resetInMemoryStores()
            SupabaseProvider.reset()
            SupabaseProvider.sessionManagerOverride = sessionManager
            DesktopAccountDataLifecycle.bootstrapAndInstall(
                rootDirProvider = { rootDir },
                storedSessionUserId = { sessionManager.loadSession()?.user?.id },
                nowEpochMs = { 1_700_000_000_000L },
                exitApp = {},
            )
            AuthRepository.initialize()
            withTimeout(5_000) { AuthRepository.state.first { it is AuthState.Unauthenticated } }
            assertEquals(StampState.Valid(StampOwner.Vacant), DesktopAccountDataLifecycle.readStamp(rootDir))
            assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
        } finally {
            WatchProgressRepository.beforePersistWriteForTest = null
            DesktopAccountDataLifecycle.bindWindowRequestQuitHandler(null)
            unregisterFlush()
        }
    }

    @Test
    fun desktop_server_switch_preserves_session_when_quit_cancelled_or_watch_progress_drain_fails(): Unit = runBlocking {
        val initialServer = ServerConfiguration(
            backendUrl = "https://example.com/Team",
            publishableKey = "team-key",
            capabilities = ServerCapabilities(emailPasswordAuth = true, tvLogin = true),
            isCustom = true,
        )
        assertTrue(ServerConfigurationRepository.saveCustom(initialServer))
        val harness = installMockBackend(activeUser = TestUser("user-a", "a@example.com", "token-a-keep"))
        val exits = installDesktopLifecycle(harness)
        AuthRepository.initialize()
        awaitAuthenticatedUser("user-a")
        WatchProgressRepository.ensureLoaded()

        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = httpServer.address.port
        val newBackendUrl = "http://127.0.0.1:$port/NextServer"
        httpServer.createContext("/.well-known/nuvio") { exchange ->
            val body = """
                {
                  "version": 1,
                  "service": "nuvio",
                  "self_hosted": true,
                  "backend_url": "$newBackendUrl",
                  "publishable_key": "next-pub-key",
                  "capabilities": { "email_password_auth": true, "tv_login": false, "watch_party": false }
                }
            """.trimIndent().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        httpServer.start()
        try {
            // 1. Cancelled quit during server switch leaves active server, stored server config, AND user session untouched!
            DesktopAccountDataLifecycle.bindWindowRequestQuitHandler { _ ->
                DesktopAccountDataLifecycle.cancelPendingQuit()
            }
            ServerConnectionController.discover("http://127.0.0.1:$port")
            withTimeout(5_000) { ServerConnectionController.state.first { it.discoveredServer != null } }
            ServerConnectionController.connectDiscovered()
            withTimeout(5_000) { ServerConnectionController.state.first { !it.isSwitching } }

            assertEquals(0, exits.get())
            awaitAuthenticatedUser("user-a")
            assertEquals("token-a-keep", harness.sessionManager.loadSession()?.accessToken)
            assertEquals("https://example.com/Team", ServerConfigurationStorage.loadCustom()?.backendUrl)
            assertNull(ServerConnectionController.state.value.switchFailure)

            // 2. Watch-progress drain failure during server switch ALSO leaves user signed in on the original server!
            DesktopAccountDataLifecycle.bindWindowRequestQuitHandler { _ ->
                launch(Dispatchers.Default) {
                    DesktopAccountDataLifecycle.executeConfirmedQuit {
                        exits.incrementAndGet()
                    }
                }
            }
            val unregisterFlush = WatchProgressRepository.registerActivePlayerFlushHook {
                WatchProgressRepository.flushPlaybackProgress(
                    session = WatchProgressPlaybackSession(
                        profileId = 1,
                        contentType = "movie",
                        parentMetaId = "tt7777777",
                        parentMetaType = "movie",
                        videoId = "tt7777777",
                        title = "Unsaved Film",
                    ),
                    snapshot = PlayerPlaybackSnapshot(
                        isLoading = false,
                        isPlaying = false,
                        isEnded = false,
                        durationMs = 5_000_000L,
                        positionMs = 1_500_000L,
                    ),
                    syncRemote = false,
                )
            }
            val blockingTmpDir = rootDir.resolve("nuvio_watch_progress.properties.tmp").also { it.createDirectories() }
            try {
                ServerConnectionController.connectDiscovered()
                withTimeout(5_000) { ServerConnectionController.state.first { !it.isSwitching && it.switchFailure != null } }

                assertEquals(0, exits.get())
                awaitAuthenticatedUser("user-a")
                assertEquals("token-a-keep", harness.sessionManager.loadSession()?.accessToken)
                assertEquals("https://example.com/Team", ServerConfigurationStorage.loadCustom()?.backendUrl)
                assertNotNull(DesktopAccountDataLifecycle.quitError.value)

                // 3. Once disk write is unblocked, confirming server switch clears session, stages new server, and exits.
                Files.move(blockingTmpDir, tempBase.resolve("retired-server-switch-tmp"))
                ServerConnectionController.connectDiscovered()
                withTimeout(5_000) {
                    while (exits.get() == 0) delay(20)
                }
                assertNull(harness.sessionManager.loadSession())
                assertEquals(newBackendUrl, ServerConfigurationStorage.loadCustom()?.backendUrl)
            } finally {
                unregisterFlush()
                DesktopAccountDataLifecycle.bindWindowRequestQuitHandler(null)
            }
        } finally {
            httpServer.stop(0)
        }
    }

    @Test
    fun queued_not_authenticated_callback_behind_same_account_sign_in_does_not_invalidate_session(): Unit = runBlocking {
        val activeToken = AtomicReference("token-a-1")
        val sessionManager = TrackingSessionManager()
        sessionManager.seed(
            UserSession(
                accessToken = "token-a-1",
                refreshToken = "refresh-a-1",
                expiresIn = 3_600L,
                tokenType = "bearer",
                user = UserInfo(id = "user-a", aud = "authenticated", email = "a@example.com"),
            ),
        )
        SupabaseProvider.engineFactory = {
            MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path.endsWith("/auth/v1/token") -> respondJson(tokenSessionJson(TestUser("user-a", "a@example.com", activeToken.get())))
                    path.endsWith("/auth/v1/user") -> respondJson(userJson(TestUser("user-a", "a@example.com", activeToken.get())))
                    path.endsWith("/auth/v1/logout") -> respond("", HttpStatusCode.NoContent)
                    else -> respondJson("[]")
                }
            }
        }
        SupabaseProvider.sessionManagerOverride = sessionManager
        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { rootDir },
            storedSessionUserId = { sessionManager.loadSession()?.user?.id },
            exitApp = {},
        )
        AuthRepository.initialize()
        awaitAuthenticatedUser("user-a")

        val holdNotAuthenticated = AtomicBoolean(true)
        val notAuthPausedBeforeMutex = CompletableDeferred<Unit>()
        val releaseNotAuthCallback = CompletableDeferred<Unit>()
        AuthRepository.beforeHandleSessionStatusForTest = { status ->
            if (status is SessionStatus.NotAuthenticated && holdNotAuthenticated.compareAndSet(true, false)) {
                notAuthPausedBeforeMutex.complete(Unit)
                releaseNotAuthCallback.await()
            }
        }

        // Sign out emits SessionStatus.NotAuthenticated, which pauses right before acquiring authCoordinatorMutex.
        assertTrue(AuthRepository.signOut().isSuccess)
        withTimeout(5_000) { notAuthPausedBeforeMutex.await() }

        // Same-account re-sign-in completes first, importing token-a-2 onto the shared client.
        activeToken.set("token-a-2")
        assertTrue(AuthRepository.signInWithEmail("a@example.com", "pw-a").isSuccess)
        awaitAuthenticatedUser("user-a")

        // Release the stale NotAuthenticated callback: it must NOT mark the fresh session lost or wipe token-a-2!
        releaseNotAuthCallback.complete(Unit)
        delay(80)

        awaitAuthenticatedUser("user-a")
        assertFalse(AuthStorage.loadFlag("signed_out_notice"))
        assertNull(AuthRepository.error.value)
        assertEquals("token-a-2", SupabaseProvider.client.auth.currentSessionOrNull()?.accessToken)
        assertEquals("token-a-2", sessionManager.loadSession()?.accessToken)
    }

    @Test
    fun interrupted_adoption_recovers_profile_migration_and_torn_transition_journal_is_moved_aside(): Unit = runBlocking {
        // Part 1a: Profile write failure during adoption refuses import, leaves AdoptingRemote, blocks user-b, and succeeds on retry.
        Files.writeString(rootDir.resolve("nuvio_library.properties"), "library_payload_1=legacy-adopt")
        ProfileStorage.savePayload(
            """{"userId":"","activeProfileIndex":2,"hasEverSelectedProfile":true,"rememberLastProfileEnabled":true,"profiles":[{"id":"p1","user_id":"","profile_index":1,"name":"Main","avatar_color_hex":"#112233"},{"id":"p2","user_id":"","profile_index":2,"name":"Cinema","avatar_color_hex":"#445566"}]}""",
        )
        val harness = installMockBackend(activeUser = TestUser("user-a", "a@example.com", "token-a-adopt"))
        harness.sessionManager.deleteSession()
        installDesktopLifecycle(harness)
        ProfileRepository.loadCachedProfiles()
        AuthRepository.initialize()
        withTimeout(5_000) { AuthRepository.state.first { it is AuthState.Unauthenticated } }

        assertTrue(AuthRepository.signInWithEmail("a@example.com", "pw-a").isSuccess)
        assertNotNull(AuthRepository.pendingAdoption.value)

        val blockingProfilesTmp = rootDir.resolve("nuvio_profiles.properties.tmp").also { it.createDirectories() }
        val failedConfirm = AuthRepository.confirmPendingAdoption()
        assertTrue(failedConfirm.isFailure, "Profile migration failure must refuse adoption before importing session")
        assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
        assertNull(harness.sessionManager.loadSession())
        assertEquals(
            StampState.Valid(StampOwner.AdoptingRemote("https://default.nuvio.example", "user-a")),
            DesktopAccountDataLifecycle.readStamp(rootDir),
        )

        // Another user (user-b) cannot claim a folder whose adoption started for user-a.
        harness.activeUser.set(TestUser("user-b", "b@example.com", "token-b"))
        assertTrue(AuthRepository.signInWithEmail("b@example.com", "pw-b").isSuccess)
        assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
        assertNull(AuthRepository.pendingAdoption.value)

        Files.move(blockingProfilesTmp, tempBase.resolve("retired-profiles-tmp"))
        harness.activeUser.set(TestUser("user-a", "a@example.com", "token-a-adopt"))
        assertTrue(AuthRepository.signInWithEmail("a@example.com", "pw-a").isSuccess)
        awaitAuthenticatedUser("user-a")
        ProfileRepository.ensureLoaded("user-a")
        assertEquals(2, ProfileRepository.state.value.profiles.size)
        assertEquals(2, ProfileRepository.activeProfileId)
        assertEquals(
            StampState.Valid(StampOwner.Remote("https://default.nuvio.example", "user-a")),
            DesktopAccountDataLifecycle.readStamp(rootDir),
        )

        // Part 1b: Abrupt crash right after AdoptingRemote is written (before profile migration) finishes on next launch and preserves profiles.
        val crashRoot = tempBase.resolve("data-adopt-crash").also { it.createDirectories() }
        AuthRepository.resetForTest()
        ProfileRepository.clearInMemory()
        DesktopAccountDataLifecycle.resetForTest()
        SupabaseProvider.resetForTest()
        DesktopStorage.setRootDirForTest(crashRoot, cacheDir)
        ServerConfigurationRepository.saveCustom(
            ServerConfiguration(
                backendUrl = "https://default.nuvio.example",
                publishableKey = "default-test-key",
                capabilities = ServerCapabilities(emailPasswordAuth = true, tvLogin = true),
                isCustom = true,
            ),
        )
        Files.writeString(crashRoot.resolve("nuvio_library.properties"), "library_payload_1=crash-adopt")
        ProfileStorage.savePayload(
            """{"userId":"","activeProfileIndex":2,"hasEverSelectedProfile":true,"rememberLastProfileEnabled":true,"profiles":[{"id":"p1","user_id":"","profile_index":1,"name":"Main","avatar_color_hex":"#112233"},{"id":"p2","user_id":"","profile_index":2,"name":"Cinema","avatar_color_hex":"#445566"}]}""",
        )
        val crashHarness = installMockBackend(activeUser = TestUser("user-a", "a@example.com", "token-a-crash"))
        crashHarness.sessionManager.deleteSession()
        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { crashRoot },
            storedSessionUserId = { null },
            exitApp = {},
            onArchiveStep = { step ->
                if (step == ArchiveStep.AfterAdoptionStarted) {
                    throw SimulatedInterruptionException("adoption_crash")
                }
            },
        )
        ProfileRepository.loadCachedProfiles()
        AuthRepository.initialize()
        withTimeout(5_000) { AuthRepository.state.first { it is AuthState.Unauthenticated } }
        assertTrue(AuthRepository.signInWithEmail("a@example.com", "pw-a").isSuccess)
        assertNotNull(AuthRepository.pendingAdoption.value)
        val crashedAdopt = runCatching { AuthRepository.confirmPendingAdoption() }
        assertTrue(crashedAdopt.exceptionOrNull() is SimulatedInterruptionException)
        assertEquals(
            StampState.Valid(StampOwner.AdoptingRemote("https://default.nuvio.example", "user-a")),
            DesktopAccountDataLifecycle.readStamp(crashRoot),
        )
        assertTrue("\"userId\":\"\"" in ProfileStorage.loadPayload().orEmpty())

        // Next launch finishes profile migration in bootstrap BEFORE ProfileRepository.loadCachedProfiles / ensureLoaded!
        AuthRepository.resetForTest()
        ProfileRepository.clearInMemory()
        DesktopAccountDataLifecycle.resetForTest()
        DesktopStorage.resetInMemoryStores()
        SupabaseProvider.reset()
        SupabaseProvider.engineFactory = crashHarness.engineFactory
        SupabaseProvider.sessionManagerOverride = crashHarness.sessionManager
        val recoveryExits = AtomicInteger(0)
        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { crashRoot },
            storedSessionUserId = { null },
            nowEpochMs = { 1_700_000_000_000L },
            exitApp = { recoveryExits.incrementAndGet() },
        )
        assertEquals(DesktopLifecycleUiState.Ready, DesktopAccountDataLifecycle.uiState.value)
        assertEquals(
            StampState.Valid(StampOwner.Remote("https://default.nuvio.example", "user-a")),
            DesktopAccountDataLifecycle.readStamp(crashRoot),
        )
        ProfileRepository.loadCachedProfiles()
        AuthRepository.initialize()
        assertTrue(AuthRepository.signInWithEmail("a@example.com", "pw-a").isSuccess)
        awaitAuthenticatedUser("user-a")
        ProfileRepository.ensureLoaded("user-a")
        assertEquals(2, ProfileRepository.state.value.profiles.size, "Recovered adoption must preserve migrated profiles across restart")
        assertEquals(2, ProfileRepository.activeProfileId)

        // Part 2: Torn / unparseable account_transition.json is moved aside into cancelled-transitions/ and leaves folder + Start fresh usable.
        assertTrue(AuthRepository.signOut().isSuccess)
        Files.writeString(crashRoot.resolve(DesktopStorage.TransitionJournalFileName), "{torn-transition-json")
        AuthRepository.resetForTest()
        DesktopAccountDataLifecycle.resetForTest()
        DesktopStorage.resetInMemoryStores()
        SupabaseProvider.reset()
        SupabaseProvider.engineFactory = crashHarness.engineFactory
        SupabaseProvider.sessionManagerOverride = crashHarness.sessionManager
        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { crashRoot },
            storedSessionUserId = { null },
            nowEpochMs = { 1_700_000_555_000L },
            exitApp = { recoveryExits.incrementAndGet() },
        )
        assertEquals(DesktopLifecycleUiState.Ready, DesktopAccountDataLifecycle.uiState.value, "Torn transition journal must not brick future launches")
        assertFalse(crashRoot.resolve(DesktopStorage.TransitionJournalFileName).exists())
        val retiredFiles = Files.list(crashRoot.resolve("cancelled-transitions")).use { it.toList() }
        assertEquals(1, retiredFiles.size)
        assertEquals("{torn-transition-json", Files.readString(retiredFiles.single()))
        assertEquals("library_payload_1=crash-adopt", Files.readString(crashRoot.resolve("nuvio_library.properties")))

        AuthRepository.initialize()
        withTimeout(5_000) { AuthRepository.state.first { it is AuthState.Unauthenticated } }
        assertTrue(AuthRepository.startFresh().isSuccess)
        assertEquals(1, recoveryExits.get())
        assertTrue(crashRoot.resolve(DesktopStorage.TransitionJournalFileName).exists())
    }

    @Test
    fun delayed_refresh_cannot_erase_same_account_re_sign_in_after_lost_session(): Unit = runBlocking {
        for (staleRefreshFails in listOf(false, true)) {
            AuthRepository.resetForTest()
            DesktopAccountDataLifecycle.resetForTest()
            SupabaseProvider.resetForTest()

            val refreshEntered = CompletableDeferred<Unit>()
            val releaseRefresh = CompletableDeferred<Unit>()
            val nextLoginToken = AtomicReference("token-a-fresh-$staleRefreshFails")
            val sessionManager = TrackingSessionManager()
            sessionManager.seed(
                UserSession(
                    accessToken = "token-a-initial-$staleRefreshFails",
                    refreshToken = "refresh-a-initial",
                    expiresIn = 3_600L,
                    tokenType = "bearer",
                    user = UserInfo(id = "user-a", aud = "authenticated", email = "a@example.com"),
                ),
            )
            SupabaseProvider.engineFactory = {
                MockEngine { request ->
                    val path = request.url.encodedPath
                    val query = request.url.encodedQuery
                    when {
                        path.endsWith("/auth/v1/token") && "refresh_token" in query -> {
                            refreshEntered.complete(Unit)
                            releaseRefresh.await()
                            if (staleRefreshFails) {
                                respond(
                                    content = """{"error":"invalid_grant","error_description":"Invalid Refresh Token"}""",
                                    status = HttpStatusCode.BadRequest,
                                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                                )
                            } else {
                                respondJson(tokenSessionJson(TestUser("user-a", "a@example.com", "token-a-stale-refresh")))
                            }
                        }
                        path.endsWith("/auth/v1/token") -> {
                            respondJson(tokenSessionJson(TestUser("user-a", "a@example.com", nextLoginToken.get())))
                        }
                        path.endsWith("/auth/v1/user") -> {
                            respondJson(userJson(TestUser("user-a", "a@example.com", nextLoginToken.get())))
                        }
                        path.endsWith("/auth/v1/logout") -> respond("", HttpStatusCode.NoContent)
                        else -> respondJson("[]")
                    }
                }
            }
            SupabaseProvider.sessionManagerOverride = sessionManager
            DesktopAccountDataLifecycle.bootstrapAndInstall(
                rootDirProvider = { rootDir },
                storedSessionUserId = { sessionManager.loadSession()?.user?.id },
                exitApp = {},
            )
            AuthRepository.initialize()
            awaitAuthenticatedUser("user-a")

            // 1. Repository 1 starts an isolated token refresh on JWT expiry and blocks in flight.
            val delayedRefreshJob = async(Dispatchers.Default) {
                AuthRepository.signOutIfSessionInvalid(RuntimeException("JWT expired"), "repo-1")
            }
            withTimeout(5_000) { refreshEntered.await() }

            // 2. Repository 2 reports a lost/invalidated remote session while Repository 1's refresh is still pending.
            val invalidated = AuthRepository.signOutIfSessionInvalid(
                RuntimeException("User from sub claim in JWT does not exist"),
                "repo-2",
            )
            assertTrue(invalidated)
            assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
            assertNull(sessionManager.loadSession())

            // 3. Same account signs back in and imports a fresh session before Repository 1's delayed refresh returns.
            assertTrue(AuthRepository.signInWithEmail("a@example.com", "pw-a").isSuccess)
            awaitAuthenticatedUser("user-a")
            assertEquals(nextLoginToken.get(), SupabaseProvider.client.auth.currentSessionOrNull()?.accessToken)
            assertEquals(nextLoginToken.get(), sessionManager.loadSession()?.accessToken)

            // 4. Delayed refresh finishes (either succeeding with stale token or failing): fresh session must remain intact!
            releaseRefresh.complete(Unit)
            assertFalse(delayedRefreshJob.await())
            delay(50)

            awaitAuthenticatedUser("user-a")
            assertEquals(nextLoginToken.get(), SupabaseProvider.client.auth.currentSessionOrNull()?.accessToken)
            assertEquals(nextLoginToken.get(), sessionManager.loadSession()?.accessToken)
        }
    }

    @Test
    fun quit_waits_for_finishing_download_to_persist_completion_and_restores_active_download_when_quit_aborts(): Unit = runBlocking {
        val realBackend = DownloadsRepository.backend
        val realHold = DownloadsRepository.connectionHold
        val realCompanions = DownloadsRepository.companions

        class ControllableDownloadsBackend : DownloadsBackend {
            @Volatile
            var payload: String? = null
            val requestsById = Collections.synchronizedMap(mutableMapOf<String, DownloadPlatformRequest>())
            val onSuccessById = Collections.synchronizedMap(mutableMapOf<String, (String, Long?) -> Unit>())
            val startCountById = Collections.synchronizedMap(mutableMapOf<String, Int>())
            val cancelledIds = Collections.synchronizedList(mutableListOf<String>())
            val sampleEnteredForUri = Collections.synchronizedMap(mutableMapOf<String, CompletableDeferred<Unit>>())
            val releaseSampleForUri = Collections.synchronizedMap(mutableMapOf<String, CompletableDeferred<Unit>>())

            override fun loadPayload(): String? = payload

            override fun savePayload(payload: String) {
                this.payload = payload
            }

            override fun restoreItem(item: DownloadItem): DownloadItem = item

            override fun start(
                request: DownloadPlatformRequest,
                onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
                onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
                onFailure: (message: String) -> Unit,
                onPaused: () -> Unit,
            ): DownloadsTaskHandle {
                val id = request.item.id
                requestsById[id] = request
                onSuccessById[id] = onSuccess
                startCountById[id] = (startCountById[id] ?: 0) + 1
                return object : DownloadsTaskHandle {
                    override fun cancel() {
                        cancelledIds += id
                    }

                    override fun cancelAndWait(timeoutMs: Long): Boolean {
                        cancelledIds += id
                        return true
                    }
                }
            }

            override fun removeFile(localFileUri: String?): Boolean = true
            override fun removePartialFile(destinationFileName: String): Boolean = true
            override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri

            override fun inspectFile(localFileUri: String): DownloadedFileFacts =
                DownloadedFileFacts(
                    exists = true,
                    sizeBytes = 50_000_000L,
                    isVideoContainer = true,
                    durationMs = null,
                )

            override fun sampleFrames(localFileUri: String): FrameSamples? {
                sampleEnteredForUri[localFileUri]?.complete(Unit)
                releaseSampleForUri[localFileUri]?.let { gate ->
                    runBlocking { gate.await() }
                }
                return null
            }
        }

        val fakeBackend = ControllableDownloadsBackend()
        val hold = ConnectionHold()
        val wpTmpBlocker = rootDir.resolve("nuvio_watch_progress.properties.tmp")
        val previousRuntime = downloadRuntimeLookup
        try {
            // A known running time and an unknown file length is what asks for a frame look.
            downloadRuntimeLookup = { 45 }
            DownloadsRepository.clearLocalState()
            DownloadsRepository.backend = fakeBackend
            DownloadsRepository.connectionHold = hold
            DownloadsRepository.companions = { DownloadCompanions(emptyList()) }
            DownloadsRepository.ensureLoaded()

            val harness = installMockBackend(activeUser = TestUser("user-a", "a@example.com", "token-a"))
            val exits = installDesktopLifecycle(harness)
            AuthRepository.initialize()
            awaitAuthenticatedUser("user-a")
            WatchProgressRepository.ensureLoaded()

            fun enqueueEpisode(ep: Int): String {
                DownloadsRepository.enqueueFromStream(
                    contentType = "series",
                    videoId = "tt777:1:$ep",
                    parentMetaId = "tt777",
                    parentMetaType = "series",
                    title = "Series",
                    logo = null,
                    poster = null,
                    background = null,
                    seasonNumber = 1,
                    episodeNumber = ep,
                    episodeTitle = "Episode $ep",
                    episodeThumbnail = null,
                    stream = StreamItem(
                        name = "1080p",
                        url = "https://cdn.example.com/ep$ep.mkv",
                        addonName = "Addon",
                        addonId = "addon.example",
                        behaviorHints = StreamBehaviorHints(),
                    ),
                )
                return DownloadsRepository.uiState.value.items.first { it.episodeNumber == ep }.id
            }

            val finishingId = enqueueEpisode(1)
            val activeId = enqueueEpisode(2)
            val finishedUri1 = rootDir.resolve("downloads/ep1.mkv").also {
                it.parent.createDirectories()
                Files.writeString(it, "video-ep1")
            }.toUri().toString()

            val sample1Entered = CompletableDeferred<Unit>()
            val releaseSample1 = CompletableDeferred<Unit>()
            fakeBackend.sampleEnteredForUri[finishedUri1] = sample1Entered
            fakeBackend.releaseSampleForUri[finishedUri1] = releaseSample1

            assertTrue(fakeBackend.requestsById.getValue(finishingId).mayPublish())
            val finishJob1 = async(Dispatchers.Default) {
                fakeBackend.onSuccessById.getValue(finishingId).invoke(finishedUri1, 50_000_000L)
            }
            withTimeout(5_000) { sample1Entered.await() }

            // Force watch-progress drain to fail AFTER stopActiveTransfersForQuit runs, aborting quit!
            val unregisterFlush = WatchProgressRepository.registerActivePlayerFlushHook {
                WatchProgressRepository.flushPlaybackProgress(
                    session = WatchProgressPlaybackSession(
                        profileId = 1,
                        contentType = "movie",
                        parentMetaId = "tt001",
                        parentMetaType = "movie",
                        videoId = "tt001",
                        title = "Unsaved Movie",
                    ),
                    snapshot = PlayerPlaybackSnapshot(
                        isLoading = false,
                        isPlaying = true,
                        isEnded = false,
                        durationMs = 5_000_000L,
                        positionMs = 1_200_000L,
                    ),
                    syncRemote = false,
                )
            }
            try {
                Files.createDirectories(wpTmpBlocker)
                val abortedQuit = async(Dispatchers.Default) {
                    DesktopAccountDataLifecycle.executeConfirmedQuit {
                        exits.incrementAndGet()
                    }
                }
                delay(80)
                // stopActiveTransfersForQuit must still be waiting on finishingId rather than cancelling/orphaning it!
                assertFalse(abortedQuit.isCompleted)
                assertFalse(finishingId in fakeBackend.cancelledIds, "Finishing transfer must not be cancelled during quit")
                releaseSample1.complete(Unit)
                finishJob1.await()

                val quitResult = abortedQuit.await()
                assertTrue(quitResult.isFailure, "Quit must abort when watch-progress drain fails")
                assertEquals(0, exits.get())

                // 1. Finishing download completed and saved its final localFileUri to the download list!
                val completedItem = DownloadsRepository.uiState.value.items.single { it.id == finishingId }
                assertEquals(DownloadStatus.Completed, completedItem.status)
                assertEquals(finishedUri1, completedItem.localFileUri)
                val decodedStored = DownloadsCodec.decode(requireNotNull(fakeBackend.payload))
                assertEquals(
                    finishedUri1,
                    decodedStored?.items?.single { it.id == finishingId }?.localFileUri,
                )

                // 2. Non-finishing download was restored after quit aborted (not stranded in Downloading with no transfer)!
                val restoredActiveItem = DownloadsRepository.uiState.value.items.single { it.id == activeId }
                assertEquals(DownloadStatus.Downloading, restoredActiveItem.status)
                assertTrue(DownloadsRepository.hasActiveTransferForTest(activeId), "Aborted quit must restore active transfer")
                assertEquals(2, fakeBackend.startCountById[activeId])

                // And user controls (such as Pause) still work cleanly on the restored download.
                DownloadsRepository.pauseDownload(activeId)
                assertEquals(
                    DownloadStatus.Paused,
                    DownloadsRepository.uiState.value.items.single { it.id == activeId }.status,
                )

                // 3. Now verify a confirmed quit that succeeds also waits for a second finishing download and persists its URI before exiting.
                Files.deleteIfExists(wpTmpBlocker)
                val finishingId2 = enqueueEpisode(3)
                val finishedUri3 = rootDir.resolve("downloads/ep3.mkv").also {
                    Files.writeString(it, "video-ep3")
                }.toUri().toString()
                val sample3Entered = CompletableDeferred<Unit>()
                val releaseSample3 = CompletableDeferred<Unit>()
                fakeBackend.sampleEnteredForUri[finishedUri3] = sample3Entered
                fakeBackend.releaseSampleForUri[finishedUri3] = releaseSample3
                assertTrue(fakeBackend.requestsById.getValue(finishingId2).mayPublish())
                val finishJob3 = async(Dispatchers.Default) {
                    fakeBackend.onSuccessById.getValue(finishingId2).invoke(finishedUri3, 50_000_000L)
                }
                withTimeout(5_000) { sample3Entered.await() }

                val confirmedQuit = async(Dispatchers.Default) {
                    DesktopAccountDataLifecycle.executeConfirmedQuit {
                        exits.incrementAndGet()
                    }
                }
                delay(80)
                assertFalse(confirmedQuit.isCompleted)
                releaseSample3.complete(Unit)
                finishJob3.await()
                assertTrue(confirmedQuit.await().isSuccess)
                assertEquals(1, exits.get())

                // Simulate reopen from persisted payload: both finished downloads have Completed + localFileUri!
                DownloadsRepository.clearLocalState()
                DownloadsRepository.ensureLoaded()
                val reloadedItems = DownloadsRepository.uiState.value.items.associateBy { it.id }
                assertEquals(DownloadStatus.Completed, reloadedItems.getValue(finishingId).status)
                assertEquals(finishedUri1, reloadedItems.getValue(finishingId).localFileUri)
                assertEquals(DownloadStatus.Completed, reloadedItems.getValue(finishingId2).status)
                assertEquals(finishedUri3, reloadedItems.getValue(finishingId2).localFileUri)
            } finally {
                unregisterFlush()
            }
        } finally {
            downloadRuntimeLookup = previousRuntime
            wpTmpBlocker.toFile().deleteRecursively()
            DownloadsRepository.clearLocalState()
            DownloadsRepository.backend = realBackend
            DownloadsRepository.connectionHold = realHold
            DownloadsRepository.companions = realCompanions
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun real_player_runtime_pauses_before_quit_drain_and_skips_post_drain_dispose_save() {
        val harness = runBlocking { installMockBackend(activeUser = TestUser("user-a", "a@example.com", "token-a")) }
        val exits = installDesktopLifecycle(harness)
        runBlocking {
            AuthRepository.initialize()
            awaitAuthenticatedUser("user-a")
            WatchProgressRepository.ensureLoaded()
        }

        class FakePlayerController : PlayerEngineController {
            val pauseCount = AtomicInteger(0)
            override fun play() = Unit
            override fun pause() {
                pauseCount.incrementAndGet()
            }
            override fun seekTo(positionMs: Long) = Unit
            override fun seekBy(offsetMs: Long) = Unit
            override fun retry() = Unit
            override fun setPlaybackSpeed(speed: Float) = Unit
            override fun getAudioTracks(): List<AudioTrack> = emptyList()
            override fun getSubtitleTracks(): List<SubtitleTrack> = emptyList()
            override fun applyAudioLanguagePreferences(languages: List<String>) = Unit
            override fun selectAudioTrack(index: Int) = Unit
            override fun selectSubtitleTrack(index: Int) = Unit
            override fun setSubtitleUri(url: String) = Unit
            override fun clearExternalSubtitle() = Unit
            override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
        }

        val controller = FakePlayerController()
        val args = PlayerScreenArgs(
            profileId = 1,
            title = "Runtime Quit Film",
            sourceUrl = "https://cdn.example.com/film.mp4",
            sourceAudioUrl = null,
            sourceHeaders = emptyMap(),
            sourceResponseHeaders = emptyMap(),
            streamType = "mp4",
            providerName = "Provider",
            streamTitle = "1080p",
            streamSubtitle = null,
            initialBingeGroup = null,
            pauseDescription = null,
            onBack = { release -> release({}, {}) },
            onOpenInExternalPlayer = null,
            onOpenExternalUrl = null,
            modifier = Modifier,
            logo = null,
            poster = null,
            background = null,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            episodeThumbnail = null,
            contentType = "movie",
            videoId = "tt5555555",
            parentMetaId = "tt5555555",
            parentMetaType = "movie",
            providerAddonId = "addon.example",
            torrentInfoHash = null,
            torrentFileIdx = null,
            torrentFilename = null,
            torrentTrackers = emptyList(),
            initialPositionMs = 0L,
            initialProgressFraction = null,
        )
        val runtime = PlayerScreenRuntime(args)
        val wpTmpBlocker = rootDir.resolve("nuvio_watch_progress.properties.tmp")

        try {
            runComposeUiTest {
                var showPlayer by mutableStateOf(true)
                setContent {
                    NuvioTheme {
                        if (showPlayer) {
                            runtime.scope = rememberCoroutineScope()
                            runtime.playerController = controller
                            runtime.BindPlayerUiVisibilityEffects()
                            runtime.BindPlayerQuitAndDisposeEffects()
                            Text("Player Mounted")
                        } else {
                            Text("Player Disposed")
                        }
                    }
                }
                waitForIdle()
                onNodeWithText("Player Mounted").assertIsDisplayed()

                runOnIdle {
                    assertTrue(
                        runtime.updatePlaybackSnapshot(
                            PlayerPlaybackSnapshot(
                                isLoading = false,
                                isPlaying = true,
                                isEnded = false,
                                durationMs = 5_400_000L,
                                positionMs = 1_800_000L,
                            ),
                        ),
                    )
                }
                waitForIdle()

                // 1. Aborted quit pauses playback, flushes, fails drain, and unfreezes runtime so later playback updates still work.
                Files.createDirectories(wpTmpBlocker)
                val aborted = runBlocking {
                    DesktopAccountDataLifecycle.executeConfirmedQuit {
                        showPlayer = false
                        exits.incrementAndGet()
                    }
                }
                assertTrue(aborted.isFailure)
                assertEquals(1, controller.pauseCount.get())
                assertTrue(showPlayer)
                assertFalse(runtime.frozenForQuit, "Aborted quit must unfreeze player runtime")

                runOnIdle {
                    assertTrue(
                        runtime.updatePlaybackSnapshot(
                            PlayerPlaybackSnapshot(
                                isLoading = false,
                                isPlaying = true,
                                isEnded = false,
                                durationMs = 5_400_000L,
                                positionMs = 1_950_000L,
                            ),
                        ),
                    )
                }
                waitForIdle()

                // 2. Confirmed quit with slow persistence drain: late engine ticks during drain are ignored and onDispose queues NO new save!
                Files.deleteIfExists(wpTmpBlocker)
                val drainEntered = CompletableDeferred<Unit>()
                val releaseDrain = CompletableDeferred<Unit>()
                WatchProgressRepository.beforePersistWriteForTest = {
                    drainEntered.complete(Unit)
                    releaseDrain.await()
                }

                val quitDeferred = CompletableDeferred<Result<Unit>>()
                val quitThread = Thread {
                    val res = runBlocking {
                        DesktopAccountDataLifecycle.executeConfirmedQuit {
                            showPlayer = false
                            exits.incrementAndGet()
                        }
                    }
                    quitDeferred.complete(res)
                }.apply { isDaemon = true; start() }

                runBlocking { withTimeout(5_000) { drainEntered.await() } }
                assertEquals(2, controller.pauseCount.get(), "Player must be paused before persistence drain writes")
                assertTrue(runtime.frozenForQuit)

                // Simulate decoder/engine firing late snapshot ticks while persistence drain is still in progress.
                runOnIdle {
                    val accepted = runtime.updatePlaybackSnapshot(
                        PlayerPlaybackSnapshot(
                            isLoading = false,
                            isPlaying = true,
                            isEnded = false,
                            durationMs = 5_400_000L,
                            positionMs = 2_345_000L,
                        ),
                    )
                    assertFalse(accepted, "Frozen player runtime must ignore late playback snapshots during quit drain")
                    runtime.persistPlaybackProgressTick()
                }
                waitForIdle()

                WatchProgressRepository.beforePersistWriteForTest = null
                releaseDrain.complete(Unit)
                val finalQuit = runBlocking { withTimeout(5_000) { quitDeferred.await() } }
                quitThread.join(5_000)
                assertTrue(finalQuit.isSuccess)
                assertEquals(1, exits.get())

                // Drive Compose disposal of PlayerScreenRuntime effects (the real onDispose path).
                waitForIdle()
                onNodeWithText("Player Disposed").assertIsDisplayed()
                assertFalse(
                    WatchProgressRepository.hasPendingOrActivePersistenceForTest(),
                    "Disposing player after quit drain must not queue another un-drained watch-progress write",
                )

                WatchProgressRepository.clearLocalState()
                WatchProgressRepository.ensureLoaded()
                val savedEntry = WatchProgressRepository.progressForVideo(
                    videoId = "tt5555555",
                    parentMetaId = "tt5555555",
                )
                assertNotNull(savedEntry)
                assertEquals(1_950_000L, savedEntry.lastPositionMs)
            }
        } finally {
            WatchProgressRepository.beforePersistWriteForTest = null
            wpTmpBlocker.toFile().deleteRecursively()
        }
    }

    @Test
    fun mobile_account_deletion_wipes_in_place_and_allows_immediate_re_sign_in_and_guest_entry(): Unit = runBlocking {
        AccountDataLifecycle.resetToDefault()
        val activeUser = AtomicReference(TestUser("user-a", "a@example.com", "token-mobile-a"))
        val sessionManager = TrackingSessionManager()
        sessionManager.seed(
            UserSession(
                accessToken = "token-mobile-a",
                refreshToken = "refresh-mobile-a",
                expiresIn = 3_600L,
                tokenType = "bearer",
                user = UserInfo(id = "user-a", aud = "authenticated", email = "a@example.com"),
            ),
        )
        SupabaseProvider.engineFactory = {
            MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path.contains("/functions/v1/delete-account") -> respondJson("{}")
                    path.contains("/rpc/start_device_login_session") -> respondJson(
                        """[{"device_code":"dev-mob","user_code":"ABCD","verification_uri_complete":"https://nuvio.example/tv?c=ABCD","poll_interval_seconds":5}]""",
                    )
                    path.contains("/rpc/poll_tv_login_session") -> respondJson("""[{"status":"pending"}]""")
                    path.endsWith("/auth/v1/token") -> respondJson(tokenSessionJson(activeUser.get()))
                    path.endsWith("/auth/v1/user") -> respondJson(userJson(activeUser.get()))
                    path.endsWith("/auth/v1/logout") -> respond("", HttpStatusCode.NoContent)
                    else -> respondJson("[]")
                }
            }
        }
        SupabaseProvider.sessionManagerOverride = sessionManager

        AuthRepository.initialize()
        awaitAuthenticatedUser("user-a")
        LibraryRepository.ensureLoaded()
        LibraryRepository.save(
            LibraryItem(
                id = "tt0133093",
                type = "movie",
                name = "The Matrix",
                savedAtEpochMs = 1_700_000_000_000L,
            ),
        )
        assertEquals(1, LibraryRepository.uiState.value.items.size)

        // Delete account using DefaultMobileAccountDataLifecycleHandler (wipes in place without exiting).
        val deleteOutcome = AuthRepository.deleteAccount()
        assertTrue(deleteOutcome.isSuccess)
        assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
        assertFalse(AccountDataLifecycle.isTransitionQuitPending())
        assertTrue(LibraryRepository.uiState.value.items.isEmpty(), "Mobile account deletion must wipe local library")

        // Immediately start device link, enter guest mode, and sign in with email without restarting!
        DeviceLinkAuthRepository.start()
        val linkState = withTimeout(5_000) {
            DeviceLinkAuthRepository.state.first {
                it is DeviceLinkAuthState.Waiting || it is DeviceLinkAuthState.Failed
            }
        }
        assertTrue(linkState is DeviceLinkAuthState.Waiting, "Device link must not be blocked after mobile account deletion")
        DeviceLinkAuthRepository.cancel()

        AuthRepository.signInAnonymously()
        val anonState = AuthRepository.state.value as? AuthState.Authenticated
        assertNotNull(anonState, "Guest sign-in must succeed after mobile account deletion")
        assertTrue(anonState.isAnonymous)
        assertTrue(AuthRepository.signOut().isSuccess)

        activeUser.set(TestUser("user-b", "b@example.com", "token-mobile-b"))
        assertTrue(AuthRepository.signInWithEmail("b@example.com", "pw-b").isSuccess)
        awaitAuthenticatedUser("user-b")
        assertEquals("token-mobile-b", sessionManager.loadSession()?.accessToken)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun production_quit_coordinator_survives_window_recompositions_for_start_fresh_delete_and_server_switch() {
        val realBackend = DownloadsRepository.backend
        val realHold = DownloadsRepository.connectionHold
        val realCompanions = DownloadsRepository.companions

        class QuietBackend : DownloadsBackend {
            var payload: String? = null
            override fun loadPayload(): String? = payload
            override fun savePayload(payload: String) {
                this.payload = payload
            }
            override fun restoreItem(item: DownloadItem): DownloadItem = item
            override fun start(
                request: DownloadPlatformRequest,
                onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
                onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
                onFailure: (message: String) -> Unit,
                onPaused: () -> Unit,
            ): DownloadsTaskHandle = object : DownloadsTaskHandle {
                override fun cancel() = Unit
                override fun cancelAndWait(timeoutMs: Long): Boolean = true
            }
            override fun removeFile(localFileUri: String?): Boolean = true
            override fun removePartialFile(destinationFileName: String): Boolean = true
            override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri
        }

        val quietBackend = QuietBackend()
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = httpServer.address.port
        val nextServerUrl = "http://127.0.0.1:$port/RecomposeServer"
        httpServer.createContext("/.well-known/nuvio") { exchange ->
            val body = """
                {
                  "version": 1,
                  "service": "nuvio",
                  "self_hosted": true,
                  "backend_url": "$nextServerUrl",
                  "publishable_key": "recompose-pub-key",
                  "capabilities": { "email_password_auth": true, "tv_login": false, "watch_party": false }
                }
            """.trimIndent().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        httpServer.start()

        try {
            DownloadsRepository.clearLocalState()
            DownloadsRepository.backend = quietBackend
            DownloadsRepository.connectionHold = ConnectionHold()
            DownloadsRepository.companions = { DownloadCompanions(emptyList()) }
            DownloadsRepository.ensureLoaded()

            fun enqueueRunningDownload(ep: Int) {
                DownloadsRepository.enqueueFromStream(
                    contentType = "series",
                    videoId = "tt888:1:$ep",
                    parentMetaId = "tt888",
                    parentMetaType = "series",
                    title = "Active Show",
                    logo = null,
                    poster = null,
                    background = null,
                    seasonNumber = 1,
                    episodeNumber = ep,
                    episodeTitle = "Ep $ep",
                    episodeThumbnail = null,
                    stream = StreamItem(
                        name = "1080p",
                        url = "https://cdn.example.com/active-$ep.mkv",
                        addonName = "Addon",
                        addonId = "addon.example",
                        behaviorHints = StreamBehaviorHints(),
                    ),
                )
            }

            val exits = AtomicInteger(0)
            val activeUser = AtomicReference(TestUser("user-a", "a@example.com", "token-recompose-a"))
            val sessionManager = TrackingSessionManager()
            sessionManager.seed(
                UserSession(
                    accessToken = "token-recompose-a",
                    refreshToken = "refresh-recompose-a",
                    expiresIn = 3_600L,
                    tokenType = "bearer",
                    user = UserInfo(id = "user-a", aud = "authenticated", email = "a@example.com"),
                ),
            )
            val engineFactory = {
                MockEngine { request ->
                    val path = request.url.encodedPath
                    when {
                        path.contains("/functions/v1/delete-account") -> respondJson("{}")
                        path.endsWith("/auth/v1/token") -> respondJson(tokenSessionJson(activeUser.get()))
                        path.endsWith("/auth/v1/user") -> respondJson(userJson(activeUser.get()))
                        path.endsWith("/auth/v1/logout") -> respond("", HttpStatusCode.NoContent)
                        else -> respondJson("[]")
                    }
                }
            }
            SupabaseProvider.engineFactory = engineFactory
            SupabaseProvider.sessionManagerOverride = sessionManager
            DesktopAccountDataLifecycle.bootstrapAndInstall(
                rootDirProvider = { rootDir },
                storedSessionUserId = { sessionManager.loadSession()?.user?.id },
                exitApp = { exits.incrementAndGet() },
            )
            runBlocking {
                AuthRepository.initialize()
                awaitAuthenticatedUser("user-a")
                assertTrue(AuthRepository.signOut().isSuccess)
            }
            enqueueRunningDownload(1)

            runComposeUiTest {
                var recomposeTick by mutableStateOf(0)
                setContent {
                    val appScope = rememberCoroutineScope()
                    val coordinator = rememberDesktopQuitCoordinator(
                        appScope = appScope,
                        onExitApplication = { exits.incrementAndGet() },
                    )
                    NuvioTheme {
                        Box {
                            Text("Tick $recomposeTick / Quitting ${coordinator.isQuitting}")
                            coordinator.askedAboutDownloads?.let { running ->
                                QuitWithDownloadsDialog(
                                    activeDownloads = running,
                                    onQuit = coordinator.confirmQuitNow,
                                    onKeepOpen = coordinator.keepOpen,
                                )
                            }
                        }
                    }
                }
                waitForIdle()

                // 1a. Clicking "Keep downloading" cancels Start fresh cleanly without writing account_transition.json.
                val cancelledDeferred = CompletableDeferred<Result<Unit>>()
                val cancelledThread = Thread {
                    cancelledDeferred.complete(runBlocking { AuthRepository.startFresh() })
                }.apply { isDaemon = true; start() }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("Downloads are still running. Quit anyway?").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText("Keep downloading").performClick()
                val cancelledResult = runBlocking { withTimeout(5_000) { cancelledDeferred.await() } }
                cancelledThread.join(5_000)
                waitForIdle()
                assertTrue(cancelledResult.isSuccess)
                assertEquals(0, exits.get())
                assertFalse(rootDir.resolve(DesktopStorage.TransitionJournalFileName).exists())

                // 1b. Aborted Start fresh (watch-progress drain failure) restores active download and allows immediate retry!
                WatchProgressRepository.ensureLoaded()
                val wpBlocker = rootDir.resolve("nuvio_watch_progress.properties.tmp").also { it.createDirectories() }
                val unregisterRetryFlush = WatchProgressRepository.registerActivePlayerFlushHook {
                    WatchProgressRepository.flushPlaybackProgress(
                        session = WatchProgressPlaybackSession(
                            profileId = 1,
                            contentType = "movie",
                            parentMetaId = "tt7777777",
                            parentMetaType = "movie",
                            videoId = "tt7777777",
                            title = "Retry Movie",
                        ),
                        snapshot = PlayerPlaybackSnapshot(
                            isLoading = false,
                            isPlaying = true,
                            isEnded = false,
                            durationMs = 3_600_000L,
                            positionMs = 600_000L,
                        ),
                        syncRemote = false,
                    )
                }
                val startFreshDeferred = CompletableDeferred<Result<Unit>>()
                var startFreshThread: Thread? = null
                try {
                    val abortedDeferred = CompletableDeferred<Result<Unit>>()
                    val abortedThread = Thread {
                        val failed = runBlocking { AuthRepository.startFresh() }
                        abortedDeferred.complete(failed)
                        if (failed.isFailure) {
                            Files.deleteIfExists(wpBlocker)
                            // Immediate retry on the background thread before waitForIdle() runs!
                            startFreshThread = Thread {
                                startFreshDeferred.complete(runBlocking { AuthRepository.startFresh() })
                            }.apply { isDaemon = true; start() }
                        }
                    }.apply { isDaemon = true; start() }

                    waitUntil(timeoutMillis = 5_000) {
                        onAllNodesWithText("Downloads are still running. Quit anyway?").fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithText("Quit").performClick()
                    val abortedRes = runBlocking { withTimeout(5_000) { abortedDeferred.await() } }
                    abortedThread.join(5_000)
                    assertTrue(abortedRes.isFailure, "Failed watch-progress drain must abort Start fresh")
                    assertEquals(0, exits.get())

                    // 1c. Immediate retry with restored active download opens QuitWithDownloadsDialog and survives recompositions!
                    waitUntil(timeoutMillis = 5_000) {
                        onAllNodesWithText("Downloads are still running. Quit anyway?").fetchSemanticsNodes().isNotEmpty()
                    }
                    repeat(3) {
                        runOnIdle { recomposeTick += 1 }
                        waitForIdle()
                    }
                    assertFalse(
                        startFreshDeferred.isCompleted,
                        "Window recompositions while QuitWithDownloadsDialog is open must not cancel Start fresh",
                    )
                    // Sign-in must remain blocked while the quit confirmation dialog is open!
                    runBlocking {
                        assertTrue(AuthRepository.signInWithEmail("a@example.com", "pw-a").isSuccess)
                        assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
                    }

                    onNodeWithText("Quit").performClick()
                    val startFreshResult = runBlocking { withTimeout(5_000) { startFreshDeferred.await() } }
                    startFreshThread?.join(5_000)
                    waitForIdle()
                    assertTrue(startFreshResult.isSuccess)
                    assertEquals(1, exits.get())
                    assertTrue(
                        rootDir.resolve(DesktopStorage.TransitionJournalFileName).exists(),
                        "Confirmed Start fresh after dialog + recompositions must write account_transition.json",
                    )
                } finally {
                    wpBlocker.toFile().deleteRecursively()
                    unregisterRetryFlush()
                }
            }

            // Mount a second Compose window session to verify both desktop Server switch and deleteAccount through rememberDesktopQuitCoordinator.
            runBlocking {
                AuthRepository.resetForTest()
                WatchProgressRepository.resetForTest()
                DesktopAccountDataLifecycle.resetForTest()
                SupabaseProvider.reset()
            }
            val subRoot = tempBase.resolve("sub-recompose-root").also { it.createDirectories() }
            DesktopStorage.setRootDirForTest(subRoot, cacheDir)
            allowLocalFixtureServer()
            assertTrue(
                ServerConfigurationRepository.saveCustom(
                    ServerConfiguration(
                        backendUrl = "https://default.nuvio.example",
                        publishableKey = "default-test-key",
                        capabilities = ServerCapabilities(emailPasswordAuth = true, tvLogin = true, watchParty = true),
                        isCustom = true,
                    ),
                ),
            )
            SupabaseProvider.engineFactory = engineFactory
            SupabaseProvider.sessionManagerOverride = sessionManager
            exits.set(0)
            DesktopAccountDataLifecycle.bootstrapAndInstall(
                rootDirProvider = { subRoot },
                storedSessionUserId = { null },
                exitApp = { exits.incrementAndGet() },
            )
            runBlocking {
                AuthRepository.initialize()
                withTimeout(5_000) { AuthRepository.state.first { it is AuthState.Unauthenticated } }
                assertTrue(AuthRepository.signInWithEmail("a@example.com", "pw-a").isSuccess)
                awaitAuthenticatedUser("user-a")
            }
            DownloadsRepository.clearLocalState()
            DownloadsRepository.ensureLoaded()
            enqueueRunningDownload(2)

            runComposeUiTest {
                var recomposeTick by mutableStateOf(0)
                var windowMounted by mutableStateOf(true)
                setContent {
                    if (windowMounted) {
                        val appScope = rememberCoroutineScope()
                        val coordinator = rememberDesktopQuitCoordinator(
                            appScope = appScope,
                            onExitApplication = { exits.incrementAndGet() },
                        )
                        NuvioTheme {
                            Box {
                                Text("SubTick $recomposeTick / Quitting ${coordinator.isQuitting}")
                                coordinator.askedAboutDownloads?.let { running ->
                                    QuitWithDownloadsDialog(
                                        activeDownloads = running,
                                        onQuit = coordinator.confirmQuitNow,
                                        onKeepOpen = coordinator.keepOpen,
                                    )
                                }
                            }
                        }
                    }
                }
                waitForIdle()

                // Server switch with an active download + recompositions stages new server config and exits on Quit!
                ServerConnectionController.discover("http://127.0.0.1:$port")
                runBlocking {
                    withTimeout(5_000) { ServerConnectionController.state.first { it.discoveredServer != null } }
                }
                ServerConnectionController.connectDiscovered()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("Downloads are still running. Quit anyway?").fetchSemanticsNodes().isNotEmpty()
                }
                repeat(3) {
                    runOnIdle { recomposeTick += 1 }
                    waitForIdle()
                }
                assertTrue(ServerConnectionController.state.value.isSwitching, "Server switch must remain pending across recompositions")
                onNodeWithText("Quit").performClick()
                runBlocking {
                    withTimeout(5_000) { ServerConnectionController.state.first { !it.isSwitching } }
                }
                waitForIdle()
                assertEquals(1, exits.get())
                assertEquals(nextServerUrl, ServerConfigurationStorage.loadCustom()?.backendUrl)
                assertNull(runBlocking { sessionManager.loadSession() })

                // Remount window after simulated reopen on nextServerUrl, sign in, and run deleteAccount with active download!
                val stagedCustomServer = requireNotNull(ServerConfigurationStorage.loadCustom())
                runOnIdle { windowMounted = false }
                waitForIdle()

                runBlocking {
                    AuthRepository.resetForTest()
                    WatchProgressRepository.resetForTest()
                    DesktopAccountDataLifecycle.resetForTest()
                    SupabaseProvider.reset()
                }
                val deleteRoot = tempBase.resolve("delete-window-root").also { it.createDirectories() }
                DesktopStorage.setRootDirForTest(deleteRoot, cacheDir)
                assertTrue(ServerConfigurationRepository.saveCustom(stagedCustomServer))
                SupabaseProvider.engineFactory = engineFactory
                SupabaseProvider.sessionManagerOverride = sessionManager
                exits.set(0)
                DesktopAccountDataLifecycle.bootstrapAndInstall(
                    rootDirProvider = { deleteRoot },
                    storedSessionUserId = { null },
                    exitApp = { exits.incrementAndGet() },
                )
                runBlocking {
                    AuthRepository.initialize()
                    withTimeout(5_000) { AuthRepository.state.first { it is AuthState.Unauthenticated } }
                    assertTrue(AuthRepository.signInWithEmail("a@example.com", "pw-a").isSuccess)
                    awaitAuthenticatedUser("user-a")
                }
                DownloadsRepository.clearLocalState()
                DownloadsRepository.ensureLoaded()
                enqueueRunningDownload(3)

                runOnIdle { windowMounted = true }
                waitForIdle()

                val deleteDeferred = CompletableDeferred<Result<Unit>>()
                val deleteThread = Thread {
                    deleteDeferred.complete(runBlocking { AuthRepository.deleteAccount() })
                }.apply { isDaemon = true; start() }
                runOnIdle { recomposeTick += 1 }
                val deleteRes = runBlocking { withTimeout(5_000) { deleteDeferred.await() } }
                deleteThread.join(5_000)
                waitForIdle()
                assertTrue(deleteRes.isSuccess)
                assertEquals(1, exits.get())
                assertTrue(
                    deleteRoot.resolve(DesktopStorage.TransitionJournalFileName).exists(),
                    "deleteAccount must write account_transition.json despite isQuitting recomposition",
                )
            }
        } finally {
            httpServer.stop(0)
            DownloadsRepository.clearLocalState()
            DownloadsRepository.backend = realBackend
            DownloadsRepository.connectionHold = realHold
            DownloadsRepository.companions = realCompanions
        }
    }

    @Test
    fun start_fresh_and_server_switch_keep_saved_session_until_quit_and_staging_succeed(): Unit = runBlocking {
        // Part A: Conflicted folder preserves its saved session on startup, AND keeps it if Start fresh is refused, cancelled, or fails to drain!
        val harness = installMockBackend(activeUser = TestUser("user-a", "a@example.com", "token-conflicted-a"))
        ProfileStorage.savePayload("""{"userId":"user-b","activeProfileIndex":1,"profiles":[]}""")
        AuthStorage.saveFlag("had_account_session", true)
        DesktopStorage.resetInMemoryStores()

        val movesClean = AtomicBoolean(false)
        val exits = AtomicInteger(0)
        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { rootDir },
            storedSessionUserId = { harness.sessionManager.loadSession()?.user?.id },
            exitApp = { exits.incrementAndGet() },
            moveCleanCheck = { movesClean.get() },
        )
        assertEquals(
            StampState.Valid(StampOwner.Conflicted(sessionUserId = "user-a", profileUserId = "user-b")),
            DesktopAccountDataLifecycle.readStamp(rootDir),
        )
        AuthRepository.initialize()
        withTimeout(5_000) { AuthRepository.state.first { it is AuthState.Unauthenticated } }
        assertEquals("token-conflicted-a", harness.sessionManager.loadSession()?.accessToken)
        assertTrue(AuthStorage.loadFlag("had_account_session"))

        // 1. Unresolved download move blocks Start fresh BEFORE touching the saved session or flags!
        val blockedByMove = AuthRepository.startFresh()
        assertTrue(blockedByMove.isFailure)
        assertEquals("token-conflicted-a", harness.sessionManager.loadSession()?.accessToken)
        assertTrue(AuthStorage.loadFlag("had_account_session"))

        // 2. Cancelled quit ("Keep downloading") preserves the saved session and flags!
        movesClean.set(true)
        DesktopAccountDataLifecycle.bindWindowRequestQuitHandler { _ ->
            DesktopAccountDataLifecycle.cancelPendingQuit()
        }
        val cancelledFresh = AuthRepository.startFresh()
        assertTrue(cancelledFresh.isSuccess)
        assertEquals(0, exits.get())
        assertEquals("token-conflicted-a", harness.sessionManager.loadSession()?.accessToken)
        assertTrue(AuthStorage.loadFlag("had_account_session"))

        // 3. Watch-progress drain failure during confirmed Start fresh ALSO preserves the saved session and flags!
        WatchProgressRepository.ensureLoaded()
        val wpTmpBlocker = rootDir.resolve("nuvio_watch_progress.properties.tmp")
        val unregisterFlush = WatchProgressRepository.registerActivePlayerFlushHook {
            WatchProgressRepository.flushPlaybackProgress(
                session = WatchProgressPlaybackSession(
                    profileId = 1,
                    contentType = "movie",
                    parentMetaId = "tt009",
                    parentMetaType = "movie",
                    videoId = "tt009",
                    title = "Drain Fail",
                ),
                snapshot = PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = true,
                    isEnded = false,
                    durationMs = 4_000_000L,
                    positionMs = 900_000L,
                ),
                syncRemote = false,
            )
        }
        try {
            DesktopAccountDataLifecycle.bindWindowRequestQuitHandler { _ ->
                launch(Dispatchers.Default) {
                    DesktopAccountDataLifecycle.executeConfirmedQuit {
                        exits.incrementAndGet()
                    }
                }
            }
            Files.createDirectories(wpTmpBlocker)
            val failedDrainFresh = AuthRepository.startFresh()
            assertTrue(failedDrainFresh.isFailure)
            assertEquals(0, exits.get())
            assertEquals("token-conflicted-a", harness.sessionManager.loadSession()?.accessToken)
            assertTrue(AuthStorage.loadFlag("had_account_session"))

            // 4. Once drain succeeds, confirmed Start fresh clears the saved session and writes account_transition.json!
            Files.deleteIfExists(wpTmpBlocker)
            val completedFresh = AuthRepository.startFresh()
            assertTrue(completedFresh.isSuccess)
            assertEquals(1, exits.get())
            assertNull(harness.sessionManager.loadSession())
            assertFalse(AuthStorage.loadFlag("had_account_session"))
            assertTrue(rootDir.resolve(DesktopStorage.TransitionJournalFileName).exists())
        } finally {
            wpTmpBlocker.toFile().deleteRecursively()
            DesktopAccountDataLifecycle.bindWindowRequestQuitHandler(null)
            unregisterFlush()
        }

        // Part B: Desktop and mobile server switches keep the active session intact when saveConfiguration() fails!
        AuthRepository.resetForTest()
        WatchProgressRepository.resetForTest()
        DesktopAccountDataLifecycle.resetForTest()
        SupabaseProvider.resetForTest()
        val serverRoot = tempBase.resolve("server-save-fail-root").also { it.createDirectories() }
        DesktopStorage.setRootDirForTest(serverRoot, cacheDir)
        assertTrue(
            ServerConfigurationRepository.saveCustom(
                ServerConfiguration(
                    backendUrl = "https://default.nuvio.example",
                    publishableKey = "default-test-key",
                    capabilities = ServerCapabilities(emailPasswordAuth = true, tvLogin = true, watchParty = true),
                    isCustom = true,
                ),
            ),
        )
        val serverHarness = installMockBackend(activeUser = TestUser("user-a", "a@example.com", "token-server-keep"))
        val serverExits = installDesktopLifecycle(serverHarness, customRoot = serverRoot)
        AuthRepository.initialize()
        awaitAuthenticatedUser("user-a")

        val desktopSaveFailed = AuthRepository.switchServerSerialized(
            stageOnlyForRestart = true,
            saveConfiguration = { false },
        )
        assertEquals(ServerSwitchResult.Failed(ServerSwitchFailure.Save), desktopSaveFailed)
        assertEquals(0, serverExits.get())
        awaitAuthenticatedUser("user-a")
        assertEquals("token-server-keep", SupabaseProvider.client.auth.currentSessionOrNull()?.accessToken)
        assertEquals("token-server-keep", serverHarness.sessionManager.loadSession()?.accessToken)

        val mobileSaveFailed = AuthRepository.switchServerSerialized(
            stageOnlyForRestart = false,
            saveConfiguration = { false },
        )
        assertEquals(ServerSwitchResult.Failed(ServerSwitchFailure.Save), mobileSaveFailed)
        awaitAuthenticatedUser("user-a")
        assertEquals("token-server-keep", SupabaseProvider.client.auth.currentSessionOrNull()?.accessToken)
        assertEquals("token-server-keep", serverHarness.sessionManager.loadSession()?.accessToken)
    }

    @Test
    fun ordinary_request_refresh_on_expired_session_updates_current_epoch_and_never_overwrites_same_account_re_sign_in(): Unit = runBlocking {
        val observedRestBearerTokens = Collections.synchronizedList(mutableListOf<String?>())
        val holdRefresh = AtomicBoolean(false)
        val refreshEntered = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val nextRefreshToken = AtomicReference("token-a-request-refreshed-1")
        val loginUser = AtomicReference(TestUser("user-a", "a@example.com", "token-a-expired-1"))
        val loginExpiresIn = AtomicReference(0L)
        val sessionManager = TrackingSessionManager()

        SupabaseProvider.engineFactory = {
            MockEngine { request ->
                val path = request.url.encodedPath
                val query = request.url.encodedQuery
                when {
                    path.endsWith("/auth/v1/token") && "refresh_token" in query -> {
                        if (holdRefresh.get()) {
                            refreshEntered.complete(Unit)
                            releaseRefresh.await()
                        }
                        val token = nextRefreshToken.get()
                        respondJson(
                            """{"access_token":"$token","token_type":"bearer","expires_in":3600,"refresh_token":"refresh-$token","user":${userJson(TestUser("user-a", "a@example.com", token))}}""",
                        )
                    }
                    path.endsWith("/auth/v1/token") -> {
                        val user = loginUser.get()
                        val exp = loginExpiresIn.get()
                        respondJson(
                            """{"access_token":"${user.accessToken}","token_type":"bearer","expires_in":$exp,"refresh_token":"refresh-${user.accessToken}","user":${userJson(user)}}""",
                        )
                    }
                    path.endsWith("/auth/v1/user") -> respondJson(userJson(loginUser.get()))
                    path.endsWith("/auth/v1/logout") -> respond("", HttpStatusCode.NoContent)
                    path.endsWith("/rest/v1/addons") -> {
                        observedRestBearerTokens += request.headers[HttpHeaders.Authorization]
                        respondJson("[]")
                    }
                    else -> respondJson("[]")
                }
            }
        }
        SupabaseProvider.sessionManagerOverride = sessionManager
        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { rootDir },
            storedSessionUserId = { sessionManager.loadSession()?.user?.id },
            exitApp = {},
        )
        AuthRepository.initialize()
        withTimeout(5_000) { AuthRepository.state.first { it is AuthState.Unauthenticated } }

        // Part A: Ordinary Postgrest request outside SyncManager with an expired token refreshes in isolation and sends the fresh token.
        assertTrue(AuthRepository.signInWithEmail("a@example.com", "pw-a").isSuccess)
        awaitAuthenticatedUser("user-a")
        SupabaseProvider.client.postgrest.from("addons").select()
        assertEquals(listOf("Bearer token-a-request-refreshed-1"), observedRestBearerTokens.toList())
        assertEquals("token-a-request-refreshed-1", SupabaseProvider.client.auth.currentSessionOrNull()?.accessToken)
        assertEquals("token-a-request-refreshed-1", sessionManager.loadSession()?.accessToken)

        // Part B: Ordinary Postgrest request starts refresh on an expired session, survives session loss, and returns AFTER same-account re-sign-in.
        assertTrue(AuthRepository.signOut().isSuccess)
        observedRestBearerTokens.clear()
        loginUser.set(TestUser("user-a", "a@example.com", "token-a-expired-2"))
        loginExpiresIn.set(0L)
        assertTrue(AuthRepository.signInWithEmail("a@example.com", "pw-a").isSuccess)
        awaitAuthenticatedUser("user-a")

        holdRefresh.set(true)
        nextRefreshToken.set("token-a-obsolete-from-request")
        val inFlightRequest = async(Dispatchers.Default) {
            runCatching { SupabaseProvider.client.postgrest.from("addons").select() }
        }
        withTimeout(5_000) { refreshEntered.await() }

        // Session is lost while the ordinary request's refresh is still in flight.
        val invalidated = AuthRepository.signOutIfSessionInvalid(
            RuntimeException("User from sub claim in JWT does not exist"),
            "independent-caller",
        )
        assertTrue(invalidated)
        assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)

        // User signs back into the SAME account with a fresh 1-hour session.
        holdRefresh.set(false)
        loginUser.set(TestUser("user-a", "a@example.com", "token-a-fresh-relogin"))
        loginExpiresIn.set(3_600L)
        assertTrue(AuthRepository.signInWithEmail("a@example.com", "pw-a").isSuccess)
        awaitAuthenticatedUser("user-a")
        assertEquals("token-a-fresh-relogin", SupabaseProvider.client.auth.currentSessionOrNull()?.accessToken)

        // Release the delayed request-triggered refresh: it must NOT overwrite token-a-fresh-relogin!
        releaseRefresh.complete(Unit)
        inFlightRequest.await()
        delay(80)

        awaitAuthenticatedUser("user-a")
        assertEquals(
            "token-a-fresh-relogin",
            SupabaseProvider.client.auth.currentSessionOrNull()?.accessToken,
            "Delayed request-triggered refresh must never overwrite a newer same-account re-sign-in session",
        )
        assertEquals(
            "token-a-fresh-relogin",
            sessionManager.loadSession()?.accessToken,
            "Delayed request-triggered refresh must never overwrite the stored session after re-sign-in",
        )
        assertFalse(
            observedRestBearerTokens.contains("Bearer token-a-obsolete-from-request"),
            "Discarded obsolete refresh token must not be attached to outgoing requests",
        )
    }

    @Test
    fun download_quit_timeout_blocks_exit_without_orphaning_finishing_video_or_stranding_active_downloads(): Unit = runBlocking {
        val realBackend = DownloadsRepository.backend
        val realHold = DownloadsRepository.connectionHold
        val realCompanions = DownloadsRepository.companions

        class TimeoutDownloadsBackend : DownloadsBackend {
            var payload: String? = null
            val requestsById = java.util.concurrent.ConcurrentHashMap<String, DownloadPlatformRequest>()
            val onSuccessById = java.util.concurrent.ConcurrentHashMap<String, (String, Long?) -> Unit>()
            val startCountById = java.util.concurrent.ConcurrentHashMap<String, Int>()
            val cancelTimeoutIds = Collections.synchronizedSet(mutableSetOf<String>())
            val sampleEnteredForUri = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()
            val releaseSampleForUri = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()

            override fun loadPayload(): String? = payload
            override fun savePayload(payload: String) {
                this.payload = payload
            }
            override fun restoreItem(item: DownloadItem): DownloadItem = item

            override fun start(
                request: DownloadPlatformRequest,
                onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
                onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
                onFailure: (message: String) -> Unit,
                onPaused: () -> Unit,
            ): DownloadsTaskHandle {
                val id = request.item.id
                requestsById[id] = request
                onSuccessById[id] = onSuccess
                startCountById.merge(id, 1, Int::plus)
                return object : DownloadsTaskHandle {
                    override fun cancel() = Unit
                    override fun cancelAndWait(timeoutMs: Long): Boolean = id !in cancelTimeoutIds
                }
            }

            override fun inspectFile(localFileUri: String): DownloadedFileFacts =
                DownloadedFileFacts(
                    exists = true,
                    sizeBytes = 50_000_000L,
                    isVideoContainer = true,
                    durationMs = null,
                )

            override fun sampleFrames(localFileUri: String): FrameSamples? {
                sampleEnteredForUri[localFileUri]?.complete(Unit)
                releaseSampleForUri[localFileUri]?.let { gate -> runBlocking { gate.await() } }
                return null
            }

            override fun removeFile(localFileUri: String?): Boolean = true
            override fun removePartialFile(destinationFileName: String): Boolean = true
            override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri
        }

        val fakeBackend = TimeoutDownloadsBackend()
        val previousRuntime = downloadRuntimeLookup
        try {
            // A known running time and an unknown file length is what asks for a frame look.
            downloadRuntimeLookup = { 45 }
            DownloadsRepository.clearLocalState()
            DownloadsRepository.backend = fakeBackend
            DownloadsRepository.connectionHold = ConnectionHold()
            DownloadsRepository.companions = { DownloadCompanions(emptyList()) }
            DownloadsRepository.quitCancelTimeoutMs = 120L
            DownloadsRepository.quitFinishingTimeoutMs = 150L
            DownloadsRepository.ensureLoaded()

            val harness = installMockBackend(activeUser = TestUser("user-a", "a@example.com", "token-a"))
            val exits = installDesktopLifecycle(harness)
            AuthRepository.initialize()
            awaitAuthenticatedUser("user-a")
            assertTrue(AuthRepository.signOut().isSuccess)

            fun enqueueEpisode(ep: Int): String {
                DownloadsRepository.enqueueFromStream(
                    contentType = "series",
                    videoId = "tt999:1:$ep",
                    parentMetaId = "tt999",
                    parentMetaType = "series",
                    title = "Timeout Series",
                    logo = null,
                    poster = null,
                    background = null,
                    seasonNumber = 1,
                    episodeNumber = ep,
                    episodeTitle = "Episode $ep",
                    episodeThumbnail = null,
                    stream = StreamItem(
                        name = "1080p",
                        url = "https://cdn.example.com/timeout-ep$ep.mkv",
                        addonName = "Addon",
                        addonId = "addon.example",
                        behaviorHints = StreamBehaviorHints(),
                    ),
                )
                return DownloadsRepository.uiState.value.items.first { it.episodeNumber == ep }.id
            }

            val finishingId = enqueueEpisode(1)
            val normalActiveId = enqueueEpisode(2)
            val stuckCancelId = enqueueEpisode(3)
            fakeBackend.cancelTimeoutIds += stuckCancelId

            val finishedUri = rootDir.resolve("downloads/timeout-ep1.mkv").also {
                it.parent.createDirectories()
                Files.writeString(it, "video-ep1-complete")
            }.toUri().toString()
            val sampleEntered = CompletableDeferred<Unit>()
            val releaseSample = CompletableDeferred<Unit>()
            fakeBackend.sampleEnteredForUri[finishedUri] = sampleEntered
            fakeBackend.releaseSampleForUri[finishedUri] = releaseSample

            assertTrue(fakeBackend.requestsById.getValue(finishingId).mayPublish())
            val finishJob = async(Dispatchers.Default) {
                fakeBackend.onSuccessById.getValue(finishingId).invoke(finishedUri, 50_000_000L)
            }
            withTimeout(5_000) { sampleEntered.await() }

            // 1. Start fresh quit hits BOTH cancel timeout (stuckCancelId) AND finishing-check timeout (finishingId):
            // quit MUST fail, stay open, write NO transition journal, restore normalActiveId, pause stuckCancelId,
            // and leave finishingId registered so its frame check can still complete!
            val timedOutFresh = AuthRepository.startFresh()
            assertTrue(timedOutFresh.isFailure, "Quit must fail when a finishing or cancelling download exceeds its wait")
            assertEquals(0, exits.get())
            assertNotNull(DesktopAccountDataLifecycle.quitError.value)
            assertFalse(rootDir.resolve(DesktopStorage.TransitionJournalFileName).exists())

            val itemsAfterTimeout = DownloadsRepository.uiState.value.items.associateBy { it.id }
            assertEquals(DownloadStatus.Downloading, itemsAfterTimeout.getValue(normalActiveId).status)
            assertTrue(DownloadsRepository.hasActiveTransferForTest(normalActiveId), "Cleanly stopped download must be restored")
            assertEquals(
                DownloadStatus.Paused,
                itemsAfterTimeout.getValue(stuckCancelId).status,
                "Download whose cancel timed out must move to Paused rather than staying Downloading with no transfer",
            )
            assertFalse(DownloadsRepository.hasActiveTransferForTest(stuckCancelId))
            assertTrue(
                DownloadsRepository.hasActiveTransferForTest(finishingId),
                "Timed-out finishing transfer must remain registered while its check completes",
            )

            // 2. Release the long frame check while the app is still open: finishingId saves Completed + localFileUri!
            releaseSample.complete(Unit)
            finishJob.await()
            val finishedItem = DownloadsRepository.uiState.value.items.single { it.id == finishingId }
            assertEquals(DownloadStatus.Completed, finishedItem.status)
            assertEquals(finishedUri, finishedItem.localFileUri)

            // 3. Retry Start fresh now that the finishing download has saved: succeeds, writes journal, and exits!
            val retryFresh = AuthRepository.startFresh()
            assertTrue(retryFresh.isSuccess)
            assertEquals(1, exits.get())
            assertTrue(rootDir.resolve(DesktopStorage.TransitionJournalFileName).exists())

            DownloadsRepository.clearLocalState()
            DownloadsRepository.ensureLoaded()
            val reloadedFinished = DownloadsRepository.uiState.value.items.single { it.id == finishingId }
            assertEquals(DownloadStatus.Completed, reloadedFinished.status)
            assertEquals(finishedUri, reloadedFinished.localFileUri)
        } finally {
            downloadRuntimeLookup = previousRuntime
            DownloadsRepository.clearLocalState()
            DownloadsRepository.backend = realBackend
            DownloadsRepository.connectionHold = realHold
            DownloadsRepository.companions = realCompanions
        }
    }

    @Suppress("DEPRECATION")
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun player_flush_failure_during_snapshot_collection_or_sync_save_blocks_quit_and_keeps_app_open() {
        val harness = runBlocking { installMockBackend(activeUser = TestUser("user-a", "a@example.com", "token-a")) }
        val exits = installDesktopLifecycle(harness)
        runBlocking {
            AuthRepository.initialize()
            awaitAuthenticatedUser("user-a")
            WatchProgressRepository.ensureLoaded()
        }

        // Part A: Direct exception during snapshot collection (before enqueueing anything into pendingPersists) blocks quit!
        val throwOnCollect = AtomicBoolean(true)
        val unregisterThrowingHook = WatchProgressRepository.registerActivePlayerFlushHook {
            if (throwOnCollect.get()) {
                throw IllegalStateException("Simulated snapshot collection failure")
            }
        }
        try {
            val failedCollectQuit = runBlocking {
                DesktopAccountDataLifecycle.executeConfirmedQuit {
                    exits.incrementAndGet()
                }
            }
            assertTrue(failedCollectQuit.isFailure, "Flush-hook exception before enqueueing must block quit")
            assertEquals(0, exits.get())
            assertNotNull(DesktopAccountDataLifecycle.quitError.value)
            assertFalse(WatchProgressRepository.hasPendingOrActivePersistenceForTest())
        } finally {
            unregisterThrowingHook()
        }

        // Part B: Real PlayerScreenRuntime whose playbackSession.profileId (2) != activeProfileId (1),
        // which executes a SYNCHRONOUS WatchProgressStorage.savePayload(2, ...) inside flushActivePlayers()
        // without queueing into pendingPersists!
        class FakePlayerController : PlayerEngineController {
            val pauseCount = AtomicInteger(0)
            override fun play() = Unit
            override fun pause() {
                pauseCount.incrementAndGet()
            }
            override fun seekTo(positionMs: Long) = Unit
            override fun seekBy(offsetMs: Long) = Unit
            override fun retry() = Unit
            override fun setPlaybackSpeed(speed: Float) = Unit
            override fun getAudioTracks(): List<AudioTrack> = emptyList()
            override fun getSubtitleTracks(): List<SubtitleTrack> = emptyList()
            override fun applyAudioLanguagePreferences(languages: List<String>) = Unit
            override fun selectAudioTrack(index: Int) = Unit
            override fun selectSubtitleTrack(index: Int) = Unit
            override fun setSubtitleUri(url: String) = Unit
            override fun clearExternalSubtitle() = Unit
            override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
        }

        val controller = FakePlayerController()
        val args = PlayerScreenArgs(
            profileId = 2,
            title = "Profile 2 Sync Flush Film",
            sourceUrl = "https://cdn.example.com/profile2-film.mp4",
            sourceAudioUrl = null,
            sourceHeaders = emptyMap(),
            sourceResponseHeaders = emptyMap(),
            streamType = "mp4",
            providerName = "Provider",
            streamTitle = "1080p",
            streamSubtitle = null,
            initialBingeGroup = null,
            pauseDescription = null,
            onBack = { release -> release({}, {}) },
            onOpenInExternalPlayer = null,
            onOpenExternalUrl = null,
            modifier = Modifier,
            logo = null,
            poster = null,
            background = null,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            episodeThumbnail = null,
            contentType = "movie",
            videoId = "tt6666666",
            parentMetaId = "tt6666666",
            parentMetaType = "movie",
            providerAddonId = "addon.example",
            torrentInfoHash = null,
            torrentFileIdx = null,
            torrentFilename = null,
            torrentTrackers = emptyList(),
            initialPositionMs = 0L,
            initialProgressFraction = null,
        )
        val runtime = PlayerScreenRuntime(args)
        val wpTmpBlocker = rootDir.resolve("nuvio_watch_progress.properties.tmp")

        try {
            runComposeUiTest {
                var showPlayer by mutableStateOf(true)
                setContent {
                    NuvioTheme {
                        if (showPlayer) {
                            runtime.scope = rememberCoroutineScope()
                            runtime.playerController = controller
                            runtime.BindPlayerUiVisibilityEffects()
                            runtime.BindPlayerQuitAndDisposeEffects()
                            Text("Profile 2 Player Mounted")
                        } else {
                            Text("Profile 2 Player Disposed")
                        }
                    }
                }
                waitForIdle()
                onNodeWithText("Profile 2 Player Mounted").assertIsDisplayed()

                runOnIdle {
                    assertTrue(
                        runtime.updatePlaybackSnapshot(
                            PlayerPlaybackSnapshot(
                                isLoading = false,
                                isPlaying = true,
                                isEnded = false,
                                durationMs = 4_800_000L,
                                positionMs = 2_100_000L,
                            ),
                        ),
                    )
                }
                waitForIdle()

                // Block synchronous WatchProgressStorage.savePayload(2, ...) during flushActivePlayers():
                // pendingPersists stays empty, yet quit MUST fail, unfreeze runtime, and keep the app open!
                Files.createDirectories(wpTmpBlocker)
                val failedSyncFlushQuit = runBlocking {
                    DesktopAccountDataLifecycle.executeConfirmedQuit {
                        showPlayer = false
                        exits.incrementAndGet()
                    }
                }
                assertTrue(
                    failedSyncFlushQuit.isFailure,
                    "Synchronous storage failure inside flushActivePlayers must block quit",
                )
                assertEquals(0, exits.get())
                assertTrue(showPlayer)
                assertFalse(runtime.frozenForQuit, "Aborted quit after flush failure must unfreeze player runtime")
                assertNotNull(DesktopAccountDataLifecycle.quitError.value)
                assertFalse(WatchProgressRepository.hasPendingOrActivePersistenceForTest())

                // Player remains usable after the aborted quit; advance position and retry once disk write unblocks.
                runOnIdle {
                    assertTrue(
                        runtime.updatePlaybackSnapshot(
                            PlayerPlaybackSnapshot(
                                isLoading = false,
                                isPlaying = true,
                                isEnded = false,
                                durationMs = 4_800_000L,
                                positionMs = 2_250_000L,
                            ),
                        ),
                    )
                }
                waitForIdle()

                Files.deleteIfExists(wpTmpBlocker)
                val succeededQuit = runBlocking {
                    DesktopAccountDataLifecycle.executeConfirmedQuit {
                        showPlayer = false
                        exits.incrementAndGet()
                    }
                }
                assertTrue(succeededQuit.isSuccess)
                assertEquals(1, exits.get())
                waitForIdle()
                onNodeWithText("Profile 2 Player Disposed").assertIsDisplayed()

                val profile2Raw = WatchProgressStorage.loadPayload(2)
                assertNotNull(profile2Raw)
                assertTrue("tt6666666" in profile2Raw && "2250000" in profile2Raw)
            }
        } finally {
            wpTmpBlocker.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejected_refresh_inside_party_request_releases_auth_even_while_party_lock_is_held(): Unit = runBlocking {
        val user = TestUser("user-a", "a@example.com", "party-expired")
        val harness = installMockBackend(activeUser = user)
        installDesktopLifecycle(harness)
        AuthRepository.initialize()
        awaitAuthenticatedUser("user-a")
        // Import an expired candidate through the real sign-in path, then reject its next refresh.
        val factory = harness.engineFactory
        SupabaseProvider.engineFactory = {
            MockEngine { request ->
                when {
                    request.url.encodedQuery.contains("refresh_token") -> respond(
                        """{"error":"invalid_grant","error_description":"Invalid Refresh Token: Refresh Token Not Found"}""",
                        HttpStatusCode.BadRequest,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                    request.url.encodedPath.endsWith("/auth/v1/token") -> respondJson(
                        tokenSessionJson(user).replace("\"expires_in\":3600", "\"expires_in\":0"),
                    )
                    request.url.encodedPath.endsWith("/auth/v1/user") -> respondJson(userJson(user))
                    else -> respondJson("[]")
                }
            }
        }
        assertTrue(AuthRepository.signInWithEmail(user.email, "pw").isSuccess)
        val partyLock = PartyRepository::class.java.getDeclaredField("mutex").also { it.isAccessible = true }
            .get(PartyRepository) as Mutex
        // This is the lock held by create/join/end/rejoin while their real request plugin runs.
        val returned = partyLock.withLock {
            withTimeoutOrNull(1_500) {
                runCatching { SupabaseProvider.client.postgrest.from("addons").select() }
                true
            }
        }
        assertEquals(true, returned, "Rejected refresh must return without waiting for the caller's party lock")
        assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
        assertTrue(AuthRepository.signInWithEmail(user.email, "pw").isSuccess)
        assertEquals(true, withTimeoutOrNull(1_500) {
            PartyRepository.join("JOINME")
            true
        }, "A real join request must unwind after refresh is rejected while it owns the party lock")
        assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
        SupabaseProvider.engineFactory = factory
        partyLock.withLock {
            assertTrue(withTimeout(5_000) { AuthRepository.signInWithEmail(user.email, "pw") }.isSuccess)
            awaitAuthenticatedUser("user-a")
            assertTrue(withTimeout(5_000) { AuthRepository.signOut() }.isSuccess)
        }
    }

    @Test
    fun failed_finished_download_record_save_blocks_quit_until_the_real_store_can_save(): Unit = runBlocking {
        val originalBackend = DownloadsRepository.backend
        val originalHold = DownloadsRepository.connectionHold
        val originalCompanions = DownloadsRepository.companions
        lateinit var finish: (String, Long?) -> Unit
        var starts = 0
        val backend = object : DownloadsBackend by originalBackend {
            override fun start(
                request: DownloadPlatformRequest,
                onProgress: (Long, Long?) -> Unit,
                onSuccess: (String, Long?) -> Unit,
                onFailure: (String) -> Unit,
                onPaused: () -> Unit,
            ): DownloadsTaskHandle {
                starts++
                finish = onSuccess
                return object : DownloadsTaskHandle { override fun cancel() = Unit }
            }
            override fun restoreItem(item: DownloadItem): DownloadItem = item
            override fun inspectFile(localFileUri: String): DownloadedFileFacts =
                DownloadedFileFacts(exists = true, sizeBytes = 50_000_000L, isVideoContainer = true, durationMs = 2_400_000L)
            override fun sampleFrames(localFileUri: String): FrameSamples? = null
            override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri
        }
        val blocked = rootDir.resolve("nuvio_downloads.properties.tmp")
        try {
            DownloadsRepository.clearLocalState()
            DownloadsRepository.backend = backend
            DownloadsRepository.connectionHold = ConnectionHold()
            DownloadsRepository.companions = { DownloadCompanions(emptyList()) }
            DownloadsRepository.ensureLoaded()
            val harness = installMockBackend(activeUser = TestUser("user-a", "a@example.com", "token-a"))
            val exits = installDesktopLifecycle(harness)
            AuthRepository.initialize()
            awaitAuthenticatedUser("user-a")
            DownloadsRepository.enqueueFromStream(
                contentType = "movie", videoId = "tt-save", parentMetaId = "tt-save", parentMetaType = "movie",
                title = "Saved video", logo = null, poster = null, background = null,
                seasonNumber = null, episodeNumber = null, episodeTitle = null, episodeThumbnail = null,
                stream = StreamItem(name = "1080p", url = "https://cdn.example.com/video.mkv", addonName = "Addon", addonId = "addon.example"),
            )
            val id = DownloadsRepository.uiState.value.items.single().id
            val video = rootDir.resolve("downloads/saved-video.mkv").also {
                it.parent.createDirectories()
                Files.writeString(it, "finished video")
            }
            val storedBefore = Files.readString(rootDir.resolve("nuvio_downloads.properties"))
            blocked.createDirectories()
            Files.writeString(blocked.resolve("keep"), "keep this file")
            val storeLocked = CompletableDeferred<Unit>()
            val releaseStore = CompletableDeferred<Unit>()
            val holdingStore = async(Dispatchers.IO) {
                DesktopStorage.store("nuvio_downloads").exclusive {
                    storeLocked.complete(Unit)
                    runBlocking { releaseStore.await() }
                }
            }
            withTimeout(5_000) { storeLocked.await() }
            val finishing = async(Dispatchers.IO) { finish(video.toUri().toString(), 50_000_000L) }
            try {
                withTimeout(5_000) { DownloadsRepository.uiState.first { it.items.single().status == DownloadStatus.Completed } }
                val responsive = async(Dispatchers.Default) { DownloadsRepository.hasActiveTransferForTest(id) }
                assertEquals(true, withTimeoutOrNull(1_500) { responsive.await() }, "Waiting for the real store must not hold the state lock")
            } finally {
                releaseStore.complete(Unit)
                holdingStore.await()
                finishing.await()
            }
            assertEquals(storedBefore, Files.readString(rootDir.resolve("nuvio_downloads.properties")))
            repeat(2) {
                val quit = DesktopAccountDataLifecycle.executeConfirmedQuit { exits.incrementAndGet() }
                assertTrue(quit.isFailure, "A completed file whose record is not saved must block every quit attempt")
                assertEquals(0, exits.get())
                assertNotNull(DesktopAccountDataLifecycle.quitError.value)
                assertTrue(video.exists())
                assertFalse(rootDir.resolve(DesktopStorage.TransitionJournalFileName).exists())
                assertTrue(DownloadsRepository.hasActiveTransferForTest(id))
            }
            // A failed delete drops the active handle, but must not discard the unsaved completion.
            assertEquals(DownloadDeleteResult.NotMoved, DownloadsRepository.deleteDownload(id))
            assertTrue(DesktopAccountDataLifecycle.executeConfirmedQuit { exits.incrementAndGet() }.isFailure)
            assertEquals(0, exits.get())
            assertTrue(video.exists())
            // Move the obstruction aside; even test files are preserved.
            Files.move(blocked, rootDir.resolve("saved-write-obstruction"))
            assertTrue(DesktopAccountDataLifecycle.executeConfirmedQuit { exits.incrementAndGet() }.isSuccess)
            assertEquals(1, exits.get())
            DownloadsRepository.clearLocalState()
            DownloadsRepository.ensureLoaded()
            val reloaded = DownloadsRepository.uiState.value.items.single { it.id == id }
            assertEquals(DownloadStatus.Completed, reloaded.status)
            assertEquals(video, Path.of(java.net.URI(requireNotNull(reloaded.localFileUri))))
            assertEquals(1, starts, "Retrying the record save must never download the video again")
            assertEquals("finished video", Files.readString(video))
        } finally {
            DownloadsRepository.clearLocalState()
            DownloadsRepository.backend = originalBackend
            DownloadsRepository.connectionHold = originalHold
            DownloadsRepository.companions = originalCompanions
        }
    }

    @Test
    fun replacing_a_download_after_a_failed_completion_save_lets_quit_finish(): Unit = runBlocking {
        val originalBackend = DownloadsRepository.backend
        val originalHold = DownloadsRepository.connectionHold
        val originalCompanions = DownloadsRepository.companions
        lateinit var finish: (String, Long?) -> Unit
        val backend = object : DownloadsBackend by originalBackend {
            override fun start(
                request: DownloadPlatformRequest,
                onProgress: (Long, Long?) -> Unit,
                onSuccess: (String, Long?) -> Unit,
                onFailure: (String) -> Unit,
                onPaused: () -> Unit,
            ): DownloadsTaskHandle {
                finish = onSuccess
                return object : DownloadsTaskHandle { override fun cancel() = Unit }
            }
            override fun restoreItem(item: DownloadItem): DownloadItem = item
            override fun inspectFile(localFileUri: String): DownloadedFileFacts =
                DownloadedFileFacts(exists = true, sizeBytes = 50_000_000L, isVideoContainer = true, durationMs = 2_400_000L)
            override fun sampleFrames(localFileUri: String): FrameSamples? = null
            override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri
            override fun ownershipOf(item: DownloadItem, fileUri: String): FileOwnership = FileOwnership.Free
            override fun discardFile(localFileUri: String?): Boolean {
                if (localFileUri.isNullOrBlank()) return false
                val path = Path.of(java.net.URI(localFileUri))
                val dest = rootDir.resolve("held").also { it.createDirectories() }.resolve(path.fileName)
                Files.move(path, dest)
                return true
            }
        }
        val blocked = rootDir.resolve("nuvio_downloads.properties.tmp")
        try {
            DownloadsRepository.clearLocalState()
            DownloadsRepository.backend = backend
            DownloadsRepository.connectionHold = ConnectionHold()
            DownloadsRepository.companions = { DownloadCompanions(emptyList()) }
            DownloadsRepository.ensureLoaded()
            val harness = installMockBackend(activeUser = TestUser("user-a", "a@example.com", "token-a"))
            val exits = installDesktopLifecycle(harness)
            AuthRepository.initialize()
            awaitAuthenticatedUser("user-a")
            DownloadsRepository.enqueueFromStream(
                contentType = "movie", videoId = "tt-replace", parentMetaId = "tt-replace", parentMetaType = "movie",
                title = "Replaced video", logo = null, poster = null, background = null,
                seasonNumber = null, episodeNumber = null, episodeTitle = null, episodeThumbnail = null,
                stream = StreamItem(name = "1080p", url = "https://cdn.example.com/video.mkv", addonName = "Addon", addonId = "addon.example"),
            )
            val id = DownloadsRepository.uiState.value.items.single().id
            val video = rootDir.resolve("downloads/replaced-video.mkv").also {
                it.parent.createDirectories()
                Files.writeString(it, "finished video")
            }
            val storedBefore = Files.readString(rootDir.resolve("nuvio_downloads.properties"))
            blocked.createDirectories()
            Files.writeString(blocked.resolve("keep"), "keep this file")
            val storeLocked = CompletableDeferred<Unit>()
            val releaseStore = CompletableDeferred<Unit>()
            val holdingStore = async(Dispatchers.IO) {
                DesktopStorage.store("nuvio_downloads").exclusive {
                    storeLocked.complete(Unit)
                    runBlocking { releaseStore.await() }
                }
            }
            withTimeout(5_000) { storeLocked.await() }
            val finishing = async(Dispatchers.IO) { finish(video.toUri().toString(), 50_000_000L) }
            try {
                withTimeout(5_000) { DownloadsRepository.uiState.first { it.items.single().status == DownloadStatus.Completed } }
            } finally {
                releaseStore.complete(Unit)
                holdingStore.await()
                finishing.await()
            }
            assertEquals(storedBefore, Files.readString(rootDir.resolve("nuvio_downloads.properties")))
            assertTrue(DesktopAccountDataLifecycle.executeConfirmedQuit { exits.incrementAndGet() }.isFailure)
            assertEquals(0, exits.get())

            // The list can be saved again. Replacing the finished row has to drop the unsaved completion.
            Files.move(blocked, rootDir.resolve("saved-write-obstruction"))
            assertEquals(
                DownloadEnqueueResult.Replaced,
                DownloadsRepository.enqueueFromStream(
                    contentType = "movie", videoId = "tt-replace", parentMetaId = "tt-replace", parentMetaType = "movie",
                    title = "Replaced video", logo = null, poster = null, background = null,
                    seasonNumber = null, episodeNumber = null, episodeTitle = null, episodeThumbnail = null,
                    stream = StreamItem(name = "720p", url = "https://cdn.example.com/other.mkv", addonName = "Addon", addonId = "addon.example"),
                    userChoseReplacement = true,
                ),
            )
            assertTrue(DesktopAccountDataLifecycle.executeConfirmedQuit { exits.incrementAndGet() }.isSuccess)
            assertEquals(1, exits.get())
            assertFalse(rootDir.resolve(DesktopStorage.TransitionJournalFileName).exists())
            val held = rootDir.resolve("held").resolve(video.fileName)
            assertEquals("finished video", Files.readString(held))
            assertFalse(video.exists())
            DownloadsRepository.clearLocalState()
            DownloadsRepository.ensureLoaded()
            val stored = DownloadsRepository.uiState.value.items
            assertTrue(stored.none { it.id == id })
            assertEquals("https://cdn.example.com/other.mkv", stored.single().sourceUrl)
            assertEquals(DownloadStatus.Downloading, stored.single().status)
        } finally {
            DownloadsRepository.clearLocalState()
            DownloadsRepository.backend = originalBackend
            DownloadsRepository.connectionHold = originalHold
            DownloadsRepository.companions = originalCompanions
        }
    }

    @Test
    fun saving_another_profiles_list_keeps_an_unsaved_completion_until_quit(): Unit = runBlocking {
        val originalBackend = DownloadsRepository.backend
        val originalHold = DownloadsRepository.connectionHold
        val originalCompanions = DownloadsRepository.companions
        val homeProfile = ProfileRepository.activeProfileId
        lateinit var finish: (String, Long?) -> Unit
        val backend = object : DownloadsBackend by originalBackend {
            override fun start(
                request: DownloadPlatformRequest,
                onProgress: (Long, Long?) -> Unit,
                onSuccess: (String, Long?) -> Unit,
                onFailure: (String) -> Unit,
                onPaused: () -> Unit,
            ): DownloadsTaskHandle {
                finish = onSuccess
                return object : DownloadsTaskHandle { override fun cancel() = Unit }
            }
            override fun restoreItem(item: DownloadItem): DownloadItem = item
            override fun inspectFile(localFileUri: String): DownloadedFileFacts =
                DownloadedFileFacts(exists = true, sizeBytes = 50_000_000L, isVideoContainer = true, durationMs = 2_400_000L)
            override fun sampleFrames(localFileUri: String): FrameSamples? = null
            override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri
        }
        val blocked = rootDir.resolve("nuvio_downloads.properties.tmp")
        try {
            DownloadsRepository.clearLocalState()
            DownloadsRepository.backend = backend
            DownloadsRepository.connectionHold = ConnectionHold()
            DownloadsRepository.companions = { DownloadCompanions(emptyList()) }
            DownloadsRepository.ensureLoaded()
            val harness = installMockBackend(activeUser = TestUser("user-a", "a@example.com", "token-a"))
            val exits = installDesktopLifecycle(harness)
            AuthRepository.initialize()
            awaitAuthenticatedUser("user-a")
            DownloadsRepository.enqueueFromStream(
                contentType = "movie", videoId = "tt-home", parentMetaId = "tt-home", parentMetaType = "movie",
                title = "Home video", logo = null, poster = null, background = null,
                seasonNumber = null, episodeNumber = null, episodeTitle = null, episodeThumbnail = null,
                stream = StreamItem(name = "1080p", url = "https://cdn.example.com/home.mkv", addonName = "Addon", addonId = "addon.example"),
            )
            val id = DownloadsRepository.uiState.value.items.single().id
            val video = rootDir.resolve("downloads/home-video.mkv").also {
                it.parent.createDirectories()
                Files.writeString(it, "home video")
            }
            blocked.createDirectories()
            Files.writeString(blocked.resolve("keep"), "keep this file")
            val storeLocked = CompletableDeferred<Unit>()
            val releaseStore = CompletableDeferred<Unit>()
            val holdingStore = async(Dispatchers.IO) {
                DesktopStorage.store("nuvio_downloads").exclusive {
                    storeLocked.complete(Unit)
                    runBlocking { releaseStore.await() }
                }
            }
            withTimeout(5_000) { storeLocked.await() }
            val finishing = async(Dispatchers.IO) { finish(video.toUri().toString(), 50_000_000L) }
            try {
                withTimeout(5_000) { DownloadsRepository.uiState.first { it.items.single().status == DownloadStatus.Completed } }
            } finally {
                releaseStore.complete(Unit)
                holdingStore.await()
                finishing.await()
            }
            assertTrue(DesktopAccountDataLifecycle.executeConfirmedQuit { exits.incrementAndGet() }.isFailure)
            assertEquals(0, exits.get())
            Files.move(blocked, rootDir.resolve("saved-write-obstruction"))

            ProfileRepository.switchToProfile(homeProfile + 1)
            assertEquals(homeProfile + 1, ProfileRepository.activeProfileId)
            DownloadsRepository.enqueueFromStream(
                contentType = "movie", videoId = "tt-other", parentMetaId = "tt-other", parentMetaType = "movie",
                title = "Other video", logo = null, poster = null, background = null,
                seasonNumber = null, episodeNumber = null, episodeTitle = null, episodeThumbnail = null,
                stream = StreamItem(name = "720p", url = "https://cdn.example.com/other-profile.mkv", addonName = "Addon", addonId = "addon.example"),
            )
            assertTrue(DesktopAccountDataLifecycle.executeConfirmedQuit { exits.incrementAndGet() }.isSuccess)
            assertEquals(1, exits.get())
            val homePayload = DesktopStorage.store("nuvio_downloads").getString(ProfileScopedKey.of("downloads", homeProfile))
            val homeItem = DownloadsCodec.decode(requireNotNull(homePayload))!!.items.single { it.id == id }
            assertEquals(DownloadStatus.Completed, homeItem.status)
            assertEquals(video, Path.of(java.net.URI(requireNotNull(homeItem.localFileUri))))
            assertEquals("home video", Files.readString(video))
        } finally {
            ProfileRepository.clearInMemory()
            DownloadsRepository.clearLocalState()
            DownloadsRepository.backend = originalBackend
            DownloadsRepository.connectionHold = originalHold
            DownloadsRepository.companions = originalCompanions
        }
    }

    @Test
    fun removing_a_watched_download_after_a_failed_completion_save_lets_quit_finish(): Unit = runBlocking {
        val originalBackend = DownloadsRepository.backend
        val originalHold = DownloadsRepository.connectionHold
        val originalCompanions = DownloadsRepository.companions
        lateinit var finish: (String, Long?) -> Unit
        val backend = object : DownloadsBackend by originalBackend {
            override fun start(
                request: DownloadPlatformRequest,
                onProgress: (Long, Long?) -> Unit,
                onSuccess: (String, Long?) -> Unit,
                onFailure: (String) -> Unit,
                onPaused: () -> Unit,
            ): DownloadsTaskHandle {
                finish = onSuccess
                return object : DownloadsTaskHandle { override fun cancel() = Unit }
            }
            override fun restoreItem(item: DownloadItem): DownloadItem = item
            override fun inspectFile(localFileUri: String): DownloadedFileFacts =
                DownloadedFileFacts(exists = true, sizeBytes = 50_000_000L, isVideoContainer = true, durationMs = 2_400_000L)
            override fun sampleFrames(localFileUri: String): FrameSamples? = null
            override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri
            override fun isConnected(item: DownloadItem): Boolean = true
            override fun ownershipOf(item: DownloadItem, fileUri: String): FileOwnership = FileOwnership.Free
            override fun locate(item: DownloadItem): FileLookup =
                item.localFileUri?.let { FileLookup.Found(it, item.locationId) } ?: FileLookup.Missing
            override val usesSystemTrash: Boolean get() = true
            override fun trashWatchedFile(localFileUri: String, stillWanted: () -> Boolean): AutoDownloadRemovalResult {
                if (!stillWanted()) return AutoDownloadRemovalResult.Changed
                val path = Path.of(java.net.URI(localFileUri))
                val dest = rootDir.resolve("held").also { it.createDirectories() }.resolve(path.fileName)
                Files.move(path, dest)
                return if (stillWanted()) AutoDownloadRemovalResult.Removed else AutoDownloadRemovalResult.Changed
            }
        }
        val blocked = rootDir.resolve("nuvio_downloads.properties.tmp")
        try {
            DownloadsRepository.clearLocalState()
            DownloadsRepository.backend = backend
            DownloadsRepository.connectionHold = ConnectionHold()
            DownloadsRepository.companions = { DownloadCompanions(emptyList()) }
            DownloadsRepository.ensureLoaded()
            val harness = installMockBackend(activeUser = TestUser("user-a", "a@example.com", "token-a"))
            val exits = installDesktopLifecycle(harness)
            AuthRepository.initialize()
            awaitAuthenticatedUser("user-a")
            DownloadsRepository.enqueueFromStream(
                contentType = "movie", videoId = "tt-watched", parentMetaId = "tt-watched", parentMetaType = "movie",
                title = "Watched video", logo = null, poster = null, background = null,
                seasonNumber = null, episodeNumber = null, episodeTitle = null, episodeThumbnail = null,
                stream = StreamItem(name = "1080p", url = "https://cdn.example.com/watched.mkv", addonName = "Addon", addonId = "addon.example"),
                autoDownloadRuleKey = "rule",
            )
            val id = DownloadsRepository.uiState.value.items.single().id
            val video = rootDir.resolve("downloads/watched-video.mkv").also {
                it.parent.createDirectories()
                Files.writeString(it, "watched video")
            }
            blocked.createDirectories()
            Files.writeString(blocked.resolve("keep"), "keep this file")
            val storeLocked = CompletableDeferred<Unit>()
            val releaseStore = CompletableDeferred<Unit>()
            val holdingStore = async(Dispatchers.IO) {
                DesktopStorage.store("nuvio_downloads").exclusive {
                    storeLocked.complete(Unit)
                    runBlocking { releaseStore.await() }
                }
            }
            withTimeout(5_000) { storeLocked.await() }
            val finishing = async(Dispatchers.IO) { finish(video.toUri().toString(), 50_000_000L) }
            try {
                withTimeout(5_000) { DownloadsRepository.uiState.first { it.items.single().status == DownloadStatus.Completed } }
            } finally {
                releaseStore.complete(Unit)
                holdingStore.await()
                finishing.await()
            }
            Files.move(blocked, rootDir.resolve("saved-write-obstruction"))
            assertEquals(
                AutoDownloadRemovalResult.Removed,
                DownloadsRepository.removeWatchedAutoDownload(id, "rule"),
            )
            assertTrue(DesktopAccountDataLifecycle.executeConfirmedQuit { exits.incrementAndGet() }.isSuccess)
            assertEquals(1, exits.get())
            val held = rootDir.resolve("held").resolve(video.fileName)
            assertEquals("watched video", Files.readString(held))
            assertFalse(video.exists())
            DownloadsRepository.clearLocalState()
            DownloadsRepository.ensureLoaded()
            assertTrue(DownloadsRepository.uiState.value.items.none { it.id == id })
        } finally {
            DownloadsRepository.clearLocalState()
            DownloadsRepository.backend = originalBackend
            DownloadsRepository.connectionHold = originalHold
            DownloadsRepository.companions = originalCompanions
        }
    }

    @Test
    fun aged_saved_session_refreshes_from_absolute_expiry_instead_of_original_lifetime(): Unit = runBlocking {
        val user = TestUser("user-a", "a@example.com", "aged-token")
        val harness = installMockBackend(activeUser = user)
        val saved = requireNotNull(harness.sessionManager.loadSession())
        harness.sessionManager.seed(saved.copy(expiresAt = Clock.System.now() + 20.seconds))
        val refreshes = AtomicInteger()
        val sentTokens = Collections.synchronizedList(mutableListOf<String?>())
        val fresh = user.copy(accessToken = "fresh-token")
        val offline = AtomicBoolean(false)
        val factory = {
            MockEngine { request ->
                if (offline.get() && "/auth/v1/" in request.url.encodedPath) throw java.io.IOException("Connection is away")
                when {
                    request.url.encodedPath.endsWith("/auth/v1/token") -> {
                        refreshes.incrementAndGet()
                        respondJson(tokenSessionJson(fresh))
                    }
                    request.url.encodedPath.endsWith("/auth/v1/user") -> respondJson(userJson(user))
                    request.url.encodedPath.endsWith("/rest/v1/addons") -> {
                        sentTokens += request.headers[HttpHeaders.Authorization]
                        respondJson("[]")
                    }
                    else -> respondJson("[]")
                }
            }
        }
        SupabaseProvider.engineFactory = factory
        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { rootDir }, storedSessionUserId = { user.id }, exitApp = {},
        )
        AuthRepository.initialize()
        awaitAuthenticatedUser(user.id)
        SupabaseProvider.client.postgrest.from("addons").select()
        assertEquals(listOf("Bearer fresh-token"), sentTokens.toList())
        assertEquals(1, refreshes.get())
        assertEquals("fresh-token", harness.sessionManager.loadSession()?.accessToken)

        // An already expired saved token is refreshed before startup publishes it.
        AuthRepository.resetForTest()
        SupabaseProvider.reset()
        harness.sessionManager.seed(saved.copy(expiresAt = Clock.System.now() - 60.seconds))
        SupabaseProvider.engineFactory = factory
        AuthRepository.initialize()
        awaitAuthenticatedUser(user.id)
        assertEquals(2, refreshes.get(), "Startup must refresh an expired saved token before publishing it")
        assertEquals("fresh-token", harness.sessionManager.loadSession()?.accessToken)

        AuthRepository.resetForTest()
        SupabaseProvider.reset()
        harness.sessionManager.seed(saved.copy(expiresAt = Clock.System.now() - 60.seconds))
        SupabaseProvider.engineFactory = factory
        offline.set(true)
        AuthRepository.initialize()
        awaitAuthenticatedUser(user.id)
        assertEquals("aged-token", harness.sessionManager.loadSession()?.accessToken)
        offline.set(false)
        SupabaseProvider.client.postgrest.from("addons").select()
        assertEquals("Bearer fresh-token", sentTokens.last())
        assertEquals(3, refreshes.get(), "An offline restore must keep the token expired until refresh succeeds")
        assertEquals("fresh-token", harness.sessionManager.loadSession()?.accessToken)
    }

    private fun seedAllAccountDataFiles(
        userId: String,
        profilesJson: String = """{"userId":"$userId","activeProfileIndex":1,"profiles":[]}""",
    ): Map<String, String> {
        val entries = linkedMapOf(
            "nuvio_library.properties" to "library_payload_1={\"id\":\"tt0111161\"}\n",
            "nuvio_downloads.properties" to "downloads_1=[{\"id\":\"dl-1\"}]\n",
            "nuvio_watch_progress.properties" to "watch_progress_payload_1={\"entries\":[]}\n",
            "nuvio_stream_badge_settings.properties" to "stream_badge_rules_1=[\"4K\"]\n",
            "nuvio_debrid_settings.properties" to "debrid_stream_badge_rules_1=[\"RD+\"]\n",
            "nuvio_profiles.properties" to "profiles=$profilesJson\n",
            "nuvio_player_settings.properties" to "preferred_audio_language_1=eng\n",
            "nuvio_addons.properties" to "installed_manifest_urls_1=[\"https://addon.example/manifest.json\"]\n",
            "server_credentials.properties" to "servers_1=[{\"id\":\"jellyfin-1\"}]\n",
            "nuvio_trakt_auth.properties" to "trakt_auth_payload_1={\"access_token\":\"trakt-tok\"}\n",
            "nuvio_simkl_auth.properties" to "simkl_auth_metadata_1={\"access_token\":\"simkl-tok\"}\n",
            "nuvio_mdblist_auth.properties" to "mdblist_auth_1={\"access_token\":\"mdblist-tok\"}\n",
        )
        entries.forEach { (name, content) ->
            Files.writeString(rootDir.resolve(name), content)
        }
        DesktopStorage.resetInMemoryStores()
        return entries
    }

    @Test
    fun a_finished_quit_stays_closed_and_a_failed_quit_can_be_retried(): Unit = runBlocking {
        val harness = installMockBackend(activeUser = TestUser("user-a", "a@example.com", "token-latch"))
        val exits = installDesktopLifecycle(harness)
        AuthRepository.initialize()
        awaitAuthenticatedUser("user-a")
        WatchProgressRepository.ensureLoaded()
        assertTrue(AuthRepository.signOut().isSuccess)

        val unregisterFailure = WatchProgressRepository.registerActivePlayerFlushHook {
            throw IllegalStateException("progress save failed")
        }
        val failed = DesktopAccountDataLifecycle.executeConfirmedQuit { exits.incrementAndGet() }
        unregisterFailure()
        assertTrue(failed.isFailure, "A progress save that throws must keep the app open")
        assertEquals(0, exits.get())
        assertFalse(DesktopAccountDataLifecycle.isConfirmedQuitLatched())
        assertFalse(rootDir.resolve(DesktopStorage.TransitionJournalFileName).exists())
        assertTrue(DesktopAccountDataLifecycle.tryScheduleConfirmedQuit(), "A failed quit must accept another close")
        DesktopAccountDataLifecycle.cancelPendingQuit()

        val flushes = AtomicInteger()
        val unregisterCount = WatchProgressRepository.registerActivePlayerFlushHook {
            flushes.incrementAndGet()
        }
        try {
            val started = AuthRepository.startFresh()
            assertTrue(started.isSuccess)
            assertEquals(1, exits.get())
            assertEquals(1, flushes.get(), "The quit gates run once")
            assertTrue(DesktopAccountDataLifecycle.isConfirmedQuitLatched())
            assertTrue(AccountDataLifecycle.isTransitionQuitPending())
            val journal = rootDir.resolve(DesktopStorage.TransitionJournalFileName)
            assertTrue(journal.exists())

            assertFalse(DesktopAccountDataLifecycle.tryScheduleConfirmedQuit())
            DesktopAccountDataLifecycle.cancelPendingQuit()
            assertTrue(journal.exists(), "Keep open after a finished quit must leave the transition journal in place")
            assertTrue(DesktopAccountDataLifecycle.isConfirmedQuitLatched())
            val secondFresh = AuthRepository.startFresh()
            assertTrue(secondFresh.isFailure, "A second account change must wait until this quit has left")
            assertEquals(1, exits.get())
            assertEquals(1, flushes.get(), "A second quit must not flush playback again")
            val secondExit = DesktopAccountDataLifecycle.executeConfirmedQuit { exits.incrementAndGet() }
            assertTrue(secondExit.isSuccess)
            assertEquals(1, exits.get(), "A second quit must not exit again")
            assertTrue(journal.exists())

            assertTrue(AuthRepository.signInWithEmail("a@example.com", "pw-a").isSuccess)
            assertEquals(AuthState.Unauthenticated, AuthRepository.state.value)
            assertNull(harness.sessionManager.loadSession())
        } finally {
            unregisterCount()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun the_window_stays_closed_after_quit_gates_return_and_a_failed_quit_opens_again() {
        val harness = runBlocking { installMockBackend(activeUser = TestUser("user-a", "a@example.com", "token-window-latch")) }
        val exits = installDesktopLifecycle(harness)
        runBlocking {
            AuthRepository.initialize()
            awaitAuthenticatedUser("user-a")
            WatchProgressRepository.ensureLoaded()
        }
        val releaseFailure = CompletableDeferred<Unit>()
        val failureEntered = CompletableDeferred<Unit>()
        val unregisterFailure = WatchProgressRepository.registerActivePlayerFlushHook {
            failureEntered.complete(Unit)
            runBlocking { releaseFailure.await() }
            throw IllegalStateException("progress save failed")
        }
        try {
            runComposeUiTest {
                val confirmQuit = AtomicReference<(() -> Unit)?>(null)
                setContent {
                    val appScope = rememberCoroutineScope()
                    val coordinator = rememberDesktopQuitCoordinator(
                        appScope = appScope,
                        onExitApplication = { exits.incrementAndGet() },
                    )
                    confirmQuit.set(coordinator.confirmQuitNow)
                    NuvioTheme {
                        Text(if (coordinator.isQuitting) "Quitting" else "Open")
                    }
                }
                waitForIdle()
                runOnIdle { confirmQuit.get()?.invoke() }
                runBlocking { withTimeout(5_000) { failureEntered.await() } }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("Quitting").fetchSemanticsNodes().isNotEmpty()
                }
                releaseFailure.complete(Unit)
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("Open").fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals(0, exits.get())
                assertFalse(DesktopAccountDataLifecycle.isConfirmedQuitLatched())

                unregisterFailure()
                runOnIdle { confirmQuit.get()?.invoke() }
                waitUntil(timeoutMillis = 5_000) {
                    exits.get() == 1 && onAllNodesWithText("Quitting").fetchSemanticsNodes().isNotEmpty()
                }
                assertTrue(DesktopAccountDataLifecycle.isConfirmedQuitLatched())
                runOnIdle { confirmQuit.get()?.invoke() }
                waitForIdle()
                assertEquals(1, exits.get(), "A second close after the gates return must not exit again")
                onNodeWithText("Quitting").assertIsDisplayed()
                assertFalse(DesktopAccountDataLifecycle.tryScheduleConfirmedQuit())
            }
        } finally {
            if (!releaseFailure.isCompleted) releaseFailure.complete(Unit)
            unregisterFailure()
        }
    }

    private suspend fun awaitAuthenticatedUser(expectedUserId: String) {
        val state = try {
            withTimeout(5_000) {
                AuthRepository.state.first {
                    it is AuthState.Authenticated && it.userId == expectedUserId
                } as AuthState.Authenticated
            }
        } catch (t: Throwable) {
            throw AssertionError(
                "Timed out waiting for Authenticated($expectedUserId); actual state=${AuthRepository.state.value}, error=${AuthRepository.error.value}, pendingAdoption=${AuthRepository.pendingAdoption.value}, activeServer=${ServerConfigurationRepository.active.value.backendUrl}",
                t,
            )
        }
        assertEquals(expectedUserId, state.userId)
    }

    private fun listArchiveDirs(dir: Path): List<Path> =
        Files.list(dir).use { stream ->
            stream
                .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && it.fileName.toString().startsWith("account-") }
                .toList()
        }

    private fun installDesktopLifecycle(
        harness: MockBackendHarness,
        customRoot: Path = rootDir,
    ): AtomicInteger {
        SupabaseProvider.engineFactory = harness.engineFactory
        SupabaseProvider.sessionManagerOverride = harness.sessionManager
        val exits = AtomicInteger(0)
        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { customRoot },
            storedSessionUserId = { harness.sessionManager.loadSession()?.user?.id },
            exitApp = { exits.incrementAndGet() },
        )
        return exits
    }

    private suspend fun simulateNextLaunch(
        harness: MockBackendHarness,
        customRoot: Path = rootDir,
    ) {
        simulateNextLaunchWithClock(
            harness = harness,
            customRoot = customRoot,
            fixedEpochMs = System.currentTimeMillis(),
        )
    }

    private suspend fun simulateNextLaunchWithClock(
        harness: MockBackendHarness,
        fixedEpochMs: Long,
        customRoot: Path = rootDir,
    ) {
        AuthRepository.resetForTest()
        ProfileRepository.clearInMemory()
        WatchProgressRepository.resetForTest()
        DesktopAccountDataLifecycle.resetForTest()
        DesktopStorage.resetInMemoryStores()
        SupabaseProvider.reset()
        SupabaseProvider.engineFactory = harness.engineFactory
        SupabaseProvider.sessionManagerOverride = harness.sessionManager
        DesktopAccountDataLifecycle.bootstrapAndInstall(
            rootDirProvider = { customRoot },
            storedSessionUserId = { harness.sessionManager.loadSession()?.user?.id },
            nowEpochMs = { fixedEpochMs },
            exitApp = {},
        )
        AuthRepository.initialize()
    }

    private suspend fun installMockBackend(
        activeUser: TestUser?,
        rejectRefresh: Boolean = false,
    ): MockBackendHarness {
        val userRef = AtomicReference(activeUser)
        val rejectRefreshRef = AtomicReference(rejectRefresh)
        val sessionManager = TrackingSessionManager()
        if (activeUser != null) {
            sessionManager.seed(
                UserSession(
                    accessToken = activeUser.accessToken,
                    refreshToken = "refresh-${activeUser.id}",
                    expiresIn = 3_600L,
                    tokenType = "bearer",
                    user = UserInfo(id = activeUser.id, aud = "authenticated", email = activeUser.email),
                ),
            )
        }
        val engineFactory = {
            MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path.endsWith("/auth/v1/token") -> {
                        if (rejectRefreshRef.get()) {
                            respond(
                                content = """{"error":"invalid_grant","error_description":"Invalid Refresh Token: Refresh Token Not Found"}""",
                                status = HttpStatusCode.BadRequest,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        } else {
                            val user = userRef.get()
                            if (user == null) {
                                respond(
                                    content = """{"error":"invalid_grant","error_description":"Invalid login credentials"}""",
                                    status = HttpStatusCode.BadRequest,
                                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                                )
                            } else {
                                respondJson(tokenSessionJson(user))
                            }
                        }
                    }
                    path.endsWith("/auth/v1/user") -> {
                        val user = userRef.get()
                        if (user == null) {
                            respond(
                                content = """{"msg":"Invalid JWT"}""",
                                status = HttpStatusCode.Unauthorized,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        } else {
                            respondJson(userJson(user))
                        }
                    }
                    path.endsWith("/auth/v1/logout") -> respond("", HttpStatusCode.NoContent)
                    else -> respondJson("[]")
                }
            }
        }
        SupabaseProvider.engineFactory = engineFactory
        SupabaseProvider.sessionManagerOverride = sessionManager
        return MockBackendHarness(
            engineFactory = engineFactory,
            sessionManager = sessionManager,
            activeUser = userRef,
            rejectRefresh = rejectRefreshRef,
        )
    }
}

private class SimulatedInterruptionException(step: String) : RuntimeException("Interrupted at $step")

private data class TestUser(
    val id: String,
    val email: String,
    val accessToken: String,
)

private data class RecordedCall(
    val path: String,
    val authorization: String?,
)

private class MockBackendHarness(
    val engineFactory: () -> MockEngine,
    val sessionManager: TrackingSessionManager,
    val activeUser: AtomicReference<TestUser?>,
    val rejectRefresh: AtomicReference<Boolean>,
)

private class TrackingSessionManager(
    private val onSave: (UserSession) -> Unit = {},
) : SessionManager {
    @Volatile
    private var current: UserSession? = null

    fun seed(session: UserSession) {
        current = session
    }

    override suspend fun saveSession(session: UserSession) {
        current = session
        onSave(session)
    }

    override suspend fun loadSession(): UserSession? = current

    override suspend fun deleteSession() {
        current = null
    }
}

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(body: String) =
    respond(
        content = body,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

private fun tokenSessionJson(user: TestUser): String =
    """{"access_token":"${user.accessToken}","token_type":"bearer","expires_in":3600,"refresh_token":"refresh-${user.id}","user":${userJson(user)}}"""

private fun userJson(user: TestUser): String =
    """{"id":"${user.id}","aud":"authenticated","email":"${user.email}"}"""
