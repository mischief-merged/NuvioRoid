package com.nuvio.app.features.downloads

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The folders of the desktop app: moving a show, a selection or everything, with drives coming and going. */
class DesktopDownloadFoldersTest {
    private val world = FolderWorld()

    @AfterTest
    fun clean_up() = world.close()

    private class Hooks(items: List<DownloadItem>) : ProfileHooks(items)

    private fun controller(hooks: Hooks, crashAt: String? = null) = DesktopDownloadFolders(
        locations = world.locations,
        validator = world.validator,
        mover = world.mover(checkpoint = { if (it == crashAt) throw Crash(it) }),
        hooks = hooks,
    )

    private fun episode(show: String, n: Int, status: DownloadStatus = DownloadStatus.Completed, bytes: Long = 200_000L): DownloadItem {
        val base = episodeItem(show, n, status = status, bytes = bytes)
        val file = world.appFolder.resolve(base.fileName).writeVideo(show.hashCode() + n)
        return base.copy(localFileUri = file.toUri().toString())
    }

    private fun fileOf(item: DownloadItem): Path = world.appFolder.resolve(item.fileName)

    @Test
    fun published_files_waiting_for_checks_are_never_moved_as_partial_files() {
        val drive = world.driveLocation("Archive")
        for ((index, status) in listOf(DownloadStatus.Paused, DownloadStatus.Downloading).withIndex()) {
            val pending = episode("A", index + 1, status).copy(awaitingCheck = true)
            val original = Files.readAllBytes(fileOf(pending))
            val hooks = Hooks(listOf(pending))
            val report = controller(hooks).move(setOf(pending.id), drive.path.toString())
            assertEquals(0, report.moved)
            assertEquals(listOf(MoveFailureReason.Busy), report.failures.map { it.reason })
            assertEquals(pending.locationId, hooks.items.single().locationId)
            assertEquals(pending.localFileUri, hooks.items.single().localFileUri)
            assertTrue(hooks.items.single().awaitingCheck)
            assertContentEquals(original, Files.readAllBytes(fileOf(pending)))
            assertFalse(Files.exists(drive.path.resolve(pending.fileName)))
        }
    }

    @Test
    fun moving_one_show_moves_its_episodes_and_leaves_every_other_download_alone() {
        val showA = (1..3).map { episode("A", it) }
        val showB = (1..2).map { episode("B", it) }
        val drive = world.driveLocation("Archive")
        val originals = (showA + showB).associate { it.id to Files.readAllBytes(fileOf(it)) }
        val hooks = Hooks(showA + showB)
        val folders = controller(hooks)

        val report = folders.move(showA.map { it.id }.toSet(), drive.path.toString())

        assertEquals(3, report.moved)
        assertEquals("Movies", report.destinationName)
        assertTrue(report.failures.isEmpty())
        showA.forEach {
            assertFalse(fileOf(it).exists())
            assertContentEquals(originals.getValue(it.id), Files.readAllBytes(drive.path.resolve(it.fileName)))
        }
        showB.forEach {
            assertContentEquals(originals.getValue(it.id), Files.readAllBytes(fileOf(it)))
            assertFalse(drive.path.resolve(it.fileName).exists())
        }
        assertEquals(setOf(drive.id), hooks.items.filter { it.parentMetaId == "A" }.map { it.locationId }.toSet())
        assertEquals(setOf<String?>(null), hooks.items.filter { it.parentMetaId == "B" }.map { it.locationId }.toSet())
    }

    @Test
    fun after_a_show_moved_its_next_episode_goes_with_it_and_other_shows_keep_going_to_the_default() {
        val showA = (1..2).map { episode("A", it) }
        val drive = world.driveLocation("Archive")
        val hooks = Hooks(showA)
        val folders = controller(hooks)

        folders.move(showA.map { it.id }.toSet(), drive.path.toString())

        assertEquals(drive.id, folders.placementFor(hooks.items, "A", isEpisode = true))
        assertEquals(DownloadLocations.AppId, folders.placementFor(hooks.items, "Z", isEpisode = true))
    }

