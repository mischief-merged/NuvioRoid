package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Skip times at the two ends of a download's life: saved when it finishes, and carried or removed
 * with the file. Temporary files and a fake service only.
 */
class DownloadSkipTimesLifecycleTest {
    private class FakeBackend : DownloadsBackend {
        var payload: String? = null
        val files = mutableMapOf<String, DownloadedFileFacts>()
        var finish: ((String, Long?) -> Unit)? = null
        var progress: ((Long, Long?) -> Unit)? = null

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
            finish = onSuccess
            progress = onProgress
            return object : DownloadsTaskHandle { override fun cancel() = Unit }
        }
        override fun removeFile(localFileUri: String?): Boolean { files.remove(localFileUri); return true }
        override fun removePartialFile(destinationFileName: String): Boolean = true
        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? =
            localFileUri?.takeIf { it in files }
        override fun inspectFile(localFileUri: String): DownloadedFileFacts = files[localFileUri] ?: DownloadedFileFacts(exists = false)
    }

    private val backend = FakeBackend()
    private val realBackend = DownloadsRepository.backend
    private val realSkipTimes = DownloadsRepository.companions
    private val world = FolderWorld()
    private lateinit var folder: File
    private val intro = SkipInterval(10.0, 90.0, "intro", "introdb")
    private val requests = mutableListOf<SkipTimesRequest>()

    @BeforeTest
    fun use_an_in_memory_store_and_a_temporary_folder() {
        folder = world.appFolder.also { Files.createDirectories(it) }.toFile()
        DesktopDownloads.replaceForTest(DesktopDownloadFolders(
            world.locations, world.validator, world.mover(),
            trashPath = DesktopDownloadsTrash({ world.appFolder }, systemTrash = null, nowEpochMs = { 123L })::moveToTrash,
        ))
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        val skipTimes = OfflineSkipTimes(
            fetcher = { request -> requests += request; SkipFetchResult.Found(listOf(intro)) },
            isOnline = { true },
            io = Dispatchers.Unconfined,
        )
        val companions = DownloadCompanions(
            listOf(skipTimes), isOnline = { true },
            scope = CoroutineScope(Dispatchers.Unconfined), io = Dispatchers.Unconfined,
        )
        DownloadsRepository.companions = { companions }
    }

    @AfterTest
    fun restore_the_real_repository_and_remove_the_folder() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        DownloadsRepository.companions = realSkipTimes
        DesktopDownloads.replaceForTest(null)
        world.close()
    }

    private fun download(contentType: String, videoId: String, season: Int?, episode: Int?, name: String): String {
        val video = File(folder, name).also { it.writeText("video") }
        val uri = video.toURI().toString()
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.enqueueFromStream(
            contentType = contentType, videoId = videoId, parentMetaId = videoId.substringBefore(':'), parentMetaType = contentType,
            title = "Show", logo = null, poster = null, background = null, seasonNumber = season, episodeNumber = episode,
            episodeTitle = null, episodeThumbnail = null,
            stream = StreamItem(name = "1080p", url = "https://example.com/e.mkv", addonName = "A", addonId = "a"),
        )
        backend.files[uri] = DownloadedFileFacts(exists = true, sizeBytes = 1_000, isVideoContainer = true)
        backend.progress!!(0, 1_000)
        backend.finish!!(uri, 1_000)
        return uri
    }

    @Test
    fun finishing_an_episode_download_saves_its_skip_times_beside_the_file() {
        val uri = download("series", "tt1:2:3", 2, 3, "Show.S02E03.mkv")

        assertEquals(DownloadStatus.Completed, DownloadsRepository.uiState.value.items.single().status)
        assertEquals(listOf(intro), (SkipTimesSidecar.read(uri) as SavedSkipTimesRead.Saved).times.intervals)
        assertTrue(File(folder, "Show.S02E03.mkv.subtitles/skip-times.json").isFile)
        val request = requests.single()
        assertEquals(false, request.isMovie)
        assertEquals(Triple("tt1:2:3", 2, 3), Triple(request.videoId, request.season, request.episode))
        assertEquals("tt1", request.imdbId)
    }

    @Test
    fun finishing_a_movie_download_asks_for_a_movie() {
        val uri = download("movie", "tt9", null, null, "Movie.mkv")

        assertTrue(requests.single().isMovie)
        assertEquals(SkipTimesNote.Saved, OfflineSkipTimes.app.note(uri))
    }

    @Test
    fun a_download_that_fails_its_checks_saves_no_skip_times() {
        val video = File(folder, "Bad.mkv").also { it.writeText("x") }
        val uri = video.toURI().toString()
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.enqueueFromStream(
            contentType = "series", videoId = "tt1:1:1", parentMetaId = "tt1", parentMetaType = "series", title = "Show",
            logo = null, poster = null, background = null, seasonNumber = 1, episodeNumber = 1, episodeTitle = null,
            episodeThumbnail = null,
            stream = StreamItem(name = "1080p", url = "https://example.com/e.mkv", addonName = "A", addonId = "a"),
        )
        backend.files[uri] = DownloadedFileFacts(exists = true, sizeBytes = 100, isVideoContainer = false)
        backend.finish!!(uri, 100)

        assertEquals(DownloadStatus.Failed, DownloadsRepository.uiState.value.items.single().status)
        assertEquals(emptyList(), requests)
        assertEquals(SavedSkipTimesRead.Missing, SkipTimesSidecar.read(uri))
    }

    // ---- carried and removed with the file ----

    private fun savedBeside(name: String): File {
        val video = File(folder, name).also { it.writeText("video") }
        SkipTimesSidecar.write(video.toURI().toString(), SavedSkipTimes(2L, listOf(intro)))
        return video
    }

    @Test
    fun deleting_a_download_into_the_system_trash_takes_the_skip_times_with_it() {
        val video = savedBeside("Show.S01E01.mkv")
        val trashed = mutableListOf<File>()
        val trash = DesktopDownloadsTrash(downloadsDir = { folder.toPath() }, systemTrash = { file -> trashed += file; file.deleteRecursively() })

        assertTrue(trash.moveAway(video.toURI().toString()))

        assertEquals(listOf("Show.S01E01.mkv", "Show.S01E01.mkv.subtitles"), trashed.map { it.name })
        assertEquals(emptyList(), folder.list().orEmpty().filterNot { it == SidecarOwnership.MarkerName })
    }

    @Test
    fun a_held_file_carries_the_skip_times_in_its_folder_and_leaves_nothing_behind() {
        val video = savedBeside("Show.S01E01.mkv")
        val trash = DesktopDownloadsTrash(downloadsDir = { folder.toPath() }, systemTrash = null)

        assertTrue(trash.moveAway(video.toURI().toString()))

        assertFalse(File(folder, "Show.S01E01.mkv").exists())
        assertFalse(File(folder, "Show.S01E01.mkv.subtitles").exists())
        val held = File(folder, DesktopDownloadsTrash.HoldingFolderName).walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertTrue(SkipTimesSidecar.FILE_NAME in held, held.toString())
    }

    @Test
    fun a_sidecar_alone_is_not_taken_for_a_download() {
        savedBeside("Show.S01E01.mkv")

        assertEquals(emptyList(), DownloadsRepository.uiState.value.items)
        assertEquals(setOf("Show.S01E01.mkv", "Show.S01E01.mkv.subtitles"), folder.list().orEmpty().filterNot { it == SidecarOwnership.MarkerName }.toSet())
    }

    @Test
    fun skip_times_sit_in_the_same_folder_the_saved_subtitles_use_so_one_rule_covers_both() {
        val video = File(folder, "Show.S01E01.mkv").also { it.writeText("video") }
        val uri = video.toURI().toString()
        DownloadSubtitleStorage(uri).write("manifest.json", "{}")
        SkipTimesSidecar.write(uri, SavedSkipTimes(2L, listOf(intro)))

        assertEquals(setOf("manifest.json", "skip-times.json"), File(folder, "Show.S01E01.mkv.subtitles").list().orEmpty().filterNot { it == SidecarOwnership.MarkerName }.toSet())

        val before = Files.readAllBytes(folder.toPath().resolve("Show.S01E01.mkv.subtitles/skip-times.json"))
        DownloadSubtitleStorage(uri).remove()
        assertEquals(SavedSkipTimesRead.Missing, SkipTimesSidecar.read(uri))
        val held = folder.toPath().resolve("${DesktopDownloadsTrash.HoldingFolderName}/123-Show.S01E01.mkv.subtitles")
        assertContentEquals(before, Files.readAllBytes(held.resolve("skip-times.json")))
        assertEquals("{}", Files.readString(held.resolve("manifest.json")))
    }
}
