package com.nuvio.app.features.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.components.audioLanguageLabel
import com.nuvio.app.features.details.components.batchQualityLabel
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

internal val TripFillHourPresets = listOf(4, 8, 12)
internal const val TripFillMinHours = 1
internal const val TripFillMaxHours = 48
internal const val DefaultTripFillHours = 8

private const val MillisPerMinute = 60_000L

/**
 * Injectable dependencies for Trip ready inspection and "Fill for my trip" planning/execution.
 * Tests pass fakes here without changing Downloads screen behavior.
 */
internal data class TripReadyDeps(
    val collectShows: () -> List<TripShowCandidate> = { collectAppTripShowCandidates() },
    val buildPlan: suspend (hours: Int, shows: List<TripShowCandidate>) -> TripFillPlan = { hours, shows ->
        buildAppTripFillPlan(targetHours = hours, shows = shows)
    },
    val runner: TripFillRunner = TripFillHost.runner,
    val loadDiskInfo: suspend (List<DownloadItem>, Long) -> Map<String, TripItemDiskInfo> = { items, nowEpochMs ->
        loadAppTripDiskInfo(items = items, nowEpochMs = nowEpochMs)
    },
    val durationMsOf: (DownloadItem, Map<String, TripItemDiskInfo>) -> Long? = ::resolveAppItemDurationMs,
    val bytesPerSecondOf: (DownloadItem, Long) -> Long? = { item, nowMs ->
        DownloadsRepository.transferBytesPerSecond(item.id, nowMs)
    },
    val fetchMissingCompanions: (suspend (List<DownloadItem>) -> CompanionBackfillSummary)? = { items ->
        DownloadCompanions.app.backfill(items, DownloadsRepository::playableLocalFileUri)
    },
    val pauseDownload: (String) -> Unit = DownloadsRepository::pauseDownload,
    val resumeDownload: (String) -> Unit = DownloadsRepository::resumeDownload,
)

/** Converts a positive duration in milliseconds to whole `(hours, minutes)` components. */
internal fun computeTripDurationComponents(durationMs: Long, roundUpMinutes: Boolean = false): Pair<Int, Int> {
    if (durationMs <= 0L) return 0 to 0
    val totalMinutes = if (roundUpMinutes) {
        ((durationMs + MillisPerMinute - 1L) / MillisPerMinute).toInt().coerceAtLeast(1)
    } else {
        ((durationMs + (MillisPerMinute / 2L)) / MillisPerMinute).toInt().coerceAtLeast(1)
    }
    return (totalMinutes / 60) to (totalMinutes % 60)
}

@Composable
internal fun formatTripDuration(durationMs: Long, roundUpMinutes: Boolean = false): String {
    if (durationMs <= 0L) return stringResource(Res.string.downloads_trip_duration_zero)
    val (hours, minutes) = computeTripDurationComponents(durationMs, roundUpMinutes)
    return when {
        hours > 0 && minutes > 0 -> stringResource(
            Res.string.downloads_trip_duration_hr_min,
            pluralStringResource(Res.plurals.downloads_trip_duration_hours, hours, hours),
            pluralStringResource(Res.plurals.downloads_trip_duration_minutes, minutes, minutes),
        )
        hours > 0 -> pluralStringResource(Res.plurals.downloads_trip_duration_hours, hours, hours)
        else -> pluralStringResource(Res.plurals.downloads_trip_duration_minutes, minutes, minutes)
    }
}

@Composable
internal fun formatTripEtaText(etaSeconds: Long?): String = when {
    etaSeconds == null -> stringResource(Res.string.downloads_trip_eta_estimating)
    etaSeconds < 60L -> stringResource(Res.string.downloads_trip_eta_less_than_minute)
    else -> stringResource(
        Res.string.downloads_trip_eta_about,
        formatTripDuration(etaSeconds * 1_000L, roundUpMinutes = true),
    )
}

