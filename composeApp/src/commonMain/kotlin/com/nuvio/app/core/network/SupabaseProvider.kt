package com.nuvio.app.core.network

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.build.AppVersionConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.websocket.KtorRealtimeWebsocketFactory
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.supabaseJson
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.HttpHeaders
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.seconds

object SupabaseProvider {
    private val clientLock = SynchronizedObject()
    private var cachedClient: SupabaseClient? = null
    private var cachedPublicClient: SupabaseClient? = null
    private var cachedEngine: io.ktor.client.engine.HttpClientEngine? = null
    private var backendEngineReady = false
    private val rateLimitCoordinator = BackendRateLimitCoordinator()
    internal var engineFactory: (() -> HttpClientEngine)? = null
    internal var sessionManagerOverride: SessionManager? = null

    @OptIn(SupabaseInternal::class)
    val client: SupabaseClient
        get() = synchronized(clientLock) {
            cachedClient ?: try {
                createPrivateClient().also { cachedClient = it }
            } catch (error: Throwable) {
                abandonBackendEngineIfUnused()
                throw error
            }
        }

    @OptIn(SupabaseInternal::class)
    internal val publicClient: SupabaseClient
        get() = synchronized(clientLock) {
            cachedPublicClient ?: try {
                createPublicClient().also { cachedPublicClient = it }
            } catch (error: Throwable) {
                abandonBackendEngineIfUnused()
                throw error
            }
        }

    internal fun peekClient(): SupabaseClient? = synchronized(clientLock) { cachedClient }

    @OptIn(SupabaseInternal::class)
    internal suspend fun loadStoredSession(): UserSession? = runCatching {
        val manager = sessionManagerOverride ?: client.auth.sessionManager
        manager.loadSession()
    }.getOrNull()

    suspend fun loadStoredSessionUserId(): String? {
        val session = loadStoredSession() ?: return null
        return session.user?.id?.trim()?.takeIf { it.isNotEmpty() }
            ?: extractJwtSubClaim(session.accessToken)
    }

    internal suspend fun awaitSharedAuthReady(): SupabaseClient {
        val activeClient = client
        if (activeClient.auth.sessionStatus.value is SessionStatus.Initializing) {
            withTimeoutOrNull(150) {
                activeClient.auth.sessionStatus.first { it !is SessionStatus.Initializing }
            }
        }
        return activeClient
    }

    @OptIn(SupabaseInternal::class)
    internal suspend fun clearAndVerifyStoredSession(): Boolean {
        val activeClient = awaitSharedAuthReady()
        val clearAttempt = runCatching {
            activeClient.auth.clearSession()
            activeClient.auth.sessionManager.deleteSession()
        }
        if (clearAttempt.isFailure) return false
        if (activeClient.auth.currentSessionOrNull() != null) return false
        val reloaded = runCatching { activeClient.auth.sessionManager.loadSession() }
            .getOrElse { return false }
        return reloaded == null
    }

    @OptIn(SupabaseInternal::class)
    internal suspend fun restoreStoredSessionIfPresent(session: UserSession?) {
        if (session == null) return
        runCatching {
            val manager = sessionManagerOverride ?: awaitSharedAuthReady().auth.sessionManager
            manager.saveSession(session)
        }
    }

    @OptIn(SupabaseInternal::class, SupabaseExperimental::class)
    internal fun createThrowawayAuthClient(
        configuration: ServerConfiguration = ServerConfigurationRepository.active.value,
        includeDeviceLinkPlugins: Boolean = false,
    ): SupabaseClient {
        val userAgent = currentUserAgent()
        val customEngine = synchronized(clientLock) { engineForClient() }
        return try {
            createSupabaseClient(
                supabaseUrl = configuration.backendUrl,
                supabaseKey = configuration.publishableKey,
            ) {
                if (customEngine != null) {
                    httpEngine = customEngine
                }
                httpConfig {
                    installCommonHttpPlugins(userAgent)
                }
                install(Auth) {
                    sessionManager = MemorySessionManager()
                    codeVerifierCache = MemoryCodeVerifierCache()
                    autoLoadFromStorage = false
                    autoSaveToStorage = false
                    alwaysAutoRefresh = false
                    checkSessionOnRequest = false
                    autoSetupPlatform = false
                    enableLifecycleCallbacks = false
                }
                if (includeDeviceLinkPlugins) {
                    install(Postgrest) {
                        requireValidSession = false
                    }
                    install(Functions) {
                        requireValidSession = false
                    }
                }
            }
        } catch (error: Throwable) {
            synchronized(clientLock) { abandonBackendEngineIfUnused() }
            throw error
        }
    }

