package com.nuvio.app.features.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.PlaybackPreparationController
import com.nuvio.app.PlaybackPreparationOverlay
import com.nuvio.app.VerifiedLocalPlayback
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.ui.NuvioContinueWatchingActionSheet
import com.nuvio.app.core.ui.NuvioPosterCard
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.details.EpisodeDetailsScreen
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.SavedDetailsNotice
import com.nuvio.app.features.details.components.DetailActionButtons
import com.nuvio.app.features.details.components.DetailSecondaryAction
import com.nuvio.app.features.details.components.EpisodeWatchedActionSheet
import com.nuvio.app.features.details.rememberSavedLookup
import com.nuvio.app.features.details.savedDetailsWanted
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import org.junit.Rule
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineFirstUiTest {
    @get:Rule
    val compose = createComposeRule()

    private class RecordingBackend : DownloadsBackend {
        var payload: String? = """{"items":[]}"""
        val presentUris = mutableSetOf<String>()
        val resolveCallThreads = CopyOnWriteArrayList<Thread>()
        val resolveCallCount = AtomicInteger(0)
        var progressCallback: ((Long, Long?) -> Unit)? = null
        var successCallback: ((String, Long?) -> Unit)? = null
        var onResolveHook: (() -> Unit)? = null

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
        ): DownloadsTaskHandle {
            progressCallback = onProgress
            successCallback = onSuccess
            return object : DownloadsTaskHandle {
                override fun cancel() = Unit
            }
        }
        override fun removeFile(localFileUri: String?): Boolean = true
        override fun removePartialFile(destinationFileName: String): Boolean = true
        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
            resolveCallCount.incrementAndGet()
            resolveCallThreads.add(Thread.currentThread())
            onResolveHook?.invoke()
            return localFileUri?.takeIf { it in presentUris }
        }
    }

    private val backend = RecordingBackend()
    private val realBackend = DownloadsRepository.backend
    private var tempFolder: File? = null

    @BeforeTest
    fun setup() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        backend.payload = """{"items":[]}"""
        DownloadsRepository.ensureLoaded()
        MetaDetailsRepository.clear()
        MetaDetailsRepository.loadMetaForTest = null
        AddonRepository.clearLocalState()
        NetworkStatusRepository.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        MetaDetailsRepository.clear()
        MetaDetailsRepository.loadMetaForTest = null
        AddonRepository.clearLocalState()
        NetworkStatusRepository.resetForTest()
        tempFolder?.deleteRecursively()
        tempFolder = null
    }

    @Test
    fun poster_card_displays_available_offline_badge_when_downloaded() {
        compose.setContent {
            NuvioTheme {
                NuvioPosterCard(
                    title = "Severance",
                    imageUrl = null,
                    isAvailableOffline = true,
                )
            }
        }

        compose.onNodeWithText("Available offline").assertIsDisplayed()
    }

    @Test
    fun detail_actions_offer_clickable_stream_instead_when_online_with_download() {
        var playCalls = 0
        var streamInsteadCalls = 0

        compose.setContent {
            NuvioTheme {
                DetailActionButtons(
                    playLabel = "Play",
                    playEnabled = true,
                    onPlayClick = { playCalls += 1 },
                    streamInsteadAction = DetailSecondaryAction(
                        label = "Stream instead",
                        icon = Icons.Default.PlayArrow,
                        onClick = { streamInsteadCalls += 1 },
                    ),
                )
            }
        }

        compose.onNodeWithText("Stream instead").assertIsDisplayed().performClick()
        compose.onNodeWithText("Play").performClick()

        assertEquals(1, streamInsteadCalls)
        assertEquals(1, playCalls)
    }

    @Test
    fun detail_actions_disable_play_and_hide_stream_instead_when_online_only_is_offline() {
        compose.setContent {
            NuvioTheme {
                DetailActionButtons(
                    playLabel = "Playback unavailable",
                    playEnabled = false,
                    streamInsteadAction = null,
                )
            }
        }

        compose.onNodeWithText("Playback unavailable").assertIsNotEnabled()
        compose.onNodeWithText("Stream instead").assertDoesNotExist()
    }

    @Test
    fun continue_watching_sheet_displays_stream_instead_when_enabled() {
        var manualPlayCalls = 0
        val item = ContinueWatchingItem(
            parentMetaId = "tt1",
            parentMetaType = "movie",
            videoId = "tt1",
            title = "Downloaded Film",
            subtitle = "Resume",
            imageUrl = null,
            resumePositionMs = 12_000L,
            durationMs = 120_000L,
            progressFraction = 0.1f,
        )

        compose.setContent {
            NuvioTheme {
                NuvioContinueWatchingActionSheet(
                    item = item,
                    showManualPlayOption = true,
                    manualPlayLabel = "Stream instead",
                    onDismiss = {},
                    onOpenDetails = {},
                    onStartFromBeginning = {},
                    onPlayManually = { manualPlayCalls += 1 },
                    onRemove = {},
                )
            }
        }

        compose.onNodeWithText("Stream instead").assertIsDisplayed().performClick()
        assertEquals(1, manualPlayCalls)
    }

    @Test
    fun episode_action_sheet_displays_stream_instead_and_saved_details_notice_works() {
        var manualCalls = 0
        compose.setContent {
            NuvioTheme {
                EpisodeWatchedActionSheet(
                    episode = MetaVideo(id = "tt1:1:1", title = "Pilot", season = 1, episode = 1),
                    seasonLabel = "Season 1",
                    isEpisodeWatched = false,
                    canMarkPreviousEpisodes = false,
                    arePreviousEpisodesWatched = false,
                    isSeasonWatched = false,
                    onDismiss = {},
                    onToggleWatched = {},
                    onTogglePreviousWatched = {},
                    onToggleSeasonWatched = {},
                    showPlayManually = true,
                    manualPlayLabel = "Stream instead",
                    onPlayManually = { manualCalls += 1 },
                )
                SavedDetailsNotice()
            }
        }

        compose.onNodeWithText("Stream instead").assertIsDisplayed().performClick()
        compose.onNodeWithText("Showing saved details. Connect to the internet for the full page.").assertIsDisplayed()
        assertEquals(1, manualCalls)
    }

    @Test
    fun offline_download_index_does_not_scan_filesystem_on_ui_thread_or_during_progress_updates() {
        val completedUri = "file:///tmp/indexed-show.mkv"
        backend.presentUris += completedUri
        val completedItem = DownloadItem(
            id = "dl-done",
            contentType = "series",
            parentMetaId = "tt-idx",
            parentMetaType = "series",
            videoId = "tt-idx:1:1",
            title = "Indexed Show",
            seasonNumber = 1,
            episodeNumber = 1,
            streamTitle = "1080p",
            providerName = "Test",
            sourceUrl = "https://example.com/done.mkv",
            localFileUri = completedUri,
            fileName = "done.mkv",
            status = DownloadStatus.Completed,
            framesCheck = DownloadFramesCheck.Passed,
            downloadedBytes = 1_000L,
            totalBytes = 1_000L,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        DownloadsRepository.clearLocalState()
        backend.payload = """{"items":[${json.encodeToString(DownloadItem.serializer(), completedItem)}]}"""
        DownloadsRepository.ensureLoaded()
        backend.resolveCallCount.set(0)
        backend.resolveCallThreads.clear()

        var uiThread: Thread? = null
        var hasMetaInUi = false
        compose.setContent {
            SideEffect {
                uiThread = Thread.currentThread()
            }
            NuvioTheme {
                // Multiple composables observing the index must share a cached index, not scan per poster.
                val idx1 = rememberOfflineDownloadIndex()
                val idx2 = rememberOfflineDownloadIndex()
                val idx3 = rememberOfflineDownloadIndex()
                hasMetaInUi = idx1.hasMeta("tt-idx") && idx2.hasMeta("tt-idx") && idx3.hasMeta("tt-idx")
                Text(
                    when {
                        idx1.hasMeta("tt-active") && hasMetaInUi -> "BothIndexedReady"
                        hasMetaInUi -> "IndexedReady"
                        else -> "IndexedPending"
                    },
                )
            }
        }

        compose.waitUntil(timeoutMillis = 3_000L) {
            compose.onAllNodesWithText("IndexedReady").fetchSemanticsNodes().isNotEmpty()
        }
        val initialResolveCount = backend.resolveCallCount.get()
        assertTrue(initialResolveCount > 0, "Expected background index to resolve completed download")
        assertFalse(
            backend.resolveCallThreads.any { it === uiThread },
            "Filesystem scan ran on the UI thread!",
        )
        assertEquals(1, initialResolveCount, "Expected a single cached index scan across 3 composables")

        // Progress update on an active download must NOT trigger any filesystem re-scan.
        val dir = Files.createTempDirectory("nuvio-idx-refresh").toFile()
        tempFolder = dir
        val activeFile = File(dir, "active.mkv").apply { writeText("video") }
        val activeUri = activeFile.toURI().toString()
        backend.presentUris += activeUri
        DownloadsRepository.enqueueFromStream(
            contentType = "movie",
            videoId = "tt-active",
            parentMetaId = "tt-active",
            parentMetaType = "movie",
            title = "Active Movie",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            episodeThumbnail = null,
            stream = StreamItem(url = "https://example.com/active.mkv", addonName = "Test", addonId = "test-addon"),
        )
        backend.progressCallback?.invoke(500L, 2_000L)
        compose.waitForIdle()
        assertEquals(
            initialResolveCount,
            backend.resolveCallCount.get(),
            "Download progress update must not re-scan completed downloads",
        )

        // When the download completes, the cached index automatically refreshes off the UI thread.
        backend.successCallback?.invoke(activeUri, 2_000L)
        compose.waitUntil(timeoutMillis = 3_000L) {
            compose.onAllNodesWithText("BothIndexedReady").fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse(
            backend.resolveCallThreads.any { it === uiThread },
            "Refresh filesystem scan ran on the UI thread!",
        )
    }

    @Test
    fun episode_page_retry_after_reconnecting_reloads_when_status_was_stale_offline() {
        NetworkStatusRepository.setConditionForTest(NetworkCondition.NoInternet)
        MetaDetailsRepository.loadMetaForTest = { _, id ->
            MetaDetails(
                id = id,
                type = "series",
                name = "Retry Show",
                videos = listOf(
                    MetaVideo(
                        id = "$id:1:1",
                        title = "Recovered Pilot",
                        released = "2024-01-01T00:00:00Z",
                        season = 1,
                        episode = 1,
                        available = true,
                    ),
                ),
            )
        }

        compose.setContent {
            NuvioTheme {
                EpisodeDetailsScreen(
                    type = "series",
                    id = "tt-retry",
                    videoId = "tt-retry:1:1",
                    season = 1,
                    episode = 1,
                    onBack = {},
                    onOpenShow = {},
                    onOpenSeason = {},
                    onOpenEpisode = {},
                    onPlay = { _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
                    onPlayManually = { _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
                )
            }
        }

        compose.waitUntil(timeoutMillis = 3_000L) {
            compose.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty()
        }

        // Connectivity is restored while NetworkStatusRepository still holds stale NoInternet.
        // Clicking Retry must still attempt the load and display the recovered episode.
        compose.onNodeWithText("Retry").performClick()

        compose.waitUntil(timeoutMillis = 3_000L) {
            compose.onAllNodesWithText("Recovered Pilot").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Recovered Pilot").assertIsDisplayed()
    }

    @Test
    fun episode_page_auto_reloads_when_network_status_transitions_online_after_offline_error() {
        NetworkStatusRepository.setConditionForTest(NetworkCondition.NoInternet)
        MetaDetailsRepository.loadMetaForTest = { _, id ->
            MetaDetails(
                id = id,
                type = "series",
                name = "Reconnect Show",
                videos = listOf(
                    MetaVideo(
                        id = "$id:1:1",
                        title = "Auto Reloaded Pilot",
                        released = "2024-01-01T00:00:00Z",
                        season = 1,
                        episode = 1,
                        available = true,
                    ),
                ),
            )
        }

        compose.setContent {
            NuvioTheme {
                EpisodeDetailsScreen(
                    type = "series",
                    id = "tt-reconnect",
                    videoId = "tt-reconnect:1:1",
                    season = 1,
                    episode = 1,
                    onBack = {},
                    onOpenShow = {},
                    onOpenSeason = {},
                    onOpenEpisode = {},
                    onPlay = { _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
                    onPlayManually = { _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
                )
            }
        }

        compose.waitUntil(timeoutMillis = 3_000L) {
            compose.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty()
        }

        NetworkStatusRepository.setConditionForTest(NetworkCondition.Online)

        compose.waitUntil(timeoutMillis = 3_000L) {
            compose.onAllNodesWithText("Auto Reloaded Pilot").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Auto Reloaded Pilot").assertIsDisplayed()
    }

    @Test
    fun downloads_list_update_does_not_blank_offline_saved_details_page() {
        val dir = Files.createTempDirectory("nuvio-saved-blank").toFile()
        tempFolder = dir
        val videoFile = File(dir, "Ep1.mkv").apply { writeText("video") }
        val videoUri = videoFile.toURI().toString()
        val storage = DownloadSubtitleStorage(videoUri)
        storage.write(
            "episodes.json",
            """{"version":1,"fetchedAtEpochMs":1,"showName":"Saved Offline Show","parentMetaId":"tt-saved-ui",""" +
                """"parentMetaType":"series","imdbId":"tt-saved-ui","episodes":[""" +
                """{"season":1,"episode":1,"id":"tt-saved-ui:1:1","title":"Ep One"}]}""",
        )
        backend.presentUris += videoUri
        val savedItem = DownloadItem(
            id = "saved-1",
            contentType = "series",
            parentMetaId = "tt-saved-ui",
            parentMetaType = "series",
            videoId = "tt-saved-ui:1:1",
            title = "Saved Offline Show",
            seasonNumber = 1,
            episodeNumber = 1,
            streamTitle = "1080p",
            providerName = "Test",
            sourceUrl = "https://example.com/e1.mkv",
            localFileUri = videoUri,
            fileName = "Ep1.mkv",
            status = DownloadStatus.Completed,
            downloadedBytes = 100L,
            totalBytes = 100L,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        DownloadsRepository.clearLocalState()
        backend.payload = """{"items":[${json.encodeToString(DownloadItem.serializer(), savedItem)}]}"""
        DownloadsRepository.ensureLoaded()
        NetworkStatusRepository.setConditionForTest(NetworkCondition.NoInternet)

        var wasEverLoaded = false
        var blankedAfterLoaded = false
        compose.setContent {
            NuvioTheme {
                val lookup by rememberSavedLookup(
                    type = "series",
                    id = "tt-saved-ui",
                    wanted = savedDetailsWanted(
                        detailsLoaded = false,
                        loadError = "Failed",
                        condition = NetworkCondition.NoInternet,
                    ),
                )
                if (lookup.meta != null) {
                    wasEverLoaded = true
                } else if (wasEverLoaded) {
                    blankedAfterLoaded = true
                }
                Column {
                    when {
                        lookup.meta != null -> Text(lookup.meta!!.name)
                        !lookup.settled -> Text("BLANK_GAP")
                        else -> Text("ERROR_SCREEN")
                    }
                }
            }
        }

        compose.waitUntil(timeoutMillis = 3_000L) {
            compose.onAllNodesWithText("Saved Offline Show").fetchSemanticsNodes().isNotEmpty()
        }

        // Simulate a slow disk read on the subsequent sidecar reload and trigger a downloads-list update.
        backend.onResolveHook = { Thread.sleep(150L) }
        DownloadsRepository.enqueueFromStream(
            contentType = "movie",
            videoId = "tt-other",
            parentMetaId = "tt-other",
            parentMetaType = "movie",
            title = "Other Movie",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            episodeThumbnail = null,
            stream = StreamItem(url = "https://example.com/other.mkv", addonName = "Test", addonId = "test-addon"),
        )
        compose.waitForIdle()

        assertFalse(
            blankedAfterLoaded,
            "Downloads-list update caused offline saved details to drop to null/blank!",
        )
        compose.onNodeWithText("Saved Offline Show").assertIsDisplayed()
    }

    @Test
    fun play_launch_verifies_download_off_ui_thread_with_loading_state_and_cancel_support() {
        val dir = Files.createTempDirectory("nuvio-play-off-ui").toFile()
        tempFolder = dir
        val videoFile = File(dir, "SlowDriveMovie.mkv").apply { writeText("video") }
        val videoUri = videoFile.toURI().toString()
        val inspectEntered = java.util.concurrent.CountDownLatch(1)
        val releaseInspect = java.util.concurrent.CountDownLatch(1)
        val inspectThreads = CopyOnWriteArrayList<Thread>()
        var blockInspect = true
        val slowBackend = object : DownloadsBackend {
            override fun loadPayload(): String? = backend.payload
            override fun savePayload(payload: String) {
                backend.payload = payload
            }
            override fun restoreItem(item: DownloadItem): DownloadItem = item
            override fun start(
                request: DownloadPlatformRequest,
                onProgress: (Long, Long?) -> Unit,
                onSuccess: (String, Long?) -> Unit,
                onFailure: (String) -> Unit,
                onPaused: () -> Unit,
            ): DownloadsTaskHandle = backend.start(request, onProgress, onSuccess, onFailure, onPaused)
            override fun removeFile(localFileUri: String?): Boolean = true
            override fun removePartialFile(destinationFileName: String): Boolean = true
            override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? =
                backend.resolveLocalFileUri(localFileUri, destinationFileName)
            override fun inspectFile(localFileUri: String): DownloadedFileFacts? {
                inspectThreads.add(Thread.currentThread())
                if (blockInspect) {
                    inspectEntered.countDown()
                    releaseInspect.await(5, java.util.concurrent.TimeUnit.SECONDS)
                }
                return DownloadedFileFacts(exists = true, sizeBytes = 1_000L)
            }
        }
        backend.presentUris += videoUri
        val slowMovie = DownloadItem(
            id = "slow-movie",
            contentType = "movie",
            parentMetaId = "tt-slow",
            parentMetaType = "movie",
            videoId = "tt-slow",
            title = "Slow Drive Movie",
            streamTitle = "1080p",
            providerName = "Test",
            sourceUrl = "https://example.com/slow.mkv",
            localFileUri = videoUri,
            fileName = "SlowDriveMovie.mkv",
            status = DownloadStatus.Completed,
            downloadedBytes = 1_000L,
            totalBytes = 1_000L,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = slowBackend
        backend.payload = """{"items":[${json.encodeToString(DownloadItem.serializer(), slowMovie)}]}"""
        DownloadsRepository.ensureLoaded()
        backend.resolveCallThreads.clear()
        backend.resolveCallCount.set(0)

        var uiThread: Thread? = null
        val launched = CopyOnWriteArrayList<VerifiedLocalPlayback>()
        var fallbackCount = 0

        compose.setContent {
            SideEffect {
                uiThread = Thread.currentThread()
            }
            NuvioTheme {
                val scope = rememberCoroutineScope()
                val preparation = remember(scope) { PlaybackPreparationController(scope = scope) }
                var tapsWhileLoading by remember { mutableIntStateOf(0) }
                var lastAction by remember { mutableStateOf("Idle") }
                Box {
                    Column {
                        Text("Taps:$tapsWhileLoading:$lastAction")
                        Button(
                            onClick = {
                                tapsWhileLoading += 1
                                preparation.launchWithDownloadPreference(
                                    manualSelection = false,
                                    downloadsEnabled = true,
                                    parentMetaId = "tt-slow",
                                    seasonNumber = null,
                                    episodeNumber = null,
                                    videoId = "tt-slow",
                                    onStreamFallback = {
                                        fallbackCount += 1
                                        lastAction = "Stream"
                                    },
                                    onLocalPlaybackReady = { resolved ->
                                        launched.add(resolved)
                                        lastAction = "Local:${resolved.item.id}"
                                    },
                                )
                            },
                        ) {
                            Text("Play")
                        }
                    }
                    PlaybackPreparationOverlay(
                        visible = preparation.isPreparing,
                        onCancel = preparation::cancel,
                    )
                }
            }
        }

        // First click starts verification off the UI thread and immediately shows the loading state.
        compose.onNodeWithText("Play").performClick()
        assertTrue(inspectEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        compose.waitUntil(timeoutMillis = 2_000L) {
            compose.onAllNodesWithText("Preparing playback…").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Preparing playback…").assertIsDisplayed()
        assertTrue(launched.isEmpty(), "Playback must wait for verification off the main thread")

        // UI thread stayed free and recorded zero UI-thread file scans/inspections.
        assertFalse(backend.resolveCallThreads.any { it === uiThread }, "resolveLocalFileUri ran on UI thread!")
        assertFalse(inspectThreads.any { it === uiThread }, "inspectFile ran on UI thread!")

        // Clicking Cancel keeps the user responsive and aborts the pending launch cleanly.
        compose.onNodeWithContentDescription("Cancel").performClick()
        compose.waitUntil(timeoutMillis = 2_000L) {
            compose.onAllNodesWithText("Preparing playback…").fetchSemanticsNodes().isEmpty()
        }
        releaseInspect.countDown()
        compose.waitForIdle()
        assertTrue(launched.isEmpty(), "Cancelled Play launch must not open the player")
        assertEquals(0, fallbackCount)

        // Second Play click completes off-thread verification, clears the loading state, and opens local playback.
        blockInspect = false
        compose.onNodeWithText("Play").performClick()
        compose.waitUntil(timeoutMillis = 2_000L) {
            compose.onAllNodesWithText("Taps:2:Local:slow-movie").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, launched.size)
        assertEquals("slow-movie", launched.single().item.id)
        assertEquals(videoUri, launched.single().sourceUrl)
        assertTrue(compose.onAllNodesWithText("Preparing playback…").fetchSemanticsNodes().isEmpty())
        assertFalse(backend.resolveCallThreads.any { it === uiThread }, "resolveLocalFileUri ran on UI thread!")
        assertFalse(inspectThreads.any { it === uiThread }, "inspectFile ran on UI thread!")
    }
}
