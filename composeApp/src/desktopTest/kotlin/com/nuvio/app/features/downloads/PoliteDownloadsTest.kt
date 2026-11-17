package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The yield controller alone, with round trips and throughput given to it. */
class YieldControllerTest {
    private val controller = YieldController()
    private var now = 0L

    private fun sample(rtt: Long?, throughput: Long): Long? {
        now += PoliteTickMs
        return controller.onSample(now, rtt, throughput)
    }

    private fun quiet(times: Int = 5, throughput: Long = 1_000_000L) = repeat(times) { sample(50L, throughput) }

    @Test
    fun a_quiet_link_is_not_limited() {
        quiet(20)
        assertNull(controller.capBytesPerSecond)
        assertEquals(50L, controller.baselineMs)
    }

    @Test
    fun a_rising_round_trip_cuts_the_rate_below_what_downloads_get() {
        quiet()
        // Queues build: the recent round trip has to stay up for a few samples, one late answer is not enough.
        assertNull(sample(400L, 1_000_000L), "one late answer cut the rate")
        sample(400L, 1_000_000L)
        val cut = sample(400L, 1_000_000L)
        assertEquals(700_000L, cut)
        // Still congested: cut again, from what was set.
        assertEquals(420_000L, sample(400L, 600_000L))
    }

    @Test
    fun a_full_queue_never_takes_downloads_below_the_minimum() {
        quiet()
        repeat(50) { sample(ProbeTimeoutMs, 10_000L) }
        assertEquals(YieldMinBytesPerSecond, controller.capBytesPerSecond)
    }

    @Test
    fun the_rate_grows_back_slowly_once_the_round_trip_is_near_the_baseline_and_then_the_limit_goes() {
        quiet()
        repeat(3) { sample(400L, 1_000_000L) }
        val cut = controller.capBytesPerSecond!!
        // Within the cooldown nothing grows.
        sample(50L, cut)
        sample(50L, cut)
        assertEquals(cut, controller.capBytesPerSecond, "grew during the cooldown")
        sample(50L, cut)
        sample(50L, cut)
        val grown = controller.capBytesPerSecond!!
        assertTrue(grown in (cut + 1)..(cut * 2), "grew $cut -> $grown in one step")
        // Downloads stay below the limit (it no longer binds) and the link stays calm: the limit goes.
        repeat(40) { sample(50L, 300_000L) }
        assertNull(controller.capBytesPerSecond)
    }

    @Test
    fun the_baseline_follows_a_path_that_got_slower_within_ten_minutes() {
        quiet()
        // A satellite handover: everything is 300 ms from now on, without our downloads causing it.
        repeat((11 * 60_000L / PoliteTickMs).toInt()) { sample(300L, 1_000_000L) }
        assertEquals(300L, controller.baselineMs)
        // The rate was cut while the baseline was still the old one; on the new one it is calm and the limit goes.
        repeat((5 * 60_000L / PoliteTickMs).toInt()) { sample(300L, 300_000L) }
        assertNull(controller.capBytesPerSecond)
    }

    @Test
    fun the_target_is_larger_on_long_links() {
        assertEquals(60L, queueTargetMs(40L))
        assertEquals(150L, queueTargetMs(600L))
        assertEquals(200L, queueTargetMs(2_000L))
    }
}

/** The user's own speed limit stays the ceiling. */
class YieldUnderUserLimitTest {
    private val before = DownloadBandwidth.userCapBytesPerSecond.value

    @AfterTest
    fun restore() {
        DownloadBandwidth.setYieldCap(null)
        DownloadBandwidth.setUserCap(before)
    }

    @Test
    fun the_lower_of_the_two_limits_applies() {
        DownloadBandwidth.setUserCap(1_000_000L)
        DownloadBandwidth.setYieldCap(3_000_000L)
        assertEquals(1_000_000L, DownloadBandwidth.limiter.bytesPerSecond, "yielding raised the user's limit")
        DownloadBandwidth.setYieldCap(400_000L)
        assertEquals(400_000L, DownloadBandwidth.limiter.bytesPerSecond)
        // A transfer that starts applies the stored limit again: the yield stays.
        DownloadBandwidth.applyStoredLimit()
        assertEquals(400_000L, DownloadBandwidth.limiter.bytesPerSecond)
        DownloadBandwidth.setYieldCap(null)
        assertEquals(1_000_000L, DownloadBandwidth.limiter.bytesPerSecond)
        DownloadBandwidth.setUserCap(null)
        assertNull(DownloadBandwidth.limiter.bytesPerSecond)
    }
}

