package com.nuvio.app.features.downloads

import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.details.EpisodeAvailability
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.SavedDetails
import com.nuvio.app.features.details.availability
import com.nuvio.app.features.details.buildSavedMeta
import com.nuvio.app.features.details.isPlayableNow
import com.nuvio.app.features.details.resolveSequentialPrimaryAction
import com.nuvio.app.features.details.seriesPrimaryAction
import com.nuvio.app.features.shuffle.EpisodeShuffle
import com.nuvio.app.features.shuffle.EpisodeShuffleSettings
import com.nuvio.app.features.shuffle.ShuffleSurface
import com.nuvio.app.features.shuffle.shufflePrimaryAction
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.home.HOME_DOWNLOADED_SECTION_KEY
import com.nuvio.app.features.home.buildDownloadedHomeSection
import com.nuvio.app.features.player.resolveSkipSegmentsJson
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.plugins.PluginsUiState
import com.nuvio.app.features.streams.PlaybackAvailability
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineFirstBehaviorTest {
    private class FakeBackend : DownloadsBackend {
        var payload: String? = null
        val presentUris = mutableSetOf<String>()
        val fileFacts = mutableMapOf<String, DownloadedFileFacts>()

        override fun loadPayload(): String? = payload
        override fun savePayload(payload: String) {
            this.payload = payload
        }
        override fun restoreItem(item: DownloadItem): DownloadItem = item
        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
            onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
            onFailure: (message: String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle = object : DownloadsTaskHandle {
            override fun cancel() = Unit
        }
        override fun removeFile(localFileUri: String?): Boolean {
            if (localFileUri != null) {
                presentUris.remove(localFileUri)
                fileFacts.remove(localFileUri)
            }
            return true
        }
        override fun removePartialFile(destinationFileName: String): Boolean = true
        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? =
            localFileUri?.takeIf { it in presentUris }
        override fun inspectFile(localFileUri: String): DownloadedFileFacts? = fileFacts[localFileUri]
    }

    private val backend = FakeBackend()
    private val realBackend = DownloadsRepository.backend
    private var tempFolder: File? = null

    @BeforeTest
    fun setup() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        backend.payload = """{"items":[]}"""
        DownloadsRepository.ensureLoaded()
    }

    @AfterTest
    fun tearDown() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        AddonRepository.clearLocalState()
        NetworkStatusRepository.resetForTest()
        tempFolder?.deleteRecursively()
        tempFolder = null
    }

    private fun downloadItem(
        id: String,
        parentMetaId: String,
        videoId: String,
        season: Int? = null,
        episode: Int? = null,
        title: String = "Title",
        episodeTitle: String? = null,
        poster: String? = "https://img/poster.jpg",
        status: DownloadStatus = DownloadStatus.Completed,
        framesCheck: DownloadFramesCheck = DownloadFramesCheck.Passed,
        verifiedBy: DownloadVerification? = DownloadVerification.Size,
        totalBytes: Long? = 1_000L,
        updatedAtEpochMs: Long = 100L,
        localUri: String = "file:///tmp/$id.mkv",
    ) = DownloadItem(
        id = id,
        contentType = if (season == null) "movie" else "series",
        parentMetaId = parentMetaId,
        parentMetaType = if (season == null) "movie" else "series",
        videoId = videoId,
        title = title,
        poster = poster,
        seasonNumber = season,
        episodeNumber = episode,
        episodeTitle = episodeTitle,
        streamTitle = "1080p",
        providerName = "TestAddon",
        sourceUrl = "https://example.com/$id.mkv",
        localFileUri = localUri,
        fileName = "$id.mkv",
        status = status,
        framesCheck = framesCheck,
        verifiedBy = verifiedBy,
        downloadedBytes = totalBytes ?: 0L,
        totalBytes = totalBytes,
        createdAtEpochMs = updatedAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    private fun persistItems(vararg items: DownloadItem) {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val listJson = items.joinToString(",") { json.encodeToString(DownloadItem.serializer(), it) }
        DownloadsRepository.clearLocalState()
        backend.payload = """{"items":[$listJson]}"""
        DownloadsRepository.ensureLoaded()
    }

    private fun streamAddon(): ManagedAddon = ManagedAddon(
        manifestUrl = "https://addon.example/manifest.json",
        enabled = true,
        manifest = AddonManifest(
            id = "stream-addon",
            version = "1.0.0",
            name = "StreamAddon",
            description = "Test stream addon",
            resources = listOf(AddonResource(name = "stream", types = listOf("movie", "series"))),
            types = listOf("movie", "series"),
            transportUrl = "https://addon.example/manifest.json",
        ),
    )

    @Test
    fun play_prefers_completed_verified_local_download_and_rejects_flagged_or_missing_files() {
        val realEp = downloadItem(
            id = "real-ep",
            parentMetaId = "tt100",
            videoId = "tt100:1:2",
            season = 1,
            episode = 2,
            framesCheck = DownloadFramesCheck.Passed,
            totalBytes = 2_000L,
        )
        val flaggedPlaceholder = downloadItem(
            id = "fake-ep",
            parentMetaId = "tt100",
            videoId = "tt100:1:3",
            season = 1,
            episode = 3,
            framesCheck = DownloadFramesCheck.NotTheRealVideo,
            totalBytes = 2_000L,
        )
        val missingFileEp = downloadItem(
            id = "missing-ep",
            parentMetaId = "tt100",
            videoId = "tt100:1:4",
            season = 1,
            episode = 4,
            framesCheck = DownloadFramesCheck.Passed,
            totalBytes = 2_000L,
        )
        val truncatedEp = downloadItem(
            id = "trunc-ep",
            parentMetaId = "tt100",
            videoId = "tt100:1:5",
            season = 1,
            episode = 5,
            framesCheck = DownloadFramesCheck.Passed,
            totalBytes = 2_000L,
        )
        val realUri = requireNotNull(realEp.localFileUri)
        val flaggedUri = requireNotNull(flaggedPlaceholder.localFileUri)
        val truncatedUri = requireNotNull(truncatedEp.localFileUri)
        backend.presentUris += listOf(realUri, flaggedUri, truncatedUri)
        backend.fileFacts[realUri] = DownloadedFileFacts(exists = true, sizeBytes = 2_000L)
        backend.fileFacts[flaggedUri] = DownloadedFileFacts(exists = true, sizeBytes = 2_000L)
        backend.fileFacts[truncatedUri] = DownloadedFileFacts(exists = true, sizeBytes = 500L)
        persistItems(realEp, flaggedPlaceholder, missingFileEp, truncatedEp)

        // Exact episode with verified local file matches
        assertEquals("real-ep", DownloadsRepository.findPlayableDownload("tt100", 1, 2, "tt100:1:2")?.id)
        assertTrue(DownloadsRepository.verifyCompletedDownload("real-ep"))
        assertEquals(realEp.localFileUri, DownloadsRepository.playableLocalFileUri(realEp))

        // Flagged "not the real video" item is rejected everywhere
        assertNull(DownloadsRepository.findPlayableDownload("tt100", 1, 3, "tt100:1:3"))
        assertNull(DownloadsRepository.playableLocalFileUri(flaggedPlaceholder))
        assertFalse(DownloadsRepository.verifyCompletedDownload("fake-ep"))

        // Missing file on disk is rejected
        assertNull(DownloadsRepository.findPlayableDownload("tt100", 1, 4, "tt100:1:4"))

        // Non-downloaded sibling episode does not match
        assertNull(DownloadsRepository.findPlayableDownload("tt100", 1, 1, "tt100:1:1"))

        // Truncated file fails verification
        assertFalse(DownloadsRepository.verifyCompletedDownload("trunc-ep"))
        assertNull(DownloadsRepository.findPlayableDownload("tt100", 1, 5, "tt100:1:5"))
    }

    @Test
    fun offline_download_index_distinguishes_title_posters_from_exact_episodes() {
        val ep = downloadItem("ep1", "tt-show", "tt-show:1:1", season = 1, episode = 1)
        val movie = downloadItem("mov1", "tt-movie", "tt-movie", season = null, episode = null)
        val flaggedShow = downloadItem(
            id = "bad-show",
            parentMetaId = "tt-flagged",
            videoId = "tt-flagged:1:1",
            season = 1,
            episode = 1,
            framesCheck = DownloadFramesCheck.NotTheRealVideo,
        )
        backend.presentUris += listOf(ep.localFileUri!!, movie.localFileUri!!, flaggedShow.localFileUri!!)
        val index = OfflineDownloadIndex(listOf(ep, movie, flaggedShow)) { item ->
            backend.resolveLocalFileUri(item.localFileUri, item.fileName)
        }

        assertTrue(index.hasMeta("tt-show"))
        assertTrue(index.hasMeta("tt-movie"))
        assertFalse(index.hasMeta("tt-flagged"))
        assertTrue(index.hasFor("tt-show", 1, 1, "tt-show:1:1"))
        assertFalse(index.hasFor("tt-show", 1, 2, "tt-show:1:2"))
        // Bare show id in episode lookup must not falsely match
        assertFalse(index.hasFor("tt-movie", 1, 1, "tt-movie"))
        assertTrue(index.hasFor("tt-movie", null, null, "tt-movie"))
    }

    @Test
    fun saved_subtitles_and_skip_times_work_offline_without_network() = runBlocking {
        val dir = Files.createTempDirectory("nuvio-offline-companions").toFile()
        tempFolder = dir
        val videoFile = File(dir, "Episode.1.mkv").apply { writeText("video") }
        val videoUri = videoFile.toURI().toString()
        val storage = DownloadSubtitleStorage(videoUri)
        storage.write("en.srt", "1\n00:00:01,000 --> 00:00:02,000\nHello")
        storage.write(
            "manifest.json",
            """{"complete":true,"tracks":[{"sourceUrl":"https://example.com/en.srt","fileName":"en.srt","language":"en","name":"English"}]}""",
        )

        val localSubs = DownloadSubtitles.localSubtitles(videoUri)
        assertEquals(1, localSubs.size)
        assertEquals("en", localSubs.single().language)
        assertTrue(localSubs.single().url.startsWith("file:"))

        // Also write episodes.json companion and confirm SavedDetails.metaFor builds show & episode metadata offline
        val episodesJson =
            """{"version":1,"fetchedAtEpochMs":1,"showName":"Offline Show","parentMetaId":"tt-saved",""" +
                """"parentMetaType":"series","imdbId":"tt-saved","poster":"https://img/p.jpg","episodes":[""" +
                """{"season":1,"episode":1,"id":"tt-saved:1:1","title":"Ep One","overview":"First."},""" +
                """{"season":1,"episode":2,"id":"tt-saved:1:2","title":"Ep Two","overview":"Second."}]}"""
        storage.write("episodes.json", episodesJson)
        val savedEpItem = downloadItem(
            id = "saved-ep-1",
            parentMetaId = "tt-saved",
            videoId = "tt-saved:1:1",
            season = 1,
            episode = 1,
            localUri = videoUri,
        )
        backend.presentUris += videoUri
        persistItems(savedEpItem)
        val savedMeta = SavedDetails.metaFor("series", "tt-saved")
        assertNotNull(savedMeta)
        assertEquals("Offline Show", savedMeta.name)
        assertEquals(listOf(true, false), savedMeta.videos.map { it.available })

        val skipJson = resolveSkipSegmentsJson(
            type = "series",
            videoId = "tt1:1:1",
            season = 1,
            episode = 1,
            contentId = "tt1",
            localSourceUrl = videoUri,
            isOffline = true,
            readSavedSkipIntervals = {
                listOf(SkipInterval(startTime = 12.5, endTime = 88.0, type = "intro", provider = "introdb"))
            },
        )
        assertNotNull(skipJson)
        assertTrue(skipJson.contains("\"intro\""))
        assertTrue(skipJson.contains("12.5"))
        assertTrue(skipJson.contains("88.0"))
    }

    @Test
    fun playback_availability_gates_online_only_items_when_offline_and_offers_stream_instead_when_online() {
        val addons = listOf(streamAddon())
        val plugins = PluginsUiState(pluginsEnabled = false)
        val localDownload = downloadItem("dl1", "tt-dl", "tt-dl:1:1", season = 1, episode = 1)
        val downloaderLookup: (String, Int?, Int?, String?) -> DownloadItem? = { parentMetaId, s, e, _ ->
            if (parentMetaId == "tt-dl" && s == 1 && e == 1) localDownload else null
        }

        val online = PlaybackAvailability(
            addons = addons,
            plugins = plugins,
            isOnline = true,
            findPlayableDownload = downloaderLookup,
        )
        assertTrue(online.canStream("series", "tt-online:1:1"))
        assertTrue(online.canPlay("series", "tt-online:1:1", "tt-online", 1, 1))
        assertFalse(online.canStreamInstead("series", "tt-online:1:1", "tt-online", 1, 1))
        assertTrue(online.canPlay("series", "tt-dl:1:1", "tt-dl", 1, 1))
        assertTrue(online.canStreamInstead("series", "tt-dl:1:1", "tt-dl", 1, 1))

        val offline = PlaybackAvailability(
            addons = addons,
            plugins = plugins,
            isOnline = false,
            findPlayableDownload = downloaderLookup,
        )
        assertFalse(offline.canStream("series", "tt-online:1:1"))
        assertFalse(offline.canPlay("series", "tt-online:1:1", "tt-online", 1, 1))
        assertFalse(offline.canStreamInstead("series", "tt-dl:1:1", "tt-dl", 1, 1))
        assertTrue(offline.canPlay("series", "tt-dl:1:1", "tt-dl", 1, 1))
    }

    @Test
    fun cached_episodes_become_unavailable_offline_unless_locally_downloaded() {
        val airedCached = MetaVideo(
            id = "tt1:1:1",
            title = "Online Only",
            released = "2024-01-01T00:00:00Z",
            season = 1,
            episode = 1,
            available = true,
        )
        val downloadedCached = MetaVideo(
            id = "tt1:1:2",
            title = "Downloaded",
            released = "2024-01-08T00:00:00Z",
            season = 1,
            episode = 2,
            available = true,
        )
        val futureEpisode = MetaVideo(
            id = "tt1:1:3",
            title = "Future",
            released = "2099-01-01T00:00:00Z",
            season = 1,
            episode = 3,
            available = true,
        )

        assertEquals(EpisodeAvailability.Unavailable, airedCached.availability(offline = true, hasLocalDownload = false))
        assertFalse(airedCached.isPlayableNow(offline = true, hasLocalDownload = false))
        assertEquals(EpisodeAvailability.Playable, downloadedCached.availability(offline = true, hasLocalDownload = true))
        assertTrue(downloadedCached.isPlayableNow(offline = true, hasLocalDownload = true))
        assertEquals(EpisodeAvailability.NotReleased, futureEpisode.availability(offline = true, hasLocalDownload = false))
    }

    @Test
    fun buildDownloadedHomeSection_groups_shows_ignores_placeholders_and_sorts_by_recent() {
        val showEp1 = downloadItem("s1e1", "tt-show", "tt-show:1:1", 1, 1, title = "Show", updatedAtEpochMs = 100L)
        val showEp2 = downloadItem("s1e2", "tt-show", "tt-show:1:2", 1, 2, title = "Show", updatedAtEpochMs = 300L)
        val movie = downloadItem("mov", "tt-mov", "tt-mov", null, null, title = "Film", updatedAtEpochMs = 200L)
        val flagged = downloadItem(
            id = "bad",
            parentMetaId = "tt-bad",
            videoId = "tt-bad",
            title = "Bad",
            framesCheck = DownloadFramesCheck.NotTheRealVideo,
            updatedAtEpochMs = 900L,
        )
        val downloading = downloadItem(
            id = "active",
            parentMetaId = "tt-active",
            videoId = "tt-active",
            title = "Active",
            status = DownloadStatus.Downloading,
            updatedAtEpochMs = 950L,
        )

        val section = buildDownloadedHomeSection(
            title = "Downloaded",
            items = listOf(showEp1, showEp2, movie, flagged, downloading),
            resolveLocalFile = { it.localFileUri },
            readSidecar = { uri ->
                if (uri == showEp2.localFileUri) {
                    SavedEpisodes(
                        fetchedAtEpochMs = 10L,
                        showName = "Saved Show Name",
                        parentMetaId = "tt-show",
                        parentMetaType = "series",
                        imdbId = "tt-show",
                        poster = "https://img/sidecar.jpg",
                        episodes = emptyList(),
                    )
                } else {
                    null
                }
            },
        )

        assertNotNull(section)
        assertEquals(HOME_DOWNLOADED_SECTION_KEY, section.key)
        assertEquals("Downloaded", section.title)
        assertEquals(listOf("tt-show", "tt-mov"), section.items.map { it.id })
        assertEquals("Saved Show Name", section.items.first().name)
    }

    @Test
    fun account_service_outage_does_not_block_working_streams() {
        val localEp = downloadItem("dl-outage", "tt-outage", "tt-outage:1:1", season = 1, episode = 1)
        backend.presentUris += localEp.localFileUri!!
        persistItems(localEp)
        AddonRepository.setAddonsForTest(listOf(streamAddon()))

        // When Supabase is unreachable (ServersUnreachable), public internet and addons still work.
        NetworkStatusRepository.setConditionForTest(NetworkCondition.ServersUnreachable)
        val availabilityDuringAccountOutage = PlaybackAvailability.current()
        assertTrue(availabilityDuringAccountOutage.canStream("series", "tt-online:1:1"))
        assertTrue(availabilityDuringAccountOutage.canStreamInstead("series", "tt-outage:1:1", "tt-outage", 1, 1))

        // Only actual NoInternet blocks streaming.
        NetworkStatusRepository.setConditionForTest(NetworkCondition.NoInternet)
        val availabilityOffline = PlaybackAvailability.current()
        assertFalse(availabilityOffline.canStream("series", "tt-online:1:1"))
        assertFalse(availabilityOffline.canStreamInstead("series", "tt-outage:1:1", "tt-outage", 1, 1))
        assertTrue(availabilityOffline.canPlay("series", "tt-outage:1:1", "tt-outage", 1, 1))
    }

    @Test
    fun offline_show_play_never_skips_undownloaded_or_undated_episode_to_play_later_download() {
        val ep2Download = downloadItem("dl-e2", "tt-skip", "tt-skip:1:2", season = 1, episode = 2)
        val ep3Download = downloadItem("dl-e3", "tt-skip", "tt-skip:1:3", season = 1, episode = 3)
        backend.presentUris += listOf(ep2Download.localFileUri!!, ep3Download.localFileUri!!)
        persistItems(ep2Download, ep3Download)

        val showMeta = MetaDetails(
            id = "tt-skip",
            type = "series",
            name = "Skip Test Show",
            videos = listOf(
                MetaVideo(id = "tt-skip:1:1", title = "Ep 1", released = "2024-01-01T00:00:00Z", season = 1, episode = 1, available = true),
                MetaVideo(id = "tt-skip:1:2", title = "Ep 2", released = null, season = 1, episode = 2, available = true),
                MetaVideo(id = "tt-skip:1:3", title = "Ep 3", released = "2024-01-15T00:00:00Z", season = 1, episode = 3, available = true),
            ),
        )
        val offline = PlaybackAvailability(
            addons = listOf(streamAddon()),
            plugins = PluginsUiState(pluginsEnabled = false),
            isOnline = false,
            findPlayableDownload = { parentId, s, e, _ ->
                if (parentId == "tt-skip" && s == 1 && e == 3) ep3Download else null
            },
        )

        // Scenario 1 (cold Play from cached catalog): S1E1 is NOT downloaded, S1E2/S1E3 ARE downloaded.
        // Play must stay on S1E1 with Play disabled, never skip to S1E2/S1E3.
        val coldAction = showMeta.resolveSequentialPrimaryAction(
            fromSavedDetails = false,
            entries = emptyList(),
            watchedItems = emptyList(),
            todayIsoDate = "2026-11-18",
            allowRewatch = true,
        )
        assertNotNull(coldAction)
        assertEquals(1, coldAction.episodeNumber)
        assertFalse(offline.canPlay("series", coldAction.videoId, showMeta.id, coldAction.seasonNumber, coldAction.episodeNumber))

        // Scenario 2 (Next Up from cached catalog): S1E1 is watched, S1E2 is NOT downloaded and undated (released = null),
        // S1E3 IS downloaded. Play must pick S1E2 with Play disabled, never skip to S1E3.
        val nextUpAction = showMeta.resolveSequentialPrimaryAction(
            fromSavedDetails = false,
            entries = emptyList(),
            watchedItems = listOf(
                WatchedItem(id = "tt-skip", type = "series", season = 1, episode = 1, name = "Skip Test Show", markedAtEpochMs = 100L),
            ),
            todayIsoDate = "2026-11-18",
            allowRewatch = true,
        )
        assertNotNull(nextUpAction)
        assertEquals(2, nextUpAction.episodeNumber)
        assertFalse(offline.canPlay("series", nextUpAction.videoId, showMeta.id, nextUpAction.seasonNumber, nextUpAction.episodeNumber))

        // Scenario 3 (SavedDetails fallback): episodes.json marks undownloaded episodes available = false in buildSavedMeta,
        // yet resolveSequentialPrimaryAction(fromSavedDetails = true) must still stay on S1E1 / S1E2 instead of skipping to S1E3.
        val savedMeta = buildSavedMeta(
            type = "series",
            id = "tt-skip",
            items = listOf(ep3Download),
            lists = listOf(
                SavedEpisodes(
                    fetchedAtEpochMs = 1L,
                    showName = "Skip Test Show",
                    parentMetaId = "tt-skip",
                    parentMetaType = "series",
                    episodes = listOf(
                        SavedEpisode(1, 1, "tt-skip:1:1", "Ep 1", released = "2024-01-01T00:00:00Z"),
                        SavedEpisode(1, 2, "tt-skip:1:2", "Ep 2", released = null),
                        SavedEpisode(1, 3, "tt-skip:1:3", "Ep 3", released = "2024-01-15T00:00:00Z"),
                    ),
                ),
            ),
        )!!
        val savedNextUp = savedMeta.resolveSequentialPrimaryAction(
            fromSavedDetails = true,
            entries = emptyList(),
            watchedItems = listOf(
                WatchedItem(id = "tt-skip", type = "series", season = 1, episode = 1, name = "Skip Test Show", markedAtEpochMs = 100L),
            ),
            todayIsoDate = "2026-11-18",
            allowRewatch = true,
        )
        assertNotNull(savedNextUp)
        assertEquals(2, savedNextUp.episodeNumber)
        assertFalse(offline.canPlay("series", savedNextUp.videoId, savedMeta.id, savedNextUp.seasonNumber, savedNextUp.episodeNumber))

        // Scenario 4 (Phantom catalog episode): S1E1 has available = false & released = null in catalog;
        // S1E2 is real (available = true, not downloaded), S1E3 is downloaded.
        // Must never pick phantom S1E1 and must stay on S1E2 (not skip to S1E3).
        val phantomShowMeta = MetaDetails(
            id = "tt-skip",
            type = "series",
            name = "Phantom Test Show",
            videos = listOf(
                MetaVideo(id = "tt-skip:1:1", title = "Phantom", released = null, season = 1, episode = 1, available = false),
                MetaVideo(id = "tt-skip:1:2", title = "Real Ep 2", released = null, season = 1, episode = 2, available = true),
                MetaVideo(id = "tt-skip:1:3", title = "Ep 3", released = "2024-01-15T00:00:00Z", season = 1, episode = 3, available = true),
            ),
        )
        val phantomCold = phantomShowMeta.resolveSequentialPrimaryAction(
            fromSavedDetails = false,
            entries = emptyList(),
            watchedItems = emptyList(),
            todayIsoDate = "2026-11-18",
            allowRewatch = true,
        )
        assertNotNull(phantomCold)
        assertEquals(2, phantomCold.episodeNumber)
        assertFalse(offline.canPlay("series", phantomCold.videoId, phantomShowMeta.id, phantomCold.seasonNumber, phantomCold.episodeNumber))

        // Scenario 5 (Offline Shuffle): only locally downloaded episodes are eligible when offline.
        val shuffle = EpisodeShuffle()
        val offlineShuffleMeta = showMeta.copy(
            videos = showMeta.videos.filter { it.season == 1 && it.episode == 3 },
        )
        val shuffleAction = offlineShuffleMeta.shufflePrimaryAction(
            profileId = 1,
            settings = EpisodeShuffleSettings(enabled = true, includeWatched = true),
            entries = emptyList(),
            watchedKeys = emptySet(),
            visit = 1L,
            shuffle = shuffle,
            surface = ShuffleSurface.DETAIL,
        )
        assertNotNull(shuffleAction)
        assertEquals(3, shuffleAction.episodeNumber)
        assertTrue(offline.canPlay("series", shuffleAction.videoId, showMeta.id, shuffleAction.seasonNumber, shuffleAction.episodeNumber))
    }

    @Test
    fun reconnecting_download_drive_and_folder_changes_refresh_offline_index() {
        val world = FolderWorld()
        val locBackend = LocationsBackend(world)
        try {
            val drive = world.driveLocation("Archive")
            val movie = movieItem("200", locationId = drive.id, bytes = 200_000L + 16L)
            val file = drive.path.resolve(movie.fileName).writeVideo(200)
            val storedMovie = movie.copy(
                localFileUri = file.toUri().toString(),
                totalBytes = Files.size(file),
                downloadedBytes = Files.size(file),
            )
            world.unplug("Archive")

            DownloadsRepository.clearLocalState()
            DownloadsRepository.backend = locBackend
            locBackend.payload = storedPayload(listOf(storedMovie))
            DownloadsRepository.ensureLoaded()
            DownloadsRepository.ensureOfflineIndexStarted()
            NetworkStatusRepository.setConditionForTest(NetworkCondition.NoInternet)

            val folders = DesktopDownloadFolders(
                locations = world.locations,
                validator = world.validator,
                mover = world.mover(),
                hooks = RepositoryDownloadsHooks,
            )
            // Initial refresh records the drive as NotConnected; initial indexing finishes empty.
            folders.refresh()
            Thread.sleep(150L)
            assertFalse(DownloadsRepository.offlineIndex.value.hasFor(storedMovie.parentMetaId, videoId = storedMovie.videoId))
            assertFalse(
                PlaybackAvailability(
                    addons = emptyList(),
                    plugins = PluginsUiState(pluginsEnabled = false),
                    isOnline = false,
                    findPlayableDownload = DownloadsRepository.offlineIndex.value::findFor,
                ).canPlay("movie", storedMovie.videoId, storedMovie.parentMetaId),
            )

            // Reconnect the drive without changing any DownloadItem record fields, then trigger refresh().
            world.replug("Archive")
            folders.refresh()

            val deadline = System.currentTimeMillis() + 2_000L
            while (!DownloadsRepository.offlineIndex.value.hasFor(storedMovie.parentMetaId, videoId = storedMovie.videoId) &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(20L)
            }
            assertTrue(
                DownloadsRepository.offlineIndex.value.hasFor(storedMovie.parentMetaId, videoId = storedMovie.videoId),
                "Reconnecting the download drive must refresh DownloadsRepository.offlineIndex",
            )
            val homeSection = buildDownloadedHomeSection(
                title = "Downloaded",
                items = DownloadsRepository.offlineIndex.value.playableItems,
                resolveLocalFile = DownloadsRepository.offlineIndex.value::resolvedUriFor,
            )
            assertNotNull(homeSection)
            assertEquals(listOf(storedMovie.parentMetaId), homeSection.items.map { it.id })

            // Disconnecting the drive again must also update the offline index.
            world.unplug("Archive")
            folders.refresh()
            val disconnectDeadline = System.currentTimeMillis() + 2_000L
            while (DownloadsRepository.offlineIndex.value.hasFor(storedMovie.parentMetaId, videoId = storedMovie.videoId) &&
                System.currentTimeMillis() < disconnectDeadline
            ) {
                Thread.sleep(20L)
            }
            assertFalse(
                DownloadsRepository.offlineIndex.value.hasFor(storedMovie.parentMetaId, videoId = storedMovie.videoId),
                "Disconnecting the download drive must clear items on that drive from offlineIndex",
            )

            // Direct drive-return handler must also refresh the offline index.
            world.replug("Archive")
            folders.onLocationReturned()
            val returnDeadline = System.currentTimeMillis() + 2_000L
            while (!DownloadsRepository.offlineIndex.value.hasFor(storedMovie.parentMetaId, videoId = storedMovie.videoId) &&
                System.currentTimeMillis() < returnDeadline
            ) {
                Thread.sleep(20L)
            }
            assertTrue(
                DownloadsRepository.offlineIndex.value.hasFor(storedMovie.parentMetaId, videoId = storedMovie.videoId),
                "onLocationReturned() must refresh DownloadsRepository.offlineIndex",
            )
        } finally {
            DownloadsRepository.clearLocalState()
            DownloadsRepository.backend = backend
            world.close()
        }
    }

    @Test
    fun findPlayableDownload_skips_sibling_episodes_and_verifyCompletedDownload_does_not_hold_state_lock_during_io() {
        val checkedUris = java.util.concurrent.CopyOnWriteArrayList<String>()
        val inspectStarted = java.util.concurrent.CountDownLatch(1)
        val releaseInspect = java.util.concurrent.CountDownLatch(1)
        val trackingBackend = object : DownloadsBackend by backend {
            override fun locate(item: DownloadItem): FileLookup {
                item.localFileUri?.let { checkedUris += it }
                return backend.locate(item)
            }

            override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
                if (localFileUri != null) checkedUris += localFileUri
                return backend.resolveLocalFileUri(localFileUri, destinationFileName)
            }

            override fun inspectFile(localFileUri: String): DownloadedFileFacts? {
                inspectStarted.countDown()
                releaseInspect.await(5, java.util.concurrent.TimeUnit.SECONDS)
                return DownloadedFileFacts(exists = true, sizeBytes = 1_000L)
            }
        }
        val ep1 = downloadItem("ep1", "tt-sib", "tt-sib:1:1", season = 1, episode = 1)
        val ep2 = downloadItem("ep2", "tt-sib", "tt-sib:1:2", season = 1, episode = 2)
        val ep3 = downloadItem("ep3", "tt-sib", "tt-sib:1:3", season = 1, episode = 3)
        backend.presentUris += listOf(ep1.localFileUri!!, ep2.localFileUri!!, ep3.localFileUri!!)
        persistItems(ep1, ep2, ep3)
        val indexDeadline = System.currentTimeMillis() + 2_000L
        while (!DownloadsRepository.offlineIndex.value.hasFor("tt-sib", 1, 3, "tt-sib:1:3") &&
            System.currentTimeMillis() < indexDeadline
        ) {
            Thread.sleep(10L)
        }
        DownloadsRepository.backend = trackingBackend
        checkedUris.clear()

        val found = DownloadsRepository.findPlayableDownload("tt-sib", 1, 1, "tt-sib:1:1")
        assertEquals("ep1", found?.id)
        assertEquals(
            listOf(ep1.localFileUri),
            checkedUris.toList(),
            "findPlayableDownload must not resolve unrelated sibling episodes on disk",
        )

        val verifyResult = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        val verifyThread = Thread {
            verifyResult.set(DownloadsRepository.verifyCompletedDownload(ep1.id))
        }.apply { isDaemon = true; start() }
        assertTrue(inspectStarted.await(2, java.util.concurrent.TimeUnit.SECONDS))

        val stateLockAcquired = java.util.concurrent.CountDownLatch(1)
        Thread {
            DownloadsRepository.holdStateLockForTest {
                stateLockAcquired.countDown()
            }
        }.apply { isDaemon = true; start() }

        val gotLockWhileInspectBlocked = stateLockAcquired.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        releaseInspect.countDown()
        verifyThread.join(2_000L)
        assertTrue(
            gotLockWhileInspectBlocked,
            "verifyCompletedDownload must not hold stateLock while inspecting the file on disk",
        )
        assertEquals(true, verifyResult.get())
    }

    @Test
    fun folder_changes_invoke_refreshOfflineIndex_hook_only_when_snapshot_changes() {
        val world = FolderWorld()
        try {
            val drive = world.driveLocation("Archive")
            var refreshCalls = 0
            val hooks = object : ProfileHooks(emptyList()) {
                override fun refreshOfflineIndex() {
                    refreshCalls += 1
                }
            }
            val folders = DesktopDownloadFolders(
                locations = world.locations,
                validator = world.validator,
                mover = world.mover(),
                hooks = hooks,
            )

            // First refresh records initial snapshot.
            folders.refresh()
            assertEquals(1, refreshCalls)

            // Steady-state polling refresh with no change must not churn offlineIndex.
            folders.refresh()
            assertEquals(1, refreshCalls)

            // Disconnecting and reconnecting the drive each trigger refreshOfflineIndex.
            world.unplug("Archive")
            folders.refresh()
            assertEquals(2, refreshCalls)

            world.replug("Archive")
            folders.refresh()
            assertEquals(3, refreshCalls)

            // Adding/changing default folder triggers refreshOfflineIndex.
            val secondary = world.volumes.resolve("Archive").resolve("MoreMovies").also { Files.createDirectories(it) }
            assertTrue(folders.setDefault(secondary.toString()))
            assertEquals(4, refreshCalls)

            // Direct drive-return handler triggers refreshOfflineIndex.
            folders.onLocationReturned()
            assertEquals(5, refreshCalls)
        } finally {
            world.close()
        }
    }

    @Test
    fun playback_preparation_handles_duplicates_manual_selection_and_verification_failure_off_main_thread() = runBlocking {
        val callerThread = Thread.currentThread()
        val resolutionThreads = java.util.concurrent.CopyOnWriteArrayList<Thread>()
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val truncated = downloadItem(
            id = "trunc-play",
            parentMetaId = "tt-prep",
            videoId = "tt-prep",
            totalBytes = 2_000L,
        )
        backend.presentUris += truncated.localFileUri!!
        backend.fileFacts[truncated.localFileUri] = DownloadedFileFacts(exists = true, sizeBytes = 100L)
        persistItems(truncated)

        val controller = com.nuvio.app.PlaybackPreparationController(
            scope = this,
            resolveLocalPlayback = { parentMetaId, season, episode, videoId ->
                started.complete(Unit)
                release.await()
                com.nuvio.app.resolveVerifiedLocalPlayback(
                    parentMetaId = parentMetaId,
                    seasonNumber = season,
                    episodeNumber = episode,
                    videoId = videoId,
                    findPlayable = { p, s, e, v ->
                        resolutionThreads += Thread.currentThread()
                        DownloadsRepository.findPlayableDownload(p, s, e, v)
                    },
                )
            },
        )

        // Manual selection bypasses download verification synchronously without entering loading state.
        var manualStreamFallbacks = 0
        controller.launchWithDownloadPreference(
            manualSelection = true,
            downloadsEnabled = true,
            parentMetaId = "tt-prep",
            seasonNumber = null,
            episodeNumber = null,
            videoId = "tt-prep",
            onStreamFallback = { manualStreamFallbacks += 1 },
            onLocalPlaybackReady = {},
        )
        assertEquals(1, manualStreamFallbacks)
        assertFalse(controller.isPreparing)

        // Automatic selection starts off-thread verification and ignores duplicate clicks while preparing.
        var streamFallbacks = 0
        var localOpens = 0
        controller.launchWithDownloadPreference(
            manualSelection = false,
            downloadsEnabled = true,
            parentMetaId = "tt-prep",
            seasonNumber = null,
            episodeNumber = null,
            videoId = "tt-prep",
            onStreamFallback = { streamFallbacks += 1 },
            onLocalPlaybackReady = { localOpens += 1 },
        )
        assertTrue(controller.isPreparing)
        controller.launchWithDownloadPreference(
            manualSelection = false,
            downloadsEnabled = true,
            parentMetaId = "tt-prep",
            seasonNumber = null,
            episodeNumber = null,
            videoId = "tt-prep",
            onStreamFallback = { streamFallbacks += 1 },
            onLocalPlaybackReady = { localOpens += 1 },
        )

        started.await()
        release.complete(Unit)
        kotlinx.coroutines.yield()
        while (controller.isPreparing) {
            kotlinx.coroutines.delay(10L)
        }

        // Truncated download fails verification and falls back to stream exactly once.
        assertEquals(0, localOpens)
        assertEquals(1, streamFallbacks)
        assertTrue(resolutionThreads.isNotEmpty())
        assertFalse(resolutionThreads.any { it === callerThread })
    }
}
