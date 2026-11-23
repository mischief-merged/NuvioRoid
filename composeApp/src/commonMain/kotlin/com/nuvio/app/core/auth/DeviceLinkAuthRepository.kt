package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.ServerConfiguration
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.ktor.client.statement.bodyAsText
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface DeviceLinkAuthState {
    data object Idle : DeviceLinkAuthState
    data object Starting : DeviceLinkAuthState
    data class Waiting(
        val code: String,
        val verificationUrl: String,
        val isCompleting: Boolean = false,
    ) : DeviceLinkAuthState
    data class Failed(val reason: DeviceLinkAuthFailure) : DeviceLinkAuthState
}

enum class DeviceLinkAuthFailure {
    Start,
    Expired,
    Complete,
}

object DeviceLinkAuthRepository {
    private const val maxConsecutivePollFailures = 3
    private const val maxPollAttempts = 120
    private const val officialLinkUrl = "https://nuvio.tv/link"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("DeviceLinkAuthRepository")
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<DeviceLinkAuthState>(DeviceLinkAuthState.Idle)
    val state: StateFlow<DeviceLinkAuthState> = _state.asStateFlow()
    private val attemptCounter = atomic(0L)
    private var activeJob: Job? = null

    @OptIn(ExperimentalUuidApi::class)
    fun start() {
        if (_state.value is DeviceLinkAuthState.Starting || _state.value is DeviceLinkAuthState.Waiting) return

        activeJob?.cancel()
        val myAttemptId = attemptCounter.incrementAndGet()
        val attemptEpoch = AuthRepository.currentAuthEpoch()
        val configuration = ServerConfigurationRepository.active.value

        activeJob = scope.launch {
            _state.value = DeviceLinkAuthState.Starting
            val nonce = Uuid.random().toString()
            val throwaway = SupabaseProvider.createThrowawayAuthClient(
                configuration = configuration,
                includeDeviceLinkPlugins = true,
            )
            var handedOffToAuth = false
            try {
                val session = startSession(
                    client = throwaway,
                    configuration = configuration,
                    nonce = nonce,
                    deviceName = currentDeviceClientMetadata().deviceName,
                )
                if (!isCurrentAttempt(myAttemptId, configuration)) return@launch
                _state.value = DeviceLinkAuthState.Waiting(
                    code = formatDeviceLinkCode(session.userCode),
                    verificationUrl = session.verificationUriComplete,
                )
                handedOffToAuth = pollAndComplete(
                    client = throwaway,
                    session = session,
                    nonce = nonce,
                    myAttemptId = myAttemptId,
                    attemptEpoch = attemptEpoch,
                    configuration = configuration,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: DeviceLinkAuthException) {
                log.w(error) { "Device link sign-in stopped" }
                if (attemptCounter.value == myAttemptId) {
                    _state.value = DeviceLinkAuthState.Failed(error.reason)
                }
            } catch (error: Throwable) {
                log.w(error) { "Device link sign-in failed" }
                if (attemptCounter.value == myAttemptId) {
                    _state.value = DeviceLinkAuthState.Failed(DeviceLinkAuthFailure.Start)
                }
            } finally {
                if (!handedOffToAuth) {
                    runCatching { throwaway.close() }
                }
            }
        }
    }

    fun cancel() {
        attemptCounter.incrementAndGet()
        activeJob?.cancel()
        activeJob = null
        _state.value = DeviceLinkAuthState.Idle
    }

    private fun isCurrentAttempt(myAttemptId: Long, configuration: ServerConfiguration): Boolean =
        attemptCounter.value == myAttemptId &&
            ServerConfigurationRepository.active.value.backendUrl == configuration.backendUrl

    private suspend fun startSession(
        client: SupabaseClient,
        configuration: ServerConfiguration,
        nonce: String,
        deviceName: String,
    ): DeviceLinkStartResponse {
        val params = buildJsonObject {
            put("p_device_nonce", nonce)
            put("p_redirect_base_url", configuration.deviceLinkUrl())
            put("p_device_name", deviceName)
            put("p_device_type", "mobile")
        }
        return client.postgrest
            .rpc("start_device_login_session", params)
            .decodeList<DeviceLinkStartResponse>()
            .firstOrNull()
            ?.takeIf {
                it.deviceCode.isNotBlank() &&
                    it.userCode.isNotBlank() &&
                    it.verificationUriComplete.isNotBlank()
            }
            ?: throw DeviceLinkAuthException(DeviceLinkAuthFailure.Start)
    }

    private suspend fun pollAndComplete(
        client: SupabaseClient,
        session: DeviceLinkStartResponse,
        nonce: String,
        myAttemptId: Long,
        attemptEpoch: Long,
        configuration: ServerConfiguration,
    ): Boolean {
        var pollAttempts = 0
        var consecutiveFailures = 0
        val intervalMillis = session.pollIntervalSeconds.coerceIn(2, 10) * 1_000L

        while (currentCoroutineContext().isActive && pollAttempts < maxPollAttempts) {
            delay(intervalMillis)
            if (!isCurrentAttempt(myAttemptId, configuration)) return false
            pollAttempts += 1
            val poll = try {
                val params = buildJsonObject {
                    put("p_code", session.deviceCode)
                    put("p_device_nonce", nonce)
                }
                client.postgrest
                    .rpc("poll_tv_login_session", params)
                    .decodeList<DeviceLinkPollResponse>()
                    .firstOrNull()
                    ?: throw DeviceLinkAuthException(DeviceLinkAuthFailure.Complete)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                consecutiveFailures += 1
                if (consecutiveFailures >= maxConsecutivePollFailures) throw error
                continue
            }
            consecutiveFailures = 0

            when (poll.status.trim().lowercase()) {
                "pending" -> Unit
                "approved" -> {
                    if (!isCurrentAttempt(myAttemptId, configuration)) return false
                    _state.value = DeviceLinkAuthState.Waiting(
                        code = formatDeviceLinkCode(session.userCode),
                        verificationUrl = session.verificationUriComplete,
                        isCompleting = true,
                    )
                    return completeSession(
                        client = client,
                        deviceCode = session.deviceCode,
                        nonce = nonce,
                        myAttemptId = myAttemptId,
                        attemptEpoch = attemptEpoch,
                        configuration = configuration,
                    )
                }
                else -> throw DeviceLinkAuthException(DeviceLinkAuthFailure.Expired)
            }
        }

        throw DeviceLinkAuthException(DeviceLinkAuthFailure.Expired)
    }

    private suspend fun completeSession(
        client: SupabaseClient,
        deviceCode: String,
        nonce: String,
        myAttemptId: Long,
        attemptEpoch: Long,
        configuration: ServerConfiguration,
    ): Boolean {
        try {
            val payload = buildJsonObject {
                put("code", deviceCode)
                put("device_nonce", nonce)
            }
            val response = client.functions.invoke("tv-logins-exchange", payload)
            val result = json.decodeFromString<DeviceLinkExchangeResponse>(response.bodyAsText())
            val user = result.user ?: client.auth.retrieveUser(result.accessToken)
            val expiresIn = requireNotNull(result.expiresIn?.takeIf { it > 0L })
            client.auth.importSession(
                UserSession(
                    accessToken = result.accessToken,
                    refreshToken = result.refreshToken,
                    expiresIn = expiresIn,
                    tokenType = result.tokenType?.takeIf { it.isNotBlank() } ?: "bearer",
                    user = user,
                ),
                autoRefresh = false,
            )
            if (!isCurrentAttempt(myAttemptId, configuration)) {
                runCatching { client.auth.signOut() }
                return false
            }
            AuthRepository.completeDeviceLinkSignIn(
                throwaway = client,
                attemptServer = configuration,
                attemptEpoch = attemptEpoch,
            )
            if (attemptCounter.value == myAttemptId) {
                _state.value = DeviceLinkAuthState.Idle
            }
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw DeviceLinkAuthException(DeviceLinkAuthFailure.Complete, error)
        }
    }

    private fun ServerConfiguration.deviceLinkUrl(): String =
        if (isCustom) "${backendUrl.trimEnd('/')}/link" else officialLinkUrl
}

internal fun formatDeviceLinkCode(value: String): String {
    val normalized = value.uppercase().filter { it.isLetterOrDigit() }.take(6)
    return if (normalized.length <= 3) normalized else "${normalized.take(3)}-${normalized.drop(3)}"
}

@Serializable
private data class DeviceLinkStartResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri_complete") val verificationUriComplete: String,
    @SerialName("poll_interval_seconds") val pollIntervalSeconds: Int = 3,
)

@Serializable
private data class DeviceLinkPollResponse(
    val status: String,
)

@Serializable
private data class DeviceLinkExchangeResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val user: UserInfo? = null,
)

private class DeviceLinkAuthException(
    val reason: DeviceLinkAuthFailure,
    cause: Throwable? = null,
) : Exception(reason.name, cause)
