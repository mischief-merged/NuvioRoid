package com.nuvio.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.testDownload
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.player.StartupSubtitleDependencies
import com.nuvio.app.features.player.StartupSubtitlePreparer
import com.nuvio.app.features.player.SubtitleStyleState
import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackPreparationSubtitleTest {
    @get:Rule
    val compose = createComposeRule()

    private val subtitleSettings = PlayerSettingsUiState(
        subtitleStyle = SubtitleStyleState.DEFAULT.copy(useForcedSubtitles = false),
        preferredSubtitleLanguage = "en",
    )

    private val validSrt = "1\n00:00:01,000 --> 00:00:03,000\nHello\n"

    private fun slowPreparer(
        onReadEntered: (String) -> Unit,
        gateForUri: (String) -> CompletableDeferred<Unit>,
    ): StartupSubtitlePreparer = StartupSubtitlePreparer(
        StartupSubtitleDependencies(
            fetchAddonSubtitlesStreaming = { _, _ -> emptyList() },
            fetchSubtitleText = { _, _, _ -> null },
            readLocalSubtitleText = { uri ->
                onReadEntered(uri)
                gateForUri(uri).await()
                validSrt
            },
            writeCachedSubtitleText = { _, _ -> null },
            deviceLanguages = { listOf("en") },
            loadPersistedTrackPreference = { null },
            localValidationTimeoutMs = 5_000L,
        ),
    )

    private fun sampleLaunch(
        videoId: String,
        sourceUrl: String,
        subtitleUrl: String,
    ): PlayerLaunch = PlayerLaunch(
        profileId = 1,
        title = "Show $videoId",
        sourceUrl = sourceUrl,
        externalSubtitles = listOf(StreamSubtitle(subtitleUrl, "en", "English")),
        streamTitle = "1080p",
        providerName = "Downloaded",
        contentType = "series",
        videoId = videoId,
        parentMetaId = "tt-show",
        parentMetaType = "series",
    )

    @Test
    fun cancel_during_slow_subtitle_check_prevents_launch_for_both_download_callbacks() = runBlocking {
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        try {
            // 1. launchWithDownloadPreference path
            run {
                val readStarted = CountDownLatch(1)
                val gate = CompletableDeferred<Unit>()
                val preparer = slowPreparer(
                    onReadEntered = { readStarted.countDown() },
                    gateForUri = { gate },
                )
                val launched = CopyOnWriteArrayList<PlayerLaunch>()
                val fallbacks = CopyOnWriteArrayList<String>()
                val controller = PlaybackPreparationController(
                    scope = scope,
                    resolveLocalPlayback = { _, _, _, videoId ->
                        VerifiedLocalPlayback(
                            item = testDownload(episode = 1, status = DownloadStatus.Completed),
                            sourceUrl = "file:///tmp/$videoId.mp4",
                            externalSubtitles = listOf(StreamSubtitle("file:///tmp/$videoId.srt", "en", "English")),
                        )
                    },
                )

                controller.launchWithDownloadPreference(
                    manualSelection = false,
                    downloadsEnabled = true,
                    parentMetaId = "tt-show",
                    seasonNumber = 1,
                    episodeNumber = 1,
                    videoId = "tt-show:1:1",
                    onStreamFallback = { fallbacks += "fallback" },
                    onLocalPlaybackReady = { resolved ->
                        val prepared = prepareLocalPlayerLaunch(
                            savedLaunch = sampleLaunch(
                                videoId = resolved.item.videoId,
                                sourceUrl = resolved.sourceUrl,
                                subtitleUrl = resolved.externalSubtitles.first().url,
                            ),
                            settings = subtitleSettings,
                            prepareSubtitles = preparer::prepareDownloadedSubtitles,
                        )
                        launched += prepared
                    },
                )

                assertTrue(readStarted.await(5, TimeUnit.SECONDS), "Subtitle check did not start")
                assertTrue(controller.isPreparing, "isPreparing must stay true while subtitles are checked")

                controller.cancel()
                assertFalse(controller.isPreparing, "cancel() must clear isPreparing immediately")

                gate.complete(Unit)
                Thread.sleep(150)
                assertTrue(launched.isEmpty(), "Cancelled launchWithDownloadPreference must not open player")
                assertTrue(fallbacks.isEmpty())
            }

            // 2. openDownloadedItem path
            run {
                val readStarted = CountDownLatch(1)
                val gate = CompletableDeferred<Unit>()
                val preparer = slowPreparer(
                    onReadEntered = { readStarted.countDown() },
                    gateForUri = { gate },
                )
                val launched = CopyOnWriteArrayList<PlayerLaunch>()
                val unavailable = CopyOnWriteArrayList<String>()
                val item = testDownload(episode = 2, status = DownloadStatus.Completed)
                val controller = PlaybackPreparationController(
                    scope = scope,
                    verifyDownloadItem = { download ->
                        VerifiedLocalPlayback(
                            item = download,
                            sourceUrl = "file:///tmp/${download.id}.mp4",
                            externalSubtitles = listOf(StreamSubtitle("file:///tmp/${download.id}.srt", "en", "English")),
                        )
                    },
                )

                controller.openDownloadedItem(
                    item = item,
                    onUnavailable = { unavailable += item.id },
                    onLocalPlaybackReady = { resolved ->
                        val prepared = prepareLocalPlayerLaunch(
                            savedLaunch = sampleLaunch(
                                videoId = resolved.item.videoId,
                                sourceUrl = resolved.sourceUrl,
                                subtitleUrl = resolved.externalSubtitles.first().url,
                            ),
                            settings = subtitleSettings,
                            prepareSubtitles = preparer::prepareDownloadedSubtitles,
                        )
                        launched += prepared
                    },
                )

                assertTrue(readStarted.await(5, TimeUnit.SECONDS), "Subtitle check did not start")
                assertTrue(controller.isPreparing, "isPreparing must stay true during openDownloadedItem subtitle check")

                controller.cancel()
                assertFalse(controller.isPreparing)

                gate.complete(Unit)
                Thread.sleep(150)
                assertTrue(launched.isEmpty(), "Cancelled openDownloadedItem must not open player")
                assertTrue(unavailable.isEmpty())
            }
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun second_selection_during_slow_subtitle_check_launches_only_the_second() = runBlocking {
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        try {
            // 1. Two openDownloadedItem selections: second replaces first during slow subtitle check.
            run {
                val firstReadStarted = CountDownLatch(1)
                val firstGate = CompletableDeferred<Unit>()
                val secondGate = CompletableDeferred<Unit>()
                val preparer = slowPreparer(
                    onReadEntered = { uri ->
                        if (uri.contains("ep1")) firstReadStarted.countDown()
                    },
                    gateForUri = { uri ->
                        if (uri.contains("ep1")) firstGate else secondGate
                    },
                )
                val launched = CopyOnWriteArrayList<PlayerLaunch>()
                val ep1 = testDownload(episode = 1, status = DownloadStatus.Completed, id = "ep1")
                val ep2 = testDownload(episode = 2, status = DownloadStatus.Completed, id = "ep2")
                val controller = PlaybackPreparationController(
                    scope = scope,
                    verifyDownloadItem = { download ->
                        VerifiedLocalPlayback(
                            item = download,
                            sourceUrl = "file:///tmp/${download.id}.mp4",
                            externalSubtitles = listOf(StreamSubtitle("file:///tmp/${download.id}.srt", "en", "English")),
                        )
                    },
                )

                suspend fun launchFor(resolved: VerifiedLocalPlayback) {
                    val prepared = prepareLocalPlayerLaunch(
                        savedLaunch = sampleLaunch(
                            videoId = resolved.item.videoId,
                            sourceUrl = resolved.sourceUrl,
                            subtitleUrl = resolved.externalSubtitles.first().url,
                        ),
                        settings = subtitleSettings,
                        prepareSubtitles = preparer::prepareDownloadedSubtitles,
                    )
                    launched += prepared
                }

                controller.openDownloadedItem(
                    item = ep1,
                    onUnavailable = {},
                    onLocalPlaybackReady = ::launchFor,
                )
                assertTrue(firstReadStarted.await(5, TimeUnit.SECONDS), "First subtitle check did not start")
                assertTrue(controller.isPreparing)

                controller.openDownloadedItem(
                    item = ep2,
                    onUnavailable = {},
                    onLocalPlaybackReady = ::launchFor,
                )
                assertTrue(controller.isPreparing)

                // Release both gates; only ep2 must launch.
                firstGate.complete(Unit)
                secondGate.complete(Unit)

                val deadline = System.nanoTime() + 5_000_000_000L
                while (controller.isPreparing && System.nanoTime() < deadline) {
                    Thread.sleep(10)
                }
                assertFalse(controller.isPreparing)
                assertEquals(listOf("tt1:1:2"), launched.map { it.videoId })
                assertEquals("file:///tmp/ep2.srt", launched.single().startupSubtitleSelection?.selectedUrl)
            }

            // 2. launchWithDownloadPreference followed by manual stream selection cancels the download check.
            run {
                val firstReadStarted = CountDownLatch(1)
                val firstGate = CompletableDeferred<Unit>()
                val preparer = slowPreparer(
                    onReadEntered = { firstReadStarted.countDown() },
                    gateForUri = { firstGate },
                )
                val localLaunched = CopyOnWriteArrayList<String>()
                val streamFallbacks = CopyOnWriteArrayList<String>()
                val controller = PlaybackPreparationController(
                    scope = scope,
                    resolveLocalPlayback = { _, _, _, videoId ->
                        VerifiedLocalPlayback(
                            item = testDownload(episode = 1, status = DownloadStatus.Completed),
                            sourceUrl = "file:///tmp/$videoId.mp4",
                            externalSubtitles = listOf(StreamSubtitle("file:///tmp/$videoId.srt", "en", "English")),
                        )
                    },
                )

                controller.launchWithDownloadPreference(
                    manualSelection = false,
                    downloadsEnabled = true,
                    parentMetaId = "tt-show",
                    seasonNumber = 1,
                    episodeNumber = 1,
                    videoId = "tt-show:1:1",
                    onStreamFallback = {},
                    onLocalPlaybackReady = { resolved ->
                        val prepared = prepareLocalPlayerLaunch(
                            savedLaunch = sampleLaunch(
                                videoId = resolved.item.videoId,
                                sourceUrl = resolved.sourceUrl,
                                subtitleUrl = resolved.externalSubtitles.first().url,
                            ),
                            settings = subtitleSettings,
                            prepareSubtitles = preparer::prepareDownloadedSubtitles,
                        )
                        localLaunched += prepared.videoId.orEmpty()
                    },
                )
                assertTrue(firstReadStarted.await(5, TimeUnit.SECONDS))
                assertTrue(controller.isPreparing)

                controller.launchWithDownloadPreference(
                    manualSelection = true,
                    downloadsEnabled = true,
                    parentMetaId = "tt-show",
                    seasonNumber = 1,
                    episodeNumber = 1,
                    videoId = "tt-show:1:1",
                    onStreamFallback = { streamFallbacks += "manual" },
                    onLocalPlaybackReady = {},
                )

                assertFalse(controller.isPreparing)
                firstGate.complete(Unit)
                Thread.sleep(150)
                assertTrue(localLaunched.isEmpty(), "Superseded download check must not launch after manual selection")
                assertEquals(listOf("manual"), streamFallbacks.toList())
            }

            // 3. Two launchWithDownloadPreference selections for different episodes: only the second launches.
            run {
                val firstReadStarted = CountDownLatch(1)
                val firstGate = CompletableDeferred<Unit>()
                val secondGate = CompletableDeferred<Unit>()
                val preparer = slowPreparer(
                    onReadEntered = { uri ->
                        if (uri.contains("ep-a")) firstReadStarted.countDown()
                    },
                    gateForUri = { uri ->
                        if (uri.contains("ep-a")) firstGate else secondGate
                    },
                )
                val launched = CopyOnWriteArrayList<String>()
                val controller = PlaybackPreparationController(
                    scope = scope,
                    resolveLocalPlayback = { _, _, episode, videoId ->
                        val epNum = episode ?: 1
                        VerifiedLocalPlayback(
                            item = testDownload(episode = epNum, status = DownloadStatus.Completed),
                            sourceUrl = "file:///tmp/$videoId.mp4",
                            externalSubtitles = listOf(StreamSubtitle("file:///tmp/$videoId.srt", "en", "English")),
                        )
                    },
                )

                suspend fun launchFor(resolved: VerifiedLocalPlayback) {
                    val prepared = prepareLocalPlayerLaunch(
                        savedLaunch = sampleLaunch(
                            videoId = resolved.item.videoId,
                            sourceUrl = resolved.sourceUrl,
                            subtitleUrl = resolved.externalSubtitles.first().url,
                        ),
                        settings = subtitleSettings,
                        prepareSubtitles = preparer::prepareDownloadedSubtitles,
                    )
                    launched += prepared.sourceUrl
                }

                controller.launchWithDownloadPreference(
                    manualSelection = false,
                    downloadsEnabled = true,
                    parentMetaId = "tt-show",
                    seasonNumber = 1,
                    episodeNumber = 1,
                    videoId = "ep-a",
                    onStreamFallback = {},
                    onLocalPlaybackReady = ::launchFor,
                )
                assertTrue(firstReadStarted.await(5, TimeUnit.SECONDS))

                controller.launchWithDownloadPreference(
                    manualSelection = false,
                    downloadsEnabled = true,
                    parentMetaId = "tt-show",
                    seasonNumber = 1,
                    episodeNumber = 2,
                    videoId = "ep-b",
                    onStreamFallback = {},
                    onLocalPlaybackReady = ::launchFor,
                )
                assertTrue(controller.isPreparing)

                firstGate.complete(Unit)
                secondGate.complete(Unit)

                val deadline = System.nanoTime() + 5_000_000_000L
                while (controller.isPreparing && System.nanoTime() < deadline) {
                    Thread.sleep(10)
                }
                assertFalse(controller.isPreparing)
                assertEquals(listOf("file:///tmp/ep-b.mp4"), launched.toList())
            }
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun preparation_overlay_stays_displayed_until_subtitle_check_completes_and_launch_runs() {
        val firstReadEntered = CountDownLatch(1)
        val firstGate = CompletableDeferred<Unit>()
        val secondReadEntered = CountDownLatch(1)
        val secondGate = CompletableDeferred<Unit>()
        val preparer = slowPreparer(
            onReadEntered = { uri ->
                if (uri.contains("sub1")) firstReadEntered.countDown()
                if (uri.contains("sub2")) secondReadEntered.countDown()
            },
            gateForUri = { uri ->
                if (uri.contains("sub1")) firstGate else secondGate
            },
        )
        val launched = CopyOnWriteArrayList<PlayerLaunch>()
        val item2 = testDownload(episode = 2, status = DownloadStatus.Completed, id = "item-2")

        compose.setContent {
            NuvioTheme {
                val scope = rememberCoroutineScope()
                val preparation = remember(scope) {
                    PlaybackPreparationController(
                        scope = scope,
                        resolveLocalPlayback = { _, _, _, videoId ->
                            VerifiedLocalPlayback(
                                item = testDownload(episode = 1, status = DownloadStatus.Completed, id = "item-1"),
                                sourceUrl = "file:///tmp/$videoId.mp4",
                                externalSubtitles = listOf(StreamSubtitle("file:///tmp/sub1.srt", "en", "English")),
                            )
                        },
                        verifyDownloadItem = { download ->
                            VerifiedLocalPlayback(
                                item = download,
                                sourceUrl = "file:///tmp/${download.id}.mp4",
                                externalSubtitles = listOf(StreamSubtitle("file:///tmp/sub2.srt", "en", "English")),
                            )
                        },
                    )
                }
                Box {
                    Column {
                        Button(
                            onClick = {
                                preparation.launchWithDownloadPreference(
                                    manualSelection = false,
                                    downloadsEnabled = true,
                                    parentMetaId = "tt-show",
                                    seasonNumber = 1,
                                    episodeNumber = 1,
                                    videoId = "v1",
                                    onStreamFallback = {},
                                    onLocalPlaybackReady = { resolved ->
                                        launched += prepareLocalPlayerLaunch(
                                            savedLaunch = sampleLaunch(
                                                videoId = resolved.item.videoId,
                                                sourceUrl = resolved.sourceUrl,
                                                subtitleUrl = resolved.externalSubtitles.first().url,
                                            ),
                                            settings = subtitleSettings,
                                            prepareSubtitles = preparer::prepareDownloadedSubtitles,
                                        )
                                    },
                                )
                            },
                        ) {
                            Text("Play Preference")
                        }
                        Button(
                            onClick = {
                                preparation.openDownloadedItem(
                                    item = item2,
                                    onUnavailable = {},
                                    onLocalPlaybackReady = { resolved ->
                                        launched += prepareLocalPlayerLaunch(
                                            savedLaunch = sampleLaunch(
                                                videoId = resolved.item.videoId,
                                                sourceUrl = resolved.sourceUrl,
                                                subtitleUrl = resolved.externalSubtitles.first().url,
                                            ),
                                            settings = subtitleSettings,
                                            prepareSubtitles = preparer::prepareDownloadedSubtitles,
                                        )
                                    },
                                )
                            },
                        ) {
                            Text("Play Download")
                        }
                    }
                    PlaybackPreparationOverlay(
                        visible = preparation.isPreparing,
                        onCancel = preparation::cancel,
                    )
                }
            }
        }

        // First launch: overlay stays displayed during slow subtitle check, and cancel prevents launch.
        compose.onNodeWithText("Play Preference").performClick()
        assertTrue(firstReadEntered.await(5, TimeUnit.SECONDS))
        compose.waitUntil(timeoutMillis = 2_000L) {
            compose.onAllNodesWithText("Preparing playback…").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Preparing playback…").assertIsDisplayed()
        assertTrue(launched.isEmpty(), "Launch must wait for subtitle preparation")

        compose.onNodeWithContentDescription("Cancel").performClick()
        compose.waitUntil(timeoutMillis = 2_000L) {
            compose.onAllNodesWithText("Preparing playback…").fetchSemanticsNodes().isEmpty()
        }
        firstGate.complete(Unit)
        compose.waitForIdle()
        assertTrue(launched.isEmpty(), "Cancelled download must not launch after subtitle gate opens")

        // Second launch via openDownloadedItem: overlay stays up until subtitle check finishes, then launches.
        compose.onNodeWithText("Play Download").performClick()
        assertTrue(secondReadEntered.await(5, TimeUnit.SECONDS))
        compose.waitUntil(timeoutMillis = 2_000L) {
            compose.onAllNodesWithText("Preparing playback…").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Preparing playback…").assertIsDisplayed()
        assertTrue(launched.isEmpty())

        secondGate.complete(Unit)
        compose.waitUntil(timeoutMillis = 2_000L) {
            launched.isNotEmpty() &&
                compose.onAllNodesWithText("Preparing playback…").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(1, launched.size)
        assertEquals("file:///tmp/item-2.mp4", launched.single().sourceUrl)
        assertEquals("file:///tmp/sub2.srt", launched.single().startupSubtitleSelection?.selectedUrl)
    }
}