    @Test
    fun the_state_lists_each_location_with_its_items_size_free_space_and_whether_it_is_connected() {
        val items = (1..2).map { episode("A", it, bytes = 1_000L) } + episode("B", 1, bytes = 500L)
        val drive = world.driveLocation("Archive")
        val hooks = Hooks(items)
        val folders = controller(hooks)
        folders.move(items.filter { it.parentMetaId == "A" }.map { it.id }.toSet(), drive.path.toString())

        folders.refresh()
        val state = folders.state.value

        val app = state.locations.first { it.isAppFolder }
        val onDrive = state.locations.first { it.id == drive.id }
        assertEquals(1, app.itemCount)
        assertEquals(500L, app.totalBytes)
        assertEquals(2, onDrive.itemCount)
        assertEquals(2_000L, onDrive.totalBytes)
        assertNotNull(onDrive.freeBytes)
        assertTrue(state.hasSeveralLocations)
        assertTrue(app.isDefault && !onDrive.isDefault)

        world.unplug("Archive")
        folders.refresh()

        val away = folders.state.value.locations.first { it.id == drive.id }
        assertEquals(FolderAvailability.NotConnected, away.availability)
        assertNull(away.freeBytes)
        assertEquals(listOf(drive.id), folders.state.value.unreachable.map { it.id })
        assertEquals(2, away.itemCount, "an item on a drive that is away is still counted")
    }

    @Test
    fun some_locations_can_be_connected_while_others_wait() {
        val a = episode("A", 1)
        val driveOne = world.driveLocation("One")
        val driveTwo = world.driveLocation("Two")
        val hooks = Hooks(listOf(a))
        val folders = controller(hooks)
        folders.move(setOf(a.id), driveOne.path.toString())
        val b = episode("B", 1)
        hooks.items = hooks.items + b
        folders.move(setOf(b.id), driveTwo.path.toString())

        world.unplug("One")
        folders.refresh()

        val moved = hooks.items.associateBy { it.id }
        assertFalse(folders.isConnected(moved.getValue(a.id)))
        assertTrue(folders.isConnected(moved.getValue(b.id)))
        assertTrue(folders.heldByFolder(moved.getValue(a.id)))
        assertFalse(folders.heldByFolder(moved.getValue(b.id)))
        assertEquals(FileLookup.Unreachable(driveOne.id, FolderAvailability.NotConnected), folders.lookup(moved.getValue(a.id)))
        assertTrue(folders.lookup(moved.getValue(b.id)) is FileLookup.Found)
    }

    @Test
    fun a_move_that_includes_an_item_on_an_unplugged_drive_moves_the_rest_and_reports_that_one() {
        val a = episode("A", 1)
        val b = episode("B", 1)
        val driveOne = world.driveLocation("One")
        val driveTwo = world.driveLocation("Two")
        val hooks = Hooks(listOf(a, b))
        val folders = controller(hooks)
        folders.move(setOf(a.id), driveOne.path.toString())
        val bytesOfA = Files.readAllBytes(driveOne.path.resolve(a.fileName))
        world.unplug("One")

        val report = folders.move(setOf(a.id, b.id), driveTwo.path.toString())

        assertEquals(1, report.moved)
        assertEquals(1, report.unreachable)
        assertTrue(driveTwo.path.resolve(b.fileName).exists())
        world.replug("One")
        assertContentEquals(bytesOfA, Files.readAllBytes(driveOne.path.resolve(a.fileName)), "the file on the drive that was away was touched")
    }

