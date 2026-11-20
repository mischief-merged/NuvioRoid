package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable

/** Which episodes an automatic-download rule keeps downloaded. */
@Serializable
internal enum class AutoDownloadScope {
    /** The episodes that follow the last one watched. */
    NextUnwatched,

    /** The last released episodes in season and episode order, without the ones already watched. */
    LatestReleased,
}

/**
 * The automatic-download rule of one show. A show without a rule is never downloaded automatically.
 * The quality, bitrate and language are not part of the rule: they are the saved season-download
 * choices at the time episodes are queued.
 */
@Serializable
internal data class AutoDownloadRule(
    val parentMetaType: String,
    val parentMetaId: String,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val keepCount: Int = DefaultKeepCount,
    val scope: AutoDownloadScope = AutoDownloadScope.NextUnwatched,
    /** Removes an episode this rule downloaded once it was watched to the end on this device. Off unless chosen. */
    val deleteAfterWatching: Boolean = false,
    /**
     * Episodes ("season|episode") that were seen in Downloads as a finished download this rule
     * queued, or that the user deleted from Downloads. One that is no longer there was removed, and
     * the rule does not fetch it again.
     */
    val ownedEpisodeKeys: Set<String> = emptySet(),
) {
    val key: String get() = autoDownloadRuleKey(parentMetaType, parentMetaId)

    fun normalized(): AutoDownloadRule =
        copy(keepCount = keepCount.takeIf { it in keepCountOptions } ?: DefaultKeepCount)

    companion object {
        const val DefaultKeepCount = 3
        val keepCountOptions: List<Int> = listOf(1, 2, 3, 5, 7, 10)
    }
}

internal fun autoDownloadRuleKey(parentMetaType: String, parentMetaId: String): String =
    "${parentMetaType.trim().lowercase()}|${parentMetaId.trim()}"

internal fun autoDownloadEpisodeKey(seasonNumber: Int, episodeNumber: Int): String = "$seasonNumber|$episodeNumber"

/** What the last check of a rule found. Shown as text by the interface, never stored. */
internal enum class AutoDownloadStatus {
    NotChecked,
    Checking,
    UpToDate,

    /** Episodes are wanted and will be queued by the next check. */
    Pending,
    Downloading,
    Paused,

    /** The show's episode list could not be fetched. */
    CouldNotCheck,

    /** No source matched the saved choices. Tried again later. */
    NoSource,

    /** The only sources found were at this computer or a private network, which automatic downloads refuse. Tried again later. */
    PrivateSourcesSkipped,

    /** A download failed. Tried again later. */
    Failed,

    /** Adding episodes was stopped. Tried again later. */
    Stopped,

    /** The storage limit for automatic downloads is reached. */
    StorageLimitReached,

    /** The downloads volume has too little free space left for automatic downloads. */
    LowDiskSpace,

    /** The location that holds this show's downloads is not connected. Nothing is queued elsewhere. */
    FolderNotConnected,
}

internal data class AutoDownloadPlan(
    /** The episodes the rule wants downloaded right now, oldest first. */
    val window: List<SeasonBatchEpisode>,
    /** The part of [window] that has no download yet, or only a failed one. */
    val toDownload: List<SeasonBatchEpisode>,
)

/**
 * Decides which episodes [rule] wants. It reads nothing but its arguments.
 *
 * - Specials (season 0) are never downloaded automatically.
 * - Next unwatched: the episodes after the last watched one, [AutoDownloadRule.keepCount] of them.
 * - Latest released: the last [AutoDownloadRule.keepCount] released episodes in season and episode
 *   order, without the watched ones, so a show that was watched to the end gets nothing.
 * - An episode the rule finished downloading before that is no longer in [downloads] was removed
 *   and is left out.
 * - An episode that is downloaded, downloading or paused, by the rule or by hand, counts as kept and
 *   is not queued again. One whose download failed, or whose finished file is too small to be the
 *   episode or is marked as not the real video, is queued again.
 */
