package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.available_offline
import org.jetbrains.compose.resources.stringResource

/**
 * Index of completed, verified local downloads (excluding flagged placeholders and missing files)
 * keyed by content ID, video ID, and season/episode coordinates.
 */
@Immutable
internal class OfflineDownloadIndex(
    items: List<DownloadItem>,
    resolveLocalFile: (DownloadItem) -> String?,
) {
    private data class EpisodeKey(
        val parentMetaId: String,
        val season: Int,
        val episode: Int,
    )

    private val resolvedUriById: Map<String, String>
    val playableItems: List<DownloadItem>

    init {
        val resolved = LinkedHashMap<String, String>()
        val playable = ArrayList<DownloadItem>()
        items
            .asSequence()
            .filter { it.status == DownloadStatus.Completed && !it.isPlaceholder }
            .sortedByDescending { it.updatedAtEpochMs }
            .forEach { item ->
                val uri = resolveLocalFile(item)
                if (uri != null) {
                    resolved[item.id] = uri
                    playable += item
                }
            }
        resolvedUriById = resolved
        playableItems = playable
    }

    fun resolvedUriFor(item: DownloadItem): String? = resolvedUriById[item.id]

    private val byVideoId: Map<String, DownloadItem> = LinkedHashMap<String, DownloadItem>(playableItems.size).apply {
        playableItems.forEach { item ->
            val videoId = item.videoId.trim()
            if (videoId.isNotBlank()) putIfAbsent(videoId, item)
        }
    }

    private val byEpisode: Map<EpisodeKey, DownloadItem> = LinkedHashMap<EpisodeKey, DownloadItem>(playableItems.size).apply {
        playableItems.forEach { item ->
            val parentId = item.parentMetaId.trim()
            val season = item.seasonNumber
            val episode = item.episodeNumber
            if (parentId.isNotBlank() && season != null && episode != null) {
                putIfAbsent(EpisodeKey(parentId, season, episode), item)
            }
        }
    }

    private val byMovieMetaId: Map<String, DownloadItem> = LinkedHashMap<String, DownloadItem>(playableItems.size).apply {
        playableItems.forEach { item ->
            val parentId = item.parentMetaId.trim()
            if (parentId.isNotBlank() && item.seasonNumber == null && item.episodeNumber == null) {
                putIfAbsent(parentId, item)
            }
        }
    }

    val metaIds: Set<String> = playableItems.mapNotNullTo(LinkedHashSet()) { item ->
        item.parentMetaId.trim().takeIf { it.isNotBlank() }
    }

    fun hasMeta(parentMetaId: String): Boolean {
        val normalized = parentMetaId.trim()
        return normalized.isNotBlank() && (normalized in metaIds || normalized in byVideoId)
    }

    fun itemsForMeta(parentMetaId: String): List<DownloadItem> {
        val normalized = parentMetaId.trim()
        if (normalized.isBlank()) return emptyList()
        return playableItems.filter { it.parentMetaId.trim() == normalized }
    }

    fun findFor(
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String? = null,
    ): DownloadItem? {
        val normalizedParent = parentMetaId.trim()
        val normalizedVideo = videoId?.trim().orEmpty()
        if (normalizedVideo.isNotBlank()) {
            // Avoid matching a movie/unrelated record by bare show ID when episode coordinates are specified.
            val videoMatchesParent = normalizedVideo == normalizedParent && seasonNumber != null && episodeNumber != null
            if (!videoMatchesParent) {
                byVideoId[normalizedVideo]?.let { matched ->
                    val sameEpisodeOrUnnumbered =
                        seasonNumber == null ||
                            episodeNumber == null ||
                            (matched.seasonNumber == seasonNumber && matched.episodeNumber == episodeNumber)
                    if (sameEpisodeOrUnnumbered) return matched
                }
            }
        }
        if (normalizedParent.isBlank()) return null
        return if (seasonNumber != null && episodeNumber != null) {
            byEpisode[EpisodeKey(normalizedParent, seasonNumber, episodeNumber)]
        } else {
            byMovieMetaId[normalizedParent]
        }
    }

    fun hasFor(
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String? = null,
    ): Boolean = findFor(
        parentMetaId = parentMetaId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        videoId = videoId,
    ) != null

    override fun equals(other: Any?): Boolean =
        this === other || (other is OfflineDownloadIndex && playableItems == other.playableItems && resolvedUriById == other.resolvedUriById)

    override fun hashCode(): Int = 31 * playableItems.hashCode() + resolvedUriById.hashCode()

    companion object {
        val Empty: OfflineDownloadIndex = OfflineDownloadIndex(emptyList()) { null }
    }
}

@Composable
internal fun rememberOfflineDownloadIndex(): OfflineDownloadIndex {
    val index by remember {
        DownloadsRepository.ensureOfflineIndexStarted()
        DownloadsRepository.offlineIndex
    }.collectAsStateWithLifecycle()
    return index
}

@Composable
internal fun AvailableOfflineBadge(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val label = stringResource(Res.string.available_offline)
    val shape = RoundedCornerShape(999.dp)
    val horizontalPad = if (compact) 7.dp else 9.dp
    val verticalPad = if (compact) 3.dp else 4.dp
    Row(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                contentDescription = label
            }
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.78f))
            .border(width = 1.dp, color = Color(0xFF4ADE80).copy(alpha = 0.55f), shape = shape)
            .padding(horizontal = horizontalPad, vertical = verticalPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.DownloadDone,
            contentDescription = null,
            tint = Color(0xFF4ADE80),
            modifier = Modifier.size(if (compact) 11.dp else 13.dp),
        )
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = if (compact) 10.sp else 11.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun BoxScope.PosterAvailableOfflineOverlay(
    isAvailableOffline: Boolean,
    modifier: Modifier = Modifier,
    padding: Dp = 6.dp,
) {
    if (!isAvailableOffline) return
    AvailableOfflineBadge(
        compact = true,
        modifier = modifier
            .align(Alignment.TopStart)
            .padding(padding),
    )
}
