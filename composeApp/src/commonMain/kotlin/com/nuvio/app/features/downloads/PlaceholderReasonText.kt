package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.runtimeMinutesFor
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_error_placeholder_length_episode
import nuvio.composeapp.generated.resources.downloads_error_placeholder_length_film
import nuvio.composeapp.generated.resources.downloads_error_placeholder_not_cached
import nuvio.composeapp.generated.resources.downloads_error_placeholder_size
import nuvio.composeapp.generated.resources.downloads_error_placeholder_small_episode
import nuvio.composeapp.generated.resources.downloads_error_placeholder_small_film
import org.jetbrains.compose.resources.getString

/**
 * The reason a file is not the video, as the plain sentence the row shows: "Not the real video: 8.5 MB
 * arrived, the source said 9.5 GB". The sizes that arrived are written as the list writes them, the
 * size the source claimed in the units its text used.
 */
internal fun placeholderReasonText(reason: PlaceholderReason, item: DownloadItem): String = runBlocking {
    when (reason) {
        is PlaceholderReason.SizeBelowClaim -> getString(
            Res.string.downloads_error_placeholder_size,
            formatListedSize(reason.fileBytes),
            formatClaimedSize(reason.claimedBytes),
        )
        is PlaceholderReason.TooSmallForRuntime -> getString(
            if (item.isEpisode) {
                Res.string.downloads_error_placeholder_small_episode
            } else {
                Res.string.downloads_error_placeholder_small_film
            },
            formatListedSize(reason.fileBytes),
            formatRunLength(reason.runtimeMinutes),
        )
        is PlaceholderReason.ShortForRuntime -> getString(
            if (item.isEpisode) {
                Res.string.downloads_error_placeholder_length_episode
            } else {
                Res.string.downloads_error_placeholder_length_film
            },
            formatFileLength(reason.durationMs),
            formatRunLength(reason.runtimeMinutes),
        )
        is PlaceholderReason.NotCachedClip -> getString(
            Res.string.downloads_error_placeholder_not_cached,
            formatFileLength(reason.durationMs),
        )
    }
}

/**
 * Where the running time of the title of a download comes from: the details of the title when they
 * are already loaded, the same source the player uses. Nothing is fetched. Tests replace it.
 */
internal var downloadRuntimeLookup: (DownloadItem) -> Int? = { item ->
    runCatching {
        MetaDetailsRepository.peek(item.parentMetaType, item.parentMetaId)
            ?.runtimeMinutesFor(item.videoId, item.seasonNumber, item.episodeNumber)
    }.getOrNull()
}

internal fun runtimeMinutesOfDownload(item: DownloadItem): Int? = downloadRuntimeLookup(item)
