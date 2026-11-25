package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem

/** Keeps stored automatic-download state in memory, by profile. */
internal class MemoryAutoDownloadPayloads : AutoDownloadPayloads {
    val saved = mutableMapOf<Int, String>()
    val unreadable = mutableMapOf<Int, String>()
    var failSave = false
    var failSync = false

    override fun load(profileId: Int): String? = saved[profileId]

    override fun save(profileId: Int, payload: String) {
        if (failSave) throw java.io.IOException("Simulated store save failure")
        saved[profileId] = payload
    }

    override fun saveUnreadable(profileId: Int, payload: String) {
        unreadable[profileId] = payload
    }

    override fun sync(): Boolean = !failSync
}

internal fun testRule(
    keepCount: Int = 3,
    scope: AutoDownloadScope = AutoDownloadScope.NextUnwatched,
    showId: String = "tt1",
    owned: Set<String> = emptySet(),
) = AutoDownloadRule(
    parentMetaType = "series",
    parentMetaId = showId,
    title = "Show",
    keepCount = keepCount,
    scope = scope,
    ownedEpisodeKeys = owned,
)

internal fun testEpisode(episode: Int, season: Int = 1, showId: String = "tt1") =
    SeasonBatchEpisode("$showId:$season:$episode", season, episode, "Ep $episode", null, runtimeMinutes = 45)

internal fun testDownload(
    episode: Int,
    status: DownloadStatus,
    season: Int = 1,
    showId: String = "tt1",
    ruleKey: String? = null,
    localFileUri: String? = null,
    id: String = "$showId-$season-$episode",
) = DownloadItem(
    id = id,
    contentType = "series",
    parentMetaId = showId,
    parentMetaType = "series",
    videoId = "$showId:$season:$episode",
    title = "Show",
    seasonNumber = season,
    episodeNumber = episode,
    streamTitle = "s",
    providerName = "A",
    sourceUrl = "https://example.com/$episode.mp4",
    localFileUri = localFileUri,
    fileName = "f$season-$episode.mp4",
    status = status,
    downloadedBytes = if (status == DownloadStatus.Completed) 700_000_000 else 0,
    createdAtEpochMs = 0,
    updatedAtEpochMs = 0,
    autoDownloadRuleKey = ruleKey,
)

internal fun testStream(url: String, label: String = "1080p") = StreamItem(
    name = label,
    url = url,
    addonName = "A",
    addonId = "addon:A",
)
