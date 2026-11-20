package com.nuvio.app.features.downloads

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DownloadStorageSummaryTest {
    @get:Rule
    val compose = createComposeRule()

    private val world = FolderWorld()

    @AfterTest
    fun clean_up() = world.close()

    private fun text(resource: StringResource, vararg arguments: Any) = runBlocking { getString(resource, *arguments) }

    private fun available(vararg ids: String) = (listOf(DownloadLocations.AppId) + ids).associateWith { FolderAvailability.Available }

    // What is counted

    @Test
    fun the_total_counts_what_is_on_disk_for_finished_partial_and_companion_files_and_nothing_else() {
        world.appFolder.createDirectories()
        val film = movieItem("m1", bytes = 999_999).copy(fileName = "Film_ab.mp4")
        val filmFile = world.appFolder.resolve("Film_ab.mp4").also { Files.write(it, ByteArray(1_000)) }
        world.appFolder.resolve("Film_ab.skiptimes.json").writeText("x".repeat(50))
        world.appFolder.resolve("Film_ab.mp4.subtitles").createDirectories().resolve("en.srt").writeText("y".repeat(200))
        val running = episodeItem("A", 1, status = DownloadStatus.Downloading, bytes = 5_000_000, ruleKey = "rule")
        Files.write(world.appFolder.resolve(running.fileName + ".part"), ByteArray(700))
        world.appFolder.resolve("unrelated.mp4").also { Files.write(it, ByteArray(9_999)) }
        val usage = DownloadStorageUsage(world.locations)

        val result = usage.current(listOf(film.copy(localFileUri = filmFile.toUri().toString()), running), available())

        assertEquals(1_000L + 200 + 700, result.perLocation.getValue(DownloadLocations.AppId).usedBytes, "announced sizes, siblings that only share a name or unrelated files were counted")
        assertEquals(700L, result.autoDownloadBytes)
        assertFalse(result.perLocation.getValue(DownloadLocations.AppId).lastKnown)
    }

    @Test
    fun a_location_that_is_away_keeps_its_last_known_size_and_is_marked_and_the_others_are_measured() {
        val drive = world.driveLocation("Archive")
        val onDrive = movieItem("d1", locationId = drive.id, bytes = 123).copy(fileName = "d1.mp4")
        val file = drive.path.resolve("d1.mp4").also { Files.write(it, ByteArray(4_000)) }
        val inApp = movieItem("a1").copy(fileName = "a1.mp4").also { world.appFolder.createDirectories(); Files.write(world.appFolder.resolve("a1.mp4"), ByteArray(1_500)) }
        val items = listOf(onDrive.copy(localFileUri = file.toUri().toString()), inApp)
        val usage = DownloadStorageUsage(world.locations)
        usage.current(items, available(drive.id))

        world.unplug("Archive")
        val away = usage.current(items, mapOf(DownloadLocations.AppId to FolderAvailability.Available, drive.id to FolderAvailability.NotConnected))

        assertEquals(LocationUsage(4_000, lastKnown = true), away.perLocation.getValue(drive.id))
        assertEquals(LocationUsage(1_500, lastKnown = false), away.perLocation.getValue(DownloadLocations.AppId))
        assertEquals(0L, away.autoDownloadBytes)
    }

    @Test
    fun a_location_never_measured_that_is_away_falls_back_to_what_the_records_say() {
        val drive = world.driveLocation("Archive")
        val item = movieItem("d1", locationId = drive.id, bytes = 777)
        world.unplug("Archive")

        val result = DownloadStorageUsage(world.locations).current(listOf(item), mapOf(DownloadLocations.AppId to FolderAvailability.Available, drive.id to FolderAvailability.NotConnected))

        assertEquals(LocationUsage(777, lastKnown = true), result.perLocation.getValue(drive.id))
    }

    // When it is measured again

    @Test
    fun the_disk_is_walked_again_only_when_something_changed_or_a_few_seconds_passed_while_transfers_run() {
        var now = 0L
        val usage = DownloadStorageUsage(world.locations, clock = { now }, whileRunningMs = 5_000, maxAgeMs = 60_000)
        val done = episodeItem("A", 1)
        val avail = available()

        usage.current(listOf(done), avail)
        usage.current(listOf(done), avail)
        assertEquals(1, usage.measurements, "measured again for nothing")

        usage.current(listOf(done.copy(status = DownloadStatus.Failed)), avail)
        assertEquals(2, usage.measurements, "a finished or changed download did not trigger it")

        usage.current(emptyList(), avail)
        assertEquals(3, usage.measurements, "a deleted download did not trigger it")

        usage.current(emptyList(), mapOf(DownloadLocations.AppId to FolderAvailability.NotConnected))
        assertEquals(4, usage.measurements, "a location changing state did not trigger it")

        val running = done.copy(status = DownloadStatus.Downloading)
        usage.current(listOf(running), avail)
        val afterRunning = usage.measurements
        now += 2_000
        usage.current(listOf(running.copy(downloadedBytes = 10)), avail) // progress alone is not a change
        assertEquals(afterRunning, usage.measurements, "measured on every progress step")
        now += 4_000
        usage.current(listOf(running.copy(downloadedBytes = 20)), avail)
        assertEquals(afterRunning + 1, usage.measurements, "not refreshed while a transfer runs")

        usage.invalidate()
        usage.current(listOf(running), avail)
        assertEquals(afterRunning + 2, usage.measurements, "a move did not trigger it")

        now += 61_000
        usage.current(listOf(done), avail)
        usage.current(listOf(done), avail)
        assertTrue(usage.measurements >= afterRunning + 3)
    }

    @Test
    fun the_controller_publishes_the_total_free_space_and_automatic_use_without_the_screen_waiting_for_the_disk() {
        world.appFolder.createDirectories()
        val auto = episodeItem("A", 1, ruleKey = "rule", bytes = 5).copy(fileName = "a.mp4")
        val file = world.appFolder.resolve("a.mp4").also { Files.write(it, ByteArray(2_048)) }
        val hooks = ProfileHooks(listOf(auto.copy(localFileUri = file.toUri().toString())))
        val folders = DesktopDownloadFolders(world.locations, world.validator, world.mover(), hooks)
        assertFalse(folders.state.value.usageMeasured)

        folders.refresh()

        val state = folders.state.value
        assertTrue(state.usageMeasured)
        assertEquals(2_048L, state.totalUsedBytes)
        assertEquals(2_048L, state.autoDownloadUsedBytes)
        assertTrue((state.totalFreeBytes ?: 0L) > 0L)
    }

    // The summary on screen

    private fun location(id: String, name: String, used: Long, free: Long?, availability: FolderAvailability = FolderAvailability.Available, lastKnown: Boolean = false) =
        DownloadLocationInfo(id, "/x/$name", name, isDefault = id == "app", isAppFolder = id == "app", availability = availability, itemCount = 1, totalBytes = used, freeBytes = free, usedBytes = used, usedIsLastKnown = lastKnown)

    private val gb = 1_000_000_000L

    @Test
    fun the_summary_is_one_readable_line_with_units_spoken_properly() {
        val state = DownloadFolderState(listOf(location("app", "downloads", 48 * gb + gb / 5, 512 * gb)), usageMeasured = true, totalFreeBytes = 512 * gb)
        compose.setContent { NuvioTheme { DownloadStorageSummary(state, autoLimitGb = null, onManageLocations = null) } }

        compose.onNodeWithText(text(Res.string.downloads_storage_summary, "48.2 GB", "512.0 GB")).assertExists()
        compose.onNode(hasContentDescription("Downloads use 48.2 gigabytes, 512.0 gigabytes free")).assertExists()
    }

    @Test
    fun the_limit_line_shows_a_labelled_progress_bar_that_exposes_its_value() {
        val state = DownloadFolderState(
            listOf(location("app", "downloads", 40_000_000_000, 100 * gb)),
            usageMeasured = true, totalFreeBytes = 100 * gb, autoDownloadUsedBytes = 31_000_000_000,
        )
        compose.setContent { NuvioTheme { DownloadStorageSummary(state, autoLimitGb = 100, onManageLocations = null) } }

        compose.onNodeWithText(text(Res.string.downloads_storage_auto, formatBytes(31_000_000_000), formatBytes(100_000_000_000))).assertExists()
        val spoken = text(Res.string.downloads_storage_auto_spoken, "31.0 gigabytes", "100.0 gigabytes")
        compose.onNode(
            hasContentDescription(spoken) and
                SemanticsMatcher("has progress") { node ->
                    val info = node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
                    info != null && kotlin.math.abs(info.current - 0.31f) < 0.01f
                },
        ).assertExists()
    }

    @Test
    fun without_a_limit_there_is_no_bar() {
        val state = DownloadFolderState(listOf(location("app", "downloads", 10, 20)), usageMeasured = true, totalFreeBytes = 20)
        compose.setContent { NuvioTheme { DownloadStorageSummary(state, autoLimitGb = null, onManageLocations = null) } }

        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
    }

    @Test
    fun several_locations_open_to_a_compact_list_with_the_one_that_is_away_marked_and_a_way_to_manage_them() {
        val state = DownloadFolderState(
            listOf(
                location("app", "downloads", 2 * gb, 50 * gb),
                location("loc-1", "Archive", 7 * gb, null, FolderAvailability.NotConnected, lastKnown = true),
            ),
            usageMeasured = true, totalFreeBytes = 50 * gb,
        )
        var managed = false
        compose.setContent { NuvioTheme { DownloadStorageSummary(state, autoLimitGb = null, onManageLocations = { managed = true }) } }

        // The headline counts what is connected.
        compose.onNodeWithText(text(Res.string.downloads_storage_summary, "2.0 GB", "50.0 GB")).assertExists()
        compose.onNode(hasText(text(Res.string.downloads_storage_location_line_away, "Archive", "7.0 GB"))).assertDoesNotExist()

        compose.onNodeWithText(text(Res.string.downloads_storage_show_locations)).performClick()

        compose.onNodeWithText(text(Res.string.downloads_storage_location_line, "downloads", "2.0 GB", "50.0 GB")).assertExists()
        compose.onNodeWithText(text(Res.string.downloads_storage_location_line_away, "Archive", "7.0 GB")).assertExists()
        compose.onNodeWithText(text(Res.string.downloads_storage_manage)).performClick()
        assertTrue(managed)
    }

    @Test
    fun before_the_first_measurement_the_line_says_so_instead_of_showing_zero() {
        compose.setContent { NuvioTheme { DownloadStorageSummary(DownloadFolderState(), autoLimitGb = null, onManageLocations = null) } }

        compose.onNodeWithText(text(Res.string.downloads_storage_measuring)).assertExists()
    }

    @Suppress("unused")
    private fun unusedPath(): Path = world.root
}