    @OptIn(SupabaseInternal::class, SupabaseExperimental::class)
    private fun createPrivateClient(): SupabaseClient {
        val configuration = ServerConfigurationRepository.active.value
        val userAgent = currentUserAgent()
        val customEngine = engineForClient()
        val customSessionManager = sessionManagerOverride
        lateinit var createdClient: SupabaseClient
        createdClient = createSupabaseClient(
            supabaseUrl = configuration.backendUrl,
            supabaseKey = configuration.publishableKey,
        ) {
            if (customEngine != null) {
                httpEngine = customEngine
            }
            httpConfig {
                installCommonHttpPlugins(userAgent)
                install(
                    createClientPlugin("CoordinatedAuthRefreshPlugin") {
                        onRequest { request, _ ->
                            val path = request.url.build().encodedPath
                            val authHeader = request.headers[HttpHeaders.Authorization]
                            if (!path.contains("/auth/v1/") && authHeader?.startsWith("Bearer ", ignoreCase = true) == true) {
                                val existingToken = authHeader.substringAfter(' ').trim()
                                val resolvedToken = AuthRepository.resolveRequestAccessToken(
                                    observedClient = createdClient,
                                    requestBearerToken = existingToken,
                                )
                                if (!resolvedToken.isNullOrBlank()) {
                                    request.headers[HttpHeaders.Authorization] = "Bearer $resolvedToken"
                                }
                            }
                        }
                    },
                )
                install(WebSockets) {
                    contentConverter = KotlinxWebsocketSerializationConverter(supabaseJson)
                }
            }
            install(Auth) {
                autoLoadFromStorage = false
                alwaysAutoRefresh = false
                checkSessionOnRequest = false
                autoSetupPlatform = false
                enableLifecycleCallbacks = false
                if (customSessionManager != null) {
                    sessionManager = customSessionManager
                }
            }
            install(Postgrest) {
                requireValidSession = true
            }
            install(Functions) {
                requireValidSession = true
            }
            install(Storage) {
                requireValidSession = true
            }
            install(Realtime) {
                customUrl = SupabaseEndpointConfig.realtimeUrl(configuration.backendUrl)
                reconnectDelay = 3.seconds
                websocketFactory = RealtimeSocketFactory { url ->
                    KtorRealtimeWebsocketFactory(createdClient.httpClient.httpClient).create(url)
                }
            }
        }
        return createdClient
    }

    /**
     * One engine for the signed-in client, the public client, and a throwaway check.
     * A test factory replaces it. The caller holds [clientLock].
     */
    private fun engineForClient(): io.ktor.client.engine.HttpClientEngine? {
        val factory = engineFactory
        if (factory != null) return factory()
        if (!backendEngineReady) {
            cachedEngine = createBackendHttpEngine()
            backendEngineReady = true
        }
        return cachedEngine
    }

    /** The caller holds [clientLock]. A failed first client must not leave the engine open. */
    private fun abandonBackendEngineIfUnused() {
        if (cachedClient != null || cachedPublicClient != null) return
        cachedEngine?.close()
        cachedEngine = null
        backendEngineReady = false
    }

    @OptIn(SupabaseInternal::class)
    private fun createPublicClient(): SupabaseClient {
        val configuration = ServerConfigurationRepository.active.value
        val userAgent = currentUserAgent()
        val customEngine = engineForClient()
        return createSupabaseClient(
            supabaseUrl = configuration.backendUrl,
            supabaseKey = configuration.publishableKey,
        ) {
            if (customEngine != null) {
                httpEngine = customEngine
            }
            httpConfig {
                installCommonHttpPlugins(userAgent)
            }
            install(Postgrest) {
                requireValidSession = false
            }
        }
    }