/** When the network is too slow for automatic downloads. */
class SlowNetworkJudgeTest {
    private val judge = SlowNetworkJudge()
    private var now = 0L

    private fun run(ms: Long, throughput: Long, active: Boolean = true, userCap: Long? = null): Boolean {
        var slow = false
        val until = now + ms
        while (now < until) {
            now += PoliteTickMs
            slow = judge.onSample(now, throughput, active, userCap)
        }
        return slow
    }

    @Test
    fun a_minute_under_the_floor_after_the_start_is_slow() {
        assertFalse(run(SlowGraceMs + SlowSustainMs - 4_000L, 100_000L), "slow too early")
        assertTrue(run(6_000L, 100_000L))
    }

    @Test
    fun a_fast_moment_starts_the_minute_again() {
        run(SlowGraceMs + 50_000L, 100_000L)
        run(2_000L, 2_000_000L)
        assertFalse(run(50_000L, 100_000L))
    }

    @Test
    fun the_users_own_low_limit_never_makes_the_network_slow() {
        assertFalse(run(10 * 60_000L, 100_000L, userCap = 125_000L))
    }

    @Test
    fun a_trial_comes_after_five_minutes_and_fast_user_downloads_end_the_wait_early() {
        assertTrue(run(SlowGraceMs + SlowSustainMs + 2_000L, 100_000L))
        assertTrue(run(SlowRecheckMs - 10_000L, 0L, active = false), "the wait ended before the trial")
        assertFalse(run(12_000L, 0L, active = false), "no trial after five minutes")

        assertTrue(run(SlowGraceMs + SlowSustainMs + 2_000L, 100_000L))
        assertFalse(run(SlowRecoverMs + 2_000L, 1_000_000L), "fast downloads of the user did not end the wait")
    }
}

/** When a network blocks downloads. */
class BlockedNetworkJudgeTest {
    private val judge = BlockedNetworkJudge()

    @Test
    fun a_sign_in_page_blocks_at_once_and_the_block_ends_when_the_page_goes() {
        judge.onFailure(0L, "cdn.example", TransferFailureKind.PageInsteadOfFile)
        assertTrue(judge.checkDue(0L))
        assertTrue(judge.onCheck(0L, NetworkCheck.SignInPage))
        assertFalse(judge.checkDue(BlockedRecheckMs - 1L), "looked at again too soon")
        assertTrue(judge.checkDue(BlockedRecheckMs))
        assertFalse(judge.onCheck(BlockedRecheckMs, NetworkCheck.Open))
    }

    @Test
    fun one_host_failing_is_that_source_not_the_network() {
        judge.onFailure(0L, "a.example", TransferFailureKind.Refused)
        judge.onFailure(1_000L, "a.example", TransferFailureKind.Reset)
        assertFalse(judge.onCheck(2_000L, NetworkCheck.Open))
    }

    @Test
    fun every_host_failing_while_other_requests_work_is_a_blocking_network() {
        judge.onFailure(0L, "a.example", TransferFailureKind.Reset)
        judge.onFailure(1_000L, "b.example", TransferFailureKind.Refused)
        assertTrue(judge.onCheck(2_000L, NetworkCheck.Open))
    }

    @Test
    fun not_when_bytes_still_arrive_or_the_internet_is_down() {
        judge.onFailure(0L, "a.example", TransferFailureKind.Reset)
        judge.onFailure(1_000L, "b.example", TransferFailureKind.Reset)
        assertFalse(judge.onCheck(2_000L, NetworkCheck.Unreachable), "an outage is not a block")
        judge.onBytes(130_000L)
        assertFalse(judge.onCheck(130_000L, NetworkCheck.Open), "bytes arrived")
    }

    @Test
    fun broken_secure_connections_block_only_when_downloads_failed_that_way() {
        judge.onFailure(0L, "a.example", TransferFailureKind.Reset)
        assertFalse(judge.onCheck(0L, NetworkCheck.SecureConnectionBroken))
        judge.onFailure(NetworkCheckMinIntervalMs, "a.example", TransferFailureKind.SecureConnection)
        assertTrue(judge.onCheck(NetworkCheckMinIntervalMs, NetworkCheck.SecureConnectionBroken))
    }

