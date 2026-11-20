package com.nuvio.app.features.downloads

/** What was read from disk beside a completed, reachable download for Trip ready reporting. */
internal data class TripItemDiskInfo(
    val fileExists: Boolean = true,
    val subtitleCount: Int = 0,
    val skipTimesNote: SkipTimesNote? = null,
    val sidecarRefused: Boolean = false,
    /** Episode runtimes in minutes by `(season, episode)`, from the saved episode-list sidecar. */
    val episodeRuntimes: Map<Pair<Int, Int>, Int> = emptyMap(),
    val fileDurationMs: Long? = null,
    val canFetchCompanions: Boolean = false,
)

internal enum class TripSkipTimesStatus {
    Present,
    NotSaved,
    NoneFound,
}

internal data class TripReadyTitleSummary(
    val key: String,
    val title: String,
    val isEpisode: Boolean,
    val parentMetaId: String,
    val parentMetaType: String,
    val items: List<DownloadItem>,
    val episodeCount: Int,
    val totalKnownDurationMs: Long,
    val unknownDurationCount: Int,
    val playableBytes: Long,
) {
    val totalKnownHours: Double
        get() = totalKnownDurationMs / MillisPerHourDouble
}

internal data class TripDownloadingEntry(
    val item: DownloadItem,
    val phase: DownloadPhase,
    val progressFraction: Float,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val bytesPerSecond: Long?,
    val etaSeconds: Long?,
    val rowStatus: DownloadRowStatus = DownloadRowStatus(phase = phase, isAuto = item.autoDownloadRuleKey != null),
)

internal data class TripAttentionEntry(
    val item: DownloadItem,
    val isFlaggedPlaceholder: Boolean = false,
    val isSuspect: Boolean = false,
    val isUnverified: Boolean = false,
    val isFileMissing: Boolean = false,
    val missingSubtitles: Boolean = false,
    val skipTimesStatus: TripSkipTimesStatus = TripSkipTimesStatus.Present,
    val sidecarRefused: Boolean = false,
    val canFetchCompanions: Boolean = false,
) {
    val hasAnyIssue: Boolean
        get() = isFlaggedPlaceholder ||
            isSuspect ||
            isUnverified ||
            isFileMissing ||
            missingSubtitles ||
            skipTimesStatus != TripSkipTimesStatus.Present ||
            sidecarRefused
}

internal data class TripUnpluggedDriveGroup(
    val location: DownloadLocationInfo,
    val items: List<DownloadItem>,
    val completedCount: Int,
    val waitingCount: Int,
    val knownDurationMs: Long,
    val unknownDurationCount: Int,
)

internal data class TripReadySummary(
    val totalPlayableOfflineMs: Long,
    val totalPlayableCount: Int,
    val totalPlayableEpisodeCount: Int,
    val totalPlayableMovieCount: Int,
    val totalPlayableUnknownDurationCount: Int,
    val titles: List<TripReadyTitleSummary>,
    val downloading: List<TripDownloadingEntry>,
    val totalDownloadingEtaSeconds: Long?,
    val pausedItems: List<Pair<DownloadItem, DownloadRowStatus>>,
    val attentionItems: List<TripAttentionEntry>,
    val unpluggedDrives: List<TripUnpluggedDriveGroup>,
    val unpluggedTotalCount: Int,
) {
    val totalPlayableOfflineHours: Double
        get() = totalPlayableOfflineMs / MillisPerHourDouble
}

private const val MillisPerHourDouble = 3_600_000.0
private const val MillisPerMinute = 60_000L

/**
 * Whether a completed download counts as verified and not flagged for offline trip playback:
 * checked by source hash, server digest, announced size, or decoded picture frames, and neither
 * a placeholder clip nor flagged as suspect.
 */
internal fun DownloadItem.isVerifiedForTrip(): Boolean {
    if (status != DownloadStatus.Completed) return false
    if (isPlaceholder || framesCheck == DownloadFramesCheck.NotTheRealVideo) return false
    if (framesCheck == DownloadFramesCheck.Suspect) return false
    if (localFileUri.isNullOrBlank()) return false
    return verifiedBy == DownloadVerification.Hash ||
        verifiedBy == DownloadVerification.Digest ||
        verifiedBy == DownloadVerification.Size ||
        framesCheck == DownloadFramesCheck.Passed
}

/**
 * Resolves the playable length in milliseconds for a download from the best available source:
 * container duration, loaded metadata runtime, offline episode sidecar runtime, or saved watch
 * progress duration. Returns null when none is known.
 */
internal fun resolveItemDurationMs(
    fileDurationMs: Long? = null,
    runtimeMinutes: Int? = null,
    sidecarRuntimeMinutes: Int? = null,
    watchProgressDurationMs: Long? = null,
): Long? =
    fileDurationMs?.takeIf { it > 0L }
        ?: runtimeMinutes?.takeIf { it > 0 }?.let { it * MillisPerMinute }
        ?: sidecarRuntimeMinutes?.takeIf { it > 0 }?.let { it * MillisPerMinute }
        ?: watchProgressDurationMs?.takeIf { it > 0L }

