package com.nuvio.app.features.downloads

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.streams.rememberPlaybackAvailability
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DownloadProgressRedrawTest {
    @get:Rule val compose = createComposeRule()
    private val backend = ProgressBackend()
    private val rowVisits = mutableMapOf<String, Int>()
    private val runtime: (DownloadItem) -> Int? = { item ->
        rowVisits[item.id] = (rowVisits[item.id] ?: 0) + 1
        null
    }
    private val original = DownloadsRepository.backend
    private val originalCompanions = DownloadsRepository.companions
    private val originalScope = DownloadsRepository.progressScope
    @Before fun setup() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        DownloadsRepository.companions = { DownloadCompanions(emptyList()) }
        DownloadsRepository.progressScope = TestScope().backgroundScope
        val movie = progressItem("Finished movie", DownloadStatus.Completed)
        val episode = progressItem("Finished episode", DownloadStatus.Completed).copy(
            contentType = "series", parentMetaType = "series", parentMetaId = "show", seasonNumber = 1, episodeNumber = 1,
        )
        val running = progressItem("Running").copy(
            contentType = "series", parentMetaType = "series", parentMetaId = "show", seasonNumber = 1, episodeNumber = 2,
        )
        backend.payload = DownloadsCodec.encode(listOf(running, movie, episode), null)
        DownloadsRepository.ensureLoaded()
        compose.mainClock.autoAdvance = false
    }
    @After fun cleanup() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = original
        DownloadsRepository.companions = originalCompanions
        DownloadsRepository.progressScope = originalScope
    }
    private fun ticks() {
        repeat(8) { n ->
            compose.runOnIdle { backend.progress.getValue("Running")((n + 1L) * 123, 10000) }
            compose.mainClock.advanceTimeBy(250)
            compose.waitForIdle()
        }
        compose.mainClock.advanceTimeBy(5100)
        compose.waitForIdle()
    }

    @Test fun finished_rows_stay_unchanged_while_the_running_row_shows_progress() {
        compose.setContent { NuvioTheme {
            DownloadsScreenContent(onBack = {}, onOpenDownload = {}, showAutoDownloads = false, runtimeMinutes = runtime)
        } }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        val before = compose.runOnIdle { rowVisits["Finished movie"] ?: 0 }
        assertTrue(before > 0)
        ticks()
        assertEquals(before, compose.runOnIdle { rowVisits["Finished movie"] ?: 0 })
        compose.onAllNodesWithText("984 B", substring = true).assertCountEquals(1)
    }

    @Test fun open_show_episodes_stay_unchanged_across_progress_and_clock_ticks() {
        compose.setContent { NuvioTheme {
            DownloadsScreenContent(onBack = {}, onOpenDownload = {}, initialShowId = "show", showAutoDownloads = false, runtimeMinutes = runtime)
        } }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        val before = compose.runOnIdle { rowVisits["Finished episode"] ?: 0 }
        assertTrue(before > 0)
        ticks()
        assertEquals(before, compose.runOnIdle { rowVisits["Finished episode"] ?: 0 })
        compose.onAllNodesWithText("984 B", substring = true).assertCountEquals(1)
    }

    @Test fun watched_show_summary_includes_live_partial_bytes_without_redrawing_finished_rows() {
        DownloadsRepository.clearLocalState()
        fun episode(id: String, number: Int, status: DownloadStatus, bytes: Long) = progressItem(id, status).copy(
            contentType = "series", parentMetaType = "series", parentMetaId = "show", seasonNumber = 1,
            episodeNumber = number, downloadedBytes = bytes,
        )
        backend.payload = DownloadsCodec.encode(listOf(
            episode("Finished episode", 1, DownloadStatus.Completed, 512),
            episode("Running", 2, DownloadStatus.Downloading, 128),
            episode("Paused", 3, DownloadStatus.Paused, 256),
            episode("Other show", 1, DownloadStatus.Downloading, 9000).copy(parentMetaId = "other-show"),
        ), null)
        DownloadsRepository.ensureLoaded()
        val watched = WatchedItem(id = "show", type = "series", name = "Show", season = 1, episode = 1, markedAtEpochMs = 1)
        val alreadyWatched = WatchedRepository.isWatched("show", "series", 1, 1)
        try {
            if (!alreadyWatched) WatchedRepository.markWatchedLocally(listOf(watched))
            compose.setContent { NuvioTheme {
                DownloadsScreenContent(onBack = {}, onOpenDownload = {}, initialShowId = "show", showAutoDownloads = false, runtimeMinutes = runtime)
            } }
            compose.mainClock.advanceTimeBy(1000)
            compose.waitForIdle()
            compose.onAllNodesWithText(" • 896 B • ", substring = true).assertCountEquals(1)
            val before = compose.runOnIdle { rowVisits["Finished episode"] ?: 0 }
            assertTrue(before > 0)
            ticks()
            // 512 + 984 + 256 = 1,752 B = 1.8 KB in decimal units.
            compose.onAllNodesWithText(" • 1.8 KB • ", substring = true).assertCountEquals(1)
            assertEquals(before, compose.runOnIdle { rowVisits["Finished episode"] ?: 0 })
            compose.runOnIdle { backend.progress.getValue("Other show")(9500, 10000) }
            compose.mainClock.advanceTimeBy(250)
            compose.waitForIdle()
            compose.onAllNodesWithText(" • 1.8 KB • ", substring = true).assertCountEquals(1)
            assertEquals(before, compose.runOnIdle { rowVisits["Finished episode"] ?: 0 })
        } finally {
            if (!alreadyWatched) WatchedRepository.unmarkWatchedLocally(listOf(watched))
        }
    }

    @Test fun production_playback_subscription_ignores_bytes_and_observes_completion() {
        var compositions = 0
        compose.setContent {
            rememberPlaybackAvailability()
            SideEffect { compositions++ }
            Text("Playback")
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        val before = compose.runOnIdle { compositions }
        ticks()
        assertEquals(before, compose.runOnIdle { compositions })
        compose.runOnIdle { backend.success.getValue("Running")("file:/Running.mp4", null) }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        assertTrue(compose.runOnIdle { compositions } > before)
    }
}
