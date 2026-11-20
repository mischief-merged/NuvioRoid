package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.SubtitleRequestExtras
import com.nuvio.app.features.player.looksLikePlaceholder
import com.nuvio.app.features.player.addonSubtitleRequests
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.sourceLabelSaysNotCached
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.concurrent.Volatile
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

object DownloadsRepository {
    private const val MaxDownloadAttempts = 3

    /** How often a delete looks again when the file moved while it waited for the per-video lock. */
    private const val LockAttempts = 3

    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    private val _hasUnseenCompleted = MutableStateFlow(false)
    val hasUnseenCompleted: StateFlow<Boolean> = _hasUnseenCompleted.asStateFlow()

    private val _offlineIndex = MutableStateFlow(OfflineDownloadIndex.Empty)
    internal val offlineIndex: StateFlow<OfflineDownloadIndex> = _offlineIndex.asStateFlow()

    private val indexLock = SynchronizedObject()
    private val indexScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal var indexDispatcher: CoroutineDispatcher = Dispatchers.IO
    private var indexJob: Job? = null
    private var indexGeneration: Long = 0L
    private var lastCompletedIndexKey: List<CompletedIndexKey>? = null

    private data class CompletedIndexKey(
        val id: String,
        val localFileUri: String?,
        val fileName: String,
        val parentMetaId: String,
        val videoId: String,
        val seasonNumber: Int?,
        val episodeNumber: Int?,
        val updatedAtEpochMs: Long,
        val locationId: String?,
    )

    // The UI, downloader callbacks and background workers reach this object from different threads.
    // Every read-modify-write of the item list, and every use of activeTransfers, holds this lock.
    //
    // LOCK ORDER, everywhere: the per-video lock (SidecarLocks) FIRST, then loadLock (only in
    // ensureLoaded / onProfileChanged), then this state lock. Code that holds stateLock never
    // acquires loadLock or calls ensureLoaded(), and never waits for a per-video lock: the guard
    // behind every sidecar write (videoFileIsThere) reads the disk through the backend only. Both
    // delete paths therefore take the per-video lock before entering here.
    private val loadLock = SynchronizedObject()
    private val stateLock = SynchronizedObject()

    // The transfer that currently owns each download. A transfer that was paused, replaced or removed
    // is no longer in this map, so whatever it reports afterwards is ignored.
    private val activeTransfers = mutableMapOf<String, ActiveTransfer>()
    @Volatile
    private var finishingSteps = DownloadFinishingSteps()

    /** Stops new publications and gives work already publishing or verifying a bounded time to save. */
    internal suspend fun awaitFinishingDownloads(timeoutMs: Long = 10_000L): Boolean =
        finishingSteps.awaitForQuit(timeoutMs)

    internal val rateTracker = DownloadRateTracker()
    @kotlin.concurrent.Volatile
    private var hasLoaded = false

    // Where items are persisted and transfers and files are handled. Always the platform in the app.
    internal var backend: DownloadsBackend = PlatformDownloadsBackend

    // Re-resolves the same source for a fresh link when a download URL expires or returns 403/404/410.
    internal var linkRefresher: DownloadSourceRefresher = DownloadSourceRefresher()

    // Saves what a download that finished needs beside it (subtitles, skip times). Always the app's in the app.
    internal var companions: () -> DownloadCompanions = { DownloadCompanions.app }

    // What limits automatic downloads. Always the saved setting in the app.
    internal var autoDownloadLimits: AutoDownloadLimits = SavedAutoDownloadLimits

    // Whether downloads wait because the connection is limited. Always the app's in the app.
    internal var connectionHold: ConnectionHold = LimitedConnections.hold
        set(value) {
            field = value
            value.listen(::applyConnectionHold)
        }

    init {
        connectionHold.listen(::applyConnectionHold)
    }

    private var nextDownloadOrdinal = 0L

    // What the stored payload held that this build does not use: fields of an item it does not
    // know, by download id, and items it could not read at all. Both are written back unchanged,
    // so a payload written by another build of the app loses nothing by being loaded here.
    private var loadedPayload: DownloadsCodec.Decoded? = null

    // True when a stored payload could neither be read nor be put aside. Nothing is saved then, so
    // that payload is still there for a later launch.
    private var savingIsUnsafe = false

    // The downloads that were started again when the list was loaded, until someone asks for them.
    private var downloadsResumedAtLoad: Set<String> = emptySet()

    // The profile whose list is in memory. Null until a list was loaded.
    @kotlin.concurrent.Volatile
    private var loadedProfileId: Int? = null

    // The list as it was last read from or written to storage, and its revision there. Another copy of
    // the app may save in between; a save then merges this copy's own changes onto what is stored.
    private var baseline: List<DownloadItem> = emptyList()
    private var baseRevision: Long = 0L

    /** Runs [block] inside the state lock. Tests only: proves what must not need it. */
    internal fun holdStateLockForTest(block: () -> Unit) = synchronized(stateLock) { block() }

    fun ensureLoaded() {
        if (hasLoaded) return
        synchronized(loadLock) {
            if (hasLoaded) return
            loadFromDisk()
        }
    }

    internal fun selectProfileForDownloads(select: () -> Unit) = synchronized(stateLock) { select() }

    fun onProfileChanged() {
        synchronized(loadLock) {
            loadFromDisk()
        }
    }

    fun markCompletedSeen() {
        _hasUnseenCompleted.value = false
    }

    fun clearLocalState(): Unit = synchronized(stateLock) {
        val transfers = activeTransfers.values.toList()
        activeTransfers.clear()
        finishingSteps = DownloadFinishingSteps()
        rateTracker.clearAll()
        transfers.forEach(ActiveTransfer::cancel)
        hasLoaded = false
        loadedProfileId = null
        baseline = emptyList()
        baseRevision = 0L
        _hasUnseenCompleted.value = false
        _uiState.value = DownloadsUiState()
        resetOfflineIndex()
        notifyLiveStatusPlatform()
    }

    internal fun ensureOfflineIndexStarted() {
        if (!hasLoaded) {
            synchronized(indexLock) {
                if (indexJob?.isActive == true) return
                val generation = ++indexGeneration
                indexJob = indexScope.launch(indexDispatcher) {
                    ensureLoaded()
                    val snapshot = _uiState.value.items
                    val key = completedIndexKey(snapshot)
                    val shouldBuild = synchronized(indexLock) {
                        if (indexGeneration != generation) return@launch
                        lastCompletedIndexKey = key
                        key.isNotEmpty()
                    }
                    val built = if (shouldBuild) {
                        OfflineDownloadIndex(snapshot, ::resolveLocalUriWithoutMutation)
                    } else {
                        OfflineDownloadIndex.Empty
                    }
                    synchronized(indexLock) {
                        if (indexGeneration == generation) {
                            _offlineIndex.value = built
                        }
                    }
                }
            }
            return
        }
        scheduleOfflineIndexRefresh(_uiState.value.items)
    }

    internal fun refreshOfflineIndex() {
        if (!hasLoaded) {
            ensureOfflineIndexStarted()
            return
        }
        scheduleOfflineIndexRefresh(_uiState.value.items, force = true)
    }

    private fun resetOfflineIndex() {
        synchronized(indexLock) {
            indexGeneration++
            indexJob?.cancel()
            indexJob = null
            lastCompletedIndexKey = null
            _offlineIndex.value = OfflineDownloadIndex.Empty
        }
    }

    private fun completedIndexKey(items: List<DownloadItem>): List<CompletedIndexKey> =
        items.mapNotNull { item ->
            if (!item.isVerifiedCompletedStatus()) return@mapNotNull null
            CompletedIndexKey(
                id = item.id,
                localFileUri = item.localFileUri,
                fileName = item.fileName,
                parentMetaId = item.parentMetaId,
                videoId = item.videoId,
                seasonNumber = item.seasonNumber,
                episodeNumber = item.episodeNumber,
                updatedAtEpochMs = item.updatedAtEpochMs,
                locationId = item.locationId,
            )
        }

    private fun scheduleOfflineIndexRefresh(items: List<DownloadItem>, force: Boolean = false) {
        val key = completedIndexKey(items)
        val generation = synchronized(indexLock) {
            if (!force && key == lastCompletedIndexKey) return
            lastCompletedIndexKey = key
            indexJob?.cancel()
            if (key.isEmpty()) {
                indexGeneration++
                indexJob = null
                _offlineIndex.value = OfflineDownloadIndex.Empty
                return
            }
            ++indexGeneration
        }
        val candidates = items.filter { it.isVerifiedCompletedStatus() }
        val job = indexScope.launch(indexDispatcher) {
            val built = OfflineDownloadIndex(candidates, ::resolveLocalUriWithoutMutation)
            synchronized(indexLock) {
                if (indexGeneration == generation) {
                    _offlineIndex.value = built
                }
            }
        }
        synchronized(indexLock) {
            if (indexGeneration == generation) {
                indexJob = job
            } else {
                job.cancel()
            }
        }
    }

    fun findPlayableDownloadByVideoId(videoId: String?): DownloadItem? {
        ensureLoaded()
        val normalizedVideoId = videoId?.trim().orEmpty()
        if (normalizedVideoId.isBlank()) return null
        return _uiState.value.items.firstOrNull { item ->
            item.videoId.trim() == normalizedVideoId && item.hasPlayableLocalFile()
        }
    }

    fun findPlayableDownload(
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String? = null,
    ): DownloadItem? {
        ensureLoaded()
        val normalizedParent = parentMetaId.trim()
        val normalizedVideo = videoId?.trim().orEmpty()
        val videoMatchesParent = normalizedVideo == normalizedParent && seasonNumber != null && episodeNumber != null
        val candidates = _uiState.value.items.filter { item ->
            if (!item.isVerifiedCompletedStatus()) return@filter false
            val matchesVideo = normalizedVideo.isNotBlank() &&
                !videoMatchesParent &&
                item.videoId.trim() == normalizedVideo
            val matchesCoordinates = normalizedParent.isNotBlank() &&
                item.parentMetaId.trim() == normalizedParent &&
                if (seasonNumber != null && episodeNumber != null) {
                    item.seasonNumber == seasonNumber && item.episodeNumber == episodeNumber
                } else {
                    item.seasonNumber == null && item.episodeNumber == null
                }
            matchesVideo || matchesCoordinates
        }
        if (candidates.isEmpty()) return null
        return OfflineDownloadIndex(candidates, ::resolveLocalUriWithoutMutation)
            .findFor(
                parentMetaId = parentMetaId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                videoId = videoId,
            )
    }

    fun hasPlayableDownloadForMeta(parentMetaId: String): Boolean {
        ensureLoaded()
        val normalized = parentMetaId.trim()
        if (normalized.isBlank()) return false
        val candidates = _uiState.value.items.filter { item ->
            item.isVerifiedCompletedStatus() &&
                (item.parentMetaId.trim() == normalized || item.videoId.trim() == normalized)
        }
        if (candidates.isEmpty()) return false
        return OfflineDownloadIndex(candidates, ::resolveLocalUriWithoutMutation)
            .hasMeta(normalized)
    }

    internal fun resolveLocalUriWithoutMutation(item: DownloadItem): String? {
        if (!item.isVerifiedCompletedStatus()) return null
        return (backend.locate(item) as? FileLookup.Found)?.uri
    }

    /** The finished download whose file is [localFileUri], in either spelling of a file address. */
    internal fun findCompletedDownloadByLocalFile(localFileUri: String): DownloadItem? {
        ensureLoaded()
        val wanted = localFileUri.localFilePath().takeIf { it.isNotBlank() } ?: return null
        return _uiState.value.items.firstOrNull { item ->
            item.status == DownloadStatus.Completed && (
                item.localFileUri?.localFilePath() == wanted ||
                    backend.resolveLocalFileUri(item.localFileUri, item.fileName)?.localFilePath() == wanted
                )
        }
    }

    /**
     * Whether the file at [localFileUri] physically exists, at exactly that address. Reads the disk
     * through the backend and takes no lock of this repository, so it is safe to call while holding a
     * per-video lock (see the lock order at [stateLock]).
     */
    /**
     * Whether a download must be left alone by background passes and sidecar writes: a move or a delete
     * holds it, or its location cannot be used. Reads the published list only: no lock is taken.
     */
    internal fun isBusyOrAway(item: DownloadItem): Boolean = backend.heldByFolder(item)

    /** [isBusyOrAway] for the finished download whose file is [localFileUri]; false when there is none. */
    internal fun isBusyOrAway(localFileUri: String): Boolean {
        val wanted = localFileUri.localFilePath()
        return _uiState.value.items.any { item ->
            item.status == DownloadStatus.Completed && item.localFileUri?.localFilePath() == wanted && backend.heldByFolder(item)
        }
    }