    @Test
    fun active_downloads_of_the_moved_items_pause_first_and_resume_after_and_are_held_meanwhile() {
        val done = episode("A", 1)
        val running = episodeItem("A", 2, status = DownloadStatus.Downloading, bytes = 5_000L)
        Files.createDirectories(world.appFolder)
        val partial = world.appFolder.resolve(running.fileName + ".part")
        Files.write(partial, ByteArray(5_000) { it.toByte() })
        val drive = world.driveLocation("Archive")
        val hooks = Hooks(listOf(done, running))
        val folders = controller(hooks)
        var heldWhileMoving: Boolean? = null
        hooks.onRelocate = { id -> if (id == running.id) heldWhileMoving = folders.heldByFolder(running) }

        val report = folders.move(setOf(done.id, running.id), drive.path.toString())

        assertEquals(2, report.moved)
        assertEquals(true, heldWhileMoving)
        assertFalse(folders.heldByFolder(hooks.items.first { it.id == running.id }.copy()))
        assertEquals("pause ${running.id}", hooks.log.first())
        assertTrue(hooks.log.indexOf("resume ${running.id}") > hooks.log.indexOf("relocate ${running.id}"))
        assertTrue(drive.path.resolve(running.fileName + ".part").exists())
        assertFalse(partial.exists())
    }

    @Test
    fun progress_is_reported_per_file_and_overall_and_clears_afterwards() {
        val items = (1..3).map { episode("A", it) }
        val drive = world.driveLocation("Archive")
        val hooks = Hooks(items)
        val folders = controller(hooks)
        var seen: MoveProgress? = null
        hooks.onRelocate = { seen = folders.progress.value }

        folders.move(items.map { it.id }.toSet(), drive.path.toString())

        val progress = assertNotNull(seen)
        assertEquals(3, progress.totalFiles)
        assertEquals("Movies", progress.destinationName)
        assertTrue(progress.totalBytes > 0)
        assertNull(folders.progress.value)
        assertFalse(folders.state.value.moving)
    }

    @Test
    fun cancelling_leaves_the_finished_files_moved_and_the_rest_in_place() {
        val items = (1..4).map { episode("A", it) }
        val drive = world.driveLocation("Archive")
        val hooks = Hooks(items)
        val folders = controller(hooks)
        hooks.onRelocate = { folders.cancelMove() }

        val report = folders.move(items.map { it.id }.toSet(), drive.path.toString())

        assertTrue(report.cancelled)
        assertEquals(1, report.moved)
        assertEquals(3, report.notReached)
        assertEquals(listOf(false, true, true, true), items.map { fileOf(it).exists() })
        assertEquals(listOf(true, false, false, false), items.map { drive.path.resolve(it.fileName).exists() })
        assertFalse(world.root.resolve("journal.json").exists())
    }

    @Test
    fun a_move_to_a_drive_that_is_not_there_does_not_start() {
        val item = episode("A", 1)
        val drive = world.driveLocation("Archive")
        world.unplug("Archive")
        val folders = controller(Hooks(listOf(item)))

        val report = folders.move(setOf(item.id), drive.path.toString())

        assertEquals(0, report.moved)
        assertEquals(FolderProblem.NotConnected, report.problem)
        assertTrue(fileOf(item).exists())
    }

    @Test
    fun a_move_cut_short_by_a_crash_keeps_both_files_changes_no_record_and_lists_the_leftover() {
        val done = episode("A", 1)
        val running = episodeItem("A", 9, status = DownloadStatus.Downloading, bytes = 5_000L)
        Files.createDirectories(world.appFolder)
        Files.write(world.appFolder.resolve(running.fileName + ".part"), ByteArray(5_000) { it.toByte() })
        val drive = world.driveLocation("Archive")
        val original = Files.readAllBytes(fileOf(done))
        val hooks = Hooks(listOf(done, running))
        assertFailsWith<Crash> { controller(hooks, crashAt = "published").move(setOf(done.id, running.id), drive.path.toString()) }

        val restarted = Hooks(hooks.items)
        val folders = controller(restarted)
        val report = assertNotNull(folders.recoverInterrupted())

        // Recovery touches no user file and re-points no record: it lists what it found.
        assertEquals(1, report.leftovers)
        assertEquals(0, report.unresolved)
        assertEquals(null, restarted.items.first { it.id == done.id }.locationId)
        assertContentEquals(original, Files.readAllBytes(fileOf(done)))
        assertContentEquals(original, Files.readAllBytes(drive.path.resolve(done.fileName)))
        assertTrue(world.tempsIn(drive.path).isEmpty(), "a temporary file of the move was left")
        folders.refresh()
        assertEquals(1, folders.state.value.leftovers.size)
        // A leftover does not block the next move.
        assertTrue(world.mover().unresolvedJournals().isEmpty())
    }

