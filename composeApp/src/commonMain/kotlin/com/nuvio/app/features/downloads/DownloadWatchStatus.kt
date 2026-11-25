package com.nuvio.app.features.downloads

import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressUiState

internal data class DownloadWatchStatus(
    val isWatched: Boolean = false,
    val progress: Float = 0f,
    val remainingMs: Long = 0L,
    val watchedAtEpochMs: Long? = null,
)

internal fun DownloadItem.watchStatus(
    progressState: WatchProgressUiState,
    isHistoryWatched: (id: String, type: String, season: Int?, episode: Int?) -> Boolean = { id, type, season, episode ->
        WatchedRepository.isWatched(id = id, type = type, season = season, episode = episode)
    },
    historyWatchedAt: (id: String, type: String, season: Int?, episode: Int?) -> Long? = { id, type, season, episode ->
        WatchedRepository.watchedAtEpochMs(id = id, type = type, season = season, episode = episode)
    },
): DownloadWatchStatus {
    val entry = progressState.progressForVideo(
        videoId = videoId,
        parentMetaId = parentMetaId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
    )
    if (entry != null && !entry.isEffectivelyCompleted && entry.progressFraction > 0f) {
        return DownloadWatchStatus(
            progress = entry.progressFraction,
            remainingMs = if (entry.durationMs > 0L) {
                (entry.durationMs - entry.lastPositionMs).coerceAtLeast(0L)
            } else {
                0L
            },
        )
    }
    val completedProgressAt = entry?.takeIf { it.isEffectivelyCompleted }?.lastUpdatedEpochMs?.takeIf { it > 0L }
    val markedAt = historyWatchedAt(parentMetaId, parentMetaType, seasonNumber, episodeNumber)
    val watched = entry?.isEffectivelyCompleted == true ||
        markedAt != null ||
        isHistoryWatched(parentMetaId, parentMetaType, seasonNumber, episodeNumber)
    val watchedAt = if (!watched) null else listOfNotNull(completedProgressAt, markedAt).maxOrNull()
    return DownloadWatchStatus(
        isWatched = watched,
        watchedAtEpochMs = watchedAt,
    )
}
