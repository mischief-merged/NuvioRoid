package com.nuvio.app.features.downloads

import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.features.cloud.CloudLibraryContentType
import com.nuvio.app.features.debrid.DebridStreamLanguage
import com.nuvio.app.features.debrid.DebridStreamMetadata
import com.nuvio.app.features.debrid.DebridStreamPreferences
import com.nuvio.app.features.debrid.DebridStreamResolution
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.parseRuntimeMinutes
import com.nuvio.app.features.details.wantedContent
import com.nuvio.app.features.home.CompletedSeriesCandidate
import com.nuvio.app.features.home.buildHomeNextUpSeedCandidates
import com.nuvio.app.features.home.filterEntriesForContinueWatchingWindow
import com.nuvio.app.features.home.filterHomeNextUpCandidatesForContinueWatchingWindow
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.WantedContent
import com.nuvio.app.features.streams.saysNotCached
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watching.domain.isSeriesLikeWatchingContentType
import com.nuvio.app.features.watchprogress.CachedInProgressItem
import com.nuvio.app.features.watchprogress.CachedNextUpItem
import com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentCache
import com.nuvio.app.features.watchprogress.ContinueWatchingLimit
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.WatchProgressClock
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.continueWatchingEntries
import com.nuvio.app.features.watchprogress.isSeriesTypeForContinueWatching
import com.nuvio.app.features.watchprogress.nextUpDismissKey
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

internal val DownloadItem.hasTripPartial: Boolean
    get() = status == DownloadStatus.Failed && (downloadedBytes > 0L || partialFileKey != null)

internal const val DefaultTripEpisodeRuntimeMinutes = 45
private const val MaxConsecutiveNoSourcePerShow = 3

internal data class TripShowCandidate(
    val parentMetaType: String,
    val parentMetaId: String,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val fromContinueWatching: Boolean = false,
    val autoDownloadRule: AutoDownloadRule? = null,
    val inProgressSeason: Int? = null,
    val inProgressEpisode: Int? = null,
    val lastActivityEpochMs: Long = 0L,
)

internal data class TripShowMeta(
    val title: String,
    val episodes: List<SeasonBatchEpisode>,
    val defaultRuntimeMinutes: Int? = null,
    val wantedForEpisode: (SeasonBatchEpisode) -> WantedContent = { ep ->
        WantedContent(title = title, season = ep.seasonNumber, episode = ep.episodeNumber)
    },
)

internal data class TripPlannedEpisode(
    val show: TripShowCandidate,
    val episode: SeasonBatchEpisode,
    val effectiveRuntimeMinutes: Int,
    val runtimeEstimated: Boolean,
    val rankedStreams: List<StreamItem>,
    val selectedStream: StreamItem,
    val isCached: Boolean,
    val estimatedBytes: Long,
    val sizeEstimated: Boolean,
)

internal data class TripDestination(val key: String, val freeBytes: Long?)

internal enum class TripPlanStopReason {
    TargetReached,
    StorageLimitReached,
    LowDiskSpace,
    NoMoreEpisodes,
    NoShows,
    Offline,
}

internal enum class TripSkippedReason {
    FolderNotConnected,
    CouldNotLoadEpisodes,
    NoMatchingSources,
}

internal data class TripSkippedShow(
    val show: TripShowCandidate,
    val reason: TripSkippedReason,
)

internal data class TripFillPlan(
    val requestedHours: Int,
    val plannedEpisodes: List<TripPlannedEpisode>,
    val totalPlannedMinutes: Int,
    val totalEstimatedBytes: Long,
    val qualityDefaults: SeasonDownloadDefaults,
    val storageLimitBytes: Long?,
    val remainingStorageLimitBytes: Long?,
    val freeDiskBytes: Long?,
    val connectionHoldReason: LimitedConnectionReason?,
    val stoppedReason: TripPlanStopReason,
    val skippedShows: List<TripSkippedShow> = emptyList(),
    val profileId: Int? = null,
)

internal data class TripFillDoneSummary(
    val totalPlanned: Int,
    val queuedCount: Int,
    val waitingOnHoldCount: Int,
    val failedCount: Int,
    val stoppedEarly: Boolean,
)

internal data class TripFillRunState(
    val isRunning: Boolean = false,
    val totalEpisodes: Int = 0,
    val processedEpisodes: Int = 0,
    val queuedEpisodes: Int = 0,
    val waitingOnHoldEpisodes: Int = 0,
    val failedEpisodes: Int = 0,
    val currentLabel: String? = null,
    val doneSummary: TripFillDoneSummary? = null,
)

/**
 * Collects unique shows from Continue Watching (in-progress and Next Up) and Auto-download rules.
 * Continue Watching shows come first, ordered by most recent watch activity, followed by any
 * remaining Auto-download rule shows. Pure and deterministic.
 */
