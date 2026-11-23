package com.nuvio.app.features.streams

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.downloads.DownloadEnqueueResult
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadPlatformRequest
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsBackend
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.DownloadsTaskHandle
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.streams_pick_to_download
import nuvio.composeapp.generated.resources.streams_pick_to_download_hint
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Before
import org.junit.Rule
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The list of sources opened to pick a source for a download: a tap on a source downloads it in place
 * of the failed download and never plays it. Opened to play, a tap still plays.
 */
class StreamPickForDownloadTest {
    @get:Rule
    val compose = createComposeRule()

    private class FakeBackend : DownloadsBackend {
        var payload: String? = null
        val started = CopyOnWriteArrayList<String>()
        override fun loadPayload(): String? = payload
        override fun savePayload(payload: String) { this.payload = payload }
        override fun restoreItem(item: DownloadItem): DownloadItem = item
        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
            onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
            onFailure: (message: String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle {
            started += request.item.sourceUrl
            return object : DownloadsTaskHandle {
                override fun cancel() = Unit
            }
        }
        override fun removeFile(localFileUri: String?): Boolean = true
        override fun removePartialFile(destinationFileName: String): Boolean = true
        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri
    }

    private val backend = FakeBackend()
    private val realBackend = DownloadsRepository.backend
    private val played = CopyOnWriteArrayList<StreamItem>()

    private val failed =
        """{"id":"f","contentType":"movie","parentMetaType":"movie","videoId":"tt1","parentMetaId":"tt1","title":"Disclosure Day",""" +
            """"streamTitle":"1080p","providerName":"Addon","sourceUrl":"https://example.com/bad.mkv","fileName":"bad.mkv",""" +
            """"status":"Failed","failureKind":"BadFile","errorMessage":"Not the real video","createdAtEpochMs":1,"updatedAtEpochMs":1}"""

    private val picked = StreamItem(name = "[RD+] 2160p", url = "https://example.com/good.mkv", addonName = "Addon", addonId = "addon")

    @Before
    fun use_an_in_memory_store() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        backend.payload = """{"items":[$failed]}"""
        DownloadsRepository.ensureLoaded()
    }

    @After
    fun restore_the_real_repository() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
    }

    private fun enqueue(stream: StreamItem): DownloadEnqueueResult = DownloadsRepository.enqueueFromStream(
        contentType = "movie", videoId = "tt1", parentMetaId = "tt1", parentMetaType = "movie", title = "Disclosure Day",
        logo = null, poster = null, background = null, seasonNumber = null, episodeNumber = null, episodeTitle = null,
        episodeThumbnail = null, stream = stream, userChoseReplacement = true,
    )

    private fun download(stream: StreamItem) {
        runBlocking {
            downloadPickedStream(stream, needsResolve = false, resolve = { error("no lookup needed") }, enqueue = ::enqueue, message = { null })
        }
    }

    private fun tap(purpose: StreamPickPurpose, stream: StreamItem = picked) =
        routeStreamPick(purpose, stream, play = { played += it }, download = ::download)

    @Test
    fun in_download_mode_a_tap_on_a_source_downloads_it_in_place_of_the_failed_download() {
        tap(StreamPickPurpose.Download)

        assertTrue(played.isEmpty(), "a tap in download mode played the source")
        assertEquals(listOf("https://example.com/good.mkv"), backend.started.toList())
        val only = DownloadsRepository.uiState.value.items.single()
        assertEquals("https://example.com/good.mkv", only.sourceUrl)
        assertEquals(DownloadStatus.Downloading, only.status)
        assertFalse(DownloadsRepository.uiState.value.items.any { it.id == "f" }, "the failed download was not replaced")
    }

    @Test
    fun in_play_mode_a_tap_still_plays_and_downloads_nothing() {
        tap(StreamPickPurpose.Play)

        assertEquals(listOf(picked), played.toList())
        assertTrue(backend.started.isEmpty())
        assertEquals("f", DownloadsRepository.uiState.value.items.single().id)
    }

    @Test
    fun a_source_that_needs_a_debrid_link_gets_one_before_it_is_downloaded() {
        val magnet = StreamItem(name = "[RD+] 1080p", infoHash = "abc", addonName = "Addon", addonId = "addon")

        val outcome = runBlocking {
            downloadPickedStream(
                magnet,
                needsResolve = true,
                resolve = { DirectDebridPlayableResult.Success(it.copy(url = "https://debrid.example.com/file.mkv")) },
                enqueue = ::enqueue,
                message = { null },
            )
        }

        assertTrue(outcome.queued)
        assertEquals(listOf("https://debrid.example.com/file.mkv"), backend.started.toList())
    }

    @Test
    fun a_source_whose_debrid_link_cannot_be_had_is_not_downloaded_and_the_failed_download_stays() {
        val outcome = runBlocking {
            downloadPickedStream(picked, needsResolve = true, resolve = { DirectDebridPlayableResult.NotCached }, enqueue = ::enqueue, message = { null })
        }

        assertFalse(outcome.queued)
        assertTrue(backend.started.isEmpty())
        assertEquals("f", DownloadsRepository.uiState.value.items.single().id)
    }

    @Test
    fun download_mode_says_so_at_the_top() {
        compose.setContent { NuvioTheme { StreamDownloadModeBanner(title = "Disclosure Day") } }

        compose.onNodeWithText(runBlocking { getString(Res.string.streams_pick_to_download) }).assertIsDisplayed()
        compose.onNodeWithText(runBlocking { getString(Res.string.streams_pick_to_download_hint, "Disclosure Day") }).assertIsDisplayed()
    }
}
