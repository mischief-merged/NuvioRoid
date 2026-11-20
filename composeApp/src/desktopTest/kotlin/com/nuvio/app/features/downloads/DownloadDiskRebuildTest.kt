package com.nuvio.app.features.downloads

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_find_on_disk
import nuvio.composeapp.generated.resources.downloads_find_on_disk_found
import nuvio.composeapp.generated.resources.downloads_find_on_disk_found_partial
import nuvio.composeapp.generated.resources.downloads_find_on_disk_none
import nuvio.composeapp.generated.resources.downloads_section_movies
import nuvio.composeapp.generated.resources.downloads_section_shows
import nuvio.composeapp.generated.resources.downloads_section_unrecognised
import nuvio.composeapp.generated.resources.downloads_unrecognised_reason_ambiguous_name
import nuvio.composeapp.generated.resources.downloads_unrecognised_reason_episode_mismatch
import nuvio.composeapp.generated.resources.downloads_unrecognised_reason_foreign_companion
import nuvio.composeapp.generated.resources.downloads_unrecognised_reason_no_companion
import nuvio.composeapp.generated.resources.downloads_unrecognised_reason_not_video
import nuvio.composeapp.generated.resources.downloads_unrecognised_reason_unreadable_companion
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Rebuilding the downloads list from video files and `<video>.subtitles/` companion folders
 * in temporary download folders when `nuvio_downloads.properties` is lost, emptied, or wiped.
 */
@OptIn(ExperimentalTestApi::class)
class DownloadDiskRebuildTest {
    @get:Rule
    val compose = createComposeRule()

    private val world = FolderWorld()
    private val realBackend = DownloadsRepository.backend

    @Before
    fun set_up() {
        ProfileRepository.clearInMemory()
        DownloadsRepository.clearLocalState()
        NuvioToastController.dismiss()
    }

