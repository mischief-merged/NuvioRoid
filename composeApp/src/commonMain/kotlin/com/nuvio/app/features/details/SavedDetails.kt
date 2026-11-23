package com.nuvio.app.features.details

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.EpisodeListSidecar
import com.nuvio.app.features.downloads.SavedEpisodes
import com.nuvio.app.features.downloads.SidecarIo
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.details_showing_saved
import org.jetbrains.compose.resources.stringResource

/**
 * The details of a title built only from what downloads saved: the download records and the
 * episode lists saved beside the files. Used by the show page and the episode page when the real
 * details cannot be loaded. Nothing is invented: a field nobody saved stays empty, the page's own
 * trailers, cast and ratings are absent, and an episode that is listed but not downloaded is marked
 * unavailable so that its page does not offer to play it.
 */
internal object SavedDetails {
    /** Null when the title has no finished download, or the files cannot be read in time. Reads on the IO dispatcher. */
    suspend fun metaFor(type: String, id: String): MetaDetails? = SidecarIo.read { readMeta(type, id) }

    private fun readMeta(type: String, id: String): MetaDetails? {
        // The list of downloads may not have been read yet when a title is opened offline.
        DownloadsRepository.ensureLoaded()
        val resolved = DownloadsRepository.uiState.value.items.mapNotNull { item ->
            if (item.parentMetaId.trim() != id.trim()) return@mapNotNull null
            val uri = DownloadsRepository.resolveLocalUriWithoutMutation(item) ?: return@mapNotNull null
            item to uri
        }
        if (resolved.isEmpty()) return null
        val items = resolved.map { it.first }
        val lists = resolved.mapNotNull { (_, uri) ->
            runCatching { EpisodeListSidecar.read(uri) }.getOrNull()
        }
        return buildSavedMeta(type, id, items, lists)
    }
}

/** Pure part of [SavedDetails.metaFor]. */
internal fun buildSavedMeta(type: String, id: String, items: List<DownloadItem>, lists: List<SavedEpisodes>): MetaDetails? {
    val first = items.firstOrNull() ?: return null
    val downloaded = items.filter { it.isEpisode }.associateBy { it.seasonNumber!! to it.episodeNumber!! }
    val listed = LinkedHashMap<Pair<Int, Int>, MetaVideo>()
    for (list in lists) for (episode in list.episodes) {
        val key = episode.season to episode.episode
        listed[key] = MetaVideo(
            id = episode.id, title = episode.title, released = episode.released, thumbnail = episode.thumbnail,
            season = episode.season, episode = episode.episode, overview = episode.overview, runtime = episode.runtime,
            available = key in downloaded,
        )
    }
    for ((key, item) in downloaded) {
        val known = listed[key]
        listed[key] = (known ?: MetaVideo(
            id = item.videoId,
            title = item.episodeTitle?.takeIf { it.isNotBlank() } ?: item.displaySubtitle,
            thumbnail = item.episodeThumbnail,
            season = key.first,
            episode = key.second,
        )).copy(available = true, thumbnail = known?.thumbnail ?: item.episodeThumbnail)
    }
    val videos = listed.values.sortedWith(compareBy({ it.season }, { it.episode }))
    val list = lists.firstOrNull()
    return MetaDetails(
        id = id,
        type = type,
        name = list?.showName?.takeIf { it.isNotBlank() } ?: first.title,
        imdbId = list?.imdbId,
        poster = list?.poster ?: first.poster,
        background = first.background,
        logo = first.logo,
        videos = videos,
    )
}

/**
 * Whether the page stands in saved details for the real ones: the details are not loaded and either
 * their load failed or the network is known to be down, which includes "online, but the servers
 * cannot be reached", the state that otherwise ends in a dead end.
 */
internal fun savedDetailsWanted(detailsLoaded: Boolean, loadError: String?, condition: NetworkCondition): Boolean =
    !detailsLoaded && (loadError != null ||
        condition == NetworkCondition.NoInternet || condition == NetworkCondition.ServersUnreachable)

/** What looking for saved details came to: [settled] is false while the look is still being made. */
internal class SavedLookup(val meta: MetaDetails?, val settled: Boolean)

/**
 * The saved details of a title, while [wanted] (the real details are missing). The meta is null until
 * read, and when there are none; [SavedLookup.settled] says which, so that a page does not show its
 * "failed to load" dead end for the moment the look takes.
 */
@Composable
internal fun rememberSavedLookup(type: String, id: String, wanted: Boolean): State<SavedLookup> {
    val downloadsState by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()
    val relevantItems = remember(downloadsState.items, id) {
        val normalizedId = id.trim()
        downloadsState.items.filter { item ->
            item.parentMetaId.trim() == normalizedId &&
                item.status == DownloadStatus.Completed &&
                !item.isPlaceholder
        }
    }
    val state = remember(type, id) {
        mutableStateOf(SavedLookup(null, settled = !wanted))
    }
    LaunchedEffect(type, id, wanted, relevantItems) {
        if (!wanted) {
            state.value = SavedLookup(null, settled = true)
        } else {
            val previousMeta = state.value.meta
            state.value = SavedLookup(meta = previousMeta, settled = previousMeta != null)
            state.value = SavedLookup(SavedDetails.metaFor(type, id), settled = true)
        }
    }
    return state
}

/** The plain line shown over a page built from saved details. */
@Composable
internal fun SavedDetailsNotice(modifier: Modifier = Modifier) {
    val text = stringResource(Res.string.details_showing_saved)
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = text,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A trailer is streamed, so the trailers section is offered only with trailers to show and a connection. */
internal fun trailersOffered(trailerCount: Int, offline: Boolean): Boolean = trailerCount > 0 && !offline
