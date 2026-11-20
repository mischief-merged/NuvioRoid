package com.nuvio.app.features.player

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.testDownload
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadedEpisodeVerificationTest {
    @Test
    fun a_damaged_download_offline_is_not_chosen_and_does_not_search() = runBlocking {
        val selected = mutableListOf<String>()
        val searching = mutableListOf<Boolean>()
        val job = start(
            offline = true,
            download = savedEpisode(),
            verify = { false },
            onSelected = { selected += it.id },
            onSearching = { searching += it },
        )
        job?.join()
        assertTrue(selected.isEmpty())
        assertTrue(searching.none { it })
    }

    @Test
    fun a_verified_download_offline_is_chosen_after_an_off_screen_check() = runBlocking {
        val selected = mutableListOf<String>()
        val searching = mutableListOf<Boolean>()
        val caller = Thread.currentThread()
        var worker: Thread? = null
        val job = start(
            offline = true,
            download = savedEpisode(),
            verify = {
                yield()
                worker = Thread.currentThread()
                true
            },
            onSelected = { selected += it.id },
            onSearching = { searching += it },
        )
        job?.join()
        assertEquals(listOf("saved"), selected)
        val checkedOn = worker
        assertTrue(checkedOn != null && checkedOn != caller, "check ran on ${checkedOn?.name}")
        assertTrue(checkedOn.name.startsWith("DefaultDispatcher-worker"), checkedOn.name)
        assertTrue(searching.none { it })
    }

    @Test
    fun offline_with_no_download_does_not_start_a_search() = runBlocking {
        val searching = mutableListOf<Boolean>()
        val job = start(offline = true, download = null, onSearching = { searching += it })
        assertNull(job)
        assertTrue(searching.isEmpty())
    }

    @Test
    fun a_damaged_download_online_falls_through_to_a_search() {
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        val selected = mutableListOf<String>()
        val started = CompletableDeferred<Unit>()
        try {
            val job = scope.start(
                offline = false,
                download = savedEpisode(),
                verify = { false },
                onSelected = { selected += it.id },
                onSearching = { if (it) started.complete(Unit) },
            )
            runBlocking { withTimeout(5_000) { started.await() } }
            assertTrue(selected.isEmpty())
            job?.cancel()
            runBlocking { withTimeout(5_000) { job?.join() } }
        } finally {
            parent.cancel()
        }
    }

    private fun CoroutineScope.start(
        offline: Boolean,
        download: DownloadItem?,
        verify: suspend (DownloadItem) -> Boolean = { false },
        onSelected: (DownloadItem) -> Unit = {},
        onSearching: (Boolean) -> Unit = {},
    ) = launchPlayerNextEpisodeAutoPlay(
        previousJob = null,
        nextEpisodeInfo = NextEpisodeInfo(
            videoId = "show:1:6", season = 1, episode = 6, title = "Episode 6",
            thumbnail = null, overview = null, released = null,
            hasAired = true, isWatched = false, unairedMessage = null,
        ),
        allEpisodes = listOf(
            MetaVideo(id = "show:1:6", title = "Episode 6", season = 1, episode = 6, thumbnail = null),
        ),
        parentMetaId = "show",
        parentMetaType = "series",
        contentType = "series",
        settings = PlayerSettingsUiState(),
        currentStreamBingeGroup = null,
        onDownloadedEpisodeSelected = { item, _ -> onSelected(item) },
        onEpisodeStreamSelected = { _, _ -> },
        onManualSelectionRequired = {},
        onSearchingChanged = onSearching,
        onSourceNameChanged = {},
        onCountdownChanged = {},
        onNextEpisodeCardVisibleChanged = {},
        isOffline = { offline },
        findDownloadedEpisode = { download },
        verifyDownloadedEpisode = verify,
    )

    private fun savedEpisode() = testDownload(
        episode = 6,
        status = DownloadStatus.Completed,
        showId = "show",
        id = "saved",
    )
}
