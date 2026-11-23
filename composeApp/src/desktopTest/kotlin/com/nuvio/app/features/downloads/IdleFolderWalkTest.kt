package com.nuvio.app.features.downloads

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * While nothing is asking for sizes, a running transfer must not re-list the download folder
 * every few seconds. Idle is stricter still: the folder is not walked on a timer at all.
 */
class IdleFolderWalkTest {
    private val world = FolderWorld()

    @AfterTest
    fun clean_up() = world.close()

    private fun available() = mapOf(DownloadLocations.AppId to FolderAvailability.Available)

    @Test
    fun a_running_transfer_does_not_relist_the_folder_every_few_seconds() {
        var now = 0L
        val usage = DownloadStorageUsage(world.locations, clock = { now })
        val running = episodeItem("A", 1, status = DownloadStatus.Downloading, bytes = 100)
        val avail = available()

        usage.current(listOf(running), avail)
        val start = usage.measurements
        repeat(5) { step ->
            now += 5_000
            usage.current(listOf(running.copy(downloadedBytes = 100L + step)), avail)
        }

        assertTrue(
            usage.measurements - start <= 1,
            "walked ${usage.measurements - start} times across 25 seconds of one transfer",
        )
    }

    @Test
    fun an_idle_list_is_not_walked_again_within_a_minute() {
        var now = 0L
        val usage = DownloadStorageUsage(world.locations, clock = { now })
        val done = episodeItem("A", 1)
        val avail = available()

        usage.current(listOf(done), avail)
        repeat(19) {
            now += 3_000
            usage.current(listOf(done), avail)
        }

        assertEquals(1, usage.measurements, "an idle minute walked the folder ${usage.measurements} times")
    }

    @Test
    fun an_idle_location_check_does_not_list_the_download_folder() {
        val folders = folders(ProfileHooks(listOf(episodeItem("A", 1))))

        repeat(20) { folders.poll() }

        assertEquals(0, folders.usage.measurements, "idle checks walked the folder ${folders.usage.measurements} times")
    }

    @Test
    fun sizes_are_measured_once_when_the_screen_opens_and_not_again_within_a_minute() {
        var now = 0L
        val usage = DownloadStorageUsage(world.locations, clock = { now })
        val folders = folders(ProfileHooks(listOf(episodeItem("A", 1))), usage)
        folders.setUsageWatched(true)

        folders.poll()
        repeat(19) {
            now += 3_000
            folders.poll()
        }

        assertEquals(1, usage.measurements, "an open idle screen walked the folder ${usage.measurements} times in 57 seconds")
        now += 3_000
        folders.poll()
        assertEquals(2, usage.measurements, "the once-a-minute measure did not happen")
    }

    @Test
    fun progress_while_the_screen_is_closed_does_not_list_the_folder_and_finishing_lists_it_once() {
        val running = episodeItem("A", 1, status = DownloadStatus.Downloading, bytes = 100)
        val hooks = ProfileHooks(listOf(running))
        val folders = folders(hooks)

        repeat(5) { step ->
            hooks.items = listOf(running.copy(downloadedBytes = 100L + step))
            folders.poll()
        }
        assertEquals(0, folders.usage.measurements, "progress walked the folder ${folders.usage.measurements} times")

        hooks.items = listOf(running.copy(status = DownloadStatus.Completed))
        folders.poll()
        folders.poll()

        assertEquals(1, folders.usage.measurements, "finishing walked the folder ${folders.usage.measurements} times")
    }

    @Test
    fun a_running_transfer_on_screen_is_listed_once_per_thirty_seconds() {
        var now = 0L
        val usage = DownloadStorageUsage(world.locations, clock = { now })
        val running = episodeItem("A", 1, status = DownloadStatus.Downloading, bytes = 100)
        val hooks = ProfileHooks(listOf(running))
        val folders = folders(hooks, usage)
        folders.setUsageWatched(true)

        folders.poll()
        repeat(5) { step ->
            now += 5_000
            hooks.items = listOf(running.copy(downloadedBytes = 100L + step))
            folders.poll()
        }

        assertEquals(1, usage.measurements, "walked ${usage.measurements} times across 25 seconds on screen")
        now += 5_000
        folders.poll()
        assertEquals(2, usage.measurements, "did not list the folder again at 30 seconds")
    }

    @Test
    fun closing_the_screen_stops_the_short_measure() {
        var now = 0L
        val usage = DownloadStorageUsage(world.locations, clock = { now })
        val running = episodeItem("A", 1, status = DownloadStatus.Downloading, bytes = 100)
        val hooks = ProfileHooks(listOf(running))
        val folders = folders(hooks, usage)
        folders.setUsageWatched(true)
        folders.poll()
        folders.setUsageWatched(false)

        now += 30_000
        hooks.items = listOf(running.copy(downloadedBytes = 50))
        folders.poll()

        assertEquals(1, usage.measurements, "closing the screen still walked the folder at 30 seconds")
    }

    private fun folders(hooks: ProfileHooks, usage: DownloadStorageUsage = DownloadStorageUsage(world.locations)) =
        DesktopDownloadFolders(world.locations, world.validator, world.mover(), hooks, usage = usage)
}