    private fun HttpClientConfig<*>.installCommonHttpPlugins(userAgent: String) {
        install(BackendRateLimitPlugin) {
            coordinator = rateLimitCoordinator
        }
        install(HttpRequestRetry) {
            retryOnExceptionIf(maxRetries = 1) { request, cause ->
                isSafeBackendRetryRequest(
                    method = request.method.value,
                    encodedPath = request.url.build().encodedPath,
                ) && SupabaseEndpointConfig.shouldRetryWithFallback(
                    requestUrl = request.url.buildString(),
                    cause = cause,
                )
            }
            retryIf(maxRetries = 1) { request, response ->
                val safeToRetry = isSafeBackendRetryRequest(
                    method = request.method.value,
                    encodedPath = request.url.encodedPath,
                )
                val retryableResponse = isRetryableBackendResponse(response.status.value)
                val fallbackEligible = SupabaseEndpointConfig.shouldRetryWithFallback(
                    requestUrl = request.url.toString(),
                    statusCode = response.status.value,
                )
                safeToRetry && (retryableResponse || fallbackEligible)
            }
            modifyRequest { request ->
                val retryResponse = response
                val retryCause = cause
                val shouldUseFallback = when {
                    retryResponse != null && shouldApplyBackendCooldown(
                        retryResponse.status.value,
                        retryResponse.headers[HttpHeaders.RetryAfter],
                    ) -> false
                    retryResponse != null -> SupabaseEndpointConfig.shouldRetryWithFallback(
                        requestUrl = request.url.buildString(),
                        statusCode = retryResponse.status.value,
                    )
                    retryCause != null -> SupabaseEndpointConfig.shouldRetryWithFallback(
                        requestUrl = request.url.buildString(),
                        cause = retryCause,
                    )
                    else -> false
                }
                if (shouldUseFallback) {
                    SupabaseEndpointConfig.fallbackUrlFor(request.url.buildString())?.let { fallbackUrl ->
                        request.url.takeFrom(fallbackUrl)
                    }
                }
            }
            delayMillis(respectRetryAfterHeader = false) { retryCount ->
                val retryResponse = response
                if (retryResponse != null && isRetryableBackendResponse(retryResponse.status.value)) {
                    backendRetryDelayMillis(
                        retryCount = retryCount,
                        retryAfterHeader = retryResponse.headers[HttpHeaders.RetryAfter],
                    )
                } else {
                    100L
                }
            }
        }
        defaultRequest {
            headers.append(HttpHeaders.UserAgent, userAgent)
        }
    }

    private fun currentUserAgent(): String =
        "NuvioMobile/${AppVersionConfig.VERSION_NAME.ifBlank { "dev" }}"

    suspend fun reset() {
        val previous = synchronized(clientLock) {
            val snapshot = Triple(cachedClient, cachedPublicClient, cachedEngine)
            cachedClient = null
            cachedPublicClient = null
            cachedEngine = null
            backendEngineReady = false
            rateLimitCoordinator.clear()
            snapshot
        }
        try {
            previous.first?.close()
        } finally {
            try {
                previous.second?.close()
            } finally {
                previous.third?.close()
            }
        }
    }

    internal suspend fun resetForTest() {
        engineFactory = null
        sessionManagerOverride = null
        reset()
    }
}

@OptIn(ExperimentalEncodingApi::class)
internal fun extractJwtSubClaim(token: String): String? = runCatching {
    val parts = token.split('.')
    if (parts.size < 2) return null
    val padded = when (parts[1].length % 4) {
        2 -> parts[1] + "=="
        3 -> parts[1] + "="
        else -> parts[1]
    }
    val payload = Base64.UrlSafe.decode(padded).decodeToString()
    Json.parseToJsonElement(payload).jsonObject["sub"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}.getOrNull()
