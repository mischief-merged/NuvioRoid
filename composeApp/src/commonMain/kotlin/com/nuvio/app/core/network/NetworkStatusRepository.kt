package com.nuvio.app.core.network

import androidx.compose.runtime.Composable
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.details_check_connection
import nuvio.composeapp.generated.resources.details_servers_unreachable
import nuvio.composeapp.generated.resources.network_cannot_reach_servers
import nuvio.composeapp.generated.resources.network_connection_issue
import nuvio.composeapp.generated.resources.network_no_internet_connection
import nuvio.composeapp.generated.resources.network_please_check_connection
import org.jetbrains.compose.resources.stringResource

enum class NetworkCondition {
    Unknown,
    Checking,
    Online,
    NoInternet,
    ServersUnreachable,
}

data class NetworkStatusUiState(
    val condition: NetworkCondition = NetworkCondition.Unknown,
) {
    val isOnline: Boolean
        get() = condition == NetworkCondition.Online

    val isNoInternet: Boolean
        get() = condition == NetworkCondition.NoInternet

    val hasInternet: Boolean
        get() = condition != NetworkCondition.NoInternet

    val isOfflineLike: Boolean
        get() = condition == NetworkCondition.NoInternet || condition == NetworkCondition.ServersUnreachable
}

@Composable
fun NetworkCondition.titleForEmptyState(): String =
    when (this) {
        NetworkCondition.ServersUnreachable -> stringResource(Res.string.network_cannot_reach_servers)
        NetworkCondition.NoInternet -> stringResource(Res.string.network_no_internet_connection)
        else -> stringResource(Res.string.network_connection_issue)
    }

@Composable
fun NetworkCondition.messageForEmptyState(): String =
    when (this) {
        NetworkCondition.ServersUnreachable -> stringResource(Res.string.details_servers_unreachable)
        NetworkCondition.NoInternet -> stringResource(Res.string.details_check_connection)
        else -> stringResource(Res.string.network_please_check_connection)
    }

object NetworkStatusRepository {
    private const val REQUEST_TIMEOUT_MS = 4_500L
    private const val FOREGROUND_REFRESH_DELAY_MS = 6_000L
    private const val FOREGROUND_FAILURE_CONFIRM_DELAY_MS = 2_000L
    private const val DEFAULT_OFFLINE_RETRY_BASE_DELAY_MS = 5_000L
    private const val DEFAULT_OFFLINE_RETRY_MAX_DELAY_MS = 30_000L
    private const val DEFAULT_MIN_RECOVERY_PROBE_INTERVAL_MS = 5_000L
    private const val PUBLIC_PROBE_PRIMARY = "https://www.gstatic.com/generate_204"
    private const val PUBLIC_PROBE_FALLBACK = "https://cloudflare.com/cdn-cgi/trace"
    private val DEFAULT_PUBLIC_PROBE_URLS = listOf(PUBLIC_PROBE_PRIMARY, PUBLIC_PROBE_FALLBACK)

    private val monotonicMark = TimeSource.Monotonic.markNow()

    internal var publicProbeUrls: List<String> = DEFAULT_PUBLIC_PROBE_URLS
    internal var serverProbeUrls: (() -> List<Pair<String, Map<String, String>>>)? = null
    internal var offlineRetryBaseDelayMs: Long = DEFAULT_OFFLINE_RETRY_BASE_DELAY_MS
    internal var offlineRetryMaxDelayMs: Long = DEFAULT_OFFLINE_RETRY_MAX_DELAY_MS
    internal var minRecoveryProbeIntervalMs: Long = DEFAULT_MIN_RECOVERY_PROBE_INTERVAL_MS
    internal var nowMs: () -> Long = { monotonicMark.elapsedNow().inWholeMilliseconds }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(NetworkStatusUiState())
    val uiState: StateFlow<NetworkStatusUiState> = _uiState.asStateFlow()

    private var started = false
    private var pinnedForTest = false
    private var probeInFlight = false
    private var pendingProbeAfterCurrent = false
    private var pendingProbeConfirmFailures = false
    private var pendingProbeFromTraffic = false
    private var trafficObservedWhileProbing = false
    private var offlineRetryAttempt = 0
    private var trafficProbeAttempt = 0
    private var lastProbeCompletedAtMs = Long.MIN_VALUE
    private var activeProbeUrls: Set<String> = emptySet()
    private var foregroundRefreshJob: Job? = null
    private var probeJob: Job? = null
    private var offlineRetryJob: Job? = null
    private var recoveryCooldownJob: Job? = null
    private var probeGeneration = 0
    private val probeLock = Any()