    @Test
    fun the_network_is_looked_at_no_more_than_every_two_minutes_however_much_fails() {
        var checks = 0
        var now = 0L
        while (now < 10 * 60_000L) {
            judge.onFailure(now, "a.example", TransferFailureKind.Reset)
            if (judge.checkDue(now)) {
                checks++
                judge.onCheck(now, NetworkCheck.Open)
            }
            now += 1_000L
        }
        assertEquals(5, checks)
    }

    @Test
    fun the_failures_a_download_shows_are_told_apart() {
        assertEquals(TransferFailureKind.PageInsteadOfFile, transferFailureKind(PageInsteadOfFile()))
        assertEquals(TransferFailureKind.SecureConnection, transferFailureKind(javax.net.ssl.SSLHandshakeException("PKIX path building failed")))
        assertEquals(TransferFailureKind.Refused, transferFailureKind(IllegalStateException("Download failed with HTTP 451")))
        assertEquals(TransferFailureKind.Refused, transferFailureKind(IOException("Download failed with HTTP 503")))
        assertEquals(TransferFailureKind.Reset, transferFailureKind(IOException("Connection reset")))
        assertNull(transferFailureKind(IllegalStateException("Download failed with HTTP 404")))
        assertTrue(isWebPage("text/html; charset=utf-8"))
        assertFalse(isWebPage("video/x-matroska"))
        assertFalse(isWebPage(null))
    }
}

/** The runner, with a fake clock, probe and network. */
class PoliteDownloadsRunnerTest {
    private var now = 0L
    private var rtt = 50L
    private var probes = 0
    private var networkChecks = 0
    private var check = NetworkCheck.Open
    private var enabled = true
    private var userCap: Long? = null
    private val traffic = DownloadTraffic()
    private val caps = mutableListOf<Long?>()
    private val qualities = mutableListOf<LimitedConnectionReason?>()

    private val polite = PoliteDownloads(
        clock = { now },
        probeRoundTrip = { probes++; rtt },
        checkNetwork = { networkChecks++; check },
        traffic = traffic,
        enabled = { enabled },
        userCapBytesPerSecond = { userCap },
        setYieldCap = { caps += it },
        reportQuality = { qualities += it },
    )

    private fun tick(stepMs: Long = PoliteTickMs, bytesPerSecond: Long = 0L) = runBlocking {
        traffic.add((bytesPerSecond * stepMs / 1_000L).toInt())
        now += stepMs
        polite.tick()
    }

    @Test
    fun no_probe_while_nothing_downloads_and_at_most_one_per_tick_interval_while_something_does() {
        repeat(100) { tick() }
        assertEquals(0, probes, "probed with no download")
        traffic.begin(TransferEndpoint("cdn.example", 443))
        repeat(100) { tick(stepMs = 500L, bytesPerSecond = 1_000_000L) }
        assertEquals(25, probes, "50 s of downloading at a tick every half second")
    }

    @Test
    fun congestion_caps_the_rate_and_the_screen_says_so() {
        traffic.begin(TransferEndpoint("cdn.example", 443))
        repeat(5) { tick(bytesPerSecond = 1_000_000L) }
        assertFalse(polite.state.value.yielding)
        rtt = 600L
        repeat(3) { tick(bytesPerSecond = 1_000_000L) }
        assertTrue(polite.state.value.yielding)
        assertTrue(caps.last()!! < 1_000_000L)
    }

    @Test
    fun turned_off_it_never_limits_or_probes() {
        enabled = false
        traffic.begin(TransferEndpoint("cdn.example", 443))
        rtt = 900L
        repeat(60) { tick(bytesPerSecond = 50_000L) }
        assertEquals(0, probes)
        assertTrue(caps.all { it == null })
        assertTrue(qualities.none { it == LimitedConnectionReason.SlowConnection })
    }

    @Test
    fun a_slow_network_is_reported_and_later_let_go_for_a_trial() {
        traffic.begin(TransferEndpoint("cdn.example", 443))
        repeat(((SlowGraceMs + SlowSustainMs) / PoliteTickMs + 2).toInt()) { tick(bytesPerSecond = 100_000L) }
        assertEquals(LimitedConnectionReason.SlowConnection, qualities.last())
        repeat((SlowRecheckMs / PoliteTickMs + 1).toInt()) { tick() }
        assertNull(qualities.last())
    }

