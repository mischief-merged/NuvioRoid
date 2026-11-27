package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerSettingsRepository
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.*

class NativePlayerMetadataTest {
    @Test
    fun first_reads_include_real_metadata_chapters_and_tracks_and_external_subtitles_can_be_reselected() = runBlocking {
        assumeTrue(DesktopHostOs.current == DesktopHostOs.MACOS)
        val root = Files.createTempDirectory("player-metadata-").toFile()
        val media = root.resolve("episode.mkv")
        val extra = root.resolve("extra.srt").apply { writeText("1\n00:00:00,000 --> 00:00:03,000\nExtra\n") }
        writePlayerMetadataFixture(media)
        val disposed = CountDownLatch(1)
        val resolutions = AtomicInteger()
        val backend = object : NativePlayerBackend by RealNativePlayerBackend {}
        val controller = NativePlayerController(NativePlayerHost(), backend = backend,
            nativeCreate = { _, source, headers, play, position, _, _, _, _, sink ->
                NativePlayerBridge.createHeadlessMacosForTest(source, headers, play, position, true, sink)
            },
            nativeDispose = { RealNativePlayerBackend.dispose(it); disposed.countDown() },
            isHostDisplayable = { true }, resolveHostView = { 1L }, startupIsOffline = { false },
            resolvePlayableLocalSubtitle = { _, _, _ -> resolutions.incrementAndGet(); extra.toURI().toString() })
        val chaptersEnabled = PlayerSettingsRepository.uiState.value.androidExoChaptersEnabled
        fun awaitState(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(condition())
        }
        try {
            PlayerSettingsRepository.setAndroidExoChaptersEnabled(true)
            controller.attach(media.toURI().toString(), emptyMap(), false, 0L, 2, false) { if (it != null) error(it) }
            awaitState { !controller.snapshot().isLoading }
            val info = controller.getMediaInfo()
            assertEquals(32, info.videoWidth)
            assertEquals(32, info.videoHeight)
            assertFalse(info.videoCodec.isNullOrBlank())
            assertEquals(listOf("First", "Second"), controller.getChapters().map { it.title })
            assertEquals(listOf(0L, 2_000L), controller.getChapters().map { it.startMs })
            assertEquals(1, controller.getAudioTracks().size)
            assertEquals(1, controller.getSubtitleTracks().size)
            val remote = "https://subtitles.example.invalid/extra.srt"
            controller.setSubtitleUri(remote)
            awaitState { controller.getSubtitleTracks().size == 2 }
            controller.clearExternalSubtitleAndSelect(0)
            awaitState { controller.getSubtitleTracks().size == 1 }
            controller.setSubtitleUri(remote)
            awaitState { controller.getSubtitleTracks().size == 2 && controller.getSubtitleTracks().last().isSelected }
            assertEquals(2, resolutions.get(), "a removed external subtitle must be loaded again")
        } finally {
            controller.dispose()
            assertTrue(disposed.await(5, TimeUnit.SECONDS))
            PlayerSettingsRepository.setAndroidExoChaptersEnabled(chaptersEnabled)
            root.deleteRecursively()
        }
    }
}
