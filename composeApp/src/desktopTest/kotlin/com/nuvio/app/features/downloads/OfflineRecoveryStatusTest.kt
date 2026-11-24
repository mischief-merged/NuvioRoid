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
import com.nuvio.app.core.network.TrustedHomeServers
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.streams.StreamItem
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Rule
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class OfflineRecoveryStatusTest {
    @get:Rule
    val compose = createComposeRule()

    private val previousBackend = DownloadsRepository.backend
    private val previousConnectionHold = DownloadsRepository.connectionHold
    private val previousFreeBytes = DownloadsPlatformDownloader.freeBytesProvider
    private val previousProgressInterval = DownloadsPlatformDownloader.progressIntervalMs
    private lateinit var storageRoot: Path
    private lateinit var server: HttpServer
    private lateinit var scope: CoroutineScope
    private val tempFiles = mutableListOf<File>()

    private val allowPublicProbe = AtomicBoolean(false)
    private val allowAccountServer = AtomicBoolean(false)
    private val allowVideoStream = AtomicBoolean(false)
    private val publicProbeHits = AtomicInteger(0)
    private val videoPayload = ByteArray(512 * 1024) { (it * 17 + 3).toByte() }

    @BeforeTest
    fun setUp() {
        storageRoot = Files.createTempDirectory("nuvio-offline-recovery-test-")
        DesktopStorage.setRootDirForTest(storageRoot.resolve("data"), storageRoot.resolve("cache"))
        listOf("127.0.0.1", "localhost", "http://[::1]").forEach(TrustedHomeServers::add)
        NetworkStatusRepository.resetForTest()
        DownloadsRepository.clearLocalState()
        DownloadsRepository.connectionHold = ConnectionHold()
        DownloadsPlatformDownloader.freeBytesProvider = { null }
        DownloadsPlatformDownloader.progressIntervalMs = 25L
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/generate_204") { exchange ->
            publicProbeHits.incrementAndGet()
            if (!allowPublicProbe.get()) {
                exchange.close()
                return@createContext
            }
            exchange.sendResponseHeaders(204, -1L)
            exchange.close()
        }
        server.createContext("/rest/v1/") { exchange ->
            if (!allowAccountServer.get()) {
                exchange.close()
                return@createContext
            }
            val body = "{}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/ping") { exchange ->
            exchange.responseHeaders.add("Connection", "close")
            val body = """{"ok":true}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/episode.mkv") { exchange ->
            if (!allowVideoStream.get()) {
                exchange.close()
                return@createContext
            }
            exchange.responseHeaders.add("Content-Type", "video/x-matroska")
            exchange.sendResponseHeaders(200, (videoPayload.size * 4).toLong())
            exchange.responseBody.use { out ->
                var offset = 0
                while (offset < videoPayload.size) {
                    val step = minOf(32 * 1024, videoPayload.size - offset)
                    out.write(videoPayload, offset, step)
                    out.flush()
                    offset += step
                    Thread.sleep(20L)
                }
            }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        server.stop(0)
        DownloadsPlatformDownloader.freeBytesProvider = previousFreeBytes
        DownloadsPlatformDownloader.progressIntervalMs = previousProgressInterval
        DownloadsRepository.clearLocalState()
        DownloadsRepository.connectionHold = previousConnectionHold
        DownloadsRepository.backend = previousBackend
        NetworkStatusRepository.resetForTest()
        DesktopStorage.setRootDirForTest(null, null)
        if (::storageRoot.isInitialized) {
            storageRoot.toFile().deleteRecursively()
        }
        tempFiles.forEach { it.delete() }
        tempFiles.clear()
    }

    private fun configureLocalNetworkProbes(
        retryBaseMs: Long = 150L,
        retryMaxMs: Long = 600L,
        minIntervalMs: Long = 150L,
    ) {
        val base = "http://127.0.0.1:${server.address.port}"
        NetworkStatusRepository.publicProbeUrls = listOf("$base/generate_204")
        NetworkStatusRepository.serverProbeUrls = { listOf("$base/rest/v1/" to emptyMap()) }
        NetworkStatusRepository.offlineRetryBaseDelayMs = retryBaseMs
        NetworkStatusRepository.offlineRetryMaxDelayMs = retryMaxMs
        NetworkStatusRepository.minRecoveryProbeIntervalMs = minIntervalMs
    }

    private suspend fun awaitCondition(timeoutMs: Long = 2_000L, predicate: (NetworkCondition) -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate(NetworkStatusRepository.uiState.value.condition)) return true
            delay(20L)
        }
        return predicate(NetworkStatusRepository.uiState.value.condition)
    }

    @Test
    fun network_status_recovers_from_no_internet_when_connectivity_returns_without_os_event() = runBlocking {
        configureLocalNetworkProbes(retryBaseMs = 150L, retryMaxMs = 450L, minIntervalMs = 100L)
        allowPublicProbe.set(false)
        allowAccountServer.set(false)

        NetworkStatusRepository.ensureStarted()
        assertTrue(
            awaitCondition { it == NetworkCondition.NoInternet },
            "Expected initial probe failure to enter NoInternet, got ${NetworkStatusRepository.uiState.value.condition}",
        )

        // Connectivity returns at the packet level while the account server remains down and no OS/window event fires.
        allowPublicProbe.set(true)
        assertTrue(
            awaitCondition(timeoutMs = 1_500L) { NetworkStatusRepository.uiState.value.hasInternet && it == NetworkCondition.ServersUnreachable },
            "Expected NetworkStatusRepository to leave NoInternet without an OS event, got ${NetworkStatusRepository.uiState.value.condition}",
        )

        // Also verify full Online recovery when both public internet and account server recover.
        NetworkStatusRepository.resetForTest()
        configureLocalNetworkProbes(retryBaseMs = 150L, retryMaxMs = 450L, minIntervalMs = 100L)
        allowPublicProbe.set(false)
        allowAccountServer.set(false)
        NetworkStatusRepository.ensureStarted()
        assertTrue(awaitCondition { it == NetworkCondition.NoInternet })

        allowPublicProbe.set(true)
        allowAccountServer.set(true)
        assertTrue(
            awaitCondition(timeoutMs = 1_500L) { it == NetworkCondition.Online },
            "Expected NetworkStatusRepository to recover to Online without an OS event, got ${NetworkStatusRepository.uiState.value.condition}",
        )
    }

    @Test
    fun receiving_download_row_is_not_labelled_waiting_while_network_flag_is_offline() {
        val backend = ControllableBackend()
        DownloadsRepository.backend = backend
        DownloadsRepository.ensureLoaded()
        val clockMs = AtomicLong(DownloadsClock.nowEpochMs())
        NetworkStatusRepository.setConditionForTest(NetworkCondition.NoInternet)

        compose.setContent {
            NuvioTheme {
                DownloadsScreenContent(
                    onBack = {},
                    onOpenDownload = {},
                    showAutoDownloads = false,
                    folders = NoDownloadFolders,
                    connectionHold = DownloadsRepository.connectionHold,
                    nowEpochMs = clockMs::get,
                    tripDeps = TripReadyDeps(
                        loadDiskInfo = { _, _ -> emptyMap() },
                        durationMsOf = { _, _ -> null },
                        bytesPerSecondOf = { item, at -> DownloadsRepository.transferBytesPerSecond(item.id, at) },
                        fetchMissingCompanions = null,
                    ),
                    runtimeMinutes = { null },
                )
            }
        }

        DownloadsRepository.enqueueFromStream(
            contentType = "series",
            videoId = "tt-pantheon:1:2",
            parentMetaId = "tt-pantheon",
            parentMetaType = "series",
            title = "Pantheon",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodeNumber = 2,
            episodeTitle = "Cycles",
            episodeThumbnail = null,
            stream = StreamItem(url = "https://media.example/pantheon-s01e02.mkv", addonName = "Test", addonId = "test"),
            autoDownloadRuleKey = "series|tt-pantheon",
        )
        compose.waitForIdle()
        compose.onNodeWithText("Waiting for the internet", substring = true).assertIsDisplayed()

        // Bytes arrive while NetworkStatusRepository still reports NoInternet.
        backend.onProgress?.invoke(125_000_000L, 1_400_000_000L)
        Thread.sleep(20L)
        backend.onProgress?.invoke(410_000_000L, 1_400_000_000L)
        clockMs.set(DownloadsClock.nowEpochMs())
        compose.mainClock.advanceTimeBy(5_000L)
        compose.waitForIdle()

        assertEquals(NetworkCondition.NoInternet, NetworkStatusRepository.uiState.value.condition)
        compose.onNodeWithText("Downloading •", substring = true).assertIsDisplayed()
        assertTrue(
            compose.onAllNodesWithText("Waiting for the internet", substring = true).fetchSemanticsNodes().isEmpty(),
            "A row actively receiving bytes must not say Waiting for the internet",
        )

        compose.onNodeWithText("Trip ready").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Pantheon S01E02"))
        assertTrue(
            compose.onAllNodesWithText("Waiting for the internet", substring = true).fetchSemanticsNodes().isEmpty(),
            "Trip ready transfer row receiving bytes must not say Waiting for the internet",
        )
    }

    @Test
    fun real_transfer_recovers_row_status_and_network_state_after_packet_block_clears() {
        configureLocalNetworkProbes(retryBaseMs = 10_000L, retryMaxMs = 30_000L, minIntervalMs = 100L)
        allowPublicProbe.set(false)
        allowAccountServer.set(false)
        allowVideoStream.set(false)

        val tempPart = File.createTempFile("nuvio-i144-", ".part").also {
            tempFiles += it
            it.delete()
        }
        val firstAttemptFailed = CountDownLatch(1)
        val allowRetry = CountDownLatch(1)
        val backend = object : DownloadsBackend {
            var payload: String? = null
            override fun loadPayload(): String? = payload
            override fun savePayload(payload: String) {
                this.payload = payload
            }
            override fun restoreItem(item: DownloadItem): DownloadItem = item
            override fun start(
                request: DownloadPlatformRequest,
                onProgress: (Long, Long?) -> Unit,
                onSuccess: (String, Long?) -> Unit,
                onFailure: (String) -> Unit,
                onPaused: () -> Unit,
            ): DownloadsTaskHandle {
                val job = scope.launch {
                    try {
                        DownloadsPlatformDownloader.transferToTempFile(
                            request = request,
                            tempFile = tempPart,
                            onProgress = onProgress,
                        )
                    } catch (_: Throwable) {
                        firstAttemptFailed.countDown()
                    }
                    if (!allowRetry.await(5, TimeUnit.SECONDS)) return@launch
                    runCatching {
                        DownloadsPlatformDownloader.transferToTempFile(
                            request = request,
                            tempFile = tempPart,
                            onProgress = onProgress,
                        )
                    }
                }
                return object : DownloadsTaskHandle {
                    override fun cancel() {
                        job.cancel()
                    }
                }
            }
            override fun removeFile(localFileUri: String?): Boolean = false
            override fun removePartialFile(destinationFileName: String): Boolean = false
            override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri
        }
        DownloadsRepository.backend = backend
        DownloadsRepository.ensureLoaded()
        NetworkStatusRepository.ensureStarted()

        runBlocking {
            assertTrue(awaitCondition { it == NetworkCondition.NoInternet })
        }

        compose.setContent {
            NuvioTheme {
                DownloadsScreenContent(
                    onBack = {},
                    onOpenDownload = {},
                    showAutoDownloads = false,
                    folders = NoDownloadFolders,
                    connectionHold = DownloadsRepository.connectionHold,
                    tripDeps = TripReadyDeps(
                        loadDiskInfo = { _, _ -> emptyMap() },
                        durationMsOf = { _, _ -> null },
                        bytesPerSecondOf = { _, _ -> null },
                        fetchMissingCompanions = null,
                    ),
                    runtimeMinutes = { null },
                )
            }
        }

        val streamUrl = "http://127.0.0.1:${server.address.port}/episode.mkv"
        DownloadsRepository.enqueueFromStream(
            contentType = "series",
            videoId = "tt-real:1:2",
            parentMetaId = "tt-real",
            parentMetaType = "series",
            title = "Pantheon",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodeNumber = 2,
            episodeTitle = "Cycles",
            episodeThumbnail = null,
            stream = StreamItem(url = streamUrl, addonName = "Local", addonId = "local"),
        )
        assertTrue(firstAttemptFailed.await(3, TimeUnit.SECONDS))
        compose.waitForIdle()
        compose.onNodeWithText("Waiting for the internet", substring = true).assertIsDisplayed()

        // Packet block clears without any OS/foreground event; real transfer resumes and probes succeed.
        // Note: retryBaseMs is 10s here, so recovery within 2s proves traffic-triggered recovery works too.
        allowPublicProbe.set(true)
        allowVideoStream.set(true)
        allowRetry.countDown()

        compose.waitUntil(timeoutMillis = 3_000L) {
            tempPart.exists() && tempPart.length() > 0L &&
                compose.onAllNodesWithText("Downloading •", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Downloading •", substring = true).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Waiting for the internet", substring = true).fetchSemanticsNodes().isEmpty())
        runBlocking {
            assertTrue(
                awaitCondition(timeoutMs = 2_000L) { NetworkStatusRepository.uiState.value.hasInternet },
                "Expected real transfer traffic to trigger NetworkStatusRepository recovery from NoInternet",
            )
        }
    }

    @Test
    fun real_no_internet_with_no_received_bytes_still_shows_waiting() {
        val now = 1_000_000L
        val resumedPartial = testDownload(2, DownloadStatus.Downloading).copy(
            downloadedBytes = 125_000_000L,
            totalBytes = 1_400_000_000L,
            updatedAtEpochMs = now,
        )
        assertEquals(
            DownloadPhase.WaitingForNetwork,
            downloadRowStatus(resumedPartial, nowEpochMs = now, offline = true).phase,
        )

        val backend = ControllableBackend()
        backend.payload = DownloadsCodec.encode(
            listOf(resumedPartial.copy(status = DownloadStatus.Paused)),
            null,
        )
        DownloadsRepository.backend = backend
        DownloadsRepository.ensureLoaded()
        NetworkStatusRepository.setConditionForTest(NetworkCondition.NoInternet)

        compose.setContent {
            NuvioTheme {
                DownloadsScreenContent(
                    onBack = {},
                    onOpenDownload = {},
                    showAutoDownloads = false,
                    folders = NoDownloadFolders,
                    connectionHold = DownloadsRepository.connectionHold,
                    tripDeps = TripReadyDeps(
                        loadDiskInfo = { _, _ -> emptyMap() },
                        durationMsOf = { _, _ -> null },
                        bytesPerSecondOf = { _, _ -> null },
                        fetchMissingCompanions = null,
                    ),
                    runtimeMinutes = { null },
                )
            }
        }

        DownloadsRepository.resumeDownload(resumedPartial.id)
        // Downloader reports initial existing partial length (0 new bytes read) while truly offline.
        backend.onProgress?.invoke(125_000_000L, 1_400_000_000L)
        compose.waitForIdle()
        compose.onNodeWithText("Waiting for the internet", substring = true).assertIsDisplayed()
    }

    @Test
    fun offline_recovery_probes_are_bounded_without_probe_storm() = runTest {
        val repositoryScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            var internetUp = false
            var publicHits = 0
            NetworkStatusRepository.coroutineScope = repositoryScope
            NetworkStatusRepository.nowMs = { testScheduler.currentTime }
            NetworkStatusRepository.publicProbeUrls = listOf("https://probe.example/generate_204")
            NetworkStatusRepository.serverProbeUrls = { listOf("https://account.example/rest/v1" to emptyMap()) }
            NetworkStatusRepository.probeReachableOverride = { url, _ ->
                if (url.contains("generate_204")) {
                    publicHits++
                    internetUp
                } else {
                    false
                }
            }

            val backend = ControllableBackend()
            DownloadsRepository.backend = backend
            DownloadsRepository.ensureLoaded()
            DownloadsRepository.enqueueFromStream(
                contentType = "movie",
                videoId = "tt-storm",
                parentMetaId = "tt-storm",
                parentMetaType = "movie",
                title = "Bounded Probe Movie",
                logo = null,
                poster = null,
                background = null,
                seasonNumber = null,
                episodeNumber = null,
                episodeTitle = null,
                episodeThumbnail = null,
                stream = StreamItem(url = "https://media.example/storm.mkv", addonName = "Test", addonId = "test"),
            )

            NetworkStatusRepository.ensureStarted()
            runCurrent()
            assertEquals(NetworkCondition.NoInternet, NetworkStatusRepository.uiState.value.condition)
            assertEquals(1, publicHits, "Initial startup should issue a single public probe")

            // 30 rapid download chunks + HTTP traffic signals within the first 4,500ms (< 5,000ms cooldown):
            // must coalesce without issuing any extra probe early.
            var nextChunk = 1L
            repeat(30) { step ->
                backend.onProgress?.invoke(nextChunk++ * 65_536L, 100_000_000L)
                if (step % 5 == 0) {
                    NetworkStatusRepository.onTrafficObserved("https://addon.example/catalog.json")
                }
                advanceTimeBy(150L)
                runCurrent()
            }
            assertEquals(1, publicHits, "Traffic burst inside 5s cooldown must not fire extra probes")

            advanceTimeBy(500L)
            runCurrent()
            assertEquals(2, publicHits, "Exactly one coalesced probe should fire at the 5s mark")

            // Because the traffic-triggered probe still failed public reachability, the next gap backs off to 10s.
            repeat(30) {
                backend.onProgress?.invoke(nextChunk++ * 65_536L, 100_000_000L)
                NetworkStatusRepository.onTrafficObserved("https://addon.example/catalog.json")
                advanceTimeBy(300L)
                runCurrent()
            }
            assertEquals(2, publicHits, "Traffic burst inside 10s backed-off window must not fire early")
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(3, publicHits, "Second traffic-triggered probe should fire after 10s backoff")

            // Third failed traffic window backs off to 20s, and fourth caps at 30s.
            NetworkStatusRepository.onTrafficObserved("https://addon.example/catalog.json")
            advanceTimeBy(19_999L)
            runCurrent()
            assertEquals(3, publicHits)
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(4, publicHits)

            NetworkStatusRepository.onTrafficObserved("https://addon.example/catalog.json")
            advanceTimeBy(29_999L)
            runCurrent()
            assertEquals(4, publicHits)
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(5, publicHits)

            // Once public connectivity recovers, periodic/traffic polling stops completely.
            internetUp = true
            NetworkStatusRepository.onTrafficObserved("https://addon.example/catalog.json")
            advanceTimeBy(30_000L)
            runCurrent()
            assertEquals(6, publicHits)
            assertEquals(NetworkCondition.ServersUnreachable, NetworkStatusRepository.uiState.value.condition)
            assertTrue(NetworkStatusRepository.uiState.value.hasInternet)

            repeat(10) {
                backend.onProgress?.invoke(nextChunk++ * 65_536L, 100_000_000L)
                NetworkStatusRepository.onTrafficObserved("https://addon.example/catalog.json")
                advanceTimeBy(30_000L)
                runCurrent()
            }
            assertEquals(6, publicHits, "No background offline probes should run once internet reachability recovers")
        } finally {
            repositoryScope.cancel()
            NetworkStatusRepository.resetForTest()
        }
    }

    @Test
    fun guarded_http_client_response_wakes_offline_recovery_without_self_probe_loop() = runBlocking {
        configureLocalNetworkProbes(retryBaseMs = 60_000L, retryMaxMs = 60_000L, minIntervalMs = 50L)
        allowPublicProbe.set(false)
        allowAccountServer.set(true)
        publicProbeHits.set(0)

        NetworkStatusRepository.ensureStarted()
        assertTrue(awaitCondition(timeoutMs = 5_000L) { it == NetworkCondition.NoInternet })
        assertEquals(1, publicProbeHits.get())

        val readyAfter = NetworkStatusRepository.nowMs() + 60L
        while (NetworkStatusRepository.nowMs() < readyAfter) {
            delay(10L)
        }
        allowPublicProbe.set(true)
        val pingUrl = "http://127.0.0.1:${server.address.port}/ping"
        assertTrue(httpGetText(pingUrl).contains("ok"))

        assertTrue(
            awaitCondition(timeoutMs = 5_000L) { it == NetworkCondition.Online },
            "Expected guardedHttpClient response to wake offline recovery without waiting for 60s timer",
        )
        delay(120L)
        assertEquals(2, publicProbeHits.get(), "Own probe responses must not trigger extra self-probes")
    }

    @Test
    fun completed_probe_does_not_clear_concurrent_probe_in_flight_flag_or_overlap_probes() = runTest {
        val supervisor = SupervisorJob()
        val customScope = CoroutineScope(supervisor + StandardTestDispatcher(testScheduler))
        try {
            NetworkStatusRepository.coroutineScope = customScope
            NetworkStatusRepository.nowMs = { testScheduler.currentTime }
            NetworkStatusRepository.offlineRetryBaseDelayMs = 60_000L
            NetworkStatusRepository.offlineRetryMaxDelayMs = 60_000L
            NetworkStatusRepository.publicProbeUrls = listOf("https://probe.example/generate_204")
            NetworkStatusRepository.serverProbeUrls = { listOf("https://account.example/rest/v1" to emptyMap()) }

            var probeNumber = 0
            var inFlight = 0
            var maxConcurrent = 0
            val startedOrder = mutableListOf<Int>()
            val completedOrder = mutableListOf<Int>()
            val finishProbe2 = CompletableDeferred<Boolean>()

            NetworkStatusRepository.probeReachableOverride = { url, _ ->
                if (!url.contains("generate_204")) {
                    true
                } else {
                    val id = ++probeNumber
                    startedOrder += id
                    inFlight++
                    maxConcurrent = maxOf(maxConcurrent, inFlight)
                    try {
                        when (id) {
                            1 -> false
                            2 -> finishProbe2.await()
                            else -> true
                        }
                    } finally {
                        inFlight--
                        completedOrder += id
                    }
                }
            }

            // Right after Probe 1 releases its in-flight ownership in the loop and before Probe 1's finally runs,
            // start Probe 2 and yield so Probe 2 is already blocked in flight when Probe 1 executes its finally block.
            var interceptedFirstExit = false
            NetworkStatusRepository.onAfterProbeReleaseForTest = {
                if (!interceptedFirstExit) {
                    interceptedFirstExit = true
                    NetworkStatusRepository.requestRefresh(force = true)
                    yield()
                }
            }

            NetworkStatusRepository.ensureStarted()
            runCurrent()

            assertEquals(listOf(1, 2), startedOrder)
            assertEquals(listOf(1), completedOrder)
            assertEquals(1, inFlight)
            assertEquals(1, supervisor.children.count { it.isActive }, "Only Probe 2 job should be active")

            // Requesting Probe 3 while Probe 2 remains blocked must queue behind Probe 2 without scheduling a third job.
            NetworkStatusRepository.requestRefresh(force = true)
            assertEquals(
                1,
                supervisor.children.count { it.isActive },
                "Probe 1 finally must not clear Probe 2 ownership or schedule a third probe job while Probe 2 is blocked",
            )
            runCurrent()
            assertEquals(1, maxConcurrent, "Probe 3 must not overlap with blocked Probe 2")
            assertEquals(listOf(1, 2), startedOrder, "Probe 3 must not start before Probe 2 completes")
            assertEquals(listOf(1), completedOrder)

            finishProbe2.complete(false)
            runCurrent()
            assertEquals(1, maxConcurrent, "Probes must never overlap")
            assertEquals(listOf(1, 2, 3), startedOrder)
            assertEquals(listOf(1, 2, 3), completedOrder)
            assertEquals(NetworkCondition.Online, NetworkStatusRepository.uiState.value.condition)

            // Verify both unexpected runtime exceptions and mid-probe cancellations release ownership in finally.
            for (throwableFactory in listOf(
                { IllegalStateException("simulated mid-probe failure") },
                { CancellationException("simulated mid-probe cancellation") },
            )) {
                NetworkStatusRepository.resetForTest()
                NetworkStatusRepository.coroutineScope = customScope
                NetworkStatusRepository.nowMs = { testScheduler.currentTime }
                NetworkStatusRepository.offlineRetryBaseDelayMs = 60_000L
                NetworkStatusRepository.offlineRetryMaxDelayMs = 60_000L
                NetworkStatusRepository.publicProbeUrls = listOf("https://probe.example/generate_204")
                NetworkStatusRepository.serverProbeUrls = { listOf("https://account.example/rest/v1" to emptyMap()) }
                var abortFirst = true
                NetworkStatusRepository.probeReachableOverride = { _, _ ->
                    if (abortFirst) {
                        abortFirst = false
                        throw throwableFactory()
                    }
                    true
                }
                NetworkStatusRepository.ensureStarted()
                runCurrent()
                assertEquals(0, supervisor.children.count { it.isActive })

                NetworkStatusRepository.requestRefresh(force = true)
                runCurrent()
                assertEquals(
                    NetworkCondition.Online,
                    NetworkStatusRepository.uiState.value.condition,
                    "Aborted probe must release ownership in finally so subsequent refreshes recover",
                )
            }
        } finally {
            customScope.cancel()
            NetworkStatusRepository.resetForTest()
        }
    }

    @Test
    fun offline_retry_backoff_grows_and_caps_at_thirty_seconds() {
        assertEquals(5_000L, NetworkStatusRepository.offlineRetryDelayMs(attempt = 1, baseDelayMs = 5_000L, maxDelayMs = 30_000L))
        assertEquals(10_000L, NetworkStatusRepository.offlineRetryDelayMs(attempt = 2, baseDelayMs = 5_000L, maxDelayMs = 30_000L))
        assertEquals(20_000L, NetworkStatusRepository.offlineRetryDelayMs(attempt = 3, baseDelayMs = 5_000L, maxDelayMs = 30_000L))
        assertEquals(30_000L, NetworkStatusRepository.offlineRetryDelayMs(attempt = 4, baseDelayMs = 5_000L, maxDelayMs = 30_000L))
        assertEquals(30_000L, NetworkStatusRepository.offlineRetryDelayMs(attempt = 9, baseDelayMs = 5_000L, maxDelayMs = 30_000L))
    }

    @Test
    fun satisfied_mac_network_path_wakes_offline_recovery_probe() = runBlocking {
        configureLocalNetworkProbes(retryBaseMs = 30_000L, retryMaxMs = 30_000L, minIntervalMs = 50L)
        allowPublicProbe.set(false)
        allowAccountServer.set(true)
        NetworkStatusRepository.ensureStarted()
        assertTrue(awaitCondition(timeoutMs = 5_000L) { it == NetworkCondition.NoInternet })

        val readyAfter = NetworkStatusRepository.nowMs() + 60L
        while (NetworkStatusRepository.nowMs() < readyAfter) {
            delay(10L)
        }
        allowPublicProbe.set(true)
        val hold = ConnectionHold()
        val recheck = WakeNetworkRecheck(
            hold = hold,
            onSatisfiedPath = NetworkStatusRepository::onNetworkPathAvailable,
            checkQuality = {},
        )
        recheck.report(pathReport(satisfied = true, expensive = false, constrained = false, interfaces = "en0", gateways = "192.168.1.1"))

        assertTrue(
            awaitCondition(timeoutMs = 5_000L) { it == NetworkCondition.Online },
            "Expected satisfied path report to trigger immediate recovery from NoInternet",
        )
    }

    private class ControllableBackend : DownloadsBackend {
        var payload: String? = null
        var onProgress: ((Long, Long?) -> Unit)? = null

        override fun loadPayload(): String? = payload
        override fun savePayload(payload: String) {
            this.payload = payload
        }
        override fun restoreItem(item: DownloadItem): DownloadItem = item
        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (Long, Long?) -> Unit,
            onSuccess: (String, Long?) -> Unit,
            onFailure: (String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle {
            this.onProgress = onProgress
            return object : DownloadsTaskHandle {
                override fun cancel() = Unit
            }
        }
        override fun removeFile(localFileUri: String?): Boolean = false
        override fun removePartialFile(destinationFileName: String): Boolean = false
        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri
    }
}
