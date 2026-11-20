package com.nuvio.app.features.player

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import com.nuvio.app.features.downloads.WatchedRemovalGuard
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.atomicfu.locks.synchronized
import org.junit.Rule

class LocalPlaybackReadinessTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun local_playback_waits_for_rollback_without_blocking_the_ui() = withRemovalPaused { release ->
        val ready = AtomicReference<String?>()
        compose.setContent {
            val source = rememberReadyPlayerSource("file:///downloads/episode.mkv")
            SideEffect { ready.set(source) }
        }
        compose.runOnIdle { assertNull(ready.get()) }
        release.countDown()
        compose.waitUntil(5_000) { ready.get() != null }
        assertEquals("file:///downloads/episode.mkv", ready.get())
    }

    @Test
    fun switching_sources_during_rollback_does_not_open_the_old_file() = withRemovalPaused { release ->
        val selected = mutableStateOf("file:///downloads/episode.mkv")
        val ready = AtomicReference<String?>()
        compose.setContent {
            val source = rememberReadyPlayerSource(selected.value)
            SideEffect { ready.set(source) }
        }
        compose.runOnIdle {
            assertNull(ready.get())
            selected.value = "https://example.com/video.mp4"
        }
        compose.runOnIdle { assertEquals(selected.value, ready.get()) }
        release.countDown()
        compose.waitForIdle()
        assertEquals(selected.value, ready.get())
    }

    private fun withRemovalPaused(test: (CountDownLatch) -> Unit) {
        val paused = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val removal = thread(isDaemon = true) {
            runCatching {
                synchronized(WatchedRemovalGuard.lock) {
                    paused.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                }
            }.onFailure { failure.set(it) }
        }
        try {
            assertTrue(paused.await(5, TimeUnit.SECONDS))
            test(release)
        } finally {
            release.countDown()
            removal.join(5_000)
        }
        assertFalse(removal.isAlive)
        assertNull(failure.get())
    }
}