internal fun planAutoDownload(
    rule: AutoDownloadRule,
    released: List<SeasonBatchEpisode>,
    isWatched: (SeasonBatchEpisode) -> Boolean,
    downloads: List<DownloadItem>,
): AutoDownloadPlan {
    val ordered = released
        .filter { it.seasonNumber > 0 }
        .distinctBy { it.autoDownloadKey }
        .sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
    val statusByEpisode = downloads.episodeStatuses(rule.parentMetaId)
    val placeholders = downloads.placeholderEpisodeKeys(rule.parentMetaId, ordered)
    val removed = rule.ownedEpisodeKeys - statusByEpisode.keys

    val window = when (rule.scope) {
        AutoDownloadScope.NextUnwatched ->
            ordered
                .drop(ordered.indexOfLast(isWatched) + 1)
                .filter { it.autoDownloadKey !in removed }
                .take(rule.keepCount)
        AutoDownloadScope.LatestReleased ->
            ordered
                .takeLast(rule.keepCount)
                .filter { it.autoDownloadKey !in removed && !isWatched(it) }
    }
    val toDownload = window.filter { episode ->
        when (statusByEpisode[episode.autoDownloadKey]) {
            // A finished download that is only a placeholder clip is not the episode.
            DownloadStatus.Completed -> episode.autoDownloadKey in placeholders
            DownloadStatus.Downloading, DownloadStatus.Paused -> false
            DownloadStatus.Failed, null -> true
        }
    }
    return AutoDownloadPlan(window = window, toDownload = toDownload)
}

internal val SeasonBatchEpisode.autoDownloadKey: String
    get() = autoDownloadEpisodeKey(seasonNumber, episodeNumber)

/**
 * The episodes of a show whose only finished download is not the video: marked as not the real
 * video, or too small to be one. An episode that also has a download that is not such a file, a good
 * copy or one that is on its way, is not in the set: the marked file is kept beside it, as a problem
 * for the user to deal with, and does not make the rule fetch again.
 */
internal fun List<DownloadItem>.placeholderEpisodeKeys(parentMetaId: String, episodes: List<SeasonBatchEpisode>): Set<String> {
    val parent = parentMetaId.trim()
    val runtimes = episodes.associate { it.autoDownloadKey to it.runtimeMinutes }
    fun isNotTheVideo(item: DownloadItem, key: String) =
        item.isPlaceholder || item.finishedSizeBytes < minPlausibleVideoBytes(runtimes[key])
    val ofShow = mapNotNull { item ->
        val season = item.seasonNumber ?: return@mapNotNull null
        val episode = item.episodeNumber ?: return@mapNotNull null
        if (item.parentMetaId.trim() != parent) return@mapNotNull null
        autoDownloadEpisodeKey(season, episode) to item
    }
    return ofShow.groupBy({ it.first }, { it.second }).mapNotNullTo(mutableSetOf()) { (key, items) ->
        val finished = items.filter { it.status == DownloadStatus.Completed }
        val others = items.filter { it.status != DownloadStatus.Completed && it.status != DownloadStatus.Failed }
        key.takeIf {
            finished.isNotEmpty() && finished.all { isNotTheVideo(it, key) } && others.isEmpty()
        }
    }
}

/**
 * The status of each episode of a show. An episode with several downloads has the status of the one
 * that stands for it: one that is downloading or paused, else a finished one that is not marked,
 * else a marked or failed one.
 */
internal fun List<DownloadItem>.episodeStatuses(parentMetaId: String): Map<String, DownloadStatus> {
    val parent = parentMetaId.trim()
    fun rank(item: DownloadItem) = when {
        item.status == DownloadStatus.Downloading || item.status == DownloadStatus.Paused -> 0
        item.status == DownloadStatus.Completed && !item.isPlaceholder -> 1
        else -> 2
    }
    return mapNotNull { item ->
        val season = item.seasonNumber ?: return@mapNotNull null
        val episode = item.episodeNumber ?: return@mapNotNull null
        if (item.parentMetaId.trim() != parent) return@mapNotNull null
        autoDownloadEpisodeKey(season, episode) to item
    }.groupBy({ it.first }, { it.second }).mapValues { (_, items) -> items.minBy(::rank).status }
}

/** How long a rule waits before it tries an episode again: 1 hour, 3 hours, 12 hours, then daily. */
internal fun autoDownloadRetryDelayMs(failedAttempts: Int): Long {
    val hour = 3_600_000L
    return when {
        failedAttempts <= 1 -> hour
        failedAttempts == 2 -> 3 * hour
        failedAttempts == 3 -> 12 * hour
        else -> 24 * hour
    }
}
