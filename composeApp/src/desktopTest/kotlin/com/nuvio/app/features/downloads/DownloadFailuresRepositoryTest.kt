package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_error_file_checksum
import nuvio.composeapp.generated.resources.downloads_error_not_enough_space
import org.jetbrains.compose.resources.getString
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Failed downloads and what is done about them, over an in-memory store. No network, no real folder. */
class DownloadFailuresRepositoryTest {
    private class FakeBackend : DownloadsBackend {
        var payload: String? = null
        val files = mutableMapOf<String, DownloadedFileFacts>()
        val started = mutableListOf<String>()
        val partialsRemoved = mutableListOf<String>()
        val failures = mutableMapOf<String, (String) -> Unit>()
        var finish: ((String, Long?) -> Unit)? = null
        var progress: ((Long, Long?) -> Unit)? = null

        override fun loadPayload(): String? = payload
        override fun savePayload(payload: String) {
            this.payload = payload
        }

        override fun restoreItem(item: DownloadItem): DownloadItem = item

        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
            onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
            onFailure: (message: String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle {
            started += request.item.id
            failures[request.item.id] = onFailure
            finish = onSuccess
            progress = onProgress
            return object : DownloadsTaskHandle {
                override fun cancel() = Unit
            }
        }

        override fun removeFile(localFileUri: String?): Boolean = true
        override fun removePartialFile(destinationFileName: String): Boolean {
            partialsRemoved += destinationFileName
            return true
        }

        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? =
            localFileUri?.takeIf { it in files }

        override fun inspectFile(localFileUri: String): DownloadedFileFacts = files[localFileUri] ?: DownloadedFileFacts(exists = false)
    }

    private val backend = FakeBackend()
    private val realBackend = DownloadsRepository.backend

    @BeforeTest
    fun use_an_in_memory_store() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        DownloadsRepository.ensureLoaded()
    }

