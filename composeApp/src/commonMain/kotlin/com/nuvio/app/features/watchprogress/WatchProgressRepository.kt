package com.nuvio.app.features.watchprogress

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.tracking.ensureTrackingProvidersRegistered
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonsUiState
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.downloads.AutoDownloads
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.servers.ServerItemRef
import com.nuvio.app.features.tracking.TrackingProgressProvider
import com.nuvio.app.features.tracking.TrackingProgressSnapshot
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.tracking.effectiveWatchProgressSource
import com.nuvio.app.features.tracking.providerId
import com.nuvio.app.features.watching.application.WatchingActions
import com.nuvio.app.features.watching.sync.ProgressDeltaEvent
import com.nuvio.app.features.watching.sync.ProgressSyncRecord
import com.nuvio.app.features.watching.sync.ProgressSyncAdapter
import com.nuvio.app.features.watching.sync.SupabaseProgressSyncAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

private const val WATCH_PROGRESS_METADATA_RESOLUTION_CONCURRENCY = 4
private const val WATCH_PROGRESS_METADATA_RESOLUTION_LIMIT = 64
private const val WATCH_PROGRESS_METADATA_FETCH_ATTEMPTS = 3
private const val WATCH_PROGRESS_METADATA_ENRICHMENT_MAX_ATTEMPTS = 3
private const val WATCH_PROGRESS_METADATA_RETRY_BASE_DELAY_MS = 750L
private const val WATCH_PROGRESS_DELTA_PAGE_SIZE = 900
private const val WATCH_PROGRESS_DELTA_OPERATION_UPSERT = "upsert"
private const val WATCH_PROGRESS_DELTA_OPERATION_DELETE = "delete"
private const val WATCH_PROGRESS_REMOTE_WRITE_DEDUP_WINDOW_MS = 5_000L

private data class RemoteMetadataResolutionResult(
    val key: WatchProgressMetadataKey,
    val entries: List<WatchProgressEntry>,
    val meta: MetaDetails?,
)

/**
 * Snapshot of the state a persist will write. It is kept in memory until the background
 * write lands so a reload never observes an older file than the state we already have.
 */
private data class PendingWatchProgressPayload(
    val sequence: Long = 0L,
    val entries: List<WatchProgressEntry>,
    val lastSuccessfulPushEpochMs: Long,
    val deltaCursorEventId: Long,
    val deltaInitialized: Boolean,
    val dirtyProgressKeys: Set<String>,
    val locallyFinishedEpisodeGroups: List<Set<String>> = emptyList(),
)

private data class ClearedLocallyFinishedResult(
    val changed: Boolean,
    val aliases: Set<LocallyFinishedEpisodeAlias>,
)

private data class MetadataProviderReadiness(
    val providers: List<AddonManifest>,
) {
    val fingerprint: String
        get() = providers.map(AddonManifest::transportUrl).sorted().joinToString(separator = "|")

    val isReady: Boolean
        get() = providers.isNotEmpty()
}

internal class MetadataResolutionRetryCoordinator {
    private val lock = SynchronizedObject()
    private var generation = 0L
    private var activeGeneration: Long? = null
    private var activeProviderFingerprint: String? = null
    private var lastRequestedProviderFingerprint: String? = null
    private var pendingProviderFingerprint: String? = null

    fun reset() {
        synchronized(lock) {
            generation += 1L
            activeGeneration = null
            activeProviderFingerprint = null
            lastRequestedProviderFingerprint = null
            pendingProviderFingerprint = null
        }
    }

    fun invalidateActiveResolution() {
        synchronized(lock) {
            generation += 1L
            activeGeneration = null
            activeProviderFingerprint = null
            pendingProviderFingerprint = null
        }
    }

    fun requestForProviders(providerFingerprint: String): Boolean =
        synchronized(lock) {
            if (activeGeneration != null) {
                if (providerFingerprint != activeProviderFingerprint) {
                    pendingProviderFingerprint = providerFingerprint
                }
                return@synchronized false
            }
            if (providerFingerprint == lastRequestedProviderFingerprint) {
                return@synchronized false
            }

            lastRequestedProviderFingerprint = providerFingerprint
            true
        }

    fun beginResolution(providerFingerprint: String?): Long =
        synchronized(lock) {
            generation += 1L
            activeGeneration = generation
            activeProviderFingerprint = providerFingerprint
            pendingProviderFingerprint = null
            if (providerFingerprint != null) {
                lastRequestedProviderFingerprint = providerFingerprint
            }
            generation
        }

    fun providersObservedBeforeFetch(
        resolutionGeneration: Long,
        providerFingerprint: String,
    ) {
        synchronized(lock) {
            if (activeGeneration != resolutionGeneration) return@synchronized
            activeProviderFingerprint = providerFingerprint
            lastRequestedProviderFingerprint = providerFingerprint
            if (pendingProviderFingerprint == providerFingerprint) {
                pendingProviderFingerprint = null
            }
        }
    }

    fun finishResolution(
        resolutionGeneration: Long,
        currentProviderFingerprint: String?,
    ): Boolean = synchronized(lock) {
        if (activeGeneration != resolutionGeneration) return@synchronized false

        activeGeneration = null
        val shouldRetry = currentProviderFingerprint != null &&
            currentProviderFingerprint != activeProviderFingerprint &&
            (pendingProviderFingerprint != null ||
                currentProviderFingerprint != lastRequestedProviderFingerprint)
        activeProviderFingerprint = null
        pendingProviderFingerprint = null
        if (shouldRetry) {
            lastRequestedProviderFingerprint = currentProviderFingerprint
        }
        shouldRetry
    }
}

private data class WatchProgressDeltaApplyResult(
    val appliedUpserts: Int,
    val appliedDeletes: Int,
    val preservedLocalItems: Boolean,
    val changed: Boolean,
)

internal enum class WatchProgressDeltaDecisionType {
    UPSERT,
    DELETE,
    PRESERVE_LOCAL,
    IGNORE,
}

internal data class WatchProgressDeltaDecision(
    val type: WatchProgressDeltaDecisionType,
    val updatedEntry: WatchProgressEntry? = null,
    val clearsDirtyProgress: Boolean = false,
)

private data class RemoteProgressWriteKey(
    val profileId: Int,
    val progressKey: String,
)

private data class RemoteProgressWrite(
    val entry: WatchProgressEntry,
    val sentAtEpochMs: Long,
)

internal class RemoteProgressWriteDeduplicator(
    private val windowMs: Long = WATCH_PROGRESS_REMOTE_WRITE_DEDUP_WINDOW_MS,
) {
    private val lock = SynchronizedObject()
    private val recentWrites = mutableMapOf<RemoteProgressWriteKey, RemoteProgressWrite>()

    fun shouldSend(
        profileId: Int,
        entry: WatchProgressEntry,
        nowEpochMs: Long,
    ): Boolean = synchronized(lock) {
        recentWrites.entries.removeAll { (_, write) ->
            val elapsedMs = nowEpochMs - write.sentAtEpochMs
            elapsedMs < 0L || elapsedMs >= windowMs
        }
        val key = RemoteProgressWriteKey(
            profileId = profileId,
            progressKey = entry.resolvedProgressKey(),
        )
        val normalizedEntry = entry.copy(lastUpdatedEpochMs = 0L)
        val previous = recentWrites[key]
        if (previous?.entry == normalizedEntry) {
            return@synchronized false
        }
        recentWrites[key] = RemoteProgressWrite(
            entry = normalizedEntry,
            sentAtEpochMs = nowEpochMs,
        )
        true
    }

    fun clear() {
        synchronized(lock) {
            recentWrites.clear()
        }
    }
}

private class ActivePlayerQuitHook(
    val onStopAndFlush: () -> Unit,
    val onQuitAborted: () -> Unit,
)

object WatchProgressRepository {
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val accountScopeLock = SynchronizedObject()
    private var accountScopeJob: Job = SupervisorJob()
    private var accountScope = CoroutineScope(accountScopeJob + Dispatchers.Default)
    private val log = Logger.withTag("WatchProgressRepository")

    private val _uiState = MutableStateFlow(WatchProgressUiState())
    val uiState: StateFlow<WatchProgressUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var hasLoadedNuvioRemoteProgress = false
    private var currentProfileId: Int = 1
    private var profileGeneration: Long = 0L
    private var activeSource: WatchProgressSource = WatchProgressSource.NUVIO_SYNC
    private val _activeSourceState = MutableStateFlow(activeSource)
    internal val activeSourceState: StateFlow<WatchProgressSource> = _activeSourceState.asStateFlow()
    private val entriesLock = SynchronizedObject()
    private var entriesByProgressKey: MutableMap<String, WatchProgressEntry> = mutableMapOf()
    private var dirtyProgressKeys: MutableSet<String> = mutableSetOf()
    private var locallyFinishedEpisodeGroups: MutableList<Set<String>> = mutableListOf()
    private var metadataResolutionJob: Job? = null
    private val metadataResolutionRetryCoordinator = MetadataResolutionRetryCoordinator()
    private val metadataEnrichmentLock = SynchronizedObject()
    private val metadataEnrichmentAttempts = mutableMapOf<String, Int>()
    private val persistLock = SynchronizedObject()
    private var persistSequence = 0L
    private val persistedSequenceByProfile = mutableMapOf<Int, Long>()
    private val pendingPersists = mutableMapOf<Int, PendingWatchProgressPayload>()
    private val activePersistJobs = mutableSetOf<Job>()
    private val activePlayerFlushHooks = LinkedHashSet<ActivePlayerQuitHook>()
    internal var beforePersistWriteForTest: (suspend () -> Unit)? = null
    private val providerMetadataOverlay = ProviderProgressMetadataOverlay()
    private val nuvioPullMutex = Mutex()
    private var lastSuccessfulPushEpochMs = 0L
    private var deltaCursorEventId = 0L
    private var deltaInitialized = false
    private val remoteWriteDeduplicator = RemoteProgressWriteDeduplicator()
    internal var syncAdapter: ProgressSyncAdapter = SupabaseProgressSyncAdapter

    init {
        ensureTrackingProvidersRegistered()
        TrackingProviderRegistry.progressProviders().forEach { provider ->
            syncScope.launch {
                provider.changes.collectLatest {
                    if (activeSource.providerId == provider.providerId) {
                        publish()
                        if (hasLoaded && !provider.providesCompleteMetadata) {
                            resolveRemoteMetadata()
                        }
                    }
                }
            }
        }

        syncScope.launch {
            AddonRepository.uiState.collectLatest { state ->
                retryMetadataResolutionWhenAddonMetaProvidersReady(state)
            }
        }

    }

    fun ensureLoaded() {
        ensureTrackingProvidersRegistered()
        TrackingProviderRegistry.ensureLoaded()
        TrackingSettingsRepository.ensureLoaded()
        TrackingProviderRegistry.progressProviders().forEach(TrackingProgressProvider::ensureLoaded)
        if (!hasLoaded) {
            updateActiveSource(
                effectiveWatchProgressSource(
                    requestedSource = TrackingSettingsRepository.uiState.value.watchProgressSource,
                    isProviderAuthenticated = ::isProgressProviderAvailable,
                ),
            )
            loadFromDisk(ProfileRepository.activeProfileId)
        }
    }

