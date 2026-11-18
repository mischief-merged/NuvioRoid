package com.nuvio.app.features.home

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.EpisodeListSidecar
import com.nuvio.app.features.downloads.SavedEpisodes

internal const val HOME_DOWNLOADED_SECTION_KEY = "downloaded_offline"

internal fun buildDownloadedHomeSection(
    title: String,
    items: List<DownloadItem>,
    resolveLocalFile: (DownloadItem) -> String? = DownloadsRepository::resolveLocalUriWithoutMutation,
    readSidecar: (String) -> SavedEpisodes? = { uri -> runCatching { EpisodeListSidecar.read(uri) }.getOrNull() },
    episodeCountText: ((Int) -> String)? = null,
): HomeCatalogSection? {
    val verifiedItems = items
        .filter { it.status == DownloadStatus.Completed && !it.isPlaceholder && resolveLocalFile(it) != null }
    if (verifiedItems.isEmpty()) return null

    val grouped = LinkedHashMap<String, MutableList<DownloadItem>>()
    for (item in verifiedItems.sortedByDescending { it.updatedAtEpochMs }) {
        val groupId = item.parentMetaId.trim().ifBlank { item.videoId.trim() }
        if (groupId.isBlank()) continue
        grouped.getOrPut(groupId) { mutableListOf() }.add(item)
    }
    if (grouped.isEmpty()) return null

    val previews = grouped.entries
        .sortedByDescending { (_, groupItems) -> groupItems.maxOf { it.updatedAtEpochMs } }
        .map { (groupId, groupItems) ->
            val latest = groupItems.first()
            val isSeries = groupItems.any { it.isEpisode } || latest.parentMetaType.equals("series", ignoreCase = true)
            val sidecar = groupItems.firstNotNullOfOrNull { item ->
                resolveLocalFile(item)?.let(readSidecar)
            }
            val poster = groupItems.firstNotNullOfOrNull { it.poster?.takeIf(String::isNotBlank) }
                ?: sidecar?.poster?.takeIf(String::isNotBlank)
            val banner = groupItems.firstNotNullOfOrNull { it.background?.takeIf(String::isNotBlank) }
            val logo = groupItems.firstNotNullOfOrNull { it.logo?.takeIf(String::isNotBlank) }
            val name = sidecar?.showName?.takeIf { it.isNotBlank() && isSeries } ?: latest.title
            val detail = if (isSeries && episodeCountText != null) {
                episodeCountText(groupItems.size)
            } else {
                null
            }
            MetaPreview(
                id = groupId,
                type = latest.parentMetaType.ifBlank { latest.contentType },
                name = name,
                poster = poster,
                banner = banner,
                logo = logo,
                releaseInfo = detail,
            )
        }

    if (previews.isEmpty()) return null
    return HomeCatalogSection(
        key = HOME_DOWNLOADED_SECTION_KEY,
        title = title,
        subtitle = "",
        addonName = title,
        target = CatalogTarget.Addon(
            manifestUrl = "",
            contentType = "downloaded",
            catalogId = HOME_DOWNLOADED_SECTION_KEY,
            supportsPagination = false,
        ),
        items = previews,
    )
}
