package com.nuvio.app.features.player

import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.streams.StreamSubtitle
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

class StartupSubtitleBlockingTest {
    private val fullSubtitleStyle = SubtitleStyleState.DEFAULT.copy(useForcedSubtitles = false)
    @Test
    fun malformedSavedTtmlDoesNotBlockStartup() = runBlocking {
        val preparer = StartupSubtitlePreparer(
            dependencies(localText = "<p ".repeat(20_000)),
        )
        val elapsed = measureTime {
            val prepared = preparer.prepareDownloadedSubtitles(
                listOf(StreamSubtitle("file:///tmp/bad.ttml", "en", "English")),
                PlayerSettingsUiState(subtitleStyle = fullSubtitleStyle, preferredSubtitleLanguage = "en"),
                "tt-bad-ttml",
            )
            assertNull(prepared.selection.selectedUrl)
            assertTrue(prepared.selection.exhaustedOrTimedOut)
        }
        assertTrue(elapsed.inWholeMilliseconds < 1_500L, "Malformed TTML took $elapsed")
    }

    @Test
    fun savedTtmlValidationKeepsCallerResponsiveAndPreservesCues() = runBlocking {
        val read = CompletableDeferred<Unit>()
        val text = "<tt><body>" +
            "<p begin=\"1s\" end=\"3s\">Hello<br/>world</p>".repeat(10_000) + "</body></tt>"
        val preparer = StartupSubtitlePreparer(dependencies().copy(
            readLocalSubtitleText = { read.complete(Unit); text },
            localValidationTimeoutMs = 10_000L,
        ))
        var callerTicks = 0
        val heartbeat = launch {
            read.await()
            while (isActive) {
                callerTicks++
                yield()
            }
        }
        try {
            val prepared = preparer.prepareDownloadedSubtitles(
                listOf(StreamSubtitle("file:///tmp/valid.ttml", "en", "English")),
                PlayerSettingsUiState(subtitleStyle = fullSubtitleStyle, preferredSubtitleLanguage = "en"),
                "tt-valid-ttml",
            )
            assertNotNull(prepared.selection.selectedUrl)
            assertTrue(callerTicks > 0, "Parsing blocked the caller")
            val cues = PlayerSubtitleCueParser.parse(text, "valid.ttml")
            kotlin.test.assertEquals(10_000, cues.size)
            kotlin.test.assertEquals("Hello\nworld", cues.first().text)
        } finally {
            heartbeat.cancelAndJoin()
        }
    }

    @Test
    fun readySubtitleCancelsDiscoveryWaitingForHttpHeaders() { stalledDiscovery(false) }

    @Test
    fun readySubtitleCancelsDiscoveryWaitingForHttpBody() { stalledDiscovery(true) }

    private fun stalledDiscovery(sendHeaders: Boolean) = runBlocking {
        val requested = CompletableDeferred<Unit>()
        val written = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/slow") { exchange ->
            try {
                if (sendHeaders) {
                    exchange.sendResponseHeaders(200, 2)
                    exchange.responseBody.write('['.code)
                    exchange.responseBody.flush()
                }
                requested.complete(Unit)
                release.await()
                if (sendHeaders) {
                    exchange.responseBody.write(']'.code)
                } else {
                    exchange.sendResponseHeaders(200, 2)
                    exchange.responseBody.write("[]".toByteArray())
                }
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            val preparer = StartupSubtitlePreparer(dependencies().copy(
                startupTimeoutMs = 30_000L,
                writeCachedSubtitleText = { _, _ -> written.complete(Unit); "file:///tmp/ready.srt" },
                fetchAddonSubtitlesStreaming = { _, onBatch ->
                    coroutineScope {
                        val slow = async { httpGetText("http://127.0.0.1:${server.address.port}/slow") }
                        requested.await()
                        onBatch(listOf(AddonSubtitle("fast", "https://subs.example.invalid/en.srt", "en", "English")))
                        slow.await()
                        emptyList()
                    }
                },
            ))
            val preparation = async {
                preparer.prepareStreamSubtitles(StartupSubtitleRequest(
                    type = "movie", videoId = "tt-http", parentMetaId = "tt-http",
                    settings = PlayerSettingsUiState(subtitleStyle = fullSubtitleStyle, preferredSubtitleLanguage = "en"),
                ))
            }
            withTimeout(30_000L) { written.await() }
            val prepared = withTimeoutOrNull(1_500L) { preparation.await() }
            assertNotNull(prepared, "A ready subtitle waited for the stalled discovery request")
            assertNotNull(prepared.selection.selectedUrl)
        } finally {
            release.countDown()
            server.stop(0)
        }
    }

    private fun dependencies(localText: String? = null) = StartupSubtitleDependencies(
        fetchAddonSubtitlesStreaming = { _, _ -> emptyList() },
        fetchSubtitleText = { _, _, _ -> "1\n00:00:01,000 --> 00:00:03,000\nHello\n" },
        readLocalSubtitleText = { localText },
        writeCachedSubtitleText = { _, _ -> "file:///tmp/ready.srt" },
        deviceLanguages = { listOf("en") },
        loadPersistedTrackPreference = { null },
        startupTimeoutMs = 10_000L,
    )
}