/**
 * Computes the full Trip ready snapshot from the current downloads list, drive availability,
 * live transfer speeds, companion sidecar checks, and item durations. Pure and deterministic.
 */
internal fun buildTripReadySummary(
    items: List<DownloadItem>,
    folderState: DownloadFolderState = DownloadFolderState(),
    locationIdOf: (DownloadItem) -> String = { it.locationId.orEmpty() },
    addressRequired: Boolean = false,
    nowEpochMs: Long = 0L,
    offline: Boolean = false,
    limitedBy: LimitedConnectionReason? = null,
    diskInfoByDownloadId: Map<String, TripItemDiskInfo> = emptyMap(),
    durationMsOf: (DownloadItem) -> Long? = { null },
    bytesPerSecondOf: (DownloadItem) -> Long? = { null },
): TripReadySummary {
    val locationAvailabilityByItem = items.associate { item ->
        val location = folderState.location(locationIdOf(item))
        val availability = location?.availability ?: FolderAvailability.Available
        item.id to (location to availability)
    }

    fun isPlayableVerified(item: DownloadItem): Boolean {
        if (!item.isVerifiedForTrip()) return false
        if (diskInfoByDownloadId[item.id]?.fileExists == false) return false
        val (_, availability) = locationAvailabilityByItem.getValue(item.id)
        if (availability != FolderAvailability.Available) return false
        if (item.id in folderState.movingIds) return false
        if (addressRequired && item.localFileUri == null) return false
        return true
    }

    // Deduplicate verified playable copies of the same logical episode/movie so hours aren't double-counted.
    val verifiedPlayableItems = items
        .filter(::isPlayableVerified)
        .groupBy { it.logicalContentKey }
        .values
        .map { copies ->
            copies.maxByOrNull { copy ->
                val hasDuration = if (durationMsOf(copy) != null) 1L else 0L
                (hasDuration shl 40) + copy.moveBytes.coerceAtMost((1L shl 40) - 1L)
            } ?: copies.first()
        }

    var totalPlayableOfflineMs = 0L
    var totalPlayableUnknownDurationCount = 0
    val titleGroups = verifiedPlayableItems
        .groupBy { if (it.isEpisode) "show:${it.parentMetaId.trim()}" else "movie:${it.logicalContentKey}" }
        .map { (key, groupItems) ->
            val sorted = groupItems.sortedForSeriesDownloads()
            val first = sorted.first()
            var groupDurationMs = 0L
            var groupUnknown = 0
            sorted.forEach { entry ->
                val ms = durationMsOf(entry)
                if (ms != null && ms > 0L) {
                    groupDurationMs += ms
                } else {
                    groupUnknown++
                }
            }
            totalPlayableOfflineMs += groupDurationMs
            totalPlayableUnknownDurationCount += groupUnknown
            TripReadyTitleSummary(
                key = key,
                title = first.title,
                isEpisode = first.isEpisode,
                parentMetaId = first.parentMetaId,
                parentMetaType = first.parentMetaType,
                items = sorted,
                episodeCount = if (first.isEpisode) sorted.size else 0,
                totalKnownDurationMs = groupDurationMs,
                unknownDurationCount = groupUnknown,
                playableBytes = sorted.sumOf { it.moveBytes },
            )
        }
        .sortedWith(compareBy({ it.title.lowercase() }, { it.key }))

    val downloadingEntries = items
        .filter { it.status == DownloadStatus.Downloading }
        .map { item ->
            val (_, availability) = locationAvailabilityByItem.getValue(item.id)
            val moving = item.id in folderState.movingIds
            val rowStatus = downloadRowStatus(
                item = item,
                nowEpochMs = nowEpochMs,
                offline = offline,
                location = availability,
                moving = moving,
                addressRequired = addressRequired,
                limitedBy = limitedBy,
            )
            val bps = if (rowStatus.phase == DownloadPhase.Downloading) bytesPerSecondOf(item) else null
            val eta = if (rowStatus.phase == DownloadPhase.Downloading) {
                estimateDownloadEtaSeconds(item, bps, nowEpochMs, offline)
            } else {
                null
            }
            TripDownloadingEntry(
                item = item,
                phase = rowStatus.phase,
                progressFraction = item.progressFraction,
                downloadedBytes = item.downloadedBytes,
                totalBytes = item.totalBytes,
                bytesPerSecond = bps,
                etaSeconds = eta,
                rowStatus = rowStatus,
            )
        }

    val unpluggedItemsByLocationId = linkedMapOf<String, Pair<DownloadLocationInfo, MutableList<DownloadItem>>>()
    items.forEach { item ->
        val (location, availability) = locationAvailabilityByItem.getValue(item.id)
        if (location != null && availability != FolderAvailability.Available &&
            (item.status == DownloadStatus.Completed || item.pausedByFolder)
        ) {
            unpluggedItemsByLocationId
                .getOrPut(location.id) { location to mutableListOf() }
                .second += item
        }
    }
    val unpluggedDrives = unpluggedItemsByLocationId.values.map { (location, groupItems) ->
        val sorted = groupItems.sortedWith(compareBy<DownloadItem>({ it.title.lowercase() }).then(downloadSeriesEpisodeComparator))
        var knownMs = 0L
        var unknown = 0
        sorted.filter { it.status == DownloadStatus.Completed && !it.isPlaceholder }.forEach { entry ->
            val ms = durationMsOf(entry)
            if (ms != null && ms > 0L) knownMs += ms else unknown++
        }
        TripUnpluggedDriveGroup(
            location = location,
            items = sorted,
            completedCount = sorted.count { it.status == DownloadStatus.Completed },
            waitingCount = sorted.count { it.status != DownloadStatus.Completed },
            knownDurationMs = knownMs,
            unknownDurationCount = unknown,
        )
    }.sortedBy { it.location.name.lowercase() }

    val unpluggedItemIds = unpluggedDrives.flatMapTo(mutableSetOf()) { group -> group.items.map { it.id } }

    val pausedItems = items
        .filter { it.status == DownloadStatus.Paused && it.id !in unpluggedItemIds }
        .map { item ->
            val (_, availability) = locationAvailabilityByItem.getValue(item.id)
            val rowStatus = downloadRowStatus(
                item = item,
                nowEpochMs = nowEpochMs,
                offline = offline,
                location = availability,
                moving = item.id in folderState.movingIds,
                addressRequired = addressRequired,
                limitedBy = limitedBy,
            )
            item to rowStatus
        }

    val attentionItems = items
        .filter { it.status == DownloadStatus.Completed && it.id !in unpluggedItemIds }
        .mapNotNull { item ->
            val missingFile = (addressRequired && item.localFileUri.isNullOrBlank()) ||
                diskInfoByDownloadId[item.id]?.fileExists == false
            if (item.isPlaceholder) {
                return@mapNotNull TripAttentionEntry(
                    item = item,
                    isFlaggedPlaceholder = true,
                )
            }
            if (missingFile) {
                return@mapNotNull TripAttentionEntry(
                    item = item,
                    isFileMissing = true,
                )
            }
            val diskInfo = diskInfoByDownloadId[item.id]
            val isSuspect = item.framesCheck == DownloadFramesCheck.Suspect
            val isUnverified = !isSuspect && !item.isVerifiedForTrip()
            val missingSubtitles = diskInfo != null && diskInfo.subtitleCount == 0
            val skipTimesStatus = when (diskInfo?.skipTimesNote) {
                null -> if (diskInfo != null) TripSkipTimesStatus.NotSaved else TripSkipTimesStatus.Present
                SkipTimesNote.Saved -> TripSkipTimesStatus.Present
                SkipTimesNote.NoneFound -> TripSkipTimesStatus.NoneFound
            }
            val sidecarRefused = diskInfo?.sidecarRefused == true
            val entry = TripAttentionEntry(
                item = item,
                isSuspect = isSuspect,
                isUnverified = isUnverified,
                missingSubtitles = missingSubtitles,
                skipTimesStatus = skipTimesStatus,
                sidecarRefused = sidecarRefused,
                canFetchCompanions = diskInfo?.canFetchCompanions == true,
            )
            entry.takeIf { it.hasAnyIssue }
        }
        .sortedWith(
            compareByDescending<TripAttentionEntry> { it.isFlaggedPlaceholder }
                .thenByDescending { it.isFileMissing }
                .thenByDescending { it.isSuspect }
                .thenByDescending { it.isUnverified }
                .thenBy { it.item.title.lowercase() }
                .then(Comparator { a, b -> downloadSeriesEpisodeComparator.compare(a.item, b.item) }),
        )

    return TripReadySummary(
        totalPlayableOfflineMs = totalPlayableOfflineMs,
        totalPlayableCount = verifiedPlayableItems.size,
        totalPlayableEpisodeCount = verifiedPlayableItems.count { it.isEpisode },
        totalPlayableMovieCount = verifiedPlayableItems.count { !it.isEpisode },
        totalPlayableUnknownDurationCount = totalPlayableUnknownDurationCount,
        titles = titleGroups,
        downloading = downloadingEntries,
        totalDownloadingEtaSeconds = estimateTotalDownloadingEtaSeconds(downloadingEntries),
        pausedItems = pausedItems,
        attentionItems = attentionItems,
        unpluggedDrives = unpluggedDrives,
        unpluggedTotalCount = unpluggedItemIds.size,
    )
}