    internal fun videoFileIsThere(localFileUri: String): Boolean {
        val resolved = backend.resolveLocalFileUri(localFileUri, "") ?: return false
        return resolved.localFilePath() == localFileUri.localFilePath()
    }

    /** The address of a finished download's file now, from the published list: no lock is taken. */
    private fun finishedFileUriOf(downloadId: String): String? {
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return null
        return if (item.status == DownloadStatus.Completed) backend.resolveLocalFileUri(item.localFileUri, item.fileName) else null
    }

    fun playableLocalFileUri(item: DownloadItem): String? {
        ensureLoaded()
        if (!item.isVerifiedCompletedStatus()) return null
        val resolvedUri = (backend.locate(item) as? FileLookup.Found)?.uri ?: return null

        if (resolvedUri != item.localFileUri) {
            mutateItem(item.id) { current ->
                if (current.fileName == item.fileName) {
                    current.copy(
                        localFileUri = resolvedUri,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                } else {
                    current
                }
            }
        }

        return resolvedUri
    }

    fun enqueueFromStream(
        contentType: String,
        videoId: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeTitle: String?,
        episodeThumbnail: String?,
        stream: StreamItem,
        autoDownloadRuleKey: String? = null,
        /** The user picked this source for a title that has a finished download: it replaces that download's file. */
        userChoseReplacement: Boolean = false,
        tripFill: Boolean = false,
        expectedProfileId: Int? = null,
        onlyIfAbsent: Boolean = false,
        allowedExistingIds: Set<String> = emptySet(),
    ): DownloadEnqueueResult {
        ensureLoaded()
        return synchronized(stateLock) {
            if (expectedProfileId != null && (loadedProfileId != expectedProfileId ||
                    com.nuvio.app.features.profiles.ProfileRepository.activeProfileId != expectedProfileId)) {
                return DownloadEnqueueResult.MissingUrl
            }

        val sourceUrl = stream.playableDirectUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return DownloadEnqueueResult.MissingUrl

        if (!sourceUrl.isSupportedDownloadUrl()) {
            return DownloadEnqueueResult.UnsupportedFormat
        }
        // Nobody looks at the address of a download that starts by itself, so it may only go to a
        // public host.
        if (autoDownloadRuleKey != null && !sourceUrl.hasPublicDownloadHost()) {
            return DownloadEnqueueResult.UnsupportedFormat
        }

        val now = DownloadsClock.nowEpochMs()
        val logicalKey = buildLogicalKey(
            parentMetaId = parentMetaId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )

        if (onlyIfAbsent && _uiState.value.items.any { item ->
                item.logicalContentKey == logicalKey && !item.isPlaceholder && (
                    item.id !in allowedExistingIds || item.hasTripPartial ||
                        item.status == DownloadStatus.Downloading || item.status == DownloadStatus.Paused
                    )
            }) return DownloadEnqueueResult.MissingUrl

        var replacedExisting = false
        val currentItems = _uiState.value.items.toMutableList()
        val destinationLocationId = backend.placementFor(currentItems, parentMetaId, seasonNumber != null && episodeNumber != null)
        // The sources this title had, and how many the app started by itself, go on with the new download.
        val history = sourceHistoryFor(currentItems.filter { it.logicalContentKey == logicalKey }, stream, userChoseReplacement)
        // What is replaced: a download that has no finished file. A finished file, marked as not the
        // real video or not, is a stored file of the user: it is replaced only when the user chose
        // to ([userChoseReplacement]), and then a marked one goes to the trash. Anything automatic
        // leaves it and its record where they are and fetches the new copy beside it, under a name
        // and a record of its own.
        val replaced = currentItems.filter { item ->
            item.logicalContentKey == logicalKey && (userChoseReplacement || item.status != DownloadStatus.Completed)
        }
        replaced.forEach { existing ->
            replacedExisting = true
            activeTransfers.remove(existing.id)?.cancel()
            rateTracker.clear(existing.id)
            currentItems.removeAll { it.id == existing.id }
        }

        val downloadId = nextDownloadId(now)
        val fileName = buildFileName(
            title = title,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            fallbackTitle = stream.streamLabel,
            sourceUrl = sourceUrl,
            downloadId = downloadId,
        )

        val item = DownloadItem(
            id = downloadId,
            contentType = contentType,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            videoId = videoId,
            title = title,
            logo = logo,
            poster = poster,
            background = background,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            episodeThumbnail = episodeThumbnail,
            streamTitle = stream.streamLabel,
            streamSubtitle = stream.streamSubtitle,
            providerName = stream.addonName,
            providerAddonId = stream.addonId,
            sourceUrl = sourceUrl,
            sourceHeaders = sanitizeDownloadRequestHeaders(stream.behaviorHints.proxyHeaders?.request),
            sourceResponseHeaders = sanitizeDownloadResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
            subtitleRequests = addonSubtitleRequests(
                type = contentType,
                videoId = videoId,
                extras = SubtitleRequestExtras.from(stream.behaviorHints, sourceUrl),
            ),
            sourceSubtitles = stream.externalSubtitles,
            localFileUri = null,
            fileName = fileName,
            // A show stays where its downloaded episodes are; a movie goes to the default location.
            locationId = destinationLocationId,
            status = DownloadStatus.Downloading,
            downloadedBytes = 0L,
            totalBytes = null,
            errorMessage = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            expectedVideoHash = stream.behaviorHints.videoHash.asVideoHash(),
            autoDownloadRuleKey = autoDownloadRuleKey,
            tripFill = tripFill,
            triedSources = history.triedSources,
            autoSourceAttempts = history.autoSourceAttempts,
            sourceInfoHash = (stream.p2pInfoHash ?: stream.clientResolve?.infoHash)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
            sourceFileIdx = stream.p2pFileIdx ?: stream.clientResolve?.fileIdx,
            sourceFilename = stream.behaviorHints.filename ?: stream.clientResolve?.filename ?: stream.clientResolve?.stream?.raw?.filename,
            sourceFileSizeBytes = (stream.behaviorHints.videoSize ?: stream.clientResolve?.stream?.raw?.size ?: stream.debridCacheStatus?.cachedSize)?.takeIf { it > 0L },
            sourceBingeGroup = stream.behaviorHints.bingeGroup?.trim()?.takeIf { it.isNotEmpty() },
            sourceDebridResolved = stream.debridResolved,
            sourceClientResolve = stream.toDownloadClientResolve(),
        )

        // The size the source claims for the selected file, where that is established (see SourceSizeClaim).
        val claimed = usableSizeClaim(stream, item.isEpisode, runtimeMinutesOf(item))
        val queued = item.copy(expectedSizeBytes = claimed)

        currentItems.add(0, queued)
        publish(currentItems)
        // The list without the old record is on disk first; only then does its file go, so a crash or a
        // failed save can leave a file without a record, never a record whose file is in the Trash.
        val savedDurably = persist(durably = replaced.isNotEmpty())
        replaced.forEach { existing ->
            val oldUri = playableLocalFileUri(existing) ?: existing.localFileUri
            // The old file goes to the Trash (a flagged placeholder too, so it can be looked at), and only when
            // no other record of any profile uses it; where that cannot be told it stays where it is.
            val keep = !savedDurably || oldUri != null &&
                backend.ownershipOf(existing, oldUri).let { it == FileOwnership.Owned || it == FileOwnership.Unknown }
            if (!keep) {
                if (existing.isPlaceholder) oldUri?.let(backend::trashFile) else backend.discardFile(oldUri)
                backend.removePartialOf(existing)
            }
        }
        startDownload(queued)

        return if (replacedExisting) {
            DownloadEnqueueResult.Replaced
        } else {
            DownloadEnqueueResult.Started
        }
        }
    }

    internal fun enqueueTripEpisode(
        show: TripShowCandidate,
        episode: SeasonBatchEpisode,
        stream: StreamItem,
        profileId: Int?,
        allowedExistingIds: Set<String>,
    ): String? = synchronized(stateLock) {
        val result = enqueueFromStream(
            show.parentMetaType, episode.videoId, show.parentMetaId, show.parentMetaType, show.title,
            show.logo, show.poster, show.background, episode.seasonNumber, episode.episodeNumber,
            episode.title, episode.thumbnail, stream, tripFill = true, expectedProfileId = profileId,
            onlyIfAbsent = true, allowedExistingIds = allowedExistingIds,
        )
        if (result != DownloadEnqueueResult.Started && result != DownloadEnqueueResult.Replaced) return null
        _uiState.value.items.firstOrNull { it.parentMetaId == show.parentMetaId &&
            it.seasonNumber == episode.seasonNumber && it.episodeNumber == episode.episodeNumber }?.id
    }

    internal fun resumeTripEpisode(item: DownloadItem, profileId: Int?): Unit = synchronized(stateLock) {
        if (loadedProfileId != profileId || com.nuvio.app.features.profiles.ProfileRepository.activeProfileId != profileId) return
        if (_uiState.value.items.any { it.id == item.id && it.hasTripPartial }) {
            mutateItem(item.id) { it.copy(tripFill = true) }
            resumeDownload(item.id)
        }
    }

    internal fun tripDestination(parentMetaId: String): TripDestination {
        val state = DownloadFolders.controller.state.value
        val locationId = backend.placementFor(_uiState.value.items, parentMetaId, true)
        val location = state.location(locationId)
        return TripDestination(location?.volumeKey ?: locationId.orEmpty(), location?.freeBytes)
    }

    /**
     * The app found no working source for the title [titleKey] ([DownloadItem.logicalContentKey]): its
     * downloads with a bad source are marked, so that the app does not look again and the user is
     * offered to pick one. Nothing else about them changes; a file stays where it is.
     */
    internal fun markNoWorkingSourceFound(titleKey: String) {
        ensureLoaded()
        synchronized(stateLock) {
            val items = _uiState.value.items
            if (items.none { it.logicalContentKey == titleKey && it.hasBadSource && !it.noWorkingSourceFound }) return
            publish(
                items.map { item ->
                    if (item.logicalContentKey == titleKey && item.hasBadSource) item.copy(noWorkingSourceFound = true) else item
                },
            )
            persist()
        }
    }

    fun pauseDownload(downloadId: String) {
        ensureLoaded()
        synchronized(stateLock) {
            val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
            // One that waits for a better connection becomes paused by the user: it stays paused when that ends.
            if (item.status == DownloadStatus.Paused && item.pausedByConnection) {
                mutateItem(downloadId) { it.copy(pausedByConnection = false, updatedAtEpochMs = DownloadsClock.nowEpochMs()) }
                return
            }
            if (item.status != DownloadStatus.Downloading) return

            activeTransfers.remove(downloadId)?.cancel()
            rateTracker.clear(downloadId)
            mutateItem(downloadId) { current ->
                current.copy(
                    status = DownloadStatus.Paused,
                    pausedByFolder = false,
                    pausedByConnection = false,
                    updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    errorMessage = null,
                )
            }
        }
    }

    fun pauseActiveDownloads() {
        ensureLoaded()
        _uiState.value.items
            .filter { it.status == DownloadStatus.Downloading }
            .map { it.id }
            .forEach(::pauseDownload)
    }

    /**
     * Pauses a running download and waits until its transfer has stopped writing, so that its file can
     * be looked at or moved. False when it did not stop in time. Never holds the state lock while waiting.
     */
    internal fun pauseAndWait(downloadId: String, timeoutMs: Long = 30_000L): Boolean {
        ensureLoaded()
        val transfer = synchronized(stateLock) {
            val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return true
            if (item.status != DownloadStatus.Downloading) return true
            rateTracker.clear(downloadId)
            activeTransfers.remove(downloadId).also {
                mutateItem(downloadId) { current ->
                    current.copy(
                        status = DownloadStatus.Paused,
                        pausedByFolder = false,
                        pausedByConnection = false,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                        errorMessage = null,
                    )
                }
            }
        }
        return transfer?.cancelAndWait(timeoutMs) ?: true
    }

    fun resumeDownload(downloadId: String) {
        ensureLoaded()
        synchronized(stateLock) {
            resumeDownloadLocked(downloadId)
        }
    }

    private fun resumeDownloadLocked(downloadId: String) {
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Paused && item.status != DownloadStatus.Failed) return

        val reset = item.copy(
            status = DownloadStatus.Downloading,
            errorMessage = null,
            autoDownloadStop = null,
            failureKind = null,
            localFileUri = null,
            pausedByFolder = false,
            pausedByConnection = false,
            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
        )

        replaceItem(reset)
        persist()
        startDownload(reset)
    }

    fun retryDownload(downloadId: String) {
        resumeDownload(downloadId)
    }