    fun ensureStarted() {
        synchronized(probeLock) {
            if (started || pinnedForTest) return
            started = true
            startOrQueueProbeLocked(force = true, confirmFailures = false)
        }
    }

    fun requestForegroundRefresh() {
        val generation = synchronized(probeLock) {
            if (pinnedForTest) return
            started = true
            foregroundRefreshJob?.cancel()
            probeGeneration
        }
        val job = scope.launch {
            delay(FOREGROUND_REFRESH_DELAY_MS)
            synchronized(probeLock) {
                if (generation != probeGeneration || pinnedForTest) return@launch
                startOrQueueProbeLocked(force = true, confirmFailures = true)
            }
        }
        synchronized(probeLock) {
            if (generation == probeGeneration && !pinnedForTest) {
                foregroundRefreshJob = job
            } else {
                job.cancel()
            }
        }
    }

    fun requestRefresh(force: Boolean = false, confirmFailures: Boolean = false) {
        synchronized(probeLock) {
            if (pinnedForTest) return
            started = true
            startOrQueueProbeLocked(force = force, confirmFailures = confirmFailures)
        }
    }

    /**
     * Notes that an application request or download received data over the network.
     * When the repository is stuck in [NetworkCondition.NoInternet], schedules a rate-limited re-probe.
     */
    fun onTrafficObserved(url: String? = null) {
        synchronized(probeLock) {
            if (!started || pinnedForTest || _uiState.value.condition != NetworkCondition.NoInternet) return
            if (url != null && isOwnProbeUrlLocked(url)) return
            if (probeInFlight) {
                trafficObservedWhileProbing = true
                return
            }
            val minGap = requiredTrafficProbeGapMsLocked()
            val now = nowMs()
            val elapsed = if (lastProbeCompletedAtMs == Long.MIN_VALUE) {
                minGap
            } else {
                (now - lastProbeCompletedAtMs).coerceAtLeast(0L)
            }
            if (elapsed >= minGap) {
                startOrQueueProbeLocked(force = true, confirmFailures = false, fromTraffic = true)
            } else if (recoveryCooldownJob?.isActive != true) {
                val remaining = minGap - elapsed
                val generation = probeGeneration
                offlineRetryJob?.cancel()
                offlineRetryJob = null
                recoveryCooldownJob = scope.launch {
                    delay(remaining)
                    synchronized(probeLock) {
                        recoveryCooldownJob = null
                        if (generation != probeGeneration || !started || pinnedForTest) return@launch
                        if (_uiState.value.condition != NetworkCondition.NoInternet) return@launch
                        startOrQueueProbeLocked(force = true, confirmFailures = false, fromTraffic = true)
                    }
                }
            }
        }
    }

    private fun requiredTrafficProbeGapMsLocked(): Long {
        val base = minRecoveryProbeIntervalMs.coerceAtLeast(1L)
        if (trafficProbeAttempt <= 0) return base
        return offlineRetryDelayMs(
            attempt = trafficProbeAttempt + 1,
            baseDelayMs = base,
            maxDelayMs = offlineRetryMaxDelayMs.coerceAtLeast(base),
        )
    }

    /** Notes that the platform network monitor reported an active route. */
    fun onNetworkPathAvailable() {
        onTrafficObserved()
    }

    internal fun offlineRetryDelayMs(
        attempt: Int,
        baseDelayMs: Long = offlineRetryBaseDelayMs,
        maxDelayMs: Long = offlineRetryMaxDelayMs,
    ): Long {
        val base = baseDelayMs.coerceAtLeast(1L)
        val cap = maxDelayMs.coerceAtLeast(base)
        val shift = (attempt - 1).coerceIn(0, 10)
        val scaled = if (base > cap shr shift) cap else base shl shift
        return scaled.coerceIn(base, cap)
    }

