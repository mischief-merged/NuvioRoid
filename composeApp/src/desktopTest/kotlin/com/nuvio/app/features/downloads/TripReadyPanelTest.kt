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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_back
import nuvio.composeapp.generated.resources.compose_settings_root_downloads_title
import nuvio.composeapp.generated.resources.downloads_checking_network
import nuvio.composeapp.generated.resources.downloads_held_download_anyway
import nuvio.composeapp.generated.resources.downloads_trip_fill_plan_button
import nuvio.composeapp.generated.resources.downloads_trip_fill_plan_heading
import nuvio.composeapp.generated.resources.downloads_trip_fill_start
import nuvio.composeapp.generated.resources.downloads_trip_ready_button
import nuvio.composeapp.generated.resources.downloads_trip_ready_title
import nuvio.composeapp.generated.resources.travel_status
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

    @Test
    fun `trip ready shows travel banner without download anyway for travel and checking network holds`() {
        val pausedAuto = DownloadItem(
            id = "ep-travel",
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
            fileName = "ep1.mkv",
            status = DownloadStatus.Paused,
            downloadedBytes = 100_000L,
            totalBytes = 500_000L,
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 2_000L,
            autoDownloadRuleKey = "series:show:andor",
            pausedByConnection = true,
        )
        val downloadsFlow = MutableStateFlow(DownloadsUiState(items = listOf(pausedAuto)))
        val hold = ConnectionHold().apply { setTravelEnabled(true) }
        val preview8HrText = runBlocking { getString(Res.string.downloads_trip_fill_plan_button, DefaultTripFillHours) }
        val planHeading8Hr = runBlocking { getString(Res.string.downloads_trip_fill_plan_heading, DefaultTripFillHours) }
        val travelStatusText = runBlocking { getString(Res.string.travel_status, (DefaultTravelCapBytesPerSecond / 1000).toInt()) }
        val downloadAnywayText = runBlocking { getString(Res.string.downloads_held_download_anyway) }
        val checkingNetworkText = runBlocking { getString(Res.string.downloads_checking_network) }

        var planHoldReason: LimitedConnectionReason? = LimitedConnectionReason.CheckingNetwork
        val deps = TripReadyDeps(
            collectShows = { emptyList() },
            buildPlan = { hours, _ ->
                TripFillPlan(
                    requestedHours = hours,
                    plannedEpisodes = emptyList(),
                    totalPlannedMinutes = 0,
                    totalEstimatedBytes = 0L,
                    qualityDefaults = SeasonDownloadDefaults(),
                    storageLimitBytes = null,
                    remainingStorageLimitBytes = null,
                    freeDiskBytes = null,
                    connectionHoldReason = planHoldReason,
                    stoppedReason = TripPlanStopReason.NoMoreEpisodes,
                )
            },
            loadDiskInfo = { _, _ -> emptyMap() },
        )

        compose.setContent {
            NuvioTheme {
                TripReadyPanel(
                    onBack = {},
                    onOpenDownload = {},
                    onOpenShow = { _, _ -> },
                    downloadsStateFlow = downloadsFlow,
                    folders = NoDownloadFolders,
                    rechecks = null,
                    connectionHold = hold,
                    tripDeps = deps,
                )
            }
        }

        // Travel mode: shows Travel status banner, never "Download anyway".
        compose.onNodeWithText(travelStatusText).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText(downloadAnywayText).fetchSemanticsNodes().size)

        // Checking network on the plan card: shows checking message without "Download anyway".
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(preview8HrText))
        compose.onNodeWithText(preview8HrText).performClick()
        compose.waitUntil(timeoutMillis = 3_000L) {
            compose.onAllNodesWithText(planHeading8Hr).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(checkingNetworkText))
        compose.onNodeWithText(checkingNetworkText).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText(downloadAnywayText).fetchSemanticsNodes().size)

        // Switching to a Hotspot hold shows "Download anyway" for both the held download and the plan card.
        planHoldReason = LimitedConnectionReason.Hotspot
        hold.setTravelEnabled(false)
        hold.report(NetworkPathReport.Known(expensive = true, constrained = false, networkKey = "en0|r1"))
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(preview8HrText))
        compose.onNodeWithText(preview8HrText).performClick()
        compose.waitUntil(timeoutMillis = 3_000L) {
            compose.onAllNodesWithText(downloadAnywayText).fetchSemanticsNodes().size == 2
        }
    }

    @Test
    fun `stopping trip queueing while two downloads are receiving shows two in progress and done summary`() {
        val showCandidate = TripShowCandidate(
            parentMetaType = "series",
            parentMetaId = "show:trip",
            title = "Andor",
            fromContinueWatching = true,
        )
        val cachedStream = StreamItem(
            name = "Andor.1080p",
            title = "Andor.1080p",
            url = "https://ex/ep.mkv",
            addonName = "Addon",
            addonId = "addon:test",
            behaviorHints = StreamBehaviorHints(videoSize = 900_000_000L, filename = "ep.mkv"),
            debridCacheStatus = StreamDebridCacheStatus(
                providerId = "rd",
                providerName = "RD",
                state = StreamDebridCacheState.CACHED,
                cachedSize = 900_000_000L,
            ),
        )
        val plannedEpisodes = (1..4).map { number ->
            TripPlannedEpisode(
                show = showCandidate,
                episode = SeasonBatchEpisode("show:trip:1:$number", 1, number, "Episode $number", null, runtimeMinutes = 45),
                effectiveRuntimeMinutes = 45,
                runtimeEstimated = false,
                rankedStreams = listOf(cachedStream.copy(url = "https://ex/ep$number.mkv")),
                selectedStream = cachedStream.copy(url = "https://ex/ep$number.mkv"),
                isCached = true,
                estimatedBytes = 900_000_000L,
                sizeEstimated = false,
            )
        }
        DownloadsRepository.ensureLoaded()
        val profileId = DownloadsRepository.loadedProfile()
        val runner = TripFillRunner(
            scope = runnerScope,
            downloads = DownloadsRepository.uiState,
            prepareStream = { s, _ -> s },
            enqueue = { _, _, _ -> DownloadEnqueueResult.MissingUrl },
            enqueueOwned = DownloadsRepository::enqueueTripEpisode,
            resume = DownloadsRepository::resumeTripEpisode,
            currentProfileId = { profileId },
            maxConcurrent = 2,
        )
        val deps = TripReadyDeps(
            collectShows = { listOf(showCandidate) },
            buildPlan = { hours, _ ->
                TripFillPlan(
                    requestedHours = hours,
                    plannedEpisodes = plannedEpisodes,
                    totalPlannedMinutes = 180,
                    totalEstimatedBytes = 3_600_000_000L,
                    qualityDefaults = SeasonDownloadDefaults(),
                    storageLimitBytes = null,
                    remainingStorageLimitBytes = null,
                    freeDiskBytes = 40_000_000_000L,
                    connectionHoldReason = null,
                    stoppedReason = TripPlanStopReason.TargetReached,
                    profileId = profileId,
                )
            },
            runner = runner,
            loadDiskInfo = { _, _ -> emptyMap() },
            durationMsOf = { _, _ -> null },
            bytesPerSecondOf = { _, _ -> null },
            fetchMissingCompanions = null,
        )

        val preview8HrText = runBlocking { getString(Res.string.downloads_trip_fill_plan_button, DefaultTripFillHours) }
        val startDownloadingText = runBlocking { getString(Res.string.downloads_trip_fill_start) }

        compose.setContent {
            NuvioTheme {
                TripReadyPanel(
                    onBack = {},
                    onOpenDownload = {},
                    onOpenShow = { _, _ -> },
                    downloadsStateFlow = DownloadsRepository.uiState,
                    folders = NoDownloadFolders,
                    rechecks = null,
                    connectionHold = ConnectionHold(),
                    tripDeps = deps,
                )
            }
        }

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(preview8HrText))
        compose.onNodeWithText(preview8HrText).performClick()
        compose.waitUntil(timeoutMillis = 5_000L) {
            compose.onAllNodesWithText(startDownloadingText).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(startDownloadingText))
        compose.onNodeWithText(startDownloadingText).performClick()

        // Wait for two downloads to be enqueued and actively receiving, and verify progress reflects 2 of 4.
        compose.waitUntil(timeoutMillis = 5_000L) {
            DownloadsRepository.uiState.value.items.count { it.tripFill && it.status == DownloadStatus.Downloading } == 2 &&
                compose.onAllNodesWithText("Queued 2 of 4:", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Stop queueing").assertIsDisplayed().performClick()

        compose.waitUntil(timeoutMillis = 5_000L) {
            compose.onAllNodesWithText("Queued 2 of 4 planned episodes.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Queueing stopped early.").assertIsDisplayed()
        assertEquals(2, DownloadsRepository.uiState.value.items.count { it.tripFill })
    }

    @Test
    fun `running progress label matches enqueued count during initial preparation and failover backup`() {
        val showCandidate = TripShowCandidate(
            parentMetaType = "series",
            parentMetaId = "show:trip-prep",
            title = "Andor",
            fromContinueWatching = true,
        )
        val primaryStream = StreamItem(
            name = "Andor.1080p.primary",
            title = "Andor.1080p.primary",
            url = "https://ex/primary.mkv",
            addonName = "Addon",
            addonId = "addon:test",
            behaviorHints = StreamBehaviorHints(videoSize = 900_000_000L, filename = "primary.mkv"),
            debridCacheStatus = StreamDebridCacheStatus(
                providerId = "rd",
                providerName = "RD",
                state = StreamDebridCacheState.CACHED,
                cachedSize = 900_000_000L,
            ),
        )
        val backupStream = primaryStream.copy(
            name = "Andor.1080p.backup",
            title = "Andor.1080p.backup",
            url = "https://ex/backup.mkv",
        )
        val plannedEpisodes = (1..4).map { number ->
            val primary = primaryStream.copy(url = "https://ex/ep$number-primary.mkv")
            val backup = backupStream.copy(url = "https://ex/ep$number-backup.mkv")
            TripPlannedEpisode(
                show = showCandidate,
                episode = SeasonBatchEpisode("show:trip-prep:1:$number", 1, number, "Episode $number", null, runtimeMinutes = 45),
                effectiveRuntimeMinutes = 45,
                runtimeEstimated = false,
                rankedStreams = listOf(primary, backup),
                selectedStream = primary,
                isCached = true,
                estimatedBytes = 900_000_000L,
                sizeEstimated = false,
            )
        }

        val initialPrepareGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val backupPrepareEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val backupPrepareGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val downloadsFlow = MutableStateFlow(DownloadsUiState())
        try {
            val runner = TripFillRunner(
                scope = runnerScope,
                downloads = downloadsFlow,
                prepareStream = { stream, _ ->
                    if (stream.url.orEmpty().contains("-primary")) {
                        initialPrepareGate.await()
                    } else {
                        backupPrepareEntered.complete(Unit)
                        backupPrepareGate.await()
                    }
                    stream
                },
                enqueue = { show, episode, chosen ->
                    val suffix = if (chosen.url.orEmpty().contains("-primary")) "primary" else "backup"
                    val item = DownloadItem(
                        id = "dl-${episode.episodeNumber}-$suffix",
                        contentType = "series",
                        parentMetaId = show.parentMetaId,
                        parentMetaType = show.parentMetaType,
                        videoId = episode.videoId,
                        title = show.title,
                        seasonNumber = episode.seasonNumber,
                        episodeNumber = episode.episodeNumber,
                        streamTitle = chosen.name.orEmpty(),
                        providerName = "Addon",
                        sourceUrl = chosen.url.orEmpty(),
                        fileName = "ep${episode.episodeNumber}-$suffix.mkv",
                        status = DownloadStatus.Downloading,
                        downloadedBytes = 0L,
                        totalBytes = 900_000_000L,
                        createdAtEpochMs = 1_000L,
                        updatedAtEpochMs = 2_000L,
                        tripFill = true,
                    )
                    downloadsFlow.update { state -> state.copy(items = state.items + item) }
                    DownloadEnqueueResult.Started
                },
                maxConcurrent = 2,
            )

            val deps = TripReadyDeps(
                collectShows = { listOf(showCandidate) },
                buildPlan = { hours, _ ->
                    TripFillPlan(
                        requestedHours = hours,
                        plannedEpisodes = plannedEpisodes,
                        totalPlannedMinutes = 180,
                        totalEstimatedBytes = 3_600_000_000L,
                        qualityDefaults = SeasonDownloadDefaults(),
                        storageLimitBytes = null,
                        remainingStorageLimitBytes = null,
                        freeDiskBytes = 40_000_000_000L,
                        connectionHoldReason = null,
                        stoppedReason = TripPlanStopReason.TargetReached,
                    )
                },
                runner = runner,
                loadDiskInfo = { _, _ -> emptyMap() },
                durationMsOf = { _, _ -> null },
                bytesPerSecondOf = { _, _ -> null },
                fetchMissingCompanions = null,
            )

            val preview8HrText = runBlocking { getString(Res.string.downloads_trip_fill_plan_button, DefaultTripFillHours) }
            val startDownloadingText = runBlocking { getString(Res.string.downloads_trip_fill_start) }

            compose.setContent {
                NuvioTheme {
                    TripReadyPanel(
                        onBack = {},
                        onOpenDownload = {},
                        onOpenShow = { _, _ -> },
                        downloadsStateFlow = downloadsFlow,
                        folders = NoDownloadFolders,
                        rechecks = null,
                        connectionHold = ConnectionHold(),
                        tripDeps = deps,
                    )
                }
            }

            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(preview8HrText))
            compose.onNodeWithText(preview8HrText).performClick()
            compose.waitUntil(timeoutMillis = 5_000L) {
                compose.onAllNodesWithText(startDownloadingText).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(startDownloadingText))
            compose.onNodeWithText(startDownloadingText).performClick()

            // While both workers are resolving sources before any enqueue, label stays at 0 of 4.
            compose.waitUntil(timeoutMillis = 5_000L) {
                compose.onAllNodesWithText("Queued 0 of 4: Andor", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(0, compose.onAllNodesWithText("Queued 1 of 4:", substring = true).fetchSemanticsNodes().size)
            assertEquals(0, compose.onAllNodesWithText("Queued 2 of 4:", substring = true).fetchSemanticsNodes().size)

            // Release initial preparation -> two downloads become active and label shows 2 of 4.
            initialPrepareGate.complete(Unit)
            compose.waitUntil(timeoutMillis = 5_000L) {
                downloadsFlow.value.items.count { it.status == DownloadStatus.Downloading } == 2 &&
                    compose.onAllNodesWithText("Queued 2 of 4:", substring = true).fetchSemanticsNodes().isNotEmpty()
            }

            // Fail one active episode with 0 bytes so it enters backup preparation while the other stays active.
            val failedTarget = downloadsFlow.value.items.last()
            downloadsFlow.update { state ->
                state.copy(
                    items = state.items.map {
                        if (it.id == failedTarget.id) it.copy(status = DownloadStatus.Failed, downloadedBytes = 0L) else it
                    },
                )
            }
            runBlocking { kotlinx.coroutines.withTimeout(5_000L) { backupPrepareEntered.await() } }

            // With one active download and one preparing its backup, label shows 1 of 4 (not 2 of 4).
            compose.waitUntil(timeoutMillis = 5_000L) {
                compose.onAllNodesWithText("Queued 1 of 4:", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(0, compose.onAllNodesWithText("Queued 2 of 4:", substring = true).fetchSemanticsNodes().size)

            // Stopping in this preparation/failover window reports 1 of 4, matching the running label.
            compose.onNodeWithText("Stop queueing").assertIsDisplayed().performClick()
            compose.waitUntil(timeoutMillis = 5_000L) {
                compose.onAllNodesWithText("Queued 1 of 4 planned episodes.").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Queueing stopped early.").assertIsDisplayed()
        } finally {
            initialPrepareGate.cancel()
            backupPrepareGate.cancel()
        }
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