    /**
     * Starts every failed download again, except the ones whose source is the problem: fetching
     * those again would give the same bad file, so they wait for another source. Returns how many
     * were started.
     */
    fun retryAllFailed(): Int {
        ensureLoaded()
        return synchronized(stateLock) {
            val retryable = _uiState.value.items
                .filter { it.status == DownloadStatus.Failed && !it.needsAnotherSource }
                .map { it.id }
            retryable.forEach(::resumeDownloadLocked)
            retryable.size
        }
    }

    /** Removes every failed download from the list. A failed download has no file; only a partial one is cleaned up. Returns how many. */
    fun clearFailed(): Int {
        ensureLoaded()
        return synchronized(stateLock) {
            // A failed download on a location that is away is left alone: its partial file cannot be cleaned up now.
            val failed = _uiState.value.items.filter { it.status == DownloadStatus.Failed && backend.isConnected(it) }
                .filter { ItemLocks.tryAcquire(it.id) }
            if (failed.isEmpty()) return 0
            try {
                failed.forEach { item ->
                    activeTransfers.remove(item.id)?.cancel()
                    backend.removePartialOf(item)
                }
                val ids = failed.mapTo(mutableSetOf()) { it.id }
                publish(_uiState.value.items.filterNot { it.id in ids })
                persist()
            } finally {
                failed.forEach { ItemLocks.release(it.id) }
            }
            failed.size
        }
    }

    internal fun reattachBackgroundDownload(downloadId: String): Unit = synchronized(stateLock) {
        if (!hasLoaded) return
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        activeTransfers.remove(downloadId)?.cancel()
        rateTracker.clear(downloadId)
        val restored = backend.restoreItem(item)
        replaceItem(restored)
        persist()
        if (restored.status == DownloadStatus.Downloading) startDownload(restored)
    }

    fun cancelDownload(downloadId: String) {
        ensureLoaded()
        synchronized(stateLock) {
            val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
            // On a location that is away, or while a move has the item, nothing is cancelled or removed.
            if (!backend.isConnected(item) || !ItemLocks.tryAcquire(downloadId)) return
            try {
                backend.withListLock {
                    val fileUri = playableLocalFileUri(item) ?: item.localFileUri
                    // A file another record of any profile uses stays; one that cannot be told stays, and so does the entry.
                    val used = fileUri?.let { usedByAnother(item, _uiState.value.items, it) } ?: false
                    if (used == null) return@withListLock
                    activeTransfers.remove(downloadId)?.cancel()
                    rateTracker.clear(downloadId)
                    // The record goes first, durably; then the file. A failure after that leaves a file, never a dangling record.
                    if (!removeRecordDurably(item)) return@withListLock
                    if (fileUri != null && !used && !backend.discardFile(fileUri)) {
                        restoreRecord(item)
                        return@withListLock
                    }
                    backend.removePartialOf(item)
                }
            } finally {
                ItemLocks.release(downloadId)
            }
        }
    }

    /**
     * Deletes a download the user chose to delete. Its finished file goes to the trash, as it does
     * for a download deleted after watching, and is not deleted outright; what a transfer wrote so
     * far is removed. A file that another download also uses stays where it is. When the file cannot
     * be moved nothing is removed: the entry stays, so that the file is not lost from view.
     */
    internal fun deleteDownload(downloadId: String, permanently: Boolean = false): DownloadDeleteResult {
        ensureLoaded()
        // LOCK ORDER (see stateLock): per-video lock, then the state lock, then the list lock.
        // If the file moved between the look and the locks, look again.
        repeat(LockAttempts) {
            val locked = finishedFileUriOf(downloadId)
            val result = if (locked != null) {
                SidecarLocks.withLock(locked) { deleteWithLocksHeld(downloadId, locked, permanently) }
            } else {
                deleteWithLocksHeld(downloadId, null, permanently)
            }
            if (result != null) return result
        }
        return DownloadDeleteResult.NotMoved
    }

    /** Null when the file is no longer where [lockedUri] said: the caller starts again with the right lock. */
    private fun deleteWithLocksHeld(downloadId: String, lockedUri: String?, permanently: Boolean): DownloadDeleteResult? = synchronized(stateLock) {
        val items = _uiState.value.items
        val item = items.firstOrNull { it.id == downloadId } ?: return DownloadDeleteResult.Gone
        val current = if (item.status == DownloadStatus.Completed) backend.resolveLocalFileUri(item.localFileUri, item.fileName) else null
        if (current != lockedUri) return null
        // Whatever the status, an item whose location is away is not touched: not its partial file, not its record.
        if (!backend.isConnected(item)) return DownloadDeleteResult.NotReachable
        // One thing changes an item at a time: a move has it, or something else is deleting it.
        if (!ItemLocks.tryAcquire(downloadId)) return DownloadDeleteResult.Moving
        try {
            // The ownership check, the file and the record change under the lock every save of a list takes.
            return backend.withListLock { deleteLocked(item, items, permanently) }
        } finally {
            ItemLocks.release(downloadId)
        }
    }

    private fun deleteLocked(item: DownloadItem, items: List<DownloadItem>, permanently: Boolean): DownloadDeleteResult {
        val downloadId = item.id
        val lookup = if (item.status == DownloadStatus.Completed) backend.locate(item) else FileLookup.Missing
        // A file whose location is away is not gone. Nothing is touched, and the entry stays.
        if (lookup is FileLookup.Unreachable) return DownloadDeleteResult.NotReachable
        val fileUri = (lookup as? FileLookup.Found)?.uri
        if (fileUri != null) {
            val usedByAnother = usedByAnother(item, items, fileUri) ?: return DownloadDeleteResult.NotMoved
            activeTransfers.remove(downloadId)?.cancel()
            rateTracker.clear(downloadId)
            // The record goes first, durably; then the file. A failure after that leaves a file, never a dangling record.
            if (!removeRecordDurably(item)) return DownloadDeleteResult.NotMoved
            if (!usedByAnother) {
                val gone = if (permanently) backend.removeFile(fileUri) else backend.trashFile(fileUri)
                if (!gone) {
                    restoreRecord(item)
                    return DownloadDeleteResult.NotMoved
                }
            }
            backend.removePartialOf(item)
            return DownloadDeleteResult.Removed
        }
        activeTransfers.remove(downloadId)?.cancel()
        rateTracker.clear(downloadId)
        if (!removeRecordDurably(item)) return DownloadDeleteResult.NotMoved
        backend.removePartialOf(item)
        return DownloadDeleteResult.Removed
    }

    /** Whether the files of deleted downloads go to the trash of the system, or are held for days in the downloads folder. */
    internal val usesSystemTrash: Boolean get() = backend.usesSystemTrash

    private fun loadFromDisk() {
        val (profileId, storeProblem, normalized) = synchronized(stateLock) {
            _hasUnseenCompleted.value = false
            val profileId = com.nuvio.app.features.profiles.ProfileRepository.activeProfileId
            loadedProfileId = profileId
            loadedPayload = null
            baseline = emptyList()
            baseRevision = 0L
            savingIsUnsafe = false
            val payload = backend.loadPayload(profileId).orEmpty().trim()
            var decoded = if (payload.isEmpty()) null else DownloadsCodec.decode(payload)
            val payloadUnreadable = payload.isNotEmpty() && decoded == null
            if (decoded == null && payload.isNotEmpty()) {
                // A payload that cannot be read is put aside before anything can be saved in its place,
                // and the one from before the last write is read instead when there is one. That one
                // is fetched first: putting the payload aside is a write, after which the copy from
                // before the last write is no longer this one.
                val previous = runCatching { backend.loadPreviousPayload(profileId) }.getOrNull()
                savingIsUnsafe = runCatching { backend.saveUnreadablePayload(profileId, payload, DownloadsClock.nowEpochMs()) }.isFailure
                decoded = previous
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(DownloadsCodec::decode)
            }
            // A payload with content that could not be read is a problem to show, whatever was read in its place.
            val storeProblem = when {
                !payloadUnreadable -> null
                savingIsUnsafe -> DownloadsStoreProblem.NotSaving
                decoded != null -> DownloadsStoreProblem.EarlierCopyShown
                else -> DownloadsStoreProblem.StartedEmpty
            }
            if (decoded != null) {
                loadedPayload = decoded
                baseline = decoded.items
                baseRevision = DownloadsCodec.revisionOf(payload)
            } else if (payloadUnreadable && !savingIsUnsafe) {
                baseRevision = DownloadsCodec.revisionOf(payload)
            }

            var shouldPersistNormalized = false
            val normalized = decoded?.items.orEmpty()
                .map { item ->
                    val statusNormalized = backend.restoreItem(item)

                    val localUriNormalized = normalizeCompletedLocalFileUri(statusNormalized).withoutLostFile()
                    if (localUriNormalized != item) {
                        shouldPersistNormalized = true
                    }
                    localUriNormalized
                }

            _uiState.value = DownloadsUiState(
                items = normalized,
                storeProblem = storeProblem,
            )
            scheduleOfflineIndexRefresh(normalized, force = true)
            notifyLiveStatusPlatform()
            if (shouldPersistNormalized) {
                persist()
            }
            // Stored items can be used while discovery is still waiting on the filesystem.
            hasLoaded = true
            // Resume the stored list before discovery waits on any download volume.
            val resumed = _uiState.value.items.filter { it.status == DownloadStatus.Downloading && it.id !in activeTransfers }
            downloadsResumedAtLoad = downloadsResumedAtLoad + resumed.map { it.id }
            resumed.forEach(::startDownload)
            // Downloads that waited for a better connection when the app quit go on when it is no longer limited.
            applyConnectionHold()
            Triple(profileId, storeProblem, normalized)
        }

        // Run the disk scan outside stateLock so UI actions are never blocked on filesystem I/O.
        val scanResult = if (backend.supportsDiskScan) {
            runCatching {
                backend.scanDownloadsOnDisk(
                    activeProfileId = profileId,
                    inMemoryItems = normalized,
                    nextDownloadId = ::nextDownloadId,
                    nowEpochMs = DownloadsClock.nowEpochMs(),
                )
            }.getOrDefault(DiskScanResult())
        } else {
            DiskScanResult()
        }

        synchronized(stateLock) {
            if (loadedProfileId != profileId) return
            val currentItems = _uiState.value.items
            val newRecovered = scanResult.recoveredItems.filterNot { candidate ->
                currentItems.any { existing -> existing.sameRecoveredFileAs(candidate) }
            }
            val finalItems = currentItems + newRecovered
            _uiState.value = _uiState.value.copy(
                items = finalItems,
                storeProblem = storeProblem,
                unrecognisedFiles = scanResult.unrecognisedFiles,
            )
            scheduleOfflineIndexRefresh(finalItems, force = true)
            notifyLiveStatusPlatform()
            if (newRecovered.isNotEmpty()) {
                persist()
            }
        }
        if (backend === PlatformDownloadsBackend) TripFillHost.onProfileLoaded(profileId)
    }

    internal fun canScanDisk(): Boolean = backend.supportsDiskScan

    /** Scans every available storage location for completed downloads whose records are missing. */
    internal fun findDownloadsOnDisk(
        controllerScanner: ((activeProfileId: Int, inMemoryItems: List<DownloadItem>, nextDownloadId: (Long) -> String, nowEpochMs: Long) -> DiskScanResult)? = null,
    ): DiskScanSummary {
        ensureLoaded()
        val (scanFn, profileId, snapshotItems) = synchronized(stateLock) {
            val fn: ((Int, List<DownloadItem>, (Long) -> String, Long) -> DiskScanResult) = when {
                backend.supportsDiskScan -> backend::scanDownloadsOnDisk
                controllerScanner != null -> controllerScanner
                else -> return DiskScanSummary(0, _uiState.value.unrecognisedFiles.size)
            }
            Triple(
                fn,
                loadedProfileId ?: com.nuvio.app.features.profiles.ProfileRepository.activeProfileId,
                _uiState.value.items,
            )
        }
        // Run the filesystem scan outside stateLock so UI actions are never blocked.
        val result = runCatching {
            scanFn(
                profileId,
                snapshotItems,
                ::nextDownloadId,
                DownloadsClock.nowEpochMs(),
            )
        }.getOrDefault(DiskScanResult())
        if (result.busy) {
            return DiskScanSummary(0, _uiState.value.unrecognisedFiles.size, skippedBusy = true)
        }
        return synchronized(stateLock) {
            // Discard stale results if the active profile changed while scanning.
            if (!hasLoaded || loadedProfileId != profileId) {
                return@synchronized DiskScanSummary(0, _uiState.value.unrecognisedFiles.size)
            }
            val currentItems = _uiState.value.items
            val newRecovered = result.recoveredItems.filterNot { candidate ->
                currentItems.any { existing -> existing.sameRecoveredFileAs(candidate) }
            }
            val updatedItems = if (newRecovered.isNotEmpty()) {
                currentItems + newRecovered
            } else {
                currentItems
            }
            _uiState.value = _uiState.value.copy(
                items = updatedItems,
                unrecognisedFiles = result.unrecognisedFiles,
            )
            notifyLiveStatusPlatform()
            if (newRecovered.isNotEmpty()) {
                persist()
            }
            DiskScanSummary(
                addedCount = newRecovered.size,
                unrecognisedCount = result.unrecognisedFiles.size,
            )
        }
    }

