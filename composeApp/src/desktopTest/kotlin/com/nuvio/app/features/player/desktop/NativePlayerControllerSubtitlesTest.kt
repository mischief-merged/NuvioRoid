package com.nuvio.app.features.player.desktop

import androidx.compose.ui.Modifier
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerScreenArgs
import com.nuvio.app.features.player.PlayerScreenRuntime
import com.nuvio.app.features.player.StartupSubtitleSelection
import com.nuvio.app.features.player.selectServerAudioTrack
import com.nuvio.app.features.player.selectServerSubtitleTrack
import com.nuvio.app.features.player.tryRefreshExpiredLinkAfterError
import com.nuvio.app.features.servers.FakeServerProvider
import com.nuvio.app.features.servers.ServerItemRef
import com.nuvio.app.features.servers.ServerPlayback
import com.nuvio.app.features.servers.ServerPlaybackRequest
import com.nuvio.app.features.servers.ServerPlaybackSession
import com.nuvio.app.features.servers.ServerPlayMethod
import com.nuvio.app.features.servers.ServerProvider
import com.nuvio.app.features.servers.ServerProviders
import com.nuvio.app.features.servers.ServerSession
import com.nuvio.app.features.servers.ServerStreams
import com.nuvio.app.features.servers.installFakeServer
import com.nuvio.app.features.servers.removeFakeServer
import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativePlayerControllerSubtitlesTest {

    private class RecordingSubtitleBridgeOps : NativeSubtitleBridgeOps {
        data class StartupCall(
            val handle: Long,
            val urls: List<String>,
            val titles: List<String>,
            val languages: List<String>,
            val selectedIndex: Int,
            val disableAutoSelect: Boolean,
        )

        data class AddCall(
            val handle: Long,
            val url: String,
            val title: String,
            val language: String,
            val select: Boolean,
        )

        val startupCalls: MutableList<StartupCall> = Collections.synchronizedList(mutableListOf())
        val addCalls: MutableList<AddCall> = Collections.synchronizedList(mutableListOf())
        val clearedHandles: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        val startupLatch = AtomicReference<CountDownLatch?>(null)
        val addLatch = AtomicReference<CountDownLatch?>(null)
        val clearLatch = AtomicReference<CountDownLatch?>(null)

        override fun configureStartupSubtitles(
            handle: Long,
            urls: Array<String>,
            titles: Array<String>,
            languages: Array<String>,
            selectedIndex: Int,
            disableAutoSelect: Boolean,
        ) {
            startupCalls += StartupCall(handle, urls.toList(), titles.toList(), languages.toList(), selectedIndex, disableAutoSelect)
            startupLatch.get()?.countDown()
        }

        override fun addSubtitleTrack(handle: Long, url: String, title: String, language: String, select: Boolean) {
            addCalls += AddCall(handle, url, title, language, select)
            addLatch.get()?.countDown()
        }

        override fun clearExternalSubtitles(handle: Long) {
            clearedHandles += handle
            clearLatch.get()?.countDown()
        }

        override fun clearExternalSubtitlesAndSelect(handle: Long, trackId: Int) = Unit
        override fun selectSubtitleTrack(handle: Long, trackId: Int) = Unit
        override fun subtitleTracksJson(handle: Long): String = "[]"
    }

    @Test
    fun startupConfigurationDecodesLocalPathsAndSelectsPreferredTrack() {
        val dir = Files.createTempDirectory("nuvio-npc-sub-start").toFile()
        try {
            val enFile = File(dir, "en sub.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nHi\n") }
            val esFile = File(dir, "es sub.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nHola\n") }
            val bridgeOps = RecordingSubtitleBridgeOps()
            val latch = CountDownLatch(1).also { bridgeOps.startupLatch.set(it) }
            val controller = NativePlayerController(
                host = NativePlayerHost(),
                nativeCreate = { _, _, _, _, _, _, _, _, _ -> 99L },
                nativeDispose = {},
                isHostDisplayable = { false },
                resolveHostView = { 1234L },
                subtitleBridgeOps = bridgeOps,
            )

            controller.attach(
                sourceUrl = "https://cdn.example.invalid/movie.mkv",
                sourceHeaders = emptyMap(),
                playWhenReady = true,
                initialPositionMs = 0L,
                decoderPriority = 0,
                nvidiaRtxSuperResolutionEnabled = false,
                externalSubtitles = listOf(
                    StreamSubtitle(url = enFile.toURI().toString(), language = "en", name = "English"),
                    StreamSubtitle(url = esFile.toURI().toString(), language = "es", name = "Spanish"),
                ),
                startupSubtitleSelection = StartupSubtitleSelection(selectedUrl = esFile.toURI().toString()),
                onError = {},
            )

            controller.setNativeHandleForTest(99L)
            controller.applyStartupSubtitlesIfNeeded(
                sourceUrl = "https://cdn.example.invalid/movie.mkv",
                externalSubtitles = listOf(
                    StreamSubtitle(url = enFile.toURI().toString(), language = "en", name = "English"),
                    StreamSubtitle(url = esFile.toURI().toString(), language = "es", name = "Spanish"),
                ),
                startupSubtitleSelection = StartupSubtitleSelection(selectedUrl = esFile.toURI().toString()),
            )

            assertTrue(latch.await(2, TimeUnit.SECONDS))
            val call = bridgeOps.startupCalls.single()
            assertEquals(99L, call.handle)
            assertEquals(listOf(enFile.absolutePath, esFile.absolutePath), call.urls)
            assertEquals(1, call.selectedIndex)
            assertFalse(call.disableAutoSelect)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun setSubtitleUriResolvesRemoteToLocalOffCallerThreadAndFallsBackToNoneOnBadFile() {
        val dir = Files.createTempDirectory("nuvio-npc-sub-ondemand").toFile()
        try {
            val cachedFile = File(dir, "cached_es.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nHola\n") }
            val bridgeOps = RecordingSubtitleBridgeOps()
            val resolvedCount = AtomicInteger(0)
            val gate = CompletableDeferred<Unit>()
            val controller = NativePlayerController(
                host = NativePlayerHost(),
                nativeCreate = { _, _, _, _, _, _, _, _, _ -> 77L },
                nativeDispose = {},
                resolvePlayableLocalSubtitle = { url, _, _ ->
                    gate.await()
                    resolvedCount.incrementAndGet()
                    if (url.endsWith("good.srt")) cachedFile.toURI().toString() else null
                },
                subtitleBridgeOps = bridgeOps,
            )
            controller.setNativeHandleForTest(77L)

            val addLatch = CountDownLatch(1).also { bridgeOps.addLatch.set(it) }
            controller.setSubtitleUri("https://subs.example.invalid/good.srt")

            // Must return immediately without blocking caller or handing remote URL to mpv.
            assertEquals(0, resolvedCount.get())
            assertTrue(bridgeOps.addCalls.isEmpty())

            gate.complete(Unit)
            assertTrue(addLatch.await(2, TimeUnit.SECONDS))
            val added = bridgeOps.addCalls.single()
            assertEquals(cachedFile.absolutePath, added.url)
            assertTrue(added.select)

            // Now test bad subtitle fallback -> clears external subtitles & notifies listener.
            val failedUrl = AtomicReference<String?>(null)
            val failedLatch = CountDownLatch(1)
            val clearLatch = CountDownLatch(1).also { bridgeOps.clearLatch.set(it) }
            controller.setOnExternalSubtitleLoadFailed { bad ->
                failedUrl.set(bad)
                failedLatch.countDown()
            }
            controller.setSubtitleUri("https://subs.example.invalid/bad.srt")

            assertTrue(clearLatch.await(2, TimeUnit.SECONDS))
            assertTrue(failedLatch.await(2, TimeUnit.SECONDS))
            assertEquals("https://subs.example.invalid/bad.srt", failedUrl.get())
            assertEquals(1, bridgeOps.addCalls.size)
            SwingUtilities.invokeAndWait {}
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun serverRetryAfterStartupTimeoutLoadsReplacementLocalSubtitles() =
        assertServerReplacementLoadsSubtitles(Replacement.RETRY, local = true)

    @Test
    fun serverRetryAfterStartupTimeoutLoadsReplacementRemoteSubtitles() =
        assertServerReplacementLoadsSubtitles(Replacement.RETRY, local = false)

    @Test
    fun serverAudioSwitchAfterStartupTimeoutLoadsReplacementLocalSubtitles() =
        assertServerReplacementLoadsSubtitles(Replacement.AUDIO, local = true)

    @Test
    fun serverAudioSwitchAfterStartupTimeoutLoadsReplacementRemoteSubtitles() =
        assertServerReplacementLoadsSubtitles(Replacement.AUDIO, local = false)

    @Test
    fun serverSubtitleSwitchAfterStartupTimeoutLoadsReplacementLocalSubtitles() =
        assertServerReplacementLoadsSubtitles(Replacement.SUBTITLE, local = true)

    @Test
    fun serverSubtitleSwitchAfterStartupTimeoutLoadsReplacementRemoteSubtitles() =
        assertServerReplacementLoadsSubtitles(Replacement.SUBTITLE, local = false)

    private enum class Replacement { RETRY, AUDIO, SUBTITLE }

    private fun assertServerReplacementLoadsSubtitles(replacement: Replacement, local: Boolean) = runTest {
        val dir = Files.createTempDirectory("nuvio-server-sub-replacement").toFile()
        val subtitleFile = File(dir, "replacement.srt").apply {
            writeText("1\n00:00:01,000 --> 00:00:02,000\nHello\n")
        }
        val replacementUrl = "https://fake.example/replacement.m3u8"
        val subtitleUrl = if (local) subtitleFile.toURI().toString() else "https://fake.example/replacement.srt"
        val subtitles = listOf(StreamSubtitle(url = subtitleUrl, language = "en", name = "English"))
        val provider = FakeServerProvider()
        val connection = installFakeServer(provider)
        var controller: NativePlayerController? = null
        var originalUrl: String? = null
        try {
            var prepareCount = 0
            ServerProviders.registered.removeAll { it.id == provider.id }
            ServerProviders.registered += object : ServerProvider by provider {
                override suspend fun preparePlayback(
                    session: ServerSession,
                    request: ServerPlaybackRequest,
                ): ServerPlaybackSession {
                    val initial = prepareCount++ == 0
                    return ServerPlaybackSession(
                        target = request.target,
                        mediaSourceId = "source",
                        url = if (initial) "https://fake.example/original.mkv" else replacementUrl,
                        headers = mapOf("X-Playback" to if (initial) "original" else "replacement"),
                        subtitles = if (initial) emptyList() else subtitles,
                        playSessionId = null,
                        playMethod = if (initial && replacement == Replacement.RETRY) {
                            ServerPlayMethod.DIRECT_PLAY
                        } else {
                            ServerPlayMethod.TRANSCODE
                        },
                    )
                }
            }
            val stream = ServerPlayback.prepare(ServerStreams.candidates(ServerItemRef(connection.id, "42")).single())
            originalUrl = requireNotNull(stream.playableDirectUrl)
            val runtime = PlayerScreenRuntime(
                PlayerScreenArgs(
                    profileId = 1,
                    title = "Movie",
                    sourceUrl = originalUrl,
                    sourceAudioUrl = null,
                    sourceHeaders = emptyMap(),
                    sourceResponseHeaders = emptyMap(),
                    streamType = null,
                    providerName = stream.addonName,
                    streamTitle = "Original",
                    streamSubtitle = null,
                    initialBingeGroup = null,
                    pauseDescription = null,
                    onBack = {},
                    onOpenInExternalPlayer = null,
                    onOpenExternalUrl = null,
                    modifier = Modifier,
                    logo = null,
                    poster = null,
                    background = null,
                    seasonNumber = null,
                    episodeNumber = null,
                    episodeTitle = null,
                    episodeThumbnail = null,
                    contentType = "movie",
                    videoId = "server-replacement",
                    parentMetaId = "server-replacement",
                    parentMetaType = "movie",
                    providerAddonId = stream.addonId,
                    startupSubtitleSelection = StartupSubtitleSelection(
                        disableAutoSelect = true,
                        exhaustedOrTimedOut = true,
                        failedUrls = setOf("https://fake.example/old.srt"),
                    ),
                    torrentInfoHash = null,
                    torrentFileIdx = null,
                    torrentFilename = null,
                    torrentTrackers = emptyList(),
                    initialPositionMs = 0L,
                    initialProgressFraction = null,
                ),
            ).apply {
                scope = this@runTest
                initialSeekApplied = true
                playbackSnapshot = PlayerPlaybackSnapshot(positionMs = 12_000L)
            }
            when (replacement) {
                Replacement.RETRY -> assertTrue(runtime.tryRefreshExpiredLinkAfterError("Playback failed"))
                Replacement.AUDIO -> runtime.selectServerAudioTrack(2)
                Replacement.SUBTITLE -> runtime.selectServerSubtitleTrack(3)
            }
            runCurrent()
            assertEquals(replacementUrl, runtime.activeSourceUrl)
            assertEquals(subtitles, runtime.externalSubtitles)
            assertEquals(mapOf("X-Playback" to "replacement"), runtime.activeSourceHeaders)
            assertEquals(12_000L, runtime.activeInitialPositionMs)

            val bridgeOps = RecordingSubtitleBridgeOps()
            val loaded = CountDownLatch(1)
            if (local) bridgeOps.startupLatch.set(loaded) else bridgeOps.addLatch.set(loaded)
            controller = NativePlayerController(
                host = NativePlayerHost(),
                nativeCreate = { _, _, _, _, _, _, _, _, _ -> 99L },
                nativeDispose = {},
                isHostDisplayable = { false },
                resolveHostView = { 1234L },
                resolvePlayableLocalSubtitle = { url, _, _ ->
                    assertEquals(subtitleUrl, url)
                    subtitleFile.toURI().toString()
                },
                subtitleBridgeOps = bridgeOps,
            )
            controller.attach(
                sourceUrl = runtime.activeSourceUrl,
                sourceHeaders = runtime.activeSourceHeaders,
                playWhenReady = true,
                initialPositionMs = runtime.activeInitialPositionMs,
                decoderPriority = 0,
                nvidiaRtxSuperResolutionEnabled = false,
                externalSubtitles = runtime.externalSubtitles,
                startupSubtitleSelection = runtime.startupSubtitleSelection,
                onError = { error(it ?: "Player creation failed") },
            )
            controller.setNativeHandleForTest(99L)
            controller.applyStartupSubtitlesIfNeeded(
                sourceUrl = runtime.activeSourceUrl,
                externalSubtitles = runtime.externalSubtitles,
                startupSubtitleSelection = runtime.startupSubtitleSelection,
            )
            assertTrue(loaded.await(2, TimeUnit.SECONDS), "Replacement subtitles must load after the old startup timed out")
            assertTrue(bridgeOps.startupCalls.none { it.disableAutoSelect }, "Old startup timeout must not turn captions off")
            if (local) {
                val call = bridgeOps.startupCalls.single()
                assertEquals(listOf(subtitleFile.absolutePath), call.urls)
                assertEquals(0, call.selectedIndex)
            } else {
                val call = bridgeOps.addCalls.single()
                assertEquals(subtitleFile.absolutePath, call.url)
                assertTrue(call.select)
            }
        } finally {
            controller?.releaseBeforeNavigation({}, {})
            SwingUtilities.invokeAndWait {}
            ServerPlayback.stop(originalUrl)
            ServerPlayback.stop(replacementUrl)
            removeFakeServer(connection.id)
            dir.deleteRecursively()
        }
    }

}
