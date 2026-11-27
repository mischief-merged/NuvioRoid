package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlaybackStallDetector
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Arrays
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class MacosNativeMiniPlayerBridgeTest {

    /** Builds a 32-second 64x48 10fps YUV4MPEG2 video stream in memory with a 6s initial split point. */
    private fun buildTestY4mBytes(
        width: Int = 64,
        height: Int = 48,
        fps: Int = 10,
        durationSeconds: Int = 32,
        initialSeconds: Int = 6,
    ): Pair<ByteArray, Int> {
        val header = "YUV4MPEG2 W$width H$height F$fps:1 Ip A1:1 C420\n".toByteArray(Charsets.US_ASCII)
        val frameTag = "FRAME\n".toByteArray(Charsets.US_ASCII)
        val lumaSize = width * height
        val chromaSize = (width / 2) * (height / 2)
        val totalFrames = fps * durationSeconds
        val initialFrames = fps * initialSeconds
        val out = ByteArrayOutputStream(header.size + totalFrames * (frameTag.size + lumaSize + chromaSize * 2))
        out.write(header)
        var initialCutOffset = 0
        val planeBuffer = ByteArray(lumaSize + chromaSize * 2)
        Arrays.fill(planeBuffer, lumaSize, planeBuffer.size, 128.toByte())
        for (frame in 0 until totalFrames) {
            for (y in 0 until height) {
                val base = y * width
                for (x in 0 until width) {
                    planeBuffer[base + x] = (32 + ((x * 3 + y + frame * 7) and 0x7F)).toByte()
                }
            }
            out.write(frameTag)
            out.write(planeBuffer)
            if (frame + 1 == initialFrames) {
                initialCutOffset = out.size()
            }
        }
        return out.toByteArray() to initialCutOffset
    }

    @Test
    fun invalid_media_reports_failure_without_waiting_for_the_startup_deadline() {
        assumeTrue(DesktopHostOs.current == DesktopHostOs.MACOS)
        NativePlayerBridge.ensureLoaded()
        val failed = CountDownLatch(1)
        val handle = NativePlayerBridge.createHeadlessMacosForTest(
            "/missing-playback-test/episode.mkv", emptyArray(), true, 0L, true,
            NativePlayerEventSink { type, _ -> if (type == "playbackFailed") failed.countDown() },
        )
        try {
            assertTrue(failed.await(3, TimeUnit.SECONDS), "native media failure was not reported")
        } finally {
            RealNativePlayerBackend.dispose(handle)
        }
    }

    @Test
    fun native_surface_renders_first_frame_and_remains_responsive_through_subtitle_setup() {
        assumeTrue(DesktopHostOs.current == DesktopHostOs.MACOS)
        assumeTrue(!java.awt.GraphicsEnvironment.isHeadless())
        NativePlayerBridge.ensureLoaded()
        val video = java.nio.file.Files.createTempFile("startup-surface-", ".y4m").toFile()
        video.writeBytes(buildTestY4mBytes(durationSeconds = 8).first)
        val host = NativePlayerHost()
        val frame = javax.swing.JFrame("Playback test")
        var handle = 0L
        try {
            var pointer = 0L
            javax.swing.SwingUtilities.invokeAndWait {
                frame.add(host)
                frame.setSize(400, 300)
                frame.isVisible = true
                pointer = AwtNativeViewResolver.resolveNativeViewPointer(host)
            }
            handle = RealNativePlayerBackend.create(
                pointer, video.absolutePath, emptyArray(), true, 0L,
                RealNativePlayerBackend.controlsPageUrl(), 2, false, false,
                NativePlayerEventSink { _, _ -> }, remoteCommandsEnabled = false,
            )
            RealNativePlayerBackend.applySubtitleStyle(
                handle, "#FFFFFFFF", "#00000000", "#FF000000", 1f, false, 24f, 100, true, false,
            )
            RealNativePlayerBackend.setSubtitleAppearance(handle, "sans-serif", "", "#00000000", 0f, 0f)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (RealNativePlayerBackend.isLoading(handle) && System.nanoTime() < deadline) Thread.sleep(20)
            assertFalse(RealNativePlayerBackend.isLoading(handle), "startup did not finish")
            assertTrue(NativePlayerBridge.hasRenderedFrameForTest(handle), "startup cleared before a frame was rendered")
            val pulse = CountDownLatch(1)
            javax.swing.SwingUtilities.invokeLater { pulse.countDown() }
            assertTrue(pulse.await(1, TimeUnit.SECONDS), "window stopped responding")
        } finally {
            if (handle != 0L) RealNativePlayerBackend.dispose(handle)
            javax.swing.SwingUtilities.invokeAndWait { frame.dispose() }
            video.delete()
        }
    }

    @Test
    fun stalled_subtitle_reader_keeps_pause_and_ui_state_responsive() {
        assumeTrue(DesktopHostOs.current == DesktopHostOs.MACOS)
        NativePlayerBridge.ensureLoaded()
        val video = java.nio.file.Files.createTempFile("startup-video-", ".y4m").toFile()
        video.writeBytes(buildTestY4mBytes(durationSeconds = 8).first)
        val requested = CountDownLatch(1)
        val release = CountDownLatch(1)
        val paused = CountDownLatch(1)
        val listenForPause = AtomicBoolean(false)
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val worker = Thread({
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    requested.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    val body = "1\n00:00:00,000 --> 00:00:05,000\nTest\n"
                    socket.getOutputStream().write(
                        ("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n" + body).toByteArray(),
                    )
                }
            }
        }, "startup-subtitle-server").apply { isDaemon = true; start() }
        var handle = 0L
        try {
            handle = NativePlayerBridge.createHeadlessMacosForTest(
                video.absolutePath, emptyArray(), true, 0L, true,
                NativePlayerEventSink { type, _ ->
                    if (type == "stateSynchronized" && listenForPause.get()) paused.countDown()
                },
            )
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (RealNativePlayerBackend.isLoading(handle) && System.nanoTime() < deadline) Thread.sleep(20)
            assertFalse(RealNativePlayerBackend.isLoading(handle), "fixture must be playing before subtitle loading")
            RealNativePlayerBackend.addSubtitleUrl(handle, "http://127.0.0.1:${server.localPort}/slow.srt")
            assertTrue(requested.await(5, TimeUnit.SECONDS), "subtitle request did not reach the server")
            listenForPause.set(true)
            RealNativePlayerBackend.setPaused(handle, true)
            assertTrue(paused.await(1, TimeUnit.SECONDS), "pause blocked the player UI behind subtitle loading")
            assertTrue(RealNativePlayerBackend.isPaused(handle))
        } finally {
            release.countDown()
            server.close()
            worker.join(2_000)
            if (handle != 0L) RealNativePlayerBackend.dispose(handle)
            video.delete()
        }
    }

    @Test
    fun minimized_native_player_sync_observes_demuxer_cache_growth_preserves_focus_and_updates_rate() {
        assumeTrue("macOS native player bridge test only runs on macOS", DesktopHostOs.current == DesktopHostOs.MACOS)
        NativePlayerBridge.ensureLoaded()

        val (videoBytes, initialCutBytes) = buildTestY4mBytes(durationSeconds = 32, initialSeconds = 6)
        val allowFullStream = AtomicBoolean(false)
        val headersSent = CountDownLatch(1)
        val releaseInitialChunk = CountDownLatch(1)
        val firstChunkSent = CountDownLatch(1)
        val releaseRemainder = CountDownLatch(1)
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "mini-bridge-http").apply { isDaemon = true }
        }

        executor.execute {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                executor.execute {
                    client.use { socket ->
                        socket.soTimeout = 25_000
                        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val requestLine = reader.readLine() ?: return@use
                        while (true) {
                            val header = reader.readLine() ?: break
                            if (header.isEmpty()) break
                        }
                        if (!requestLine.startsWith("GET ")) return@use
                        val out = socket.getOutputStream()
                        val headers = buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            append("Content-Type: application/octet-stream\r\n")
                            append("Accept-Ranges: none\r\n")
                            append("Content-Length: ${videoBytes.size}\r\n")
                            append("Connection: close\r\n\r\n")
                        }
                        out.write(headers.toByteArray(Charsets.ISO_8859_1))
                        out.flush()
                        if (allowFullStream.get()) {
                            out.write(videoBytes)
                            out.flush()
                        } else {
                            headersSent.countDown()
                            releaseInitialChunk.await(15, TimeUnit.SECONDS)
                            out.write(videoBytes, 0, initialCutBytes)
                            out.flush()
                            firstChunkSent.countDown()
                            releaseRemainder.await(20, TimeUnit.SECONDS)
                            if (videoBytes.size > initialCutBytes) {
                                out.write(videoBytes, initialCutBytes, videoBytes.size - initialCutBytes)
                                out.flush()
                            }
                        }
                    }
                }
            }
        }

        var handle = 0L
        var fullModeHandle = 0L
        val stateSyncLatchRef = AtomicReference<CountDownLatch?>(null)
        try {
            val streamUrl = "http://127.0.0.1:${server.localPort}/episode.y4m"
            handle = NativePlayerBridge.createHeadlessMacosForTest(
                sourceUrl = streamUrl,
                headerLines = emptyArray(),
                playWhenReady = true,
                initialPositionMs = 0L,
                inMiniPlayer = true,
                eventSink = NativePlayerEventSink { type, _ ->
                    if (type == "stateSynchronized") {
                        stateSyncLatchRef.get()?.countDown()
                    }
                },
            )
            assertTrue(handle != 0L, "expected native MpvWebPlayer handle")

            // Created in mini mode: 2.0 s periodic sync is active immediately and initial focus is suppressed.
            assertEquals(2.0, NativePlayerBridge.activeSyncTimerIntervalSecondsForTest(handle), 0.05)
            assertFalse(NativePlayerBridge.attemptedInitialFocusForTest(handle))

            assertTrue(headersSent.await(10, TimeUnit.SECONDS), "mpv did not connect to local stream")
            assertFalse(RealNativePlayerBackend.isPaused(handle))
            assertTrue(RealNativePlayerBackend.isLoading(handle))
            assertEquals(
                0.0,
                NativePlayerBridge.lastNowPlayingRateForTest(handle),
                0.001,
                "Now Playing playback rate must stay 0.0 while loading even when unpaused",
            )

            // Deliver the first 6s of frames while staying in mini mode: 2.0 s sync sees initial buffer and 1.0 rate.
            releaseInitialChunk.countDown()
            assertTrue(firstChunkSent.await(10, TimeUnit.SECONDS), "initial 6s video chunk was not sent")

            var initialBufferedMs = 0L
            val firstSyncDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (System.nanoTime() < firstSyncDeadline) {
                val buffered = RealNativePlayerBackend.bufferedPositionMs(handle)
                val rate = NativePlayerBridge.lastNowPlayingRateForTest(handle)
                if (buffered >= 2_500L && rate >= 0.99) {
                    initialBufferedMs = buffered
                    break
                }
                Thread.sleep(150L)
            }
            assertTrue(
                initialBufferedMs in 2_500L..10_000L,
                "expected initial ~6s partial demuxer cache while minimized, got ${initialBufferedMs}ms",
            )
            assertEquals(1.0, NativePlayerBridge.lastNowPlayingRateForTest(handle), 0.05)
            assertFalse(NativePlayerBridge.attemptedInitialFocusForTest(handle))

            // Let playback exhaust the 6s chunk while the HTTP stream stays open: rebuffering drops Now Playing rate to 0.0.
            var stalledMidStream = false
            val midStallDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < midStallDeadline) {
                if (
                    !RealNativePlayerBackend.isPaused(handle) &&
                    RealNativePlayerBackend.isLoading(handle) &&
                    abs(NativePlayerBridge.lastNowPlayingRateForTest(handle)) < 0.01
                ) {
                    stalledMidStream = true
                    break
                }
                Thread.sleep(150L)
            }
            assertTrue(stalledMidStream, "expected mid-stream rebuffering stall to publish 0.0 Now Playing rate")

            // Release the remaining 26s of frames while still in mini mode: 2.0 s native sync observes demuxer-cache-time growth.
            allowFullStream.set(true)
            releaseRemainder.countDown()

            var grownBufferedMs = initialBufferedMs
            val growthDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (System.nanoTime() < growthDeadline) {
                grownBufferedMs = RealNativePlayerBackend.bufferedPositionMs(handle)
                if (
                    grownBufferedMs >= 22_000L &&
                    grownBufferedMs >= initialBufferedMs + 14_000L &&
                    NativePlayerBridge.lastNowPlayingRateForTest(handle) >= 0.99
                ) {
                    break
                }
                Thread.sleep(150L)
            }
            assertTrue(
                grownBufferedMs >= 22_000L && grownBufferedMs >= initialBufferedMs + 14_000L,
                "expected native minimized bufferedPositionMs to grow past 22s from ${initialBufferedMs}ms, got ${grownBufferedMs}ms",
            )
            assertEquals(1.0, NativePlayerBridge.lastNowPlayingRateForTest(handle), 0.05)
            assertEquals(2.0, NativePlayerBridge.activeSyncTimerIntervalSecondsForTest(handle), 0.05)
            assertFalse(NativePlayerBridge.attemptedInitialFocusForTest(handle))

            // Feeding the real native bufferedPositionMs growth into PlaybackStallDetector holds off the 20s short deadline.
            var stallState = PlaybackStallDetector.initial(
                nowMs = 0L,
                positionMs = 6_000L,
                bufferedPositionMs = initialBufferedMs,
            )
            for (stepIndex in 1..12) {
                val nowMs = stepIndex * 2_000L
                val interpolatedBuffer = initialBufferedMs +
                    ((grownBufferedMs - initialBufferedMs) * stepIndex) / 12
                val step = PlaybackStallDetector.step(
                    stallState,
                    PlaybackStallDetector.Sample(
                        nowMs = nowMs,
                        positionMs = 6_000L,
                        bufferedPositionMs = interpolatedBuffer,
                        durationMs = 32_000L,
                        isLoading = true,
                        isPlaying = false,
                        wantsToPlay = true,
                        isEnded = false,
                        isUserSeeking = false,
                        isOffline = false,
                    ),
                )
                assertIs<PlaybackStallDetector.Step.Continue>(step, "buffer growth at ${nowMs}ms must not stall at 20s")
                stallState = step.state
            }

            // Pausing while minimized turns the native 2.0 s sync timer completely off (0.0 s) and signals completion.
            val pauseLatch = CountDownLatch(1)
            stateSyncLatchRef.set(pauseLatch)
            RealNativePlayerBackend.setPaused(handle, true)
            assertTrue(pauseLatch.await(3, TimeUnit.SECONDS), "expected stateSynchronized after native pause")
            assertTrue(RealNativePlayerBackend.isPaused(handle))
            assertEquals(
                0.0,
                NativePlayerBridge.activeSyncTimerIntervalSecondsForTest(handle),
                0.01,
                "paused mini player must turn off the native periodic sync timer",
            )
            assertEquals(0.0, NativePlayerBridge.lastNowPlayingRateForTest(handle), 0.01)

            // Seeking while paused in mini mode updates cached position before firing stateSynchronized and keeps sync off.
            val positionBeforeSeekMs = RealNativePlayerBackend.positionMs(handle)
            val seekLatch = CountDownLatch(1)
            stateSyncLatchRef.set(seekLatch)
            RealNativePlayerBackend.seekBy(handle, 10_000L)
            assertTrue(seekLatch.await(3, TimeUnit.SECONDS), "expected stateSynchronized after paused native seek")
            val positionAtSyncSignalMs = RealNativePlayerBackend.positionMs(handle)
            assertTrue(
                positionAtSyncSignalMs >= positionBeforeSeekMs + 9_000L,
                "expected cached position to advance by ~10s before stateSynchronized (was ${positionBeforeSeekMs}ms, got ${positionAtSyncSignalMs}ms)",
            )
            assertEquals(
                0.0,
                NativePlayerBridge.activeSyncTimerIntervalSecondsForTest(handle),
                0.01,
                "paused seek must leave native periodic sync off",
            )

            // Resuming playback in mini mode restarts the 2.0 s periodic sync timer.
            val resumeLatch = CountDownLatch(1)
            stateSyncLatchRef.set(resumeLatch)
            RealNativePlayerBackend.setPaused(handle, false)
            assertTrue(resumeLatch.await(3, TimeUnit.SECONDS), "expected stateSynchronized after native resume")
            assertEquals(2.0, NativePlayerBridge.activeSyncTimerIntervalSecondsForTest(handle), 0.05)

            // Reopening from mini mode restores 0.5 s sync and triggers initial focus on the player.
            RealNativePlayerBackend.setMiniPlayerMode(handle, false)
            val reopenDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (System.nanoTime() < reopenDeadline) {
                if (
                    abs(NativePlayerBridge.activeSyncTimerIntervalSecondsForTest(handle) - 0.5) < 0.05 &&
                    NativePlayerBridge.attemptedInitialFocusForTest(handle)
                ) {
                    break
                }
                Thread.sleep(100L)
            }
            assertEquals(0.5, NativePlayerBridge.activeSyncTimerIntervalSecondsForTest(handle), 0.05)
            assertTrue(NativePlayerBridge.attemptedInitialFocusForTest(handle))

            // Creating a player directly in full mode (inMiniPlayer = false) starts at 0.5 s sync and attempts initial focus.
            fullModeHandle = NativePlayerBridge.createHeadlessMacosForTest(
                sourceUrl = streamUrl,
                headerLines = emptyArray(),
                playWhenReady = false,
                initialPositionMs = 0L,
                inMiniPlayer = false,
                eventSink = NativePlayerEventSink { _, _ -> },
            )
            assertTrue(fullModeHandle != 0L)
            assertEquals(0.5, NativePlayerBridge.activeSyncTimerIntervalSecondsForTest(fullModeHandle), 0.05)
            assertTrue(NativePlayerBridge.attemptedInitialFocusForTest(fullModeHandle))
        } finally {
            allowFullStream.set(true)
            releaseInitialChunk.countDown()
            releaseRemainder.countDown()
            runCatching { server.close() }
            executor.shutdownNow()
            if (fullModeHandle != 0L) {
                RealNativePlayerBackend.dispose(fullModeHandle)
            }
            if (handle != 0L) {
                RealNativePlayerBackend.dispose(handle)
            }
        }
    }
}