    fun onProfileChanged(profileId: Int) {
        if (profileId == currentProfileId && hasLoaded) return
        updateActiveSource(
            effectiveWatchProgressSource(
                requestedSource = TrackingSettingsRepository.uiState.value.watchProgressSource,
                isProviderAuthenticated = ::isProgressProviderAvailable,
            ),
        )
        loadFromDisk(profileId)
        TrackingProviderRegistry.progressProviders().forEach(TrackingProgressProvider::onProfileChanged)
    }

    fun clearLocalState() {
        val previousAccountJob = synchronized(accountScopeLock) {
            accountScopeJob.also {
                accountScopeJob = SupervisorJob()
                accountScope = CoroutineScope(accountScopeJob + Dispatchers.Default)
            }
        }
        previousAccountJob.cancel()
        cancelMetadataResolution(resetProviderHistory = true)
        hasLoaded = false
        hasLoadedNuvioRemoteProgress = false
        currentProfileId = 1
        profileGeneration += 1L
        updateActiveSource(WatchProgressSource.NUVIO_SYNC)
        providerMetadataOverlay.clear()
        clearLocalEntries()
        clearMetadataEnrichmentAttempts()
        lastSuccessfulPushEpochMs = 0L
        deltaCursorEventId = 0L
        deltaInitialized = false
        remoteWriteDeduplicator.clear()
        TrackingProviderRegistry.progressProviders().forEach(TrackingProgressProvider::clearLocalState)
        _uiState.value = WatchProgressUiState()
    }

    internal fun resetForTest() {
        beforePersistWriteForTest = null
        val jobs = synchronized(persistLock) {
            pendingPersists.clear()
            persistedSequenceByProfile.clear()
            persistSequence = 0L
            activePlayerFlushHooks.clear()
            activePersistJobs.toList().also { activePersistJobs.clear() }
        }
        jobs.forEach(Job::cancel)
        clearLocalState()
    }

    internal fun registerActivePlayerFlushHook(
        onQuitAborted: () -> Unit = {},
        onStopAndFlush: () -> Unit,
    ): () -> Unit {
        val entry = ActivePlayerQuitHook(
            onStopAndFlush = onStopAndFlush,
            onQuitAborted = onQuitAborted,
        )
        synchronized(persistLock) {
            activePlayerFlushHooks.add(entry)
        }
        return {
            synchronized(persistLock) {
                activePlayerFlushHooks.remove(entry)
            }
        }
    }