    private fun DownloadItem.sameRecoveredFileAs(other: DownloadItem): Boolean {
        if (id == other.id) return true
        val uriA = localFileUri ?: return false
        val uriB = other.localFileUri ?: return false
        if (backend.isSameFile(uriA, uriB)) return true
        return uriA.localFilePath().equals(uriB.localFilePath(), ignoreCase = true)
    }

    /**
     * Applies the connection hold to the list as it is now: while it holds, every running transfer is
     * stopped the way a pause stops it (its partial file stays, to be continued); once it no longer
     * holds, every download that waited for it goes on. Run whenever the hold changes.
     */
    private fun applyConnectionHold(): Unit = synchronized(stateLock) {
        if (!hasLoaded) return
        _uiState.value.items
            .filter { it.status == DownloadStatus.Downloading && it.connectionHoldReason() != null }
            // A transfer whose file is complete and being checked is left to finish: stopping it then would lose the file.
            .filter { activeTransfers[it.id]?.finishing != true }
            .forEach { item ->
                activeTransfers.remove(item.id)?.cancel()
                rateTracker.clear(item.id)
                mutateItem(item.id) { it.waitingForConnection() }
            }
        _uiState.value.items
            .filter { it.status == DownloadStatus.Paused && it.pausedByConnection && it.connectionHoldReason() == null }
            .forEach { resumeDownloadLocked(it.id) }
    }

    /** Why this download waits for the connection now: some reasons hold automatic downloads only. */
    private fun DownloadItem.connectionHoldReason(): LimitedConnectionReason? =
        connectionHold.holdReasonFor(automatic = autoDownloadRuleKey != null)

    private fun DownloadItem.waitingForConnection(): DownloadItem =
        if (status != DownloadStatus.Downloading) {
            this
        } else {
            copy(
                status = DownloadStatus.Paused,
                pausedByConnection = true,
                errorMessage = null,
                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
            )
        }

    private fun startDownload(item: DownloadItem, attempt: Int = 1): Unit = synchronized(stateLock) {
        // On a limited connection a download waits instead of starting, and goes on when that ends.
        if (item.connectionHoldReason() != null) {
            rateTracker.clear(item.id)
            mutateItem(item.id) { it.waitingForConnection() }
            return
        }
        val transfer = ActiveTransfer()
        val finishing = finishingSteps
        val transferProfileId = loadedProfileId
        // Registered before the downloader is asked to start, so a report made before start returns
        // already finds this transfer.
        activeTransfers[item.id] = transfer
        rateTracker.recordStart(item.id, item.downloadedBytes, DownloadsClock.nowEpochMs())

        val handle = backend.start(
            DownloadPlatformRequest(
                item,
                onDigestVerified = { transfer.digestVerified = true },
                limits = autoTransferLimits(item, transfer),
                onPartialCreated = { key -> ifCurrent(item.id, transfer) { mutateItem(item.id) { it.copy(partialFileKey = key) } } },
                onFileNameChanged = { name ->
                    ifCurrent(item.id, transfer) {
                        mutateItem(item.id) {
                            it.copy(
                                fileName = name,
                                partialFileKey = null,
                                partialFileName = null,
                                sourceETag = null,
                                sourceLastModified = null,
                                pendingLinkVerification = false,
                            )
                        }
                    }
                },
                onFinished = { finishing.end(transfer) },
                onPublished = { uri, size ->
                    synchronized(stateLock) {
                        if (activeTransfers[item.id] !== transfer) false else {
                            // Keep the actual final address before the partial name goes, including a changed name.
                            mutateItem(item.id) { it.copy(localFileUri = uri, downloadedBytes = size) }
                            persist(durably = true)
                        }
                    }
                },
                // A connection hold or quit either came first, or sees this work finishing and leaves it.
                mayPublish = {
                    synchronized(stateLock) {
                        (activeTransfers[item.id] === transfer && finishing.begin(transfer)).also { if (it) transfer.finishing = true }
                    }
                },
                onSourceIdentityObserved = { observedTotalBytes, eTag, lastModified ->
                    ifCurrent(item.id, transfer) {
                        mutateItem(item.id) { current ->
                            if (current.status != DownloadStatus.Downloading) {
                                current
                            } else {
                                current.copy(
                                    totalBytes = observedTotalBytes?.takeIf { it > 0L } ?: current.totalBytes,
                                    sourceETag = eTag ?: current.sourceETag,
                                    sourceLastModified = lastModified ?: current.sourceLastModified,
                                    pendingLinkVerification = false,
                                )
                            }
                        }
                    }
                },
                refreshSourceLink = { currentItem ->
                    val latest = synchronized(stateLock) {
                        _uiState.value.items.firstOrNull { it.id == currentItem.id }
                    } ?: currentItem
                    linkRefresher.refresh(latest)
                },
                onSourceRefreshed = { link, pendingVerification ->
                    ifCurrent(item.id, transfer) {
                        mutateItem(item.id) { current ->
                            if (current.status != DownloadStatus.Downloading) {
                                current
                            } else {
                                val oldUrlKey = sourceUrlKey(current.sourceUrl)
                                val nextTried = if (oldUrlKey != null && oldUrlKey !in current.triedSources) {
                                    (current.triedSources + oldUrlKey).takeLast(MaxRememberedSources)
                                } else {
                                    current.triedSources
                                }
                                current.copy(
                                    sourceUrl = link.url,
                                    sourceHeaders = link.requestHeaders,
                                    sourceResponseHeaders = link.responseHeaders,
                                    sourceFilename = link.filename ?: current.sourceFilename,
                                    sourceFileSizeBytes = link.videoSize?.takeIf { it > 0L } ?: current.sourceFileSizeBytes,
                                    triedSources = nextTried,
                                    pendingLinkVerification = pendingVerification,
                                    updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                                )
                            }
                        }
                    }
                },
                onSwitchedToNewPartial = { newPartialFileName, kept ->
                    ifCurrent(item.id, transfer) {
                        mutateItem(item.id) { current ->
                            if (current.status != DownloadStatus.Downloading) {
                                current
                            } else {
                                val nextSuperseded = if (kept != null && current.supersededPartials.none { it.partialFileKey == kept.partialFileKey }) {
                                    current.supersededPartials + kept
                                } else {
                                    current.supersededPartials
                                }
                                current.copy(
                                    partialFileKey = null,
                                    partialFileName = newPartialFileName,
                                    downloadedBytes = 0L,
                                    totalBytes = null,
                                    sourceETag = null,
                                    sourceLastModified = null,
                                    supersededPartials = nextSuperseded,
                                    pendingLinkVerification = false,
                                    updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                                )
                            }
                        }
                    }
                },
                isCompletionAccepted = {
                    synchronized(stateLock) { transfer.completionAccepted && loadedProfileId == transferProfileId }
                },
                onSupersededPartialsRetired = { retiredKeys ->
                    synchronized(stateLock) {
                        if (!transfer.completionAccepted || loadedProfileId != transferProfileId) return@synchronized
                        mutateItem(item.id) { current ->
                            if (retiredKeys.isEmpty() || current.supersededPartials.isEmpty()) {
                                current
                            } else {
                                current.copy(
                                    supersededPartials = current.supersededPartials.filterNot { it.partialFileKey in retiredKeys },
                                )
                            }
                        }
                    }
                },
            ),
            { downloadedBytes, totalBytes ->
                ifCurrent(item.id, transfer) {
                    val currentItem = _uiState.value.items.firstOrNull { it.id == item.id } ?: item
                    if (failIfAnnouncedAsPlaceholder(currentItem, totalBytes)) return@ifCurrent
                    val now = DownloadsClock.nowEpochMs()
                    rateTracker.recordProgress(item.id, downloadedBytes, now)
                    mutateItem(item.id) { current ->
                        if (current.status != DownloadStatus.Downloading) {
                            current
                        } else {
                            current.copy(
                                downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                                totalBytes = totalBytes?.takeIf { it > 0L },
                                updatedAtEpochMs = now,
                                errorMessage = null,
                            )
                        }
                    }
                }
            },
            { localFileUri, totalBytes ->
                if (finishing.begin(transfer)) {
                    try { finishTransfer(item, transfer, localFileUri, totalBytes) }
                    finally { finishing.end(transfer) }
                }
            },
            { message ->
                try {
                    ifCurrent(item.id, transfer) {
                        activeTransfers.remove(item.id)
                        rateTracker.clear(item.id)
                        val current = _uiState.value.items.firstOrNull { it.id == item.id }
                        val kind = classifyDownloadFailure(message)
                        // A bad file, a refusal or a full disk comes again on every attempt.
                        val worthAnotherAttempt = kind == DownloadFailureKind.Other
                        if (current?.status == DownloadStatus.Downloading && attempt < MaxDownloadAttempts && worthAnotherAttempt && !finishing.isClosing) {
                            startDownload(current, attempt + 1)
                        } else {
                            mutateItem(item.id) { failed ->
                                if (failed.status != DownloadStatus.Downloading) {
                                    failed
                                } else {
                                    failed.copy(
                                        status = DownloadStatus.Failed,
                                        failureKind = kind,
                                        errorMessage = message.ifBlank { runBlocking { getString(Res.string.download_failed) } },
                                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                                    )
                                }
                            }
                        }
                    }
                } finally { finishing.end(transfer) }
            },
            {
                try {
                    ifCurrent(item.id, transfer) {
                        activeTransfers.remove(item.id)
                        rateTracker.clear(item.id)
                        // Quit refused a new publication: leave it interrupted, to resume on the next launch.
                        if (finishing.isClosing && !transfer.finishing) return@ifCurrent
                        mutateItem(item.id) { current ->
                            if (current.status != DownloadStatus.Downloading) return@mutateItem current
                            // A transfer that could not run because its location is away or being moved
                            // waits for it, and goes on by itself.
                            current.copy(status = DownloadStatus.Paused, errorMessage = null, pausedByFolder = backend.heldByFolder(current))
                        }
                    }
                } finally { finishing.end(transfer) }
            },
        )
        transfer.attach(handle)
    }

    /**
     * A transfer reported that its file is complete. The file is accepted only if its checks pass:
     * first the look at the file itself, then, for what that passed, a look at decoded frames. The
     * frame look takes seconds, so it runs outside the state lock, while the transfer still owns the
     * download; the result is applied under the lock, and only if the transfer still owns it.
     */
    private fun finishTransfer(item: DownloadItem, transfer: ActiveTransfer, localFileUri: String, totalBytes: Long?) {
        transfer.finishing = true
        // A download is finished only when its file is there, has the size the server
        // announced and is a video. Anything else is a failed download without a file.
        val inspection = synchronized(stateLock) {
            if (activeTransfers[item.id] !== transfer) return
            val facts = backend.inspectFile(localFileUri)
            FileInspection(facts, facts?.problem(announcedBytes = totalBytes, expectedVideoHash = item.expectedVideoHash))
        }
        val facts = inspection.facts
        val problem = inspection.problem

        // Null samples: the platform cannot look, which is no fault and is not recorded as a check.
        val samples = if (problem == null) runCatching { backend.sampleFrames(localFileUri) }.getOrNull() else null
        val verdict = samples?.let { first ->
            runCatching {
                confirmedFramesProblem(first) { backend.sampleConfirmingFrames(localFileUri, first.durationMs) }
            }.getOrNull()
        }
        val framesProblem = verdict?.problem
        val framesCheck = when {
            verdict == null || !verdict.conclusive || framesProblem != null -> DownloadFramesCheck.NotChecked
            samples.titleNamesAnotherEpisode(
                expectedSeason = item.seasonNumber.takeIf { item.isEpisode },
                expectedEpisode = item.episodeNumber.takeIf { item.isEpisode },
            ) -> DownloadFramesCheck.Suspect
            else -> DownloadFramesCheck.Passed
        }

        // What the source claimed and the title's running time, held against the file that arrived.
        val placeholder = if (problem == null && framesProblem == null && facts != null) {
            (placeholderVerdict(
                PlaceholderEvidence(
                    fileBytes = facts.sizeBytes,
                    claimedBytes = item.claimedSizeBytes(runtimeMinutesOf(item)),
                    durationMs = facts.durationMs ?: samples?.durationMs,
                    runtimeMinutes = runtimeMinutesOf(item),
                    sourceSaysNotCached = sourceLabelSaysNotCached(item.streamTitle),
                ),
            ) as? PlaceholderVerdict.NotTheRealVideo)?.reason
        } else {
            null
        }

        ifCurrent(item.id, transfer) {
            activeTransfers.remove(item.id)
            rateTracker.clear(item.id)
            // Only the file this transfer just published, proven by its identity; anything else stays.
            if (problem != null || framesProblem != null || placeholder != null) backend.removeJustPublished(localFileUri)
            var completedItem: DownloadItem? = null
            mutateItem(item.id) { current ->
                if (current.status != DownloadStatus.Downloading) return@mutateItem current
                if (problem != null) return@mutateItem current.failedBecauseOf(problem)
                if (framesProblem != null) return@mutateItem current.failedBecauseOf(framesProblem)
                if (placeholder != null) return@mutateItem current.failedAsPlaceholder(placeholder)
                current.copy(
                    verifiedBy = facts?.verification(
                        // Only a size the server announced while the transfer ran counts.
                        announcedBytes = current.totalBytes,
                        expectedVideoHash = current.expectedVideoHash,
                        expectedSizeBytes = current.expectedSizeBytes,
                        digestVerified = transfer.digestVerified,
                    ) ?: if (transfer.digestVerified) DownloadVerification.Digest else null,
                    framesCheck = framesCheck,
                    status = DownloadStatus.Completed,
                    localFileUri = localFileUri,
                    downloadedBytes = if (totalBytes != null && totalBytes > 0L) {
                        totalBytes
                    } else {
                        current.downloadedBytes
                    },
                    totalBytes = totalBytes?.takeIf { it > 0L } ?: current.totalBytes,
                    partialFileName = null,
                    errorMessage = null,
                    updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                ).also { completedItem = it }
            }
            transfer.completionAccepted = completedItem != null
            if (transfer.completionAccepted) {
                companions().saveAfterDownload(completedItem ?: item, localFileUri)
            }
        }
    }