    @Test
    fun failures_from_every_host_report_a_blocked_network_after_one_look() {
        polite.onTransferFailure("a.example", TransferFailureKind.Reset)
        polite.onTransferFailure("b.example", TransferFailureKind.Refused)
        repeat(30) { tick() }
        assertEquals(LimitedConnectionReason.NetworkBlocksDownloads, qualities.last())
        assertEquals(1, networkChecks, "the network was looked at more than once in a minute")
    }
}

/** The holds for a slow or blocking network, applied by the downloads list. */
class QualityHoldDownloadsTest {
    private class FakeBackend : DownloadsBackend {
        var payload: String? = null
        val started = mutableListOf<DownloadPlatformRequest>()
        val cancelled = mutableListOf<String>()
        override fun loadPayload(): String? = payload
        override fun savePayload(payload: String) { this.payload = payload }
        override fun restoreItem(item: DownloadItem): DownloadItem = item
        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (Long, Long?) -> Unit,
            onSuccess: (String, Long?) -> Unit,
            onFailure: (String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle {
            started += request
            return object : DownloadsTaskHandle { override fun cancel() { cancelled += request.item.id } }
        }
        override fun removeFile(localFileUri: String?): Boolean = true
        override fun removePartialFile(destinationFileName: String): Boolean = error("a partial file was removed")
        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = null
    }

    private val backend = FakeBackend()
    private val hold = ConnectionHold()
    private val realBackend = DownloadsRepository.backend
    private val realHold = DownloadsRepository.connectionHold

    @BeforeTest
    fun setUp() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        DownloadsRepository.connectionHold = hold
        DownloadsRepository.ensureLoaded()
    }

    @AfterTest
    fun tearDown() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        DownloadsRepository.connectionHold = realHold
    }

    private fun enqueue(episode: Int, auto: Boolean): String {
        DownloadsRepository.enqueueFromStream(
            contentType = "series", videoId = "tt1:1:$episode", parentMetaId = "tt1", parentMetaType = "series", title = "Show",
            logo = null, poster = null, background = null, seasonNumber = 1, episodeNumber = episode, episodeTitle = null,
            episodeThumbnail = null,
            stream = StreamItem(name = "1080p", url = "https://example.com/e$episode.mkv", addonName = "A", addonId = "a", behaviorHints = StreamBehaviorHints()),
            autoDownloadRuleKey = if (auto) "rule" else null,
        )
        return DownloadsRepository.uiState.value.items.first { it.episodeNumber == episode }.id
    }

    private fun item(id: String) = DownloadsRepository.uiState.value.items.single { it.id == id }

    @Test
    fun a_slow_network_holds_automatic_downloads_and_lets_the_users_go_on() {
        val auto = enqueue(1, auto = true)
        val mine = enqueue(2, auto = false)
        backend.started.first { it.item.id == auto }.onPartialCreated("dev:1")

        hold.reportQuality(LimitedConnectionReason.SlowConnection)

        assertTrue(item(auto).pausedByConnection)
        assertEquals("dev:1", item(auto).partialFileKey, "the partial file is no longer known as the download's")
        assertEquals(DownloadStatus.Downloading, item(mine).status)
        assertEquals(listOf(auto), backend.cancelled)
        // A new automatic download waits; one the user starts runs.
        assertTrue(item(enqueue(3, auto = true)).pausedByConnection)
        assertEquals(DownloadStatus.Downloading, item(enqueue(4, auto = false)).status)
        assertEquals(DownloadPhase.WaitingOnSlowNetwork, downloadRowStatus(item(auto), 0L, limitedBy = hold.state.value.reason).phase)

        hold.reportQuality(null)
        assertEquals(DownloadStatus.Downloading, item(auto).status)
        assertEquals("dev:1", backend.started.last { it.item.id == auto }.item.partialFileKey)
    }

    @Test
    fun a_network_that_blocks_downloads_holds_every_download_and_download_anyway_lets_them_try() {
        val auto = enqueue(1, auto = true)
        val mine = enqueue(2, auto = false)

        hold.reportQuality(LimitedConnectionReason.NetworkBlocksDownloads)
        assertTrue(item(auto).pausedByConnection)
        assertTrue(item(mine).pausedByConnection)
        assertEquals(LimitedConnectionReason.NetworkBlocksDownloads, hold.holdReason)

        hold.downloadAnyway()
        assertEquals(DownloadStatus.Downloading, item(auto).status)
        assertEquals(DownloadStatus.Downloading, item(mine).status)
    }
}