    @AfterTest
    fun restore_the_real_repository() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
    }

    private fun text(resource: org.jetbrains.compose.resources.StringResource) = runBlocking { getString(resource) }

    private fun enqueue(episode: Int): String {
        DownloadsRepository.enqueueFromStream(
            contentType = "series", videoId = "tt1:1:$episode", parentMetaId = "tt1", parentMetaType = "series", title = "Show",
            logo = null, poster = null, background = null, seasonNumber = 1, episodeNumber = episode, episodeTitle = null,
            episodeThumbnail = null,
            stream = StreamItem(name = "1080p", url = "https://example.com/e$episode.mkv", addonName = "A", addonId = "a", behaviorHints = StreamBehaviorHints()),
        )
        return DownloadsRepository.uiState.value.items.first { it.episodeNumber == episode }.id
    }

    private fun item(id: String) = DownloadsRepository.uiState.value.items.single { it.id == id }

    @Test
    fun a_file_that_does_not_match_what_the_source_said_fails_at_once_as_a_bad_file() {
        val id = enqueue(1)

        backend.failures.getValue(id)(text(Res.string.downloads_error_file_checksum))

        assertEquals(1, backend.started.count { it == id }, "the same bad source was fetched again")
        assertEquals(DownloadStatus.Failed, item(id).status)
        assertEquals(DownloadFailureKind.BadFile, item(id).failureKind)
        assertTrue(item(id).needsAnotherSource)
    }

    @Test
    fun a_full_disk_fails_at_once_with_a_plain_message_and_is_not_fetched_again() {
        val id = enqueue(1)

        backend.failures.getValue(id)(text(Res.string.downloads_error_not_enough_space))

        assertEquals(1, backend.started.count { it == id })
        assertEquals(DownloadFailureKind.NotEnoughSpace, item(id).failureKind)
        assertEquals(text(Res.string.downloads_error_not_enough_space), item(id).errorMessage)
        assertFalse(item(id).needsAnotherSource)
    }

    @Test
    fun a_system_message_about_a_full_disk_is_the_same_failure() {
        val id = enqueue(1)

        backend.failures.getValue(id)("No space left on device")

        assertEquals(DownloadFailureKind.NotEnoughSpace, item(id).failureKind)
    }

    @Test
    fun a_source_that_refuses_fails_at_once() {
        val id = enqueue(1)

        backend.failures.getValue(id)("Download failed with HTTP 404")

        assertEquals(1, backend.started.count { it == id })
        assertEquals(DownloadFailureKind.SourceRefused, item(id).failureKind)
        assertTrue(item(id).needsAnotherSource)
    }

    @Test
    fun a_lost_connection_is_still_tried_three_times_before_it_fails() {
        val id = enqueue(1)

        repeat(3) { backend.failures.getValue(id)("Connection lost") }

        assertEquals(3, backend.started.count { it == id })
        assertEquals(DownloadStatus.Failed, item(id).status)
        assertEquals(DownloadFailureKind.Other, item(id).failureKind)
    }

    @Test
    fun a_file_that_fails_its_check_is_a_bad_file() {
        val id = enqueue(1)
        backend.files["file:/e.mkv"] = DownloadedFileFacts(exists = true, sizeBytes = 1_000, isVideoContainer = false)

        backend.progress!!(0, 1_000)
        backend.finish!!("file:/e.mkv", 1_000)

        assertEquals(DownloadFailureKind.BadFile, item(id).failureKind)
    }

    @Test
    fun a_file_that_went_missing_is_not_blamed_on_its_source() {
        val id = enqueue(1)

        backend.progress!!(0, 1_000)
        backend.finish!!("file:/gone.mkv", 1_000)

        assertEquals(DownloadStatus.Failed, item(id).status)
        assertEquals(DownloadFailureKind.Other, item(id).failureKind)
    }

    @Test
    fun trying_again_forgets_the_failure() {
        val id = enqueue(1)
        backend.failures.getValue(id)(text(Res.string.downloads_error_file_checksum))

        DownloadsRepository.retryDownload(id)

        assertEquals(DownloadStatus.Downloading, item(id).status)
        assertNull(item(id).failureKind)
        assertNull(item(id).errorMessage)
    }

    @Test
    fun retry_all_starts_the_failed_downloads_except_those_that_need_another_source() {
        val connection = enqueue(1)
        val space = enqueue(2)
        val bad = enqueue(3)
        val refused = enqueue(4)
        val running = enqueue(5)
        repeat(3) { backend.failures.getValue(connection)("Connection lost") }
        backend.failures.getValue(space)(text(Res.string.downloads_error_not_enough_space))
        backend.failures.getValue(bad)(text(Res.string.downloads_error_file_checksum))
        backend.failures.getValue(refused)("Download failed with HTTP 404")
        val startsBefore = backend.started.groupingBy { it }.eachCount()

        val retried = DownloadsRepository.retryAllFailed()

        assertEquals(2, retried)
        assertEquals(DownloadStatus.Downloading, item(connection).status)
        assertEquals(DownloadStatus.Downloading, item(space).status)
        assertEquals(DownloadStatus.Failed, item(bad).status)
        assertEquals(DownloadStatus.Failed, item(refused).status)
        assertEquals(startsBefore.getValue(bad), backend.started.count { it == bad })
        assertEquals(startsBefore.getValue(refused), backend.started.count { it == refused })
        assertEquals(startsBefore.getValue(running), backend.started.count { it == running })
    }

    @Test
    fun clear_failed_removes_only_the_failed_downloads_and_cleans_up_their_partial_files() {
        val failed = enqueue(1)
        val bad = enqueue(2)
        val running = enqueue(3)
        backend.failures.getValue(failed)(text(Res.string.downloads_error_not_enough_space))
        backend.failures.getValue(bad)(text(Res.string.downloads_error_file_checksum))
        val failedFiles = listOf(item(failed).fileName, item(bad).fileName)

        val cleared = DownloadsRepository.clearFailed()

        assertEquals(2, cleared)
        assertEquals(listOf(running), DownloadsRepository.uiState.value.items.map { it.id })
        assertTrue(backend.partialsRemoved.containsAll(failedFiles))
        assertFalse(backend.payload!!.contains(failed))
        assertEquals(0, DownloadsRepository.clearFailed())
    }

    @Test
    fun a_list_stored_before_failures_were_recorded_loads_whole_and_is_not_set_aside() {
        DownloadsRepository.clearLocalState()
        val unreadable = mutableListOf<String>()
        val store = object : DownloadsBackend by backend {
            override fun saveUnreadablePayload(payload: String, atEpochMs: Long) {
                unreadable += payload
            }

            override fun saveUnreadablePayload(profileId: Int, payload: String, atEpochMs: Long) =
                saveUnreadablePayload(payload, atEpochMs)
        }
        DownloadsRepository.backend = store
        fun stored(id: String, extra: String) =
            """{"id":"$id","contentType":"movie","parentMetaId":"tt$id","parentMetaType":"movie","videoId":"tt$id","title":"Film $id",""" +
                """"streamTitle":"1080p","providerName":"Addon","sourceUrl":"https://example.com/$id.mkv","fileName":"$id.mkv",""" +
                """"status":"Failed","errorMessage":"Connection lost","createdAtEpochMs":1,"updatedAtEpochMs":2$extra}"""
        backend.payload = """{"items":[${stored("a", "")},${stored("b", ""","failureKind":"BadFile"""")},${stored("c", ""","failureKind":"FromALaterBuild"""")}]}"""

        DownloadsRepository.ensureLoaded()

        assertEquals(listOf("a", "b", "c"), DownloadsRepository.uiState.value.items.map { it.id })
        assertEquals(listOf(null, DownloadFailureKind.BadFile, null), DownloadsRepository.uiState.value.items.map { it.failureKind })
        assertTrue(unreadable.isEmpty(), "a readable list was set aside")
    }
}