    private class FileInspection(val facts: DownloadedFileFacts?, val problem: DownloadedFileProblem?)

    /**
     * Gives up a transfer whose server announced, before sending anything, a total far below the size
     * the source claimed: it is a placeholder, and downloading it all would only waste the time. The
     * download fails as a bad file, so a season or an automatic download goes on to the next source
     * and a single download shows the reason with "Choose another source". Called under the state
     * lock for a transfer that still owns the download. True when the transfer was ended.
     */
    private fun failIfAnnouncedAsPlaceholder(item: DownloadItem, announcedBytes: Long?): Boolean {
        if (announcedBytes == null || announcedBytes <= 0L) return false
        val runtime = runtimeMinutesOf(item)
        val claimed = item.claimedSizeBytes(runtime)
        // No length is known yet at the start, so only what needs none decides: a total far below a
        // claim that describes the file, or a total that is absurdly small for the running time. A low
        // bitrate in between is judged on the finished file, whose own length can say it is complete.
        val reason = when {
            claimed != null && announcedSizeIsFarBelowClaim(announcedBytes, claimed) ->
                PlaceholderReason.SizeBelowClaim(announcedBytes, claimed)
            runtime != null && isAbsurdlySmallForRuntime(announcedBytes, runtime) ->
                PlaceholderReason.TooSmallForRuntime(announcedBytes, runtime)
            else -> return false
        }
        activeTransfers.remove(item.id)?.cancel()
        rateTracker.clear(item.id)
        if (!item.tripFill) backend.removeActivePartialOf(item)
        mutateItem(item.id) { current ->
            if (current.status != DownloadStatus.Downloading) {
                current
            } else {
                current.failedAsPlaceholder(reason)
                    .copy(
                        totalBytes = if (current.tripFill && current.downloadedBytes > 0L) current.totalBytes else announcedBytes,
                        downloadedBytes = if (current.tripFill) current.downloadedBytes else 0L,
                    )
            }
        }
        return true
    }

    /** The running time of the title of [item] in minutes, from details that are already loaded. Null when not known. */
    private fun runtimeMinutesOf(item: DownloadItem): Int? = runtimeMinutesOfDownload(item)

    /** The downloads that went on by themselves when the list was loaded. Each is handed out once. */
    internal fun takeDownloadsResumedAtLoad(): Set<String> = synchronized(stateLock) {
        downloadsResumedAtLoad.also { downloadsResumedAtLoad = emptySet() }
    }

    /** Notes the audio language playback of a download starts on. */
    internal fun setPreferredAudioLanguage(downloadId: String, language: String) {
        ensureLoaded()
        mutateItem(downloadId) { it.copy(preferredAudioLanguage = language) }
    }

    /** The audio language that was noted for the finished download with this file, if any. */
    fun preferredAudioLanguageOfFile(localFileUri: String): String? =
        _uiState.value.items
            .firstOrNull { it.status == DownloadStatus.Completed && it.localFileUri == localFileUri }
            ?.preferredAudioLanguage

    /** A look at the file of a finished download. Null when it has none or the platform cannot look. */
    internal fun inspectCompletedDownload(item: DownloadItem): DownloadedFileFacts? {
        if (item.status != DownloadStatus.Completed) return null
        val fileUri = backend.resolveLocalFileUri(item.localFileUri, item.fileName) ?: item.localFileUri ?: return null
        return backend.inspectFile(fileUri)
    }

    /**
     * Looks again at the file of a finished download and holds it against what the source claimed and
     * the running time of the title (see [placeholderVerdict]), then saves the result on the download
     * the way a fresh download's result is saved. Never touches the file: a placeholder is marked, and
     * a mark that no longer applies is cleared. The look at the disk, and at frames, runs outside the
     * state lock. A file that cannot be looked at, or is broken in another way, is left as it is.
     */
    internal fun recheckCompleted(downloadId: String): RecheckOutcome {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return RecheckOutcome.Skipped
        if (item.status != DownloadStatus.Completed) return RecheckOutcome.Skipped
        val uri = backend.resolveLocalFileUri(item.localFileUri, item.fileName) ?: return RecheckOutcome.Unavailable
        val facts = runCatching { backend.inspectFile(uri) }.getOrNull() ?: return RecheckOutcome.CouldNotLook
        if (!facts.exists) return RecheckOutcome.Unavailable
        if (facts.problem(announcedBytes = item.totalBytes, expectedVideoHash = item.expectedVideoHash) != null) {
            return RecheckOutcome.CouldNotLook
        }

        val runtime = runtimeMinutesOfDownload(item)
        val claimed = item.claimedSizeBytes(runtime)
        fun evidence(durationMs: Long?, frames: DownloadedFramesProblem?) =
            PlaceholderEvidence(facts.sizeBytes, claimed, durationMs, runtime, frames, sourceLabelSaysNotCached(item.streamTitle))

        var verdict = placeholderVerdict(evidence(facts.durationMs, null))
        var picturePassed = false
        // The picture is worth the seconds it takes only when it can change the answer: the title's
        // running time is known and the file is short for it, or the container did not state its length.
        val needsPicture = verdict !is PlaceholderVerdict.NotTheRealVideo && runtime != null && item.framesCheck != DownloadFramesCheck.Passed &&
            (facts.durationMs == null || looksLikePlaceholder(facts.durationMs, runtime))
        if (needsPicture) {
            val samples = runCatching { backend.sampleFrames(uri) }.getOrNull()
            val framesVerdict = samples?.let { first ->
                runCatching { confirmedFramesProblem(first) { backend.sampleConfirmingFrames(uri, first.durationMs) } }.getOrNull()
            }
            if (samples != null && framesVerdict != null && framesVerdict.conclusive) {
                verdict = placeholderVerdict(evidence(facts.durationMs ?: samples.durationMs, framesVerdict.problem))
                picturePassed = framesVerdict.problem == null && samples.structuralProblem() == null
            } else if (samples != null && facts.durationMs == null) {
                verdict = placeholderVerdict(evidence(samples.durationMs, null))
            }
        }

        val verification = facts.verification(
            announcedBytes = item.totalBytes,
            expectedVideoHash = item.expectedVideoHash,
            expectedSizeBytes = item.expectedSizeBytes,
            digestVerified = false,
        )
        val flagged = (verdict as? PlaceholderVerdict.NotTheRealVideo)?.reason
        val message = flagged?.let { placeholderReasonText(it, item) }
        var applied = false
        mutateItem(downloadId) { current ->
            if (current.status != DownloadStatus.Completed || current.fileName != item.fileName) return@mutateItem current
            applied = true
            val wasMarked = current.framesCheck == DownloadFramesCheck.NotTheRealVideo
            current.copy(
                verifiedBy = current.verifiedBy ?: verification,
                expectedSizeBytes = current.expectedSizeBytes ?: claimed,
                framesCheck = when {
                    flagged != null -> DownloadFramesCheck.NotTheRealVideo
                    picturePassed -> DownloadFramesCheck.Passed
                    wasMarked -> DownloadFramesCheck.NotChecked
                    else -> current.framesCheck
                },
                errorMessage = when {
                    flagged != null -> message
                    wasMarked -> null
                    else -> current.errorMessage
                },
            )
        }
        return when {
            !applied -> RecheckOutcome.Skipped
            flagged != null -> RecheckOutcome.Flagged
            verdict == PlaceholderVerdict.LooksRight -> RecheckOutcome.LooksRight
            else -> RecheckOutcome.CouldNotCheck
        }
    }

    /**
     * Whether a finished download still has its file. One whose file is gone, or smaller than the
     * download was, becomes a failed download, which can be tried again, and is not counted as
     * downloaded. True for a platform that cannot look at the file.
     */
    internal fun verifyCompletedDownload(downloadId: String): Boolean {
        val initial = synchronized(stateLock) {
            ensureLoaded()
            _uiState.value.items.firstOrNull { it.id == downloadId }
        } ?: return false
        if (!initial.isVerifiedCompletedStatus()) return false
        // A move has the item: its file is in the middle of changing places, which is not a file that is lost.
        if (!ItemLocks.tryAcquire(downloadId)) return true
        try {
            var snapshot = initial
            for (attempt in 0..2) {
                val checked = snapshot.withoutLostFile()
                synchronized(stateLock) {
                    val latest = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return false
                    if (!latest.isVerifiedCompletedStatus()) return false
                    if (latest != snapshot && attempt < 2) {
                        snapshot = latest
                    } else if (checked.status == DownloadStatus.Completed) {
                        scheduleOfflineIndexRefresh(_uiState.value.items, force = true)
                        return true
                    } else {
                        mutateItem(downloadId) { checked }
                        return false
                    }
                }
            }
            return false
        } finally {
            ItemLocks.release(downloadId)
        }
    }

    /** This finished download, or the failed download it has become because its file is lost. */
    private fun DownloadItem.withoutLostFile(): DownloadItem {
        if (status != DownloadStatus.Completed) return this
        val lookup = backend.locate(this)
        // A location that is away says nothing about its files. They are not lost, and the record is kept as it is.
        if (lookup is FileLookup.Unreachable) return this
        val fileUri = (lookup as? FileLookup.Found)?.uri ?: localFileUri ?: return this
        val facts = backend.inspectFile(fileUri) ?: return this
        val problem = when {
            !facts.exists -> DownloadedFileProblem.Missing
            totalBytes != null && facts.sizeBytes < totalBytes -> DownloadedFileProblem.Incomplete
            else -> return this
        }
        return failedBecauseOf(problem)
    }

    private fun DownloadItem.failedBecauseOf(problem: DownloadedFileProblem): DownloadItem = failedWith(
        when (problem) {
            DownloadedFileProblem.Missing -> Res.string.downloads_error_file_missing
            DownloadedFileProblem.Incomplete -> Res.string.downloads_error_file_incomplete
            DownloadedFileProblem.NotVideo -> Res.string.downloads_error_file_not_video
            DownloadedFileProblem.ChecksumMismatch -> Res.string.downloads_error_file_checksum
        },
        // A file that went missing or was cut short says nothing against its source.
        when (problem) {
            DownloadedFileProblem.Missing, DownloadedFileProblem.Incomplete -> DownloadFailureKind.Other
            DownloadedFileProblem.NotVideo, DownloadedFileProblem.ChecksumMismatch -> DownloadFailureKind.BadFile
        },
    )

    private fun DownloadItem.failedBecauseOf(problem: DownloadedFramesProblem): DownloadItem = failedWith(
        when (problem) {
            DownloadedFramesProblem.Undecodable -> Res.string.downloads_error_frames_undecodable
            DownloadedFramesProblem.BrokenEnd -> Res.string.downloads_error_frames_broken_end
            DownloadedFramesProblem.BrokenStart -> Res.string.downloads_error_frames_broken_start
            DownloadedFramesProblem.Blank -> Res.string.downloads_error_frames_blank
            DownloadedFramesProblem.StillPicture -> Res.string.downloads_error_frames_still
        },
        DownloadFailureKind.BadFile,
    )