internal fun collectTripShowCandidates(
    inProgressEntries: List<WatchProgressEntry>,
    nextUpSeeds: List<CompletedSeriesCandidate>,
    autoDownloadRules: List<AutoDownloadRule>,
    cachedNextUp: List<CachedNextUpItem> = emptyList(),
    cachedInProgress: List<CachedInProgressItem> = emptyList(),
    watchedItems: List<WatchedItem> = emptyList(),
    hiddenContentIds: Set<String> = emptySet(),
    isDroppedShow: (String) -> Boolean = { false },
    dismissedNextUpKeys: Set<String> = emptySet(),
    cutoffEpochMs: Long? = null,
    titlePeek: (type: String, id: String) -> String? = { _, _ -> null },
): List<TripShowCandidate> {
    val rulesByMetaId = autoDownloadRules.associateBy { it.parentMetaId.trim() }
    val cachedNextUpById = cachedNextUp.associateBy { it.contentId.trim() }
    val cachedInProgressById = cachedInProgress.associateBy { it.contentId.trim() }
    val watchedTitleById = watchedItems.asSequence()
        .filter { !it.name.isNullOrBlank() }
        .associate { it.id.trim() to it.name!! }

    val byMetaId = linkedMapOf<String, TripShowCandidate>()

    fun isHidden(id: String): Boolean = id in hiddenContentIds || isDroppedShow(id)

    fun resolveTitle(type: String, id: String, direct: String?): String =
        direct?.takeIf { it.isNotBlank() && !it.equals(id, ignoreCase = true) }
            ?: cachedInProgressById[id]?.name?.takeIf { it.isNotBlank() }
            ?: cachedNextUpById[id]?.name?.takeIf { it.isNotBlank() }
            ?: rulesByMetaId[id]?.title?.takeIf { it.isNotBlank() }
            ?: watchedTitleById[id]?.takeIf { it.isNotBlank() }
            ?: titlePeek(type, id)?.takeIf { it.isNotBlank() }
            ?: direct?.takeIf { it.isNotBlank() }
            ?: id

    // Combine CW in-progress and Next Up seeds, sorted by most recent activity descending.
    val visibleProgress = filterEntriesForContinueWatchingWindow(
        entries = inProgressEntries.filterNot { entry ->
            isHidden(entry.parentMetaId.trim()) ||
                entry.contentType.equals(CloudLibraryContentType, ignoreCase = true) ||
                entry.parentMetaType.equals(CloudLibraryContentType, ignoreCase = true)
        },
        cutoffEpochMs = cutoffEpochMs,
    ).continueWatchingEntries(limit = ContinueWatchingLimit)
        .filter { entry ->
            entry.parentMetaType.isSeriesTypeForContinueWatching() ||
                entry.parentMetaType.isSeriesLikeWatchingContentType() ||
                (entry.seasonNumber != null && entry.episodeNumber != null)
        }

    val visibleSeeds = filterHomeNextUpCandidatesForContinueWatchingWindow(
        candidates = nextUpSeeds.filterNot { candidate ->
            val id = candidate.content.id.trim()
            isHidden(id) ||
                nextUpDismissKey(id, candidate.seasonNumber, candidate.episodeNumber) in dismissedNextUpKeys
        },
        cutoffEpochMs = cutoffEpochMs,
    )

    val cwOrdered = buildList {
        visibleProgress.forEach { entry ->
            val id = entry.parentMetaId.trim()
            if (id.isNotBlank()) {
                val rule = rulesByMetaId[id]
                add(
                    TripShowCandidate(
                        parentMetaType = entry.parentMetaType.ifBlank { "series" },
                        parentMetaId = id,
                        title = resolveTitle(entry.parentMetaType, id, entry.title),
                        logo = entry.logo ?: rule?.logo,
                        poster = entry.poster ?: cachedInProgressById[id]?.poster ?: rule?.poster,
                        background = entry.background ?: cachedInProgressById[id]?.backdrop ?: rule?.background,
                        fromContinueWatching = true,
                        autoDownloadRule = rule,
                        inProgressSeason = entry.seasonNumber,
                        inProgressEpisode = entry.episodeNumber,
                        lastActivityEpochMs = entry.lastUpdatedEpochMs,
                    ),
                )
            }
        }
        visibleSeeds.forEach { seed ->
            val id = seed.content.id.trim()
            if (id.isNotBlank()) {
                val cached = cachedNextUpById[id]
                val rule = rulesByMetaId[id]
                val type = seed.content.type.ifBlank { cached?.contentType ?: rule?.parentMetaType ?: "series" }
                add(
                    TripShowCandidate(
                        parentMetaType = type,
                        parentMetaId = id,
                        title = resolveTitle(type, id, cached?.name),
                        logo = cached?.logo ?: rule?.logo,
                        poster = cached?.poster ?: rule?.poster,
                        background = cached?.backdrop ?: rule?.background,
                        fromContinueWatching = true,
                        autoDownloadRule = rule,
                        lastActivityEpochMs = seed.markedAtEpochMs,
                    ),
                )
            }
        }
    }.sortedByDescending { it.lastActivityEpochMs }

    cwOrdered.forEach { candidate ->
        if (candidate.parentMetaId !in byMetaId) {
            byMetaId[candidate.parentMetaId] = candidate
        }
    }

    autoDownloadRules.forEach { rule ->
        val id = rule.parentMetaId.trim()
        if (id.isBlank()) return@forEach
        val existing = byMetaId[id]
        if (existing == null) {
            byMetaId[id] = TripShowCandidate(
                parentMetaType = rule.parentMetaType,
                parentMetaId = id,
                title = rule.title.ifBlank { id },
                logo = rule.logo,
                poster = rule.poster,
                background = rule.background,
                fromContinueWatching = false,
                autoDownloadRule = rule,
            )
        } else if (existing.autoDownloadRule == null) {
            byMetaId[id] = existing.copy(autoDownloadRule = rule)
        }
    }

    return byMetaId.values.toList()
}

/** Collects Continue Watching + Auto-download show candidates from the app's repositories. */
internal fun collectAppTripShowCandidates(
    profileId: Int = ProfileRepository.activeProfileId,
    autoDownloadRules: List<AutoDownloadRule> = AutoDownloads.rules.value,
): List<TripShowCandidate> {
    WatchProgressRepository.ensureLoaded()
    WatchedRepository.ensureLoaded()
    ContinueWatchingPreferencesRepository.ensureLoaded()
    TrackingSettingsRepository.ensureLoaded()

    val progressState = WatchProgressRepository.uiState.value
    val watchedState = WatchedRepository.uiState.value
    val cwPrefs = ContinueWatchingPreferencesRepository.uiState.value
    val trackingSettings = TrackingSettingsRepository.uiState.value
    val source = WatchProgressRepository.activeSourceState.value
    val providerOwnsCompletedHistory = WatchProgressRepository.activeProviderOwnsCompletedHistoryProjection()
    val now = WatchProgressClock.nowEpochMs()
    val cutoffEpochMs = WatchProgressRepository.activeProviderContinueWatchingCutoffEpochMs(
        daysCap = trackingSettings.continueWatchingDaysCap,
        nowEpochMs = now,
    )
    val nextUpWatchedItems = if (providerOwnsCompletedHistory) emptyList() else watchedState.items
    val nextUpSeeds = buildHomeNextUpSeedCandidates(
        progressEntries = progressState.entries,
        watchedItems = nextUpWatchedItems,
        providerOwnsCompletedHistory = providerOwnsCompletedHistory,
        preferFurthestEpisode = cwPrefs.upNextFromFurthestEpisode,
        nowEpochMs = now,
        shouldUseProgressSeed = WatchProgressRepository::shouldUseAsNextUpSeed,
        isContentHidden = { contentId ->
            contentId in progressState.hiddenContentIds || WatchProgressRepository.isDroppedShow(contentId)
        },
    )
    val (cachedNextUp, cachedInProgress) = ContinueWatchingEnrichmentCache.getSnapshots(profileId, source)

    return collectTripShowCandidates(
        inProgressEntries = progressState.entries,
        nextUpSeeds = nextUpSeeds,
        autoDownloadRules = autoDownloadRules,
        cachedNextUp = cachedNextUp,
        cachedInProgress = cachedInProgress,
        watchedItems = watchedState.items,
        hiddenContentIds = progressState.hiddenContentIds,
        isDroppedShow = WatchProgressRepository::isDroppedShow,
        dismissedNextUpKeys = cwPrefs.dismissedNextUpKeys,
        cutoffEpochMs = cutoffEpochMs,
        titlePeek = { type, id -> MetaDetailsRepository.peek(type, id)?.name },
    )
}

