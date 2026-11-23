package com.nuvio.app.features.downloads

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Findings of the review of the polite downloads, each as the sequence that showed it. */
class PoliteDownloadsReviewTest {
    private var now = 0L
    private val rtts = mutableMapOf<String, Long?>()
    private val traffic = DownloadTraffic()
    private val qualities = mutableListOf<LimitedConnectionReason?>()
    private var cap: Long? = null
    private var check = NetworkCheck.Open

    private val polite = PoliteDownloads(
        clock = { now },
        probeRoundTrip = { rtts[it.host] },
        checkNetwork = { check },
        traffic = traffic,
        enabled = { true },
        userCapBytesPerSecond = { null },
        setYieldCap = { cap = it },
        reportQuality = { qualities += it },
    )

    private fun ticks(count: Int, bytesPerSecond: Long) = runBlocking {
        repeat(count) {
            traffic.add((bytesPerSecond * PoliteTickMs / 1_000L).toInt())
            now += PoliteTickMs
            polite.tick()
        }
    }

    @Test
    fun failed_probes_after_a_cut_let_the_limit_go_instead_of_keeping_it() {
        val controller = YieldController()
        var t = 0L
        fun sample(rtt: Long?, throughput: Long) = controller.onSample(t.also { t += PoliteTickMs }, rtt, throughput)
        repeat(5) { sample(50L, 1_000_000L) }
        repeat(3) { sample(400L, 1_000_000L) }
        assertTrue(controller.capBytesPerSecond != null)
        // New connections to the host are refused now, while the running transfer goes on.
        repeat(100) { sample(null, 300_000L) }
        assertNull(controller.capBytesPerSecond, "a limit stayed with no evidence of congestion")
    }

    @Test
    fun a_far_host_is_measured_against_its_own_baseline_not_a_near_ones() {
        traffic.begin(TransferEndpoint("near.example", 443))
        rtts["near.example"] = 20L
        ticks(10, 1_000_000L)
        // A healthy download from a host far away: its round trip is long, and steady.
        traffic.begin(TransferEndpoint("far.example", 443))
        rtts["far.example"] = 200L
        ticks(30, 1_000_000L)
        assertNull(cap, "a far host's normal round trip was taken for a queue")
    }

    @Test
    fun the_yield_limit_alone_never_makes_the_network_slow() {
        traffic.begin(TransferEndpoint("cdn.example", 443))
        rtts["cdn.example"] = 50L
        ticks(5, 1_000_000L)
        // Other traffic on the link: the round trip rises and downloads are limited, and get what the limit lets through.
        rtts["cdn.example"] = 600L
        ticks(3, 1_000_000L)
        val limited = cap!!
        ticks(((SlowGraceMs + SlowSustainMs * 3) / PoliteTickMs).toInt(), minOf(limited, 100_000L))
        assertFalse(LimitedConnectionReason.SlowConnection in qualities, "the controller's own limit held automatic downloads")
    }

    @Test
    fun download_anyway_lasts_on_the_same_network_through_a_slow_hold_that_ends_and_comes_back() {
        val hold = ConnectionHold()
        hold.report(NetworkPathReport.Known(false, false, "en0/wifi|10.0.0.1@aa:bb:cc:00:00:01"))
        hold.reportQuality(LimitedConnectionReason.SlowConnection)
        hold.downloadAnyway()
        hold.reportQuality(null)
        hold.reportQuality(LimitedConnectionReason.SlowConnection)
        assertNull(hold.holdReason, "the choice was forgotten on the same network")
        // Another network ends it.
        hold.report(NetworkPathReport.Known(false, false, "en0/wifi|10.0.0.1@aa:bb:cc:00:00:02"))
        assertEquals(LimitedConnectionReason.SlowConnection, hold.holdReason)
    }

    @Test
    fun refused_or_failing_sources_are_not_a_blocking_network() {
        assertNull(transferFailureKind(IllegalStateException("Download failed with HTTP 403")))
        assertNull(transferFailureKind(IOException("Download failed with HTTP 503")))
    }

    @Test
    fun two_hosts_of_one_provider_are_one_source_not_every_host() {
        val judge = BlockedNetworkJudge()
        judge.onFailure(0L, "edge-1.cdn.provider.com", TransferFailureKind.Reset)
        judge.onFailure(1_000L, "edge-2.cdn.provider.com", TransferFailureKind.Reset)
        assertFalse(judge.onCheck(2_000L, NetworkCheck.Open))
        judge.onFailure(3_000L, "files.other.co.uk", TransferFailureKind.Reset)
        assertTrue(judge.onCheck(NetworkCheckMinIntervalMs + 3_000L, NetworkCheck.Open), "two providers failed")
    }

    @Test
    fun failures_during_an_outage_are_not_a_block_once_the_internet_is_back() {
        val judge = BlockedNetworkJudge()
        judge.onFailure(0L, "a.example", TransferFailureKind.Reset)
        judge.onFailure(1_000L, "b.other", TransferFailureKind.Reset)
        assertFalse(judge.onCheck(2_000L, NetworkCheck.Unreachable))
        assertFalse(judge.onCheck(2_000L + NetworkCheckMinIntervalMs, NetworkCheck.Open), "the outage was taken for a block")
    }
}