    /** A download that just arrived, or is arriving, as a placeholder: failed, without a file, as a bad file. */
    private fun DownloadItem.failedAsPlaceholder(reason: PlaceholderReason): DownloadItem = copy(
        status = DownloadStatus.Failed,
        localFileUri = null,
        framesCheck = DownloadFramesCheck.NotChecked,
        failureKind = DownloadFailureKind.BadFile,
        errorMessage = placeholderReasonText(reason, this),
        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
    )

    private fun DownloadItem.failedWith(message: StringResource, kind: DownloadFailureKind): DownloadItem = copy(
        status = DownloadStatus.Failed,
        localFileUri = null,
        framesCheck = DownloadFramesCheck.NotChecked,
        failureKind = kind,
        errorMessage = runBlocking { getString(message) },
        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
    )

    internal fun transferBytesPerSecond(downloadId: String, nowEpochMs: Long = DownloadsClock.nowEpochMs()): Long? =
        rateTracker.bytesPerSecond(downloadId, nowEpochMs)

    internal fun autoDownloadStorageLimitBytes(): Long? = autoDownloadLimits.storageLimitBytes()

    internal fun autoDownloadMinFreeBytes(): Long = autoDownloadLimits.minFreeBytes

    /**
     * The bytes automatic downloads take: what each has recorded as downloaded, and for a running
     * transfer the full size once the server announced it.
     */
    internal fun autoDownloadBytes(exceptDownloadId: String? = null): Long =
        _uiState.value.items
            // Locations that are away are not counted; nothing is ever deleted to make room because of them.
            .filter { (it.autoDownloadRuleKey != null || it.tripFill) && it.id != exceptDownloadId && backend.isConnected(it) }
            .sumOf { item ->
                if (item.status == DownloadStatus.Downloading || (item.tripFill && item.status == DownloadStatus.Paused)) {
                    maxOf(item.totalBytes ?: (item.expectedSizeBytes.takeIf { item.tripFill }) ?: 0L, item.downloadedBytes)
                } else {
                    item.downloadedBytes
                }
            }

    private fun autoTransferLimits(item: DownloadItem, transfer: ActiveTransfer): DownloadTransferLimits? {
        if (item.autoDownloadRuleKey == null && !item.tripFill) return null
        val limits = autoDownloadLimits
        return DownloadTransferLimits(
            maxTotalBytes = { limits.storageLimitBytes()?.let { it - autoDownloadBytes(exceptDownloadId = item.id) } },
            minFreeBytes = limits.minFreeBytes,
            checkAndReserve = { check, announcedBytes ->
                synchronized(stateLock) {
                    check() ?: run {
                        if (announcedBytes != null && announcedBytes > 0L) {
                            ifCurrent(item.id, transfer) {
                                mutateItem(item.id) { current ->
                                    if (current.status == DownloadStatus.Downloading) current.copy(totalBytes = announcedBytes) else current
                                }
                            }
                        }
                        null
                    }
                }
            },
            onStopped = { reason ->
                ifCurrent(item.id, transfer) {
                    activeTransfers.remove(item.id)
                    rateTracker.clear(item.id)
                    mutateItem(item.id) { current ->
                        if (current.status != DownloadStatus.Downloading) return@mutateItem current
                        current.copy(
                            status = DownloadStatus.Paused,
                            autoDownloadStop = reason,
                            errorMessage = null,
                            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                        )
                    }
                }
            },
        )
    }

    /**
     * Removes a finished watched download: its file is moved to the trash and its entry dropped.
     * Everything is looked at again here, under the state lock, so nothing that changed since the
     * caller decided is acted on: the download must still be finished and still belong to [ruleKey],
     * and [stillWanted] must say so.
     * A file that another download also uses stays where it is and only the entry is dropped. When
     * the file cannot be moved, the entry stays as well.
     */
    internal fun removeWatchedAutoDownload(
        downloadId: String,
        ruleKey: String?,
        stillWanted: () -> Boolean = { true },
    ): AutoDownloadRemovalResult {
        ensureLoaded()
        // Per-video lock first, then the state lock (see the lock order at stateLock).
        repeat(LockAttempts) {
            val locked = finishedFileUriOf(downloadId)
            val result = if (locked != null) {
                SidecarLocks.withLock(locked) { removeAutoWithLocksHeld(downloadId, ruleKey, stillWanted, locked) }
            } else {
                removeAutoWithLocksHeld(downloadId, ruleKey, stillWanted, null)
            }
            if (result != null) return result
        }
        return AutoDownloadRemovalResult.NotMoved
    }

    private fun removeAutoWithLocksHeld(
        downloadId: String,
        ruleKey: String?,
        stillWanted: () -> Boolean,
        lockedUri: String?,
    ): AutoDownloadRemovalResult? = synchronized(stateLock) {
        val items = _uiState.value.items
        val item = items.firstOrNull { it.id == downloadId } ?: return AutoDownloadRemovalResult.Changed
        // A file flagged as a placeholder is never removed automatically.
        if (item.autoDownloadRuleKey != ruleKey || item.status != DownloadStatus.Completed || item.isPlaceholder) {
            return AutoDownloadRemovalResult.Changed
        }
        // Scheduled removals must never use a holding folder that is later emptied permanently.
        if (!backend.usesSystemTrash) return AutoDownloadRemovalResult.NotMoved
        if (!stillWanted()) return AutoDownloadRemovalResult.Changed
        val fileUri = backend.resolveLocalFileUri(item.localFileUri, item.fileName)
        if (fileUri != lockedUri) return null
        if (!backend.isConnected(item)) return AutoDownloadRemovalResult.NotReachable
        // A move has the item: it stays scheduled and is looked at again later.
        if (!ItemLocks.tryAcquire(downloadId)) return AutoDownloadRemovalResult.Busy
        try {
            return synchronized(WatchedRemovalGuard.lock) {
                val checked = WatchedRemovalGuard.check(stillWanted)
                backend.withListLock { removeWatchedLocked(item, items, checked) }
            }
        } finally {
            ItemLocks.release(downloadId)
        }
    }

    private fun removeWatchedLocked(
        item: DownloadItem,
        items: List<DownloadItem>,
        stillWanted: () -> Boolean,
    ): AutoDownloadRemovalResult {
        if (!stillWanted()) return AutoDownloadRemovalResult.Changed
        val lookup = backend.locate(item)
        // A file whose location is away is not gone: it is left, and the entry stays.
        if (lookup is FileLookup.Unreachable) return AutoDownloadRemovalResult.NotReachable
        val fileUri = (lookup as? FileLookup.Found)?.uri
        val shared = fileUri?.let { usedByAnother(item, items, it) ?: return AutoDownloadRemovalResult.NotMoved } ?: false
        if (!stillWanted()) return AutoDownloadRemovalResult.Changed
        if (!removeRecordDurably(item)) return AutoDownloadRemovalResult.NotMoved
        // Saving the list may block. Keep, settings, playback and progress must be checked again.
        val result = runCatching {
            if (!stillWanted()) AutoDownloadRemovalResult.Changed
            else if (fileUri != null && !shared) backend.trashWatchedFile(fileUri, stillWanted)
            else AutoDownloadRemovalResult.Removed
        }.getOrDefault(AutoDownloadRemovalResult.NotMoved)
        if (result != AutoDownloadRemovalResult.Removed) restoreRecord(item)
        return result
    }

    /** Removes [item]'s record and saves the list durably. False (and the record back in place) when that save failed. */
    private fun removeRecordDurably(item: DownloadItem): Boolean {
        publish(_uiState.value.items.filterNot { it.id == item.id })
        if (persist(durably = true)) return true
        restoreRecord(item)
        return false
    }

    /** Puts [item]'s record back after its file could not be removed, so the record and the file stay together. */
    private fun restoreRecord(item: DownloadItem) {
        if (_uiState.value.items.none { it.id == item.id }) publish(_uiState.value.items + item)
        persist()
    }

    /**
     * Whether another record uses [fileUri]: of any profile, sidecar folder included, where the platform can
     * tell (desktop); otherwise of this list. Null when that cannot be told: then nothing may be removed.
     */
    private fun usedByAnother(item: DownloadItem, items: List<DownloadItem>, fileUri: String): Boolean? =
        when (backend.ownershipOf(item, fileUri)) {
            FileOwnership.Owned -> true
            FileOwnership.Free -> false
            FileOwnership.Unknown -> null
            null -> items.any { other ->
                other.id != item.id &&
                    listOfNotNull(other.localFileUri, backend.resolveLocalFileUri(other.localFileUri, other.fileName))
                        .any { backend.isSameFile(it, fileUri) }
            }
        }

    /** Runs [block] under the state lock, and only while [transfer] still owns the download. */
    private inline fun ifCurrent(downloadId: String, transfer: ActiveTransfer, block: () -> Unit) {
        synchronized(stateLock) {
            if (activeTransfers[downloadId] === transfer) block()
        }
    }

    private fun mutateItem(downloadId: String, transform: (DownloadItem) -> DownloadItem): Unit = synchronized(stateLock) {
        var changed = false
        val updated = _uiState.value.items.map { item ->
            if (item.id == downloadId) {
                changed = true
                transform(item)
            } else {
                item
            }
        }

        if (changed) {
            publish(updated)
            persist()
        }
    }

    private fun replaceItem(item: DownloadItem): Unit = synchronized(stateLock) {
        val updated = _uiState.value.items.map { existing ->
            if (existing.id == item.id) item else existing
        }
        publish(updated)
    }

    private fun publish(items: List<DownloadItem>) {
        val previousStatuses = _uiState.value.items.associate { it.id to it.status }
        if (items.any { it.status == DownloadStatus.Completed && previousStatuses[it.id].let { status -> status != null && status != DownloadStatus.Completed } }) {
            _hasUnseenCompleted.value = true
        }
        _uiState.value = _uiState.value.copy(items = items)
        scheduleOfflineIndexRefresh(items)
        notifyLiveStatusPlatform()
    }

    private fun notifyLiveStatusPlatform() {
        runCatching {
            DownloadsLiveStatusPlatform.onItemsChanged(_uiState.value.items)
        }
    }

    /**
     * Saves the list. Where another copy of the app may have saved since (desktop), it is done under the
     * lock across processes: the stored list is read again and, when it is newer, this copy's own changes
     * since its last read are merged onto it, field by field, instead of overwriting it.
     */
    private fun persist(durably: Boolean = false): Boolean {
        if (savingIsUnsafe) return false
        val profileId = loadedProfileId ?: com.nuvio.app.features.profiles.ProfileRepository.activeProfileId
        // A lock that cannot be had, or a save that fails, is a save not done: false, and nothing is assumed saved.
        return runCatching {
            backend.withListLock {
                val revision = if (backend.rereadsPayload) mergeNewerStored(profileId) ?: return@withListLock false else null
                backend.savePayload(profileId, DownloadsCodec.encode(_uiState.value.items, loadedPayload, revision))
                baseline = _uiState.value.items
                if (revision != null) baseRevision = revision
                !durably || backend.syncPayload()
            }
        }.getOrDefault(false)
    }

    /**
     * Under the list lock: when the stored list is newer than the one this copy read, merges this copy's
     * changes onto it and publishes the result. The revision to save next, or null when the stored list
     * is newer and cannot be read (nothing may be saved over it then).
     */
    private fun mergeNewerStored(
        profileId: Int = loadedProfileId ?: com.nuvio.app.features.profiles.ProfileRepository.activeProfileId,
    ): Long? {
        val stored = runCatching { backend.loadFreshPayload(profileId) }.getOrNull()?.trim().orEmpty()
        val storedRevision = if (stored.isEmpty()) 0L else DownloadsCodec.revisionOf(stored)
        if (storedRevision > baseRevision) {
            val decoded = DownloadsCodec.decode(stored) ?: return null
            val merged = DownloadsCodec.merge(decoded.items, baseline, _uiState.value.items)
            loadedPayload = decoded
            baseline = decoded.items
            baseRevision = storedRevision
            publish(merged)
        }
        return maxOf(storedRevision, baseRevision) + 1
    }

    /** The profile whose list is in memory, or null before one was loaded. */
    internal fun loadedProfile(): Int? = loadedProfileId.takeIf { hasLoaded }

    /**
     * The record [downloadId] of [profileId], read from that profile's list: the one in memory when it is
     * that profile's, otherwise the stored one. Null when there is no such record or the list cannot be read.
     */
    internal fun recordOf(profileId: Int, downloadId: String, lists: ProfileLists): DownloadItem? =
        // Without the state lock: the mover asks this while it holds the list lock (lock order: state, then list).
        if (hasLoaded && loadedProfileId == profileId) {
            _uiState.value.items.firstOrNull { it.id == downloadId }
        } else {
            decodeStoredDownloads(runCatching { lists.load(profileId) }.getOrNull().orEmpty())?.firstOrNull { it.id == downloadId }
        }

