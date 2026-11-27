package com.nuvio.app.features.player.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativePlayerStartupResponsivenessTest {
    @Test
    fun playing_audio_with_blocked_video_lookup_does_not_block_the_window() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val backend = object : NativePlayerBackend by NoOpNativePlayerBackend {
            override fun stringProperty(handle: Long, name: String): String {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                return if (name.endsWith("/dw")) "640" else "360"
            }
        }
        val controller = NativePlayerController(
            host = NativePlayerHost(), backend = backend,
            isHostDisplayable = { false }, nativeDispose = {},
        )
        controller.assignNativeHandleForTest(1L)
        try {
            SwingUtilities.invokeLater {
                controller.snapshot()
                returned.countDown()
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS), "video lookup did not begin")
            assertTrue(returned.await(1, TimeUnit.SECONDS), "video lookup blocked the window while audio was playing")
            val snapshot = controller.snapshot(includeVideoSize = false)
            assertTrue(snapshot.isPlaying)
            assertFalse(snapshot.isLoading)
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (controller.snapshot(includeVideoSize = false).videoWidth == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertEquals(640, controller.snapshot(includeVideoSize = false).videoWidth)
        } finally {
            release.countDown()
            returned.await(2, TimeUnit.SECONDS)
            controller.dispose()
        }
    }
    @Test
    fun startup_timeout_reports_once_and_discards_a_late_native_player() {
        val now = java.util.concurrent.atomic.AtomicLong(0L)
        val created = CountDownLatch(1)
        val finishCreate = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        val error = CountDownLatch(1)
        val errors = java.util.concurrent.atomic.AtomicInteger()
        val controller = NativePlayerController(
            host = NativePlayerHost(),
            nativeCreate = { _, _, _, _, _, _, _, _, _, _ ->
                created.countDown()
                finishCreate.await(5, TimeUnit.SECONDS)
                12L
            },
            nativeDispose = { disposed.countDown() },
            isHostDisplayable = { true }, resolveHostView = { 1L },
            startupTimeoutMs = 100L, startupNowMs = now::get,
        )
        try {
            SwingUtilities.invokeAndWait {
                controller.attach("https://example.invalid/episode", emptyMap(), true, 0L, 0, false) {
                    if (it != null) { errors.incrementAndGet(); error.countDown() }
                }
            }
            assertTrue(created.await(2, TimeUnit.SECONDS))
            now.set(101L)
            SwingUtilities.invokeAndWait { controller.snapshot() }
            assertTrue(error.await(2, TimeUnit.SECONDS), "timed-out startup did not report an error")
            SwingUtilities.invokeAndWait { repeat(3) { controller.snapshot() } }
            assertEquals(1, errors.get())
            finishCreate.countDown()
            assertTrue(disposed.await(2, TimeUnit.SECONDS), "late player was accepted after startup failed")
            assertTrue(controller.snapshot().isLoading)
        } finally {
            finishCreate.countDown()
            controller.dispose()
        }
    }

    @Test
    fun close_waits_off_the_window_thread_for_a_video_lookup_before_disposing() {
        val entered = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        val backend = object : NativePlayerBackend by NoOpNativePlayerBackend {
            override fun stringProperty(handle: Long, name: String): String {
                entered.countDown()
                finishRead.await(5, TimeUnit.SECONDS)
                return "640"
            }
        }
        val controller = NativePlayerController(
            host = NativePlayerHost(), backend = backend,
            nativeDispose = { disposed.countDown() }, isHostDisplayable = { false },
        )
        controller.assignNativeHandleForTest(2L)
        try {
            SwingUtilities.invokeAndWait { controller.snapshot() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            SwingUtilities.invokeAndWait { controller.dispose() }
            assertFalse(disposed.await(100, TimeUnit.MILLISECONDS), "disposed a handle still being read")
            finishRead.countDown()
            assertTrue(disposed.await(2, TimeUnit.SECONDS))
        } finally {
            finishRead.countDown()
            controller.dispose()
        }
    }

    @Test
    fun slow_font_loading_stays_off_the_window_thread_and_keeps_the_latest_style() {
        val entered = CountDownLatch(1)
        val finishFont = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val applied = CountDownLatch(1)
        val fonts = java.util.Collections.synchronizedList(mutableListOf<String>())
        val backend = object : NativePlayerBackend by NoOpNativePlayerBackend {
            override fun setSubtitleAppearance(
                handle: Long, font: String, fontsDir: String, shadowColor: String,
                shadowOffset: Float, shadowBlur: Float,
            ) {
                fonts += font
                applied.countDown()
            }
        }
        val controller = NativePlayerController(
            host = NativePlayerHost(), backend = backend, nativeDispose = {},
            isHostDisplayable = { false },
            resolveSubtitleFont = { id ->
                if (id == "default") {
                    entered.countDown()
                    finishFont.await(5, TimeUnit.SECONDS)
                }
                id to ""
            },
        )
        controller.assignNativeHandleForTest(3L)
        try {
            val style = com.nuvio.app.features.player.SubtitleStyleState()
            SwingUtilities.invokeLater {
                controller.applySubtitleStyle(style, true)
                returned.countDown()
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(returned.await(1, TimeUnit.SECONDS), "font loading blocked the window")
            SwingUtilities.invokeAndWait {
                controller.applySubtitleStyle(style.copy(appearance = style.appearance.copy(fontId = "serif")), true)
            }
            finishFont.countDown()
            assertTrue(applied.await(2, TimeUnit.SECONDS))
            SwingUtilities.invokeAndWait { }
            assertEquals(listOf("serif"), fonts.toList())
        } finally {
            finishFont.countDown()
            controller.dispose()
        }
    }

    @Test
    fun a_previous_sources_playing_handle_cannot_finish_the_new_startup() {
        val now = java.util.concurrent.atomic.AtomicLong(0L)
        val error = CountDownLatch(1)
        val controller = NativePlayerController(
            host = NativePlayerHost(), backend = NoOpNativePlayerBackend,
            isHostDisplayable = { false }, nativeDispose = {},
            startupTimeoutMs = 100L, startupNowMs = now::get,
        )
        try {
            SwingUtilities.invokeAndWait {
                controller.attach("https://example.invalid/new-episode", emptyMap(), true, 0L, 0, false) {
                    if (it != null) error.countDown()
                }
                controller.assignNativeHandleForTest(4L)
                assertTrue(controller.snapshot().isLoading, "the previous source cleared the new startup")
            }
            now.set(101L)
            SwingUtilities.invokeAndWait { controller.snapshot() }
            assertTrue(error.await(2, TimeUnit.SECONDS), "the old handle disabled the new startup deadline")
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun late_startup_subtitle_file_checks_do_not_wait_on_the_window_thread() {
        val entered = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val configured = CountDownLatch(1)
        val backend = object : NativePlayerBackend by NoOpNativePlayerBackend {
            override fun configureStartupSubtitles(
                handle: Long, urls: Array<String>, titles: Array<String>, languages: Array<String>,
                selectedIndex: Int, disableAutoSelect: Boolean,
            ) { configured.countDown() }
        }
        val controller = NativePlayerController(
            host = NativePlayerHost(), backend = backend, nativeDispose = {},
            isHostDisplayable = { false },
            resolveLocalFilePath = {
                entered.countDown()
                finishRead.await(5, TimeUnit.SECONDS)
                "/subtitle-test/episode.srt"
            },
        )
        try {
            SwingUtilities.invokeAndWait {
                controller.attach("https://example.invalid/episode", emptyMap(), true, 0L, 0, false) {}
                controller.assignNativeHandleForTest(5L)
            }
            SwingUtilities.invokeLater {
                controller.applyStartupSubtitlesIfNeeded(
                    "https://example.invalid/episode",
                    listOf(com.nuvio.app.features.streams.StreamSubtitle("file:///subtitle-test/episode.srt", "en")),
                    null,
                )
                returned.countDown()
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(returned.await(1, TimeUnit.SECONDS), "subtitle file checks blocked the window")
            finishRead.countDown()
            assertTrue(configured.await(2, TimeUnit.SECONDS))
        } finally {
            finishRead.countDown()
            controller.dispose()
        }
    }

}