/** Reads duration, subtitles, skip times, and episode-list sidecar info on IO off the main thread. */
internal suspend fun loadAppTripDiskInfo(
    items: List<DownloadItem>,
    nowEpochMs: Long = DownloadsClock.nowEpochMs(),
    folderState: DownloadFolderState = DownloadFolders.controller.state.value,
    locationIdOf: (DownloadItem) -> String = DownloadFolders.controller::locationIdOf,
    resolveUri: (DownloadItem) -> String? = DownloadsRepository::playableLocalFileUri,
    inspect: (DownloadItem) -> DownloadedFileFacts? = DownloadsRepository::inspectCompletedDownload,
): Map<String, TripItemDiskInfo> = withContext(Dispatchers.IO) {
    val reachableCompleted = items.filter { item ->
        if (item.status != DownloadStatus.Completed || item.isPlaceholder) return@filter false
        if (item.id in folderState.movingIds) return@filter false
        val availability = folderState.location(locationIdOf(item))?.availability ?: FolderAvailability.Available
        availability == FolderAvailability.Available
    }

    val rawById = mutableMapOf<String, TripItemDiskInfo>()
    val runtimesByShow = mutableMapOf<String, MutableMap<Pair<Int, Int>, Int>>()

    for (item in reachableCompleted) {
        val uri = runCatching { resolveUri(item) }.getOrNull()
        val facts = runCatching { inspect(item) }.getOrNull()
        if (uri == null || facts?.exists == false) {
            rawById[item.id] = TripItemDiskInfo(fileExists = false)
            continue
        }
        val fileDurationMs = facts?.durationMs
        val subtitleCount = runCatching { DownloadSubtitles.savedCount(uri) }.getOrDefault(0)
        val skipTimesNote = runCatching { OfflineSkipTimes.app.note(uri) }.getOrNull()
        val sidecarRefused = runCatching { DownloadSubtitleStorage(uri).refused() }.getOrDefault(false)
        val episodeRuntimes = runCatching {
            EpisodeListSidecar.read(uri)
                ?.episodes
                ?.mapNotNull { ep -> ep.runtime?.takeIf { it > 0 }?.let { (ep.season to ep.episode) to it } }
                ?.toMap()
        }.getOrNull().orEmpty()
        if (episodeRuntimes.isNotEmpty() && item.isEpisode) {
            runtimesByShow.getOrPut(item.parentMetaId.trim()) { mutableMapOf() }.putAll(episodeRuntimes)
        }
        val canFetchCompanions = !sidecarRefused && (
            (subtitleCount == 0 && runCatching { DownloadSubtitles.isDue(item, uri, nowEpochMs) }.getOrDefault(false)) ||
                (skipTimesNote == null && runCatching { OfflineSkipTimes.app.isDue(item, uri) }.getOrDefault(false))
            )
        rawById[item.id] = TripItemDiskInfo(
            subtitleCount = subtitleCount,
            skipTimesNote = skipTimesNote,
            sidecarRefused = sidecarRefused,
            episodeRuntimes = episodeRuntimes,
            fileDurationMs = fileDurationMs,
            canFetchCompanions = canFetchCompanions,
        )
    }

    rawById.mapValues { (id, info) ->
        val item = reachableCompleted.firstOrNull { it.id == id }
        val showRuntimes = if (item != null && item.isEpisode) {
            runtimesByShow[item.parentMetaId.trim()].orEmpty()
        } else {
            info.episodeRuntimes
        }
        if (showRuntimes.isEmpty() || info.episodeRuntimes.size >= showRuntimes.size) {
            info
        } else {
            info.copy(episodeRuntimes = showRuntimes + info.episodeRuntimes)
        }
    }
}

/** Resolves playable length in milliseconds using container, metadata, sidecar, and watch progress. */
internal fun resolveAppItemDurationMs(
    item: DownloadItem,
    diskInfoByDownloadId: Map<String, TripItemDiskInfo>,
): Long? {
    val disk = diskInfoByDownloadId[item.id]
    val epKey = if (item.isEpisode && item.seasonNumber != null && item.episodeNumber != null) {
        item.seasonNumber to item.episodeNumber
    } else {
        null
    }
    val sidecarRuntime = epKey?.let { disk?.episodeRuntimes?.get(it) }
    val watchProgressMs = runCatching {
        WatchProgressRepository.progressForVideo(
            videoId = item.videoId,
            parentMetaId = item.parentMetaId,
            seasonNumber = item.seasonNumber,
            episodeNumber = item.episodeNumber,
        )?.durationMs
    }.getOrNull()
    return resolveItemDurationMs(
        fileDurationMs = disk?.fileDurationMs,
        runtimeMinutes = runCatching { runtimeMinutesOfDownload(item) }.getOrNull(),
        sidecarRuntimeMinutes = sidecarRuntime,
        watchProgressDurationMs = watchProgressMs,
    )
}

/**
 * Standalone Trip ready screen wrapper used when presented directly or in isolated UI tests.
 * Inside [DownloadsScreenContent], [tripReadyContent] renders inside the shared `NuvioScreen`.
 */
@Composable
internal fun TripReadyPanel(
    onBack: () -> Unit,
    onOpenDownload: (DownloadItem) -> Unit,
    onOpenShow: (showId: String, title: String) -> Unit,
    onChooseAnotherSource: ((DownloadItem) -> Unit)? = null,
    downloadsStateFlow: kotlinx.coroutines.flow.StateFlow<DownloadsUiState> = DownloadsRepository.uiState,
    folders: DownloadFolderController = DownloadFolders.controller,
    rechecks: DownloadRechecks? = DownloadRechecks.app,
    connectionHold: ConnectionHold = LimitedConnections.hold,
    nowEpochMs: () -> Long = { DownloadsClock.nowEpochMs() },
    tripDeps: TripReadyDeps = TripReadyDeps(),
) {
    val uiState by downloadsStateFlow.collectAsStateWithLifecycle()
    val folderState by folders.state.collectAsStateWithLifecycle()
    val holdState by connectionHold.state.collectAsStateWithLifecycle()
    val network by NetworkStatusRepository.uiState.collectAsStateWithLifecycle()
    val anyDownloading = uiState.items.any { it.status == DownloadStatus.Downloading }
    val now by produceState(initialValue = nowEpochMs(), anyDownloading) {
        value = nowEpochMs()
        while (anyDownloading) {
            delay(5_000L)
            value = nowEpochMs()
        }
    }
    val scope = rememberCoroutineScope()
    val controller = rememberTripReadyController(
        active = true,
        uiState = uiState,
        folderState = folderState,
        locationIdOf = if (folders.supported) folders::locationIdOf else { item -> item.locationId.orEmpty() },
        addressRequired = folders.supported,
        holdState = holdState,
        offline = network.isOfflineLike,
        nowEpochMs = now,
        rechecks = rechecks,
        tripDeps = tripDeps,
    )

    NuvioScreen {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.downloads_trip_ready_title),
                onBack = onBack,
            )
        }
        tripReadyContent(
            state = controller,
            onOpenShow = onOpenShow,
            onOpenDownload = onOpenDownload,
            onDownloadAnyway = connectionHold::downloadAnyway,
            onChooseAnotherSource = onChooseAnotherSource,
            onRecheckAll = rechecks?.let { checks ->
                { scope.launch { controller.runRecheckAll(checks) } }
            },
            onFetchMissingCompanions = controller.runFetchCompanions?.let { action ->
                { scope.launch { action() } }
            },
            onPauseDownload = tripDeps.pauseDownload,
            onResumeDownload = tripDeps.resumeDownload,
        )
    }
}

