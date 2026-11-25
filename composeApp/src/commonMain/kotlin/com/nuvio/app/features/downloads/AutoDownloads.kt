package com.nuvio.app.features.downloads

import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.releasedPlayableEpisodes
import com.nuvio.app.features.watching.domain.WatchingContentRef
import com.nuvio.app.features.watching.domain.buildPlaybackVideoId
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.isDesktop
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.downloads_delete_setting_save_failed
import nuvio.composeapp.generated.resources.downloads_movie_scheduled_toast
import nuvio.composeapp.generated.resources.downloads_movie_scheduled_toast_soon
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_auto_delete_failed_toast
import nuvio.composeapp.generated.resources.downloads_auto_delete_not_connected_toast
import nuvio.composeapp.generated.resources.downloads_delete_moving_toast
import nuvio.composeapp.generated.resources.downloads_auto_delete_playing_toast
import nuvio.composeapp.generated.resources.downloads_auto_deleted_toast
import nuvio.composeapp.generated.resources.downloads_auto_keep
import nuvio.composeapp.generated.resources.downloads_auto_toast_limit_failed
import nuvio.composeapp.generated.resources.downloads_auto_toast_off
import nuvio.composeapp.generated.resources.downloads_auto_toast_off_failed
import nuvio.composeapp.generated.resources.downloads_auto_toast_on
import nuvio.composeapp.generated.resources.downloads_auto_toast_save_failed
import nuvio.composeapp.generated.resources.downloads_auto_scheduled_toast
import nuvio.composeapp.generated.resources.downloads_auto_scheduled_toast_soon
import nuvio.composeapp.generated.resources.downloads_cleanup_held_toast
import nuvio.composeapp.generated.resources.downloads_cleanup_moved_toast
import nuvio.composeapp.generated.resources.downloads_undo_failed_toast
import nuvio.composeapp.generated.resources.downloads_undo_file_gone_toast
import nuvio.composeapp.generated.resources.downloads_undo_restored_toast
import nuvio.composeapp.generated.resources.downloads_undo_subtitles_not_restored_toast
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/** Emits each time the internet can be reached again after it could not. */
internal fun Flow<NetworkCondition>.internetReturns(): Flow<Unit> = flow {
    var wasGone = false
    collect { condition ->
        when (condition) {
            NetworkCondition.NoInternet -> wasGone = true
            NetworkCondition.Online, NetworkCondition.ServersUnreachable -> if (wasGone) {
                wasGone = false
                emit(Unit)
            }
            NetworkCondition.Unknown, NetworkCondition.Checking -> Unit
        }
    }
}

private val DefaultDeletionMode = kotlinx.coroutines.flow.MutableStateFlow(DeleteAfterWatchingMode.AutomaticOnly)
internal val NoKeptDownloads = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
private val NoTotalStorageLimit = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
private val NoMakeRoomAutomatically = kotlinx.coroutines.flow.MutableStateFlow(false)

private val NoDeletedEpisodes = kotlinx.coroutines.flow.MutableStateFlow(emptyList<AutoDownloadDeleted>())

/** What the interface needs from automatic downloads. */
internal interface AutoDownloadController {
    val rules: StateFlow<List<AutoDownloadRule>>
    val statuses: StateFlow<Map<String, AutoDownloadStatus>>

    /** The storage limit for automatic downloads, in gigabytes. Null means no limit. */
    val storageLimitGb: StateFlow<Int?>

    /** The combined storage limit for all downloads, in gigabytes. Null means no limit. */
    val totalStorageLimitGb: StateFlow<Int?> get() = NoTotalStorageLimit

    /** Whether watched downloads are moved to the Trash automatically when over a storage limit. Off by default. */
    val makeRoomAutomatically: StateFlow<Boolean> get() = NoMakeRoomAutomatically

    val deleteAfterWatchingMode: StateFlow<DeleteAfterWatchingMode> get() = DefaultDeletionMode
    val keptDownloadIds: StateFlow<Set<String>> get() = NoKeptDownloads
    fun setDeleteAfterWatchingMode(profileId: Int, mode: DeleteAfterWatchingMode) = Unit
    fun setTotalStorageLimit(profileId: Int, limitGb: Int?) = Unit
    fun setMakeRoomAutomatically(profileId: Int, enabled: Boolean) = Unit
    fun undoKeepDownload(profileId: Int, downloadId: String) = Unit