/**
 * Returns the next unwatched released episodes of [show] in playback order, excluding specials,
 * episodes already downloaded/active, and episodes the user deleted from an auto-download rule.
 */
internal fun nextUnwatchedEpisodesForShow(
    show: TripShowCandidate,
    released: List<SeasonBatchEpisode>,
    isWatched: (SeasonBatchEpisode) -> Boolean,
    downloads: List<DownloadItem>,
): List<SeasonBatchEpisode> {
    val ordered = released
        .filter { it.seasonNumber > 0 }
        .distinctBy { it.autoDownloadKey }
        .sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
    if (ordered.isEmpty()) return emptyList()

    val statusByEpisode = downloads.episodeStatuses(show.parentMetaId)
    val placeholders = downloads.placeholderEpisodeKeys(show.parentMetaId, ordered)
    val removed = show.autoDownloadRule?.ownedEpisodeKeys?.minus(statusByEpisode.keys).orEmpty()

    val lastWatchedIndex = ordered.indexOfLast(isWatched)
    val inProgressIndex = if (show.inProgressSeason != null && show.inProgressEpisode != null) {
        ordered.indexOfFirst { ep ->
            ep.seasonNumber == show.inProgressSeason &&
                ep.episodeNumber == show.inProgressEpisode &&
                !isWatched(ep)
        }
    } else {
        -1
    }
    val startIndex = if (inProgressIndex >= 0) {
        inProgressIndex
    } else {
        lastWatchedIndex + 1
    }

    return ordered
        .drop(startIndex)
        .filter { episode ->
            if (isWatched(episode)) return@filter false
            if (episode.autoDownloadKey in removed) return@filter false
            when (statusByEpisode[episode.autoDownloadKey]) {
                DownloadStatus.Completed -> episode.autoDownloadKey in placeholders
                DownloadStatus.Downloading, DownloadStatus.Paused -> false
                DownloadStatus.Failed, null -> true
            }
        }
}

/** Returns `(effectiveMinutes, isEstimated)`. */
internal fun effectiveEpisodeRuntimeMinutes(
    episode: SeasonBatchEpisode,
    showDefaultMinutes: Int?,
): Pair<Int, Boolean> {
    episode.runtimeMinutes?.takeIf { it > 0 }?.let { return it to false }
    showDefaultMinutes?.takeIf { it > 0 }?.let { return it to false }
    return DefaultTripEpisodeRuntimeMinutes to true
}

/**
 * Returns `(estimatedBytes, isEstimated)`. Uses the source's claimed size or cached debrid size
 * when plausible for a single episode; otherwise estimates from resolution / bitrate cap and runtime.
 */
internal fun estimateEpisodeBytes(
    stream: StreamItem,
    quality: BatchQuality,
    maxBitrateMbps: Int?,
    runtimeMinutes: Int,
): Pair<Long, Boolean> {
    usableSizeClaim(stream, isEpisode = true, runtimeMinutes = runtimeMinutes)?.let {
        return it to false
    }
    stream.debridCacheStatus?.cachedSize
        ?.takeIf { it > 0L && isPlausibleForOneEpisode(it, runtimeMinutes) }
        ?.let { return it to false }

    val detectedResolution = DebridStreamMetadata.facts(stream, DebridStreamPreferences()).resolution
    val effectiveResolution = if (detectedResolution != DebridStreamResolution.UNKNOWN) {
        detectedResolution
    } else {
        quality.resolution ?: DebridStreamResolution.P1080
    }
    val defaultBps = when (effectiveResolution) {
        DebridStreamResolution.P2160 -> 12_000_000L
        DebridStreamResolution.P1440 -> 8_000_000L
        DebridStreamResolution.P1080 -> 5_000_000L
        DebridStreamResolution.P720 -> 2_500_000L
        DebridStreamResolution.P576, DebridStreamResolution.P480 -> 1_200_000L
        DebridStreamResolution.P360 -> 700_000L
        DebridStreamResolution.UNKNOWN -> 4_000_000L
    }
    val cappedBps = maxBitrateMbps?.takeIf { it > 0 }?.let { minOf(defaultBps, it * 1_000_000L) } ?: defaultBps
    val estimated = (cappedBps * runtimeMinutes.coerceAtLeast(1) * 60L) / 8L
    return estimated to true
}

/**
 * Plans which unwatched episodes to queue for [targetHours] across Continue Watching and
 * Auto-download shows, picking episodes round-robin across shows and respecting storage limits,
 * free disk space floor, quality preferences, connection holds, and cached-first stream ranking.
 */
