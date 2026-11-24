package com.nuvio.app.features.downloads

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.network.TrustedLocalServerTest
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.details.MetaDetailsRepository
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule

@OptIn(ExperimentalTestApi::class)
class TripConnectivityTest : TrustedLocalServerTest() {
    @get:Rule val compose = createComposeRule()

    private val previousBackend = DownloadsRepository.backend
    private val previousAddons = AddonRepository.uiState.value.addons
    private lateinit var server: HttpServer
    private lateinit var scope: CoroutineScope
    private val metaRequests = AtomicInteger()
    private val streamRequests = AtomicInteger()
    @Volatile private var failMeta = false
    @Volatile private var failStreams = false
    private val show = TripShowCandidate("series", "trip-connectivity-show", "Trip show")
    private val now = 1_000_000L
    private val receiving = testDownload(1, DownloadStatus.Downloading).copy(
        downloadedBytes = 100_000_000L,
        totalBytes = 800_000_000L,
        updatedAtEpochMs = now,
    )

    @BeforeTest
    fun setUp() {
        NetworkStatusRepository.resetForTest()
        NuvioToastController.dismiss()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = MemoryBackend()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/meta/series/${show.parentMetaId}.json") { exchange ->
            metaRequests.incrementAndGet()
            val body = """{"meta":{"id":"${show.parentMetaId}","type":"series","name":"Trip show","runtime":"60 min","videos":[{"id":"trip-connectivity-episode","season":1,"episode":1,"title":"Episode 1","released":"2020-01-01T00:00:00.000Z"}]}}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(if (failMeta) 503 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/stream/") { exchange ->
            streamRequests.incrementAndGet()
            val body = """{"streams":[{"name":"1080p","url":"https://media.example/episode.mkv","behaviorHints":{"videoSize":800000000}}]}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(if (failStreams) 503 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/ready") { exchange ->
            val bytes = "ready".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        // Initialize the HTTP client before measuring a lookup with the planner's timeout.
        runBlocking {
            withTimeout(30_000) { assertEquals("ready", httpGetText("http://127.0.0.1:${server.address.port}/ready")) }
        }
        val url = "http://127.0.0.1:${server.address.port}/manifest.json"
        AddonRepository.setAddonsForTest(listOf(ManagedAddon(
            manifestUrl = url,
            enabled = true,
            manifest = AddonManifest(
                id = "trip-connectivity", version = "1.0.0", name = "Trip sources", description = "Test sources",
                resources = listOf(AddonResource(name = "meta", types = listOf("series")), AddonResource(name = "stream", types = listOf("series"))),
                types = listOf("series"), transportUrl = url,
            ),
        )))
        MetaDetailsRepository.removeCachedMetaForTest("series", show.parentMetaId)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        NuvioToastController.dismiss()
        server.stop(0)
        MetaDetailsRepository.removeCachedMetaForTest("series", show.parentMetaId)
        AddonRepository.setAddonsForTest(previousAddons)
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = previousBackend
        NetworkStatusRepository.resetForTest()
    }

    private suspend fun plan(condition: NetworkCondition): TripFillPlan {
        NetworkStatusRepository.setConditionForTest(condition)
        return buildAppTripFillPlan(1, shows = listOf(show), todayIsoDate = "2026-11-23")
    }

    private suspend fun assertQueues(condition: NetworkCondition) {
        val plan = plan(condition)
        assertEquals(TripPlanStopReason.TargetReached, plan.stoppedReason)
        assertEquals(1, plan.plannedEpisodes.size)
        assertEquals(1, metaRequests.get())
        assertEquals(1, streamRequests.get())
        val downloads = MutableStateFlow(DownloadsUiState())
        val runner = TripFillRunner(scope, downloads,
            currentProfileId = { plan.profileId },
            prepareStream = { stream, _ -> stream },
            enqueue = { _, _, _ ->
                downloads.value = DownloadsUiState(listOf(receiving.copy(
                    status = DownloadStatus.Completed, parentMetaId = show.parentMetaId,
                    seasonNumber = 1, episodeNumber = 1,
                )))
                DownloadEnqueueResult.Started
            },
        )
        assertTrue(runner.start(plan))
        val done = withTimeout(30_000) { runner.state.first { it.doneSummary != null } }.doneSummary!!
        assertEquals(1, done.queuedCount)
        assertEquals(0, done.failedCount)
    }

    @Test
    fun account_outage_allows_real_metadata_and_source_lookups_and_queueing() = runBlocking {
        assertQueues(NetworkCondition.ServersUnreachable)
        assertTrue(NetworkStatusRepository.uiState.value.isOfflineLike)
    }

    @Test
    fun online_still_looks_up_sources_and_queues() = runBlocking {
        assertQueues(NetworkCondition.Online)
    }

    @Test
    fun no_internet_blocks_lookups_and_queues_nothing() = runBlocking {
        val plan = plan(NetworkCondition.NoInternet)
        assertEquals(TripPlanStopReason.Offline, plan.stoppedReason)
        assertTrue(plan.plannedEpisodes.isEmpty())
        assertEquals(0, metaRequests.get())
        assertEquals(0, streamRequests.get())
        val runner = TripFillRunner(scope, MutableStateFlow(DownloadsUiState()),
            prepareStream = { _, _ -> error("Must not prepare a source") },
            enqueue = { _, _, _ -> error("Must not queue while offline") },
        )
        assertFalse(runner.start(plan))
    }

    @Test
    fun metadata_host_failure_is_reported_as_an_episode_lookup_failure() = runBlocking {
        failMeta = true
        val plan = plan(NetworkCondition.ServersUnreachable)
        assertFalse(plan.stoppedReason == TripPlanStopReason.Offline)
        assertTrue(plan.plannedEpisodes.isEmpty())
        assertEquals(TripSkippedReason.CouldNotLoadEpisodes, plan.skippedShows.single().reason)
        assertEquals(1, metaRequests.get())
        assertEquals(0, streamRequests.get())
    }

    @Test
    fun source_host_failure_is_reported_without_claiming_the_device_is_offline() = runBlocking {
        failStreams = true
        val plan = plan(NetworkCondition.ServersUnreachable)
        assertFalse(plan.stoppedReason == TripPlanStopReason.Offline)
        assertTrue(plan.plannedEpisodes.isEmpty())
        assertEquals(TripSkippedReason.NoMatchingSources, plan.skippedShows.single().reason)
        assertEquals(1, streamRequests.get())
    }

    @Test
    fun source_failure_preview_names_the_source_problem() {
        failStreams = true
        NetworkStatusRepository.setConditionForTest(NetworkCondition.ServersUnreachable)
        compose.setContent {
            NuvioTheme {
                TripReadyPanel(
                    onBack = {}, onOpenDownload = {}, onOpenShow = { _, _ -> },
                    downloadsStateFlow = MutableStateFlow(DownloadsUiState()),
                    folders = NoDownloadFolders, rechecks = null, connectionHold = ConnectionHold(),
                    tripDeps = rowDeps().copy(
                        collectShows = { listOf(show) },
                        buildPlan = { hours, shows -> buildAppTripFillPlan(hours, shows = shows, todayIsoDate = "2026-11-23") },
                    ),
                )
            }
        }
        compose.onNodeWithText("Preview 8-hour plan").performClick()
        val note = "Trip show skipped: couldn't find a usable source. Check your add-ons and preferences, then try again."
        compose.waitUntil(30_000) { compose.onAllNodesWithText(note).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(note))
        compose.onNodeWithText(note).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("You're offline.", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun saved_trip_waits_for_internet_then_recovers_during_an_account_outage() = runBlocking {
        val plan = plan(NetworkCondition.Online)
        val downloads = MutableStateFlow(DownloadsUiState())
        val runner = TripFillRunner(scope, downloads,
            currentProfileId = { plan.profileId },
            prepareStream = { stream, _ -> stream },
            enqueue = { _, _, _ ->
                downloads.value = DownloadsUiState(listOf(receiving.copy(
                    status = DownloadStatus.Completed, parentMetaId = show.parentMetaId,
                    seasonNumber = 1, episodeNumber = 1,
                )))
                DownloadEnqueueResult.Started
            },
        )
        val loaded = CompletableDeferred<Unit>()
        NetworkStatusRepository.setConditionForTest(NetworkCondition.NoInternet)
        TripFillHost.onProfileLoaded(
            profileId = plan.profileId!!,
            activeProfile = { plan.profileId },
            loadPendingPlan = { loaded.complete(Unit); plan },
            activeRunner = runner,
        )
        withTimeout(30_000) { loaded.await() }
        assertFalse(runner.state.value.isRunning)
        assertTrue(downloads.value.items.isEmpty())
        NetworkStatusRepository.setConditionForTest(NetworkCondition.ServersUnreachable)
        val done = withTimeout(30_000) { runner.state.first { it.doneSummary != null } }.doneSummary!!
        assertEquals(1, done.queuedCount)
    }

    @Test
    fun downloads_rows_use_internet_reachability_and_trip_rows_keep_receiving() {
        val backend = DownloadsRepository.backend as MemoryBackend
        backend.payload = DownloadsCodec.encode(listOf(receiving), null)
        NetworkStatusRepository.setConditionForTest(NetworkCondition.ServersUnreachable)
        compose.setContent {
            NuvioTheme {
                DownloadsScreenContent(
                    onBack = {}, onOpenDownload = {}, showAutoDownloads = false,
                    folders = NoDownloadFolders, connectionHold = ConnectionHold(),
                    nowEpochMs = { now }, tripDeps = rowDeps(), runtimeMinutes = { null },
                )
            }
        }
        compose.onNodeWithText("Downloading •", substring = true).assertIsDisplayed()
        compose.runOnIdle { NetworkStatusRepository.setConditionForTest(NetworkCondition.NoInternet) }
        compose.onNodeWithText("Waiting for the internet", substring = true).assertIsDisplayed()
        compose.runOnIdle { NetworkStatusRepository.setConditionForTest(NetworkCondition.Online) }
        compose.onNodeWithText("Downloading •", substring = true).assertIsDisplayed()
        compose.runOnIdle { NetworkStatusRepository.setConditionForTest(NetworkCondition.ServersUnreachable) }
        compose.onNodeWithText("Trip ready").performClick()
        assertTripTransferIsReceiving()
    }

    @Test
    fun standalone_trip_rows_keep_receiving_during_an_account_outage() {
        NetworkStatusRepository.setConditionForTest(NetworkCondition.ServersUnreachable)
        compose.setContent {
            NuvioTheme {
                TripReadyPanel(
                    onBack = {}, onOpenDownload = {}, onOpenShow = { _, _ -> },
                    downloadsStateFlow = MutableStateFlow(DownloadsUiState(listOf(receiving))),
                    folders = NoDownloadFolders, rechecks = null, connectionHold = ConnectionHold(),
                    nowEpochMs = { now }, tripDeps = rowDeps(),
                )
            }
        }
        assertTripTransferIsReceiving()
    }

    private fun assertTripTransferIsReceiving() {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("/s • About", substring = true))
        compose.onNodeWithText("/s • About", substring = true).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Waiting for the internet", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun standalone_companions_use_the_backfill_connection_gate() {
        assertCompanionConnectionGate(embedded = false)
    }

    @Test
    fun embedded_companions_use_the_backfill_connection_gate() {
        assertCompanionConnectionGate(embedded = true)
    }

    private fun assertCompanionConnectionGate(embedded: Boolean) {
        val probe = showCompanionFetch(embedded)
        for (condition in listOf(NetworkCondition.ServersUnreachable, NetworkCondition.NoInternet,
            NetworkCondition.Unknown, NetworkCondition.Checking, NetworkCondition.Online)) {
            compose.runOnIdle {
                NuvioToastController.dismiss()
                NetworkStatusRepository.setConditionForTest(condition)
            }
            clickCompanionFetch()
            val expected = if (condition == NetworkCondition.Online) 1 else 0
            assertEquals(expected, probe.fetches.get(), condition.name)
            assertEquals(expected, probe.requests.get(), condition.name)
            if (condition == NetworkCondition.Online) {
                assertEquals(1, probe.result.value?.looked)
                assertEquals("Checked for missing subtitles and skip times.", NuvioToastController.currentToast.value?.message)
            } else {
                assertFalse(NuvioToastController.currentToast.value?.message == "Checked for missing subtitles and skip times.")
            }
        }
    }

    @Test
    fun companions_stopped_before_backfill_do_not_claim_a_finished_check() {
        val probe = showCompanionFetch(embedded = false, beforeBackfill = {
            NetworkStatusRepository.setConditionForTest(NetworkCondition.ServersUnreachable)
        })
        clickCompanionFetch()
        assertEquals(1, probe.fetches.get())
        assertEquals(0, probe.requests.get())
        assertEquals(0, probe.result.value?.looked)
        assertEquals(true, probe.result.value?.stoppedEarly)
        assertFalse(NuvioToastController.currentToast.value?.message == "Checked for missing subtitles and skip times.")
    }

    @Test
    fun companions_failed_http_lookup_does_not_claim_a_finished_check() {
        val probe = showCompanionFetch(embedded = true, failRequest = true)
        clickCompanionFetch()
        assertEquals(1, probe.fetches.get())
        assertEquals(1, probe.requests.get())
        assertEquals(1, probe.result.value?.failed)
        assertFalse(NuvioToastController.currentToast.value?.message == "Checked for missing subtitles and skip times.")
    }

    @Test
    fun companions_with_no_due_work_do_not_claim_a_finished_check() {
        val probe = showCompanionFetch(embedded = false, due = false)
        clickCompanionFetch()
        assertEquals(1, probe.fetches.get())
        assertEquals(0, probe.requests.get())
        assertEquals(0, probe.result.value?.looked)
        assertFalse(NuvioToastController.currentToast.value?.message == "Checked for missing subtitles and skip times.")
    }

    private data class CompanionFetchProbe(
        val fetches: AtomicInteger = AtomicInteger(),
        val requests: AtomicInteger = AtomicInteger(),
        val result: MutableStateFlow<CompanionBackfillSummary?> = MutableStateFlow(null),
    )

    private fun showCompanionFetch(
        embedded: Boolean,
        failRequest: Boolean = false,
        due: Boolean = true,
        beforeBackfill: () -> Unit = {},
    ): CompanionFetchProbe {
        val probe = CompanionFetchProbe()
        server.createContext("/companions") { exchange ->
            probe.requests.incrementAndGet()
            val bytes = "nothing found".toByteArray()
            exchange.sendResponseHeaders(if (failRequest) 503 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        val companion = object : DownloadCompanion {
            override val name = "test companions"
            override fun isDue(item: DownloadItem, localVideoUri: String) = due
            override suspend fun run(item: DownloadItem, localVideoUri: String): CompanionOutcome {
                httpGetText("http://127.0.0.1:${server.address.port}/companions")
                return CompanionOutcome.NothingFound
            }
        }
        val backfill = DownloadCompanions(listOf(companion), scope = scope)
        val completed = testDownload(2, DownloadStatus.Completed, localFileUri = "file:///fixture/episode.mkv")
        val items = listOf(completed)
        val deps = rowDeps().copy(
            loadDiskInfo = { _, _ -> mapOf(completed.id to TripItemDiskInfo()) },
            fetchMissingCompanions = { downloads ->
                probe.fetches.incrementAndGet()
                beforeBackfill()
                backfill.backfill(downloads) { it.localFileUri }.also { probe.result.value = it }
            },
        )
        (DownloadsRepository.backend as MemoryBackend).payload = DownloadsCodec.encode(items, null)
        NetworkStatusRepository.setConditionForTest(NetworkCondition.Online)
        compose.setContent {
            NuvioTheme {
                if (embedded) {
                    DownloadsScreenContent(
                        onBack = {}, onOpenDownload = {}, showAutoDownloads = false,
                        folders = NoDownloadFolders, connectionHold = ConnectionHold(),
                        nowEpochMs = { now }, tripDeps = deps, runtimeMinutes = { null },
                    )
                } else {
                    TripReadyPanel(
                        onBack = {}, onOpenDownload = {}, onOpenShow = { _, _ -> },
                        downloadsStateFlow = MutableStateFlow(DownloadsUiState(items)),
                        folders = NoDownloadFolders, rechecks = null, connectionHold = ConnectionHold(),
                        nowEpochMs = { now }, tripDeps = deps,
                    )
                }
            }
        }
        if (embedded) compose.onNodeWithText("Trip ready").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Needs attention"))
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Fetch missing subtitles and skip times").fetchSemanticsNodes().isNotEmpty()
        }
        return probe
    }

    private fun clickCompanionFetch() {
        val label = "Fetch missing subtitles and skip times"
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performClick()
        compose.waitUntil(30_000) { NuvioToastController.currentToast.value != null }
    }

    private fun rowDeps() = TripReadyDeps(
        loadDiskInfo = { _, _ -> emptyMap() }, durationMsOf = { _, _ -> null },
        bytesPerSecondOf = { _, _ -> 1_000_000L }, fetchMissingCompanions = null,
    )

    private class MemoryBackend : DownloadsBackend {
        var payload: String? = null
        override fun loadPayload() = payload
        override fun savePayload(payload: String) { this.payload = payload }
        override fun restoreItem(item: DownloadItem) = item
        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (Long, Long?) -> Unit,
            onSuccess: (String, Long?) -> Unit,
            onFailure: (String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle = object : DownloadsTaskHandle {
            override fun cancel() = Unit
        }
        override fun removeFile(localFileUri: String?) = false
        override fun removePartialFile(destinationFileName: String) = false
        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String) = localFileUri
    }
}
