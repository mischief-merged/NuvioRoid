package com.nuvio.app.features.downloads

import java.awt.EventQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The stored downloads list is read on the thread that asks for it, and only after the step that
 * settles a cut-short folder move. A caller that says it is drawing the screen does not read it.
 */
class CatalogLoadThreadTest {
    private class Probe : DownloadsBackend {
        var loads = 0
        var loadThread: Thread? = null
        var payload: String? = ""
        var beforeReturn: () -> Unit = {}
        var failuresLeft = 0

        override fun loadPayload(): String? {
            loads++
            loadThread = Thread.currentThread()
            beforeReturn()
            if (failuresLeft > 0) {
                failuresLeft -= 1
                error("store locked")
            }
            return payload
        }

        override fun savePayload(payload: String) = Unit

        override fun restoreItem(item: DownloadItem): DownloadItem = item

        var startFailuresLeft = 0

        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
            onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
            onFailure: (message: String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle {
            if (startFailuresLeft > 0) {
                startFailuresLeft -= 1
                error("cannot start")
            }
            return object : DownloadsTaskHandle {
                override fun cancel() = Unit
            }
        }

        override fun removeFile(localFileUri: String?): Boolean = true

        override fun removePartialFile(destinationFileName: String): Boolean = true

        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri
    }

    private val probe = Probe()
    private val realBackend = DownloadsRepository.backend
    private val realDefer = DownloadsRepository.deferLoadOnThisThread
    private val realBefore = DownloadsRepository.beforeCatalogLoad
    private val realOnLoad = DownloadsRepository.onCatalogLoad

    @BeforeTest
    fun use_an_in_memory_store() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = probe
        DownloadsRepository.deferLoadOnThisThread = { false }
        DownloadsRepository.beforeCatalogLoad = {}
        DownloadsRepository.onCatalogLoad = {}
    }