    /** The watched episodes that are going to be removed. */
    val removals: StateFlow<List<AutoDownloadRemoval>>

    /** The watched episodes that were removed recently, oldest first. Only the last week is worth showing. */
    val recentlyDeleted: StateFlow<List<AutoDownloadDeleted>> get() = NoDeletedEpisodes

    /** False when removed files stay in the holding folder until you remove them. */
    val usesSystemTrash: Boolean
    val usesUndoableSystemTrash: Boolean get() = usesSystemTrash
    val supportsWatchedCleanup: Boolean get() = true

    /**
     * Turns automatic downloads on for [meta], or changes its rule, and checks the show. Nothing
     * is saved when [profileId] is no longer the active profile. The controller says "on" or that
     * saving failed once it knows; the caller says nothing.
     */
    fun save(profileId: Int, meta: MetaDetails, keepCount: Int, scope: AutoDownloadScope, deleteAfterWatching: Boolean)

    fun turnOff(profileId: Int, ruleKey: String)

    fun setStorageLimit(profileId: Int, limitGb: Int?)

    /** Keeps a watched episode that was going to be removed. */
    fun keepDownload(profileId: Int, downloadId: String)

    /** Removes a watched episode now instead of when its time comes. */
    fun deleteDownloadNow(profileId: Int, downloadId: String)

    /** Moves chosen watched downloads to the Trash and lists them under Recently deleted with Undo. */
    fun cleanUpWatchedDownloads(profileId: Int, downloadIds: Set<String>) = Unit

    /** Restores a recently trashed watched download if it is still in the Trash. */
    fun undoDeletedDownload(profileId: Int, entry: AutoDownloadDeleted) = Unit

    fun checkNow()
}

/** Released, numbered episodes of every season of a show, in the order the app plays them. */
internal fun MetaDetails.releasedBatchEpisodes(todayIsoDate: String): List<SeasonBatchEpisode> =
    releasedPlayableEpisodes(todayIsoDate).mapNotNull { video ->
        val season = video.season ?: return@mapNotNull null
        val episode = video.episode ?: return@mapNotNull null
        SeasonBatchEpisode(
            videoId = video.id.takeIf { it.isNotBlank() }
                ?: buildPlaybackVideoId(
                    content = WatchingContentRef(type = type, id = id),
                    seasonNumber = season,
                    episodeNumber = episode,
                ),
            seasonNumber = season,
            episodeNumber = episode,
            title = video.title.takeIf { it.isNotBlank() },
            thumbnail = video.thumbnail,
            runtimeMinutes = video.runtime,
        )
    }

/**
 * App wiring for automatic downloads. Desktop only.
 *
 * Rules are checked only while the app is open: a minute after it starts, every six hours, when a
 * rule is saved, when the user asks, and after a downloaded episode was played to the end. Every
 * ten minutes the rules with an episode whose wait is over are checked as well. Storage is read and
 * written on a background thread, never on the caller's.
 *
 * The same poll removes the watched episodes whose time has come and deletes the held files whose
 * days are over.
 */
