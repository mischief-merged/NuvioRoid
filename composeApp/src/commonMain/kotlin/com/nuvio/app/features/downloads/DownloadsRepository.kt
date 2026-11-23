package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.SubtitleRequestExtras
import com.nuvio.app.features.player.looksLikePlaceholder
import com.nuvio.app.features.player.addonSubtitleRequests
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.sourceLabelSaysNotCached
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
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

    internal val completedItems = uiState.map { it.completedItems }.distinctUntilChanged()

    private val _hasUnseenCompleted = MutableStateFlow(false)
    val hasUnseenCompleted: StateFlow<Boolean> = _hasUnseenCompleted.asStateFlow()

    private val _catalogReady = MutableStateFlow(false)

    /** True once a stored list has been published. An offline launch waits for this, so it does not decide before the list exists. */
    internal val catalogReady: StateFlow<Boolean> = _catalogReady.asStateFlow()

    /**
     * When this returns true, [ensureLoadedForDisplay] leaves the list for the warm-up instead of reading it here.
     * The desktop sets it for the interface thread, so the first screen is not held up by the decode.
     * A click still waits for the list: acting on the empty one would overwrite what is stored.
     */
    @Volatile
    internal var deferLoadOnThisThread: () -> Boolean = { false }

    /** Runs before the list is read. The desktop waits here until a cut-short folder move has been settled. */
    @Volatile
    internal var beforeCatalogLoad: () -> Unit = {}

    /** Runs on the thread that is about to read the list. */
    @Volatile
    internal var onCatalogLoad: () -> Unit = {}

    // The UI, downloader callbacks and background workers reach this object from different threads.
    // Every read-modify-write of the item list, and every use of activeTransfers, holds this lock.
    //
    // LOCK ORDER, everywhere: loadLock, then the per-video lock (SidecarLocks), then this state lock.
    // loadLock covers one catalog read and the disk scan after it, and a profile change. Nothing takes
    // loadLock while it already holds the state lock or a per-video lock. The state lock is not held
    // while that scan walks the disk, and a manual scan does not take loadLock, so a pause or a profile
    // change can run during the walk. An action that may have to load the list calls ensureLoaded before
    // it takes the state lock. A caller that already holds the state lock resumes through
    // resumeDownloadLocked and does not load. Code that holds the state lock never waits for a per-video
    // lock. The guard behind every sidecar write (videoFileIsThere) reads the disk through the backend
    // only, so it does not take the state lock. Both delete paths load the list first, then take the
    // per-video lock, then the state lock.
    private val loadLock = SynchronizedObject()
    private val stateLock = SynchronizedObject()

    // One frame look at a time, including its confirming frames, across finishes and rechecks.
    private val frameProbePermit = Semaphore(1)
    private val checkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkingTransfers = mutableMapOf<String, ActiveTransfer>()

    private class FrameLook(val samples: FrameSamples?, val verdict: FrameVerdict?)

    private fun lookAtFrames(uri: String, transfer: ActiveTransfer? = null): FrameLook =
        runBlocking(transfer?.checkJob ?: EmptyCoroutineContext) {
            frameProbePermit.withPermit {
                ensureActive()
                val samples = runCatching { backend.sampleFrames(uri) }.getOrNull()
                ensureActive()
                val verdict = samples?.let { first ->
                    runCatching { confirmedFramesProblem(first) { backend.sampleConfirmingFrames(uri, first.durationMs) } }.getOrNull()
                }
                FrameLook(samples, verdict)
            }
        }

    private fun clearCheck(downloadId: String, transfer: ActiveTransfer? = null) {
        if (transfer == null || checkingTransfers[downloadId] === transfer) {
            checkingTransfers.remove(downloadId)
            _uiState.update { it.copy(checkingIds = it.checkingIds - downloadId) }
        }
    }

    private fun removeTransfer(downloadId: String): ActiveTransfer? {
        clearCheck(downloadId)
        return activeTransfers.remove(downloadId)
    }

    private fun needsFrames(item: DownloadItem, facts: DownloadedFileFacts, runtime: Int?, verdict: PlaceholderVerdict): Boolean =
        verdict !is PlaceholderVerdict.NotTheRealVideo && runtime != null && item.framesCheck != DownloadFramesCheck.Passed &&
            (facts.durationMs == null || looksLikePlaceholder(facts.durationMs, runtime))

    // The transfer that currently owns each download. A transfer that was paused, replaced or removed
    // is no longer in this map, so whatever it reports afterwards is ignored.
    private val activeTransfers = mutableMapOf<String, ActiveTransfer>()
    @Volatile
    private var finishingSteps = DownloadFinishingSteps()

    /** Stops new publications and gives work already publishing or verifying a bounded time to save. */
    internal suspend fun awaitFinishingDownloads(timeoutMs: Long = 10_000L): Boolean =
        finishingSteps.awaitForQuit(timeoutMs)
    @kotlin.concurrent.Volatile
    private var hasLoaded = false

    /** The thread currently inside [loadFromDisk]. A call it makes back must not start another read or wait again. */
    @Volatile
    private var catalogLoadingThread: Thread? = null

    // Where items are persisted and transfers and files are handled. Always the platform in the app.
    internal var backend: DownloadsBackend = PlatformDownloadsBackend

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

    internal var progressScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var progressJob: Job? = null
    private val progressWrites = Mutex()
    private val pendingProgress = mutableMapOf<Int, MutableMap<String, DownloadItem>>()

    private fun scheduleProgressSave() {
        if (progressJob != null) return
        progressJob = progressScope.launch {
            while (true) {
                delay(5_000)
                flushProgress()
                synchronized(stateLock) {
                    if (progressJob !== coroutineContext[Job]) return@launch
                    if (pendingProgress.isEmpty()) {
                        progressJob = null
                        return@launch
                    }
                }
            }
        }
    }

    /** Takes a snapshot under the state lock; storage waits never hold that lock. */
    internal suspend fun flushProgress(): Boolean = progressWrites.withLock {
        val snapshots = synchronized(stateLock) {
            pendingProgress.map { (profile, items) -> Triple(profile, items.toMap(), backend) }
        }
        var saved = true
        for ((profile, items, store) in snapshots) {
            val success = runCatching { store.saveProgress(profile, items.values.toList()) }.getOrDefault(false)
            saved = saved && success
            if (success) synchronized(stateLock) {
                val pending = pendingProgress[profile]
                items.forEach { (id, item) -> if (pending?.get(id) == item) pending.remove(id) }
                if (pending.isNullOrEmpty()) pendingProgress.remove(profile)
            }
        }
        saved
    }

    /** Stops byte callbacks before the final checkpoint, keeping running records resumable. */
    internal suspend fun prepareForQuit(): Boolean {
        val stopped = synchronized(stateLock) {
            activeTransfers.filterValues { !it.finishing }.toMap().also { transfers ->
                transfers.keys.forEach { activeTransfers.remove(it) }
            }.values
        }
        stopped.forEach(ActiveTransfer::cancel)
        return flushProgress()
    }

    fun ensureLoaded() {
        if (_catalogReady.value) return
        // The read calls back into here (a resumed transfer, a connection hold). Waiting again would stick.
        if (Thread.currentThread() === catalogLoadingThread) return
        loadAfterRecovery()
    }

    /**
     * The screen asks for the list while it is being drawn. Reading it there blocks that frame, so this
     * returns and the warm-up reads it instead. Anything that changes the list or decides playback uses
     * [ensureLoaded] and waits until the list has been published.
     */
    internal fun ensureLoadedForDisplay() {
        if (_catalogReady.value) return
        if (deferLoadOnThisThread()) return
        loadAfterRecovery()
    }

    fun onProfileChanged() {
        beforeCatalogLoad()
        synchronized(loadLock) {
            // A read already on this thread must not start another, or the two would wait on each other.
            if (Thread.currentThread() === catalogLoadingThread) return
            loadFromDisk()
        }
    }

    private fun loadAfterRecovery() {
        beforeCatalogLoad()
        synchronized(loadLock) {
            if (_catalogReady.value || Thread.currentThread() === catalogLoadingThread) return
            loadFromDisk()
        }
    }

    fun markCompletedSeen() {
        _hasUnseenCompleted.value = false
    }

    fun clearLocalState(): Unit = synchronized(stateLock) {
        val transfers = activeTransfers.values.toList()
        activeTransfers.clear()
        checkingTransfers.clear()
        finishingSteps = DownloadFinishingSteps()
        transfers.forEach(ActiveTransfer::cancel)
        progressJob?.cancel()
        progressJob = null
        pendingProgress.clear()
        hasLoaded = false
        catalogLoadingThread = null
        loadedProfileId = null
        baseline = emptyList()
        baseRevision = 0L
        _hasUnseenCompleted.value = false
        _catalogReady.value = false
        _uiState.value = DownloadsUiState()
        notifyLiveStatusPlatform()
    }

    fun findPlayableDownloadByVideoId(videoId: String?): DownloadItem? {
        ensureLoaded()
        val normalizedVideoId = videoId?.trim().orEmpty()
        if (normalizedVideoId.isBlank()) return null
        return _uiState.value.items.firstOrNull { item ->
            item.videoId == normalizedVideoId && item.hasPlayableLocalFile()
        }
    }

    fun findPlayableDownload(
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String? = null,
    ): DownloadItem? {
        ensureLoaded()
        val items = _uiState.value.items
        val normalizedParentMetaId = parentMetaId.trim()

        findPlayableDownloadByVideoId(videoId)?.let { return it }

        return if (seasonNumber != null && episodeNumber != null) {
            items.firstOrNull { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == seasonNumber &&
                    item.episodeNumber == episodeNumber &&
                    item.hasPlayableLocalFile()
            }
        } else {
            items.firstOrNull { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == null &&
                    item.episodeNumber == null &&
                    item.hasPlayableLocalFile()
            }
        }
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
        return if (item.status == DownloadStatus.Completed || item.localFileUri != null) backend.resolveLocalFileUri(item.localFileUri, item.fileName) else null
    }

    fun playableLocalFileUri(item: DownloadItem): String? {
        ensureLoaded()
        if (item.status != DownloadStatus.Completed) return null
        val resolvedUri = backend.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            destinationFileName = item.fileName,
        ) ?: return null

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
    ): DownloadEnqueueResult {
        ensureLoaded()
        return synchronized(stateLock) {
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

            var replacedExisting = false
            val currentItems = _uiState.value.items.toMutableList()
            // The sources this title had, and how many the app started by itself, go on with the new download.
            val history = sourceHistoryFor(currentItems.filter { it.logicalContentKey == logicalKey }, stream, userChoseReplacement)
            // What is replaced: a download that has no finished file. A finished file, marked as not the
            // real video or not, is a stored file of the user: it is replaced only when the user chose
            // to ([userChoseReplacement]), and then a marked one goes to the trash. Anything automatic
            // leaves it and its record where they are and fetches the new copy beside it, under a name
            // and a record of its own.
            val replaced = currentItems.filter { item ->
                item.logicalContentKey == logicalKey && (userChoseReplacement || item.status != DownloadStatus.Completed) &&
                    !(item.awaitingCheck && item.localFileUri != null)
            }
            replaced.forEach { existing ->
                replacedExisting = true
                removeTransfer(existing.id)?.cancel()
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
                sourceHeaders = sanitizeRequestHeaders(stream.behaviorHints.proxyHeaders?.request),
                sourceResponseHeaders = sanitizeResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
                subtitleRequests = addonSubtitleRequests(
                    type = contentType,
                    videoId = videoId,
                    extras = SubtitleRequestExtras.from(stream.behaviorHints, sourceUrl),
                ),
                sourceSubtitles = stream.externalSubtitles,
                localFileUri = null,
                fileName = fileName,
                // A show stays where its downloaded episodes are; a movie goes to the default location.
                locationId = backend.placementFor(currentItems, parentMetaId, seasonNumber != null && episodeNumber != null),
                status = DownloadStatus.Downloading,
                downloadedBytes = 0L,
                totalBytes = null,
                errorMessage = null,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
                expectedVideoHash = stream.behaviorHints.videoHash.asVideoHash(),
                autoDownloadRuleKey = autoDownloadRuleKey,
                triedSources = history.triedSources,
                autoSourceAttempts = history.autoSourceAttempts,
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
                    // Each source has its own destination. Leave the previous partial file for the user.
                }
            }
            startDownload(queued)

            if (replacedExisting) {
                DownloadEnqueueResult.Replaced
            } else {
                DownloadEnqueueResult.Started
            }
        }
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

            removeTransfer(downloadId)?.cancel()
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

    fun resumePausedDownloads() {
        ensureLoaded()
        synchronized(stateLock) {
            _uiState.value.items
                .filter { it.status == DownloadStatus.Paused && !it.pausedByFolder && !it.pausedByConnection }
                .map { it.id }
                .forEach(::resumeDownloadLocked)
        }
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
            removeTransfer(downloadId).also {
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
        synchronized(stateLock) { resumeDownloadLocked(downloadId) }
    }

    /** The caller already holds the state lock. This does not load the list and does not take that lock. */
    private fun resumeDownloadLocked(downloadId: String) {
        val item = _uiState.value.items.firstOrNull { it.id == downloadId }?.let(::normalizeCompletedLocalFileUri) ?: return
        if (item.status != DownloadStatus.Paused && item.status != DownloadStatus.Failed) return

        val reset = item.copy(
            status = DownloadStatus.Downloading,
            errorMessage = null,
            autoDownloadStop = null,
            failureKind = null,
            localFileUri = item.localFileUri.takeIf { item.awaitingCheck },
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
            val failed = _uiState.value.items.filter { it.status == DownloadStatus.Failed && it.localFileUri == null && backend.isConnected(it) }
                .filter { ItemLocks.tryAcquire(it.id) }
            if (failed.isEmpty()) return 0
            try {
                failed.forEach { item ->
                    removeTransfer(item.id)?.cancel()
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
        removeTransfer(downloadId)?.cancel()
        val restored = backend.restoreItem(item)
        replaceItem(restored)
        persist()
        if (restored.status == DownloadStatus.Downloading) startDownload(restored)
    }

    /**
     * Rejects a batch source without removing its record or anything it wrote. Its history is still
     * on disk if the app stops before the next source is queued. Finished files stay beside the
     * replacement; unfinished transfers stop, leaving their partial files intact. A folder hold
     * protects those files, but must not prevent recording the rejection or stopping the transfer.
     */
    internal fun rejectBatchSource(downloadId: String) {
        ensureLoaded()
        synchronized(stateLock) {
            val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
            if (item.status != DownloadStatus.Completed && item.status != DownloadStatus.Downloading) return
            removeTransfer(downloadId)?.cancel()
            mutateItem(downloadId) { current ->
                current.copy(
                    status = if (current.status == DownloadStatus.Completed) DownloadStatus.Completed else DownloadStatus.Failed,
                    failureKind = DownloadFailureKind.BadFile,
                    errorMessage = current.errorMessage ?: runBlocking { getString(Res.string.downloads_source_rejected) },
                    updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                )
            }
        }
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
                    removeTransfer(downloadId)?.cancel()
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
    internal fun deleteDownload(downloadId: String): DownloadDeleteResult {
        ensureLoaded()
        // LOCK ORDER (see stateLock): per-video lock, then the state lock, then the list lock.
        // If the file moved between the look and the locks, look again.
        repeat(LockAttempts) {
            val locked = finishedFileUriOf(downloadId)
            val result = if (locked != null) {
                SidecarLocks.withLock(locked) { deleteWithLocksHeld(downloadId, locked) }
            } else {
                deleteWithLocksHeld(downloadId, null)
            }
            if (result != null) return result
        }
        return DownloadDeleteResult.NotMoved
    }

    /** Null when the file is no longer where [lockedUri] said: the caller starts again with the right lock. */
    private fun deleteWithLocksHeld(downloadId: String, lockedUri: String?): DownloadDeleteResult? = synchronized(stateLock) {
        val items = _uiState.value.items
        val item = items.firstOrNull { it.id == downloadId } ?: return DownloadDeleteResult.Gone
        val current = if (item.status == DownloadStatus.Completed || item.localFileUri != null) backend.resolveLocalFileUri(item.localFileUri, item.fileName) else null
        if (current != lockedUri) return null
        // Whatever the status, an item whose location is away is not touched: not its partial file, not its record.
        if (!backend.isConnected(item)) return DownloadDeleteResult.NotReachable
        // One thing changes an item at a time: a move has it, or something else is deleting it.
        if (!ItemLocks.tryAcquire(downloadId)) return DownloadDeleteResult.Moving
        try {
            // The ownership check, the file and the record change under the lock every save of a list takes.
            return backend.withListLock { deleteLocked(item, items) }
        } finally {
            ItemLocks.release(downloadId)
        }
    }

    private fun deleteLocked(item: DownloadItem, items: List<DownloadItem>): DownloadDeleteResult {
        val downloadId = item.id
        val lookup = if (item.status == DownloadStatus.Completed || item.localFileUri != null) backend.locate(item) else FileLookup.Missing
        // A file whose location is away is not gone. Nothing is touched, and the entry stays.
        if (lookup is FileLookup.Unreachable) return DownloadDeleteResult.NotReachable
        val fileUri = (lookup as? FileLookup.Found)?.uri
        if (fileUri != null) {
            val usedByAnother = usedByAnother(item, items, fileUri) ?: return DownloadDeleteResult.NotMoved
            removeTransfer(downloadId)?.cancel()
            // The record goes first, durably; then the file. A failure after that leaves a file, never a dangling record.
            val retained = if (item.awaitingCheck && item.status == DownloadStatus.Downloading) {
                item.copy(status = DownloadStatus.Paused, pausedByFolder = false, pausedByConnection = false)
            } else item
            if (!removeRecordDurably(retained)) return DownloadDeleteResult.NotMoved
            if (!usedByAnother) {
                val gone = backend.trashFile(fileUri)
                if (!gone) {
                    restoreRecord(retained)
                    return DownloadDeleteResult.NotMoved
                }
            }
            if (!item.awaitingCheck) backend.removePartialOf(item)
            return DownloadDeleteResult.Removed
        }
        removeTransfer(downloadId)?.cancel()
        if (!removeRecordDurably(item)) return DownloadDeleteResult.NotMoved
        if (!item.awaitingCheck) backend.removePartialOf(item)
        return DownloadDeleteResult.Removed
    }

    /** Whether deleted downloads go to the system Trash. When they do not, the files stay in the holding folder. */
    internal val usesSystemTrash: Boolean get() = backend.usesSystemTrash

    private fun loadFromDisk() {
        // The caller holds loadLock. The read holds the state lock; the scan below does not, so a pause
        // can run once the list is published. A re-entry on this thread returns before any of that.
        val published = synchronized(stateLock) {
            if (Thread.currentThread() === catalogLoadingThread) return
            val uiBefore = _uiState.value
            val unseenBefore = _hasUnseenCompleted.value
            val resumedBefore = downloadsResumedAtLoad
            val transfersBefore = activeTransfers.toMap()
            catalogLoadingThread = Thread.currentThread()
            // Not ready until this attempt publishes, including a profile switch, so a pause during the
            // read waits and then sees the list this read found. The thread doing the read already returns
            // from ensureLoaded, so clearing this does not start the read again.
            _catalogReady.value = false
            var publishedForScan = false
            try {
                onCatalogLoad()
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
                    savingIsUnsafe = runCatching {
                        backend.saveUnreadablePayload(profileId, payload, DownloadsClock.nowEpochMs())
                    }.isFailure
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
                // An empty or unreadable list starts from nothing. Keeping the previous profile's baseline
                // would treat a matching id as a removal and drop a partial the other copy just saved.
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

                _uiState.value = DownloadsUiState(items = normalized, storeProblem = storeProblem)
                notifyLiveStatusPlatform()
                // Set once the list is the one just read, and before a hold or a resumed transfer looks at it.
                markCatalogLoaded()
                if (shouldPersistNormalized) {
                    persist()
                }
                // Stored transfers resume before discovery waits on any download volume.
                val resumed = _uiState.value.items.filter { it.status == DownloadStatus.Downloading && it.id !in activeTransfers }
                downloadsResumedAtLoad = downloadsResumedAtLoad + resumed.map { it.id }
                resumed.forEach(::startDownload)
                // Downloads that waited for a better connection when the app quit go on when it is no longer limited.
                applyConnectionHold()
                _catalogReady.value = true
                publishedForScan = true
                Triple(profileId, storeProblem, _uiState.value.items)
            } catch (failure: Exception) {
                undoFailedCatalogRead(uiBefore, unseenBefore, resumedBefore, transfersBefore)
                throw failure
            } finally {
                // A failed read must not leave this thread marked as the loader. A published read keeps
                // the mark until the scan below finishes, so a call back into here does not start another.
                if (!publishedForScan && catalogLoadingThread === Thread.currentThread()) catalogLoadingThread = null
            }
        }

        try {
            if (!backend.supportsDiskScan) return
            val scanResult = runCatching {
                backend.scanDownloadsOnDisk(
                    activeProfileId = published.first,
                    inMemoryItems = published.third,
                    nextDownloadId = ::nextDownloadId,
                    nowEpochMs = DownloadsClock.nowEpochMs(),
                )
            }.getOrDefault(DiskScanResult())
            // A scan that could not run (a move has the folders) must not replace what is already shown.
            if (scanResult.busy) return
            synchronized(stateLock) {
                if (!hasLoaded || loadedProfileId != published.first) return@synchronized
                val currentItems = _uiState.value.items
                val newRecovered = scanResult.recoveredItems.filterNot { candidate ->
                    currentItems.any { existing -> existing.sameRecoveredFileAs(candidate) }
                }
                _uiState.value = _uiState.value.copy(
                    items = currentItems + newRecovered,
                    storeProblem = published.second,
                    unrecognisedFiles = scanResult.unrecognisedFiles,
                )
                notifyLiveStatusPlatform()
                if (newRecovered.isNotEmpty()) persist()
            }
        } finally {
            synchronized(stateLock) {
                if (catalogLoadingThread === Thread.currentThread()) catalogLoadingThread = null
            }
        }
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
        // The walk is outside both locks: a pause or a profile change can run while it does.
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
            // The profile changed, or the list was cleared, while the walk ran. Those results belong to neither.
            if (!hasLoaded || loadedProfileId != profileId) {
                return@synchronized DiskScanSummary(0, _uiState.value.unrecognisedFiles.size)
            }
            val currentItems = _uiState.value.items
            val newRecovered = result.recoveredItems.filterNot { candidate ->
                currentItems.any { existing -> existing.sameRecoveredFileAs(candidate) }
            }
            _uiState.value = _uiState.value.copy(
                items = currentItems + newRecovered,
                unrecognisedFiles = result.unrecognisedFiles,
            )
            notifyLiveStatusPlatform()
            if (newRecovered.isNotEmpty()) persist()
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

    /** The list now in memory is the one just read. The profile was captured when that read began. */
    private fun markCatalogLoaded() {
        hasLoaded = true
    }

    /**
     * The read did not finish. Drop what this attempt published or started, and leave the ready flag
     * clear so the next caller reads again instead of treating a failed read as an empty list.
     */
    private fun undoFailedCatalogRead(
        uiBefore: DownloadsUiState,
        unseenBefore: Boolean,
        resumedBefore: Set<String>,
        transfersBefore: Map<String, ActiveTransfer>,
    ) {
        activeTransfers.filter { (id, transfer) -> transfersBefore[id] !== transfer }.keys.toList().forEach { id ->
            activeTransfers.remove(id)?.cancel()
        }
        hasLoaded = false
        catalogLoadingThread = null
        loadedProfileId = null
        loadedPayload = null
        baseline = emptyList()
        baseRevision = 0L
        savingIsUnsafe = false
        downloadsResumedAtLoad = resumedBefore
        _hasUnseenCompleted.value = unseenBefore
        _catalogReady.value = false
        if (_uiState.value !== uiBefore) {
            _uiState.value = uiBefore
            notifyLiveStatusPlatform()
        }
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
                removeTransfer(item.id)?.cancel()
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
        // An unavailable or moving folder is not a missing published file. Its address and bytes stay saved.
        if (item.awaitingCheck && backend.heldByFolder(item)) {
            mutateItem(item.id) { it.copy(
                status = DownloadStatus.Paused, pausedByFolder = true, errorMessage = null,
                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
            ) }
            return
        }
        // On a limited connection a download waits instead of starting, and goes on when that ends.
        // A file already on disk that only needs its check is not a transfer, so a hold does not stop it.
        if (!item.awaitingCheck && item.connectionHoldReason() != null) {
            mutateItem(item.id) { it.waitingForConnection() }
            return
        }
        val transfer = ActiveTransfer()
        val finishing = finishingSteps
        // Registered before the downloader is asked to start, so a report made before start returns
        // already finds this transfer.
        clearCheck(item.id)
        activeTransfers[item.id] = transfer
        if (item.awaitingCheck && item.localFileUri != null) {
            if (!finishing.begin(transfer)) {
                removeTransfer(item.id)
                return
            }
            transfer.digestVerified = item.publishedDigestVerified
            transfer.finishing = true
            checkScope.launch {
                try { finishTransfer(item, transfer, item.localFileUri, item.totalBytes ?: item.downloadedBytes) }
                finally { finishing.end(transfer) }
            }
            return
        }

        val handle = backend.start(
            DownloadPlatformRequest(
                item,
                onDigestVerified = { transfer.digestVerified = true },
                limits = autoTransferLimits(item, transfer),
                onPartialCreated = { key -> ifCurrent(item.id, transfer) { mutateItem(item.id) { it.copy(partialFileKey = key) } } },
                onFileNameChanged = { name -> ifCurrent(item.id, transfer) { mutateItem(item.id) { it.copy(fileName = name, partialFileKey = null) } } },
                onFinished = { finishing.end(transfer) },
                onPublished = { uri, size, key -> rememberPublished(item.id, transfer, uri, size, key) },
                // A connection hold or quit either came first, or sees this work finishing and leaves it.
                mayPublish = {
                    synchronized(stateLock) {
                        (activeTransfers[item.id] === transfer && finishing.begin(transfer)).also { if (it) transfer.finishing = true }
                    }
                },
            ),
            { downloadedBytes, totalBytes ->
                ifCurrent(item.id, transfer) {
                    if (failIfAnnouncedAsPlaceholder(item, totalBytes)) return@ifCurrent
                    mutateItem(item.id, progressOnly = true) { current ->
                        if (current.status != DownloadStatus.Downloading) {
                            current
                        } else {
                            current.copy(
                                downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                                totalBytes = totalBytes?.takeIf { it > 0L },
                                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
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
                        removeTransfer(item.id)
                        val current = _uiState.value.items.firstOrNull { it.id == item.id }
                        val kind = classifyDownloadFailure(message)
                        // A bad file, a refusal or a full disk comes again on every attempt.
                        val worthAnotherAttempt = kind == DownloadFailureKind.Other
                        if (current?.status == DownloadStatus.Downloading && attempt < MaxDownloadAttempts && worthAnotherAttempt && !finishing.isClosing) {
                            persist()
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
                        removeTransfer(item.id)
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

    private fun rememberPublished(downloadId: String, transfer: ActiveTransfer, uri: String, size: Long?, fileKey: String? = null): Boolean =
        synchronized(stateLock) {
            if (activeTransfers[downloadId] !== transfer) return false
            val current = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return false
            if (!current.awaitingCheck || current.localFileUri != uri) {
                replaceItem(current.copy(
                    localFileUri = uri, awaitingCheck = true, publishedDigestVerified = transfer.digestVerified, publishedFileKey = fileKey,
                    downloadedBytes = size?.takeIf { it > 0L } ?: current.downloadedBytes,
                ))
            }
            persist(durably = true)
        }

    /**
     * A transfer reported that its file is complete. The file is accepted only if its checks pass:
     * first the file's size and duration, then decoded frames only when they can settle the answer.
     * The frame look runs outside the state lock, while the transfer still owns the
     * download; the result is applied under the lock, and only if the transfer still owns it.
     */
    private fun finishTransfer(item: DownloadItem, transfer: ActiveTransfer, localFileUri: String, totalBytes: Long?) {
        transfer.finishing = true
        try {
            val saved = synchronized(stateLock) {
                if (activeTransfers[item.id] !== transfer) return
                val current = _uiState.value.items.firstOrNull { it.id == item.id } ?: return
                if (current.awaitingCheck && current.localFileUri == localFileUri) true
                else rememberPublished(item.id, transfer, localFileUri, totalBytes)
            }
            if (!saved) return
            finishCheckedTransfer(item, transfer, localFileUri, totalBytes)
        } catch (_: CancellationException) {
            // The saved final address stays available for the next check after a pause or quit.
        } finally {
            synchronized(stateLock) { clearCheck(item.id, transfer) }
        }
    }

    private fun finishCheckedTransfer(item: DownloadItem, transfer: ActiveTransfer, localFileUri: String, totalBytes: Long?) {
        // A download is finished only when its file is there, has the size the server
        // announced and is a video. Anything else is a failed download without a file.
        val inspection = synchronized(stateLock) {
            if (activeTransfers[item.id] !== transfer) return
            // Its network transfer is over. A season may use that slot while the file is checked.
            checkingTransfers[item.id] = transfer
            _uiState.update { it.copy(checkingIds = it.checkingIds + item.id) }
            val facts = backend.inspectFile(localFileUri)
            FileInspection(facts, facts?.problem(announcedBytes = totalBytes, expectedVideoHash = item.expectedVideoHash))
        }
        val facts = inspection.facts
        val problem = inspection.problem

        val runtime = runtimeMinutesOf(item)
        fun evidence(durationMs: Long?) = PlaceholderEvidence(
            fileBytes = facts?.sizeBytes,
            claimedBytes = item.claimedSizeBytes(runtime),
            durationMs = durationMs,
            runtimeMinutes = runtime,
            sourceSaysNotCached = sourceLabelSaysNotCached(item.streamTitle),
        )
        val initialVerdict = placeholderVerdict(evidence(facts?.durationMs))
        // Null samples mean no check was made, including when size and duration suffice.
        val look = if (problem == null && facts != null && needsFrames(item, facts, runtime, initialVerdict)) {
            lookAtFrames(localFileUri, transfer)
        } else null
        val samples = look?.samples
        val verdict = look?.verdict
        val framesProblem = verdict?.problem
        val framesCheck = when {
            (facts?.containerTitle ?: samples?.containerTitle).titleNamesAnotherEpisode(item.seasonNumber, item.episodeNumber) -> DownloadFramesCheck.Suspect
            samples == null || verdict == null || !verdict.conclusive || framesProblem != null -> DownloadFramesCheck.NotChecked
            samples.titleNamesAnotherEpisode(
                expectedSeason = item.seasonNumber.takeIf { item.isEpisode },
                expectedEpisode = item.episodeNumber.takeIf { item.isEpisode },
            ) -> DownloadFramesCheck.Suspect
            else -> DownloadFramesCheck.Passed
        }

        // What the source claimed and the title's running time, held against the file that arrived.
        val placeholder = if (problem == null && framesProblem == null && facts != null) {
            (placeholderVerdict(evidence(facts.durationMs ?: samples?.durationMs)) as? PlaceholderVerdict.NotTheRealVideo)?.reason
        } else {
            null
        }

        var rejected: DownloadItem? = null
        var completed = false
        ifCurrent(item.id, transfer) {
            val current = _uiState.value.items.firstOrNull { it.id == item.id } ?: return@ifCurrent
            if (current.status != DownloadStatus.Downloading) return@ifCurrent
            removeTransfer(item.id)
            val failed = when {
                problem != null -> current.failedBecauseOf(problem)
                framesProblem != null -> current.failedBecauseOf(framesProblem)
                placeholder != null -> current.failedAsPlaceholder(placeholder)
                else -> null
            }
            if (failed != null) {
                // Keep the final address durably until its file is confirmed out of the downloads folder.
                val retained = failed.copy(
                    localFileUri = localFileUri, awaitingCheck = true,
                    publishedFileKey = current.publishedFileKey,
                    publishedDigestVerified = current.publishedDigestVerified,
                )
                replaceItem(retained)
                if (persist(durably = true)) rejected = retained
                return@ifCurrent
            }
            completed = true
            mutateItem(item.id) { current ->
                current.copy(
                    verifiedBy = facts?.verification(
                        // Only a size the server announced while the transfer ran counts.
                        announcedBytes = current.totalBytes,
                        expectedVideoHash = item.expectedVideoHash,
                        expectedSizeBytes = item.expectedSizeBytes,
                        digestVerified = transfer.digestVerified,
                    ) ?: if (transfer.digestVerified) DownloadVerification.Digest else null,
                    framesCheck = framesCheck,
                    awaitingCheck = false,
                    publishedDigestVerified = false,
                    publishedFileKey = null,
                    status = DownloadStatus.Completed,
                    localFileUri = localFileUri,
                    downloadedBytes = if (totalBytes != null && totalBytes > 0L) {
                        totalBytes
                    } else {
                        current.downloadedBytes
                    },
                    totalBytes = totalBytes?.takeIf { it > 0L } ?: current.totalBytes,
                    errorMessage = null,
                    updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                )
            }
        }
        // File moves and companion writes can wait on the file system, so they never hold the state lock.
        rejected?.let(::trashRejectedFile)
        if (completed) companions().saveAfterDownload(item, localFileUri)
    }

    /** No state lock while waiting for the Trash; keep the failed record if any guard or move refuses. */
    private fun trashRejectedFile(item: DownloadItem) {
        val uri = item.localFileUri ?: return
        var moved = false
        SidecarLocks.withLock(uri) {
            if (!ItemLocks.tryAcquire(item.id)) return@withLock
            try {
                val mayMove = backend.withListLock {
                    val current = _uiState.value.items.firstOrNull { it.id == item.id } ?: return@withListLock false
                    if (current.localFileUri != uri || current.publishedFileKey != item.publishedFileKey || !current.awaitingCheck) return@withListLock false
                    if (!backend.isConnected(current) || usedByAnother(current, _uiState.value.items, uri) != false) return@withListLock false
                    true
                }
                // The saved record keeps its ownership claim during the move; UI saves need neither lock.
                moved = mayMove && backend.removeJustPublished(uri, item.publishedFileKey)
            } finally {
                ItemLocks.release(item.id)
            }
        }
        // The completion event must reach the replacement watcher after the file is no longer held.
        if (moved) synchronized(stateLock) {
            val items = _uiState.value.items
            val current = items.firstOrNull { it.id == item.id } ?: return@synchronized
            if (current.localFileUri != uri || current.publishedFileKey != item.publishedFileKey || !current.awaitingCheck) return@synchronized
            // A replacement may have started while Trash waited. Its saved history owns the
            // rejected source now; after the file moves, no empty failed row needs to remain.
            val replaced = current.hasBadSource && items.any { other ->
                other.id != current.id && other.logicalContentKey == current.logicalContentKey &&
                    other.triedSources.containsAll(current.sourceIdentityKeys)
            }
            publish(if (replaced) items.filterNot { it.id == current.id } else items.map { other ->
                if (other.id != current.id) other else current.copy(
                    status = DownloadStatus.Failed, localFileUri = null, awaitingCheck = false,
                    publishedFileKey = null, publishedDigestVerified = false,
                    pausedByFolder = false, pausedByConnection = false,
                )
            })
            persist()
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
        removeTransfer(item.id)?.cancel()
        backend.removePartialOf(item)
        mutateItem(item.id) { current ->
            if (current.status != DownloadStatus.Downloading) {
                current
            } else {
                current.failedAsPlaceholder(reason)
                    .copy(totalBytes = announcedBytes, downloadedBytes = 0L)
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
        if (needsFrames(item, facts, runtime, verdict)) {
            val look = lookAtFrames(uri)
            val samples = look.samples
            val framesVerdict = look.verdict
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
                    facts.containerTitle.titleNamesAnotherEpisode(item.seasonNumber, item.episodeNumber) -> DownloadFramesCheck.Suspect
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
        ensureLoaded()
        return synchronized(stateLock) {
            val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return false
            if (item.status != DownloadStatus.Completed) return false
            // A move has the item. Its file is in the middle of changing places, so this check cannot
            // say the file is lost, and it also cannot say the file is playable.
            if (!ItemLocks.tryAcquire(downloadId)) return false
            try {
                val checked = item.withoutLostFile()
                if (checked.status == DownloadStatus.Completed) return true
                mutateItem(downloadId) { checked }
                false
            } finally {
                ItemLocks.release(downloadId)
            }
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
        awaitingCheck = false,
        publishedDigestVerified = false,
        framesCheck = DownloadFramesCheck.NotChecked,
        failureKind = DownloadFailureKind.BadFile,
        errorMessage = placeholderReasonText(reason, this),
        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
    )

    private fun DownloadItem.failedWith(message: StringResource, kind: DownloadFailureKind): DownloadItem = copy(
        status = DownloadStatus.Failed,
        localFileUri = null,
        awaitingCheck = false,
        publishedDigestVerified = false,
        framesCheck = DownloadFramesCheck.NotChecked,
        failureKind = kind,
        errorMessage = runBlocking { getString(message) },
        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
    )

    /**
     * The bytes automatic downloads take: what each has recorded as downloaded, and for a running
     * transfer the full size once the server announced it.
     */
    internal fun autoDownloadBytes(exceptDownloadId: String? = null): Long =
        _uiState.value.items
            // Locations that are away are not counted; nothing is ever deleted to make room because of them.
            .filter { it.autoDownloadRuleKey != null && it.id != exceptDownloadId && backend.isConnected(it) }
            .sumOf { item ->
                if (item.status == DownloadStatus.Downloading) {
                    maxOf(item.totalBytes ?: 0L, item.downloadedBytes)
                } else {
                    item.downloadedBytes
                }
            }

    private fun autoTransferLimits(item: DownloadItem, transfer: ActiveTransfer): DownloadTransferLimits? {
        if (item.autoDownloadRuleKey == null) return null
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
                    removeTransfer(item.id)
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
        // Scheduled removals need the system Trash. Without it the file is left where it is.
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

    private fun mutateItem(downloadId: String, progressOnly: Boolean = false, transform: (DownloadItem) -> DownloadItem): Unit = synchronized(stateLock) {
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
            if (progressOnly && backend.batchesProgress) {
                if (!savingIsUnsafe) loadedProfileId?.let { profile ->
                    pendingProgress.getOrPut(profile) { mutableMapOf() }[downloadId] = updated.first { it.id == downloadId }
                    scheduleProgressSave()
                }
            } else {
                persist()
            }
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
                pendingProgress.remove(profileId)
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
            if (backend.rereadsPayload) mergeNewerStored(profileId) ?: return RelocateResult.ListUnreadable else baseRevision + 1
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
            if (item.status != DownloadStatus.Paused || !item.pausedByFolder || item.awaitingCheck || item.localFileUri != null) return
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
        if (item.status != DownloadStatus.Completed && !item.awaitingCheck) return item
        val found = backend.locate(item) as? FileLookup.Found ?: return item
        // The file may be where the record says it is not, after a move that was cut short: the record follows the file.
        val locationId = found.locationId ?: item.locationId
        return if (found.uri != item.localFileUri || locationId != item.locationId) {
            item.copy(localFileUri = found.uri, locationId = locationId)
        } else {
            item
        }
    }

    private fun DownloadItem.hasPlayableLocalFile(): Boolean =
        status == DownloadStatus.Completed &&
            !isPlaceholder &&
            backend.resolveLocalFileUri(
                localFileUri = localFileUri,
                destinationFileName = fileName,
            ) != null
}

private class ActiveTransfer {
    val checkJob = Job()
    private var handle: DownloadsTaskHandle? = null
    private var cancelled = false

    /** Set by the platform downloader when the body matched a digest the server sent. */
    @Volatile
    var digestVerified = false

    /** Set once the platform downloader reported the file complete: it is being checked and must not be stopped. */
    @Volatile
    var finishing = false

    /** A transfer cancelled before its handle is known is cancelled as soon as the handle arrives. */
    fun attach(handle: DownloadsTaskHandle) {
        this.handle = handle
        if (cancelled) handle.cancel()
    }

    fun cancel() {
        checkJob.cancel()
        cancelled = true
        handle?.cancel()
    }

    /** Cancels and waits for the transfer to have stopped writing. True when there was nothing to wait for. */
    fun cancelAndWait(timeoutMs: Long): Boolean {
        checkJob.cancel()
        cancelled = true
        return handle?.cancelAndWait(timeoutMs) ?: true
    }
}

internal interface DownloadsBackend {
    fun loadPayload(): String?
    fun loadPayload(profileId: Int): String? = loadPayload()
    fun savePayload(payload: String)
    fun savePayload(profileId: Int, payload: String) = savePayload(payload)

    /** Opt in only when counter updates are atomic with ordinary list writes. */
    val batchesProgress: Boolean get() = false

    /** Updates counters only; it must never add a record or change its status or file address. */
    fun saveProgress(profileId: Int, items: List<DownloadItem>): Boolean = withListLock {
        val payload = loadPayload() ?: return@withListLock true
        val updated = DownloadsCodec.withProgress(payload, items) ?: return@withListLock false
        savePayload(updated)
        true
    }

    /** The payload from before the last one that was saved. Null where no such copy is kept. */
    fun loadPreviousPayload(): String? = null
    fun loadPreviousPayload(profileId: Int): String? = loadPreviousPayload()

    /** Keeps a payload that could not be read, apart from the one [savePayload] writes. */
    fun saveUnreadablePayload(payload: String, atEpochMs: Long) = Unit
    fun saveUnreadablePayload(profileId: Int, payload: String, atEpochMs: Long) = saveUnreadablePayload(payload, atEpochMs)

    /** Removes a file the downloader just published that turned out bad. Only that file, where that can be proven. */
    fun removeJustPublished(localFileUri: String, fileKey: String? = null): Boolean = removeFile(localFileUri)

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

    override val batchesProgress: Boolean get() = DownloadsStorage.batchesProgress

    override fun saveProgress(profileId: Int, items: List<DownloadItem>): Boolean =
        DownloadsStorage.saveProgress(profileId, items)

    override fun loadPreviousPayload(): String? = DownloadsStorage.loadPreviousPayload()

    override fun loadPreviousPayload(profileId: Int): String? = DownloadsStorage.loadPreviousPayload(profileId)

    override fun saveUnreadablePayload(payload: String, atEpochMs: Long) =
        DownloadsStorage.saveUnreadablePayload(payload, atEpochMs)

    override fun saveUnreadablePayload(profileId: Int, payload: String, atEpochMs: Long) =
        DownloadsStorage.saveUnreadablePayload(profileId, payload, atEpochMs)

    override val rereadsPayload: Boolean get() = DownloadsStorage.rereadsPayload

    override fun syncPayload(): Boolean = DownloadsStorage.syncPayload()

    override fun removeJustPublished(localFileUri: String, fileKey: String?): Boolean =
        DownloadFolders.controller.removeJustPublished(localFileUri, fileKey) ?: false

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
        DownloadFolders.controller.removePartialOf(item) ?: removePartialFile(item.fileName)

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

    /** Patches only still-running records in the current stored list, preserving every other field. */
    fun withProgress(payload: String, updates: List<DownloadItem>): String? {
        val decoded = decode(payload) ?: return null
        val byId = updates.associateBy { it.id }
        val items = decoded.items.map { stored ->
            val progress = byId[stored.id]
            if (progress == null || stored.status != DownloadStatus.Downloading ||
                stored.fileName != progress.fileName || stored.partialFileKey != progress.partialFileKey ||
                stored.updatedAtEpochMs > progress.updatedAtEpochMs || stored.downloadedBytes > progress.downloadedBytes ||
                (stored.updatedAtEpochMs == progress.updatedAtEpochMs && stored.downloadedBytes == progress.downloadedBytes)
            ) stored else stored.copy(
                downloadedBytes = progress.downloadedBytes,
                totalBytes = progress.totalBytes,
                updatedAtEpochMs = progress.updatedAtEpochMs,
            )
        }
        if (items == decoded.items) return payload
        return encode(items, decoded, revisionOf(payload) + 1)
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

private fun sanitizeRequestHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (
                normalizedKey.isBlank() ||
                normalizedValue.isBlank() ||
                normalizedKey.equals("Accept-Encoding", ignoreCase = true) ||
                normalizedKey.equals("Range", ignoreCase = true)
            ) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun sanitizeResponseHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (normalizedKey.isBlank() || normalizedValue.isBlank()) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

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
