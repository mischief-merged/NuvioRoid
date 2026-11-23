package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.WantedContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TravelModeTest {
    private val plane = NetworkPathReport.Known(false, false, "en0/wifi|10.0.0.1@01:02:03:04:05:06")
    private val home = plane.copy(networkKey = "en0/wifi|192.168.1.1@11:12:13:14:15:16")

    @Test
    fun explicit_mode_pauses_only_automatic_downloads_and_cannot_be_bypassed_by_download_anyway() {
        val hold = ConnectionHold()
        hold.report(plane.copy(expensive = true))
        hold.setTravelEnabled(true)
        assertEquals(LimitedConnectionReason.Travel, hold.holdReasonFor(true))
        assertNull(hold.holdReasonFor(false))
        hold.downloadAnyway()
        assertEquals(LimitedConnectionReason.Travel, hold.holdReasonFor(true))
        assertNull(hold.holdReasonFor(false))
        assertFalse(hold.state.value.travel.previewsAllowed)
        assertTrue(hold.state.value.travel.yieldingEnabled(false))
        hold.setTravelEnabled(false)
        assertTrue(hold.state.value.travel.previewsAllowed)
        assertFalse(hold.state.value.travel.yieldingEnabled(false))
    }

    @Test
    fun remembered_networks_survive_a_restart_and_do_not_apply_to_another_router() {
        var saved = TravelPreferences()
        val hold = ConnectionHold(saveTravel = { saved = it })
        hold.report(plane)
        hold.rememberTravelNetwork(true)
        assertTrue(hold.state.value.travel.enabled)
        assertEquals(setOf(plane.networkKey), saved.networks)
        hold.report(home)
        assertFalse(hold.state.value.travel.enabled)
        val restarted = ConnectionHold(loadTravel = { saved })
        restarted.report(plane)
        assertTrue(restarted.state.value.travel.remembered)
        restarted.report(plane.copy(networkKey = "en0/wifi|10.0.0.1@99:99:99:99:99:99"))
        assertFalse(restarted.state.value.travel.enabled)
        restarted.report(plane)
        restarted.setTravelEnabled(false)
        restarted.report(plane)
        assertFalse(restarted.state.value.travel.enabled)
    }

    @Test
    fun an_unknown_or_disconnected_network_cannot_be_remembered() {
        var saves = 0
        val hold = ConnectionHold(saveTravel = { saves++ })
        hold.rememberTravelNetwork(true)
        hold.report(NetworkPathReport.Known(false, false, ""))
        hold.rememberTravelNetwork(true)
        assertEquals(0, saves)
        assertFalse(hold.state.value.travel.canRemember)
        hold.setTravelEnabled(true)
        assertEquals(LimitedConnectionReason.Travel, hold.holdReasonFor(true))
    }

    @Test
    fun the_adjustable_travel_cap_is_shared_and_never_raises_a_stricter_limit() {
        var saved = TravelPreferences()
        var applied: Long? = null
        val hold = ConnectionHold(saveTravel = { saved = it }, applyTravelCap = { applied = it })
        hold.setTravelEnabled(true)
        assertEquals(1_000_000L, applied)
        hold.setTravelCap(500_000L)
        assertEquals(500_000L, applied)
        assertEquals(500_000L, ConnectionHold(loadTravel = { saved }).state.value.travel.capBytesPerSecond)
        assertEquals(250_000L, effectiveDownloadCap(250_000L, applied, 400_000L))
        assertEquals(200_000L, effectiveDownloadCap(null, applied, 200_000L))
        assertEquals(500_000L, effectiveDownloadCap(null, applied, null))
        hold.setTravelEnabled(false)
        assertNull(applied)
    }

    @Test
    fun smaller_known_streams_win_without_promoting_a_different_episode() {
        fun source(label: String, size: Long?) = StreamItem(
            name = label, url = "https://example.com/$label", addonName = "A", addonId = "a",
            behaviorHints = StreamBehaviorHints(filename = label, videoSize = size),
        )
        val large = source("Show.S01E01.2160p", 8_000_000_000L)
        val small = source("Show.S01E01.1080p", 2_000_000_000L)
        val unknown = source("Show.S01E01.720p", null)
        val other = source("Show.S01E02.720p", 500_000_000L)
        val streams = listOf(large, unknown, other, small)
        val wanted = WantedContent(title = "Show", season = 1, episode = 1)
        assertEquals(small, rankStreamsByPreferences(streams, BatchQuality.BestAvailable, wanted = wanted, travelMode = true).first())
        assertEquals(large, rankStreamsByPreferences(streams, BatchQuality.BestAvailable, wanted = wanted, travelMode = false).first())
        assertEquals(listOf(large), rankStreamsByPreferences(streams, BatchQuality.P2160, wanted = wanted, travelMode = true))
    }

    @Test
    fun wake_rechecks_quality_before_a_fresh_path_releases_the_hold() = runBlocking {
        val hold = ConnectionHold()
        hold.report(home)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gate = WakeNetworkRecheck(hold) {
            entered.complete(Unit)
            release.await()
            hold.reportQuality(LimitedConnectionReason.SlowConnection)
        }
        gate.sleep()
        assertEquals(LimitedConnectionReason.CheckingNetwork, hold.holdReasonFor(false))
        gate.report(home)
        assertTrue(hold.state.value.checkingNetwork)
        gate.wake()
        val report = launch { gate.report(plane) }
        entered.await()
        assertTrue(hold.state.value.checkingNetwork)
        release.complete(Unit)
        report.join()
        assertFalse(hold.state.value.checkingNetwork)
        assertEquals(LimitedConnectionReason.SlowConnection, hold.holdReasonFor(true))
        assertNull(hold.holdReasonFor(false))
    }

    @Test
    fun a_second_sleep_invalidates_an_in_flight_wake_check() = runBlocking {
        val hold = ConnectionHold()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gate = WakeNetworkRecheck(hold) { entered.complete(Unit); release.await() }
        gate.wake()
        val report = launch { gate.report(home) }
        entered.await()
        gate.sleep()
        release.complete(Unit)
        report.join()
        assertTrue(hold.state.value.checkingNetwork)
        gate.wake()
        gate.report(NetworkPathReport.Unknown)
        assertTrue(hold.state.value.checkingNetwork)
    }

    @Test
    fun waking_on_a_remembered_travel_network_holds_automatic_downloads() = runBlocking {
        val hold = ConnectionHold(loadTravel = { TravelPreferences(networks = setOf(plane.networkKey)) })
        hold.report(home)
        val gate = WakeNetworkRecheck(hold) {}
        gate.sleep()
        gate.wake()
        gate.report(plane)
        assertEquals(LimitedConnectionReason.Travel, hold.holdReasonFor(true))
        assertNull(hold.holdReasonFor(false))
        gate.sleep()
        gate.wake()
        gate.report(home.copy(expensive = true))
        assertEquals(LimitedConnectionReason.Hotspot, hold.holdReasonFor(false))
    }
    @Test
    fun autoplay_prefers_a_smaller_stream_in_travel_and_keeps_manual_selection_manual() {
        fun source(resolution: String, bytes: Long) = StreamItem(
            name = resolution, url = "https://example.com/$resolution", addonName = "A", addonId = "a",
            behaviorHints = StreamBehaviorHints(videoSize = bytes),
        )
        val large = source("2160p", 8_000_000_000L)
        val small = source("1080p", 2_000_000_000L)
        fun selected(mode: StreamAutoPlayMode, travel: Boolean) = StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = listOf(large, small), mode = mode, regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES, installedAddonNames = setOf("A"),
            selectedAddons = emptySet(), selectedPlugins = emptySet(), travelMode = travel,
        ).stream
        assertEquals(large, selected(StreamAutoPlayMode.FIRST_STREAM, false))
        assertEquals(small, selected(StreamAutoPlayMode.FIRST_STREAM, true))
        assertNull(selected(StreamAutoPlayMode.MANUAL, true))
    }

    @Test
    fun a_sleep_does_not_turn_a_previous_slow_network_into_a_fresh_trial() {
        val judge = SlowNetworkJudge()
        judge.onSample(0, 10_000, true, null)
        judge.onSample(SlowGraceMs, 10_000, true, null)
        judge.onSample(SlowGraceMs + SlowSustainMs, 10_000, true, null)
        assertTrue(judge.slow)
        val wake = SlowRecheckMs * 10
        judge.afterWake(wake)
        assertTrue(judge.onSample(wake + PoliteTickMs, 0, false, null))
        assertFalse(judge.onSample(wake + SlowRecheckMs, 0, false, null))
    }

    @Test
    fun a_wake_check_discards_old_traffic_before_judging_a_sign_in_page() = runBlocking {
        val traffic = DownloadTraffic()
        val endpoint = traffic.begin(TransferEndpoint("example.com", 443))
        var now = 0L
        var check = NetworkCheck.Open
        val hold = ConnectionHold()
        val polite = PoliteDownloads(
            clock = { now }, probeRoundTrip = { 50L }, checkNetwork = { check },
            traffic = traffic, enabled = { true }, userCapBytesPerSecond = { null },
            setYieldCap = {}, reportQuality = hold::reportQuality,
        )
        traffic.add(100_000)
        polite.tick()
        traffic.end(endpoint)
        check = NetworkCheck.SignInPage
        now += 2000
        polite.recheckAfterWake()
        assertEquals(LimitedConnectionReason.NetworkBlocksDownloads, hold.holdReason)
        now += 2000
        polite.tick()
        assertEquals(LimitedConnectionReason.NetworkBlocksDownloads, hold.holdReason)
    }

    @Test
    fun native_sleep_invalidates_queued_path_reports_and_notifies_before_returning() {
        var listener: NetworkPathListener? = null
        var sleeps = 0
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val reports = java.util.concurrent.LinkedBlockingQueue<NetworkPathReport>()
        val detector = MacNetworkPathDetector(
            isMac = true, loadLibrary = {}, onSleep = { sleeps++ },
            native = object : MacNetworkPathDetector.NativePathMonitor {
                override fun start(value: NetworkPathListener): Boolean { listener = value; return true }
                override fun stop() = Unit
            },
        )
        try {
            assertTrue(detector.start {
                entered.countDown()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                reports.put(it)
            })
            listener!!.onPath(true, false, false, "en0/wifi", "192.168.1.1")
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            listener!!.onPath(true, false, false, "en0/wifi", "10.0.0.1")
            listener!!.onSleep()
            assertEquals(1, sleeps)
            release.countDown()
            assertTrue(detector.start { reports.put(it) })
            listener!!.onPath(true, true, false, "en0/wifi", "172.20.10.1")
            val first = reports.poll(5, java.util.concurrent.TimeUnit.SECONDS)
            val second = reports.poll(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(listOf(first, second).none { (it as? NetworkPathReport.Known)?.networkKey == "en0/wifi|10.0.0.1" })
            assertTrue(listOf(first, second).any { (it as? NetworkPathReport.Known)?.expensive == true })
        } finally {
            release.countDown()
            detector.stop()
        }
    }

    @Test
    fun a_new_path_during_a_wake_check_cannot_release_downloads_on_the_old_path() = runBlocking {
        val hold = ConnectionHold()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gate = WakeNetworkRecheck(hold) { entered.complete(Unit); release.await() }
        gate.wake()
        val old = launch { gate.report(home) }
        entered.await()
        gate.pathChanged()
        release.complete(Unit)
        old.join()
        assertTrue(hold.state.value.checkingNetwork)
        gate.report(plane.copy(expensive = true))
        assertFalse(hold.state.value.checkingNetwork)
        assertEquals(LimitedConnectionReason.Hotspot, hold.holdReasonFor(false))
    }

    @Test
    fun the_lowest_travel_cap_does_not_count_as_a_slow_network() {
        val judge = SlowNetworkJudge()
        repeat(100) { tick ->
            assertFalse(judge.onSample(tick * PoliteTickMs, 240_000L, true, 250_000L))
        }
    }

}