/** A sign-in page with no or a wrong content type, answering a resume, never replaces the partial file. */
class UnlabelledPagePartialTest {
    private val freeBefore = DownloadsPlatformDownloader.freeBytesProvider
    private val files = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        DownloadsPlatformDownloader.freeBytesProvider = freeBefore
        files.forEach { it.delete() }
    }

    private fun resumeAgainst(contentType: String?, page: ByteArray) {
        DownloadsPlatformDownloader.freeBytesProvider = { null }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v.mkv") { exchange ->
            if (contentType != null) exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(200, page.size.toLong())
            exchange.responseBody.use { it.write(page) }
        }
        server.start()
        val partial = File.createTempFile("nuvio-portal", ".part").also { files += it }
        val kept = ByteArray(200_000) { (it % 241).toByte() }
        partial.writeBytes(kept)
        try {
            val request = DownloadPlatformRequest(
                DownloadItem(
                    id = "portal2", contentType = "movie", parentMetaId = "tt0", parentMetaType = "movie", videoId = "tt0",
                    title = "t", streamTitle = "t", providerName = "p", sourceUrl = "http://127.0.0.1:${server.address.port}/v.mkv",
                    fileName = partial.name.removeSuffix(".part"), status = DownloadStatus.Downloading, createdAtEpochMs = 0, updatedAtEpochMs = 0,
                ),
            )
            val outcome = runCatching {
                runBlocking { DownloadsPlatformDownloader.transferToTempFile(request, partial, { _, _ -> }, DownloadSpeedLimiter({ System.nanoTime() })) }
            }
            assertTrue(outcome.isFailure, "a web page labelled $contentType was taken for the video")
            assertContentEquals(kept, partial.readBytes(), "the partial file was replaced")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun a_page_without_a_content_type_keeps_the_partial() =
        resumeAgainst(null, "﻿  \n<!DOCTYPE html><html><body>Sign in</body></html>".toByteArray())

    @Test
    fun a_page_labelled_as_a_file_keeps_the_partial() =
        resumeAgainst("application/octet-stream", "<html><head><title>Portal</title></head></html>".toByteArray())
}

/** The pure pieces the review fixes rest on. */
class PoliteDownloadsRulesTest {
    @Test
    fun only_apples_success_page_is_open_and_its_errors_tell_nothing() {
        val success = "<HTML><HEAD><TITLE>Success</TITLE></HEAD><BODY>Success</BODY></HTML>"
        assertEquals(NetworkCheck.Open, networkCheckOf(200, success, null))
        assertEquals(NetworkCheck.SignInPage, networkCheckOf(200, "<html><body>Welcome aboard, sign in</body></html>", null))
        assertEquals(NetworkCheck.SignInPage, networkCheckOf(302, "", "portal.airline.example"))
        assertEquals(NetworkCheck.Unknown, networkCheckOf(302, "", "www.apple.com"))
        assertEquals(NetworkCheck.Unknown, networkCheckOf(429, "Too Many Requests", null))
        assertEquals(NetworkCheck.Unknown, networkCheckOf(503, "", null))
    }

    @Test
    fun an_error_of_apples_page_never_holds_and_never_ends_a_hold() {
        val judge = BlockedNetworkJudge()
        judge.onFailure(0L, "a.example", TransferFailureKind.PageInsteadOfFile)
        assertFalse(judge.onCheck(0L, NetworkCheck.Unknown))
        assertTrue(judge.onCheck(NetworkCheckMinIntervalMs, NetworkCheck.SignInPage))
        assertTrue(judge.onCheck(NetworkCheckMinIntervalMs + BlockedRecheckMs, NetworkCheck.Unknown), "an error of Apple's ended a block")
    }

    @Test
    fun a_sign_in_page_does_not_block_downloads_that_still_receive_bytes() {
        val judge = BlockedNetworkJudge()
        judge.onBytes(1_000L)
        assertFalse(judge.onCheck(2_000L, NetworkCheck.SignInPage))
    }

    @Test
    fun the_start_of_a_web_page_is_told_from_a_video() {
        assertTrue(startsLikeWebPage("﻿ \r\n<!DOCTYPE HTML PUBLIC>".toByteArray()))
        assertTrue(startsLikeWebPage("<?xml version=\"1.0\"?><html>".toByteArray()))
        assertTrue(startsLikeWebPage("<HEAD><title>x</title>".toByteArray()))
        assertFalse(startsLikeWebPage(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0, 0)), "Matroska")
        assertFalse(startsLikeWebPage(byteArrayOf(0, 0, 0, 0x20) + "ftypisom".toByteArray()), "MP4")
        assertFalse(startsLikeWebPage(ByteArray(0)))
    }

    @Test
    fun hosts_of_one_provider_share_a_domain() {
        assertEquals("provider.com", registrableDomain("edge-1.cdn.provider.com"))
        assertEquals("other.co.uk", registrableDomain("files.other.co.uk"))
        assertEquals("example.de", registrableDomain("a.b.example.de"))
        assertEquals("203.0.113.9", registrableDomain("203.0.113.9"))
    }
}
