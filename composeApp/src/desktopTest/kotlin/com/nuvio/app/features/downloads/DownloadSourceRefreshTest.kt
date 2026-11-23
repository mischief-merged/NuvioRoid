package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamClientResolve
import com.nuvio.app.features.streams.StreamClientResolveRaw
import com.nuvio.app.features.streams.StreamClientResolveStream
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamDebridCacheStatus
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamProxyHeaders
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadSourceRefreshTest {
    private val sampleHash = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"

    private fun baseItem(
        addonId: String? = "torrentio:https://addon.example/manifest.json",
        addonName: String = "Torrentio",
        infoHash: String? = sampleHash,
        fileIdx: Int? = 2,
        filename: String? = "Show.S01E02.1080p.mkv",
        sizeBytes: Long? = 1_500_000_000L,
        bingeGroup: String? = "group-1",
        debridResolved: Boolean = false,
        clientResolve: DownloadClientResolve? = null,
        autoRuleKey: String? = null,
    ) = DownloadItem(
        id = "dl-1",
        contentType = "series",
        parentMetaId = "tt1234567",
        parentMetaType = "series",
        videoId = "tt1234567:1:2",
        title = "Example Show",
        seasonNumber = 1,
        episodeNumber = 2,
        streamTitle = "1080p WEB-DL",
        streamSubtitle = "Show.S01E02.1080p.mkv\n👤 42  1.40 GB",
        providerName = addonName,
        providerAddonId = addonId,
        sourceUrl = "https://cdn.example.com/old-token/video.mkv",
        sourceHeaders = mapOf("Authorization" to "Bearer old"),
        fileName = "Example Show - S01E02.mkv",
        status = DownloadStatus.Downloading,
        downloadedBytes = 100L,
        totalBytes = sizeBytes,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 2L,
        autoDownloadRuleKey = autoRuleKey,
        sourceInfoHash = infoHash,
        sourceFileIdx = fileIdx,
        sourceFilename = filename,
        sourceFileSizeBytes = sizeBytes,
        sourceBingeGroup = bingeGroup,
        sourceDebridResolved = debridResolved,
        sourceClientResolve = clientResolve,
    )

    private fun candidateStream(
        addonId: String = "torrentio:https://addon.example/manifest.json",
        addonName: String = "Torrentio",
        url: String? = "https://cdn.example.com/fresh-token/video.mkv",
        infoHash: String? = sampleHash,
        fileIdx: Int? = 2,
        filename: String? = "Show.S01E02.1080p.mkv",
        sizeBytes: Long? = 1_500_000_000L,
        bingeGroup: String? = "group-1",
        name: String? = "1080p WEB-DL",
        description: String? = "Show.S01E02.1080p.mkv\n👤 7  1.40 GB",
        headers: Map<String, String>? = null,
        clientResolve: StreamClientResolve? = null,
    ) = StreamItem(
        name = name,
        description = description,
        url = url,
        infoHash = infoHash,
        fileIdx = fileIdx,
        addonName = addonName,
        addonId = addonId,
        clientResolve = clientResolve,
        behaviorHints = StreamBehaviorHints(
            bingeGroup = bingeGroup,
            filename = filename,
            videoSize = sizeBytes,
            proxyHeaders = headers?.let { StreamProxyHeaders(request = it) },
        ),
    )

    @Test
    fun matches_same_source_across_rotated_url_and_changed_peer_count() {
        val item = baseItem()
        val wrongHash = candidateStream(
            infoHash = "ffffffffffffffffffffffffffffffffffffffff",
            filename = "Other.S01E02.1080p.mkv",
            sizeBytes = 900_000_000L,
            url = "https://cdn.example.com/other/video.mkv",
        )
        val expected = candidateStream()

        val matched = findMatchingSourceForRefresh(
            item = item,
            streams = listOf(wrongHash, expected),
            shouldResolveDebrid = { false },
        )

        assertEquals(expected, matched)
    }

    @Test
    fun rejects_different_addon_even_with_matching_hash_and_filename() {
        val item = baseItem()
        val otherAddon = candidateStream(
            addonId = "mediafusion:https://other.example/manifest.json",
            addonName = "MediaFusion",
        )

        val matched = findMatchingSourceForRefresh(
            item = item,
            streams = listOf(otherAddon),
            shouldResolveDebrid = { false },
        )

        assertNull(matched)
    }

    @Test
    fun selects_exact_file_index_in_multi_file_pack() {
        val item = baseItem(fileIdx = 2, filename = null, sizeBytes = null, bingeGroup = null)
        val ep1 = candidateStream(fileIdx = 1, filename = null, sizeBytes = null, bingeGroup = null, url = "https://cdn.example.com/e1.mkv")
        val ep2 = candidateStream(fileIdx = 2, filename = null, sizeBytes = null, bingeGroup = null, url = "https://cdn.example.com/e2.mkv")

        val matched = findMatchingSourceForRefresh(
            item = item,
            streams = listOf(ep1, ep2),
            shouldResolveDebrid = { false },
        )

        assertEquals(ep2, matched)
    }

    @Test
    fun rejects_conflicting_filename_size_or_binge_group() {
        val item = baseItem()
        val wrongFile = candidateStream(filename = "Different.Release.1080p.mkv")
        val wrongSize = candidateStream(sizeBytes = 1_600_000_000L)
        val wrongBinge = candidateStream(bingeGroup = "group-99")

        assertNull(findMatchingSourceForRefresh(item, listOf(wrongFile), shouldResolveDebrid = { false }))
        assertNull(findMatchingSourceForRefresh(item, listOf(wrongSize), shouldResolveDebrid = { false }))
        assertNull(findMatchingSourceForRefresh(item, listOf(wrongBinge), shouldResolveDebrid = { false }))
    }

    @Test
    fun direct_debrid_resolver_uses_force_refresh_and_updates_headers_without_hitting_addon() = runBlocking {
        var addonLookupCount = 0
        var seenForceRefresh: Boolean? = null
        val clientResolve = DownloadClientResolve(
            type = "debrid",
            infoHash = sampleHash,
            fileIdx = 2,
            filename = "Show.S01E02.1080p.mkv",
            service = "torbox",
            isCached = true,
            rawFilename = "Show.S01E02.1080p.mkv",
            rawSize = 1_500_000_000L,
            debridProviderId = "torbox",
            debridProviderName = "TorBox",
        )
        val item = baseItem(debridResolved = true, clientResolve = clientResolve)
        val refresher = DownloadSourceRefresher(
            shouldResolveDebrid = { stream ->
                stream.clientResolve?.type == "debrid" && stream.infoHash == sampleHash
            },
            resolveDebrid = { stream, season, episode, forceRefresh ->
                seenForceRefresh = forceRefresh
                assertEquals(1, season)
                assertEquals(2, episode)
                assertNull(stream.playableDirectUrl)
                DirectDebridPlayableResult.Success(
                    candidateStream(
                        url = "https://tb.example.com/fresh-dl-99/video.mkv",
                        headers = mapOf("Authorization" to "Bearer fresh", "Range" to "bytes=0-10"),
                        clientResolve = StreamClientResolve(
                            type = "debrid",
                            infoHash = sampleHash,
                            fileIdx = 2,
                            filename = "Show.S01E02.1080p.mkv",
                            service = "torbox",
                            stream = StreamClientResolveStream(raw = StreamClientResolveRaw(filename = "Show.S01E02.1080p.mkv", size = 1_500_000_000L)),
                        ),
                    ).copy(debridResolved = true),
                )
            },
            fetchSameAddonStreams = {
                addonLookupCount++
                AddonSourceLookupResult.Unavailable
            },
            isPublicHost = { true },
        )

        val result = refresher.refresh(item)
        val refreshed = assertIs<SourceLinkRefreshResult.Refreshed>(result)
        assertEquals(true, seenForceRefresh)
        assertEquals(0, addonLookupCount)
        assertEquals("https://tb.example.com/fresh-dl-99/video.mkv", refreshed.link.url)
        assertEquals(mapOf("Authorization" to "Bearer fresh"), refreshed.link.requestHeaders)
        assertEquals("Show.S01E02.1080p.mkv", refreshed.link.filename)
        assertEquals(1_500_000_000L, refreshed.link.videoSize)
    }

    @Test
    fun falls_back_to_same_addon_when_direct_resolve_is_stale_and_resolves_matched_stream() = runBlocking {
        val clientResolve = DownloadClientResolve(
            type = "debrid",
            infoHash = sampleHash,
            fileIdx = 2,
            filename = "Show.S01E02.1080p.mkv",
            service = "torbox",
            debridProviderId = "torbox",
            debridProviderName = "TorBox",
        )
        val item = baseItem(debridResolved = true, clientResolve = clientResolve)
        var calls = 0
        val refresher = DownloadSourceRefresher(
            shouldResolveDebrid = { !it.debridResolved && it.infoHash == sampleHash },
            resolveDebrid = { _, _, _, forceRefresh ->
                assertTrue(forceRefresh)
                calls++
                if (calls == 1) {
                    DirectDebridPlayableResult.Stale
                } else {
                    DirectDebridPlayableResult.Success(
                        candidateStream(url = "https://tb.example.com/re-fetched/video.mkv").copy(debridResolved = true),
                    )
                }
            },
            fetchSameAddonStreams = {
                AddonSourceLookupResult.Streams(
                    listOf(
                        candidateStream(
                            url = "https://addon.example/resolve/dummy",
                            clientResolve = StreamClientResolve(
                                type = "debrid",
                                infoHash = sampleHash,
                                fileIdx = 2,
                                service = "torbox",
                            ),
                        ).copy(debridResolved = false),
                    ),
                )
            },
            isPublicHost = { true },
        )

        val result = refresher.refresh(item)
        val refreshed = assertIs<SourceLinkRefreshResult.Refreshed>(result)
        assertEquals(2, calls)
        assertEquals("https://tb.example.com/re-fetched/video.mkv", refreshed.link.url)
    }

    @Test
    fun distinguishes_unavailable_from_temporary_failure_and_blocks_private_auto_download_urls(): Unit = runBlocking {
        val unavailable = DownloadSourceRefresher(
            shouldResolveDebrid = { false },
            fetchSameAddonStreams = { AddonSourceLookupResult.Streams(emptyList()) },
            isPublicHost = { true },
        ).refresh(baseItem())
        assertIs<SourceLinkRefreshResult.Unavailable>(unavailable)

        val transient = DownloadSourceRefresher(
            shouldResolveDebrid = { false },
            fetchSameAddonStreams = { AddonSourceLookupResult.Failed("timeout") },
            isPublicHost = { true },
        ).refresh(baseItem())
        assertEquals("timeout", assertIs<SourceLinkRefreshResult.Failed>(transient).message)

        val autoItem = baseItem(autoRuleKey = "series:tt1234567")
        val privateHostRefresher = DownloadSourceRefresher(
            shouldResolveDebrid = { false },
            fetchSameAddonStreams = {
                AddonSourceLookupResult.Streams(
                    listOf(candidateStream(url = "http://192.168.1.50/internal/video.mkv")),
                )
            },
            isPublicHost = String::hasPublicDownloadHost,
        )
        assertIs<SourceLinkRefreshResult.Unavailable>(privateHostRefresher.refresh(autoItem))
    }

    @Test
    fun codec_round_trip_and_source_replacement_share_hash_and_file_keys_after_url_refresh() {
        val item = baseItem(
            debridResolved = true,
            clientResolve = DownloadClientResolve(
                type = "debrid",
                infoHash = sampleHash,
                fileIdx = 2,
                filename = "Show.S01E02.1080p.mkv",
                service = "torbox",
                rawFilename = "Show.S01E02.1080p.mkv",
                rawSize = 1_500_000_000L,
                debridProviderId = "torbox",
                debridProviderName = "TorBox",
            ),
        ).copy(
            sourceETag = "\"abc-123\"",
            sourceLastModified = "Wed, 01 Jan 2025 00:00:00 GMT",
            partialFileName = "Example Show - S01E02 (2).mkv.part",
            supersededPartials = listOf(KeptPartialFile("Example Show - S01E02.mkv.part", "(dev=1,ino=99)")),
        )

        val encoded = DownloadsCodec.encode(listOf(item), loaded = null)
        val decoded = DownloadsCodec.decode(encoded)?.items?.single()
        assertNotNull(decoded)
        assertEquals(item, decoded)

        val refreshedStreamWithChangedPeers = candidateStream(
            url = "https://cdn.example.com/rotated-again/video.mkv",
            description = "Show.S01E02.1080p.mkv\n👤 99  1.40 GB",
            clientResolve = StreamClientResolve(type = "debrid", infoHash = sampleHash, fileIdx = 2, service = "torbox"),
        )
        val tried = item.sourceIdentityKeys.toSet()
        assertTrue(refreshedStreamWithChangedPeers.wasTried(tried))
        assertEquals("torbox", refreshedStreamWithChangedPeers.toDownloadClientResolve()?.service)
        assertTrue(
            StreamItem(
                name = "1080p WEB-DL",
                description = "Show.S01E02.1080p.mkv\n👤 1  1.40 GB",
                url = "https://cdn.example.com/different-url",
                infoHash = sampleHash,
                fileIdx = 2,
                addonName = "Torrentio",
                addonId = "torrentio:https://addon.example/manifest.json",
                debridCacheStatus = StreamDebridCacheStatus("torbox", "TorBox", StreamDebridCacheState.CACHED),
            ).wasTried(tried),
        )
    }

    @Test
    fun rejects_conflicting_client_resolve_file_index_when_top_level_index_is_absent() {
        val item = baseItem(
            fileIdx = 2,
            filename = null,
            sizeBytes = null,
            bingeGroup = null,
        ).copy(
            streamTitle = "Pack",
            streamSubtitle = null,
        )
        val conflicting = candidateStream(
            fileIdx = null,
            filename = null,
            sizeBytes = null,
            bingeGroup = null,
            name = "Pack",
            description = null,
            url = "https://cdn.example.com/wrong-ep.mkv",
            clientResolve = StreamClientResolve(
                type = "debrid",
                infoHash = sampleHash,
                fileIdx = 3,
                service = "torbox",
            ),
        )
        val matching = candidateStream(
            fileIdx = null,
            filename = null,
            sizeBytes = null,
            bingeGroup = null,
            name = "Pack",
            description = null,
            url = "https://cdn.example.com/right-ep.mkv",
            clientResolve = StreamClientResolve(
                type = "debrid",
                infoHash = sampleHash,
                fileIdx = 2,
                service = "torbox",
            ),
        )

        assertNull(findMatchingSourceForRefresh(item, listOf(conflicting), shouldResolveDebrid = { true }))
        assertEquals(matching, findMatchingSourceForRefresh(item, listOf(conflicting, matching), shouldResolveDebrid = { true }))
    }

    @Test
    fun source_replacement_does_not_treat_distinct_releases_with_unknown_size_or_different_dirs_as_tried() {
        val releaseA = StreamItem(
            name = "Release A",
            description = "Group A",
            url = "https://cdn.example.com/release-a.mkv",
            infoHash = "1111111111111111111111111111111111111111",
            addonName = "Torrentio",
            addonId = "torrentio:https://addon.example/manifest.json",
            behaviorHints = StreamBehaviorHints(filename = "GroupA/video.mkv", videoSize = null),
        )
        val releaseBSameBasenameUnknownSize = StreamItem(
            name = "Release B",
            description = "Group B",
            url = "https://cdn.example.com/release-b.mkv",
            infoHash = "2222222222222222222222222222222222222222",
            addonName = "Torrentio",
            addonId = "torrentio:https://addon.example/manifest.json",
            behaviorHints = StreamBehaviorHints(filename = "video.mkv", videoSize = null),
        )
        val releaseCDifferentDirSameSize = StreamItem(
            name = "Release C",
            description = "Group C",
            url = "https://cdn.example.com/release-c.mkv",
            infoHash = "3333333333333333333333333333333333333333",
            addonName = "Torrentio",
            addonId = "torrentio:https://addon.example/manifest.json",
            behaviorHints = StreamBehaviorHints(filename = "GroupC/video.mkv", videoSize = 1_500_000_000L),
        )
        val releaseDDifferentDirSameSize = releaseCDifferentDirSameSize.copy(
            name = "Release D",
            description = "Group D",
            url = "https://cdn.example.com/release-d.mkv",
            infoHash = "4444444444444444444444444444444444444444",
            behaviorHints = StreamBehaviorHints(filename = "GroupD/video.mkv", videoSize = 1_500_000_000L),
        )

        assertFalse(releaseBSameBasenameUnknownSize.wasTried(releaseA.sourceIdentityKeys.toSet()))
        assertFalse(releaseDDifferentDirSameSize.wasTried(releaseCDifferentDirSameSize.sourceIdentityKeys.toSet()))
    }
}
