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
            startupTimeoutMs = 100L, startupNowMs = now::get, startupIsOffline = { false },
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
            startupTimeoutMs = 100L, startupNowMs = now::get, startupIsOffline = { false },
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

    @Test
    fun unchanged_unknown_video_size_never_signals_another_snapshot() {
        val read = CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val backend = object : NativePlayerBackend by NoOpNativePlayerBackend {
            override fun stringProperty(handle: Long, name: String): String { calls.incrementAndGet(); read.countDown(); return "" }
        }
        val controller = NativePlayerController(NativePlayerHost(), backend = backend, nativeDispose = {}, startupNowMs = { 0L })
        controller.assignNativeHandleForTest(70L)
        try {
            controller.snapshot(includeVideoSize = false)
            assertTrue(read.await(5, TimeUnit.SECONDS))
            val completed = CountDownLatch(1)
            controller.releaseBeforeNavigation({ completed.countDown() }, { error(it) })
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(0L, controller.manualRefreshTick.value, "an empty size must not wake paused or minimized playback")
            assertTrue(calls.get() <= 2)
        } finally { controller.dispose() }
    }

    @Test
    fun a_create_error_is_terminal_for_the_startup_deadline() {
        val now = java.util.concurrent.atomic.AtomicLong(0L)
        val failed = CountDownLatch(1)
        val errors = java.util.concurrent.atomic.AtomicInteger()
        val controller = NativePlayerController(NativePlayerHost(),
            nativeCreate = { _, _, _, _, _, _, _, _, _, _ -> error("Cannot start") }, nativeDispose = {},
            isHostDisplayable = { true }, resolveHostView = { 1L }, startupNowMs = now::get,
            startupTimeoutMs = 100L, startupIsOffline = { false })
        try {
            controller.attach("https://example.invalid/a", emptyMap(), true, 0L, 0, false) {
                errors.incrementAndGet(); failed.countDown()
            }
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            now.set(101L)
            SwingUtilities.invokeAndWait { repeat(10) { controller.snapshot() } }
            assertEquals(1, errors.get())
        } finally { controller.dispose() }
    }

    @Test
    fun host_resolution_errors_are_terminal_for_the_startup_deadline() {
        val now = java.util.concurrent.atomic.AtomicLong(0L)
        val failed = CountDownLatch(1)
        val errors = java.util.concurrent.atomic.AtomicInteger()
        val controller = NativePlayerController(NativePlayerHost(), backend = NoOpNativePlayerBackend, nativeDispose = {},
            isHostDisplayable = { true }, resolveHostView = { error("Cannot open the video surface") },
            startupNowMs = now::get, startupTimeoutMs = 100L, startupIsOffline = { false })
        try {
            controller.attach("https://example.invalid/a", emptyMap(), true, 0L, 0, false) {
                errors.incrementAndGet(); failed.countDown()
            }
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            now.set(101L)
            SwingUtilities.invokeAndWait { repeat(10) { controller.snapshot() } }
            assertEquals(1, errors.get())
        } finally { controller.dispose() }
    }

    @Test
    fun native_playback_errors_are_terminal_for_the_startup_deadline() {
        val now = java.util.concurrent.atomic.AtomicLong(0L)
        val failed = CountDownLatch(1)
        val errors = java.util.concurrent.atomic.AtomicInteger()
        val controller = NativePlayerController(NativePlayerHost(), backend = NoOpNativePlayerBackend, nativeDispose = {},
            isHostDisplayable = { false }, startupNowMs = now::get, startupTimeoutMs = 100L, startupIsOffline = { false })
        try {
            controller.attach("https://example.invalid/a", emptyMap(), true, 0L, 0, false) {
                errors.incrementAndGet(); failed.countDown()
            }
            controller.javaClass.getDeclaredMethod("handlePlayerEvent", String::class.java, Double::class.javaPrimitiveType)
                .also { it.isAccessible = true }.invoke(controller, "playbackFailed", 0.0)
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            now.set(101L)
            SwingUtilities.invokeAndWait { repeat(10) { controller.snapshot() } }
            assertEquals(1, errors.get())
        } finally { controller.dispose() }
    }

    @Test
    fun pause_offline_and_sleep_gaps_do_not_consume_the_startup_budget() {
        val now = java.util.concurrent.atomic.AtomicLong(0L)
        var offline = false
        val failed = CountDownLatch(1)
        val controller = NativePlayerController(NativePlayerHost(), backend = NoOpNativePlayerBackend, nativeDispose = {},
            isHostDisplayable = { false }, startupNowMs = now::get, startupTimeoutMs = 100L, startupIsOffline = { offline })
        try {
            controller.attach("https://example.invalid/a", emptyMap(), false, 0L, 0, false) { failed.countDown() }
            now.set(200L); controller.snapshot()
            controller.play()
            offline = true
            now.set(400L); controller.snapshot()
            offline = false
            now.set(10_000L); controller.snapshot()
            assertEquals(1L, failed.count, "a held or suspended startup must not expire")
            now.set(10_101L); controller.snapshot()
            assertTrue(failed.await(5, TimeUnit.SECONDS))
        } finally { controller.dispose() }
    }

    @Test
    fun closing_during_startup_subtitle_lookup_never_frees_the_creating_handle() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        val freed = java.util.concurrent.atomic.AtomicBoolean(false)
        val usesAfterFree = java.util.concurrent.atomic.AtomicInteger()
        val backend = object : NativePlayerBackend by NoOpNativePlayerBackend {
            override fun configureStartupSubtitles(handle: Long, urls: Array<String>, titles: Array<String>, languages: Array<String>, selectedIndex: Int, disableAutoSelect: Boolean) {
                if (freed.get()) usesAfterFree.incrementAndGet()
            }
        }
        val controller = NativePlayerController(NativePlayerHost(), backend = backend,
            nativeCreate = { _, _, _, _, _, _, _, _, _, _ -> 71L },
            nativeDispose = { freed.set(true); disposed.countDown() }, isHostDisplayable = { true }, resolveHostView = { 1L },
            resolveLocalFilePath = { entered.countDown(); finish.await(); "/startup-test/sub.srt" })
        try {
            controller.attach("https://example.invalid/a", emptyMap(), true, 0L, 0, false,
                externalSubtitles = listOf(com.nuvio.app.features.streams.StreamSubtitle("file:/startup-test/sub.srt", "en")), onError = {})
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            SwingUtilities.invokeAndWait { controller.dispose(); controller.dispose() }
            assertFalse(freed.get())
            finish.countDown()
            assertTrue(disposed.await(5, TimeUnit.SECONDS))
            assertEquals(0, usesAfterFree.get())
        } finally { finish.countDown(); controller.dispose() }
    }

}