internal data class TripReadyUiSnapshot(
    val summary: TripReadySummary,
    val offline: Boolean,
    val holdReason: LimitedConnectionReason?,
    val selectedHours: Int,
    val isBuildingPlan: Boolean,
    val activePlan: TripFillPlan?,
    val runState: TripFillRunState,
    val isFetchingCompanions: Boolean,
    val onSelectHours: (Int) -> Unit,
    val onPreviewPlan: () -> Unit,
    val onStartPlan: () -> Unit,
    val onStopRun: () -> Unit,
    val onClearPlan: () -> Unit,
    val runRecheckAll: suspend (DownloadRechecks) -> Unit,
    val runFetchCompanions: (suspend () -> Unit)?,
)

@Composable
internal fun rememberTripReadyController(
    active: Boolean,
    uiState: DownloadsUiState,
    folderState: DownloadFolderState,
    locationIdOf: (DownloadItem) -> String,
    addressRequired: Boolean,
    holdState: ConnectionHoldState,
    offline: Boolean,
    nowEpochMs: Long,
    rechecks: DownloadRechecks?,
    tripDeps: TripReadyDeps,
): TripReadyUiSnapshot {
    val scope = rememberCoroutineScope()
    var diskInfoById by remember { mutableStateOf<Map<String, TripItemDiskInfo>>(emptyMap()) }
    var companionRefreshNonce by remember { mutableIntStateOf(0) }
    var isFetchingCompanions by remember { mutableStateOf(false) }

    LaunchedEffect(active, uiState.completedItems, folderState.locations, companionRefreshNonce) {
        if (active) {
            diskInfoById = withContext(Dispatchers.Default) {
                tripDeps.loadDiskInfo(uiState.items, nowEpochMs)
            }
        }
    }

    val summary = remember(
        uiState.items,
        folderState,
        addressRequired,
        nowEpochMs,
        offline,
        holdState.holdReason,
        diskInfoById,
    ) {
        buildTripReadySummary(
            items = uiState.items,
            folderState = folderState,
            locationIdOf = locationIdOf,
            addressRequired = addressRequired,
            nowEpochMs = nowEpochMs,
            offline = offline,
            limitedBy = holdState.holdReason,
            diskInfoByDownloadId = diskInfoById,
            durationMsOf = { item -> tripDeps.durationMsOf(item, diskInfoById) },
            bytesPerSecondOf = { item -> tripDeps.bytesPerSecondOf(item, nowEpochMs) },
        )
    }

    var selectedHours by rememberSaveable { mutableIntStateOf(DefaultTripFillHours) }
    var isBuildingPlan by remember { mutableStateOf(false) }
    var activePlan by remember { mutableStateOf<TripFillPlan?>(null) }
    val runState by tripDeps.runner.state.collectAsStateWithLifecycle()

    val fetchDoneText = stringResource(Res.string.downloads_trip_ready_fetch_extras_done)
    val fetchOfflineText = stringResource(Res.string.downloads_trip_ready_fetch_extras_offline)
    val checkClearText = stringResource(Res.string.downloads_check_all_clear)
    val checkNothingText = stringResource(Res.string.downloads_check_all_nothing)

    val runRecheckAll: suspend (DownloadRechecks) -> Unit = { checks ->
        val result = checks.checkAll()
        val message = when {
            result.flagged > 0 -> getString(Res.string.downloads_check_all_found, result.flagged)
            result.looked == 0 -> checkNothingText
            else -> checkClearText
        }
        NuvioToastController.show(message)
        companionRefreshNonce++
    }

    val runFetchCompanions: (suspend () -> Unit)? = tripDeps.fetchMissingCompanions?.let { fetcher ->
        {
            if (!isFetchingCompanions) {
                if (offline) {
                    NuvioToastController.show(fetchOfflineText)
                } else {
                    isFetchingCompanions = true
                    try {
                        fetcher(uiState.items)
                        companionRefreshNonce++
                        NuvioToastController.show(fetchDoneText)
                    } finally {
                        isFetchingCompanions = false
                    }
                }
            }
        }
    }

    return TripReadyUiSnapshot(
        summary = summary,
        offline = offline,
        holdReason = holdState.holdReason,
        selectedHours = selectedHours,
        isBuildingPlan = isBuildingPlan,
        activePlan = activePlan,
        runState = runState,
        isFetchingCompanions = isFetchingCompanions,
        onSelectHours = { hours ->
            selectedHours = hours.coerceIn(TripFillMinHours, TripFillMaxHours)
        },
        onPreviewPlan = {
            if (!isBuildingPlan && !runState.isRunning) {
                tripDeps.runner.clearDone()
                isBuildingPlan = true
                scope.launch {
                    try {
                        val shows = withContext(Dispatchers.Default) { tripDeps.collectShows() }
                        activePlan = withContext(Dispatchers.Default) {
                            tripDeps.buildPlan(selectedHours, shows)
                        }
                    } finally {
                        isBuildingPlan = false
                    }
                }
            }
        },
        onStartPlan = {
            val plan = activePlan
            if (plan != null && !runState.isRunning) {
                tripDeps.runner.start(plan)
            }
        },
        onStopRun = { tripDeps.runner.stop() },
        onClearPlan = {
            activePlan = null
            tripDeps.runner.clearDone()
        },
        runRecheckAll = runRecheckAll,
        runFetchCompanions = runFetchCompanions,
    )
}