    @Test
    fun the_extra_copy_is_removed_from_the_settings_only_when_the_user_asks_and_the_record_points_at_the_other() {
        val done = episode("A", 1)
        val drive = world.driveLocation("Archive")
        val hooks = Hooks(listOf(done))
        val bytes = Files.readAllBytes(fileOf(done))
        assertFailsWith<Crash> { controller(hooks, crashAt = "published").move(setOf(done.id), drive.path.toString()) }
        val folders = controller(hooks)
        folders.recoverInterrupted()
        folders.refresh()
        val leftover = folders.state.value.leftovers.single()

        assertEquals(done.fileName, leftover.name)
        assertEquals(LeftoverOutcome.Removed, folders.removeExtraCopy(leftover.id))

        // The record points at the original, so the copy is the extra one: it goes to the Trash, not away.
        assertTrue(fileOf(done).exists())
        assertFalse(drive.path.resolve(done.fileName).exists())
        assertTrue(world.trash.holds(bytes))
        assertEquals(0, folders.state.value.leftovers.size)
    }

    @Test
    fun a_paused_item_whose_writer_has_not_exited_is_waited_for_or_skipped_and_never_copied() {
        val item = episodeItem("A", 3, status = DownloadStatus.Paused, bytes = 5_000L)
        Files.createDirectories(world.appFolder)
        val partial = world.appFolder.resolve(item.fileName + ".part").also { Files.write(it, ByteArray(5_000)) }
        val drive = world.driveLocation("Archive")
        val hooks = Hooks(listOf(item))
        val folders = DesktopDownloadFolders(world.locations, world.validator, world.mover(), hooks, writerWaitMs = 300)
        assertTrue(WriterTokens.tryAcquire(item.id, WriterTokens.Kind.Writer)) // the writer is still closing its file

        val skipped = folders.move(setOf(item.id), drive.path.toString())

        assertEquals(0, skipped.moved)
        assertEquals(MoveFailureReason.StillWriting, skipped.failures.single().reason)
        assertTrue(partial.exists() && !drive.path.resolve(partial.fileName).exists(), "a file that may still grow was copied")

        // The same writer exits shortly after the move started waiting: the move goes on after it.
        val exiting = Thread { Thread.sleep(100); WriterTokens.release(item.id) }.also { it.start() }
        val waited = DesktopDownloadFolders(world.locations, world.validator, world.mover(), hooks, writerWaitMs = 5_000).move(setOf(item.id), drive.path.toString())
        exiting.join()

        assertEquals(1, waited.moved)
        assertTrue(drive.path.resolve(partial.fileName).exists())
        assertFalse(WriterTokens.kindOf(item.id) != null, "the token was kept")
    }

    @Test
    fun two_records_in_one_folder_that_share_a_base_name_move_one_and_the_other_is_untouched() {
        val mkv = episodeItem("A", 1).copy(fileName = "movie.mkv")
        val mp4 = episodeItem("A", 2).copy(fileName = "movie.mp4")
        Files.createDirectories(world.appFolder)
        val mkvFile = world.appFolder.resolve("movie.mkv").writeVideo(1)
        val mp4File = world.appFolder.resolve("movie.mp4").writeVideo(2)
        val drive = world.driveLocation("Archive")
        val hooks = Hooks(listOf(mkv.copy(localFileUri = mkvFile.toUri().toString()), mp4.copy(localFileUri = mp4File.toUri().toString())))
        val before = Files.readAllBytes(mkvFile)

        val report = controller(hooks).move(setOf(mp4.id), drive.path.toString())

        assertEquals(1, report.moved)
        assertTrue(drive.path.resolve("movie.mp4").exists())
        assertFalse(drive.path.resolve("movie.mkv").exists())
        assertContentEquals(before, Files.readAllBytes(mkvFile), "the other record's file was touched")
    }

