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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

private val NoDeletedEpisodes = kotlinx.coroutines.flow.MutableStateFlow(emptyList<AutoDownloadDeleted>())

/** What the interface needs from automatic downloads. */
internal interface AutoDownloadController {
    val rules: StateFlow<List<AutoDownloadRule>>
    val statuses: StateFlow<Map<String, AutoDownloadStatus>>

    /** The storage limit for automatic downloads, in gigabytes. Null means no limit. */
    val storageLimitGb: StateFlow<Int?>

    val deleteAfterWatchingMode: StateFlow<DeleteAfterWatchingMode> get() = DefaultDeletionMode
    val keptDownloadIds: StateFlow<Set<String>> get() = NoKeptDownloads
    fun setDeleteAfterWatchingMode(profileId: Int, mode: DeleteAfterWatchingMode) = Unit
    fun undoKeepDownload(profileId: Int, downloadId: String) = Unit

    /** The watched episodes that are going to be removed. */
    val removals: StateFlow<List<AutoDownloadRemoval>>

    /** The watched episodes that were removed recently, oldest first. Only the last week is worth showing. */
    val recentlyDeleted: StateFlow<List<AutoDownloadDeleted>> get() = NoDeletedEpisodes

    /** False when removed files are held in the downloads folder and then deleted for good. */
    val usesSystemTrash: Boolean

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

    private val store = AutoDownloadStore(PlatformAutoDownloadPayloads) { ProfileRepository.activeProfileId }

    private val engine = AutoDownloadEngine(
        store = store,
        loadEpisodes = { rule ->
            // Not from the details cache, which would hide episodes announced since it was filled.
            MetaDetailsRepository.fetch(rule.parentMetaType, rule.parentMetaId, cacheResult = false, useCached = false)
                ?.releasedBatchEpisodes(CurrentDateProvider.todayIsoDate())
        },
        isWatched = { rule, episode ->
            WatchedRepository.isWatched(
                id = rule.parentMetaId,
                type = rule.parentMetaType,
                season = episode.seasonNumber,
                episode = episode.episodeNumber,
            )
        },
        downloads = { DownloadsRepository.uiState.value.items },
        seasonDefaults = SeasonDownloadDefaultsRepository::load,
        startBatch = SeasonBatchDownloads::start,
        cancelBatch = SeasonBatchDownloads::cancel,
        nowEpochMs = { DownloadsClock.nowEpochMs() },
        autoDownloadBytes = { DownloadsRepository.autoDownloadBytes() },
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
        isInPlayer = { item ->
            playingFileUri.value?.let { DownloadsRepository.findCompletedDownloadByLocalFile(it)?.id == item.id } == true
        },
        hasLaterEpisode = { rule, item -> engine.knownEpisodes(rule.key)?.let { hasLaterReleasedEpisode(item, it) } },
        removeDownload = DownloadsRepository::removeWatchedAutoDownload,
        nowEpochMs = { DownloadsClock.nowEpochMs() },
    )

    val isAvailable: Boolean get() = isDesktop && AppFeaturePolicy.downloadsEnabled

    override val deleteAfterWatchingMode get() = store.deleteAfterWatchingMode
    override val keptDownloadIds get() = store.keptDownloadIds

    override val rules: StateFlow<List<AutoDownloadRule>> get() = store.rules
    override val statuses: StateFlow<Map<String, AutoDownloadStatus>> get() = engine.statuses
    override val storageLimitGb: StateFlow<Int?> get() = store.storageLimitGb
    override val removals: StateFlow<List<AutoDownloadRemoval>> get() = store.removals
    override val recentlyDeleted: StateFlow<List<AutoDownloadDeleted>> get() = store.recentlyDeleted
    override val usesSystemTrash: Boolean get() = DownloadsTrash.usesSystemTrash

    fun storageLimitBytes(): Long? = store.storageLimitBytes()

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
                    deleteAfterWatching.runDue(store.profileId())
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
        scope.launch {
            if (changeAutoDownloadStorageLimit(store, profileId, limitGb)) {
                checkSafely()
            } else {
                NuvioToastController.show(getString(Res.string.downloads_auto_toast_limit_failed))
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
        scope.launch {
            if (!runCatching { store.setDeleteAfterWatchingMode(profileId, mode) }.getOrDefault(false)) {
                NuvioToastController.show(getString(Res.string.downloads_delete_setting_save_failed))
            }
        }
    }

    override fun undoKeepDownload(profileId: Int, downloadId: String) {
        scope.launch { runCatching { store.undoKeep(profileId, downloadId) } }
    }

    override fun keepDownload(profileId: Int, downloadId: String) {
        scope.launch { runCatching { deleteAfterWatching.keep(profileId, downloadId) } }
    }

    override fun deleteDownloadNow(profileId: Int, downloadId: String) {
        scope.launch {
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

    /** Whether the player has the file of [item] open. A download that is playing is not moved. */
    fun isPlaying(item: DownloadItem): Boolean =
        playingFileUri.value?.let { DownloadsRepository.findCompletedDownloadByLocalFile(it)?.id == item.id } == true

    /** The player opened or closed a local file. A download that is open is not removed. */
    fun onLocalFileOpened(localFileUri: String) {
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
        // The profile that watched it. Nothing is scheduled when another one is active by then.
        val profileId = ProfileRepository.activeProfileId
        scope.launch {
            val item = DownloadsRepository.findCompletedDownloadByLocalFile(localFileUri) ?: return@launch
            val scheduled = runCatching { deleteAfterWatching.onLocalPlaybackCompleted(profileId, item) }.getOrDefault(false)
            if (scheduled) runCatching { announceScheduledDeletion(profileId, item) }
            // The watched state is saved by the player after it reports the end of playback.
            delay(PlaybackCheckDelayMs)
            checkSafely(autoDownloadRuleKey(item.parentMetaType, item.parentMetaId))
        }
    }

    /** Says that [item] will be deleted, with a Keep button for as long as the message stays. */
    private suspend fun announceScheduledDeletion(profileId: Int, item: DownloadItem) {
        val due = store.removals(profileId).firstOrNull { it.downloadId == item.id } ?: return
        val message = scheduledDeletionMessage(item, removalHoursLeft(due.dueAtEpochMs, DownloadsClock.nowEpochMs()))
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

    private suspend fun checkSafely(ruleKey: String? = null, ignoreWaiting: Boolean = false) {
        try {
            engine.runCheck(ruleKey, ignoreWaiting)
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