    private fun listFor(profileId: Int, lists: ProfileLists): Pair<List<DownloadItem>, DownloadsCodec.Decoded?>? {
        if (hasLoaded && loadedProfileId == profileId) return _uiState.value.items to loadedPayload
        val payload = runCatching { lists.load(profileId) }.getOrNull()?.trim().orEmpty()
        if (payload.isEmpty()) return emptyList<DownloadItem>() to null
        val decoded = DownloadsCodec.decode(payload) ?: return null
        return decoded.items to decoded
    }

    /**
     * The only way a record is pointed at a moved file. In ONE step under the state lock: loads the list
     * of [profileId] (the in-memory list only when it is that profile's), checks that the record
     * [downloadId] still [matches] what the move planned, applies [relocate], saves that profile's list
     * and flushes it to disk. Confirmed only when every part of that succeeded. Nothing else is changed.
     */
    internal fun compareAndRelocate(
        profileId: Int,
        downloadId: String,
        lists: ProfileLists,
        matches: (DownloadItem) -> Boolean,
        relocate: (DownloadItem) -> DownloadItem,
    ): RelocateResult = synchronized(stateLock) {
        // From the read to the flush, under the lock every save of a list takes, across processes.
        backend.withListLock { relocateLocked(profileId, downloadId, lists, matches, relocate) }
    }

    private fun relocateLocked(
        profileId: Int,
        downloadId: String,
        lists: ProfileLists,
        matches: (DownloadItem) -> Boolean,
        relocate: (DownloadItem) -> DownloadItem,
    ): RelocateResult {
        val inMemory = hasLoaded && loadedProfileId == profileId
        if (inMemory && savingIsUnsafe) return RelocateResult.NotDurable
        // The list in memory first takes in whatever another copy of the app saved since.
        val revision = if (inMemory) {
            if (backend.rereadsPayload) mergeNewerStored() ?: return RelocateResult.ListUnreadable else baseRevision + 1
        } else {
            DownloadsCodec.revisionOf(runCatching { lists.load(profileId) }.getOrNull().orEmpty()) + 1
        }
        val (items, base) = listFor(profileId, lists) ?: return RelocateResult.ListUnreadable
        val current = items.firstOrNull { it.id == downloadId } ?: return RelocateResult.RecordGone
        if (!matches(current)) return RelocateResult.RecordChanged
        val updated = items.map { if (it.id == downloadId) relocate(it) else it }
        val saved = runCatching { lists.saveDurably(profileId, DownloadsCodec.encode(updated, base, revision)) }.getOrDefault(false)
        // Not confirmed: the in-memory list is left as it was. Nothing may depend on the new record.
        if (!saved) return RelocateResult.NotDurable
        if (inMemory) {
            publish(updated)
            baseline = updated
            baseRevision = revision
        }
        return RelocateResult.Confirmed
    }

    /**
     * A download that waits for a location that is not there is put in another one, at the user's
     * request, and starts.
     */
    internal fun chooseLocationForWaiting(downloadId: String, locationId: String) {
        ensureLoaded()
        synchronized(stateLock) {
            val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
            if (item.status != DownloadStatus.Paused || !item.pausedByFolder) return
            backend.removePartialOf(item)
            mutateItem(downloadId) { it.copy(locationId = locationId) }
            resumeDownloadLocked(downloadId)
        }
    }

    /** Whether the location that holds the show's downloads (or would take a new one) cannot be used now. */
    internal fun showLocationIsAway(parentMetaId: String): Boolean {
        ensureLoaded()
        val locationId = backend.placementFor(_uiState.value.items, parentMetaId, true) ?: return false
        return !backend.locationConnected(locationId)
    }

    /** Starts again the downloads that waited for a location or a move, now that they can run. */
    internal fun resumeHeldByFolder(): Unit = synchronized(stateLock) {
        if (!hasLoaded) return
        _uiState.value.items
            .filter { it.status == DownloadStatus.Paused && it.pausedByFolder && !backend.heldByFolder(it) }
            .forEach { resumeDownloadLocked(it.id) }
    }

    internal fun nextDownloadId(nowEpochMs: Long): String {
        val ordinal = synchronized(stateLock) {
            nextDownloadOrdinal += 1L
            nextDownloadOrdinal
        }
        return buildString {
            append(nowEpochMs.toString(36))
            append('_')
            append(ordinal.toString(36))
        }
    }

    private fun normalizeCompletedLocalFileUri(item: DownloadItem): DownloadItem {
        if (item.status != DownloadStatus.Completed) return item
        val found = backend.locate(item) as? FileLookup.Found ?: return item
        // The file may be where the record says it is not, after a move that was cut short: the record follows the file.
        val locationId = found.locationId ?: item.locationId
        return if (found.uri != item.localFileUri || locationId != item.locationId) {
            item.copy(localFileUri = found.uri, locationId = locationId)
        } else {
            item
        }
    }

    private fun DownloadItem.isVerifiedCompletedStatus(): Boolean =
        status == DownloadStatus.Completed && !isPlaceholder

    private fun DownloadItem.hasPlayableLocalFile(): Boolean =
        isVerifiedCompletedStatus() &&
            (backend.locate(this) as? FileLookup.Found)?.uri != null
}

private class ActiveTransfer {
    private var handle: DownloadsTaskHandle? = null
    private var cancelled = false

    /** Set by the platform downloader when the body matched a digest the server sent. */
    @Volatile
    var digestVerified = false

    /** Set once the platform downloader reported the file complete: it is being checked and must not be stopped. */
    @Volatile
    var finishing = false

    /** Set only once repository validation accepted the published file and completed its record. */
    @Volatile
    var completionAccepted = false

    /** A transfer cancelled before its handle is known is cancelled as soon as the handle arrives. */
    fun attach(handle: DownloadsTaskHandle) {
        this.handle = handle
        if (cancelled) handle.cancel()
    }

    fun cancel() {
        cancelled = true
        handle?.cancel()
    }

    /** Cancels and waits for the transfer to have stopped writing. True when there was nothing to wait for. */
    fun cancelAndWait(timeoutMs: Long): Boolean {
        cancelled = true
        return handle?.cancelAndWait(timeoutMs) ?: true
    }
}

internal interface DownloadsBackend {
    fun loadPayload(): String?
    fun loadPayload(profileId: Int): String? = loadPayload()
    fun savePayload(payload: String)
    fun savePayload(profileId: Int, payload: String) = savePayload(payload)

    /** The payload from before the last one that was saved. Null where no such copy is kept. */
    fun loadPreviousPayload(): String? = null
    fun loadPreviousPayload(profileId: Int): String? = loadPreviousPayload()

    /** Keeps a payload that could not be read, apart from the one [savePayload] writes. */
    fun saveUnreadablePayload(payload: String, atEpochMs: Long) = Unit
    fun saveUnreadablePayload(profileId: Int, payload: String, atEpochMs: Long) = saveUnreadablePayload(payload, atEpochMs)

    /** Removes a file the downloader just published that turned out bad. Only that file, where that can be proven. */
    fun removeJustPublished(localFileUri: String): Boolean = removeFile(localFileUri)

    /** Flushes the saved list (file and folder) to disk. True where there is nothing more to do. */
    fun syncPayload(): Boolean = true

    /** Whether another record of any profile uses [fileUri]. Null where only this list is known. */
    fun ownershipOf(item: DownloadItem, fileUri: String): FileOwnership? = null

    /** Gets rid of the file of a cancelled download: to the Trash where there is one, deleted where there is not. */
    fun discardFile(localFileUri: String?): Boolean = removeFile(localFileUri)

    /** Whether [loadFreshPayload] reads the stored list again (another copy of the app may have saved). */
    val rereadsPayload: Boolean get() = false

    /** The stored list as it is now. Null where it is not read again. */
    fun loadFreshPayload(): String? = null
    fun loadFreshPayload(profileId: Int): String? = loadFreshPayload()

    /** Runs [block] holding the lock every save of a list takes, across processes where there is one. */
    fun <T> withListLock(block: () -> T): T = block()
    fun restoreItem(item: DownloadItem): DownloadItem
    fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle
    fun removeFile(localFileUri: String?): Boolean
    fun removePartialFile(destinationFileName: String): Boolean
    fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String?

    /** Looks at the file of a download. Null where that cannot be done. */
    fun inspectFile(localFileUri: String): DownloadedFileFacts? = null

    /** Takes decoded frames of the file of a download. Slow: seconds. Null where that cannot be done. */
    fun sampleFrames(localFileUri: String): FrameSamples? = null

    /** Takes more frames of a file whose first frames looked blank or still. Slow. Null where that cannot be done. */
    fun sampleConfirmingFrames(localFileUri: String, durationMs: Long): FrameSamples? = null

    /** Moves a file out of the downloads folder without deleting it at once. True when it is out, or was gone. */
    fun trashFile(localFileUri: String): Boolean = false

    /** A reversible system-Trash move. Cancellation restores the original path before returning. */
    fun trashWatchedFile(localFileUri: String, stillWanted: () -> Boolean): AutoDownloadRemovalResult =
        AutoDownloadRemovalResult.NotMoved

    /** True when [trashFile] puts files in the trash of the system, false when it only holds them for days. */
    val usesSystemTrash: Boolean get() = true

    /** Whether two addresses name the same file, however they are spelled. */
    fun isSameFile(firstFileUri: String, secondFileUri: String): Boolean =
        firstFileUri.trim().removePrefix("file:").trimStart('/') == secondFileUri.trim().removePrefix("file:").trimStart('/')

    /** Looks for the file of a download, telling a file that is gone from one whose location is away. */
    fun locate(item: DownloadItem): FileLookup =
        resolveLocalFileUri(item.localFileUri, item.fileName)?.let { FileLookup.Found(it, item.locationId) } ?: FileLookup.Missing

    /** The download cannot run now: its location is away or a move is carrying it. */
    fun heldByFolder(item: DownloadItem): Boolean = false

    /** Whether the location of the item can be used now. */
    fun isConnected(item: DownloadItem): Boolean = true

    /** Whether the location can be used now. */
    fun locationConnected(locationId: String?): Boolean = true

    /** Removes the partial file of the item, from its own location only. */
    fun removePartialOf(item: DownloadItem): Boolean = removePartialFile(item.fileName)

    /** Removes only the active partial file of the item, leaving any superseded partials intact. */
    fun removeActivePartialOf(item: DownloadItem): Boolean =
        if (item.supersededPartials.isEmpty()) removePartialOf(item)
        else removePartialOf(item.copy(supersededPartials = emptyList()))

    /** The location a new download goes to. Null where there are no locations. */
    fun placementFor(items: List<DownloadItem>, parentMetaId: String, isEpisode: Boolean): String? = null

    /** Whether this backend can scan storage locations on disk for missing downloads. */
    val supportsDiskScan: Boolean get() = false

    /** Scans every available storage location for completed downloads whose records are missing. */
    fun scanDownloadsOnDisk(
        activeProfileId: Int,
        inMemoryItems: List<DownloadItem>,
        nextDownloadId: (Long) -> String,
        nowEpochMs: Long,
    ): DiskScanResult = DiskScanResult()
}

/** The stored lists of downloads, one per profile, as the move reads and saves them. */
internal interface ProfileLists {
    fun load(profileId: Int): String?

    /** Saves the list of [profileId] and flushes the file and its folder. True only when every flush succeeded. */
    fun saveDurably(profileId: Int, payload: String): Boolean
}

internal enum class RelocateResult {
    /** The record points at the new place and that is on disk. */
    Confirmed,
    RecordGone,

    /** The record no longer is the one the move planned for. */
    RecordChanged,

    /** The profile's list cannot be read. */
    ListUnreadable,

    /** The save, or one of its flushes, did not succeed or could not be confirmed. */
    NotDurable,
}

internal enum class DownloadDeleteResult {
    Removed,

    /** The download was already gone. */
    Gone,

    /** The file could not be moved to the trash. Nothing was removed. */
    NotMoved,

    /** The file is on a location that is not connected. Nothing was removed. */
    NotReachable,

    /** A move has the item. Nothing was removed. */
    Moving,
}

internal enum class AutoDownloadRemovalResult {
    Removed,

    /** The download is gone, is no longer finished, or no longer belongs to the rule. Nothing was touched. */
    Changed,

    /** The file could not be moved out of the downloads folder. Nothing was removed. */
    NotMoved,

    /** The file is on a location that is not connected. Nothing was touched. */
    NotReachable,

    /** A move has the item. Nothing was touched. */
    Busy,
}

private object PlatformDownloadsBackend : DownloadsBackend {
    override fun loadPayload(): String? = DownloadsStorage.loadPayload()

    override fun loadPayload(profileId: Int): String? = DownloadsStorage.loadPayload(profileId)

    override fun savePayload(payload: String) = DownloadsStorage.savePayload(payload)