    private fun startOrQueueProbeLocked(
        force: Boolean,
        confirmFailures: Boolean,
        fromTraffic: Boolean = false,
    ) {
        if (probeInFlight) {
            if (force) {
                pendingProbeAfterCurrent = true
                pendingProbeConfirmFailures = pendingProbeConfirmFailures || confirmFailures
                pendingProbeFromTraffic = pendingProbeFromTraffic || fromTraffic
            }
            return
        }

        val generation = probeGeneration
        probeInFlight = true
        offlineRetryJob?.cancel()
        offlineRetryJob = null
        recoveryCooldownJob?.cancel()
        recoveryCooldownJob = null
        probeJob = scope.launch {
            var nextConfirmFailures = confirmFailures
            var nextFromTraffic = fromTraffic
            try {
                while (true) {
                    val (runConfirmFailures, runFromTraffic) = synchronized(probeLock) {
                        if (generation != probeGeneration || pinnedForTest) return@launch
                        val confirm = nextConfirmFailures || pendingProbeConfirmFailures
                        val triggeredByTraffic = nextFromTraffic || pendingProbeFromTraffic
                        nextConfirmFailures = false
                        nextFromTraffic = false
                        pendingProbeAfterCurrent = false
                        pendingProbeConfirmFailures = false
                        pendingProbeFromTraffic = false
                        trafficObservedWhileProbing = false
                        confirm to triggeredByTraffic
                    }
                    runProbe(
                        confirmFailures = runConfirmFailures,
                        fromTraffic = runFromTraffic,
                        generation = generation,
                    )
                    val continueImmediately = synchronized(probeLock) {
                        if (generation != probeGeneration || pinnedForTest) return@launch
                        if (pendingProbeAfterCurrent) {
                            true
                        } else {
                            probeInFlight = false
                            scheduleOfflineRecoveryIfNeededLocked(generation)
                            false
                        }
                    }
                    if (!continueImmediately) break
                }
            } finally {
                synchronized(probeLock) {
                    if (generation == probeGeneration) {
                        probeInFlight = false
                    }
                }
            }
        }
    }

    private fun scheduleOfflineRecoveryIfNeededLocked(generation: Int) {
        if (!started || pinnedForTest || generation != probeGeneration) return
        if (_uiState.value.condition != NetworkCondition.NoInternet) {
            offlineRetryJob?.cancel()
            offlineRetryJob = null
            recoveryCooldownJob?.cancel()
            recoveryCooldownJob = null
            return
        }
        val fromTraffic = trafficObservedWhileProbing
        val waitMs = if (fromTraffic) {
            trafficObservedWhileProbing = false
            requiredTrafficProbeGapMsLocked()
        } else {
            offlineRetryDelayMs(offlineRetryAttempt)
        }
        offlineRetryJob?.cancel()
        offlineRetryJob = scope.launch {
            delay(waitMs)
            synchronized(probeLock) {
                offlineRetryJob = null
                if (generation != probeGeneration || !started || pinnedForTest) return@launch
                if (_uiState.value.condition != NetworkCondition.NoInternet) return@launch
                startOrQueueProbeLocked(force = true, confirmFailures = false, fromTraffic = fromTraffic)
            }
        }
    }

    private suspend fun runProbe(confirmFailures: Boolean, fromTraffic: Boolean, generation: Int) {
        val previousCondition = synchronized(probeLock) {
            if (generation != probeGeneration || pinnedForTest) return
            val current = _uiState.value.condition
            if (current == NetworkCondition.Unknown) {
                _uiState.value = NetworkStatusUiState(condition = NetworkCondition.Checking)
            }
            current
        }

        var nextCondition = probeCondition()
        if (
            confirmFailures &&
            previousCondition == NetworkCondition.Online &&
            nextCondition.isOfflineLike()
        ) {
            delay(FOREGROUND_FAILURE_CONFIRM_DELAY_MS)
            nextCondition = probeCondition()
        }

        synchronized(probeLock) {
            if (generation != probeGeneration || pinnedForTest) return
            lastProbeCompletedAtMs = nowMs()
            _uiState.value = NetworkStatusUiState(condition = nextCondition)
            if (nextCondition == NetworkCondition.NoInternet) {
                offlineRetryAttempt = (offlineRetryAttempt + 1).coerceAtMost(16)
                trafficProbeAttempt = if (fromTraffic) {
                    (trafficProbeAttempt + 1).coerceAtMost(16)
                } else {
                    0
                }
            } else {
                offlineRetryAttempt = 0
                trafficProbeAttempt = 0
                offlineRetryJob?.cancel()
                offlineRetryJob = null
                recoveryCooldownJob?.cancel()
                recoveryCooldownJob = null
            }
        }
    }