internal suspend fun planTripFill(
    targetHours: Int,
    shows: List<TripShowCandidate>,
    existingDownloads: List<DownloadItem>,
    qualityDefaults: SeasonDownloadDefaults,
    storageLimitBytes: Long? = null,
    usedAgainstStorageLimitBytes: Long = 0L,
    freeDiskBytes: Long? = null,
    minFreeBytes: Long = SavedAutoDownloadLimits.MinFreeBytes,
    connectionHoldReason: LimitedConnectionReason? = null,
    isOffline: Boolean = false,
    showLocationIsAway: (parentMetaId: String) -> Boolean = { false },
    destinationOf: (TripShowCandidate) -> TripDestination = { TripDestination("default", freeDiskBytes) },
    isEpisodeWatched: (TripShowCandidate, SeasonBatchEpisode) -> Boolean,
    loadShowMeta: suspend (TripShowCandidate) -> TripShowMeta?,
    fetchStreams: suspend (TripShowCandidate, SeasonBatchEpisode, WantedContent) -> List<StreamItem>,
): TripFillPlan {
    val limitBudget = storageLimitBytes?.let { (it - usedAgainstStorageLimitBytes).coerceAtLeast(0L) }

    if (targetHours <= 0) {
        return TripFillPlan(
            requestedHours = targetHours,
            plannedEpisodes = emptyList(),
            totalPlannedMinutes = 0,
            totalEstimatedBytes = 0L,
            qualityDefaults = qualityDefaults,
            storageLimitBytes = storageLimitBytes,
            remainingStorageLimitBytes = limitBudget,
            freeDiskBytes = freeDiskBytes,
            connectionHoldReason = connectionHoldReason,
            stoppedReason = TripPlanStopReason.TargetReached,
        )
    }

    if (isOffline) {
        return TripFillPlan(
            requestedHours = targetHours,
            plannedEpisodes = emptyList(),
            totalPlannedMinutes = 0,
            totalEstimatedBytes = 0L,
            qualityDefaults = qualityDefaults,
            storageLimitBytes = storageLimitBytes,
            remainingStorageLimitBytes = limitBudget,
            freeDiskBytes = freeDiskBytes,
            connectionHoldReason = connectionHoldReason,
            stoppedReason = TripPlanStopReason.Offline,
        )
    }

    if (shows.isEmpty()) {
        return TripFillPlan(
            requestedHours = targetHours,
            plannedEpisodes = emptyList(),
            totalPlannedMinutes = 0,
            totalEstimatedBytes = 0L,
            qualityDefaults = qualityDefaults,
            storageLimitBytes = storageLimitBytes,
            remainingStorageLimitBytes = limitBudget,
            freeDiskBytes = freeDiskBytes,
            connectionHoldReason = connectionHoldReason,
            stoppedReason = TripPlanStopReason.NoShows,
        )
    }

    val skippedShows = mutableListOf<TripSkippedShow>()

    class ShowCursor(
        val show: TripShowCandidate,
        val meta: TripShowMeta,
        val queue: ArrayDeque<SeasonBatchEpisode>,
        var queuedAny: Boolean = false,
        var consecutiveMisses: Int = 0,
        var blockedReason: TripPlanStopReason? = null,
    )

    val cursors = mutableListOf<ShowCursor>()
    for (show in shows) {
        currentCoroutineContext().ensureActive()
        if (showLocationIsAway(show.parentMetaId)) {
            skippedShows += TripSkippedShow(show, TripSkippedReason.FolderNotConnected)
            continue
        }
        val meta = try {
            loadShowMeta(show)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
        if (meta == null) {
            skippedShows += TripSkippedShow(show, TripSkippedReason.CouldNotLoadEpisodes)
            continue
        }
        val updatedShow = if (meta.title.isNotBlank()) show.copy(title = meta.title) else show
        val unwatched = nextUnwatchedEpisodesForShow(
            show = updatedShow,
            released = meta.episodes,
            isWatched = { episode -> isEpisodeWatched(updatedShow, episode) },
            downloads = existingDownloads,
        )
        if (unwatched.isNotEmpty()) {
            cursors += ShowCursor(
                show = updatedShow,
                meta = meta,
                queue = ArrayDeque(unwatched),
            )
        }
    }

    val targetMinutes = targetHours * 60
    val planned = mutableListOf<TripPlannedEpisode>()
    var plannedMinutes = 0
    var plannedBytes = 0L
    val plannedBytesByDestination = mutableMapOf<String, Long>()
    var stoppedForStorageLimit = false
    var stoppedForLowDisk = false

    while (plannedMinutes < targetMinutes) {
        val activeCursors = cursors.filter { it.queue.isNotEmpty() && it.blockedReason == null }
        if (activeCursors.isEmpty()) break

        var madeProgressInRound = false
        for (cursor in activeCursors) {
            if (plannedMinutes >= targetMinutes) break
            currentCoroutineContext().ensureActive()

            while (cursor.queue.isNotEmpty() && cursor.blockedReason == null) {
                val episode = cursor.queue.removeFirst()
                val (runtimeMin, runtimeEst) = effectiveEpisodeRuntimeMinutes(episode, cursor.meta.defaultRuntimeMinutes)
                val episodeWithRuntime = if (episode.runtimeMinutes == null) episode.copy(runtimeMinutes = runtimeMin) else episode
                val minBytes = minPlausibleVideoBytes(runtimeMin)
                val wanted = cursor.meta.wantedForEpisode(episodeWithRuntime)
                val rawStreams = try {
                    fetchStreams(cursor.show, episodeWithRuntime, wanted)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    emptyList()
                }
                val ranked = rankBatchCandidates(
                    streams = rawStreams,
                    quality = qualityDefaults.quality,
                    maxBitrateMbps = qualityDefaults.maxBitrateMbps,
                    audioLanguage = qualityDefaults.audioLanguage,
                    runtimeMinutes = runtimeMin,
                    wanted = wanted,
                ).filter { candidate ->
                    candidate.announcedSizeBytes()?.let { it < minBytes } != true
                }

                if (ranked.isEmpty()) {
                    cursor.consecutiveMisses++
                    if (cursor.consecutiveMisses >= MaxConsecutiveNoSourcePerShow) {
                        cursor.queue.clear()
                    }
                    continue
                }

                cursor.consecutiveMisses = 0
                val top = ranked.first()
                val (estimatedBytes, sizeEst) = estimateEpisodeBytes(
                    stream = top,
                    quality = qualityDefaults.quality,
                    maxBitrateMbps = qualityDefaults.maxBitrateMbps,
                    runtimeMinutes = runtimeMin,
                )

                if (limitBudget != null && plannedBytes + estimatedBytes > limitBudget) {
                    cursor.blockedReason = TripPlanStopReason.StorageLimitReached
                    stoppedForStorageLimit = true
                    break
                }
                val destination = destinationOf(cursor.show)
                val destinationBytes = plannedBytesByDestination[destination.key] ?: 0L
                if (destination.freeBytes?.let { it - destinationBytes - estimatedBytes < minFreeBytes } == true) {
                    cursor.blockedReason = TripPlanStopReason.LowDiskSpace
                    stoppedForLowDisk = true
                    break
                }

                planned += TripPlannedEpisode(
                    show = cursor.show,
                    episode = episodeWithRuntime,
                    effectiveRuntimeMinutes = runtimeMin,
                    runtimeEstimated = runtimeEst,
                    rankedStreams = ranked,
                    selectedStream = top,
                    isCached = !top.saysNotCached,
                    estimatedBytes = estimatedBytes,
                    sizeEstimated = sizeEst,
                )
                plannedMinutes += runtimeMin
                plannedBytes += estimatedBytes
                plannedBytesByDestination[destination.key] = destinationBytes + estimatedBytes
                cursor.queuedAny = true
                madeProgressInRound = true
                break
            }
        }
        if (!madeProgressInRound && cursors.none { it.queue.isNotEmpty() && it.blockedReason == null }) {
            break
        }
    }

    cursors.filter { !it.queuedAny && it.blockedReason == null }.forEach { cursor ->
        skippedShows += TripSkippedShow(cursor.show, TripSkippedReason.NoMatchingSources)
    }

    val stoppedReason = when {
        plannedMinutes >= targetMinutes -> TripPlanStopReason.TargetReached
        stoppedForStorageLimit -> TripPlanStopReason.StorageLimitReached
        stoppedForLowDisk -> TripPlanStopReason.LowDiskSpace
        else -> TripPlanStopReason.NoMoreEpisodes
    }

    return TripFillPlan(
        requestedHours = targetHours,
        plannedEpisodes = planned,
        totalPlannedMinutes = plannedMinutes,
        totalEstimatedBytes = plannedBytes,
        qualityDefaults = qualityDefaults,
        storageLimitBytes = storageLimitBytes,
        remainingStorageLimitBytes = limitBudget?.let { (it - plannedBytes).coerceAtLeast(0L) },
        freeDiskBytes = freeDiskBytes,
        connectionHoldReason = connectionHoldReason,
        stoppedReason = stoppedReason,
        skippedShows = skippedShows,
    )
}

internal fun MetaDetails.toTripShowMeta(todayIsoDate: String): TripShowMeta = TripShowMeta(
    title = name,
    episodes = releasedBatchEpisodes(todayIsoDate),
    defaultRuntimeMinutes = parseRuntimeMinutes(runtime)?.takeIf { it > 0 },
    wantedForEpisode = { episode -> wantedContent(episode.seasonNumber, episode.episodeNumber) },
)

/** Builds a [TripFillPlan] using the app's repositories and settings. */
internal suspend fun buildAppTripFillPlan(
    targetHours: Int,
    profileId: Int = ProfileRepository.activeProfileId,
    shows: List<TripShowCandidate> = collectAppTripShowCandidates(profileId),
    todayIsoDate: String = CurrentDateProvider.todayIsoDate(),
): TripFillPlan {
    DownloadsRepository.ensureLoaded()
    val freeBytes = shows.mapNotNull { DownloadsRepository.tripDestination(it.parentMetaId).freeBytes }.minOrNull()
    val qualityDefaults = SeasonDownloadDefaultsRepository.load(profileId)
    val holdReason = LimitedConnections.hold.holdReasonFor(automatic = false)
    val offline = NetworkStatusRepository.uiState.value.isOfflineLike

    return planTripFill(
        targetHours = targetHours,
        shows = shows,
        existingDownloads = DownloadsRepository.uiState.value.items,
        qualityDefaults = qualityDefaults,
        storageLimitBytes = DownloadsRepository.autoDownloadStorageLimitBytes(),
        usedAgainstStorageLimitBytes = DownloadsRepository.autoDownloadBytes(),
        freeDiskBytes = freeBytes,
        minFreeBytes = DownloadsRepository.autoDownloadMinFreeBytes(),
        connectionHoldReason = holdReason,
        isOffline = offline,
        showLocationIsAway = DownloadsRepository::showLocationIsAway,
        destinationOf = { show -> DownloadsRepository.tripDestination(show.parentMetaId) },
        isEpisodeWatched = { show, episode ->
            WatchedRepository.isWatched(
                id = show.parentMetaId,
                type = show.parentMetaType,
                season = episode.seasonNumber,
                episode = episode.episodeNumber,
            ) || WatchProgressRepository.progressForVideo(
                videoId = episode.videoId,
                parentMetaId = show.parentMetaId,
                seasonNumber = episode.seasonNumber,
                episodeNumber = episode.episodeNumber,
            )?.isEffectivelyCompleted == true
        },
        loadShowMeta = { show ->
            MetaDetailsRepository.fetch(show.parentMetaType, show.parentMetaId)?.toTripShowMeta(todayIsoDate)
        },
        fetchStreams = { _, episode, wanted ->
            SeasonBatchDownloads.fetchStreamsForEpisode(
                episode = episode,
                quality = qualityDefaults.quality,
                maxBitrateMbps = qualityDefaults.maxBitrateMbps,
                audioLanguage = qualityDefaults.audioLanguage,
                wanted = wanted,
            )
        },
    ).copy(profileId = profileId)
}

/**
 * Executes an approved [TripFillPlan] with bounded concurrency, live storage-limit enforcement,
 * connection-hold awareness, and cached-first source failover.
 */
internal class TripFillRunner(
    private val scope: CoroutineScope,
    private val downloads: StateFlow<DownloadsUiState>,
    private val prepareStream: suspend (StreamItem, SeasonBatchEpisode) -> StreamItem?,
    private val enqueue: (TripShowCandidate, SeasonBatchEpisode, StreamItem) -> DownloadEnqueueResult,
    private val discard: (DownloadItem) -> Unit = {},
    private val inspect: (DownloadItem) -> DownloadedFileFacts? = { null },
    private val preferAudioLanguage: (DownloadItem, String) -> Unit = { _, _ -> },
    private val verifyCompleted: (DownloadItem) -> Boolean = { true },
    private val storageLimitBytes: () -> Long? = { null },
    private val autoDownloadBytes: () -> Long = { 0L },
    private val freeDiskBytes: () -> Long? = { null },
    private val minFreeBytes: Long = SavedAutoDownloadLimits.MinFreeBytes,
    private val maxConcurrent: Int = SeasonBatchDownloader.DefaultMaxConcurrent,
    private val currentProfileId: () -> Int? = { null },
    private val persistPlan: (TripFillPlan) -> Unit = {},
    private val clearPlan: (Int?) -> Unit = {},
    private val enqueueOwned: ((TripShowCandidate, SeasonBatchEpisode, StreamItem, Int?, Set<String>) -> String?)? = null,
    private val resume: (DownloadItem, Int?) -> Unit = { _, _ -> },
    private val destinationOf: ((TripShowCandidate) -> TripDestination)? = null,
    private val reloadCandidates: suspend (TripPlannedEpisode, SeasonDownloadDefaults) -> List<StreamItem> = { _, _ -> emptyList() },
) {
    private val lock = SynchronizedObject()
    private val slots = Semaphore(maxConcurrent.coerceAtLeast(1))
    private var activeJob: Job? = null
    private var stopped = false
    private var runProfileId: Int? = null

    private val _state = MutableStateFlow(TripFillRunState())
    val state: StateFlow<TripFillRunState> = _state.asStateFlow()

    fun start(plan: TripFillPlan): Boolean {
        if (plan.plannedEpisodes.isEmpty() || !profileMatches(plan.profileId)) return false
        synchronized(lock) {
            if (activeJob?.isActive == true) return false
            if (runCatching { persistPlan(plan) }.isFailure) return false
            runProfileId = plan.profileId
            stopped = false
            _state.value = TripFillRunState(
                isRunning = true,
                totalEpisodes = plan.plannedEpisodes.size,
            )
            activeJob = scope.launch { runPlan(plan) }
        }
        return true
    }

    fun stop(clearPending: Boolean = true): Job? {
        if (clearPending) runCatching { clearPlan(runProfileId) }
        val job = synchronized(lock) {
            stopped = true
            activeJob
        }
        job?.cancel()
        return job
    }

    fun clearDone() {
        if (_state.value.isRunning) return
        _state.value = TripFillRunState()
    }

    private suspend fun runPlan(plan: TripFillPlan) {
        var queued = 0
        var waitingOnHold = 0
        var failed = 0
        var processed = 0
        var cancelled = false
        val budgetLock = SynchronizedObject()
        val reservedBytesByKey = mutableMapOf<String, Long>()
        val reservedDestinationByKey = mutableMapOf<String, String>()
        val queuedEstimatedBytesByKey = mutableMapOf<String, Long>()

        try {
            coroutineScope {
                for (planned in plan.plannedEpisodes) {
                    launch {
                        val key = logicalKeyOf(planned)
                        slots.acquire()
                        try {
                            if (synchronized(lock) { stopped }) return@launch
                            val label = "${planned.show.title} S${planned.episode.seasonNumber.toString().padStart(2, '0')}E${planned.episode.episodeNumber.toString().padStart(2, '0')}"
                            _state.update { it.copy(currentLabel = label) }

                            val outcome = processPlannedEpisode(
                                planned = planned,
                                qualityDefaults = plan.qualityDefaults,
                                profileId = plan.profileId,
                                budgetLock = budgetLock,
                                reservedBytesByKey = reservedBytesByKey,
                                reservedDestinationByKey = reservedDestinationByKey,
                                queuedEstimatedBytesByKey = queuedEstimatedBytesByKey,
                            )
                            synchronized(lock) {
                                processed++
                                when (outcome) {
                                    EpisodeOutcome.Queued -> queued++
                                    EpisodeOutcome.WaitingOnHold -> {
                                        queued++
                                        waitingOnHold++
                                    }
                                    EpisodeOutcome.AlreadyPresent -> queued++
                                    EpisodeOutcome.Skipped -> Unit
                                    EpisodeOutcome.Failed -> failed++
                                    EpisodeOutcome.Stopped -> stopped = true
                                }
                                _state.update {
                                    it.copy(
                                        processedEpisodes = processed,
                                        queuedEpisodes = queued,
                                        waitingOnHoldEpisodes = waitingOnHold,
                                        failedEpisodes = failed,
                                    )
                                }
                            }
                        } finally {
                            synchronized(budgetLock) {
                                reservedBytesByKey.remove(key)
                                reservedDestinationByKey.remove(key)
                            }
                            slots.release()
                        }
                    }
                }
            }
        } catch (_: CancellationException) {
            cancelled = true
        } finally {
            withContext(NonCancellable) {
                if (!cancelled && !synchronized(lock) { stopped } && profileMatches(plan.profileId)) {
                    runCatching { clearPlan(plan.profileId) }
                }
                val (finalProcessed, finalQueued, finalHold, finalFailed, wasStopped) = synchronized(lock) {
                    listOf(processed, queued, waitingOnHold, failed, if (stopped) 1 else 0)
                }
                _state.value = TripFillRunState(
                    isRunning = false,
                    totalEpisodes = plan.plannedEpisodes.size,
                    processedEpisodes = finalProcessed,
                    queuedEpisodes = finalQueued,
                    waitingOnHoldEpisodes = finalHold,
                    failedEpisodes = finalFailed,
                    currentLabel = null,
                    doneSummary = TripFillDoneSummary(
                        totalPlanned = plan.plannedEpisodes.size,
                        queuedCount = finalQueued,
                        waitingOnHoldCount = finalHold,
                        failedCount = finalFailed,
                        stoppedEarly = cancelled || wasStopped != 0,
                    ),
                )
            }
        }
    }

    private enum class EpisodeOutcome {
        Queued,
        WaitingOnHold,
        AlreadyPresent,
        Skipped,
        Failed,
        Stopped,
    }

    private suspend fun processPlannedEpisode(
        planned: TripPlannedEpisode,
        qualityDefaults: SeasonDownloadDefaults,
        profileId: Int?,
        budgetLock: SynchronizedObject,
        reservedBytesByKey: MutableMap<String, Long>,
        reservedDestinationByKey: MutableMap<String, String>,
        queuedEstimatedBytesByKey: MutableMap<String, Long>,
    ): EpisodeOutcome {
        if (!profileMatches(profileId)) return EpisodeOutcome.Stopped
        val minBytes = minPlausibleVideoBytes(planned.effectiveRuntimeMinutes)
        val existing = findItem(downloads.value.items, planned)
        if (existing?.hasTripPartial == true) {
            resume(existing, profileId)
            if (!profileMatches(profileId)) return EpisodeOutcome.Stopped
            return if (downloads.value.items.any { it.id == existing.id && it.status != DownloadStatus.Failed }) {
                EpisodeOutcome.Queued
            } else EpisodeOutcome.Failed
        }
        if (hasActiveOrCompleted(downloads.value, planned, minBytes)) {
            return EpisodeOutcome.AlreadyPresent
        }

        val key = logicalKeyOf(planned)
        val ownedIds = mutableSetOf<String>()
        existing?.takeIf { it.status == DownloadStatus.Failed }?.let { ownedIds += it.id }
        val candidates = planned.rankedStreams.ifEmpty {
            try {
                reloadCandidates(planned, qualityDefaults)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                emptyList()
            }
        }.take(SeasonBatchDownloader.MaxSourcesPerEpisode)
        for (candidate in candidates) {
            if (synchronized(lock) { stopped }) return EpisodeOutcome.Stopped
            currentCoroutineContext().ensureActive()

            val prepared = try {
                prepareStream(candidate, planned.episode)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            } ?: continue

            if (!profileMatches(profileId)) return EpisodeOutcome.Stopped
            val intervening = findItem(downloads.value.items, planned)
            if (intervening != null && intervening.id !in ownedIds &&
                (intervening.hasTripPartial || hasActiveOrCompleted(downloads.value, planned, minBytes))) {
                return EpisodeOutcome.AlreadyPresent
            }
            val candidateBytes = estimateEpisodeBytes(prepared, qualityDefaults.quality,
                qualityDefaults.maxBitrateMbps, planned.effectiveRuntimeMinutes).first
            val fitsBudget = synchronized(budgetLock) {
                val extraTripBytes = currentTripNonAutoBytes(downloads.value, queuedEstimatedBytesByKey)
                val otherReservations = reservedBytesByKey.entries
                    .filter { (k, _) -> k != key && downloads.value.items.none { item -> !item.isPlaceholder && item.logicalContentKey == k } }
                val reservedOther = otherReservations.sumOf { it.value }
                val limit = storageLimitBytes()
                val used = autoDownloadBytes() + extraTripBytes + reservedOther
                val fitsLimit = limit == null || used + candidateBytes <= limit
                val destination = destinationOf?.invoke(planned.show)
                val free = destination?.freeBytes ?: if (destination == null) freeDiskBytes() else null
                val reservedOnDisk = otherReservations.filter { (k, _) ->
                    reservedDestinationByKey[k] == (destination?.key ?: "default")
                }.sumOf { it.value }
                val fitsDisk = free == null || free - reservedOnDisk - candidateBytes >= minFreeBytes
                if (fitsLimit && fitsDisk) {
                    reservedBytesByKey[key] = candidateBytes
                    reservedDestinationByKey[key] = destination?.key ?: "default"
                    true
                } else {
                    false
                }
            }
            if (!fitsBudget) return EpisodeOutcome.Stopped

            if (!profileMatches(profileId)) return EpisodeOutcome.Stopped
            val ownedId = try {
                if (enqueueOwned != null) {
                    enqueueOwned.invoke(planned.show, planned.episode, prepared, profileId, ownedIds.toSet())
                } else {
                    val result = enqueue(planned.show, planned.episode, prepared)
                    if (result == DownloadEnqueueResult.Started || result == DownloadEnqueueResult.Replaced) {
                        findItem(downloads.value.items, planned)?.id
                    } else null
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
            if (ownedId == null) {
                synchronized(budgetLock) {
                    reservedBytesByKey.remove(key)
                    reservedDestinationByKey.remove(key)
                }
                continue
            }
            ownedIds += ownedId

            synchronized(budgetLock) {
                queuedEstimatedBytesByKey[key] = candidateBytes
                reservedBytesByKey.remove(key)
                reservedDestinationByKey.remove(key)
            }

            // A row that pauses or is replaced releases its slot for the rest of the plan.
            val item = downloads
                .first { state ->
                    val current = state.items.firstOrNull { it.id == ownedId }
                    !profileMatches(profileId) || current == null ||
                        current.status != DownloadStatus.Downloading ||
                        (current.totalBytes != null && current.totalBytes in 1 until minBytes)
                }
                .let { state -> state.items.firstOrNull { it.id == ownedId } }

            if (!profileMatches(profileId)) return EpisodeOutcome.Stopped
            when {
                item == null -> return EpisodeOutcome.Skipped
                item.status == DownloadStatus.Paused && item.pausedByConnection ->
                    return EpisodeOutcome.WaitingOnHold
                item.status == DownloadStatus.Paused ->
                    return EpisodeOutcome.Queued
                item.status == DownloadStatus.Downloading && item.totalBytes != null && item.totalBytes in 1 until minBytes ->
                    return EpisodeOutcome.Failed
                item.isPlaceholder ->
                    Unit
                item.status == DownloadStatus.Completed && item.finishedSizeBytes < minBytes ->
                    Unit
                item.status == DownloadStatus.Completed && isPlaceholderOnDisk(item, planned.effectiveRuntimeMinutes, minBytes) ->
                    Unit
                item.status == DownloadStatus.Completed && lacksChosenAudio(item, qualityDefaults.audioLanguage) ->
                    Unit
                item.status == DownloadStatus.Completed ->
                    return EpisodeOutcome.Queued
                item.hasTripPartial -> return EpisodeOutcome.Failed
                else -> Unit // A source that wrote nothing may be tried from another source.
            }
        }
        return EpisodeOutcome.Failed
    }

    private fun currentTripNonAutoBytes(
        state: DownloadsUiState,
        queuedEstimatedBytesByKey: Map<String, Long>,
    ): Long =
        state.items
            .filter { it.autoDownloadRuleKey == null && !it.tripFill && it.logicalContentKey in queuedEstimatedBytesByKey }
            .sumOf { item ->
                val actual = when (item.status) {
                    DownloadStatus.Downloading -> item.totalBytes?.let { maxOf(it, item.downloadedBytes) }
                    DownloadStatus.Paused -> item.totalBytes?.let { maxOf(it, item.downloadedBytes) }
                    DownloadStatus.Completed -> item.finishedSizeBytes
                    DownloadStatus.Failed -> item.downloadedBytes
                }
                actual ?: maxOf(item.downloadedBytes, queuedEstimatedBytesByKey[item.logicalContentKey] ?: 0L)
            }

    private fun profileMatches(profileId: Int?): Boolean = profileId == null || currentProfileId() == profileId

    private fun logicalKeyOf(planned: TripPlannedEpisode): String =
        "${planned.show.parentMetaId.trim()}|${planned.episode.seasonNumber}|${planned.episode.episodeNumber}"

    private fun findItem(items: List<DownloadItem>, planned: TripPlannedEpisode): DownloadItem? =
        items.firstOrNull { item ->
            !item.isPlaceholder &&
                item.parentMetaId.trim() == planned.show.parentMetaId.trim() &&
                item.seasonNumber == planned.episode.seasonNumber &&
                item.episodeNumber == planned.episode.episodeNumber
        }

    private fun hasActiveOrCompleted(state: DownloadsUiState, planned: TripPlannedEpisode, minBytes: Long): Boolean {
        val item = findItem(state.items, planned) ?: return false
        return when (item.status) {
            DownloadStatus.Completed ->
                !item.isPlaceholder &&
                    item.finishedSizeBytes >= minBytes &&
                    verifyCompleted(item) &&
                    !isPlaceholderOnDisk(item, planned.effectiveRuntimeMinutes, minBytes)
            DownloadStatus.Downloading, DownloadStatus.Paused -> true
            DownloadStatus.Failed -> false
        }
    }

    private fun isPlaceholderOnDisk(item: DownloadItem, runtimeMinutes: Int, minBytes: Long): Boolean {
        val facts = inspect(item)?.takeIf { it.exists } ?: return false
        return facts.sizeBytes < minBytes || isFarShorterThanRuntime(facts.durationMs, runtimeMinutes)
    }

    private fun lacksChosenAudio(item: DownloadItem, chosen: DebridStreamLanguage?): Boolean {
        if (chosen == null) return false
        return when (val check = checkAudioTracks(inspect(item)?.audioLanguages, chosen)) {
            AudioTrackCheck.Missing -> true
            is AudioTrackCheck.Later -> {
                preferAudioLanguage(item, check.language)
                false
            }
            AudioTrackCheck.First, AudioTrackCheck.Unknown -> false
        }
    }
}

internal object TripFillHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val queue = TripFillQueue(SeasonDownloadDefaultsStorage::loadTripQueue, SeasonDownloadDefaultsStorage::saveTripQueue)
    private var recovery: Job? = null

    fun onProfileLoaded(profileId: Int) {
        recovery?.cancel()
        val previous = runner.stop(clearPending = false)
        recovery = scope.launch {
            previous?.join()
            val plan = queue.pending(profileId) ?: return@launch
            NetworkStatusRepository.uiState.first { !it.isOfflineLike }
            if (DownloadsRepository.loadedProfile() == profileId && ProfileRepository.activeProfileId == profileId) {
                runner.start(plan)
            }
        }
    }

    val runner: TripFillRunner by lazy {
        TripFillRunner(
            scope = scope,
            downloads = DownloadsRepository.uiState,
            prepareStream = SeasonBatchDownloads::prepareStream,
            enqueue = { _, _, _ -> DownloadEnqueueResult.MissingUrl },
            enqueueOwned = DownloadsRepository::enqueueTripEpisode,
            resume = DownloadsRepository::resumeTripEpisode,
            currentProfileId = {
                DownloadsRepository.loadedProfile()?.takeIf { it == ProfileRepository.activeProfileId }
            },
            persistPlan = queue::save,
            clearPlan = queue::clear,
            destinationOf = { show -> DownloadsRepository.tripDestination(show.parentMetaId) },
            reloadCandidates = { planned, defaults ->
                val wanted = MetaDetailsRepository.fetch(planned.show.parentMetaType, planned.show.parentMetaId)
                    ?.wantedContent(planned.episode.seasonNumber, planned.episode.episodeNumber)
                if (wanted == null) emptyList() else SeasonBatchDownloads.fetchStreamsForEpisode(
                    planned.episode, defaults.quality, defaults.maxBitrateMbps, defaults.audioLanguage, wanted,
                )
            },
            inspect = DownloadsRepository::inspectCompletedDownload,
            preferAudioLanguage = { item, language -> DownloadsRepository.setPreferredAudioLanguage(item.id, language) },
            verifyCompleted = { item -> DownloadsRepository.verifyCompletedDownload(item.id) },
            storageLimitBytes = DownloadsRepository::autoDownloadStorageLimitBytes,
            autoDownloadBytes = { DownloadsRepository.autoDownloadBytes() },
            minFreeBytes = DownloadsRepository.autoDownloadMinFreeBytes(),
        )
    }
}