internal fun LazyListScope.tripReadyContent(
    state: TripReadyUiSnapshot,
    onOpenShow: (showId: String, title: String) -> Unit,
    onOpenDownload: (DownloadItem) -> Unit,
    onDownloadAnyway: () -> Unit,
    onChooseAnotherSource: ((DownloadItem) -> Unit)?,
    onRecheckAll: (() -> Unit)?,
    onFetchMissingCompanions: (() -> Unit)?,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
) {
    val summary = state.summary

    item(key = "trip-overview") {
        TripOverviewCard(summary = summary)
    }

    if (state.holdReason != null && (summary.downloading.isNotEmpty() || summary.pausedItems.any { it.first.pausedByConnection })) {
        item(key = "trip-connection-hold") {
            ConnectionHoldLine(reason = state.holdReason, onDownloadAnyway = onDownloadAnyway)
        }
    }

    item(key = "trip-fill-card") {
        TripFillCard(
            selectedHours = state.selectedHours,
            isBuildingPlan = state.isBuildingPlan,
            activePlan = state.activePlan,
            runState = state.runState,
            onSelectHours = state.onSelectHours,
            onPreviewPlan = state.onPreviewPlan,
            onStartPlan = state.onStartPlan,
            onStopRun = state.onStopRun,
            onClearPlan = state.onClearPlan,
            onDownloadAnyway = onDownloadAnyway,
        )
    }

    if (summary.downloading.isNotEmpty()) {
        item(key = "trip-downloading-header") {
            TripDownloadingHeader(totalEtaSeconds = summary.totalDownloadingEtaSeconds)
        }
        items(items = summary.downloading, key = { "trip-downloading:${it.item.id}" }) { entry ->
            TripDownloadingRow(entry = entry, onPause = { onPauseDownload(entry.item.id) })
        }
    }

    if (summary.pausedItems.isNotEmpty()) {
        item(key = "trip-paused-header") {
            NuvioSectionLabel(
                text = stringResource(Res.string.downloads_trip_paused_heading),
                modifier = Modifier
                    .padding(horizontal = NuvioTokens.Space.s14, vertical = 6.dp)
                    .semantics { heading() },
            )
        }
        items(items = summary.pausedItems, key = { "trip-paused:${it.first.id}" }) { (item, rowStatus) ->
            TripPausedRow(
                item = item,
                rowStatus = rowStatus,
                onPause = { onPauseDownload(item.id) },
                onResume = { onResumeDownload(item.id) },
            )
        }
    }

    if (summary.attentionItems.isNotEmpty()) {
        item(key = "trip-attention-header") {
            TripAttentionHeader(
                attentionItems = summary.attentionItems,
                isFetchingCompanions = state.isFetchingCompanions,
                onRecheckAll = onRecheckAll,
                onFetchMissingCompanions = onFetchMissingCompanions,
            )
        }
        items(items = summary.attentionItems, key = { "trip-attention:${it.item.id}" }) { entry ->
            TripAttentionRow(entry = entry, onChooseAnotherSource = onChooseAnotherSource)
        }
    }

    if (summary.unpluggedDrives.isNotEmpty()) {
        item(key = "trip-unplugged-header") {
            NuvioSectionLabel(
                text = stringResource(Res.string.downloads_trip_unplugged_heading),
                modifier = Modifier
                    .padding(horizontal = NuvioTokens.Space.s14, vertical = 6.dp)
                    .semantics { heading() },
            )
        }
        items(items = summary.unpluggedDrives, key = { "trip-unplugged:${it.location.id}" }) { group ->
            TripUnpluggedDriveCard(group = group)
        }
    }

    if (summary.titles.isNotEmpty()) {
        item(key = "trip-titles-header") {
            NuvioSectionLabel(
                text = stringResource(Res.string.downloads_trip_by_title_heading),
                modifier = Modifier
                    .padding(horizontal = NuvioTokens.Space.s14, vertical = 6.dp)
                    .semantics { heading() },
            )
        }
        items(items = summary.titles, key = { "trip-title:${it.key}" }) { titleSummary ->
            TripTitleRow(
                entry = titleSummary,
                onClick = {
                    if (titleSummary.isEpisode) {
                        onOpenShow(titleSummary.parentMetaId, titleSummary.title)
                    } else {
                        titleSummary.items.firstOrNull()?.let(onOpenDownload)
                    }
                },
            )
        }
    }

    item(key = "trip-bottom-spacer") {
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun TripOverviewCard(summary: TripReadySummary) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s14, vertical = NuvioTokens.Space.s8),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(Res.string.downloads_trip_ready_offline_heading),
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = formatTripDuration(summary.totalPlayableOfflineMs),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = if (summary.totalPlayableCount == 0) {
                    stringResource(Res.string.downloads_trip_ready_offline_empty)
                } else {
                    val epText = pluralStringResource(
                        Res.plurals.downloads_trip_counts_episodes,
                        summary.totalPlayableEpisodeCount,
                        summary.totalPlayableEpisodeCount,
                    )
                    val movieText = pluralStringResource(
                        Res.plurals.downloads_trip_counts_movies,
                        summary.totalPlayableMovieCount,
                        summary.totalPlayableMovieCount,
                    )
                    val counts = when {
                        summary.totalPlayableEpisodeCount > 0 && summary.totalPlayableMovieCount > 0 ->
                            stringResource(Res.string.downloads_trip_summary_pair, epText, movieText)
                        summary.totalPlayableEpisodeCount > 0 -> epText
                        else -> movieText
                    }
                    stringResource(
                        Res.string.downloads_trip_summary_pair,
                        stringResource(Res.string.downloads_trip_ready_offline_subtitle),
                        counts,
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )
            if (summary.totalPlayableUnknownDurationCount > 0) {
                Text(
                    text = pluralStringResource(
                        Res.plurals.downloads_trip_ready_unknown_length_note,
                        summary.totalPlayableUnknownDurationCount,
                        summary.totalPlayableUnknownDurationCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
            val highlights = buildList {
                if (summary.downloading.isNotEmpty()) {
                    add(pluralStringResource(Res.plurals.downloads_trip_overview_downloading, summary.downloading.size, summary.downloading.size))
                }
                if (summary.attentionItems.isNotEmpty()) {
                    add(pluralStringResource(Res.plurals.downloads_trip_overview_attention, summary.attentionItems.size, summary.attentionItems.size))
                }
                if (summary.unpluggedTotalCount > 0) {
                    add(pluralStringResource(Res.plurals.downloads_trip_overview_unplugged, summary.unpluggedTotalCount, summary.unpluggedTotalCount))
                }
            }
            if (highlights.isNotEmpty()) {
                Text(
                    text = highlights.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun TripFillCard(
    selectedHours: Int,
    isBuildingPlan: Boolean,
    activePlan: TripFillPlan?,
    runState: TripFillRunState,
    onSelectHours: (Int) -> Unit,
    onPreviewPlan: () -> Unit,
    onStartPlan: () -> Unit,
    onStopRun: () -> Unit,
    onClearPlan: () -> Unit,
    onDownloadAnyway: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val minusDescription = stringResource(Res.string.downloads_trip_fill_custom_minus)
    val plusDescription = stringResource(Res.string.downloads_trip_fill_custom_plus)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s14, vertical = NuvioTokens.Space.s8),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(Res.string.downloads_trip_fill_heading),
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(Res.string.downloads_trip_fill_description),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TripFillHourPresets.forEach { preset ->
                    val selected = selectedHours == preset
                    if (selected) {
                        Button(
                            enabled = !runState.isRunning && !isBuildingPlan,
                            onClick = { onSelectHours(preset) },
                        ) {
                            Text(stringResource(Res.string.downloads_trip_fill_hours_option, preset))
                        }
                    } else {
                        OutlinedButton(
                            enabled = !runState.isRunning && !isBuildingPlan,
                            onClick = { onSelectHours(preset) },
                        ) {
                            Text(stringResource(Res.string.downloads_trip_fill_hours_option, preset))
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                OutlinedButton(
                    enabled = !runState.isRunning && !isBuildingPlan && selectedHours > TripFillMinHours,
                    modifier = Modifier.semantics { contentDescription = minusDescription },
                    onClick = { onSelectHours(selectedHours - 1) },
                ) {
                    Text("-")
                }
                Text(
                    text = stringResource(Res.string.downloads_trip_fill_hours_option, selectedHours),
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.colors.textPrimary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                OutlinedButton(
                    enabled = !runState.isRunning && !isBuildingPlan && selectedHours < TripFillMaxHours,
                    modifier = Modifier.semantics { contentDescription = plusDescription },
                    onClick = { onSelectHours(selectedHours + 1) },
                ) {
                    Text("+")
                }
            }

            Button(
                enabled = !isBuildingPlan && !runState.isRunning,
                onClick = onPreviewPlan,
            ) {
                Text(stringResource(Res.string.downloads_trip_fill_plan_button, selectedHours))
            }

            if (isBuildingPlan) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LinearProgressIndicator(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(Res.string.downloads_trip_fill_planning),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textSecondary,
                    )
                }
            }

            if (runState.isRunning) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val progress = if (runState.totalEpisodes > 0) {
                        runState.processedEpisodes.toFloat() / runState.totalEpisodes.toFloat()
                    } else {
                        0f
                    }
                    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(
                            Res.string.downloads_trip_fill_running,
                            (runState.processedEpisodes + 1).coerceAtMost(runState.totalEpisodes.coerceAtLeast(1)),
                            runState.totalEpisodes,
                            runState.currentLabel.orEmpty(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textPrimary,
                    )
                    OutlinedButton(onClick = onStopRun) {
                        Text(stringResource(Res.string.downloads_trip_fill_stop_queueing))
                    }
                }
            }

            runState.doneSummary?.let { done ->
                TripFillDoneBanner(done = done)
            }

            if (activePlan != null && !runState.isRunning) {
                TripFillPlanPreview(
                    plan = activePlan,
                    showStartButton = activePlan.plannedEpisodes.isNotEmpty() && runState.doneSummary == null,
                    onStartPlan = onStartPlan,
                    onClearPlan = onClearPlan,
                    onDownloadAnyway = onDownloadAnyway,
                )
            }
        }
    }
}

@Composable
private fun TripFillDoneBanner(done: TripFillDoneSummary) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(Res.string.downloads_trip_fill_done_queued, done.queuedCount, done.totalPlanned),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = tokens.colors.textPrimary,
            )
            if (done.waitingOnHoldCount > 0) {
                Text(
                    text = stringResource(Res.string.downloads_trip_fill_done_waiting, done.waitingOnHoldCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textSecondary,
                )
            }
            if (done.failedCount > 0) {
                Text(
                    text = stringResource(Res.string.downloads_trip_fill_done_failed, done.failedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (done.stoppedEarly) {
                Text(
                    text = stringResource(Res.string.downloads_trip_fill_done_stopped),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun TripFillPlanPreview(
    plan: TripFillPlan,
    showStartButton: Boolean,
    onStartPlan: () -> Unit,
    onClearPlan: () -> Unit,
    onDownloadAnyway: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.downloads_trip_fill_plan_heading, plan.requestedHours),
            style = MaterialTheme.typography.titleSmall,
            color = tokens.colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )

        if (plan.plannedEpisodes.isNotEmpty()) {
            val countText = pluralStringResource(
                Res.plurals.downloads_trip_counts_episodes,
                plan.plannedEpisodes.size,
                plan.plannedEpisodes.size,
            )
            val durationText = formatTripDuration(plan.totalPlannedMinutes * MillisPerMinute)
            val sizeText = formatBytes(plan.totalEstimatedBytes)
            Text(
                text = stringResource(Res.string.downloads_trip_fill_plan_summary, countText, durationText, sizeText),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(
                text = stringResource(Res.string.downloads_trip_fill_plan_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )
        }

        val prefsParts = buildList {
            add(batchQualityLabel(plan.qualityDefaults.quality))
            plan.qualityDefaults.maxBitrateMbps?.let {
                add(stringResource(Res.string.downloads_trip_fill_prefs_max_bitrate, it))
            }
            plan.qualityDefaults.audioLanguage?.let {
                add(audioLanguageLabel(it))
            }
        }
        Text(
            text = stringResource(Res.string.downloads_trip_fill_prefs, prefsParts.joinToString(", ")),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textSecondary,
        )

        val storageTexts = buildList {
            plan.remainingStorageLimitBytes?.let {
                add(stringResource(Res.string.downloads_trip_fill_storage_budget, formatBytes(it)))
            }
            plan.freeDiskBytes?.let {
                add(stringResource(Res.string.downloads_trip_fill_disk_free, formatBytes(it)))
            }
        }
        if (storageTexts.isNotEmpty()) {
            Text(
                text = storageTexts.joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }

        plan.connectionHoldReason?.let { reason ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.downloads_trip_fill_hold_notice, stringResource(connectionHoldTitle(reason))),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    val anywayDescription = stringResource(Res.string.downloads_held_download_anyway_description)
                    TextButton(
                        modifier = Modifier.semantics { contentDescription = anywayDescription },
                        onClick = onDownloadAnyway,
                    ) {
                        Text(stringResource(Res.string.downloads_held_download_anyway))
                    }
                }
            }
        }

        val stopNote = when (plan.stoppedReason) {
            TripPlanStopReason.TargetReached -> null
            TripPlanStopReason.StorageLimitReached -> stringResource(Res.string.downloads_trip_fill_stop_storage_limit)
            TripPlanStopReason.LowDiskSpace -> stringResource(Res.string.downloads_trip_fill_stop_low_disk)
            TripPlanStopReason.NoMoreEpisodes -> stringResource(Res.string.downloads_trip_fill_stop_no_more_episodes)
            TripPlanStopReason.NoShows -> stringResource(Res.string.downloads_trip_fill_stop_no_shows)
            TripPlanStopReason.Offline -> stringResource(Res.string.downloads_trip_fill_stop_offline)
        }
        if (stopNote != null) {
            Text(
                text = stopNote,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textSecondary,
            )
        }

        plan.skippedShows.forEach { skipped ->
            val reasonText = when (skipped.reason) {
                TripSkippedReason.FolderNotConnected ->
                    stringResource(Res.string.downloads_trip_fill_skipped_drive, skipped.show.title)
                TripSkippedReason.CouldNotLoadEpisodes ->
                    stringResource(Res.string.downloads_trip_fill_skipped_meta, skipped.show.title)
                TripSkippedReason.NoMatchingSources ->
                    stringResource(Res.string.downloads_trip_fill_skipped_no_source, skipped.show.title)
            }
            Text(
                text = reasonText,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }

        plan.plannedEpisodes.forEach { planned ->
            val epCode = "S${planned.episode.seasonNumber.toString().padStart(2, '0')}E${planned.episode.episodeNumber.toString().padStart(2, '0')}"
            val rowTitle = if (planned.episode.title.isNullOrBlank()) {
                stringResource(Res.string.downloads_trip_fill_episode_row, planned.show.title, epCode)
            } else {
                stringResource(Res.string.downloads_trip_fill_episode_row, planned.show.title, "$epCode • ${planned.episode.title}")
            }
            val lengthText = formatTripDuration(planned.effectiveRuntimeMinutes * MillisPerMinute)
            val cacheText = stringResource(
                if (planned.isCached) Res.string.downloads_trip_fill_cached else Res.string.downloads_trip_fill_not_cached,
            )
            val sizeText = if (planned.sizeEstimated) {
                stringResource(Res.string.downloads_trip_fill_estimated, formatBytes(planned.estimatedBytes))
            } else {
                formatBytes(planned.estimatedBytes)
            }
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    text = rowTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$lengthText • $sizeText • $cacheText",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showStartButton) {
                Button(onClick = onStartPlan) {
                    Text(
                        stringResource(
                            if (plan.connectionHoldReason != null) {
                                Res.string.downloads_trip_fill_start_waiting
                            } else {
                                Res.string.downloads_trip_fill_start
                            },
                        ),
                    )
                }
            }
            TextButton(onClick = onClearPlan) {
                Text(stringResource(Res.string.downloads_trip_fill_cancel_plan))
            }
        }
    }
}

@Composable
private fun TripDownloadingHeader(totalEtaSeconds: Long?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s14, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NuvioSectionLabel(
            text = stringResource(Res.string.downloads_trip_downloading_heading),
            modifier = Modifier.semantics { heading() },
        )
        if (totalEtaSeconds != null) {
            val etaValue = if (totalEtaSeconds < 60L) {
                stringResource(Res.string.downloads_trip_eta_less_than_minute)
            } else {
                formatTripDuration(totalEtaSeconds * 1_000L, roundUpMinutes = true)
            }
            Text(
                text = stringResource(Res.string.downloads_trip_eta_total, etaValue),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.nuvio.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun TripDownloadingRow(
    entry: TripDownloadingEntry,
    onPause: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val name = entry.item.nameForLabels()
    val pauseDescription = stringResource(Res.string.downloads_pause_description, name)
    val bytesText = if (entry.totalBytes != null && entry.totalBytes > 0L) {
        "${formatBytes(entry.downloadedBytes)} / ${formatBytes(entry.totalBytes)}"
    } else {
        formatBytes(entry.downloadedBytes)
    }
    val etaOrPhase = if (entry.phase == DownloadPhase.Downloading) {
        val etaText = formatTripEtaText(entry.etaSeconds)
        val speedText = entry.bytesPerSecond?.takeIf { it > 0L }?.let { "${formatBytes(it)}/s" }
        listOfNotNull(bytesText, speedText, etaText).joinToString(" • ")
    } else {
        statusText(item = entry.item, status = entry.rowStatus)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s14, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.totalBytes != null && entry.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = { entry.progressFraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = etaOrPhase,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textSecondary,
            )
        }
        IconButton(onClick = onPause) {
            Icon(imageVector = Icons.Rounded.Pause, contentDescription = pauseDescription)
        }
    }
}

@Composable
private fun TripPausedRow(
    item: DownloadItem,
    rowStatus: DownloadRowStatus,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val name = item.nameForLabels()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s14, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = statusText(item = item, status = rowStatus),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textSecondary,
            )
        }
        if (item.pausedByConnection) {
            val pauseDescription = stringResource(Res.string.downloads_pause_description, name)
            IconButton(onClick = onPause) {
                Icon(imageVector = Icons.Rounded.Pause, contentDescription = pauseDescription)
            }
        } else {
            val resumeDescription = stringResource(Res.string.downloads_resume_description, name)
            IconButton(onClick = onResume) {
                Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = resumeDescription)
            }
        }
    }
}

@Composable
private fun TripAttentionHeader(
    attentionItems: List<TripAttentionEntry>,
    isFetchingCompanions: Boolean,
    onRecheckAll: (() -> Unit)?,
    onFetchMissingCompanions: (() -> Unit)?,
) {
    val needsCheck = attentionItems.any { it.isUnverified || it.isSuspect || it.isFlaggedPlaceholder }
    val needsCompanions = attentionItems.any {
        !it.sidecarRefused && (it.missingSubtitles || it.skipTimesStatus == TripSkipTimesStatus.NotSaved)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s14, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NuvioSectionLabel(
            text = stringResource(Res.string.downloads_trip_attention_heading),
            modifier = Modifier.semantics { heading() },
        )
        if ((needsCheck && onRecheckAll != null) || (needsCompanions && onFetchMissingCompanions != null)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (needsCheck && onRecheckAll != null) {
                    TextButton(onClick = onRecheckAll) {
                        Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(Res.string.downloads_trip_ready_check_files))
                    }
                }
                if (needsCompanions && onFetchMissingCompanions != null) {
                    TextButton(enabled = !isFetchingCompanions, onClick = onFetchMissingCompanions) {
                        Text(stringResource(Res.string.downloads_trip_ready_fetch_extras))
                    }
                }
            }
        }
    }
}

@Composable
private fun TripAttentionRow(
    entry: TripAttentionEntry,
    onChooseAnotherSource: ((DownloadItem) -> Unit)?,
) {
    val tokens = MaterialTheme.nuvio
    val name = entry.item.nameForLabels()
    val issues = buildList {
        if (entry.isFlaggedPlaceholder) add(stringResource(Res.string.downloads_trip_issue_flagged))
        if (entry.isFileMissing) add(stringResource(Res.string.downloads_trip_issue_file_missing))
        if (entry.isSuspect) add(stringResource(Res.string.downloads_trip_issue_suspect))
        if (entry.isUnverified) add(stringResource(Res.string.downloads_trip_issue_unverified))
        if (entry.sidecarRefused) add(stringResource(Res.string.downloads_trip_issue_sidecar_refused))
        if (entry.missingSubtitles) add(stringResource(Res.string.downloads_trip_issue_no_subtitles))
        when (entry.skipTimesStatus) {
            TripSkipTimesStatus.Present -> Unit
            TripSkipTimesStatus.NotSaved -> add(stringResource(Res.string.downloads_trip_issue_no_skip_times))
            TripSkipTimesStatus.NoneFound -> add(stringResource(Res.string.downloads_trip_issue_skip_times_none_found))
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s14, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = issues.joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.isFlaggedPlaceholder || entry.isFileMissing || entry.isSuspect) {
                    MaterialTheme.colorScheme.error
                } else {
                    tokens.colors.textSecondary
                },
            )
        }
        if ((entry.isFlaggedPlaceholder || entry.isSuspect) && onChooseAnotherSource != null) {
            val chooseDescription = stringResource(Res.string.downloads_choose_another_source_description, name)
            TextButton(
                modifier = Modifier.semantics { contentDescription = chooseDescription },
                onClick = { onChooseAnotherSource(entry.item) },
            ) {
                Text(stringResource(Res.string.downloads_choose_another_source))
            }
        }
    }
}

