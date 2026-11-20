package com.nuvio.app.features.downloads

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.debrid.DebridStreamLanguage
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamDebridCacheStatus
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_back
import nuvio.composeapp.generated.resources.compose_settings_root_downloads_title
import nuvio.composeapp.generated.resources.downloads_trip_fill_plan_button
import nuvio.composeapp.generated.resources.downloads_trip_fill_plan_heading
import nuvio.composeapp.generated.resources.downloads_trip_fill_start
import nuvio.composeapp.generated.resources.downloads_trip_ready_button
import nuvio.composeapp.generated.resources.downloads_trip_ready_title
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Before
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class TripReadyPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var previousBackend: DownloadsBackend
    private lateinit var backend: FakeDownloadsBackend
    private lateinit var runnerScope: CoroutineScope

    @Before
    fun setUp() {
        previousBackend = DownloadsRepository.backend
        backend = FakeDownloadsBackend()
        DownloadsRepository.backend = backend
        DownloadsRepository.clearLocalState()
        runnerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        runnerScope.cancel()
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = previousBackend
    }

    @Test
    fun `downloads header opens trip ready showing offline hours ETAs issues unplugged drives and fill preview before starting`() {
        val localDrive = DownloadLocationInfo(
            id = "local",
            path = "/downloads",
            name = "Downloads",
            isDefault = true,
            isAppFolder = true,
            availability = FolderAvailability.Available,
            itemCount = 5,
            totalBytes = 5_000_000_000L,
            freeBytes = 40_000_000_000L,
        )
        val unpluggedDrive = DownloadLocationInfo(
            id = "usb-1",
            path = "/Volumes/TripUSB",
            name = "TripUSB",
            isDefault = false,
            isAppFolder = false,
            availability = FolderAvailability.NotConnected,
            itemCount = 1,
            totalBytes = 2_000_000_000L,
            freeBytes = 0L,
        )
        val folders = FakeFolderController(
            DownloadFolderState(locations = listOf(localDrive, unpluggedDrive)),
        )

        val nowMs = 20_000L
        val seedItems = listOf(
            // Verified completed: 45 min + 90 min = 2 hrs 15 min
            DownloadItem(
                id = "ep-ok",
                contentType = "series",
                parentMetaId = "show:andor",
                parentMetaType = "series",
                videoId = "show:andor:1:1",
                title = "Andor",
                seasonNumber = 1,
                episodeNumber = 1,
                streamTitle = "Andor S01E01",
                providerName = "Addon",
                sourceUrl = "https://ex/ep1.mkv",
                localFileUri = "file:///downloads/ep1.mkv",
                fileName = "ep1.mkv",
                status = DownloadStatus.Completed,
                downloadedBytes = 800_000_000L,
                totalBytes = 800_000_000L,
                createdAtEpochMs = 1_000L,
                updatedAtEpochMs = 5_000L,
                verifiedBy = DownloadVerification.Hash,
                locationId = "local",
            ),
            DownloadItem(
                id = "mv-ok",
                contentType = "movie",
                parentMetaId = "movie:arrival",
                parentMetaType = "movie",
                videoId = "movie:arrival",
                title = "Arrival",
                streamTitle = "Arrival",
                providerName = "Addon",
                sourceUrl = "https://ex/arrival.mkv",
                localFileUri = "file:///downloads/arrival.mkv",
                fileName = "arrival.mkv",
                status = DownloadStatus.Completed,
                downloadedBytes = 2_000_000_000L,
                totalBytes = 2_000_000_000L,
                createdAtEpochMs = 1_000L,
                updatedAtEpochMs = 5_000L,
                verifiedBy = DownloadVerification.Size,
                locationId = "local",
            ),
            // Suspect episode with missing subtitles & missing skip times (excluded from verified hours)
            DownloadItem(
                id = "ep-suspect",
                contentType = "series",
                parentMetaId = "show:severance",
                parentMetaType = "series",
                videoId = "show:severance:1:1",
                title = "Severance",
                seasonNumber = 1,
                episodeNumber = 1,
                streamTitle = "Severance S01E01",
                providerName = "Addon",
                sourceUrl = "https://ex/sev1.mkv",
                localFileUri = "file:///downloads/sev1.mkv",
                fileName = "sev1.mkv",
                status = DownloadStatus.Completed,
                downloadedBytes = 700_000_000L,
                totalBytes = 700_000_000L,
                createdAtEpochMs = 1_000L,
                updatedAtEpochMs = 5_000L,
                verifiedBy = DownloadVerification.Size,
                framesCheck = DownloadFramesCheck.Suspect,
                locationId = "local",
            ),
            // Unplugged drive movie
            DownloadItem(
                id = "mv-usb",
                contentType = "movie",
                parentMetaId = "movie:dune",
                parentMetaType = "movie",
                videoId = "movie:dune",
                title = "Dune",
                streamTitle = "Dune",
                providerName = "Addon",
                sourceUrl = "https://ex/dune.mkv",
                localFileUri = "file:///Volumes/TripUSB/dune.mkv",
                fileName = "dune.mkv",
                status = DownloadStatus.Completed,
                downloadedBytes = 3_000_000_000L,
                totalBytes = 3_000_000_000L,
                createdAtEpochMs = 1_000L,
                updatedAtEpochMs = 5_000L,
                verifiedBy = DownloadVerification.Hash,
                locationId = "usb-1",
            ),
            // Active downloading episode -> 120 MB remaining at 1 MB/s = 120s (2 min)
            DownloadItem(
                id = "ep-dl",
                contentType = "series",
                parentMetaId = "show:andor",
                parentMetaType = "series",
                videoId = "show:andor:1:2",
                title = "Andor",
                seasonNumber = 1,
                episodeNumber = 2,
                streamTitle = "Andor S01E02",
                providerName = "Addon",
                sourceUrl = "https://ex/ep2.mkv",
                fileName = "ep2.mkv",
                status = DownloadStatus.Downloading,
                downloadedBytes = 80_000_000L,
                totalBytes = 200_000_000L,
                createdAtEpochMs = 10_000L,
                updatedAtEpochMs = 19_000L,
                locationId = "local",
            ),
        )
        backend.payload = DownloadsCodec.encode(seedItems, loaded = null)
        DownloadsRepository.ensureLoaded()

        var enqueueCalls = 0
        val runnerStateFlow = MutableStateFlow(DownloadsUiState(items = seedItems))
        val showCandidate = TripShowCandidate(
            parentMetaType = "series",
            parentMetaId = "show:andor",
            title = "Andor",
            fromContinueWatching = true,
        )
        val cachedStream = StreamItem(
            name = "Andor.S01E03.1080p",
            title = "Andor.S01E03.1080p",
            url = "https://ex/ep3.mkv",
            addonName = "Addon",
            addonId = "addon:test",
            behaviorHints = StreamBehaviorHints(videoSize = 900_000_000L, filename = "ep3.mkv"),
            debridCacheStatus = StreamDebridCacheStatus(
                providerId = "rd",
                providerName = "RD",
                state = StreamDebridCacheState.CACHED,
                cachedSize = 900_000_000L,
            ),
        )
        val plannedEp3 = TripPlannedEpisode(
            show = showCandidate,
            episode = SeasonBatchEpisode("show:andor:1:3", 1, 3, "Reckoning", null, runtimeMinutes = 50),
            effectiveRuntimeMinutes = 50,
            runtimeEstimated = false,
            rankedStreams = listOf(cachedStream),
            selectedStream = cachedStream,
            isCached = true,
            estimatedBytes = 900_000_000L,
            sizeEstimated = false,
        )
        val runner = TripFillRunner(
            scope = runnerScope,
            downloads = runnerStateFlow,
            prepareStream = { s, _ -> s },
            enqueue = { _, _, _ ->
                enqueueCalls++
                val completedEp = seedItems.first().copy(
                    id = "ep3-queued",
                    episodeNumber = 3,
                    videoId = "show:andor:1:3",
                    fileName = "ep3.mkv",
                )
                runnerStateFlow.value = DownloadsUiState(items = runnerStateFlow.value.items + completedEp)
                DownloadEnqueueResult.Started
            },
        )

        val deps = TripReadyDeps(
            collectShows = { listOf(showCandidate) },
            buildPlan = { hours, _ ->
                TripFillPlan(
                    requestedHours = hours,
                    plannedEpisodes = listOf(plannedEp3),
                    totalPlannedMinutes = 50,
                    totalEstimatedBytes = 900_000_000L,
                    qualityDefaults = SeasonDownloadDefaults(
                        quality = BatchQuality.P1080,
                        maxBitrateMbps = 8,
                        audioLanguage = DebridStreamLanguage.EN,
                    ),
                    storageLimitBytes = 10_000_000_000L,
                    remainingStorageLimitBytes = 9_100_000_000L,
                    freeDiskBytes = 40_000_000_000L,
                    connectionHoldReason = null,
                    stoppedReason = TripPlanStopReason.NoMoreEpisodes,
                )
            },
            runner = runner,
            loadDiskInfo = { _, _ ->
                mapOf(
                    "ep-ok" to TripItemDiskInfo(subtitleCount = 1, skipTimesNote = SkipTimesNote.Saved),
                    "mv-ok" to TripItemDiskInfo(subtitleCount = 1, skipTimesNote = SkipTimesNote.Saved),
                    "ep-suspect" to TripItemDiskInfo(subtitleCount = 0, skipTimesNote = null),
                )
            },
            durationMsOf = { item, _ ->
                when (item.id) {
                    "ep-ok" -> 45 * 60_000L
                    "mv-ok" -> 90 * 60_000L
                    else -> null
                }
            },
            bytesPerSecondOf = { item, _ -> if (item.id == "ep-dl") 1_000_000L else null },
        )

        val tripReadyButtonLabel = runBlocking { getString(Res.string.downloads_trip_ready_button) }
        val tripReadyTitle = runBlocking { getString(Res.string.downloads_trip_ready_title) }
        val downloadsTitle = runBlocking { getString(Res.string.compose_settings_root_downloads_title) }
        val backDesc = runBlocking { getString(Res.string.action_back) }
        val preview4HrText = runBlocking { getString(Res.string.downloads_trip_fill_plan_button, 4) }
        val planHeading4Hr = runBlocking { getString(Res.string.downloads_trip_fill_plan_heading, 4) }
        val startDownloadingText = runBlocking { getString(Res.string.downloads_trip_fill_start) }

        compose.setContent {
            NuvioTheme {
                DownloadsScreenContent(
                    onBack = {},
                    onOpenDownload = {},
                    showAutoDownloads = false,
                    folders = folders,
                    connectionHold = ConnectionHold(),
                    tripDeps = deps,
                    nowEpochMs = { nowMs },
                )
            }
        }

        // Open Trip ready from the Downloads header.
        compose.onNodeWithText(tripReadyButtonLabel).assertIsDisplayed().performClick()
        compose.onNodeWithText(tripReadyTitle).assertIsDisplayed()

        // Total verified offline hours = 45m + 90m = 2 hrs 15 min (suspect & unplugged excluded).
        compose.onNodeWithText("2 hrs 15 min").assertIsDisplayed()

        // Fill for my trip: select 4 hrs, preview plan FIRST and verify nothing started yet.
        compose.onNodeWithText("4 hrs").performClick()
        compose.onNodeWithText(preview4HrText).performClick()
        compose.waitUntil(timeoutMillis = 3_000L) {
            compose.onAllNodesWithText(planHeading4Hr).fetchSemanticsNodes().isNotEmpty()
        }
        fun scrollToAndAssert(text: String, substring: Boolean = false) {
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = substring))
            compose.onNodeWithText(text, substring = substring).assertIsDisplayed()
        }

        scrollToAndAssert(planHeading4Hr)
        scrollToAndAssert("Preferences: 1080p, Max 8 Mbps, English")
        scrollToAndAssert("Andor • S01E03 • Reckoning")
        assertEquals(0, enqueueCalls)

        // Start downloading AFTER reviewing the plan.
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(startDownloadingText))
        compose.onNodeWithText(startDownloadingText).performClick()
        compose.waitUntil(timeoutMillis = 3_000L) {
            compose.onAllNodesWithText("Queued 1 of 1 planned episodes.").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, enqueueCalls)

        // Scroll through the rest of the Trip ready report sections.
        scrollToAndAssert("Still downloading")
        scrollToAndAssert("About 2 min left", substring = true)
        scrollToAndAssert("Needs attention")
        scrollToAndAssert("Suspect: file title names another episode • No subtitles saved • No skip times saved")
        scrollToAndAssert("On unplugged drives")
        scrollToAndAssert("TripUSB • Drive not connected")
        scrollToAndAssert("Ready by show and movie")
        scrollToAndAssert("Arrival")

        // Back button exits Trip ready back to Downloads root.
        compose.onNodeWithContentDescription(backDesc).performClick()
        compose.onNodeWithText(downloadsTitle).assertIsDisplayed()
    }

    private class FakeFolderController(
        initialState: DownloadFolderState,
    ) : DownloadFolderController by NoDownloadFolders {
        override val supported: Boolean = true
        override val state: StateFlow<DownloadFolderState> = MutableStateFlow(initialState)
        override fun locationIdOf(item: DownloadItem): String = item.locationId.orEmpty()
    }

    private class FakeDownloadsBackend : DownloadsBackend {
        var payload: String? = null

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

        override fun removeFile(localFileUri: String?): Boolean = true

        override fun removePartialFile(destinationFileName: String): Boolean = true

        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri
    }
}