    internal fun flushActivePlayers() {
        val hooks = synchronized(persistLock) { activePlayerFlushHooks.toList() }
        var firstFailure: Throwable? = null
        hooks.forEach { hook ->
            try {
                hook.onStopAndFlush.invoke()
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                log.e(error) { "Active player progress flush hook failed" }
                if (firstFailure == null) {
                    firstFailure = error
                } else {
                    firstFailure.addSuppressed(error)
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure
        }
    }

    internal fun unfreezeActivePlayersAfterAbortedQuit() {
        val hooks = synchronized(persistLock) { activePlayerFlushHooks.toList() }
        hooks.forEach { hook ->
            runCatching { hook.onQuitAborted.invoke() }
                .onFailure { error -> log.w(error) { "Active player quit-abort hook failed" } }
        }
    }

    internal fun hasPendingOrActivePersistenceForTest(): Boolean = synchronized(persistLock) {
        pendingPersists.isNotEmpty() || activePersistJobs.isNotEmpty()
    }

    suspend fun drainPendingPersistence() {
        while (true) {
            while (true) {
                val jobs = synchronized(persistLock) { activePersistJobs.toList() }
                if (jobs.isEmpty()) break
                jobs.forEach { it.join() }
            }
            val remaining = synchronized(persistLock) { pendingPersists.toMap() }
            if (remaining.isEmpty()) break
            remaining.forEach { (profileId, pending) ->
                val payload = WatchProgressCodec.encodePayload(
                    entries = pending.entries,
                    lastSuccessfulPushEpochMs = pending.lastSuccessfulPushEpochMs,
                    deltaCursorEventId = pending.deltaCursorEventId,
                    deltaInitialized = pending.deltaInitialized,
                    dirtyProgressKeys = pending.dirtyProgressKeys,
                    locallyFinishedEpisodeGroups = pending.locallyFinishedEpisodeGroups,
                )
                synchronized(persistLock) {
                    if (pendingPersists[profileId]?.sequence == pending.sequence) {
                        val lastPersisted = persistedSequenceByProfile[profileId] ?: 0L
                        if (pending.sequence > lastPersisted) {
                            WatchProgressStorage.savePayload(profileId, payload)
                            persistedSequenceByProfile[profileId] = pending.sequence
                        }
                        pendingPersists.remove(profileId)
                    }
                }
            }
        }
    }

    private fun loadFromDisk(profileId: Int) {
        cancelMetadataResolution(resetProviderHistory = true)
        currentProfileId = profileId
        profileGeneration += 1L
        hasLoaded = true
        hasLoadedNuvioRemoteProgress = false
        providerMetadataOverlay.clear()
        clearLocalEntries()
        clearMetadataEnrichmentAttempts()

        val pending = synchronized(persistLock) { pendingPersists[profileId] }
        val payload = WatchProgressStorage.loadPayload(profileId).orEmpty().trim()
        if (pending != null) {
            // A persist is still in flight: its snapshot is newer than the stored file.
            lastSuccessfulPushEpochMs = pending.lastSuccessfulPushEpochMs
            deltaCursorEventId = pending.deltaCursorEventId
            deltaInitialized = pending.deltaInitialized
            replaceLocalEntries(pending.entries)
            replaceDirtyProgressKeys(pending.dirtyProgressKeys)
            replaceLocallyFinishedEpisodeGroups(pending.locallyFinishedEpisodeGroups)
        } else if (payload.isNotEmpty()) {
            val storedPayload = WatchProgressCodec.decodePayload(payload)
            lastSuccessfulPushEpochMs = storedPayload.lastSuccessfulPushEpochMs
            deltaCursorEventId = storedPayload.deltaCursorEventId
            deltaInitialized = storedPayload.deltaInitialized
            replaceLocalEntries(storedPayload.entries)
            replaceDirtyProgressKeys(storedPayload.dirtyProgressKeys)
            replaceLocallyFinishedEpisodeGroups(storedPayload.locallyFinishedEpisodeGroups)
        } else {
            lastSuccessfulPushEpochMs = 0L
            deltaCursorEventId = 0L
            deltaInitialized = false
        }
        log.d {
            "Loaded watch progress for profile $profileId: entries=${localEntryCount()} " +
                "deltaInitialized=$deltaInitialized cursor=$deltaCursorEventId lastPush=$lastSuccessfulPushEpochMs"
        }
        publish()
        resolveRemoteMetadata()
    }

    private fun activeOperationGeneration(profileId: Int): Long? {
        if (ProfileRepository.activeProfileId != profileId) return null
        if (!hasLoaded || currentProfileId != profileId) {
            loadFromDisk(profileId)
        }
        return profileGeneration
    }

    private fun isActiveOperation(profileId: Int, generation: Long): Boolean =
        currentProfileId == profileId &&
            profileGeneration == generation &&
            ProfileRepository.activeProfileId == profileId

    private fun isActiveMetadataTarget(
        profileId: Int,
        generation: Long,
        source: WatchProgressSource,
    ): Boolean = isActiveOperation(profileId, generation) && activeSource == source

    suspend fun pullFromServer(profileId: Int) {
        refreshForSource(
            profileId = profileId,
            source = activeSource,
            sourceChanged = false,
            force = false,
        )
    }

    suspend fun forceSnapshotRefreshFromServer(profileId: Int) {
        refreshForSource(
            profileId = profileId,
            source = activeSource,
            sourceChanged = false,
            force = true,
        )
    }

    suspend fun selectWatchProgressSource(profileId: Int, source: WatchProgressSource) {
        WatchProgressSourceCoordinator.selectSource(profileId = profileId, source = source)
    }

    suspend fun clearLocalAndForceSnapshotRefreshFromServer(profileId: Int) {
        ContinueWatchingEnrichmentCache.clearAll(profileId)
        WatchProgressSourceCoordinator.refreshActiveSource(profileId = profileId, force = true)
    }

    internal fun activateSource(source: WatchProgressSource) {
        TrackingProviderRegistry.ensureLoaded()
        TrackingSettingsRepository.ensureLoaded()
        TrackingProviderRegistry.progressProviders().forEach(TrackingProgressProvider::ensureLoaded)
        if (!hasLoaded) {
            loadFromDisk(ProfileRepository.activeProfileId)
        }
        if (activeSource == source) {
            publish()
            return
        }

        updateActiveSource(source)
        cancelMetadataResolution(resetProviderHistory = false)
        providerMetadataOverlay.clear()
        activeProgressProvider()?.onActivated() ?: run { hasLoadedNuvioRemoteProgress = false }
        publish()
        if (activeProgressProvider()?.providesCompleteMetadata != true) {
            resolveRemoteMetadata()
        }
    }

    internal suspend fun refreshForSource(
        profileId: Int,
        source: WatchProgressSource,
        sourceChanged: Boolean,
        force: Boolean,
    ): Boolean {
        ensureLoaded()
        if (currentProfileId != profileId) {
            loadFromDisk(profileId)
        }
        val operationGeneration = activeOperationGeneration(profileId) ?: run {
            log.d { "Skipping watch progress refresh for inactive profile $profileId" }
            return false
        }

        activateSource(source)
        activeProgressProvider()?.let { provider ->
            return refreshProviderSource(
                provider = provider,
                profileId = profileId,
                operationGeneration = operationGeneration,
                sourceChanged = sourceChanged,
                force = force,
            )
        }
        return refreshNuvioSource(
            profileId = profileId,
            operationGeneration = operationGeneration,
            force = force,
        )
    }

    private suspend fun refreshProviderSource(
        provider: TrackingProgressProvider,
        profileId: Int,
        operationGeneration: Long,
        sourceChanged: Boolean,
        force: Boolean,
    ): Boolean {
        if (!isProgressProviderAvailable(provider.providerId)) {
            log.d { "Skipping ${provider.providerId.storageId} progress refresh because it is unavailable" }
            return false
        }
        log.i {
            "Tracking progress refresh request profile=$profileId provider=${provider.providerId.storageId} " +
                "source=$activeSource sourceChanged=$sourceChanged force=$force " +
                "generation=$operationGeneration"
        }
        return try {
            provider.refresh(force = force, sourceChanged = sourceChanged)
            if (
                isActiveOperation(profileId, operationGeneration) &&
                activeSource.providerId == provider.providerId
            ) {
                publish()
            }
            val state = provider.snapshot()
            state.hasLoadedRemoteProgress && state.errorMessage == null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.e(error) { "Failed to refresh ${provider.providerId.storageId} watch progress" }
            false
        }
    }

    private fun activeProgressProvider(): TrackingProgressProvider? =
        activeSource.providerId?.let(TrackingProviderRegistry::progressProvider)

    private fun isProgressProviderAvailable(providerId: TrackingProviderId): Boolean =
        TrackingProviderRegistry.progressProvider(providerId) != null &&
            TrackingProviderRegistry.isAuthenticated(providerId)

    private suspend fun removeProviderProgress(
        provider: TrackingProgressProvider,
        entries: Collection<WatchProgressEntry>,
        reason: String,
    ) {
        try {
            provider.removeProgress(entries)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.e(error) {
                "Failed to $reason from ${provider.providerId.storageId}"
            }
        }
    }

    private suspend fun refreshNuvioSource(
        profileId: Int,
        operationGeneration: Long,
        force: Boolean,
    ): Boolean {
        val authState = AuthRepository.state.value
        if (authState !is AuthState.Authenticated || authState.isAnonymous) {
            // There is no upstream source for this account, so local state is authoritative.
            hasLoadedNuvioRemoteProgress = true
            publish()
            return true
        }

        return nuvioPullMutex.withLock {
            try {
                if (force) {
                    pullNuvioSnapshotFromServer(
                        profileId = profileId,
                        operationGeneration = operationGeneration,
                    )
                } else {
                    pullSupabaseDeltaFromServer(
                        profileId = profileId,
                        operationGeneration = operationGeneration,
                    )
                }
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.e(error) { "Failed to refresh Nuvio watch progress" }
                false
            }
        }
    }

    private suspend fun pullNuvioSnapshotFromServer(
        profileId: Int,
        operationGeneration: Long,
    ) {
        val cursorBeforeSnapshot = try {
            syncAdapter.getDeltaCursor(profileId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "Watch progress cursor unavailable during snapshot refresh: ${error.message}" }
            null
        }

        pullFullFromAdapter(
            profileId = profileId,
            resetDeltaState = cursorBeforeSnapshot == null,
            operationGeneration = operationGeneration,
            preserveLocalEntries = true,
        )
        if (!isActiveOperation(profileId, operationGeneration)) return

        if (cursorBeforeSnapshot != null) {
            deltaCursorEventId = cursorBeforeSnapshot
            deltaInitialized = true
            persist()
        }
    }

    private suspend fun pullSupabaseDeltaFromServer(
        profileId: Int,
        operationGeneration: Long,
    ) {
        if (!isActiveOperation(profileId, operationGeneration)) return
        log.d {
            "Watch progress delta sync start: profile=$profileId entries=${localEntryCount()} " +
                "deltaInitialized=$deltaInitialized cursor=$deltaCursorEventId lastPush=$lastSuccessfulPushEpochMs"
        }
        if (!deltaInitialized) {
            log.d { "Watch progress delta not initialized for profile $profileId; requesting cursor before snapshot" }
            val cursorBeforeSnapshot = try {
                syncAdapter.getDeltaCursor(profileId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w { "Watch progress delta cursor unavailable, falling back to full pull: ${error.message}" }
                null
            }
            if (cursorBeforeSnapshot == null) {
                log.d { "Watch progress delta cursor unavailable for profile $profileId; using snapshot fallback" }
                pullFullFromAdapter(
                    profileId = profileId,
                    resetDeltaState = true,
                    operationGeneration = operationGeneration,
                )
                return
            }

            log.d { "Watch progress delta cursor before snapshot for profile $profileId is $cursorBeforeSnapshot" }
            pullFullFromAdapter(
                profileId = profileId,
                resetDeltaState = false,
                operationGeneration = operationGeneration,
            )
            if (!isActiveOperation(profileId, operationGeneration)) return
            deltaCursorEventId = cursorBeforeSnapshot
            deltaInitialized = true
            persist()
            log.d {
                "Watch progress delta initialized for profile $profileId: cursor=$deltaCursorEventId " +
                    "entries=${localEntryCount()}"
            }
            return
        }

        var cursor = deltaCursorEventId
        var changed = false
        var totalUpserts = 0
        var totalDeletes = 0
        var preservedLocalItems = false
        var cursorAdvanced = false
        var page = 1

        while (true) {
            log.d { "Pulling watch progress delta page $page for profile $profileId from cursor $cursor" }
            val events = try {
                syncAdapter.pullDelta(
                    profileId = profileId,
                    sinceEventId = cursor,
                    limit = WATCH_PROGRESS_DELTA_PAGE_SIZE,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w { "Watch progress delta pull unavailable, falling back to full pull: ${error.message}" }
                pullFullFromAdapter(
                    profileId = profileId,
                    resetDeltaState = true,
                    operationGeneration = operationGeneration,
                )
                return
            }
            if (!isActiveOperation(profileId, operationGeneration)) return
            if (!AuthRepository.isRemoteAccountAuthenticated()) return
            if (events.isEmpty()) {
                log.d { "Watch progress delta page $page returned no events for profile $profileId at cursor $cursor" }
                break
            }

            val firstEvent = events.firstOrNull()?.eventId
            val lastEvent = events.lastOrNull()?.eventId
            val eventUpserts = events.count { it.operation.equals(WATCH_PROGRESS_DELTA_OPERATION_UPSERT, ignoreCase = true) }
            val eventDeletes = events.count { it.operation.equals(WATCH_PROGRESS_DELTA_OPERATION_DELETE, ignoreCase = true) }
            log.d {
                "Watch progress delta page $page fetched ${events.size} events for profile $profileId " +
                    "first=$firstEvent last=$lastEvent upserts=$eventUpserts deletes=$eventDeletes"
            }

            val pageResult = applyWatchProgressDeltaEvents(events = events)
            changed = pageResult.changed || changed
            totalUpserts += pageResult.appliedUpserts
            totalDeletes += pageResult.appliedDeletes
            preservedLocalItems = preservedLocalItems || pageResult.preservedLocalItems
            val previousCursor = cursor
            cursor = maxOf(cursor, events.maxOf { it.eventId })
            cursorAdvanced = cursorAdvanced || cursor > previousCursor
            deltaCursorEventId = cursor
            deltaInitialized = true
            log.d {
                "Watch progress delta page $page applied for profile $profileId: " +
                    "appliedUpserts=${pageResult.appliedUpserts} appliedDeletes=${pageResult.appliedDeletes} " +
                    "preservedLocal=${pageResult.preservedLocalItems} newCursor=$cursor"
            }

            if (events.size < WATCH_PROGRESS_DELTA_PAGE_SIZE) break
            page += 1
        }

        hasLoaded = true
        val remoteReadinessChanged = !hasLoadedNuvioRemoteProgress
        hasLoadedNuvioRemoteProgress = true
        if (changed || remoteReadinessChanged) {
            publish()
        }
        if (changed || cursorAdvanced) {
            persist()
        }
        if (changed) {
            resolveRemoteMetadata()
        }
        log.d {
            "Watch progress delta sync finished for profile $profileId: changed=$changed " +
                "appliedUpserts=$totalUpserts appliedDeletes=$totalDeletes preservedLocal=$preservedLocalItems " +
                "cursor=$deltaCursorEventId entries=${localEntryCount()}"
        }
    }

    private suspend fun pullFullFromAdapter(
        profileId: Int,
        resetDeltaState: Boolean,
        operationGeneration: Long,
        preserveLocalEntries: Boolean = true,
    ) {
        val serverEntries = syncAdapter.pull(profileId = profileId)
        if (!isActiveOperation(profileId, operationGeneration)) return
        if (!AuthRepository.isRemoteAccountAuthenticated()) return
        log.d {
            "Watch progress snapshot fetched ${serverEntries.size} entries for profile $profileId " +
                "resetDeltaState=$resetDeltaState preserveLocalEntries=$preserveLocalEntries"
        }
        val localBeforePull = localEntriesSnapshot()
        val reconciliation = reconcileLocalProgressKeysWithSnapshot(
            serverEntries = serverEntries,
            localEntries = localBeforePull,
        )
        migrateDirtyProgressKeys(reconciliation.migratedKeys)
        val dirtyBeforeApply = dirtyProgressKeysSnapshot()
        val updatedEntries = if (preserveLocalEntries) {
            mergeWatchProgressEntriesPreservingUnsynced(
                serverEntries = serverEntries,
                localEntries = reconciliation.entries,
                dirtyProgressKeys = dirtyBeforeApply,
            )
        } else {
            val newestRemoteByKey = linkedMapOf<String, WatchProgressEntry>()
            serverEntries.forEach { record ->
                val key = record.resolvedProgressKey()
                val candidate = record.toWatchProgressEntry(cached = null)
                val existing = newestRemoteByKey[key]
                if (existing == null || candidate.isFresherThan(existing)) {
                    newestRemoteByKey[key] = candidate
                }
            }
            newestRemoteByKey
        }
        replaceLocalEntries(updatedEntries)
        acknowledgeDirtyProgressFromSnapshot(
            serverEntries = serverEntries,
            localEntriesBeforeApply = reconciliation.entries,
            dirtyKeysBeforeApply = dirtyBeforeApply,
        )
        if (resetDeltaState) {
            deltaCursorEventId = 0L
            deltaInitialized = false
        }
        hasLoaded = true
        hasLoadedNuvioRemoteProgress = true
        publish()
        persist()
        resolveRemoteMetadata()
        log.d {
            "Watch progress snapshot applied for profile $profileId: entries=${localEntryCount()} " +
                "deltaInitialized=$deltaInitialized cursor=$deltaCursorEventId"
        }
    }

    private fun applyWatchProgressDeltaEvents(
        events: Collection<ProgressDeltaEvent>,
    ): WatchProgressDeltaApplyResult {
        var changed = false
        var appliedUpserts = 0
        var appliedDeletes = 0
        var preservedLocalItems = false
        val latestEventByProgressKey = linkedMapOf<String, ProgressDeltaEvent>()
        events.sortedBy(ProgressDeltaEvent::eventId).forEach { event ->
            val progressKey = event.resolvedProgressKey()
            if (progressKey.isBlank()) {
                return@forEach
            }
            when (event.operation.lowercase()) {
                WATCH_PROGRESS_DELTA_OPERATION_DELETE -> {
                    latestEventByProgressKey[progressKey] = event
                }
                WATCH_PROGRESS_DELTA_OPERATION_UPSERT -> {
                    if (event.videoId.isNotBlank()) {
                        latestEventByProgressKey[progressKey] = event
                    }
                }
                else -> Unit
            }
        }

        latestEventByProgressKey.forEach { (progressKey, event) ->
            val current = localEntry(progressKey)
            val decision = decideWatchProgressDeltaEvent(
                current = current,
                event = event,
                isLocalDirty = progressKey in dirtyProgressKeysSnapshot(),
            )
            when (decision.type) {
                WatchProgressDeltaDecisionType.UPSERT -> {
                    upsertLocalEntry(requireNotNull(decision.updatedEntry))
                    changed = true
                    appliedUpserts += 1
                }
                WatchProgressDeltaDecisionType.DELETE -> {
                    if (removeLocalEntry(progressKey) != null) {
                        changed = true
                        appliedDeletes += 1
                    }
                }
                WatchProgressDeltaDecisionType.PRESERVE_LOCAL -> {
                    preservedLocalItems = true
                }
                WatchProgressDeltaDecisionType.IGNORE -> Unit
            }
            if (decision.clearsDirtyProgress) {
                clearProgressDirty(progressKey)
            }
        }
        return WatchProgressDeltaApplyResult(
            appliedUpserts = appliedUpserts,
            appliedDeletes = appliedDeletes,
            preservedLocalItems = preservedLocalItems,
            changed = changed,
        )
    }

    internal fun decideWatchProgressDeltaEvent(
        current: WatchProgressEntry?,
        event: ProgressDeltaEvent,
        isLocalDirty: Boolean,
    ): WatchProgressDeltaDecision = when (event.operation.lowercase()) {
        WATCH_PROGRESS_DELTA_OPERATION_UPSERT -> {
            if (event.videoId.isBlank()) {
                WatchProgressDeltaDecision(WatchProgressDeltaDecisionType.IGNORE)
            } else {
                val updated = event.toProgressSyncRecord().toWatchProgressEntry(cached = current)
                when {
                    current == null ->
                        WatchProgressDeltaDecision(
                            type = WatchProgressDeltaDecisionType.UPSERT,
                            updatedEntry = updated,
                            clearsDirtyProgress = true,
                        )
                    isLocalDirty && current.isFresherThan(updated) ->
                        WatchProgressDeltaDecision(WatchProgressDeltaDecisionType.PRESERVE_LOCAL)
                    current == updated ->
                        WatchProgressDeltaDecision(
                            type = WatchProgressDeltaDecisionType.IGNORE,
                            clearsDirtyProgress = true,
                        )
                    else -> WatchProgressDeltaDecision(
                        type = WatchProgressDeltaDecisionType.UPSERT,
                        updatedEntry = updated,
                        clearsDirtyProgress = true,
                    )
                }
            }
        }
        WATCH_PROGRESS_DELTA_OPERATION_DELETE -> when {
            current == null -> WatchProgressDeltaDecision(WatchProgressDeltaDecisionType.IGNORE)
            isLocalDirty -> WatchProgressDeltaDecision(WatchProgressDeltaDecisionType.PRESERVE_LOCAL)
            else -> WatchProgressDeltaDecision(
                type = WatchProgressDeltaDecisionType.DELETE,
                clearsDirtyProgress = true,
            )
        }
        else -> WatchProgressDeltaDecision(WatchProgressDeltaDecisionType.IGNORE)
    }

    private fun ProgressSyncRecord.toWatchProgressEntry(cached: WatchProgressEntry?): WatchProgressEntry =
        WatchProgressEntry(
            contentType = contentType,
            parentMetaId = contentId,
            parentMetaType = cached?.parentMetaType ?: contentType,
            videoId = videoId,
            title = cached?.title?.takeIf { it.isNotBlank() } ?: contentId,
            logo = cached?.logo,
            poster = cached?.poster,
            background = cached?.background,
            seasonNumber = season,
            episodeNumber = episode,
            episodeTitle = cached?.episodeTitle,
            episodeThumbnail = cached?.episodeThumbnail,
            lastPositionMs = position,
            durationMs = duration,
            lastUpdatedEpochMs = lastWatched,
            providerName = cached?.providerName,
            providerAddonId = cached?.providerAddonId,
            lastStreamTitle = cached?.lastStreamTitle,
            lastStreamSubtitle = cached?.lastStreamSubtitle,
            pauseDescription = cached?.pauseDescription,
            lastSourceUrl = cached?.lastSourceUrl,
            isCompleted = isWatchProgressComplete(position, duration, false),
            progressKey = resolvedProgressKey(),
        )

    private fun ProgressDeltaEvent.toProgressSyncRecord(): ProgressSyncRecord =
        ProgressSyncRecord(
            progressKey = progressKey,
            contentId = contentId,
            contentType = contentType,
            videoId = videoId,
            season = season,
            episode = episode,
            position = position,
            duration = duration,
            lastWatched = lastWatched,
        )

    internal fun mergeWatchProgressEntriesPreservingUnsynced(
        serverEntries: Collection<ProgressSyncRecord>,
        localEntries: Collection<WatchProgressEntry>,
        dirtyProgressKeys: Set<String>,
    ): Map<String, WatchProgressEntry> {
        val reconciliation = reconcileLocalProgressKeysWithSnapshot(
            serverEntries = serverEntries,
            localEntries = localEntries,
        )
        val effectiveDirtyKeys = dirtyProgressKeys.mapTo(mutableSetOf()) { key ->
            reconciliation.migratedKeys[key] ?: key
        }
        val localByProgressKey = reconciliation.entries.newestByProgressKey()
        val merged = linkedMapOf<String, WatchProgressEntry>()
        serverEntries.forEach { record ->
            val progressKey = record.resolvedProgressKey()
            val candidate = record.toWatchProgressEntry(cached = localByProgressKey[progressKey])
            val existing = merged[progressKey]
            if (existing == null || candidate.isFresherThan(existing)) {
                merged[progressKey] = candidate
            }
        }

        localByProgressKey.forEach { (progressKey, localEntry) ->
            val remoteEntry = merged[progressKey]
            if (progressKey !in effectiveDirtyKeys) return@forEach
            if (remoteEntry == null || localEntry.isFresherThan(remoteEntry)) {
                merged[progressKey] = localEntry
            }
        }

        return merged
    }

    private fun retryMetadataResolutionWhenAddonMetaProvidersReady(state: AddonsUiState) {
        if (!hasLoaded || activeProgressProvider()?.providesCompleteMetadata == true) return

        val readiness = state.metadataProviderReadiness()
        if (!readiness.isReady) return

        val fingerprint = readiness.fingerprint
        if (!metadataResolutionRetryCoordinator.requestForProviders(fingerprint)) return
        resolveRemoteMetadata()
    }

    private fun cancelMetadataResolution(resetProviderHistory: Boolean) {
        if (resetProviderHistory) {
            metadataResolutionRetryCoordinator.reset()
        } else {
            metadataResolutionRetryCoordinator.invalidateActiveResolution()
        }
        metadataResolutionJob?.cancel()
        metadataResolutionJob = null
    }

    private fun isMetadataEnrichmentBackedOff(entry: WatchProgressEntry): Boolean =
        synchronized(metadataEnrichmentLock) {
            val attempts = metadataEnrichmentAttempts[entry.videoId] ?: 0
            attempts >= WATCH_PROGRESS_METADATA_ENRICHMENT_MAX_ATTEMPTS
        }

    private fun recordMetadataEnrichmentFailure(entries: List<WatchProgressEntry>) {
        synchronized(metadataEnrichmentLock) {
            entries.forEach { entry ->
                val attempts = (metadataEnrichmentAttempts[entry.videoId] ?: 0) + 1
                metadataEnrichmentAttempts[entry.videoId] = attempts
            }
        }
    }

    private fun recordMetadataEnrichmentSuccess(entries: List<WatchProgressEntry>) {
        synchronized(metadataEnrichmentLock) {
            entries.forEach { entry -> metadataEnrichmentAttempts.remove(entry.videoId) }
        }
    }

    private fun clearMetadataEnrichmentAttempts() {
        synchronized(metadataEnrichmentLock) {
            metadataEnrichmentAttempts.clear()
        }
    }

    private fun resolveRemoteMetadata() {
        val targetProfileId = currentProfileId
        val targetGeneration = profileGeneration
        val targetSource = activeSource
        val missingMetadataEntries = currentEntries()
            .filter(WatchProgressEntry::needsRemoteMetadataEnrichment)
            .filterNot { entry -> isMetadataEnrichmentBackedOff(entry) }
        val entriesToResolve = missingMetadataEntries.continueWatchingEntries(
            limit = WATCH_PROGRESS_METADATA_RESOLUTION_LIMIT,
        )
        val needsResolution = entriesToResolve
            .groupBy(WatchProgressEntry::metadataKey)

        if (needsResolution.isEmpty()) return

        val providersAtStart = AddonRepository.uiState.value.metadataProviderReadiness()
        val resolutionGeneration = metadataResolutionRetryCoordinator.beginResolution(
            providerFingerprint = providersAtStart.fingerprint.takeIf { providersAtStart.isReady },
        )
        metadataResolutionJob?.cancel()
        metadataResolutionJob = syncScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!isActiveMetadataTarget(targetProfileId, targetGeneration, targetSource)) return@launch
                AddonRepository.initialize()
                val providerReadiness = AddonRepository.uiState.value.metadataProviderReadiness()
                if (providerReadiness.isReady) {
                    metadataResolutionRetryCoordinator.providersObservedBeforeFetch(
                        resolutionGeneration = resolutionGeneration,
                        providerFingerprint = providerReadiness.fingerprint,
                    )
                }
                val semaphore = Semaphore(WATCH_PROGRESS_METADATA_RESOLUTION_CONCURRENCY)
                val resolutionResults = Channel<RemoteMetadataResolutionResult>(Channel.UNLIMITED)
                needsResolution.forEach { (key, entries) ->
                    launch {
                        val result = semaphore.withPermit {
                            fetchRemoteMetadataGroup(key = key, entries = entries)
                        }
                        resolutionResults.send(result)
                    }
                }

                var resolvedEntries = 0
                repeat(needsResolution.size) {
                    val result = resolutionResults.receive()
                    ensureActive()
                    if (!isActiveMetadataTarget(targetProfileId, targetGeneration, targetSource)) return@launch
                    val meta = result.meta
                    if (meta == null) {
                        recordMetadataEnrichmentFailure(result.entries)
                        return@repeat
                    }
                    recordMetadataEnrichmentSuccess(result.entries)

                    val appliedEntries = if (targetSource.providerId == null) {
                        var appliedLocalEntries = 0
                        for (entry in result.entries) {
                            val current = localEntry(entry.resolvedProgressKey()) ?: continue
                            val enriched = enrichWatchProgressEntry(current = current, meta = meta)
                            if (enriched == current) continue
                            upsertLocalEntry(enriched)
                            appliedLocalEntries += 1
                        }
                        appliedLocalEntries
                    } else if (providerMetadataOverlay.put(targetSource, result.key, meta)) {
                        result.entries.size
                    } else {
                        0
                    }
                    if (appliedEntries == 0) return@repeat

                    resolvedEntries += appliedEntries

                    if (isActiveMetadataTarget(targetProfileId, targetGeneration, targetSource)) {
                        publish()
                    }
                }
                resolutionResults.close()
                if (
                    targetSource.providerId == null &&
                    resolvedEntries > 0 &&
                    isActiveMetadataTarget(targetProfileId, targetGeneration, targetSource)
                ) {
                    persist()
                }
            } finally {
                val currentReadiness = AddonRepository.uiState.value.metadataProviderReadiness()
                val shouldRetry = metadataResolutionRetryCoordinator.finishResolution(
                    resolutionGeneration = resolutionGeneration,
                    currentProviderFingerprint = currentReadiness.fingerprint.takeIf { currentReadiness.isReady },
                )
                if (shouldRetry && hasLoaded && activeProgressProvider()?.providesCompleteMetadata != true) {
                    resolveRemoteMetadata()
                }
            }
        }
        metadataResolutionJob?.start()
    }

    private suspend fun fetchRemoteMetadataGroup(
        key: WatchProgressMetadataKey,
        entries: List<WatchProgressEntry>,
    ): RemoteMetadataResolutionResult {
        var meta: MetaDetails? = null
        for (attempt in 1..WATCH_PROGRESS_METADATA_FETCH_ATTEMPTS) {
            if (attempt > 1) {
                val retryDelayMs = WATCH_PROGRESS_METADATA_RETRY_BASE_DELAY_MS *
                    (1L shl (attempt - 2))
                delay(retryDelayMs)
            }
            meta = try {
                MetaDetailsRepository.fetch(key.metaType, key.metaId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                null
            }
            if (meta != null) break
        }
        return RemoteMetadataResolutionResult(
            key = key,
            entries = entries,
            meta = meta,
        )
    }

    fun upsertPlaybackProgress(
        session: WatchProgressPlaybackSession,
        snapshot: PlayerPlaybackSnapshot,
        syncRemote: Boolean = true,
        holdIncomplete: Boolean = false,
    ) {
        ensureLoaded()
        upsert(
            session = session,
            snapshot = snapshot,
            persist = true,
            syncRemote = syncRemote,
            holdIncomplete = holdIncomplete,
        )
    }

    fun flushPlaybackProgress(
        session: WatchProgressPlaybackSession,
        snapshot: PlayerPlaybackSnapshot,
        syncRemote: Boolean = true,
        holdIncomplete: Boolean = false,
    ) {
        ensureLoaded()
        upsert(
            session = session,
            snapshot = snapshot,
            persist = true,
            syncRemote = syncRemote,
            holdIncomplete = holdIncomplete,
        )
    }

    fun applyExternalProgress(entries: List<WatchProgressEntry>) {
        ensureLoaded()
        var changed = false
        entries.forEach { candidate ->
            val entry = localEntriesSnapshot().resolveIdentityForUpsert(candidate)
            val existing = localEntry(entry.resolvedProgressKey())
            if (existing != null && existing.lastUpdatedEpochMs >= entry.lastUpdatedEpochMs) return@forEach
            upsertLocalEntry(entry)
            changed = true
        }
        if (changed) {
            publish()
            persist()
        }
    }

    fun clearProgress(videoId: String, parentMetaId: String? = null) {
        clearProgress(videoIds = listOf(videoId), parentMetaId = parentMetaId)
    }

    fun clearProgress(
        videoIds: Collection<String>,
        parentMetaId: String? = null,
    ) {
        clearProgress(
            videoIds = videoIds,
            parentMetaId = parentMetaId,
            additionalAliases = emptySet(),
            clearLocallyFinished = true,
        )
    }

    internal fun clearProgress(
        videoIds: Collection<String>,
        parentMetaId: String? = null,
        additionalAliases: Set<LocallyFinishedEpisodeAlias>,
        clearLocallyFinished: Boolean = true,
    ) {
        ensureLoaded()
        if (videoIds.isEmpty() && additionalAliases.isEmpty()) return
        remoteWriteDeduplicator.clear()

        val initialAliases = resolveClearProgressAliases(videoIds = videoIds, parentMetaId = parentMetaId) +
            additionalAliases
        val matchingParentIds = buildSet {
            parentMetaId?.trim()?.takeIf { it.isNotEmpty() }?.let { normalizedParent ->
                add(normalizedParent)
                videoIds.forEach { videoId ->
                    activeProgressProvider()
                        ?.normalizeParentContentId(normalizedParent, videoId)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let(::add)
                }
            }
            initialAliases.forEach { add(it.parentMetaId) }
        }
        val matchesClearedEntry: (WatchProgressEntry) -> Boolean = { entry ->
            val matchesVideo = entry.videoId in videoIds &&
                (parentMetaId == null || entry.parentMetaId.trim() in matchingParentIds)
            val matchesAlias = entry.seasonNumber != null &&
                entry.episodeNumber != null &&
                LocallyFinishedEpisodeAlias(
                    parentMetaId = entry.parentMetaId.trim(),
                    seasonNumber = entry.seasonNumber,
                    episodeNumber = entry.episodeNumber,
                ) in initialAliases
            matchesVideo || matchesAlias
        }

        activeProgressProvider()?.let { provider ->
            val entriesToRemove = currentEntries().filter(matchesClearedEntry)
            val locallyRemovedEntries = removeStoredLocalEntries(entriesToRemove) +
                removeLocalEntriesMatching(matchesClearedEntry)
            val removedAll = entriesToRemove + locallyRemovedEntries
            val clearedLocalFinish = if (clearLocallyFinished) {
                clearLocallyFinishedForClearedProgress(
                    removedEntries = removedAll,
                    videoIds = videoIds,
                    parentMetaId = parentMetaId,
                    additionalAliases = additionalAliases,
                )
            } else {
                ClearedLocallyFinishedResult(changed = false, aliases = emptySet())
            }
            provider.applyOptimisticRemoval(entriesToRemove)
            if (locallyRemovedEntries.isNotEmpty() || clearedLocalFinish.changed) persist()
            publish()
            if (entriesToRemove.isNotEmpty()) {
                syncScope.launch {
                    removeProviderProgress(provider, entriesToRemove, "clear playback progress")
                }
            }
            cancelPendingRemovalsForClearedProgress(
                removedEntries = removedAll,
                videoIds = videoIds,
                parentMetaId = parentMetaId,
                clearedAliases = initialAliases + clearedLocalFinish.aliases,
            )
            return
        }

        val removedEntries = removeLocalEntriesMatching(matchesClearedEntry)
        val clearedLocalFinish = if (clearLocallyFinished) {
            clearLocallyFinishedForClearedProgress(
                removedEntries = removedEntries,
                videoIds = videoIds,
                parentMetaId = parentMetaId,
                additionalAliases = additionalAliases,
            )
        } else {
            ClearedLocallyFinishedResult(changed = false, aliases = emptySet())
        }
        if (removedEntries.isNotEmpty()) {
            publish()
            persist()
            pushDeleteToServer(removedEntries)
        } else if (clearedLocalFinish.changed) {
            persist()
        }
        cancelPendingRemovalsForClearedProgress(
            removedEntries = removedEntries,
            videoIds = videoIds,
            parentMetaId = parentMetaId,
            clearedAliases = initialAliases + clearedLocalFinish.aliases,
        )
    }

    fun removeProgress(
        contentId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ) {
        ensureLoaded()
        val normalizedContentId = contentId.trim()
        if (normalizedContentId.isBlank()) return
        remoteWriteDeduplicator.clear()

        val seedParentIds = seedParentIdsFor(
            parentMetaId = normalizedContentId,
            videoId = null,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )
        val clearedLocalFinish = removeLocallyFinishedEpisodes(
            parentMetaIds = seedParentIds,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )
        val targetParentIds = seedParentIds + clearedLocalFinish.aliases.map { it.parentMetaId }
        val matchesTarget: (WatchProgressEntry) -> Boolean = { entry ->
            if (entry.parentMetaId.trim() !in targetParentIds) {
                false
            } else if (seasonNumber != null && episodeNumber != null) {
                entry.seasonNumber == seasonNumber && entry.episodeNumber == episodeNumber
            } else {
                true
            }
        }
        val entriesToRemove = currentEntries().filter(matchesTarget)

        activeProgressProvider()?.let { provider ->
            val matchingLocalEntries = localEntriesSnapshot().filter(matchesTarget)
            val locallyRemovedEntries = removeStoredLocalEntries(entriesToRemove + matchingLocalEntries)
            if (entriesToRemove.isNotEmpty() || locallyRemovedEntries.isNotEmpty()) {
                provider.applyOptimisticRemoval(entriesToRemove)
                if (locallyRemovedEntries.isNotEmpty() || clearedLocalFinish.changed) persist()
                publish()
                if (entriesToRemove.isNotEmpty()) {
                    syncScope.launch {
                        removeProviderProgress(provider, entriesToRemove, "remove playback progress")
                    }
                }
            } else if (clearedLocalFinish.changed) {
                persist()
            }
            cancelPendingRemovalsForAliases(
                parentMetaIds = targetParentIds,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                clearedAliases = clearedLocalFinish.aliases,
            )
            return
        }

        if (entriesToRemove.isNotEmpty()) {
            entriesToRemove.forEach { entry ->
                removeLocalEntry(entry.resolvedProgressKey())
            }
            publish()
            persist()
            pushDeleteToServer(entriesToRemove)
        } else if (clearedLocalFinish.changed) {
            persist()
        }
        cancelPendingRemovalsForAliases(
            parentMetaIds = targetParentIds,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            clearedAliases = clearedLocalFinish.aliases,
        )
    }

    fun progressForVideo(
        videoId: String,
        parentMetaId: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ): WatchProgressEntry? {
        ensureLoaded()
        return currentEntries().resolveProgressForVideo(
            videoId = videoId,
            parentMetaId = parentMetaId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )
    }

    /**
     * Whether [parentMetaId] S[seasonNumber]E[episodeNumber] finished playing locally on this
     * device during this install and has not since been unwatched or cleared. Cloud-synced and
     * tracker-synced progress rows never set this mark.
     */
    internal fun isEpisodeLocallyFinished(
        parentMetaId: String,
        seasonNumber: Int,
        episodeNumber: Int,
    ): Boolean {
        ensureLoaded()
        val key = localFinishedEpisodeKey(parentMetaId, seasonNumber, episodeNumber)
        return synchronized(entriesLock) { locallyFinishedEpisodeGroups.any { key in it } }
    }

    /** Returns every episode alias recorded together for this finished episode. */
    internal fun locallyFinishedEpisodeAliases(
        parentMetaId: String,
        seasonNumber: Int,
        episodeNumber: Int,
        videoId: String? = null,
    ): Set<LocallyFinishedEpisodeAlias> {
        ensureLoaded()
        val seedParents = seedParentIdsFor(
            parentMetaId = parentMetaId,
            videoId = videoId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )
        if (seedParents.isEmpty()) return emptySet()
        val seedKeys = seedParents.mapTo(mutableSetOf()) { parent ->
            localFinishedEpisodeKey(parent, seasonNumber, episodeNumber)
        }
        val groupAliases = synchronized(entriesLock) {
            locallyFinishedEpisodeGroups
                .filter { group -> group.any { it in seedKeys } }
                .flatMapTo(linkedSetOf()) { group ->
                    group.mapNotNull(::parseLocalFinishedEpisodeKey)
                }
        }
        return buildSet {
            seedParents.forEach { parent ->
                add(LocallyFinishedEpisodeAlias(parent, seasonNumber, episodeNumber))
            }
            addAll(groupAliases)
        }
    }

    /** Returns every parent ID recorded together as an alias for this finished episode. */
    internal fun locallyFinishedParentAliases(
        parentMetaId: String,
        seasonNumber: Int,
        episodeNumber: Int,
        videoId: String? = null,
    ): Set<String> = locallyFinishedEpisodeAliases(
        parentMetaId = parentMetaId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        videoId = videoId,
    ).mapTo(linkedSetOf()) { it.parentMetaId }

    /**
     * Clears the device-local finished mark and every alias recorded for that episode when an
     * episode (or show) is unwatched. Returns every cleared episode alias.
     */
    internal fun clearLocallyFinishedEpisode(
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String? = null,
    ): Set<LocallyFinishedEpisodeAlias> {
        ensureLoaded()
        val seedParents = seedParentIdsFor(
            parentMetaId = parentMetaId,
            videoId = videoId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )
        if (seedParents.isEmpty()) return emptySet()
        remoteWriteDeduplicator.clear()
        val result = removeLocallyFinishedEpisodes(
            parentMetaIds = seedParents,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )
        if (result.changed) {
            persist()
        }
        return buildSet {
            addAll(result.aliases)
            if (seasonNumber != null && episodeNumber != null) {
                seedParents.forEach { parent ->
                    add(LocallyFinishedEpisodeAlias(parent, seasonNumber, episodeNumber))
                }
            }
        }
    }

    fun resumeEntryForSeries(metaId: String): WatchProgressEntry? {
        ensureLoaded()
        return currentEntries().resumeEntryForSeries(metaId)
    }

    fun continueWatching(): List<WatchProgressEntry> {
        ensureLoaded()
        return currentEntries().continueWatchingEntries()
    }

    fun refreshEpisodeProgress(contentId: String, forceRefresh: Boolean = false) {
        ensureLoaded()
        val provider = activeProgressProvider() ?: return
        syncScope.launch {
            runCatching {
                provider.refreshEpisodeProgress(
                    contentId = contentId,
                    forceRefresh = forceRefresh,
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                log.w {
                    "Failed to refresh ${provider.providerId.storageId} episode progress " +
                        "for $contentId: ${error.message}"
                }
            }
        }
    }

    private fun upsert(
        session: WatchProgressPlaybackSession,
        snapshot: PlayerPlaybackSnapshot,
        persist: Boolean,
        syncRemote: Boolean,
        holdIncomplete: Boolean = false,
    ) {
        val targetProfileId = session.profileId
        val positionMs = snapshot.positionMs.coerceAtLeast(0L)
        val durationMs = snapshot.durationMs.coerceAtLeast(0L)
        val completion = resolvePlaybackCompletion(
            positionMs = positionMs,
            durationMs = durationMs,
            isEnded = snapshot.isEnded,
            holdIncomplete = holdIncomplete,
        )
        val isCompleted = completion.isCompleted
        if (!completion.completionHeld && !isCompleted && !shouldStoreWatchProgress(positionMs = positionMs, durationMs = durationMs)) {
            return
        }

        val progressProvider = activeProgressProvider()
        val effectiveParentMetaId = progressProvider?.normalizeParentContentId(
            parentContentId = session.parentMetaId,
            videoId = session.videoId,
        ) ?: session.parentMetaId

        val candidateEntry = WatchProgressEntry(
            contentType = session.contentType,
            parentMetaId = effectiveParentMetaId,
            parentMetaType = session.parentMetaType,
            videoId = session.videoId,
            title = session.title,
            logo = session.logo,
            poster = session.poster,
            background = session.background,
            seasonNumber = session.seasonNumber,
            episodeNumber = session.episodeNumber,
            episodeTitle = session.episodeTitle,
            episodeThumbnail = session.episodeThumbnail,
            lastPositionMs = completion.storedPositionMs,
            durationMs = durationMs,
            lastUpdatedEpochMs = WatchProgressClock.nowEpochMs(),
            providerName = session.providerName,
            providerAddonId = session.providerAddonId,
            lastStreamTitle = session.lastStreamTitle,
            lastStreamSubtitle = session.lastStreamSubtitle,
            pauseDescription = session.pauseDescription,
            lastSourceUrl = session.lastSourceUrl,
            isCompleted = isCompleted,
            completionHeld = completion.completionHeld,
        ).normalizedCompletion()

        if (targetProfileId != currentProfileId || ProfileRepository.activeProfileId != targetProfileId) {
            val resolvedEntry = resolveStoredProfileProgressIdentity(
                profileId = targetProfileId,
                entry = candidateEntry,
            )
            if (
                syncRemote &&
                !remoteWriteDeduplicator.shouldSend(
                    profileId = targetProfileId,
                    entry = resolvedEntry,
                    nowEpochMs = candidateEntry.lastUpdatedEpochMs,
                )
            ) {
                return
            }
            val entry = if (persist) {
                upsertStoredProfileProgress(
                    profileId = targetProfileId,
                    entry = resolvedEntry,
                    finishedEpisodeKeys = locallyFinishedKeysForCompletion(
                        entry = resolvedEntry,
                        effectiveParentMetaId = effectiveParentMetaId,
                        sessionParentMetaId = session.parentMetaId,
                    ),
                )
            } else {
                resolvedEntry
            }
            if (syncRemote) {
                pushScrobbleToServer(entry = entry, profileId = targetProfileId)
            }
            return
        }

        val entry = localEntriesSnapshot().resolveIdentityForUpsert(candidateEntry)
        if (
            syncRemote &&
            !remoteWriteDeduplicator.shouldSend(
                profileId = targetProfileId,
                entry = entry,
                nowEpochMs = candidateEntry.lastUpdatedEpochMs,
            )
        ) {
            return
        }

        if (entry.parentMetaType.equals("series", ignoreCase = true)) {
            ContinueWatchingPreferencesRepository.removeDismissedNextUpKeysForContent(entry.parentMetaId)
        }

        upsertLocalEntry(entry)
        markProgressDirty(entry)
        markLocallyFinishedEpisodeGroup(
            locallyFinishedKeysForCompletion(
                entry = entry,
                effectiveParentMetaId = effectiveParentMetaId,
                sessionParentMetaId = session.parentMetaId,
            ),
        )
        progressProvider?.applyOptimisticProgress(entry)
        publish()
        if (persist) persist()
        if (entry.needsRemoteMetadataEnrichment() && !isMetadataEnrichmentBackedOff(entry)) {
            resolveRemoteMetadata()
        }
        if (syncRemote) {
            pushScrobbleToServer(entry = entry, profileId = targetProfileId)
        }
        val providerOwnsCompletedHistory = progressProvider?.ownsCompletedHistoryProjection == true
        if (
            shouldCascadeCompletedProgressToWatchedHistory(
                entry = entry,
                providerOwnsCompletedHistory = providerOwnsCompletedHistory,
            )
        ) {
            WatchingActions.onProgressEntryUpdated(entry, syncRemote = syncRemote)
        } else if (entry.normalizedCompletion().isCompleted) {
            // Even when a tracker owns remote history, record the completed watch locally right
            // away so offline playback, watched state and automatic downloads stay in sync.
            WatchingActions.onProgressEntryUpdated(entry, syncRemote = false)
        }
    }

    private fun upsertStoredProfileProgress(
        profileId: Int,
        entry: WatchProgressEntry,
        finishedEpisodeKeys: Set<String> = emptySet(),
    ): WatchProgressEntry {
        val payload = WatchProgressStorage.loadPayload(profileId).orEmpty().trim()
        val storedPayload = if (payload.isNotEmpty()) {
            WatchProgressCodec.decodePayload(payload)
        } else {
            StoredWatchProgressPayload()
        }
        val resolvedEntry = storedPayload.entries.resolveIdentityForUpsert(entry)
        val progressKey = resolvedEntry.resolvedProgressKey()
        val updatedEntries = storedPayload.entries
            .filterNot { it.resolvedProgressKey() == progressKey } + resolvedEntry
        WatchProgressStorage.savePayload(
            profileId,
            WatchProgressCodec.encodePayload(
                entries = updatedEntries,
                lastSuccessfulPushEpochMs = storedPayload.lastSuccessfulPushEpochMs,
                deltaCursorEventId = storedPayload.deltaCursorEventId,
                deltaInitialized = storedPayload.deltaInitialized,
                dirtyProgressKeys = storedPayload.dirtyProgressKeys + progressKey,
                locallyFinishedEpisodeGroups = mergeLocallyFinishedEpisodeGroup(
                    existingGroups = storedPayload.locallyFinishedEpisodeGroups,
                    newGroup = finishedEpisodeKeys,
                ),
            ),
        )
        return resolvedEntry
    }

    private fun resolveStoredProfileProgressIdentity(
        profileId: Int,
        entry: WatchProgressEntry,
    ): WatchProgressEntry {
        val payload = WatchProgressStorage.loadPayload(profileId).orEmpty().trim()
        val storedEntries = if (payload.isEmpty()) {
            emptyList()
        } else {
            WatchProgressCodec.decodePayload(payload).entries
        }
        return storedEntries.resolveIdentityForUpsert(entry)
    }

    private fun pushScrobbleToServer(entry: WatchProgressEntry, profileId: Int) {
        if (ServerItemRef.isServerId(entry.parentMetaId)) return
        val operationGeneration = profileGeneration.takeIf { profileId == currentProfileId }
        accountScopeSnapshot().launch {
            runCatching {
                syncAdapter.push(profileId = profileId, entries = listOf(entry))
                recordSuccessfulPush(
                    profileId = profileId,
                    operationGeneration = operationGeneration,
                    entries = listOf(entry),
                )
            }.onFailure { e ->
                log.e(e) { "Failed to push watch progress scrobble" }
            }
        }
    }

    private fun pushDeleteToServer(entries: Collection<WatchProgressEntry>) {
        if (activeSource.providerId != null) return
        val entries = entries.filterNot { ServerItemRef.isServerId(it.parentMetaId) }
        val profileId = currentProfileId
        accountScopeSnapshot().launch {
            runCatching {
                if (entries.isEmpty()) return@runCatching
                syncAdapter.delete(profileId = profileId, entries = entries)
            }.onFailure { e ->
                log.e(e) { "Failed to push watch progress delete" }
            }
        }
    }

    private fun publish() {
        val entries = currentEntries()
        val sortedEntries = entries.sortedByDescending { it.lastUpdatedEpochMs }
        val providerSnapshot = activeProgressProvider()?.snapshot()
        _uiState.value = projectWatchProgressUiState(
            source = activeSource,
            entries = sortedEntries,
            providerSnapshot = providerSnapshot,
            hasLoadedNuvioRemoteProgress = hasLoadedNuvioRemoteProgress,
        )
    }

    private fun persist() {
        val profileId = currentProfileId
        val snapshot = PendingWatchProgressPayload(
            entries = localEntriesSnapshot(),
            lastSuccessfulPushEpochMs = lastSuccessfulPushEpochMs,
            deltaCursorEventId = deltaCursorEventId,
            deltaInitialized = deltaInitialized,
            dirtyProgressKeys = dirtyProgressKeysSnapshot(),
            locallyFinishedEpisodeGroups = locallyFinishedEpisodeGroupsSnapshot(),
        )
        lateinit var persistJob: Job
        val pending = synchronized(persistLock) {
            persistSequence += 1
            snapshot.copy(sequence = persistSequence).also { pendingPersists[profileId] = it }
        }
        // The JSON encode and the SharedPreferences write run off the caller's dispatcher:
        // the player persists progress ticks from the main thread.
        persistJob = syncScope.launch(start = CoroutineStart.LAZY) {
            try {
                val payload = WatchProgressCodec.encodePayload(
                    entries = pending.entries,
                    lastSuccessfulPushEpochMs = pending.lastSuccessfulPushEpochMs,
                    deltaCursorEventId = pending.deltaCursorEventId,
                    deltaInitialized = pending.deltaInitialized,
                    dirtyProgressKeys = pending.dirtyProgressKeys,
                    locallyFinishedEpisodeGroups = pending.locallyFinishedEpisodeGroups,
                )
                beforePersistWriteForTest?.invoke()
                synchronized(persistLock) {
                    val lastPersisted = persistedSequenceByProfile[profileId] ?: 0L
                    if (pending.sequence <= lastPersisted) return@synchronized
                    WatchProgressStorage.savePayload(profileId, payload)
                    persistedSequenceByProfile[profileId] = pending.sequence
                    if (pendingPersists[profileId]?.sequence == pending.sequence) {
                        pendingPersists.remove(profileId)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.e(e) { "Failed to persist watch progress for profile $profileId" }
            } finally {
                synchronized(persistLock) {
                    activePersistJobs.remove(persistJob)
                }
            }
        }
        synchronized(persistLock) {
            activePersistJobs.add(persistJob)
        }
        persistJob.start()

    }

    private fun recordSuccessfulPush(
        profileId: Int,
        operationGeneration: Long?,
        entries: Collection<WatchProgressEntry>,
    ) {
        if (profileId != currentProfileId) {
            acknowledgeStoredProfilePush(profileId = profileId, pushedEntries = entries)
            return
        }
        if (operationGeneration != profileGeneration) return
        val dirtyChanged = acknowledgeCurrentProfilePush(entries)
        val latestPushed = entries
            .asSequence()
            .map { entry -> entry.lastUpdatedEpochMs }
            .maxOrNull()
            ?: 0L
        val watermarkChanged = latestPushed > lastSuccessfulPushEpochMs
        if (watermarkChanged) {
            lastSuccessfulPushEpochMs = latestPushed
        }
        if (dirtyChanged || watermarkChanged) persist()
    }

    private fun acknowledgeCurrentProfilePush(entries: Collection<WatchProgressEntry>): Boolean =
        synchronized(entriesLock) {
            var changed = false
            entries.forEach { pushed ->
                val key = pushed.resolvedProgressKey()
                val current = entriesByProgressKey[key]
                if (
                    (current == null || current.lastUpdatedEpochMs <= pushed.lastUpdatedEpochMs) &&
                    dirtyProgressKeys.remove(key)
                ) {
                    changed = true
                }
            }
            changed
        }

    private fun acknowledgeStoredProfilePush(
        profileId: Int,
        pushedEntries: Collection<WatchProgressEntry>,
    ) {
        val payload = WatchProgressStorage.loadPayload(profileId).orEmpty().trim()
        if (payload.isEmpty()) return
        val storedPayload = WatchProgressCodec.decodePayload(payload)
        val storedByKey = storedPayload.entries.newestByProgressKey()
        val acknowledgedKeys = pushedEntries.mapNotNullTo(mutableSetOf()) { pushed ->
            val key = pushed.resolvedProgressKey()
            val current = storedByKey[key]
            key.takeIf { current == null || current.lastUpdatedEpochMs <= pushed.lastUpdatedEpochMs }
        }
        val remainingDirtyKeys = storedPayload.dirtyProgressKeys - acknowledgedKeys
        val latestPushed = pushedEntries.maxOfOrNull(WatchProgressEntry::lastUpdatedEpochMs) ?: 0L
        if (
            remainingDirtyKeys == storedPayload.dirtyProgressKeys &&
            latestPushed <= storedPayload.lastSuccessfulPushEpochMs
        ) {
            return
        }
        WatchProgressStorage.savePayload(
            profileId,
            WatchProgressCodec.encodePayload(
                entries = storedPayload.entries,
                lastSuccessfulPushEpochMs = maxOf(storedPayload.lastSuccessfulPushEpochMs, latestPushed),
                deltaCursorEventId = storedPayload.deltaCursorEventId,
                deltaInitialized = storedPayload.deltaInitialized,
                dirtyProgressKeys = remainingDirtyKeys,
                locallyFinishedEpisodeGroups = storedPayload.locallyFinishedEpisodeGroups,
            ),
        )
    }

    private fun accountScopeSnapshot(): CoroutineScope = synchronized(accountScopeLock) {
        accountScope
    }

    private fun updateActiveSource(source: WatchProgressSource) {
        activeSource = source
        _activeSourceState.value = source
    }

    private fun removeStoredLocalEntries(entries: Collection<WatchProgressEntry>): List<WatchProgressEntry> =
        synchronized(entriesLock) {
            val targetKeys = entries.mapTo(mutableSetOf()) { entry -> entry.resolvedProgressKey() }
            val keysToRemove = entriesByProgressKey
                .filterValues { localEntry ->
                    localEntry.resolvedProgressKey() in targetKeys || entries.any { target ->
                        localEntry.parentMetaId == target.parentMetaId &&
                            localEntry.seasonNumber == target.seasonNumber &&
                            localEntry.episodeNumber == target.episodeNumber
                    }
                }
                .keys
                .toList()
            dirtyProgressKeys.removeAll(keysToRemove.toSet())
            keysToRemove.mapNotNull(entriesByProgressKey::remove)
        }

    private fun currentEntries(): List<WatchProgressEntry> {
        val providerEntries = activeProgressProvider()
            ?.snapshot()
            ?.entries
            .orEmpty()
        val projectedEntries = projectWatchProgressSourceEntries(
            source = activeSource,
            nuvioEntries = localEntriesSnapshot(),
            providerEntries = providerEntries,
        )
        return if (activeSource.providerId == null) {
            projectedEntries
        } else {
            providerMetadataOverlay.project(source = activeSource, entries = projectedEntries)
        }
    }

    private fun localEntriesSnapshot(): List<WatchProgressEntry> =
        synchronized(entriesLock) {
            entriesByProgressKey.values.toList()
        }

    private fun localEntry(progressKey: String): WatchProgressEntry? =
        synchronized(entriesLock) {
            entriesByProgressKey[progressKey]
        }

    private fun localEntryCount(): Int =
        synchronized(entriesLock) {
            entriesByProgressKey.size
        }

    private fun clearLocalEntries() {
        synchronized(entriesLock) {
            entriesByProgressKey.clear()
            dirtyProgressKeys.clear()
            locallyFinishedEpisodeGroups.clear()
        }
    }

    private fun locallyFinishedEpisodeGroupsSnapshot(): List<Set<String>> =
        synchronized(entriesLock) {
            locallyFinishedEpisodeGroups.map { it.toSet() }
        }

    private fun replaceLocallyFinishedEpisodeGroups(groups: Collection<Set<String>>) {
        val normalized = normalizeLocallyFinishedEpisodeGroups(groups)
        synchronized(entriesLock) {
            locallyFinishedEpisodeGroups = normalized.toMutableList()
        }
    }

    private fun locallyFinishedKeysForCompletion(
        entry: WatchProgressEntry,
        effectiveParentMetaId: String,
        sessionParentMetaId: String,
    ): Set<String> {
        if (!entry.normalizedCompletion().isCompleted) return emptySet()
        val season = entry.seasonNumber ?: return emptySet()
        val episode = entry.episodeNumber ?: return emptySet()
        return buildSet {
            if (entry.parentMetaId.isNotBlank()) {
                add(localFinishedEpisodeKey(entry.parentMetaId, season, episode))
            }
            if (effectiveParentMetaId.isNotBlank()) {
                add(localFinishedEpisodeKey(effectiveParentMetaId, season, episode))
            }
            if (sessionParentMetaId.isNotBlank()) {
                add(localFinishedEpisodeKey(sessionParentMetaId, season, episode))
            }
        }
    }

    private fun markLocallyFinishedEpisodeGroup(keys: Set<String>) {
        if (keys.isEmpty()) return
        synchronized(entriesLock) {
            locallyFinishedEpisodeGroups = mergeLocallyFinishedEpisodeGroup(
                existingGroups = locallyFinishedEpisodeGroups,
                newGroup = keys,
            ).toMutableList()
        }
    }

    internal fun normalizedParentContentId(parentContentId: String, videoId: String?): String? =
        activeProgressProvider()
            ?.normalizeParentContentId(parentContentId, videoId)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun seedParentIdsFor(
        parentMetaId: String,
        videoId: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
    ): Set<String> {
        val normalizedParent = parentMetaId.trim()
        if (normalizedParent.isEmpty()) return emptySet()
        return buildSet {
            add(normalizedParent)
            activeProgressProvider()
                ?.normalizeParentContentId(normalizedParent, videoId)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(::add)
            if (!videoId.isNullOrBlank()) {
                parseEpisodeFromPlaybackVideoId(videoId, parentMetaId = null)?.let { (vidParent, vidSeason, vidEpisode) ->
                    if ((seasonNumber == null || vidSeason == seasonNumber) &&
                        (episodeNumber == null || vidEpisode == episodeNumber)
                    ) {
                        add(vidParent)
                    }
                }
            }
        }
    }

    internal fun resolveClearProgressAliases(
        videoIds: Collection<String>,
        parentMetaId: String?,
    ): Set<LocallyFinishedEpisodeAlias> {
        ensureLoaded()
        val seedKeys = buildClearProgressSeedKeys(
            removedEntries = emptyList(),
            videoIds = videoIds,
            parentMetaId = parentMetaId,
        )
        if (seedKeys.isEmpty()) return emptySet()
        return synchronized(entriesLock) {
            locallyFinishedEpisodeGroups
                .filter { group -> group.any { it in seedKeys } }
                .flatMapTo(linkedSetOf()) { group ->
                    group.mapNotNull(::parseLocalFinishedEpisodeKey)
                }
        }
    }

    private fun buildClearProgressSeedKeys(
        removedEntries: Collection<WatchProgressEntry>,
        videoIds: Collection<String>,
        parentMetaId: String?,
        additionalAliases: Set<LocallyFinishedEpisodeAlias> = emptySet(),
    ): Set<String> = buildSet {
        additionalAliases.forEach { alias ->
            if (alias.parentMetaId.isNotBlank()) {
                add(localFinishedEpisodeKey(alias.parentMetaId, alias.seasonNumber, alias.episodeNumber))
            }
        }
        removedEntries.forEach { entry ->
            val season = entry.seasonNumber ?: return@forEach
            val episode = entry.episodeNumber ?: return@forEach
            if (entry.parentMetaId.isNotBlank()) {
                seedParentIdsFor(entry.parentMetaId, entry.videoId, season, episode).forEach { parent ->
                    add(localFinishedEpisodeKey(parent, season, episode))
                }
            }
        }
        videoIds.forEach { videoId ->
            parseEpisodeFromPlaybackVideoId(videoId, parentMetaId)?.let { (parent, season, episode) ->
                seedParentIdsFor(parent, videoId, season, episode).forEach { seedParent ->
                    add(localFinishedEpisodeKey(seedParent, season, episode))
                }
            }
            parseEpisodeFromPlaybackVideoId(videoId, parentMetaId = null)?.let { (parent, season, episode) ->
                seedParentIdsFor(parent, videoId, season, episode).forEach { seedParent ->
                    add(localFinishedEpisodeKey(seedParent, season, episode))
                }
            }
        }
    }

    private fun removeLocallyFinishedEpisodes(
        parentMetaIds: Collection<String>,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ): ClearedLocallyFinishedResult {
        val normalizedParents = parentMetaIds
            .map { it.trim() }
            .filterTo(linkedSetOf()) { it.isNotEmpty() }
        if (normalizedParents.isEmpty()) {
            return ClearedLocallyFinishedResult(changed = false, aliases = emptySet())
        }
        val seedKeys = if (seasonNumber != null && episodeNumber != null) {
            normalizedParents.mapTo(mutableSetOf()) { parent ->
                localFinishedEpisodeKey(parent, seasonNumber, episodeNumber)
            }
        } else {
            emptySet()
        }
        return synchronized(entriesLock) {
            val removedKeys = linkedSetOf<String>()
            val remaining = mutableListOf<Set<String>>()
            var changed = false
            locallyFinishedEpisodeGroups.forEach { group ->
                val matches = if (seasonNumber != null && episodeNumber != null) {
                    group.any { it in seedKeys }
                } else {
                    group.any { key ->
                        parseLocalFinishedEpisodeKey(key)?.parentMetaId in normalizedParents
                    }
                }
                if (matches) {
                    changed = true
                    removedKeys += group
                } else {
                    remaining += group
                }
            }
            if (changed) {
                locallyFinishedEpisodeGroups = remaining
            }
            ClearedLocallyFinishedResult(
                changed = changed,
                aliases = removedKeys.mapNotNullTo(linkedSetOf(), ::parseLocalFinishedEpisodeKey),
            )
        }
    }

    private fun clearLocallyFinishedForClearedProgress(
        removedEntries: Collection<WatchProgressEntry>,
        videoIds: Collection<String>,
        parentMetaId: String?,
        additionalAliases: Set<LocallyFinishedEpisodeAlias> = emptySet(),
    ): ClearedLocallyFinishedResult {
        val seedKeys = buildClearProgressSeedKeys(
            removedEntries = removedEntries,
            videoIds = videoIds,
            parentMetaId = parentMetaId,
            additionalAliases = additionalAliases,
        )
        if (seedKeys.isEmpty()) {
            return ClearedLocallyFinishedResult(changed = false, aliases = emptySet())
        }
        return synchronized(entriesLock) {
            val removedKeys = linkedSetOf<String>()
            val remaining = mutableListOf<Set<String>>()
            var changed = false
            locallyFinishedEpisodeGroups.forEach { group ->
                if (group.any { it in seedKeys }) {
                    changed = true
                    removedKeys += group
                } else {
                    remaining += group
                }
            }
            if (changed) {
                locallyFinishedEpisodeGroups = remaining
            }
            ClearedLocallyFinishedResult(
                changed = changed,
                aliases = removedKeys.mapNotNullTo(linkedSetOf(), ::parseLocalFinishedEpisodeKey),
            )
        }
    }

    private fun cancelPendingRemovalsForClearedProgress(
        removedEntries: Collection<WatchProgressEntry>,
        videoIds: Collection<String>,
        parentMetaId: String?,
        clearedAliases: Set<LocallyFinishedEpisodeAlias>,
    ) {
        val aliasParentsByEpisode = clearedAliases.groupBy(
            keySelector = { it.seasonNumber to it.episodeNumber },
            valueTransform = { it.parentMetaId },
        )
        removedEntries.forEach { entry ->
            val episodeParents = if (entry.seasonNumber != null && entry.episodeNumber != null) {
                aliasParentsByEpisode[entry.seasonNumber to entry.episodeNumber].orEmpty()
            } else {
                emptyList()
            }
            val parents = seedParentIdsFor(
                parentMetaId = entry.parentMetaId,
                videoId = entry.videoId,
                seasonNumber = entry.seasonNumber,
                episodeNumber = entry.episodeNumber,
            ) + episodeParents
            PlaybackClearEpochs.recordClear(
                profileId = currentProfileId,
                parentMetaIds = parents,
                seasonNumber = entry.seasonNumber,
                episodeNumber = entry.episodeNumber,
                videoId = entry.videoId,
            )
            AutoDownloads.cancelPendingRemovals(
                parentMetaIds = parents,
                seasonNumber = entry.seasonNumber,
                episodeNumber = entry.episodeNumber,
                videoId = entry.videoId,
            )
        }
        videoIds.forEach { videoId ->
            val parsed = parseEpisodeFromPlaybackVideoId(videoId, parentMetaId)
            val episodeParents = if (parsed != null) {
                aliasParentsByEpisode[parsed.second to parsed.third].orEmpty()
            } else {
                emptyList()
            }
            val parents = buildSet {
                val baseParent = parsed?.first ?: parentMetaId.orEmpty()
                addAll(seedParentIdsFor(baseParent, videoId, parsed?.second, parsed?.third))
                parseEpisodeFromPlaybackVideoId(videoId, parentMetaId = null)?.first?.let(::add)
                addAll(episodeParents)
            }
            PlaybackClearEpochs.recordClear(
                profileId = currentProfileId,
                parentMetaIds = parents,
                seasonNumber = parsed?.second,
                episodeNumber = parsed?.third,
                videoId = videoId,
            )
            AutoDownloads.cancelPendingRemovals(
                parentMetaIds = parents,
                seasonNumber = parsed?.second,
                episodeNumber = parsed?.third,
                videoId = videoId,
            )
        }
        aliasParentsByEpisode.forEach { (seasonEpisode, parents) ->
            PlaybackClearEpochs.recordClear(
                profileId = currentProfileId,
                parentMetaIds = parents,
                seasonNumber = seasonEpisode.first,
                episodeNumber = seasonEpisode.second,
            )
            AutoDownloads.cancelPendingRemovals(
                parentMetaIds = parents,
                seasonNumber = seasonEpisode.first,
                episodeNumber = seasonEpisode.second,
            )
        }
    }

    private fun cancelPendingRemovalsForAliases(
        parentMetaIds: Collection<String>,
        seasonNumber: Int?,
        episodeNumber: Int?,
        clearedAliases: Set<LocallyFinishedEpisodeAlias>,
    ) {
        PlaybackClearEpochs.recordClear(
            profileId = currentProfileId,
            parentMetaIds = parentMetaIds,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )
        AutoDownloads.cancelPendingRemovals(
            parentMetaIds = parentMetaIds,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )
        clearedAliases
            .groupBy(
                keySelector = { it.seasonNumber to it.episodeNumber },
                valueTransform = { it.parentMetaId },
            )
            .forEach { (seasonEpisode, parents) ->
                if (seasonEpisode.first != seasonNumber || seasonEpisode.second != episodeNumber) {
                    PlaybackClearEpochs.recordClear(
                        profileId = currentProfileId,
                        parentMetaIds = parents,
                        seasonNumber = seasonEpisode.first,
                        episodeNumber = seasonEpisode.second,
                    )
                    AutoDownloads.cancelPendingRemovals(
                        parentMetaIds = parents,
                        seasonNumber = seasonEpisode.first,
                        episodeNumber = seasonEpisode.second,
                    )
                }
            }
    }

    private fun dirtyProgressKeysSnapshot(): Set<String> =
        synchronized(entriesLock) {
            dirtyProgressKeys.toSet()
        }

    private fun replaceDirtyProgressKeys(keys: Collection<String>) {
        synchronized(entriesLock) {
            dirtyProgressKeys = keys
                .filterTo(mutableSetOf()) { key -> key in entriesByProgressKey }
        }
    }

    private fun markProgressDirty(entry: WatchProgressEntry) {
        synchronized(entriesLock) {
            dirtyProgressKeys += entry.resolvedProgressKey()
        }
    }

    private fun clearProgressDirty(progressKey: String) {
        synchronized(entriesLock) {
            dirtyProgressKeys -= progressKey
        }
    }

    private fun migrateDirtyProgressKeys(migrations: Map<String, String>) {
        if (migrations.isEmpty()) return
        synchronized(entriesLock) {
            migrations.forEach { (oldKey, newKey) ->
                if (dirtyProgressKeys.remove(oldKey)) {
                    dirtyProgressKeys += newKey
                }
            }
        }
    }

    private fun acknowledgeDirtyProgressFromSnapshot(
        serverEntries: Collection<ProgressSyncRecord>,
        localEntriesBeforeApply: Collection<WatchProgressEntry>,
        dirtyKeysBeforeApply: Set<String>,
    ) {
        if (dirtyKeysBeforeApply.isEmpty()) return
        val localByKey = localEntriesBeforeApply.newestByProgressKey()
        val remoteByKey = linkedMapOf<String, WatchProgressEntry>()
        serverEntries.forEach { record ->
            val key = record.resolvedProgressKey()
            val candidate = record.toWatchProgressEntry(cached = localByKey[key])
            val existing = remoteByKey[key]
            if (existing == null || candidate.isFresherThan(existing)) {
                remoteByKey[key] = candidate
            }
        }
        synchronized(entriesLock) {
            dirtyKeysBeforeApply.forEach { key ->
                val local = localByKey[key]
                val remote = remoteByKey[key]
                if (remote != null && (local == null || !local.isFresherThan(remote))) {
                    dirtyProgressKeys -= key
                }
            }
        }
    }

    private fun replaceLocalEntries(entries: Collection<WatchProgressEntry>) {
        synchronized(entriesLock) {
            entriesByProgressKey = entries.newestByProgressKey().toMutableMap()
        }
    }

    private fun replaceLocalEntries(entries: Map<String, WatchProgressEntry>) {
        synchronized(entriesLock) {
            entriesByProgressKey = entries.values.newestByProgressKey().toMutableMap()
        }
    }

    private fun upsertLocalEntry(entry: WatchProgressEntry) {
        synchronized(entriesLock) {
            val resolvedEntry = entry.withResolvedProgressKey()
            entriesByProgressKey[resolvedEntry.resolvedProgressKey()] = resolvedEntry
        }
    }

    private fun removeLocalEntry(progressKey: String): WatchProgressEntry? =
        synchronized(entriesLock) {
            dirtyProgressKeys -= progressKey
            entriesByProgressKey.remove(progressKey)
        }

    private fun removeLocalEntriesMatching(
        predicate: (WatchProgressEntry) -> Boolean,
    ): List<WatchProgressEntry> =
        synchronized(entriesLock) {
            val keysToRemove = entriesByProgressKey
                .filterValues(predicate)
                .keys
                .toList()
            dirtyProgressKeys.removeAll(keysToRemove.toSet())
            keysToRemove.mapNotNull(entriesByProgressKey::remove)
        }

    fun isDroppedShow(contentId: String): Boolean {
        return activeProgressProvider()?.isHiddenFromProgress(contentId) == true
    }

    fun activeProviderOwnsCompletedHistoryProjection(): Boolean =
        activeProgressProvider()?.ownsCompletedHistoryProjection == true

    fun activeProviderContinueWatchingCutoffEpochMs(
        daysCap: Int,
        nowEpochMs: Long,
    ): Long? = activeProgressProvider()?.continueWatchingCutoffEpochMs(daysCap, nowEpochMs)

    fun shouldUseAsNextUpSeed(entry: WatchProgressEntry, nowEpochMs: Long): Boolean =
        activeProgressProvider()?.shouldUseAsNextUpSeed(entry, nowEpochMs)
            ?: entry.shouldUseAsCompletedSeedForContinueWatching()

    suspend fun prepareNextUpProgressEntries(
        entries: List<WatchProgressEntry>,
        contentId: String,
    ): List<WatchProgressEntry> = activeProgressProvider()
        ?.prepareNextUpProgressEntries(entries, contentId)
        ?: entries

    private fun AddonsUiState.metadataProviderReadiness(): MetadataProviderReadiness {
        val enabled = addons.enabledAddons()
        val providers = enabled
            .mapNotNull { addon -> addon.manifest }
            .filter { manifest -> manifest.hasMetaResource() }
        return MetadataProviderReadiness(
            providers = providers,
        )
    }

    private fun AddonManifest.hasMetaResource(): Boolean =
        resources.any { resource -> resource.name == "meta" }

}

internal fun projectWatchProgressUiState(
    source: WatchProgressSource,
    entries: List<WatchProgressEntry>,
    providerSnapshot: TrackingProgressSnapshot?,
    hasLoadedNuvioRemoteProgress: Boolean,
): WatchProgressUiState = WatchProgressUiState(
    source = source,
    entries = entries,
    hiddenContentIds = providerSnapshot?.hiddenContentIds.orEmpty(),
    hasLoadedRemoteProgress =
        providerSnapshot?.hasLoadedRemoteProgress ?: hasLoadedNuvioRemoteProgress,
)