@Composable
private fun TripUnpluggedDriveCard(group: TripUnpluggedDriveGroup) {
    val tokens = MaterialTheme.nuvio
    val header = when (group.location.availability) {
        FolderAvailability.NotConnected ->
            stringResource(Res.string.downloads_trip_unplugged_drive_not_connected, group.location.name)
        FolderAvailability.NotFound ->
            stringResource(Res.string.downloads_trip_unplugged_folder_not_found, group.location.name)
        FolderAvailability.NotSetUp ->
            stringResource(Res.string.downloads_trip_unplugged_folder_not_set_up, group.location.name)
        FolderAvailability.Available -> group.location.name
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s14, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = header,
                    style = MaterialTheme.typography.titleSmall,
                    color = tokens.colors.textPrimary,
                )
                if (group.knownDurationMs > 0L) {
                    Text(
                        text = formatTripDuration(group.knownDurationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textSecondary,
                    )
                }
            }
            group.items.forEach { item ->
                val text = if (item.status == DownloadStatus.Completed) {
                    stringResource(Res.string.downloads_trip_unplugged_item_completed, item.nameForLabels())
                } else {
                    stringResource(Res.string.downloads_trip_unplugged_item_waiting, item.nameForLabels())
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TripTitleRow(
    entry: TripReadyTitleSummary,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val actionLabel = if (entry.isEpisode) {
        stringResource(Res.string.downloads_trip_open_show_description, entry.title)
    } else {
        stringResource(Res.string.downloads_trip_play_movie_description, entry.title)
    }
    val durationText = when {
        entry.totalKnownDurationMs > 0L -> formatTripDuration(entry.totalKnownDurationMs)
        entry.unknownDurationCount > 0 -> stringResource(Res.string.downloads_trip_duration_unknown)
        else -> formatTripDuration(0L)
    }
    val sizeText = formatBytes(entry.playableBytes)
    val subtitle = if (entry.isEpisode) {
        val countText = pluralStringResource(
            Res.plurals.downloads_trip_counts_episodes,
            entry.episodeCount,
            entry.episodeCount,
        )
        val base = stringResource(Res.string.downloads_trip_summary_pair, countText, sizeText)
        if (entry.totalKnownDurationMs > 0L && entry.unknownDurationCount > 0) {
            val unknownText = pluralStringResource(
                Res.plurals.downloads_trip_ready_unknown_length_note,
                entry.unknownDurationCount,
                entry.unknownDurationCount,
            )
            stringResource(Res.string.downloads_trip_summary_pair, base, unknownText)
        } else {
            base
        }
    } else {
        sizeText
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = actionLabel, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = actionLabel }
            .padding(horizontal = NuvioTokens.Space.s14, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = durationText,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