internal object AutoDownloads : AutoDownloadController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = atomic(false)
    private val actionJobsLock = SynchronizedObject()
    private val activeActionJobs = LinkedHashSet<Job>()

    private val store = AutoDownloadStore(PlatformAutoDownloadPayloads) { ProfileRepository.activeProfileId }

    private val engine = AutoDownloadEngine(
        store = store,
        loadEpisodes = { rule ->
            // Not from the details cache, which would hide episodes announced since it was filled.
            MetaDetailsRepository.fetch(rule.parentMetaType, rule.parentMetaId, cacheResult = false, useCached = false)
                ?.releasedBatchEpisodes(CurrentDateProvider.todayIsoDate())
        },
        isWatched = ::isEpisodeWatchedForAutoDownload,
        downloads = { DownloadsRepository.uiState.value.items },
        seasonDefaults = SeasonDownloadDefaultsRepository::load,
        startBatch = SeasonBatchDownloads::start,
        cancelBatch = SeasonBatchDownloads::cancel,
        nowEpochMs = { DownloadsClock.nowEpochMs() },
        autoDownloadBytes = { DownloadsRepository.autoDownloadBytes() },
        allDownloadBytes = { DownloadsRepository.allDownloadBytes() },
        resumeDownload = DownloadsRepository::resumeDownload,
        locationAway = { rule -> DownloadsRepository.showLocationIsAway(rule.parentMetaId) },
    )

    // The local file the player has open, if any.
    private val playingFileUri = PlayingLocalFile.uri

    private val deleteAfterWatching = DeleteAfterWatching(
        store = store,
        downloads = { DownloadsRepository.uiState.value.items },
        isWatchedToTheEnd = { item ->
            WatchProgressRepository.progressForVideo(
                videoId = item.videoId,
                parentMetaId = item.parentMetaId,
                seasonNumber = item.seasonNumber,
                episodeNumber = item.episodeNumber,
            )?.isCompleted == true
        },
        isInPlayer = ::isPlaying,
        hasLaterEpisode = { rule, item -> engine.knownEpisodes(rule.key)?.let { hasLaterReleasedEpisode(item, it) } },
        removeDownload = DownloadsRepository::removeWatchedAutoDownload,
        nowEpochMs = { DownloadsClock.nowEpochMs() },
        removeDownloadWithUndo = DownloadsRepository::removeWatchedAutoDownloadWithUndo,
        usesUndoableTrash = { DownloadsRepository.usesUndoableSystemTrash },
    )

    private val makeRoom = MakeRoomAutomatically(
        store = store,
        downloads = { DownloadsRepository.uiState.value.items },
        allDownloadBytes = DownloadsRepository::allDownloadBytes,
        autoDownloadBytes = DownloadsRepository::autoDownloadBytes,
        totalStorageLimitBytes = ::totalStorageLimitBytes,
        autoStorageLimitBytes = ::storageLimitBytes,
        watchStatusOf = { item ->
            WatchProgressRepository.ensureLoaded()
            WatchedRepository.ensureLoaded()
            item.watchStatus(WatchProgressRepository.uiState.value)
        },
        isPlaying = ::isPlaying,
        isConnected = DownloadsRepository.backend::isConnected,
        isBusyOrMoving = DownloadsRepository::isBusyOrAway,
        usesSystemTrash = { DownloadsRepository.usesUndoableSystemTrash },
        hasRuleWaitingForStorage = { engine.hasUnqueuedRuleWaitingForStorage(store.profileId()) },
    )

    val isAvailable: Boolean get() = isDesktop && AppFeaturePolicy.downloadsEnabled

    override val deleteAfterWatchingMode get() = store.deleteAfterWatchingMode
    override val keptDownloadIds get() = store.keptDownloadIds
    override val makeRoomAutomatically get() = store.makeRoomAutomatically

    override val rules: StateFlow<List<AutoDownloadRule>> get() = store.rules
    override val statuses: StateFlow<Map<String, AutoDownloadStatus>> get() = engine.statuses
    override val storageLimitGb: StateFlow<Int?> get() = store.storageLimitGb
    override val totalStorageLimitGb: StateFlow<Int?> get() = store.totalStorageLimitGb
    override val removals: StateFlow<List<AutoDownloadRemoval>> get() = store.removals
    override val recentlyDeleted: StateFlow<List<AutoDownloadDeleted>> get() = store.recentlyDeleted
    override val usesSystemTrash: Boolean get() = DownloadsTrash.usesSystemTrash
    override val usesUndoableSystemTrash: Boolean get() = DownloadsTrash.usesUndoableSystemTrash
    override val supportsWatchedCleanup: Boolean get() = isAvailable && DownloadsRepository.supportsWatchedCleanup

    fun storageLimitBytes(): Long? = store.storageLimitBytes()
    fun totalStorageLimitBytes(): Long? = store.totalStorageLimitBytes()

    /** Called when a download waits on a storage limit. Automatic clean-up hooks in here when enabled. */
    fun onStorageLimitBlocked() {
        if (!isAvailable) return
        val profileId = ProfileRepository.activeProfileId
        if (!store.isMakeRoomEnabled(profileId)) return
        launchTrackedAction {
            val moved = runCatching { makeRoom.runIfNeeded(profileId) }.getOrDefault(0)
            if (moved > 0) checkSafely()
        }
    }

    fun start() {
        if (!isAvailable || !started.compareAndSet(expect = false, update = true)) return
        scope.launch {
            delay(StartupDelayMs)
            while (true) {
                if (engine.isFullCheckDue()) {
                    checkSafely()
                } else {
                    engine.rulesWithDueRetry().forEach { checkSafely(it) }
                }
                runCatching {
                    val profile = store.profileId()
                    deleteAfterWatching.runDue(profile)
                    if (store.isMakeRoomEnabled(profile)) makeRoom.runIfNeeded(profile)
                    DownloadsTrash.purgeHeldFiles(DownloadsClock.nowEpochMs())
                }
                delay(PollIntervalMs)
            }
        }
        scope.launch {
            // What could not be fetched while the internet was gone is looked for again as soon as
            // the app sees it return, without waiting for the next periodic check.
            NetworkStatusRepository.uiState
                .map { it.condition }
                .internetReturns()
                .collect { checkSafely(ignoreWaiting = true) }
        }
        scope.launch {
            // Progress alone changes nothing here, so only the episodes and their states are compared.
            DownloadsRepository.uiState
                .distinctUntilChangedBy { state ->
                    state.items.map { listOf(it.id, it.status, it.autoDownloadRuleKey) }
                }
                .collect { state ->
                    // A storage error here must not end the observation.
                    runCatching { engine.onDownloadsChanged(store.profileId(), state.items) }
                }
        }
    }

    override fun save(
        profileId: Int,
        meta: MetaDetails,
        keepCount: Int,
        scope: AutoDownloadScope,
        deleteAfterWatching: Boolean,
    ) {
        if (!isAvailable) return
        val rule = AutoDownloadRule(
            parentMetaType = meta.type,
            parentMetaId = meta.id,
            title = meta.name,
            logo = meta.logo,
            poster = meta.poster,
            background = meta.background,
            keepCount = keepCount,
            scope = scope,
            deleteAfterWatching = deleteAfterWatching,
        )
        this.scope.launch {
            // What is said is what happened: "on" only once the rule is saved, an error when it is not.
            val result = saveAutoDownloadRule(store, profileId, rule)
            when (result) {
                AutoDownloadSaveResult.TurnedOn -> NuvioToastController.show(getString(Res.string.downloads_auto_toast_on))
                AutoDownloadSaveResult.Changed -> Unit
                AutoDownloadSaveResult.NotSaved -> NuvioToastController.show(getString(Res.string.downloads_auto_toast_save_failed))
            }
            if (result != AutoDownloadSaveResult.NotSaved) checkSafely(rule.key, ignoreWaiting = true)
        }
    }

    override fun turnOff(profileId: Int, ruleKey: String) {
        scope.launch {
            if (removeAutoDownloadRule(store, profileId, ruleKey)) {
                engine.forget(ruleKey)
                NuvioToastController.show(getString(Res.string.downloads_auto_toast_off))
            } else {
                NuvioToastController.show(getString(Res.string.downloads_auto_toast_off_failed))
            }
        }
    }

    /** A higher limit may make room for what the old one held back, so the rules are checked. */
    override fun setStorageLimit(profileId: Int, limitGb: Int?) {
        launchTrackedAction {
            if (changeAutoDownloadStorageLimit(store, profileId, limitGb)) {
                DownloadsRepository.onStorageLimitsChanged()
                if (store.isMakeRoomEnabled(profileId)) runCatching { makeRoom.runIfNeeded(profileId) }
                checkSafely()
            } else {
                NuvioToastController.show(getString(Res.string.downloads_auto_toast_limit_failed))
            }
        }
    }

    /** Changes the combined storage limit for all downloads and resumes waiting ones when there is room. */
    override fun setTotalStorageLimit(profileId: Int, limitGb: Int?) {
        launchTrackedAction {
            if (changeTotalDownloadStorageLimit(store, profileId, limitGb)) {
                DownloadsRepository.onStorageLimitsChanged()
                if (store.isMakeRoomEnabled(profileId)) runCatching { makeRoom.runIfNeeded(profileId) }
                checkSafely()
            } else {
                NuvioToastController.show(getString(Res.string.downloads_auto_toast_limit_failed))
            }
        }
    }

    override fun setMakeRoomAutomatically(profileId: Int, enabled: Boolean) {
        if (enabled && !usesUndoableSystemTrash) return
        launchTrackedAction {
            val saved = runCatching { store.setMakeRoomAutomatically(profileId, enabled) }.getOrDefault(false)
            if (!saved) {
                NuvioToastController.show(getString(Res.string.downloads_delete_setting_save_failed))
            } else if (enabled) {
                val moved = runCatching { makeRoom.runIfNeeded(profileId) }.getOrDefault(0)
                if (moved > 0) checkSafely()
            }
        }
    }

    /**
     * The user is deleting [item] from Downloads. Called before the download is removed, so that no
     * check can queue the episode again in between. A rule that queued it remembers it.
     */
    suspend fun rememberUserDeletion(item: DownloadItem) {
        if (item.autoDownloadRuleKey == null) return
        withContext(Dispatchers.Default) {
            runCatching { engine.onDeletedByUser(store.profileId(), item) }
        }
    }

    override fun setDeleteAfterWatchingMode(profileId: Int, mode: DeleteAfterWatchingMode) {
        launchTrackedAction {
            if (!runCatching { store.setDeleteAfterWatchingMode(profileId, mode) }.getOrDefault(false)) {
                NuvioToastController.show(getString(Res.string.downloads_delete_setting_save_failed))
            }
        }
    }

    override fun undoKeepDownload(profileId: Int, downloadId: String) {
        launchTrackedAction { runCatching { deleteAfterWatching.undoKeep(profileId, downloadId) } }
    }

    override fun keepDownload(profileId: Int, downloadId: String) {
        launchTrackedAction { runCatching { deleteAfterWatching.keep(profileId, downloadId) } }
    }

    override fun deleteDownloadNow(profileId: Int, downloadId: String) {
        launchTrackedAction {
            val outcome = runCatching { deleteAfterWatching.deleteNow(profileId, downloadId) }.getOrNull()
            val message = when (outcome) {
                AutoDownloadRemovalOutcome.Removed -> Res.string.downloads_auto_deleted_toast
                AutoDownloadRemovalOutcome.Playing -> Res.string.downloads_auto_delete_playing_toast
                AutoDownloadRemovalOutcome.NotMoved -> Res.string.downloads_auto_delete_failed_toast
                AutoDownloadRemovalOutcome.NotReachable -> Res.string.downloads_auto_delete_not_connected_toast
                AutoDownloadRemovalOutcome.Moving -> Res.string.downloads_delete_moving_toast
                AutoDownloadRemovalOutcome.LastEpisode, AutoDownloadRemovalOutcome.Cancelled, null -> null
            }
            if (message != null) NuvioToastController.show(getString(message))
        }
    }

    override fun cleanUpWatchedDownloads(profileId: Int, downloadIds: Set<String>) {
        if (downloadIds.isEmpty()) return
        launchTrackedAction {
            val moved = runCatching {
                executeWatchedCleanup(
                    profileId = profileId,
                    downloadIds = downloadIds,
                    store = store,
                    isWatched = { item ->
                        WatchProgressRepository.ensureLoaded()
                        WatchedRepository.ensureLoaded()
                        item.watchStatus(WatchProgressRepository.uiState.value).isWatched
                    },
                )
            }.getOrDefault(0)
            if (moved > 0) {
                val toastPlural = if (usesUndoableSystemTrash) {
                    Res.plurals.downloads_cleanup_moved_toast
                } else {
                    Res.plurals.downloads_cleanup_held_toast
                }
                NuvioToastController.show(getPluralString(toastPlural, moved, moved))
            } else {
                NuvioToastController.show(getString(Res.string.downloads_auto_delete_failed_toast))
            }
        }
    }

    override fun undoDeletedDownload(profileId: Int, entry: AutoDownloadDeleted) {
        launchTrackedAction {
            val result = runCatching { undoDeletedWatchedDownload(profileId, entry, store) }
                .getOrDefault(UndoTrashResult.NotRestored)
            val label = entry.restorableItem?.nameForLabels() ?: entry.title
            when (result) {
                UndoTrashResult.Restored ->
                    NuvioToastController.show(getString(Res.string.downloads_undo_restored_toast, label))
                UndoTrashResult.PartiallyRestored ->
                    NuvioToastController.show(getString(Res.string.downloads_undo_subtitles_not_restored_toast, label))
                UndoTrashResult.FileGone ->
                    NuvioToastController.show(getString(Res.string.downloads_undo_file_gone_toast))
                UndoTrashResult.NotReachable ->
                    NuvioToastController.show(getString(Res.string.downloads_auto_delete_not_connected_toast))
                UndoTrashResult.TargetTaken, UndoTrashResult.NotRestored ->
                    NuvioToastController.show(getString(Res.string.downloads_undo_failed_toast))
            }
        }
    }

    /** Whether the player has the file of [item] open. A download that is playing is not moved. */
    fun isPlaying(item: DownloadItem): Boolean =
        playingFileUri.value?.let { playing ->
            // The row can be temporarily absent while its removal is being saved.
            item.localFileUri?.let { DownloadsRepository.backend.isSameFile(playing, it) } == true ||
                DownloadsRepository.findCompletedDownloadByLocalFile(playing)?.id == item.id
        } == true

    /** Record playback and cancel a removal without waiting for its disk work. */
    fun onLocalFileOpened(localFileUri: String) = WatchedRemovalGuard.signalChange {
        playingFileUri.value = localFileUri
    }

    fun onLocalFileClosed(localFileUri: String) {
        playingFileUri.compareAndSet(localFileUri, null)
    }

    override fun checkNow() {
        if (!isAvailable) return
        scope.launch { checkSafely(ignoreWaiting = true) }
    }

    /**
     * Loads the rules of the profile that is active now and stops what the rules of the previous
     * one were adding. The new profile's rules are checked at the next ten-minute poll.
     */
    fun onProfileChanged() {
        if (!started.value) return
        scope.launch { runCatching { engine.useProfile(store.profileId()) } }
    }

    /**
     * A downloaded file was played to the end on this device. The show it belongs to is checked, so
     * the episode that now comes next is fetched without waiting for the next periodic check.
     */
    fun onLocalPlaybackCompleted(localFileUri: String) {
        if (!started.value) return
        // The profile and cancel epoch when playback ended, so a later unwatch or profile switch wins.
        val profileId = ProfileRepository.activeProfileId
        val completionEpoch = store.currentCancelEpoch()
        launchTrackedAction {
            val item = DownloadsRepository.findCompletedDownloadByLocalFile(localFileUri) ?: return@launchTrackedAction
            val scheduled = runCatching {
                deleteAfterWatching.onLocalPlaybackCompleted(profileId, item, completionEpoch)
            }.getOrDefault(false)
            if (scheduled) runCatching { announceScheduledDeletion(profileId, item) }
            // The watched state is saved by the player after it reports the end of playback.
            scope.launch {
                delay(PlaybackCheckDelayMs)
                checkSafely(autoDownloadRuleKey(item.parentMetaType, item.parentMetaId))
            }
        }
    }

    /** Cancels pending watched removals when content is unwatched or its playback progress is cleared. */
    fun cancelPendingRemovals(
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String? = null,
        onlyNonEpisode: Boolean = false,
    ) {
        cancelPendingRemovals(
            parentMetaIds = listOf(parentMetaId),
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            videoId = videoId,
            onlyNonEpisode = onlyNonEpisode,
        )
    }

    /** Cancels pending watched removals matching any alias of unwatched or cleared content. */
    fun cancelPendingRemovals(
        parentMetaIds: Collection<String>,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String? = null,
        onlyNonEpisode: Boolean = false,
    ) {
        if (!isAvailable) return
        val normalizedParents = parentMetaIds
            .map { it.trim() }
            .filterTo(linkedSetOf()) { it.isNotEmpty() }
        val normalizedVideoId = videoId?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedParents.isEmpty() && normalizedVideoId == null) return
        val profileId = ProfileRepository.activeProfileId
        runCatching {
            val cancelled = deleteAfterWatching.cancelForContent(
                profileId = profileId,
                parentMetaIds = normalizedParents,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                videoId = normalizedVideoId,
                onlyNonEpisode = onlyNonEpisode,
            )
            if (cancelled) {
                launchTrackedAction {
                    runCatching {
                        store.currentRules()
                            .filter { it.parentMetaId.trim() in normalizedParents }
                            .forEach { engine.refreshStatus(profileId, it.key) }
                    }
                }
            }
        }
    }

    /** Says that [item] will be deleted, with a Keep button for as long as the message stays. */
    private suspend fun announceScheduledDeletion(profileId: Int, item: DownloadItem) {
        val due = store.removals(profileId).firstOrNull { it.downloadId == item.id } ?: return
        val message = scheduledDeletionMessage(item, removalHoursLeft(due.dueAtEpochMs, DownloadsClock.nowEpochMs()))
        if (store.removals(profileId).none { it.downloadId == item.id }) return
        NuvioToastController.show(
            message = message,
            durationMillis = ScheduledDeletionToastMs,
            actionLabel = getString(Res.string.downloads_auto_keep),
            onAction = { keepDownload(profileId, item.id) },
        )
    }

    fun onPrivateSourceSkipped(request: SeasonBatchRequest, episode: SeasonBatchEpisode) {
        engine.onPrivateSourceSkipped(request, episode)
    }

    fun onBatchFinished(request: SeasonBatchRequest, summary: SeasonBatchSummary) {
        engine.onBatchFinished(request, summary)
    }

    /** Waits until every queued completion, Keep, Undo Keep, or deletion action has settled. */
    internal suspend fun awaitIdle() {
        while (true) {
            val snapshot = synchronized(actionJobsLock) { activeActionJobs.toList() }
            if (snapshot.isEmpty()) return
            snapshot.forEach { it.join() }
        }
    }

    private fun launchTrackedAction(block: suspend CoroutineScope.() -> Unit): Job {
        lateinit var job: Job
        synchronized(actionJobsLock) {
            job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    block()
                } finally {
                    synchronized(actionJobsLock) {
                        activeActionJobs.remove(job)
                    }
                }
            }
            activeActionJobs.add(job)
        }
        job.invokeOnCompletion {
            synchronized(actionJobsLock) {
                activeActionJobs.remove(job)
            }
        }
        job.start()
        return job
    }

    private suspend fun checkSafely(
        ruleKey: String? = null,
        ignoreWaiting: Boolean = false,
        allowMakeRoomRetry: Boolean = true,
    ) {
        try {
            engine.runCheck(ruleKey, ignoreWaiting)
            if (allowMakeRoomRetry) {
                val profileId = store.profileId()
                if (store.isMakeRoomEnabled(profileId) && engine.statuses.value.values.contains(AutoDownloadStatus.StorageLimitReached)) {
                    val moved = runCatching { makeRoom.runIfNeeded(profileId) }.getOrDefault(0)
                    if (moved > 0) {
                        engine.runCheck(ruleKey, ignoreWaiting)
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // One failed check must not end the periodic checks.
        }
    }

    private const val ScheduledDeletionToastMs = 10_000L
    private const val StartupDelayMs = 60_000L
    private const val PollIntervalMs = 10L * 60 * 1000
    private const val PlaybackCheckDelayMs = 5_000L
}

/**
 * Whether [episode] counts as watched for an automatic-download rule: either marked watched in
 * history or finished by local playback on this device during this install (never a cloud-synced
 * or tracker-synced progress row).
 */
internal fun isEpisodeWatchedForAutoDownload(
    rule: AutoDownloadRule,
    episode: SeasonBatchEpisode,
    isWatchedInHistory: (id: String, type: String, season: Int, episode: Int) -> Boolean = { id, type, season, number ->
        WatchedRepository.isWatched(id = id, type = type, season = season, episode = number)
    },
    isLocallyFinishedOnDevice: (parentMetaId: String, season: Int, episode: Int) -> Boolean = { parentMetaId, season, number ->
        WatchProgressRepository.isEpisodeLocallyFinished(
            parentMetaId = parentMetaId,
            seasonNumber = season,
            episodeNumber = number,
        )
    },
): Boolean =
    isWatchedInHistory(rule.parentMetaId, rule.parentMetaType, episode.seasonNumber, episode.episodeNumber) ||
        isLocallyFinishedOnDevice(rule.parentMetaId, episode.seasonNumber, episode.episodeNumber)

/** What is said when finishing [item] schedules its deletion in about [hours] hours. */
internal suspend fun scheduledDeletionMessage(item: DownloadItem, hours: Int): String {
    if (!item.isEpisode) {
        return if (hours < 1) getString(Res.string.downloads_movie_scheduled_toast_soon, item.title)
        else getPluralString(Res.plurals.downloads_movie_scheduled_toast, hours, item.title, hours)
    }
    val season = item.seasonNumber ?: 0
    val episode = item.episodeNumber ?: 0
    return if (hours < 1) {
        getString(Res.string.downloads_auto_scheduled_toast_soon, item.title, season, episode)
    } else {
        getPluralString(Res.plurals.downloads_auto_scheduled_toast, hours, item.title, season, episode, hours)
    }
}