    override fun savePayload(profileId: Int, payload: String) = DownloadsStorage.savePayload(profileId, payload)

    override fun loadPreviousPayload(): String? = DownloadsStorage.loadPreviousPayload()

    override fun loadPreviousPayload(profileId: Int): String? = DownloadsStorage.loadPreviousPayload(profileId)

    override fun saveUnreadablePayload(payload: String, atEpochMs: Long) =
        DownloadsStorage.saveUnreadablePayload(payload, atEpochMs)

    override fun saveUnreadablePayload(profileId: Int, payload: String, atEpochMs: Long) =
        DownloadsStorage.saveUnreadablePayload(profileId, payload, atEpochMs)

    override val rereadsPayload: Boolean get() = DownloadsStorage.rereadsPayload

    override fun syncPayload(): Boolean = DownloadsStorage.syncPayload()

    override fun removeJustPublished(localFileUri: String): Boolean =
        DownloadFolders.controller.removeJustPublished(localFileUri) ?: removeFile(localFileUri)

    override fun ownershipOf(item: DownloadItem, fileUri: String): FileOwnership? =
        DownloadFolders.controller.ownershipOf(item, fileUri)

    override fun discardFile(localFileUri: String?): Boolean =
        localFileUri?.let { DownloadFolders.controller.discardFile(it) } ?: removeFile(localFileUri)

    override fun loadFreshPayload(): String? = DownloadsStorage.loadFreshPayload()

    override fun loadFreshPayload(profileId: Int): String? = DownloadsStorage.loadFreshPayload(profileId)

    override fun <T> withListLock(block: () -> T): T = DownloadsStorage.withListLock(block)

    override fun restoreItem(item: DownloadItem): DownloadItem = DownloadsPlatformDownloader.restoreItem(item)

    override fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle = DownloadsPlatformDownloader.start(request, onProgress, onSuccess, onFailure, onPaused)

    override fun removeFile(localFileUri: String?): Boolean = DownloadsPlatformDownloader.removeFile(localFileUri)

    override fun removePartialFile(destinationFileName: String): Boolean =
        DownloadsPlatformDownloader.removePartialFile(destinationFileName)

    override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? =
        DownloadsPlatformDownloader.resolveLocalFileUri(localFileUri, destinationFileName)

    override fun inspectFile(localFileUri: String): DownloadedFileFacts? = inspectDownloadedFile(localFileUri)

    override fun sampleFrames(localFileUri: String): FrameSamples? = sampleDownloadedFrames(localFileUri)

    override fun sampleConfirmingFrames(localFileUri: String, durationMs: Long): FrameSamples? =
        sampleConfirmingDownloadedFrames(localFileUri, durationMs)

    override fun trashFile(localFileUri: String): Boolean = DownloadsTrash.moveAway(localFileUri)

    override fun trashWatchedFile(localFileUri: String, stillWanted: () -> Boolean): AutoDownloadRemovalResult =
        DownloadsTrash.moveWatchedAway(localFileUri, stillWanted)

    override val usesSystemTrash: Boolean get() = DownloadsTrash.usesSystemTrash

    override fun isSameFile(firstFileUri: String, secondFileUri: String): Boolean =
        DownloadsTrash.isSameFile(firstFileUri, secondFileUri)

    override fun locate(item: DownloadItem): FileLookup =
        DownloadFolders.controller.lookup(item)
            ?: resolveLocalFileUri(item.localFileUri, item.fileName)?.let { FileLookup.Found(it, item.locationId) }
            ?: FileLookup.Missing

    override fun heldByFolder(item: DownloadItem): Boolean = DownloadFolders.controller.heldByFolder(item)

    override fun isConnected(item: DownloadItem): Boolean = DownloadFolders.controller.isConnected(item)

    override fun locationConnected(locationId: String?): Boolean = DownloadFolders.controller.locationConnected(locationId)

    override fun removePartialOf(item: DownloadItem): Boolean =
        DownloadFolders.controller.removePartialOf(item) ?: DownloadsPlatformDownloader.retirePartial(item)

    override fun removeActivePartialOf(item: DownloadItem): Boolean =
        DownloadFolders.controller.removeActivePartialOf(item)
            ?: DownloadsPlatformDownloader.retirePartial(item.copy(supersededPartials = emptyList()))

    override fun placementFor(items: List<DownloadItem>, parentMetaId: String, isEpisode: Boolean): String? =
        DownloadFolders.controller.placementFor(items, parentMetaId, isEpisode)

    override val supportsDiskScan: Boolean get() = DownloadFolders.controller.supported

    override fun scanDownloadsOnDisk(
        activeProfileId: Int,
        inMemoryItems: List<DownloadItem>,
        nextDownloadId: (Long) -> String,
        nowEpochMs: Long,
    ): DiskScanResult = DownloadFolders.controller.scanDownloadsOnDisk(
        activeProfileId = activeProfileId,
        inMemoryItems = inMemoryItems,
        nextDownloadId = nextDownloadId,
        nowEpochMs = nowEpochMs,
    )
}

/**
 * Reads and writes the stored list of downloads so that no build of the app destroys what another
 * build wrote.
 *
 * Reading: every item is read on its own. Fields this build does not know are ignored, a value it
 * does not know for a field that has a default becomes that default, and an item that still cannot
 * be read is set aside without affecting the others. Writing: the fields an item was stored with
 * that this build does not know are written back with it, and the items it could not read are
 * written back as they were.
 */
/** The records of a stored list, or null when the list, or any record in it, cannot be read. */
internal fun decodeStoredDownloads(payload: String): List<DownloadItem>? {
    if (payload.isBlank()) return emptyList()
    val decoded = DownloadsCodec.decode(payload) ?: return null
    return decoded.items.takeIf { decoded.unreadableItems.isEmpty() }
}

internal object DownloadsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    /** One readable record: as it was stored, and as this build writes what it read from it. */
    class StoredRecord(val stored: JsonObject, val readAs: JsonObject)

    class Decoded(
        val items: List<DownloadItem>,
        /** Everything in the payload beside the list itself. */
        val root: JsonObject,
        /** Every readable record, by download id. */
        val records: Map<String, StoredRecord>,
        val unreadableItems: List<JsonElement>,
    )

    /**
     * Null when the payload as a whole is not a list of downloads this app could have written: not
     * an object, or an object with content but without a list of items.
     */
    fun decode(payload: String): Decoded? {
        val root = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject ?: return null
        val stored = when (val items = root["items"]) {
            null -> if (root.isEmpty()) emptyList() else return null
            is JsonArray -> items
            else -> return null
        }
        val items = mutableListOf<DownloadItem>()
        val records = mutableMapOf<String, StoredRecord>()
        val unreadable = mutableListOf<JsonElement>()
        stored.forEach { element ->
            val item = runCatching { json.decodeFromJsonElement<DownloadItem>(element.withCurrentAutoDownloadFields()) }.getOrNull()
            if (item == null || item.id in records) {
                unreadable += element
            } else {
                items += item
                records[item.id] = StoredRecord(element as JsonObject, json.encodeToJsonElement(item) as JsonObject)
            }
        }
        return Decoded(items, root, records, unreadable)
    }

    /**
     * A record of an automatic download as an earlier build wrote it: the rule in `ruleKey` beside
     * `origin` AUTO. It is read as the same download with its rule in the field used now. The
     * earlier fields stay in the stored record, so that build still reads what this one writes.
     */
    private fun JsonElement.withCurrentAutoDownloadFields(): JsonElement {
        val record = this as? JsonObject ?: return this
        if ("autoDownloadRuleKey" in record) return this
        val origin = (record["origin"] as? JsonPrimitive)?.contentOrNull
        val ruleKey = (record["ruleKey"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        if (origin != "AUTO" || ruleKey == null) return this
        return JsonObject(record + ("autoDownloadRuleKey" to JsonPrimitive(ruleKey)))
    }

    /**
     * [items] as a payload. A field of a record is written only when this build changed it since
     * the record was read; otherwise the stored value stays, also one this build read as a default
     * because it did not know it. A record that could not be read because its id was taken is
     * dropped once the download with that id is gone, so it does not come back in its place.
     */
    /** The revision a stored list carries; 0 for one written without it. */
    fun revisionOf(payload: String): Long =
        ((runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject)?.get("revision") as? JsonPrimitive)
            ?.contentOrNull?.toLongOrNull() ?: 0L

    /**
     * This copy's changes since [base], put onto the newer [stored] list: a record this copy removed goes,
     * one it added is added, and in a record both have, each field this copy changed takes this copy's
     * value while every other field keeps the stored one. Records added or changed elsewhere are kept.
     */
    fun merge(stored: List<DownloadItem>, base: List<DownloadItem>, ours: List<DownloadItem>): List<DownloadItem> {
        val baseById = base.associateBy { it.id }
        val oursById = ours.associateBy { it.id }
        val storedIds = stored.mapTo(mutableSetOf()) { it.id }
        val merged = stored.mapNotNull { s ->
            val b = baseById[s.id]
            val o = oursById[s.id]
            when {
                o == null -> if (b != null) null else s
                b == null || o == b -> s
                else -> mergeFields(s, b, o)
            }
        }
        return merged + ours.filter { it.id !in baseById && it.id !in storedIds }
    }

    private fun mergeFields(stored: DownloadItem, base: DownloadItem, ours: DownloadItem): DownloadItem {
        val s = json.encodeToJsonElement(stored) as JsonObject
        val b = json.encodeToJsonElement(base) as JsonObject
        val o = json.encodeToJsonElement(ours) as JsonObject
        val fields = (s.keys + o.keys).associateWith { key -> if (o[key] != b[key]) o[key] else s[key] }
            .filterValues { it != null }.mapValues { it.value!! }
        return json.decodeFromJsonElement(DownloadItem.serializer(), JsonObject(fields))
    }

    fun encode(items: Collection<DownloadItem>, loaded: Decoded?, revision: Long? = null): String {
        val ids = items.mapTo(mutableSetOf()) { it.id }
        val written = items.map { item ->
            val fields = json.encodeToJsonElement(item) as JsonObject
            val record = loaded?.records?.get(item.id) ?: return@map fields
            JsonObject(record.stored + fields.filter { (name, value) -> name !in record.stored || record.readAs[name] != value })
        }
        val unreadable = loaded?.unreadableItems.orEmpty().filterNot { element ->
            val id = ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
            id != null && id in loaded?.records.orEmpty() && id !in ids
        }
        val root = loaded?.root.orEmpty() + ("items" to JsonArray(written + unreadable))
        return JsonObject(if (revision != null) root + ("revision" to JsonPrimitive(revision)) else root).toString()
    }
}

private fun buildLogicalKey(
    parentMetaId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
): String = if (seasonNumber != null && episodeNumber != null) {
    "${parentMetaId.trim()}|$seasonNumber|$episodeNumber"
} else {
    "${parentMetaId.trim()}|movie"
}

private fun buildFileName(
    title: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    fallbackTitle: String,
    sourceUrl: String,
    downloadId: String,
): String {
    val baseTitle = if (seasonNumber != null && episodeNumber != null) {
        buildString {
            append(title)
            append(" S")
            append(seasonNumber.toString().padStart(2, '0'))
            append('E')
            append(episodeNumber.toString().padStart(2, '0'))
            if (!episodeTitle.isNullOrBlank()) {
                append(' ')
                append(episodeTitle)
            }
        }
    } else {
        title.ifBlank { fallbackTitle }
    }

    val extension = sourceUrl.fileExtensionFromUrl()
    return buildString {
        append(baseTitle.sanitizeFileName().ifBlank { "download" }.take(92))
        append('_')
        append(downloadId)
        append('.')
        append(extension)
    }
}

private fun String.sanitizeFileName(): String =
    trim().replace(Regex("[^A-Za-z0-9._ -]"), "_")

private fun String.fileExtensionFromUrl(): String {
    val withoutQuery = substringBefore('?').substringBefore('#')
    val suffix = withoutQuery.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .trim()

    return if (suffix.length in 2..5 && suffix.all { it.isLetterOrDigit() }) {
        suffix
    } else {
        "mp4"
    }
}

/** A file address without its scheme and leading slashes, so file:/a and file:///a compare equal. */
private fun String.localFilePath(): String = trim().removePrefix("file:").trimStart('/')

internal fun String.isSupportedDownloadUrl(): Boolean {
    val normalized = trim().lowercase()
    if (normalized.startsWith("magnet:")) return false
    if (normalized.endsWith(".m3u8") || normalized.contains(".m3u8?")) return false
    if (normalized.endsWith(".mpd") || normalized.contains(".mpd?")) return false
    if (normalized.endsWith(".torrent") || normalized.contains(".torrent?")) return false
    return normalized.startsWith("http://") || normalized.startsWith("https://")
}
