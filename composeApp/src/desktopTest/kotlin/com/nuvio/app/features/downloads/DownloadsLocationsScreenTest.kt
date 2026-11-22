package com.nuvio.app.features.downloads

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.NuvioToastController
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Before
import org.junit.Rule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Downloads screen with more than one location, over real temporary folders and the real repository. */
@OptIn(ExperimentalTestApi::class)
class DownloadsLocationsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val world = FolderWorld()
    private val backend = LocationsBackend(world)
    private val realBackend = DownloadsRepository.backend
    private val folders = DesktopDownloadFolders(
        world.locations, world.validator, world.mover(), RepositoryDownloadsHooks,
    )

    @Before
    fun use_temporary_locations() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
    }

    @After
    fun restore() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        world.close()
    }

    private fun text(resource: StringResource, vararg arguments: Any) = runBlocking { getString(resource, *arguments) }

    private fun isButton() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun movieIn(location: DesktopDownloadLocation?, id: String): DownloadItem {
        val base = movieItem(id, locationId = location?.id, bytes = 5_000)
        val file = (location?.path ?: world.appFolder).resolve(base.fileName).writeVideo(id.hashCode())
        return base.copy(localFileUri = file.toUri().toString())
    }

    private fun show(vararg items: DownloadItem) {
        backend.payload = storedPayload(items.toList())
        compose.setContent {
            NuvioTheme {
                DownloadsScreenContent(
                    onBack = {},
                    onOpenDownload = {},
                    showAutoDownloads = false,
                    autoDownloads = null,
                    folders = folders,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun waitForText(label: String) = compose.waitUntil(5_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun an_item_on_an_unplugged_drive_says_drive_not_connected_cannot_be_played_and_a_banner_says_why() {
        val drive = world.driveLocation("Archive")
        val onDrive = movieIn(drive, "one")
        val inApp = movieIn(null, "two")
        world.unplug("Archive")

        show(onDrive, inApp)
        waitForText(text(Res.string.downloads_location_banner_title))

        compose.onNodeWithText(text(Res.string.downloads_status_drive_not_connected)).assertExists()
        compose.onNodeWithContentDescription(text(Res.string.downloads_play_description, "Film one")).assertIsNotEnabled()
        // The other item is untouched, and nothing was removed or failed.
        compose.onAllNodesWithText(text(Res.string.downloads_status_failed)).assertCountEquals(0)
        assertEquals(2, DownloadsRepository.uiState.value.items.size)
        assertTrue(DownloadsRepository.uiState.value.items.all { it.status == DownloadStatus.Completed })
        compose.onNode(hasText(text(Res.string.downloads_location_banner_message, "Movies"), substring = true)).assertExists()
    }

    @Test
    fun the_banner_goes_and_the_item_plays_again_when_the_drive_is_back() {
        val drive = world.driveLocation("Archive")
        val onDrive = movieIn(drive, "one")
        world.unplug("Archive")
        show(onDrive)
        waitForText(text(Res.string.downloads_status_drive_not_connected))

        world.replug("Archive")
        folders.refresh()

        compose.waitUntil(5_000) { compose.onAllNodesWithText(text(Res.string.downloads_status_drive_not_connected)).fetchSemanticsNodes().isEmpty() }
        compose.onAllNodesWithText(text(Res.string.downloads_location_banner_title)).assertCountEquals(0)
    }

    @Test
    fun when_more_than_one_location_is_in_use_each_row_says_where_it_is() {
        val drive = world.driveLocation("Archive")
        show(movieIn(drive, "one"), movieIn(null, "two"))
        waitForText(text(Res.string.downloads_row_location, "Movies"))

        compose.onNodeWithText(text(Res.string.downloads_row_location, "Movies")).assertExists()
        compose.onNodeWithText(text(Res.string.downloads_row_location, "downloads")).assertExists()
    }

    @Test
    fun with_one_location_the_rows_do_not_mention_it() {
        show(movieIn(null, "two"))
        compose.waitForIdle()

        compose.onAllNodesWithText(text(Res.string.downloads_row_location, "downloads")).assertCountEquals(0)
    }

    @Test
    fun a_download_waiting_for_its_drive_offers_to_choose_where_to_save_and_then_starts() {
        val drive = world.driveLocation("Archive")
        val waiting = movieItem("w", locationId = drive.id).copy(status = DownloadStatus.Paused, pausedByFolder = true, localFileUri = null)
        world.unplug("Archive")
        show(waiting)
        waitForText(text(Res.string.downloads_status_waiting_drive, "0 B"))

        compose.onNodeWithContentDescription(text(Res.string.downloads_choose_location_description, "Film w")).performClick()
        waitForText(text(Res.string.downloads_move_destination_title))
        compose.onNode(hasContentDescription(text(Res.string.downloads_move_destination_option, "downloads", world.appFolder.toString()))).performClick()
        compose.waitUntil(5_000) { backend.started.contains("w") }

        assertEquals(DownloadLocations.AppId, DownloadsRepository.uiState.value.items.single().locationId)
    }

    @Test
    fun a_row_has_a_move_action_with_a_label_that_names_it() {
        show(movieIn(null, "two"))
        compose.waitForIdle()

        compose.onNode(hasContentDescription(text(Res.string.downloads_move_description, "Film two")) and isButton()).performClick()

        waitForText(text(Res.string.downloads_move_destination_title))
    }

    @Test
    fun select_lets_several_downloads_be_chosen_and_move_acts_on_the_selection() {
        show(movieIn(null, "one"), movieIn(null, "two"))
        compose.waitForIdle()

        compose.onNodeWithText(text(Res.string.downloads_select)).performClick()
        compose.onNode(hasContentDescription(text(Res.string.downloads_select_description, "Film one"))).performClick()
        compose.onNode(hasContentDescription(text(Res.string.downloads_select_description, "Film two"))).performClick()

        compose.onNodeWithText(text(Res.string.downloads_select_count, 2)).assertExists()
        compose.onNode(hasContentDescription(text(Res.string.downloads_move_selected_description, 2)) and isButton()).performClick()
        waitForText(text(Res.string.downloads_move_destination_title))
    }

    @Test
    fun when_the_trash_fails_the_user_is_told_the_file_is_kept_and_no_permanent_delete_is_offered() {
        val item = movieIn(null, "one")
        backend.trashSucceeds = false
        show(item)
        compose.waitForIdle()

        compose.onNodeWithContentDescription(text(Res.string.downloads_delete_description, "Film one")).performClick()
        compose.onNodeWithText(text(Res.string.downloads_delete_confirm_trash)).performClick()
        compose.waitUntil(5_000) {
            NuvioToastController.currentToast.value?.message == text(Res.string.downloads_delete_not_moved_toast)
        }
        compose.waitForIdle()

        assertEquals(text(Res.string.downloads_delete_not_moved_toast), NuvioToastController.currentToast.value?.message)
        assertEquals(1, DownloadsRepository.uiState.value.items.size)
        assertTrue(backend.removedOutright.isEmpty(), "the app must never permanently delete user files")
        assertTrue(Files.exists(java.nio.file.Paths.get(java.net.URI(item.localFileUri!!))), "the file must remain on disk")
        compose.onAllNodesWithText("Delete permanently").assertCountEquals(0)
        compose.onAllNodesWithText(text(Res.string.action_cancel)).assertCountEquals(0)
    }

    @Test
    fun deleting_an_item_on_an_unplugged_drive_says_its_drive_is_not_connected() {
        val drive = world.driveLocation("Archive")
        val item = movieIn(drive, "one")
        show(item)
        compose.waitForIdle()
        world.unplug("Archive")

        compose.onNodeWithContentDescription(text(Res.string.downloads_delete_description, "Film one")).performClick()
        compose.onNodeWithText(text(Res.string.downloads_delete_confirm_trash)).performClick()
        compose.waitUntil(5_000) { NuvioToastController.currentToast.value?.message == text(Res.string.downloads_delete_not_connected_toast) }

        assertEquals(1, DownloadsRepository.uiState.value.items.size)
        assertTrue(backend.trashed.isEmpty())
        Files.exists(world.root)
    }
}