    @AfterTest
    fun restore_the_real_repository() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        DownloadsRepository.deferLoadOnThisThread = realDefer
        DownloadsRepository.beforeCatalogLoad = realBefore
        DownloadsRepository.onCatalogLoad = realOnLoad
    }

    @Test
    fun drawing_the_screen_does_not_read_the_list() {
        DownloadsRepository.deferLoadOnThisThread = { true }

        DownloadsRepository.ensureLoadedForDisplay()

        assertEquals(0, probe.loads)
        assertFalse(DownloadsRepository.catalogReady.value)
        assertFalse(EventQueue.isDispatchThread())
    }

    @Test
    fun drawing_on_the_interface_thread_does_not_read_the_list() {
        DownloadsRepository.deferLoadOnThisThread = { EventQueue.isDispatchThread() }

        EventQueue.invokeAndWait {
            assertTrue(EventQueue.isDispatchThread())
            DownloadsRepository.ensureLoadedForDisplay()
        }

        assertEquals(0, probe.loads)
        assertFalse(DownloadsRepository.catalogReady.value)
    }

    @Test
    fun the_list_is_read_on_the_calling_thread_after_the_step_before_it() {
        val order = mutableListOf<String>()
        var loadThread: Thread? = null
        DownloadsRepository.beforeCatalogLoad = { order += "before" }
        DownloadsRepository.onCatalogLoad = {
            order += "load"
            loadThread = Thread.currentThread()
        }

        DownloadsRepository.ensureLoaded()

        assertEquals(listOf("before", "load"), order)
        assertEquals(1, probe.loads)
        assertEquals(Thread.currentThread(), loadThread)
        assertEquals(Thread.currentThread(), probe.loadThread)
        assertTrue(DownloadsRepository.catalogReady.value)
        assertFalse(EventQueue.isDispatchThread())
    }

    @Test
    fun a_reader_waits_until_the_step_before_the_list_has_finished() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        DownloadsRepository.beforeCatalogLoad = {
            started.countDown()
            release.await()
        }
        val reader = Thread({ DownloadsRepository.ensureLoaded() }, "catalog-reader")
        reader.start()
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertEquals(0, probe.loads)
            assertFalse(DownloadsRepository.catalogReady.value)
            release.countDown()
            reader.join(5_000)
            assertFalse(reader.isAlive)
            assertEquals(1, probe.loads)
            assertEquals(reader, probe.loadThread)
            assertTrue(DownloadsRepository.catalogReady.value)
            assertFalse(EventQueue.isDispatchThread())
        } finally {
            release.countDown()
            reader.join(5_000)
        }
    }

    @Test
    fun switching_profile_still_reads_the_list_on_the_caller() {
        DownloadsRepository.onProfileChanged()

        assertEquals(1, probe.loads)
        assertEquals(Thread.currentThread(), probe.loadThread)
        assertTrue(DownloadsRepository.catalogReady.value)
    }

    @Test
    fun recovery_finishes_before_the_follow_up_read_and_both_stay_off_the_caller() {
        val world = FolderWorld()
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        var runner: Thread? = null
        val folders = DesktopDownloadFolders(
            world.locations,
            world.validator,
            world.mover(),
            ProfileHooks(emptyList()),
        )
        val caller = Thread.currentThread()
        val waiter = Thread { folders.awaitRecovery() }
        try {
            folders.start(afterRecovery = {
                runner = Thread.currentThread()
                entered.countDown()
                // A follow-up read on the location thread waits here. The caller must not be that thread:
                // if it were, waiting would stick the test, so the caller returns and the check below fails.
                if (Thread.currentThread() !== caller) release.await(5, TimeUnit.SECONDS)
            })
            waiter.start()
            waiter.join(2_000)
            assertFalse(waiter.isAlive, "the recovery wait was still blocked after the move had been settled")
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(1L, release.count)
            assertEquals("download-locations", runner!!.name)
            assertTrue(runner !== Thread.currentThread())
            assertFalse(EventQueue.isDispatchThread())
            assertEquals(0, probe.loads)
        } finally {
            release.countDown()
            folders.stop()
            waiter.join(2_000)
            world.close()
        }
    }

    @Test
    fun a_pause_during_the_read_still_pauses_what_the_read_finds() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        probe.payload = payloadOf(stored("d1", "Downloading"))
        probe.beforeReturn = {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val loader = Thread({ DownloadsRepository.ensureLoaded() }, "catalog-loader")
        val done = CountDownLatch(1)
        val pauser = Thread({
            DownloadsRepository.pauseActiveDownloads()
            done.countDown()
        }, "catalog-pauser")
        loader.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            pauser.start()
            val returnedWhileReading = pauseReturnedWhileReadWasBlocked(pauser, done)
            release.countDown()
            loader.join(5_000)
            pauser.join(5_000)
            assertFalse(loader.isAlive)
            assertFalse(pauser.isAlive)
            assertEquals(1, probe.loads)
            assertFalse(returnedWhileReading)
            assertEquals(DownloadStatus.Paused, DownloadsRepository.uiState.value.items.single().status)
        } finally {
            release.countDown()
            loader.join(5_000)
            pauser.join(5_000)
        }
    }

    @Test
    fun a_pause_on_the_interface_thread_still_reads_the_list() {
        DownloadsRepository.deferLoadOnThisThread = { EventQueue.isDispatchThread() }

        EventQueue.invokeAndWait {
            assertTrue(EventQueue.isDispatchThread())
            DownloadsRepository.pauseActiveDownloads()
        }

        assertEquals(1, probe.loads)
        assertTrue(DownloadsRepository.catalogReady.value)
    }

    @Test
    fun a_failed_read_is_tried_again() {
        probe.failuresLeft = 1

        assertFails { DownloadsRepository.ensureLoaded() }

        assertEquals(1, probe.loads)
        assertFalse(DownloadsRepository.catalogReady.value)

        DownloadsRepository.ensureLoaded()

        assertEquals(2, probe.loads)
        assertTrue(DownloadsRepository.catalogReady.value)
    }

    @Test
    fun a_read_that_fails_after_publishing_does_not_keep_the_half_list() {
        probe.payload = payloadOf(stored("d1", "Downloading"))
        probe.startFailuresLeft = 1

        assertFails { DownloadsRepository.ensureLoaded() }

        assertFalse(DownloadsRepository.catalogReady.value)
        assertEquals(emptyList(), DownloadsRepository.uiState.value.items)

        DownloadsRepository.ensureLoaded()

        assertEquals("d1", DownloadsRepository.uiState.value.items.single().id)
        assertEquals(DownloadStatus.Downloading, DownloadsRepository.uiState.value.items.single().status)
        assertTrue(DownloadsRepository.catalogReady.value)
        assertEquals(2, probe.loads)
    }

    @Test
    fun the_location_thread_reads_the_list_and_a_pause_during_that_read_still_pauses_it() {
        val world = FolderWorld()
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        probe.payload = payloadOf(stored("d1", "Downloading"))
        probe.beforeReturn = {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val folders = DesktopDownloadFolders(
            world.locations,
            world.validator,
            world.mover(),
            ProfileHooks(emptyList()),
        )
        DownloadsRepository.deferLoadOnThisThread = { EventQueue.isDispatchThread() }
        DownloadsRepository.beforeCatalogLoad = {
            if (Thread.currentThread() !== folders.recoveryRunner) folders.awaitRecovery()
        }
        val done = CountDownLatch(1)
        val pauser = Thread({
            DownloadsRepository.pauseActiveDownloads()
            done.countDown()
        }, "catalog-pauser")
        try {
            folders.start(afterRecovery = { DownloadsRepository.ensureLoaded() })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            pauser.start()
            val returnedWhileReading = pauseReturnedWhileReadWasBlocked(pauser, done)
            release.countDown()
            pauser.join(5_000)
            assertFalse(pauser.isAlive)
            assertEquals("download-locations", probe.loadThread?.name)
            assertEquals(1, probe.loads)
            assertFalse(returnedWhileReading)
            assertEquals(DownloadStatus.Paused, DownloadsRepository.uiState.value.items.single().status)
            assertTrue(DownloadsRepository.catalogReady.value)
            DownloadsRepository.ensureLoaded()
            assertEquals(1, probe.loads)
        } finally {
            release.countDown()
            pauser.join(5_000)
            folders.stop()
            world.close()
        }
    }

    /** True when [thread] finished the pause while the read still held the list. */
    private fun pauseReturnedWhileReadWasBlocked(thread: Thread, done: CountDownLatch): Boolean {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            if (done.count == 0L) return true
            val state = thread.state
            if (state == Thread.State.BLOCKED || state == Thread.State.WAITING) return false
            Thread.yield()
        }
        return done.count == 0L
    }

    private fun stored(id: String, status: String) =
        """{"id":"$id","contentType":"series","parentMetaId":"tt1","parentMetaType":"series","videoId":"tt1:1:1","title":"Show",""" +
            """"streamTitle":"1080p","providerName":"Addon","sourceUrl":"https://example.com/$id.mkv","fileName":"$id.mkv",""" +
            """"status":"$status","createdAtEpochMs":1,"updatedAtEpochMs":2}"""

    private fun payloadOf(vararg items: String) = """{"items":[${items.joinToString(",")}]}"""
}