    @After
    fun tear_down() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        DesktopDownloads.replaceForTest(null)
        NuvioToastController.dismiss()
        ProfileRepository.clearInMemory()
        world.close()
    }

    private fun text(resource: StringResource, vararg args: Any) = runBlocking { getString(resource, *args) }
    private fun plural(resource: PluralStringResource, quantity: Int) = runBlocking { getPluralString(resource, quantity, quantity) }
    private fun isButton() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    /** A `DownloadsBackend` backed by a real `nuvio_downloads.properties` file and real `DesktopDownloadFolders`. */
    private class TempStoreBackend(
        private val world: FolderWorld,
        private val storeFile: Path,
        private val profileId: Int = 1,
        private val otherProfiles: () -> List<RecordRef>? = { emptyList() },
    ) : DownloadsBackend {
        private var store = DesktopStorage.Store(storeFile)
        val hooks = ProfileHooks(mapOf(profileId to emptyList()), profile = profileId)
        val folders = DesktopDownloadFolders(
            locations = world.locations,
            validator = world.validator,
            mover = world.mover(),
            hooks = object : DownloadsHooks by hooks {
                override fun otherProfileRecords(profileId: Int): List<RecordRef>? = otherProfiles()
            },
            trashPath = world.trash::send,
        )

        fun reloadStore() {
            store = DesktopStorage.Store(storeFile)
        }

        override fun loadPayload(): String? = store.getString(ProfileScopedKey.of("downloads", profileId))
        override fun savePayload(payload: String) {
            store.putString(ProfileScopedKey.of("downloads", profileId), payload)
        }

        override fun loadPreviousPayload(): String? = store.getPreviousString(ProfileScopedKey.of("downloads", profileId))
        override fun saveUnreadablePayload(payload: String, atEpochMs: Long) {
            store.putString("${ProfileScopedKey.of("downloads_unreadable", profileId)}_$atEpochMs", payload)
        }

        override val rereadsPayload: Boolean = true
        override fun loadFreshPayload(): String? = store.getFreshString(ProfileScopedKey.of("downloads", profileId))
        override fun syncPayload(): Boolean = store.sync()
        override fun <T> withListLock(block: () -> T): T = store.exclusive(block)
        override fun restoreItem(item: DownloadItem): DownloadItem = item

        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
            onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
            onFailure: (message: String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle = object : DownloadsTaskHandle { override fun cancel() = Unit }

        override fun removeFile(localFileUri: String?): Boolean = false
        override fun removePartialFile(destinationFileName: String): Boolean = false
        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? =
            (world.locations.locate(null, localFileUri, destinationFileName) as? FileLookup.Found)?.uri

        override fun inspectFile(localFileUri: String): DownloadedFileFacts? = inspectDownloadedFile(localFileUri)
        override fun locate(item: DownloadItem): FileLookup = world.locations.locate(item.locationId, item.localFileUri, item.fileName)
        override fun isConnected(item: DownloadItem): Boolean = folders.isConnected(item)
        override fun locationConnected(locationId: String?): Boolean = folders.locationConnected(locationId)
        override fun heldByFolder(item: DownloadItem): Boolean = folders.heldByFolder(item)
        override fun placementFor(items: List<DownloadItem>, parentMetaId: String, isEpisode: Boolean): String =
            folders.placementFor(items, parentMetaId, isEpisode)

        override val supportsDiskScan: Boolean = true
        override fun scanDownloadsOnDisk(
            activeProfileId: Int,
            inMemoryItems: List<DownloadItem>,
            nextDownloadId: (Long) -> String,
            nowEpochMs: Long,
        ): DiskScanResult = folders.scanDownloadsOnDisk(activeProfileId, inMemoryItems, nextDownloadId, nowEpochMs)
    }

    private val testJson = kotlinx.serialization.json.Json { encodeDefaults = true }

    private fun writeSidecar(
        video: Path,
        withMarker: Boolean = true,
        manifest: String? = null,
        subtitleFiles: Map<String, String> = emptyMap(),
        episodes: SavedEpisodes? = null,
        skipTimes: SavedSkipTimes? = null,
    ): Path {
        val sidecar = video.resolveSibling(DownloadSidecarFiles.sidecarFolderName(video.fileName.toString())).createDirectories()
        if (withMarker) {
            Files.writeString(sidecar.resolve(SidecarOwnership.MarkerName), "nuvio-sidecar 1\nvideo=${video.fileName}\n")
        }
        if (manifest != null) {
            Files.writeString(sidecar.resolve("manifest.json"), manifest)
        }
        subtitleFiles.forEach { (name, content) ->
            Files.writeString(sidecar.resolve(name), content)
        }
        if (episodes != null) {
            val encoded = testJson.encodeToString(SavedEpisodes.serializer(), episodes)
            Files.writeString(sidecar.resolve(EpisodeListSidecar.FILE_NAME), encoded)
        }
        if (skipTimes != null) {
            val encoded = testJson.encodeToString(SkipTimesFile.serializer(), skipTimes.toFile())
            Files.writeString(sidecar.resolve(SkipTimesSidecar.FILE_NAME), encoded)
        }
        return sidecar
    }

    private fun sampleEpisodes(
        showName: String = "Pantheon",
        parentMetaId: String = "tt11680642",
        poster: String? = "https://img.example/poster.jpg",
    ) = SavedEpisodes(
        fetchedAtEpochMs = 1_700_000_000_000L,
        showName = showName,
        parentMetaId = parentMetaId,
        parentMetaType = "series",
        imdbId = parentMetaId,
        poster = poster,
        episodes = listOf(
            SavedEpisode(season = 1, episode = 1, id = "$parentMetaId:1:1", title = "Pantheon", thumbnail = "https://img.example/s1e1.jpg"),
            SavedEpisode(season = 1, episode = 2, id = "$parentMetaId:1:2", title = "Cycles", thumbnail = "https://img.example/s1e2.jpg"),
            SavedEpisode(season = 2, episode = 1, id = "$parentMetaId:2:1", title = "The Gods Will Not Be Chained", thumbnail = "https://img.example/s2e1.jpg"),
        ),
    )

    private fun manifestWithSubtitles(vararg fileNames: String, sourceUrlPrefix: String = "https://subs.example/sub"): String {
        val tracks = fileNames.joinToString(",") { name ->
            """{"sourceUrl":"$sourceUrlPrefix/$name","fileName":"$name","language":"en","name":"English"}"""
        }
        return """{"complete":true,"tracks":[$tracks],"checkedAtEpochMs":1700000000000,"failed":[]}"""
    }

    private fun snapshotTree(root: Path): Map<String, ByteArray> {
        if (!Files.exists(root)) return emptyMap()
        return Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .sorted()
                .toList()
                .associate { root.relativize(it).toString() to Files.readAllBytes(it) }
        }
    }

    @Test
    fun when_nuvio_downloads_properties_is_lost_wiped_or_emptied_startup_rebuilds_episodes_and_movies_across_locations() {
        val appDir = world.appFolder.createDirectories()
        val archiveDrive = world.driveLocation("Archive", "Movies")

        val ep1 = appDir.resolve("Pantheon S01E01 Pantheon_lokg51c0_1.mkv").writeVideo(1)
        writeSidecar(
            video = ep1,
            manifest = manifestWithSubtitles("0.srt"),
            subtitleFiles = mapOf("0.srt" to "1\n00:00:01,000 --> 00:00:02,000\nHello\n"),
            episodes = sampleEpisodes(),
            skipTimes = SavedSkipTimes(10L, listOf(com.nuvio.app.features.player.skip.SkipInterval(5.0, 45.0, "intro", "introdb"))),
        )

        // Sibling episode on another storage location that only has manifest.json (enriched from ep1's episodes.json).
        val ep2 = archiveDrive.path.resolve("Pantheon S01E02 Cycles_lokg51c0_2.mkv").writeVideo(2)
        writeSidecar(
            video = ep2,
            manifest = manifestWithSubtitles("0.srt"),
            subtitleFiles = mapOf("0.srt" to "1\n00:00:01,000 --> 00:00:02,000\nWorld\n"),
        )

        // Movie on the external drive with manifest.json (no episodes.json).
        val movie = archiveDrive.path.resolve("Dune_ Part Two_lokg51c0_3.mp4").writeVideo(3)
        writeSidecar(
            video = movie,
            manifest = manifestWithSubtitles("0.srt", sourceUrlPrefix = "https://subs.example/tt15239678"),
            subtitleFiles = mapOf("0.srt" to "1\n00:00:01,000 --> 00:00:02,000\nDune\n"),
        )

        val storeFile = world.appData.resolve("nuvio_downloads.properties")
        val backend = TempStoreBackend(world, storeFile)
        DownloadsRepository.backend = backend

        // Case 1: nuvio_downloads.properties does not exist (lost / wiped).
        assertFalse(storeFile.exists())
        DownloadsRepository.ensureLoaded()

        val recovered = DownloadsRepository.uiState.value.items
        assertEquals(3, recovered.size)

        val rEp1 = recovered.first { it.fileName == ep1.fileName.toString() }
        assertEquals(DownloadStatus.Completed, rEp1.status)
        assertEquals("tt11680642", rEp1.parentMetaId)
        assertEquals("tt11680642:1:1", rEp1.videoId)
        assertEquals("Pantheon", rEp1.title)
        assertEquals(1, rEp1.seasonNumber)
        assertEquals(1, rEp1.episodeNumber)
        assertEquals("Pantheon", rEp1.episodeTitle)
        assertEquals("https://img.example/poster.jpg", rEp1.poster)
        assertEquals("https://img.example/s1e1.jpg", rEp1.episodeThumbnail)
        assertEquals(DownloadLocations.AppId, rEp1.locationId)
        assertEquals(Files.size(ep1), rEp1.downloadedBytes)
        assertEquals(Files.size(ep1), rEp1.totalBytes)
        assertNotNull(DownloadsRepository.findPlayableDownload("tt11680642", 1, 1, "tt11680642:1:1"))
        val ep1Uri = assertNotNull(rEp1.localFileUri)
        assertEquals(1, DownloadSubtitles.localSubtitles(ep1Uri).size)
        assertEquals(1, OfflineSkipTimes.app.saved(ep1Uri).size)

        val rEp2 = recovered.first { it.fileName == ep2.fileName.toString() }
        assertEquals("tt11680642", rEp2.parentMetaId)
        assertEquals("tt11680642:1:2", rEp2.videoId)
        assertEquals("Cycles", rEp2.episodeTitle)
        assertEquals(archiveDrive.id, rEp2.locationId)

        val rMovie = recovered.first { it.fileName == movie.fileName.toString() }
        assertFalse(rMovie.isEpisode)
        assertEquals("Dune Part Two", rMovie.title)
        assertEquals("tt15239678", rMovie.parentMetaId)
        assertEquals(archiveDrive.id, rMovie.locationId)
        assertEquals(DownloadStatus.Completed, rMovie.status)

        // Persisted to nuvio_downloads.properties so a subsequent restart loads them directly.
        assertTrue(storeFile.exists())
        DownloadsRepository.clearLocalState()
        backend.reloadStore()
        DownloadsRepository.ensureLoaded()
        assertEquals(3, DownloadsRepository.uiState.value.items.size)

        // Case 2: nuvio_downloads.properties was emptied (0 bytes).
        DownloadsRepository.clearLocalState()
        Files.writeString(storeFile, "")
        backend.reloadStore()
        DownloadsRepository.ensureLoaded()
        assertEquals(3, DownloadsRepository.uiState.value.items.size)

        // Case 3: app data wiped while keeping downloads folder (DesktopStorage.wipeFolder).
        DownloadsRepository.clearLocalState()
        DesktopStorage.wipeFolder(world.appData, DesktopStorage.KeptOnWipe)
        backend.reloadStore()
        DownloadsRepository.ensureLoaded()
        // ep1 in appDir + ep2 and movie in archiveDrive (still in world.locations) are all recovered again.
        assertEquals(3, DownloadsRepository.uiState.value.items.size)
    }

    @Test
    fun conservative_matching_never_adopts_unconfident_files_or_part_files_and_never_deletes_or_moves_anything() {
        val appDir = world.appFolder.createDirectories()

        // 1. Partial file (.part) with valid video bytes AND companion folder — NEVER adopted as complete.
        val partFile = appDir.resolve("Movie_lokg51c0_1.mp4.part").writeVideo(10)
        writeSidecar(
            video = appDir.resolve("Movie_lokg51c0_1.mp4"),
            manifest = manifestWithSubtitles(),
        )
        writeSidecar(
            video = partFile,
            manifest = manifestWithSubtitles(),
        )

        // 2. Video with no companion folder -> Unrecognised (NoCompanionFolder).
        val noCompanion = appDir.resolve("Random.Show.S01E01.mkv").writeVideo(11)

        // 3. Video with foreign companion folder -> Unrecognised (ForeignCompanionFolder).
        val foreignVideo = appDir.resolve("Foreign Film_lokg51c0_2.mkv").writeVideo(12)
        val foreignSidecar = appDir.resolve("Foreign Film_lokg51c0_2.mkv.subtitles").createDirectories()
        Files.writeString(foreignSidecar.resolve("notes.txt"), "user notes")

        // 4. Video with corrupt episodes.json -> Unrecognised (UnreadableCompanion).
        val corruptVideo = appDir.resolve("Corrupt S01E01_lokg51c0_3.mkv").writeVideo(13)
        val corruptSidecar = appDir.resolve("Corrupt S01E01_lokg51c0_3.mkv.subtitles").createDirectories()
        Files.writeString(corruptSidecar.resolve(EpisodeListSidecar.FILE_NAME), "{not valid json")

        // 5. Video whose filename show/episode contradicts episodes.json -> Unrecognised (EpisodeMismatch).
        val mismatchVideo = appDir.resolve("Wrong Show S01E01_lokg51c0_4.mkv").writeVideo(14)
        writeSidecar(video = mismatchVideo, episodes = sampleEpisodes(showName = "Pantheon"))

        val missingEpVideo = appDir.resolve("Pantheon S09E99_lokg51c0_5.mkv").writeVideo(15)
        writeSidecar(video = missingEpVideo, episodes = sampleEpisodes(showName = "Pantheon"))

        // 6. Multi-episode pack filename -> Unrecognised (AmbiguousName).
        val packVideo = appDir.resolve("Pantheon S01E01-E02_lokg51c0_6.mkv").writeVideo(16)
        writeSidecar(video = packVideo, episodes = sampleEpisodes(showName = "Pantheon"))

        // 7. HTML web page saved as .mp4 -> Unrecognised (NotAVideoFile).
        val htmlVideo = appDir.resolve("SignIn_lokg51c0_7.mp4").also {
            Files.writeString(it, "<!DOCTYPE html><html><body>Login</body></html>")
        }
        writeSidecar(video = htmlVideo, manifest = manifestWithSubtitles())

        // 8. Hidden AppleDouble file -> ignored completely.
        val appleDouble = appDir.resolve("._Pantheon S01E01.mkv").writeVideo(17)

        val beforeAppTree = snapshotTree(appDir)

        val storeFile = world.appData.resolve("nuvio_downloads.properties")
        val backend = TempStoreBackend(world, storeFile)
        DownloadsRepository.backend = backend
        DownloadsRepository.ensureLoaded()

        val state = DownloadsRepository.uiState.value
        assertTrue(state.items.isEmpty(), "Expected no adopted items, got: ${state.items.map { it.fileName }}")

        val byName = state.unrecognisedFiles.associateBy { it.fileName }
        assertEquals(UnrecognisedFileReason.NoCompanionFolder, byName[noCompanion.fileName.toString()]?.reason)
        assertEquals(UnrecognisedFileReason.ForeignCompanionFolder, byName[foreignVideo.fileName.toString()]?.reason)
        assertEquals(UnrecognisedFileReason.UnreadableCompanion, byName[corruptVideo.fileName.toString()]?.reason)
        assertEquals(UnrecognisedFileReason.EpisodeMismatch, byName[mismatchVideo.fileName.toString()]?.reason)
        assertEquals(UnrecognisedFileReason.EpisodeMismatch, byName[missingEpVideo.fileName.toString()]?.reason)
        assertEquals(UnrecognisedFileReason.AmbiguousName, byName[packVideo.fileName.toString()]?.reason)
        assertEquals(UnrecognisedFileReason.NotAVideoFile, byName[htmlVideo.fileName.toString()]?.reason)
        assertFalse(partFile.fileName.toString() in byName)
        assertFalse(appleDouble.fileName.toString() in byName)

        // Nothing on disk was deleted, moved, or modified.
        val afterAppTree = snapshotTree(appDir)
        assertEquals(beforeAppTree.keys, afterAppTree.keys)
        beforeAppTree.forEach { (rel, bytes) ->
            assertTrue(bytes.contentEquals(afterAppTree.getValue(rel)), "$rel was modified")
        }
    }

    @Test
    fun other_profiles_addressless_records_and_move_leftovers_are_never_adopted() {
        val appDir = world.appFolder.createDirectories()
        val archive = world.driveLocation("Archive")

        val otherProfileFilm = appDir.resolve("OtherProfileFilm_lokg51c0_1.mp4").writeVideo(1)
        writeSidecar(otherProfileFilm, manifest = manifestWithSubtitles())

        val addresslessFile = appDir.resolve("Addressless.mp4").writeVideo(2)
        writeSidecar(addresslessFile, manifest = manifestWithSubtitles())

        val leftoverDup = archive.path.resolve("MovedFilm.mp4").writeVideo(3)
        writeSidecar(leftoverDup, manifest = manifestWithSubtitles())

        val ownFilm = appDir.resolve("MyFilm_lokg51c0_2.mp4").writeVideo(4)
        writeSidecar(ownFilm, manifest = manifestWithSubtitles())

        val addresslessItem = movieItem("addr").copy(
            localFileUri = null,
            fileName = addresslessFile.fileName.toString(),
        )
        val otherProfileItem = movieItem("other", file = otherProfileFilm).copy(
            fileName = otherProfileFilm.fileName.toString(),
        )

        val scanner = DownloadDiskScanner(
            locations = world.locations,
            allRecords = { activeId, inMemory ->
                inMemory.map { RecordRef.of(activeId, it) } + listOf(RecordRef.of(2, otherProfileItem))
            },
            leftoverPaths = { setOf(DownloadOwnership.keyOf(leftoverDup)) },
        )

        val result = scanner.scan(
            activeProfileId = 1,
            inMemoryItems = listOf(addresslessItem),
            nextDownloadId = { "id_${it}" },
            nowEpochMs = 1_700_000_000_000L,
        )

        assertEquals(listOf(ownFilm.fileName.toString()), result.recoveredItems.map { it.fileName })
        assertTrue(result.unrecognisedFiles.isEmpty())
    }

    @Test
    fun unplugged_drive_is_skipped_and_reconnected_drive_is_recovered_on_find_downloads_on_disk() {
        val drive = world.driveLocation("External")
        val film = drive.path.resolve("Arrival_lokg51c0_1.mkv").writeVideo(1)
        writeSidecar(film, manifest = manifestWithSubtitles())
        world.unplug("External")

        val storeFile = world.appData.resolve("nuvio_downloads.properties")
        val backend = TempStoreBackend(world, storeFile)
        DownloadsRepository.backend = backend
        DownloadsRepository.ensureLoaded()
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())

        // Reconnect under a new mount point name (External 1): rediscover + scan picks it up.
        Files.move(world.root.resolve("External.unplugged"), world.volumes.resolve("External 1"))
        val summary = DownloadsRepository.findDownloadsOnDisk()

        assertEquals(1, summary.addedCount)
        assertEquals(0, summary.unrecognisedCount)
        assertEquals(listOf("Arrival"), DownloadsRepository.uiState.value.items.map { it.title })

        // Calling again does not duplicate.
        val second = DownloadsRepository.findDownloadsOnDisk()
        assertEquals(0, second.addedCount)
        assertEquals(1, DownloadsRepository.uiState.value.items.size)
    }

    @Test
    fun downloads_screen_find_button_recovers_confident_files_and_shows_unrecognised_files_section() {
        val appDir = world.appFolder.createDirectories()
        val storeFile = world.appData.resolve("nuvio_downloads.properties")
        val backend = TempStoreBackend(world, storeFile)
        DownloadsRepository.backend = backend

        compose.setContent {
            NuvioTheme {
                DownloadsScreenContent(
                    onBack = {},
                    onOpenDownload = {},
                    showAutoDownloads = false,
                    autoDownloads = null,
                    folders = backend.folders,
                )
            }
        }
        compose.waitForIdle()

        // Button is visible on the Downloads screen even when empty.
        compose.onNode(hasText(text(Res.string.downloads_find_on_disk)) and isButton()).assertIsDisplayed()

        // Place files on disk while the app is open (simulating reconnecting/restoring files).
        val ep = appDir.resolve("Pantheon S02E01_lokg51c0_1.mkv").writeVideo(1)
        writeSidecar(ep, episodes = sampleEpisodes())
        val movie = appDir.resolve("Blade Runner_lokg51c0_2.mp4").writeVideo(2)
        writeSidecar(movie, manifest = manifestWithSubtitles())
        val mystery = appDir.resolve("mystery_clip.mkv").writeVideo(3)

        compose.onNode(hasText(text(Res.string.downloads_find_on_disk)) and isButton()).performClick()
        compose.waitUntil(5_000) { DownloadsRepository.uiState.value.items.size == 2 }
        compose.waitForIdle()

        compose.onNodeWithText(text(Res.string.downloads_section_movies)).assertIsDisplayed()
        compose.onNodeWithText("Blade Runner").assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_section_shows)).assertIsDisplayed()
        compose.onNodeWithText("Pantheon").assertIsDisplayed()

        compose.onNodeWithText(text(Res.string.downloads_section_unrecognised)).assertIsDisplayed()
        compose.onNodeWithText(mystery.fileName.toString()).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_unrecognised_reason_no_companion)).assertIsDisplayed()
        assertEquals(
            text(Res.string.downloads_find_on_disk_found_partial, 2, 1),
            NuvioToastController.currentToast.value?.message,
        )

        // Clicking again adds nothing new and keeps the same 2 downloads and 1 unrecognised file.
        compose.onNode(hasText(text(Res.string.downloads_find_on_disk)) and isButton()).performClick()
        compose.waitUntil(5_000) {
            NuvioToastController.currentToast.value?.message == plural(Res.plurals.downloads_find_on_disk_found, 0) ||
                NuvioToastController.currentToast.value?.message != text(Res.string.downloads_find_on_disk_found_partial, 2, 1)
        }
        assertEquals(2, DownloadsRepository.uiState.value.items.size)
        assertEquals(1, DownloadsRepository.uiState.value.unrecognisedFiles.size)

        // Verify the mystery file is untouched on disk.
        assertTrue(mystery.exists())
        compose.onAllNodesWithText("Blade Runner").let { assertEquals(1, it.fetchSemanticsNodes().size) }
    }

    @Test
    fun unreadable_other_profile_list_refuses_to_adopt_any_files_conservatively() {
        val appDir = world.appFolder.createDirectories()
        val film = appDir.resolve("Arrival_lokg51c0_1.mkv").writeVideo(1)
        writeSidecar(film, manifest = manifestWithSubtitles())

        val storeFile = world.appData.resolve("nuvio_downloads.properties")
        val backend = TempStoreBackend(world, storeFile, profileId = 1, otherProfiles = { null })
        DownloadsRepository.backend = backend
        DownloadsRepository.ensureLoaded()

        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
        assertTrue(DownloadsRepository.uiState.value.unrecognisedFiles.isEmpty())
    }

    @Test
    fun filename_parser_and_strict_manifest_parser_handle_nuvio_and_ambiguous_inputs() {
        val episode = parseVideoFileName("Spider-Man_ Show S02E03 Pilot_lokg51c0_1 (2).mkv")
        assertEquals("Spider-Man Show", episode.baseTitle)
        assertEquals(2, episode.seasonNumber)
        assertEquals(3, episode.episodeNumber)
        assertEquals("Pilot", episode.episodeTitle)
        assertEquals("lokg51c0_1", episode.extractedDownloadId)
        assertFalse(episode.hasAmbiguousEpisodes)

        val movie = parseVideoFileName("2001_ A Space Odyssey_lokg51c0_2.mp4")
        assertEquals("2001 A Space Odyssey", movie.baseTitle)
        assertEquals(null, movie.seasonNumber)
        assertEquals(null, movie.episodeNumber)
        assertFalse(movie.hasAmbiguousEpisodes)

        val datedMovie = parseVideoFileName("Dune (2021)_lokg51c0_3.mkv")
        assertEquals("Dune (2021)", datedMovie.baseTitle)

        val multi = parseVideoFileName("Show S01E01-E03.mkv")
        assertTrue(multi.hasAmbiguousEpisodes)

        assertEquals(null, parseSubtitleManifestStrict("{}"))
        assertEquals(null, parseSubtitleManifestStrict("""{"foo":"bar"}"""))
        assertNotNull(parseSubtitleManifestStrict("""{"complete":true,"tracks":[]}"""))
    }

    @Test
    fun pre_marker_companions_and_corrupted_list_with_revision_recover_without_modifying_sidecars() {
        val appDir = world.appFolder.createDirectories()
        val movie = appDir.resolve("Inception_lokg51c0_1.mkv").writeVideo(1)
        val sidecar = writeSidecar(
            video = movie,
            withMarker = false,
            manifest = manifestWithSubtitles("0.srt"),
            subtitleFiles = mapOf("0.srt" to "1\n00:00:01,000 --> 00:00:02,000\nDream\n"),
        )
        val beforeSidecar = snapshotTree(sidecar)

        val storeFile = world.appData.resolve("nuvio_downloads.properties")
        DesktopStorage.Store(storeFile).putString(ProfileScopedKey.of("downloads", 1), """{"items":12,"revision":5}""")
        val backend = TempStoreBackend(world, storeFile)
        DownloadsRepository.backend = backend
        DownloadsRepository.ensureLoaded()

        val items = DownloadsRepository.uiState.value.items
        assertEquals(1, items.size)
        assertEquals("Inception", items.single().title)
        assertEquals(beforeSidecar.keys, snapshotTree(sidecar).keys)
        assertFalse(sidecar.resolve(SidecarOwnership.MarkerName).exists())

        // Verify the corrupted payload did not block persisting the recovered list.
        DownloadsRepository.clearLocalState()
        backend.reloadStore()
        DownloadsRepository.ensureLoaded()
        assertEquals(1, DownloadsRepository.uiState.value.items.size)
    }

    @Test
    fun switching_profiles_during_disk_scan_never_overwrites_destination_profile_list() {
        val appDir = world.appFolder.createDirectories()
        val movie = appDir.resolve("ProfileOneFilm_lokg51c0_1.mp4").writeVideo(1)
        writeSidecar(movie, manifest = manifestWithSubtitles())

        val storeFile = world.appData.resolve("nuvio_downloads.properties")
        val seedStore = DesktopStorage.Store(storeFile)
        val profile2Partial = movieItem("p2-partial", status = DownloadStatus.Paused).copy(
            fileName = "ProfileTwoPartial_lokg51c0_9.mkv",
            downloadedBytes = 1024L,
            totalBytes = 4096L,
            partialFileKey = "key-p2",
        )
        // Profile 1 has empty list at revision 1; Profile 2 has a partial download also at revision 1.
        seedStore.putString(ProfileScopedKey.of("downloads", 1), """{"items":[],"revision":1}""")
        seedStore.putString(ProfileScopedKey.of("downloads", 2), DownloadsCodec.encode(listOf(profile2Partial), null, revision = 1L))
        seedStore.sync()

        val inner = TempStoreBackend(world, storeFile, profileId = 1)
        val multiStore = DesktopStorage.Store(storeFile)
        val multiBackend = object : DownloadsBackend by inner {
            override fun loadPayload(): String? = multiStore.getString(ProfileScopedKey.of("downloads"))
            override fun loadPayload(profileId: Int): String? = multiStore.getString(ProfileScopedKey.of("downloads", profileId))
            override fun savePayload(payload: String) {
                multiStore.putString(ProfileScopedKey.of("downloads"), payload)
            }
            override fun savePayload(profileId: Int, payload: String) {
                multiStore.putString(ProfileScopedKey.of("downloads", profileId), payload)
            }
            override fun loadFreshPayload(): String? = multiStore.getFreshString(ProfileScopedKey.of("downloads"))
            override fun loadFreshPayload(profileId: Int): String? = multiStore.getFreshString(ProfileScopedKey.of("downloads", profileId))
            override fun syncPayload(): Boolean = multiStore.sync()
            override fun <T> withListLock(block: () -> T): T = multiStore.exclusive(block)
            override val supportsDiskScan: Boolean = false
        }

        ProfileRepository.setActiveProfileIdForTest(1)
        DownloadsRepository.backend = multiBackend
        DownloadsRepository.ensureLoaded()

        // Case A: ProfileRepository.selectProfile(2) updates activeProfileId before findDownloadsOnDisk finishes & persists.
        val scanner = { activeProfileId: Int, inMemoryItems: List<DownloadItem>, nextId: (Long) -> String, now: Long ->
            val result = inner.folders.scanDownloadsOnDisk(activeProfileId, inMemoryItems, nextId, now)
            ProfileRepository.setActiveProfileIdForTest(2)
            result
        }
        DownloadsRepository.findDownloadsOnDisk(controllerScanner = scanner)

        // Profile 2's stored list must still contain its partial download, never Profile 1's movie.
        val profile2After = decodeStoredDownloads(
            DesktopStorage.Store(storeFile).getFreshString(ProfileScopedKey.of("downloads", 2)).orEmpty(),
        )
        assertNotNull(profile2After)
        assertEquals(listOf(profile2Partial), profile2After)

        // Case B: Profile 2 completes onProfileChanged() while Profile 1's unlocked scan is still running.
        val laterMovie = appDir.resolve("ProfileOneLaterFilm_lokg51c0_2.mp4").writeVideo(2)
        writeSidecar(laterMovie, manifest = manifestWithSubtitles())
        DownloadsRepository.clearLocalState()
        ProfileRepository.setActiveProfileIdForTest(1)
        DownloadsRepository.ensureLoaded()
        val scanStarted = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)
        var scannedFileNames = emptyList<String>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val scanFuture = executor.submit<DiskScanSummary> {
                DownloadsRepository.findDownloadsOnDisk { activeId, inMem, nextId, now ->
                    scanStarted.countDown()
                    allowFinish.await(5, TimeUnit.SECONDS)
                    val result = inner.folders.scanDownloadsOnDisk(activeId, inMem, nextId, now)
                    scannedFileNames = result.recoveredItems.map { it.fileName }
                    result
                }
            }
            assertTrue(scanStarted.await(2, TimeUnit.SECONDS))
            ProfileRepository.setActiveProfileIdForTest(2)
            DownloadsRepository.onProfileChanged()
            assertEquals(listOf(profile2Partial), DownloadsRepository.uiState.value.items)

            allowFinish.countDown()
            scanFuture.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(laterMovie.fileName.toString()), scannedFileNames)

            // Profile 1's stale scan results must not leak into Profile 2 in memory or on disk.
            assertEquals(listOf(profile2Partial), DownloadsRepository.uiState.value.items)
            val profile2StillIntact = decodeStoredDownloads(
                DesktopStorage.Store(storeFile).getFreshString(ProfileScopedKey.of("downloads", 2)).orEmpty(),
            )
            assertEquals(listOf(profile2Partial), profile2StillIntact)
        } finally {
            allowFinish.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun shared_episode_metadata_does_not_override_contradictory_manifest_imdb_id() {
        val appDir = world.appFolder.createDirectories()
        val original = appDir.resolve("Shogun S01E02_lokg51c0_1.mkv").writeVideo(1)
        writeSidecar(
            video = original,
            episodes = SavedEpisodes(
                fetchedAtEpochMs = 1_700_000_000_000L,
                showName = "Shogun",
                parentMetaId = "tt0080274",
                parentMetaType = "series",
                imdbId = "tt0080274",
                poster = "https://img.example/shogun1980.jpg",
                episodes = listOf(
                    SavedEpisode(season = 1, episode = 1, id = "tt0080274:1:1", title = "Original Ep 1"),
                    SavedEpisode(season = 1, episode = 2, id = "tt0080274:1:2", title = "Original Ep 2"),
                ),
            ),
        )

        // Remake S01E01 has only manifest.json with the remake's IMDb ID (tt2788316), no episodes.json.
        val remake = appDir.resolve("Shogun S01E01_lokg51c0_2.mkv").writeVideo(2)
        writeSidecar(
            video = remake,
            manifest = manifestWithSubtitles("0.srt", sourceUrlPrefix = "https://subs.example/tt2788316"),
            subtitleFiles = mapOf("0.srt" to "1\n00:00:01,000 --> 00:00:02,000\nRemake\n"),
        )

        val storeFile = world.appData.resolve("nuvio_downloads.properties")
        DownloadsRepository.backend = TempStoreBackend(world, storeFile)
        DownloadsRepository.ensureLoaded()

        val items = DownloadsRepository.uiState.value.items
        assertEquals(2, items.size)
        val recoveredRemake = items.first { it.fileName == remake.fileName.toString() }
        assertEquals("tt2788316", recoveredRemake.parentMetaId)
        assertEquals("tt2788316:1:1", recoveredRemake.videoId)
        assertEquals(null, DownloadsRepository.findPlayableDownload("tt0080274", 1, 1, "tt0080274:1:1"))
        assertEquals(recoveredRemake.id, DownloadsRepository.findPlayableDownload("tt2788316", 1, 1, "tt2788316:1:1")?.id)
    }

    @Test
    fun shared_episode_metadata_never_resolves_conflicting_manifest_identities() {
        val appDir = world.appFolder.createDirectories()
        val original = appDir.resolve("Shogun S01E02_lokg51c0_1.mkv").writeVideo(1)
        writeSidecar(original, episodes = sampleEpisodes(showName = "Shogun", parentMetaId = "tt0080274"))
        val ambiguous = appDir.resolve("Shogun S01E01_lokg51c0_2.mkv").writeVideo(2)
        writeSidecar(
            ambiguous,
            manifest = manifestWithSubtitles("0.srt", sourceUrlPrefix = "https://subs.example/tt0080274/tt2788316"),
        )

        DownloadsRepository.backend = TempStoreBackend(world, world.appData.resolve("nuvio_downloads.properties"))
        DownloadsRepository.ensureLoaded()

        val state = DownloadsRepository.uiState.value
        assertEquals(listOf(original.fileName.toString()), state.items.map { it.fileName })
        assertEquals(ambiguous.fileName.toString(), state.unrecognisedFiles.single().fileName)
        assertEquals(UnrecognisedFileReason.AmbiguousName, state.unrecognisedFiles.single().reason)
        assertEquals(null, DownloadsRepository.findPlayableDownload("tt0080274", 1, 1, "tt0080274:1:1"))
    }

    @Test
    fun remounted_drive_preserves_other_profile_and_own_ownership_before_refresh() {
        val drive = world.driveLocation("Archive", "Movies")
        val otherProfileFilm = drive.path.resolve("ProfileTwoFilm_lokg51c0_1.mp4").writeVideo(1)
        writeSidecar(otherProfileFilm, manifest = manifestWithSubtitles())
        val ownFilm = drive.path.resolve("ProfileOneFilm_lokg51c0_2.mp4").writeVideo(2)
        writeSidecar(ownFilm, manifest = manifestWithSubtitles())

        val otherItem = movieItem("p2-film", locationId = drive.id, file = otherProfileFilm).copy(
            fileName = otherProfileFilm.fileName.toString(),
        )
        val ownItem = movieItem("p1-film", locationId = drive.id, file = ownFilm).copy(
            fileName = ownFilm.fileName.toString(),
        )

        // Unplug and remount under a new path BEFORE any refresh/rediscover runs.
        world.unplug("Archive")
        Files.move(world.root.resolve("Archive.unplugged"), world.volumes.resolve("Archive 1"))

        val scanner = DownloadDiskScanner(
            locations = world.locations,
            allRecords = { _, inMemory ->
                inMemory.map { RecordRef.of(1, it) } + listOf(RecordRef.of(2, otherItem))
            },
        )
        val result = scanner.scan(
            activeProfileId = 1,
            inMemoryItems = listOf(ownItem),
            nextDownloadId = { "id_$it" },
            nowEpochMs = 1_700_000_000_000L,
        )

        assertTrue(
            result.recoveredItems.isEmpty(),
            "Expected neither Profile 2's file nor Profile 1's own file to be adopted on remount, got: ${result.recoveredItems.map { it.fileName }}",
        )
        assertTrue(result.unrecognisedFiles.isEmpty())
    }

    @Test
    fun accented_show_name_sanitized_in_filename_recovers_against_episodes_json() {
        val appDir = world.appFolder.createDirectories()
        // Filename produced by DownloadsRepository.buildFileName("Shōgun", 1, 1, "Anjin", ...)
        val ep1 = appDir.resolve("Sh_gun S01E01 Anjin_lokg51c0_1.mkv").writeVideo(1)
        writeSidecar(
            video = ep1,
            episodes = SavedEpisodes(
                fetchedAtEpochMs = 1_700_000_000_000L,
                showName = "Shōgun",
                parentMetaId = "tt2788316",
                parentMetaType = "series",
                imdbId = "tt2788316",
                episodes = listOf(
                    SavedEpisode(season = 1, episode = 1, id = "tt2788316:1:1", title = "Anjin"),
                    SavedEpisode(season = 1, episode = 2, id = "tt2788316:1:2", title = "Servants of Two Masters"),
                ),
            ),
        )
        val ep2 = appDir.resolve("Sh_gun S01E02 Servants of Two Masters_lokg51c0_2.mkv").writeVideo(2)
        writeSidecar(ep2, manifest = manifestWithSubtitles())

        val storeFile = world.appData.resolve("nuvio_downloads.properties")
        DownloadsRepository.backend = TempStoreBackend(world, storeFile)
        DownloadsRepository.ensureLoaded()

        val state = DownloadsRepository.uiState.value
        assertTrue(state.unrecognisedFiles.isEmpty(), "Unexpected unrecognised: ${state.unrecognisedFiles}")
        assertEquals(2, state.items.size)
        assertTrue(state.items.all { it.parentMetaId == "tt2788316" && it.title == "Shōgun" })
    }

    @Test
    fun non_video_file_with_video_extension_and_valid_companion_is_rejected_as_not_a_video() {
        val appDir = world.appFolder.createDirectories()
        val fakeVideo = appDir.resolve("Movie_lokg51c0_1.mp4").also {
            Files.writeString(it, """{"error":"not cached"}""")
        }
        writeSidecar(fakeVideo, manifest = manifestWithSubtitles())

        val storeFile = world.appData.resolve("nuvio_downloads.properties")
        DownloadsRepository.backend = TempStoreBackend(world, storeFile)
        DownloadsRepository.ensureLoaded()

        val state = DownloadsRepository.uiState.value
        assertTrue(state.items.isEmpty(), "Non-video file must not be adopted as Completed")
        assertEquals(1, state.unrecognisedFiles.size)
        assertEquals(UnrecognisedFileReason.NotAVideoFile, state.unrecognisedFiles.single().reason)
    }

    @Test
    fun ui_actions_are_not_blocked_by_slow_disk_scan() {
        val appDir = world.appFolder.createDirectories()
        val storeFile = world.appData.resolve("nuvio_downloads.properties")
        val runningItem = movieItem("running-1", status = DownloadStatus.Downloading).copy(
            fileName = "Running_lokg51c0_1.mp4",
        )
        val inner = TempStoreBackend(world, storeFile)
        inner.savePayload(DownloadsCodec.encode(listOf(runningItem), null, revision = 1L))
        val noScanBackend = object : DownloadsBackend by inner {
            override val supportsDiskScan: Boolean = false
        }
        DownloadsRepository.backend = noScanBackend
        DownloadsRepository.ensureLoaded()

        val scanStarted = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val scanFuture = executor.submit<DiskScanSummary> {
                DownloadsRepository.findDownloadsOnDisk { _, _, _, _ ->
                    scanStarted.countDown()
                    allowFinish.await(5, TimeUnit.SECONDS)
                    DiskScanResult()
                }
            }
            assertTrue(scanStarted.await(2, TimeUnit.SECONDS))

            // Pause on the UI thread must not wait for the filesystem scan to finish.
            val pauseStartMs = System.nanoTime()
            DownloadsRepository.pauseDownload(runningItem.id)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pauseStartMs)

            assertEquals(DownloadStatus.Paused, DownloadsRepository.uiState.value.items.single().status)
            assertTrue(elapsedMs < 1_000L, "pauseDownload waited ${elapsedMs}ms behind disk scan")

            allowFinish.countDown()
            scanFuture.get(5, TimeUnit.SECONDS)
        } finally {
            allowFinish.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun unresponsive_location_directory_check_times_out_and_startup_scan_releases_state_lock() {
        val appDir = world.appFolder.createDirectories()
        val movie = appDir.resolve("GoodFilm_lokg51c0_1.mp4").writeVideo(1)
        writeSidecar(movie, manifest = manifestWithSubtitles())

        val hungDrive = world.driveLocation("HungDrive", "Movies")
        val hungGate = CountDownLatch(1)
        val scanner = DownloadDiskScanner(
            locations = world.locations,
            allRecords = { _, inMemory -> inMemory.map { RecordRef.of(1, it) } },
            timeoutMs = 150L,
            resolveLocationDir = { path ->
                if (path == hungDrive.path) {
                    hungGate.await(5, TimeUnit.SECONDS)
                }
                if (!Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) null else path.toRealPath()
            },
        )
        try {
            val scanStartMs = System.nanoTime()
            val scanResult = scanner.scan(
                activeProfileId = 1,
                inMemoryItems = emptyList(),
                nextDownloadId = { "id_$it" },
                nowEpochMs = 1_700_000_000_000L,
            )
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - scanStartMs)
            assertTrue(elapsedMs < 2_000L, "Hung directory check took ${elapsedMs}ms instead of timing out")
            assertEquals(listOf(movie.fileName.toString()), scanResult.recoveredItems.map { it.fileName })
        } finally {
            hungGate.countDown()
        }

        // Pause must work while the startup scan is waiting, including ensureLoaded().
        val storeFile = world.appData.resolve("nuvio_downloads.properties")
        val inner = TempStoreBackend(world, storeFile)
        val runningItem = movieItem("startup-running", status = DownloadStatus.Downloading)
        inner.savePayload(DownloadsCodec.encode(listOf(runningItem), null, revision = 1L))
        val scanStarted = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = object : DownloadsBackend by inner {
            override val supportsDiskScan: Boolean = true
            override fun scanDownloadsOnDisk(
                activeProfileId: Int,
                inMemoryItems: List<DownloadItem>,
                nextDownloadId: (Long) -> String,
                nowEpochMs: Long,
            ): DiskScanResult {
                scanStarted.countDown()
                allowFinish.await(5, TimeUnit.SECONDS)
                return DiskScanResult()
            }
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val loadFuture = executor.submit { DownloadsRepository.ensureLoaded() }
            assertTrue(scanStarted.await(2, TimeUnit.SECONDS))

            val pauseFuture = executor.submit { DownloadsRepository.pauseDownload(runningItem.id) }
            pauseFuture.get(1, TimeUnit.SECONDS)
            assertEquals(DownloadStatus.Paused, DownloadsRepository.uiState.value.items.single().status)

            allowFinish.countDown()
            loadFuture.get(5, TimeUnit.SECONDS)
            assertEquals(DownloadStatus.Paused, DownloadsRepository.uiState.value.items.single().status)
        } finally {
            allowFinish.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun ownership_path_resolution_cannot_hold_discovery_forever() {
        val drive = world.driveLocation("Sleeping", "Movies")
        val owned = drive.path.resolve("Owned.mp4").writeVideo(1)
        val item = movieItem("owned", file = owned, locationId = drive.id)
        val release = CountDownLatch(1)
        val scanner = DownloadDiskScanner(
            locations = world.locations,
            allRecords = { _, _ -> listOf(RecordRef.of(2, item)) },
            timeoutMs = 150L,
            pathKey = { path ->
                if (path.startsWith(drive.path)) release.await(2, TimeUnit.SECONDS)
                DownloadOwnership.keyOf(path)
            },
        )
        try {
            val start = System.nanoTime()
            val result = scanner.scan(1, emptyList(), { "id_$it" }, 1L)
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue(elapsed < 1_000L, "Ownership checks blocked discovery for ${elapsed}ms")
            assertTrue(result.recoveredItems.isEmpty(), "Unknown ownership must prevent adoption")
        } finally {
            release.countDown()
        }
    }

    @Test
    fun move_leftover_path_resolution_cannot_hold_discovery_forever() {
        val release = CountDownLatch(1)
        val scanner = DownloadDiskScanner(
            locations = world.locations,
            allRecords = { _, _ -> emptyList() },
            leftoverPaths = {
                release.await(2, TimeUnit.SECONDS)
                emptySet()
            },
            timeoutMs = 150L,
        )
        try {
            val start = System.nanoTime()
            val result = scanner.scan(1, emptyList(), { "id_$it" }, 1L)
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue(elapsed < 1_000L, "Leftover checks blocked discovery for ${elapsed}ms")
            assertTrue(result.recoveredItems.isEmpty())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun healthy_folder_keeps_results_when_total_walk_exceeds_one_filesystem_timeout() {
        val movies = (1..4).map { index ->
            world.appFolder.resolve("Film${index}_lokg51c0_$index.mp4").writeVideo(index).also {
                writeSidecar(it, manifest = manifestWithSubtitles())
            }
        }
        val scanner = DownloadDiskScanner(
            locations = world.locations,
            allRecords = { _, _ -> emptyList() },
            timeoutMs = 200L,
            pathKey = { path ->
                if (path in movies) Thread.sleep(80L)
                DownloadOwnership.keyOf(path)
            },
        )
        val result = scanner.scan(1, emptyList(), { "id_$it" }, 1L)
        assertEquals(movies.map { it.fileName.toString() }.toSet(), result.recoveredItems.map { it.fileName }.toSet())
    }

    @Test
    fun stored_transfers_resume_before_startup_discovery_finishes() {
        val inner = TempStoreBackend(world, world.appData.resolve("nuvio_downloads.properties"))
        val running = movieItem("resume-before-scan", status = DownloadStatus.Downloading)
        inner.savePayload(DownloadsCodec.encode(listOf(running), null, revision = 1L))
        var transferStarted = false
        var startedAtScan = false
        DownloadsRepository.backend = object : DownloadsBackend by inner {
            override fun start(
                request: DownloadPlatformRequest,
                onProgress: (Long, Long?) -> Unit,
                onSuccess: (String, Long?) -> Unit,
                onFailure: (String) -> Unit,
                onPaused: () -> Unit,
            ): DownloadsTaskHandle {
                transferStarted = true
                return object : DownloadsTaskHandle { override fun cancel() = Unit }
            }
            override fun scanDownloadsOnDisk(
                activeProfileId: Int,
                inMemoryItems: List<DownloadItem>,
                nextDownloadId: (Long) -> String,
                nowEpochMs: Long,
            ): DiskScanResult {
                startedAtScan = transferStarted
                return DiskScanResult()
            }
        }
        DownloadsRepository.ensureLoaded()
        assertTrue(startedAtScan, "Stored downloads waited for startup discovery before resuming")
    }

    @Test
    fun missing_profile_list_does_not_inherit_previous_profiles_merge_state() {
        assertProfileRecoveryKeepsConcurrentPartial(null)
    }

    @Test
    fun empty_profile_list_does_not_inherit_previous_profiles_merge_state() {
        assertProfileRecoveryKeepsConcurrentPartial("  ")
    }

    @Test
    fun corrupt_profile_without_previous_copy_does_not_inherit_previous_profiles_baseline() {
        assertProfileRecoveryKeepsConcurrentPartial("""{"items":12,"revision":0}""")
    }

    private fun assertProfileRecoveryKeepsConcurrentPartial(initialPayload: String?) {
        val storeFile = world.appData.resolve("nuvio_downloads.properties")
        val store = DesktopStorage.Store(storeFile)
        // Matching ids make a leaked baseline look like a removal from the new profile.
        val partial = movieItem("shared-id", status = DownloadStatus.Paused).copy(
            downloadedBytes = 1024L, totalBytes = 4096L, partialFileKey = "partial-key",
        )
        store.putString(ProfileScopedKey.of("downloads", 1), DownloadsCodec.encode(listOf(partial), null, 10L))
        if (initialPayload != null) store.putString(ProfileScopedKey.of("downloads", 2), initialPayload)
        val movie = world.appFolder.resolve("Recovered_lokg51c0_1.mp4").writeVideo(1)
        writeSidecar(movie, manifest = manifestWithSubtitles())
        val inner = TempStoreBackend(world, storeFile)
        DownloadsRepository.backend = object : DownloadsBackend by inner {
            override fun loadPayload(profileId: Int): String? = store.getFreshString(ProfileScopedKey.of("downloads", profileId))
            override fun loadPreviousPayload(profileId: Int): String? = null
            override fun loadFreshPayload(profileId: Int): String? = store.getFreshString(ProfileScopedKey.of("downloads", profileId))
            override fun savePayload(profileId: Int, payload: String) = store.putString(ProfileScopedKey.of("downloads", profileId), payload)
            override fun scanDownloadsOnDisk(
                activeProfileId: Int,
                inMemoryItems: List<DownloadItem>,
                nextDownloadId: (Long) -> String,
                nowEpochMs: Long,
            ): DiskScanResult {
                if (activeProfileId == 1) return DiskScanResult()
                val result = inner.folders.scanDownloadsOnDisk(activeProfileId, inMemoryItems, nextDownloadId, nowEpochMs)
                assertEquals(1, result.recoveredItems.size)
                // A second copy saves after this profile was loaded but before recovery persists.
                DesktopStorage.Store(storeFile).putString(
                    ProfileScopedKey.of("downloads", 2), DownloadsCodec.encode(listOf(partial), null, 1L),
                )
                return result
            }
        }
        ProfileRepository.setActiveProfileIdForTest(1)
        DownloadsRepository.ensureLoaded()
        ProfileRepository.setActiveProfileIdForTest(2)
        DownloadsRepository.onProfileChanged()
        val saved = assertNotNull(decodeStoredDownloads(store.getFreshString(ProfileScopedKey.of("downloads", 2)).orEmpty()))
        assertEquals(partial, saved.find { it.id == partial.id }, "Recovery dropped another copy's partial download")
        assertEquals(2, saved.size)
        assertEquals(saved, DownloadsRepository.uiState.value.items)
    }

}