    @Test
    fun a_record_that_owns_a_path_of_the_item_stops_that_item_and_is_reported() {
        val a = episodeItem("A", 1).copy(fileName = "same.mp4")
        val b = episodeItem("B", 1).copy(fileName = "same.mp4")
        Files.createDirectories(world.appFolder)
        val file = world.appFolder.resolve("same.mp4").writeVideo(1)
        val drive = world.driveLocation("Archive")
        val hooks = Hooks(listOf(a.copy(localFileUri = file.toUri().toString()), b.copy(localFileUri = file.toUri().toString())))

        val report = controller(hooks).move(setOf(a.id), drive.path.toString())

        assertEquals(0, report.moved)
        assertEquals(MoveFailureReason.OwnedByAnother, report.failures.single().reason)
        assertTrue(file.exists())
    }

    @Test
    fun a_download_that_will_not_stop_is_not_moved_and_nothing_of_it_is_touched() {
        val running = episodeItem("A", 2, status = DownloadStatus.Downloading, bytes = 5_000L)
        Files.createDirectories(world.appFolder)
        val partial = world.appFolder.resolve(running.fileName + ".part")
        Files.write(partial, ByteArray(5_000) { it.toByte() })
        val drive = world.driveLocation("Archive")
        val hooks = Hooks(listOf(running)).apply { stopFails = true }

        val report = controller(hooks).move(setOf(running.id), drive.path.toString())

        assertEquals(0, report.moved)
        assertEquals(MoveFailureReason.StillWriting, report.failures.single().reason)
        assertTrue(partial.exists())
        assertFalse(ItemLocks.isHeld(running.id), "the lock was kept")
    }

    @Test
    fun an_item_being_moved_cannot_be_deleted_and_an_item_that_is_playing_is_skipped_and_reported() {
        val a = episode("A", 1)
        val b = episode("A", 2)
        val drive = world.driveLocation("Archive")
        val hooks = Hooks(listOf(a, b))
        val folders = DesktopDownloadFolders(
            world.locations, world.validator,
            world.mover(), hooks,
            isPlaying = { it.id == b.id },
        )
        var deleteWhileMoving: Boolean? = null
        var heldInState: Set<String>? = null
        hooks.onRelocate = {
            deleteWhileMoving = ItemLocks.tryAcquire(a.id)
            heldInState = folders.state.value.movingIds
        }

        val report = folders.move(setOf(a.id, b.id), drive.path.toString())

        assertEquals(false, deleteWhileMoving, "a delete could take the item while it was moving")
        assertEquals(setOf(a.id), heldInState)
        assertEquals(1, report.moved)
        assertEquals(listOf(MoveFailure(b.fileName, MoveFailureReason.Playing)), report.failures)
        assertTrue(fileOf(b).exists())
        assertFalse(ItemLocks.isHeld(a.id))
    }

    @Test
    fun a_move_does_not_start_when_the_destination_is_inside_a_download_folder_or_is_the_trash() {
        val a = episode("A", 1)
        val drive = world.driveLocation("Archive")
        val hooks = Hooks(listOf(a))
        val folders = controller(hooks)
        folders.move(setOf(a.id), drive.path.toString())

        val inside = folders.move(setOf(a.id), drive.path.resolve("sub").toString())
        val trash = folders.move(setOf(a.id), world.root.resolve("home/.Trash").toString())

        assertEquals(FolderProblem.OverlapsDownloadFolder, inside.problem)
        assertEquals(FolderProblem.IsTrash, trash.problem)
        assertEquals(0, inside.moved + trash.moved)
        assertTrue(drive.path.resolve(a.fileName).exists())
    }

    @Test
    fun moving_a_folder_into_itself_moves_nothing() {
        val a = episode("A", 1)
        val hooks = Hooks(listOf(a))

        val report = controller(hooks).move(setOf(a.id), world.appFolder.toString())

        assertEquals(0, report.moved)
        assertTrue(fileOf(a).exists())
    }