    private suspend fun probeCondition(): NetworkCondition {
        val publicUrls = publicProbeUrls
        val serverTargets = serverProbeUrls?.invoke() ?: SupabaseEndpointConfig.restEndpointUrls().map { url ->
            url to mapOf("apikey" to ServerConfigurationRepository.active.value.publishableKey)
        }
        synchronized(probeLock) {
            activeProbeUrls = (publicUrls + serverTargets.map { it.first }).mapTo(mutableSetOf()) { it.trim() }
        }
        val internetReachable = publicUrls.any { url -> probeReachable(url) }
        if (!internetReachable) {
            return NetworkCondition.NoInternet
        }

        val supabaseReachable = serverTargets.any { (url, headers) ->
            probeReachable(
                url = url,
                headers = headers,
            )
        }
        if (!supabaseReachable) {
            return NetworkCondition.ServersUnreachable
        }

        return NetworkCondition.Online
    }

    /** Asks the public internet now, whatever [uiState] last said. */
    suspend fun isInternetReachable(): Boolean =
        probePublicInternet().also { reachable ->
            if (reachable) onTrafficObserved()
        }

    internal fun setConditionForTest(condition: NetworkCondition) {
        synchronized(probeLock) {
            probeGeneration++
            pinnedForTest = true
            probeInFlight = false
            pendingProbeAfterCurrent = false
            pendingProbeConfirmFailures = false
            pendingProbeFromTraffic = false
            trafficObservedWhileProbing = false
            offlineRetryAttempt = 0
            trafficProbeAttempt = 0
            foregroundRefreshJob?.cancel()
            foregroundRefreshJob = null
            probeJob?.cancel()
            probeJob = null
            offlineRetryJob?.cancel()
            offlineRetryJob = null
            recoveryCooldownJob?.cancel()
            recoveryCooldownJob = null
            _uiState.value = NetworkStatusUiState(condition = condition)
        }
    }

    internal fun resetForTest() {
        synchronized(probeLock) {
            probeGeneration++
            started = false
            pinnedForTest = false
            probeInFlight = false
            pendingProbeAfterCurrent = false
            pendingProbeConfirmFailures = false
            pendingProbeFromTraffic = false
            trafficObservedWhileProbing = false
            offlineRetryAttempt = 0
            trafficProbeAttempt = 0
            lastProbeCompletedAtMs = Long.MIN_VALUE
            activeProbeUrls = emptySet()
            foregroundRefreshJob?.cancel()
            foregroundRefreshJob = null
            probeJob?.cancel()
            probeJob = null
            offlineRetryJob?.cancel()
            offlineRetryJob = null
            recoveryCooldownJob?.cancel()
            recoveryCooldownJob = null
            publicProbeUrls = DEFAULT_PUBLIC_PROBE_URLS
            serverProbeUrls = null
            offlineRetryBaseDelayMs = DEFAULT_OFFLINE_RETRY_BASE_DELAY_MS
            offlineRetryMaxDelayMs = DEFAULT_OFFLINE_RETRY_MAX_DELAY_MS
            minRecoveryProbeIntervalMs = DEFAULT_MIN_RECOVERY_PROBE_INTERVAL_MS
            nowMs = { monotonicMark.elapsedNow().inWholeMilliseconds }
            _uiState.value = NetworkStatusUiState()
        }
    }

    private fun isOwnProbeUrlLocked(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed in activeProbeUrls || publicProbeUrls.any { it.trim() == trimmed }
    }

    private suspend fun probePublicInternet(): Boolean {
        val urls = publicProbeUrls
        synchronized(probeLock) {
            activeProbeUrls = activeProbeUrls + urls.map { it.trim() }
        }
        return urls.any { url -> probeReachable(url) }
    }

    private suspend fun probeReachable(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Boolean {
        val response = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                httpRequestRaw(
                    method = "GET",
                    url = url,
                    headers = headers,
                    body = "",
                )
            }.getOrNull()
        } ?: return false

        return response.status in 100..599
    }

    private fun NetworkCondition.isOfflineLike(): Boolean =
        this == NetworkCondition.NoInternet || this == NetworkCondition.ServersUnreachable
}
