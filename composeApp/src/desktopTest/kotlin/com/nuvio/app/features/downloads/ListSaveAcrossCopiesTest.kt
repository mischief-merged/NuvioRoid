package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopSingleInstance
import com.nuvio.app.core.storage.DesktopStorage
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Review 4, finding 3: another running copy of the app, with a stale list in memory, must not undo a
 * confirmed relocation. Two Store objects on one file stand for two copies of the app: each has its own
 * memory, and they share only the file and its lock.
 */
class ListSaveAcrossCopiesTest {
    private val world = FolderWorld()
    private val realBackend = DownloadsRepository.backend

    @BeforeTest
    fun start() = DownloadsRepository.clearLocalState()

    @AfterTest
    fun clean_up() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        world.close()
    }

    @Test
    fun a_store_with_stale_memory_never_overwrites_a_key_another_copy_wrote() {
        val file = world.root.resolve("shared.properties")
        val first = DesktopStorage.Store(file)
        val second = DesktopStorage.Store(file)
        second.getString("anything") // the second copy has read the file before the first one writes

        first.putString("downloads_1", "moved")
        second.putString("downloads_2", "progress")

        val fresh = DesktopStorage.Store(file)
        assertEquals("moved", fresh.getString("downloads_1"))
        assertEquals("progress", fresh.getString("downloads_2"))
    }

    /** A backend over one shared Store file, as the desktop app saves its list. */
    private class StoreBackend(world: FolderWorld, val store: DesktopStorage.Store) : DownloadsBackend by LocationsBackend(world) {
        override fun loadPayload(): String? = store.getString("downloads_1")
        override fun loadPayload(profileId: Int): String? = loadPayload()
        override fun savePayload(payload: String) = store.putString("downloads_1", payload)
        override fun savePayload(profileId: Int, payload: String) = savePayload(payload)
        override val rereadsPayload: Boolean get() = true
        override fun loadFreshPayload(): String? = store.getFreshString("downloads_1")
        override fun loadFreshPayload(profileId: Int): String? = loadFreshPayload()
        override fun <T> withListLock(block: () -> T): T = store.exclusive(block)
    }

    @Test
    fun a_stale_whole_list_save_keeps_a_relocation_another_copy_confirmed() {
        val file = world.root.resolve("downloads.properties")
        val moved = movieItem("moved", file = world.appFolder.resolve("moved.mp4").writeVideo(1))
        val other = movieItem("other", file = world.appFolder.resolve("other.mp4").writeVideo(2))
        DesktopStorage.Store(file).putString("downloads_1", storedPayload(listOf(moved, other)))

        // This copy of the app loads the list...
        val mine = StoreBackend(world, DesktopStorage.Store(file))
        DownloadsRepository.backend = mine
        DownloadsRepository.ensureLoaded()

        // ...then another copy relocates "moved" and saves its list.
        val elsewhere = DesktopStorage.Store(file)
        val drive = world.driveLocation("Archive")
        val decoded = decodeStoredDownloads(elsewhere.getString("downloads_1")!!)!!
        val relocated = decoded.map { if (it.id == "moved") it.copy(locationId = drive.id, localFileUri = drive.path.resolve("moved.mp4").toUri().toString()) else it }
        elsewhere.putString("downloads_1", storedPayload(relocated).replaceFirst("{", "{\"revision\":7,"))

        // This copy changes another item and saves its (stale) whole list.
        DownloadsRepository.setPreferredAudioLanguage("other", "fr")

        val saved = decodeStoredDownloads(DesktopStorage.Store(file).getString("downloads_1")!!)!!
        assertEquals(drive.id, saved.first { it.id == "moved" }.locationId, "the stale list undid the relocation")
        assertEquals("fr", saved.first { it.id == "other" }.preferredAudioLanguage)
        assertEquals(drive.id, DownloadsRepository.uiState.value.items.first { it.id == "moved" }.locationId)
    }

    @Test
    fun a_store_whose_lock_cannot_be_taken_writes_nothing_and_says_so() {
        // The lock file's place is taken by a folder, so the lock cannot be opened.
        val file = world.root.resolve("locked.properties")
        Files.createDirectories(world.root.resolve("locked.properties.lock"))
        val store = DesktopStorage.Store(file)
        var ran = false

        val failure = runCatching { store.exclusive { ran = true } }.exceptionOrNull()
        val put = runCatching { store.putString("downloads_1", "x") }

        assertTrue(failure is DesktopStorage.StoreLockUnavailable, "$failure")
        assertFalse(ran, "the block ran without the lock")
        assertTrue(put.isFailure)
        assertFalse(Files.exists(file))
    }

    @Test
    fun only_one_copy_of_the_app_holds_the_app_data_folder() {
        val folder = Files.createDirectories(world.root.resolve("app-data-lock"))
        val first = assertNotNull(DesktopSingleInstance.tryAcquire(folder))
        try {
            assertNull(DesktopSingleInstance.tryAcquire(folder))
        } finally {
            first.close()
        }
        val again = DesktopSingleInstance.tryAcquire(folder)
        assertTrue(again != null)
        again?.close()
        assertFalse(Files.exists(folder.resolve("missing")))
    }
}