    @Test
    fun a_location_stays_while_another_profile_still_uses_it() {
        val item = episode("A", 1)
        val first = world.driveLocation("One")
        val second = world.driveLocation("Two")
        val hooks = Hooks(listOf(item))
        val folders = controller(hooks)
        folders.move(setOf(item.id), first.path.toString())
        // Another profile's stored list has a download in the first location.
        hooks.setList(2, listOf(movieItem("x", locationId = first.id, file = first.path.resolve("x.mp4").writeVideo(9))))

        folders.move(setOf(item.id), second.path.toString())

        assertTrue(first.id in world.locations.all().map { it.id }, "forgotten although another profile uses it")
        hooks.setList(2, emptyList())
        folders.refresh()
        folders.move(setOf(item.id), first.path.toString())
        assertTrue(second.id !in world.locations.all().map { it.id } || second.path != world.locations.defaultLocation().path)
    }

    @Test
    fun a_profile_switched_during_a_move_stops_it_safely_and_changes_nothing_in_the_other_profile() {
        val items = (1..3).map { episode("A", it) }
        val drive = world.driveLocation("Archive")
        val hooks = Hooks(items)
        val folders = controller(hooks)
        hooks.onRelocate = { hooks.profile = 2 } // the profile changes after the first item

        val report = folders.move(items.map { it.id }.toSet(), drive.path.toString())

        assertTrue(report.cancelled)
        assertEquals(1, report.moved)
        assertEquals(listOf(false, true, true), items.map { fileOf(it).exists() })
        assertEquals(1, hooks.log.count { it.startsWith("relocate") })
    }

    @Test
    fun a_move_counts_every_byte_on_every_volume_because_it_always_copies() {
        val item = episode("A", 1)
        val drive = world.driveLocation("Archive")
        val size = Files.size(fileOf(item))
        val folders = DesktopDownloadFolders(world.locations, world.validator, world.mover(), Hooks(listOf(item)))

        // The temporary folders share one volume here: the copy still needs its size, and the original stays in the Trash.
        assertEquals(size, folders.bytesToCopy(setOf(item.id), drive.path.toString()))
    }

    @Test
    fun a_location_coming_back_resumes_the_downloads_that_waited_and_wakes_auto_download() {
        val item = episode("A", 1)
        val drive = world.driveLocation("Archive")
        val hooks = Hooks(listOf(item))
        var woke = 0
        val folders = DesktopDownloadFolders(
            world.locations, world.validator, world.mover(), hooks, onReturned = { woke++ },
        )
        folders.move(setOf(item.id), drive.path.toString())
        hooks.log.clear()

        folders.refresh()
        world.unplug("Archive")
        folders.refresh()
        assertFalse("resumeHeld" in hooks.log)
        world.replug("Archive")
        folders.refresh()

        assertTrue("resumeHeld" in hooks.log)
        assertEquals(1, woke)
    }

    @Test
    fun a_location_that_holds_nothing_after_a_move_is_forgotten_but_the_default_stays() {
        val item = episode("A", 1)
        val first = world.driveLocation("One")
        val second = world.driveLocation("Two")
        val hooks = Hooks(listOf(item))
        val folders = controller(hooks)
        folders.move(setOf(item.id), first.path.toString())

        folders.move(setOf(item.id), second.path.toString())

        // The first location holds nothing now and is forgotten; the one in use stays (under the id it was registered with).
        assertEquals(listOf(world.appFolder, second.path), world.locations.all().map { it.path })
        assertEquals(second.path, world.locations.all().last().path)
    }

    @Test
    fun setting_the_default_changes_where_new_downloads_go_and_moves_nothing() {
        val item = episode("A", 1)
        val drive = world.driveLocation("Archive")
        val folders = controller(Hooks(listOf(item)))

        assertTrue(folders.setDefault(drive.path.toString()))

        assertEquals(drive.id, folders.state.value.defaultLocation?.id)
        assertEquals(drive.id, folders.placementFor(emptyList(), "new", isEpisode = true))
        assertTrue(fileOf(item).exists())
        assertFalse(folders.setDefault(world.root.resolve("not-there").toString()))
    }
}
