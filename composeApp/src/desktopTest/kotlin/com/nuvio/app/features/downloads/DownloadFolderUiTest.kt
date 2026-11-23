package com.nuvio.app.features.downloads

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.settings.DownloadFolderSetting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DownloadFolderUiTest {
    val compose = createComposeRule()

    private val world = FolderWorld()
    private val store = DesktopStorage.Store(world.root.resolve("downloads.properties"))
    private val backend = object : DownloadsBackend by LocationsBackend(world) {
        override fun loadPayload(): String? = store.getString("downloads")
        override fun loadPayload(profileId: Int): String? = loadPayload()
        override fun savePayload(payload: String) = store.putString("downloads", payload)
        override fun savePayload(profileId: Int, payload: String) = savePayload(payload)
    }

    // The row reads the repository as well as the folder controller. Give both known test data.
    private val repository = TestRule { body, _ ->
        object : Statement() {
            override fun evaluate() {
                val previousBackend = DownloadsRepository.backend
                val previousBeforeLoad = DownloadsRepository.beforeCatalogLoad
                try {
                    DownloadsRepository.clearLocalState()
                    DownloadsRepository.backend = backend
                    DownloadsRepository.beforeCatalogLoad = {}
                    DownloadsRepository.ensureLoaded()
                    body.evaluate()
                } finally {
                    DownloadsRepository.clearLocalState()
                    DownloadsRepository.backend = previousBackend
                    DownloadsRepository.beforeCatalogLoad = previousBeforeLoad
                    world.close()
                }
            }
        }
    }

    // Dispose the composition and its load effect before restoring the shared repository.
    @get:Rule
    val rules: TestRule = RuleChain.outerRule(repository).around(compose)

    private class FakeFolders(initial: DownloadFolderState) : DownloadFolderController {
        override val supported = true
        val stateFlow = MutableStateFlow(initial)
        override val state: StateFlow<DownloadFolderState> = stateFlow
        val progressFlow = MutableStateFlow<MoveProgress?>(null)
        override val progress: StateFlow<MoveProgress?> = progressFlow
        var picked: String? = "/Volumes/Archive/Movies"
        var pickCalls = 0
        var free: Long? = 10_000_000_000L
        var checkResult: FolderCheck = FolderCheck.Ok
        val moves = mutableListOf<Pair<Set<String>, String>>()
        val defaults = mutableListOf<String>()
        val shown = mutableListOf<String>()
        var cancelCalls = 0
        var gate: CountDownLatch? = null

        override fun pickFolder(): String? {
            pickCalls++
            return picked
        }
        override fun check(path: String, requiredBytes: Long) = checkResult
        override fun bytesToCopy(itemIds: Set<String>, destinationPath: String): Long = bytes.filterKeys { it in itemIds }.values.sum()
        override fun freeBytesAt(path: String): Long? = free
        override fun setDefault(path: String): Boolean = defaults.add(path)
        override fun move(itemIds: Set<String>, destinationPath: String): MoveReport {
            moves += itemIds to destinationPath
            gate?.await(10, TimeUnit.SECONDS)
            return MoveReport(destinationPath, destinationPath.substringAfterLast('/'), moved = itemIds.size)
        }
        override fun cancelMove() {
            cancelCalls++
            gate?.countDown()
        }
        override fun showInFileManager(locationId: String): Boolean = shown.add(locationId)
        override fun locationIdOf(item: DownloadItem): String = item.locationId ?: "app"
        override fun refresh() = Unit
        override fun appFolderPath() = "/app/downloads"
        var bytes: Map<String, Long> = emptyMap()
    }

    private fun location(
        id: String,
        path: String,
        default: Boolean = false,
        items: Int = 0,
        bytes: Long = 0,
        availability: FolderAvailability = FolderAvailability.Available,
    ) = DownloadLocationInfo(
        id = id, path = path, name = path.substringAfterLast('/'), isDefault = default, isAppFolder = id == "app",
        availability = availability, itemCount = items, totalBytes = bytes, freeBytes = if (availability == FolderAvailability.Available) 5_000_000_000L else null,
    )

    private fun state(vararg locations: DownloadLocationInfo, moving: Boolean = false) =
        DownloadFolderState(locations.toList(), isMac = true, moving = moving)

    private fun text(resource: StringResource, vararg arguments: Any) = runBlocking { getString(resource, *arguments) }

    private fun isButton() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
    private fun isCheckbox() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)

    private val episodes = (1..2).map { episodeItem("A", it, bytes = 1_000L) }
    private val movie = movieItem("m1", bytes = 4_000L)

    private fun showFlow(folders: FakeFolders, request: MoveRequest, items: List<DownloadItem>, finished: () -> Unit = {}) {
        compose.setContent {
            NuvioTheme { DownloadMoveFlow(request = request, controller = folders, items = items, onFinished = finished) }
        }
    }

    private fun waitFor(label: String) = compose.waitUntil(5_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }

    // The setting row

    @Test
    fun the_setting_shows_the_folder_its_free_space_and_three_labelled_buttons() {
        val folders = FakeFolders(state(location("app", "/Users/g/Library/Downloads", default = true)))
        compose.setContent { NuvioTheme { DownloadFolderSetting(isTablet = false, controller = folders) } }

        compose.onNodeWithText(text(Res.string.settings_download_folder_title)).assertExists()
        compose.onAllNodesWithText("/Users/g/Library/Downloads").assertCountEquals(2)
        compose.onAllNodes(hasText(text(Res.string.settings_download_folder_free, formatBytes(5_000_000_000L)), substring = true)).assertCountEquals(1)
        compose.onNode(hasText(text(Res.string.settings_download_folder_change)) and isButton()).assertHasClickAction().assertIsEnabled()
        compose.onNode(hasText(text(Res.string.settings_download_folder_use_default)) and isButton()).assertIsNotEnabled()
        compose.onAllNodes(hasText(text(Res.string.settings_download_folder_show_finder)) and isButton())[0].assertIsEnabled()
    }

    @Test
    fun the_row_reads_as_one_item_with_the_folder_the_space_and_the_state() {
        val folders = FakeFolders(
            state(location("loc-1", "/Volumes/Archive/Movies", default = true, availability = FolderAvailability.NotConnected)),
        )
        compose.setContent { NuvioTheme { DownloadFolderSetting(isTablet = false, controller = folders) } }

        compose.onNode(
            hasContentDescription(text(Res.string.settings_download_folder_title), substring = true) and
                hasContentDescription(text(Res.string.settings_download_folder_not_connected), substring = true),
        ).assertExists()
        // The button in the row and the one in the list of locations are both off: the drive is not there.
        compose.onAllNodes(hasText(text(Res.string.settings_download_folder_show_finder)) and isButton()).assertCountEquals(2)
        compose.onAllNodes(hasText(text(Res.string.settings_download_folder_show_finder)) and isButton())[0].assertIsNotEnabled()
        compose.onAllNodes(hasText(text(Res.string.settings_download_folder_show_finder)) and isButton())[1].assertIsNotEnabled()
    }

    @Test
    fun the_storage_locations_are_listed_with_items_size_space_and_whether_they_are_connected() {
        val folders = FakeFolders(
            state(
                location("app", "/app/downloads", default = true, items = 3, bytes = 3_000),
                location("loc-1", "/Volumes/Archive/Movies", items = 1, bytes = 2_000, availability = FolderAvailability.NotConnected),
            ),
        )
        compose.setContent { NuvioTheme { DownloadFolderSetting(isTablet = false, controller = folders) } }

        compose.onNodeWithText(text(Res.string.settings_download_locations_title)).assertExists()
        compose.onNode(
            hasContentDescription("Movies", substring = true) and
                hasContentDescription(text(Res.string.settings_download_folder_not_connected), substring = true),
        ).assertExists()
        compose.onNode(hasContentDescription(text(Res.string.settings_download_location_default_tag), substring = true)).assertExists()
        // The location that is away cannot be shown, or moved from or to.
        compose.onNode(hasContentDescription(text(Res.string.settings_download_location_move_here_description, "Movies"))).assertIsNotEnabled()
        compose.onNode(hasContentDescription(text(Res.string.settings_download_location_move_away_description, "Movies"))).assertIsNotEnabled()
        compose.onNode(hasContentDescription(text(Res.string.settings_download_location_move_away_description, "downloads"))).assertIsEnabled()
    }

    @Test
    fun change_opens_the_picker_and_a_folder_with_nothing_to_move_is_just_saved() {
        assertTrue(DownloadsRepository.catalogReady.value)
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
        val folders = FakeFolders(state(location("app", "/app/downloads", default = true)))
        compose.setContent { NuvioTheme { DownloadFolderSetting(isTablet = false, controller = folders) } }

        compose.onNode(hasText(text(Res.string.settings_download_folder_change)) and isButton()).performClick()
        waitFor(text(Res.string.download_folder_confirm_save))
        compose.onNode(hasText(text(Res.string.download_folder_confirm_save)) and isButton()).performClick()
        compose.waitUntil(5_000) { folders.defaults.isNotEmpty() }

        assertEquals(1, folders.pickCalls)
        assertEquals(listOf("/Volumes/Archive/Movies"), folders.defaults)
        assertTrue(folders.moves.isEmpty())
    }

    @Test
    fun a_saved_download_offers_move_choices_and_leaving_it_keeps_its_file_and_record() {
        val file = world.appFolder.resolve("saved.mp4").writeVideo(7)
        val bytes = Files.readAllBytes(file)
        val item = movieItem("saved", locationId = "app", file = file, bytes = bytes.size.toLong())
        backend.savePayload(storedPayload(listOf(item)))
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()
        assertEquals(listOf(item.id), DownloadsRepository.uiState.value.items.map { it.id })
        val folders = FakeFolders(state(location("app", world.appFolder.toString(), default = true, items = 1, bytes = item.moveBytes)))
        compose.setContent { NuvioTheme { DownloadFolderSetting(isTablet = false, controller = folders) } }

        compose.onNode(hasText(text(Res.string.settings_download_folder_change)) and isButton()).performClick()
        waitFor(text(Res.string.download_folder_choice_all))
        compose.onNodeWithText(text(Res.string.download_folder_confirm_save)).assertDoesNotExist()
        compose.onNode(hasText(text(Res.string.download_folder_choice_none)) and isButton()).performClick()
        compose.waitUntil(5_000) { folders.defaults.isNotEmpty() }

        assertEquals(listOf("/Volumes/Archive/Movies"), folders.defaults)
        assertTrue(folders.moves.isEmpty())
        assertContentEquals(bytes, Files.readAllBytes(file))
        assertEquals(listOf(item), decodeStoredDownloads(backend.loadPayload()!!))
    }

    @Test
    fun the_change_button_works_from_the_keyboard() {
        val folders = FakeFolders(state(location("app", "/app/downloads", default = true)))
        compose.setContent { NuvioTheme { DownloadFolderSetting(isTablet = false, controller = folders) } }

        compose.onNode(hasText(text(Res.string.settings_download_folder_change)) and isButton()).requestFocus()
        compose.onNode(hasText(text(Res.string.settings_download_folder_change)) and isButton()).performKeyInput { pressKey(Key.Enter) }
        compose.waitUntil(5_000) { folders.pickCalls > 0 }

        assertEquals(1, folders.pickCalls)
    }

    // The steps of a move

    private val twoLocations = state(
        location("app", "/app/downloads", default = true, items = 3, bytes = 6_000),
        location("loc-1", "/Volumes/Archive/Movies", items = 1, bytes = 500),
    )

    @Test
    fun a_problem_with_the_folder_is_said_in_plain_words_before_anything_else() {
        val folders = FakeFolders(twoLocations).apply { checkResult = FolderCheck.Problem(FolderProblem.IsTrash) }
        showFlow(folders, MoveRequest.ChangeDefault("/Users/g/.Trash"), episodes)

        waitFor(text(Res.string.download_folder_problem_trash))
        compose.onNodeWithText(text(Res.string.download_folder_result_title_problem)).assertExists()
    }

    @Test
    fun the_choice_offers_move_everything_choose_what_to_move_and_leave_everything() {
        val folders = FakeFolders(twoLocations)
        showFlow(folders, MoveRequest.ChangeDefault("/Volumes/Archive/Movies"), episodes + movie)

        waitFor(text(Res.string.download_folder_choice_all))
        compose.onNode(hasText(text(Res.string.download_folder_choice_all)) and isButton()).assertIsEnabled()
        compose.onNode(hasText(text(Res.string.download_folder_choice_some)) and isButton()).assertIsEnabled()
        compose.onNode(hasText(text(Res.string.download_folder_choice_none)) and isButton()).assertIsEnabled()
        compose.onNode(hasText("3 items, ${formatBytes(6_000L)}", substring = true)).assertExists()
    }

    @Test
    fun leaving_everything_where_it_is_saves_the_folder_and_moves_nothing() {
        val folders = FakeFolders(twoLocations)
        showFlow(folders, MoveRequest.ChangeDefault("/Volumes/Archive/Movies"), episodes + movie)

        waitFor(text(Res.string.download_folder_choice_none))
        compose.onNode(hasText(text(Res.string.download_folder_choice_none)) and isButton()).performClick()
        compose.waitUntil(5_000) { folders.defaults.isNotEmpty() }

        assertEquals(listOf("/Volumes/Archive/Movies"), folders.defaults)
        assertTrue(folders.moves.isEmpty())
    }

    @Test
    fun the_checklist_groups_by_show_and_movie_with_a_running_total_and_the_free_space() {
        val folders = FakeFolders(twoLocations).apply { bytes = (episodes + movie).associate { it.id to it.moveBytes } }
        showFlow(folders, MoveRequest.ChangeDefault("/Volumes/Archive/Movies"), episodes + movie)
        waitFor(text(Res.string.download_folder_choice_some))
        compose.onNode(hasText(text(Res.string.download_folder_choice_some)) and isButton()).performClick()

        waitFor("Show A")
        compose.onNode(hasContentDescription("Show A", substring = true) and isCheckbox()).assertIsOff()
        compose.onNode(hasContentDescription("Film m1", substring = true) and isCheckbox()).assertIsOff()
        compose.onNode(hasText(text(Res.string.action_continue)) and isButton()).assertIsNotEnabled()

        compose.onNode(hasContentDescription("Show A", substring = true) and isCheckbox()).performClick()

        compose.onNode(hasContentDescription("Show A", substring = true) and isCheckbox()).assertIsOn()
        compose.onNode(hasText("2 items selected", substring = true)).assertExists()
        compose.onNode(hasText(text(Res.string.download_folder_checklist_free, formatBytes(10_000_000_000L), "Movies"))).assertExists()
        compose.onNode(hasText(text(Res.string.action_continue)) and isButton()).assertIsEnabled()

        // Episodes can be opened, and each is its own checkbox.
        compose.onNode(hasContentDescription(text(Res.string.download_folder_checklist_expand, "Show A")) and isButton()).performClick()
        compose.onNode(hasContentDescription("S01E01", substring = true) and isCheckbox()).assertIsOn()
        compose.onNode(hasContentDescription("S01E01", substring = true) and isCheckbox()).performClick()
        compose.onNode(hasText("1 item selected", substring = true)).assertExists()
    }

    @Test
    fun a_selection_that_does_not_fit_is_refused_and_one_that_barely_fits_is_warned_about() {
        val folders = FakeFolders(twoLocations).apply {
            bytes = (episodes + movie).associate { it.id to it.moveBytes }
            free = 3_000L
        }
        showFlow(folders, MoveRequest.ChangeDefault("/Volumes/Archive/Movies"), episodes + movie)
        waitFor(text(Res.string.download_folder_choice_some))
        compose.onNode(hasText(text(Res.string.download_folder_choice_some)) and isButton()).performClick()
        waitFor("Film m1")

        compose.onNode(hasContentDescription("Film m1", substring = true) and isCheckbox()).performClick()

        waitFor(text(Res.string.download_folder_checklist_does_not_fit))
        compose.onNode(hasText(text(Res.string.action_continue)) and isButton()).assertIsNotEnabled()

        compose.onNode(hasContentDescription("Film m1", substring = true) and isCheckbox()).performClick()
        compose.onNode(hasContentDescription("Show A", substring = true) and isCheckbox()).performClick()
        // 2,000 of 3,000 is room to spare; 2,900 would be tight.
        folders.bytes = folders.bytes.mapValues { 1_450L }
        compose.onNode(hasContentDescription("Show A", substring = true) and isCheckbox()).performClick()
        compose.onNode(hasContentDescription("Show A", substring = true) and isCheckbox()).performClick()
        waitFor(text(Res.string.download_folder_checklist_tight))
        compose.onNode(hasText(text(Res.string.action_continue)) and isButton()).assertIsEnabled()
    }

    @Test
    fun the_confirmation_says_how_many_how_much_from_where_to_where() {
        val folders = FakeFolders(twoLocations).apply { bytes = mapOf(episodes[0].id to 1_000L, episodes[1].id to 1_000L) }
        showFlow(folders, MoveRequest.MoveAllTo("/Volumes/Archive/Movies"), episodes)

        waitFor(text(Res.string.download_folder_confirm_title, "2 items"))
        compose.onNode(
            hasText(text(Res.string.download_folder_confirm_message, formatBytes(2_000L), "/app/downloads", "/Volumes/Archive/Movies")),
        ).assertExists()
        compose.onNode(hasText(text(Res.string.download_folder_confirm_move)) and isButton()).assertIsEnabled()
    }

    @Test
    fun a_confirmation_for_a_folder_without_room_cannot_be_accepted() {
        val folders = FakeFolders(twoLocations).apply {
            checkResult = FolderCheck.Problem(FolderProblem.NotEnoughSpace, 9_000L, 1_000L)
        }
        showFlow(folders, MoveRequest.MoveAllTo("/Volumes/Archive/Movies"), episodes)

        // The first check, before the confirmation, already refuses it.
        waitFor(text(Res.string.download_folder_result_title_problem))
        compose.onNodeWithText(text(Res.string.download_folder_problem_not_enough_space, formatBytes(9_000L), formatBytes(1_000L))).assertExists()
    }

    @Test
    fun a_move_shows_progress_with_a_cancel_and_ends_with_the_result() {
        val folders = FakeFolders(twoLocations).apply {
            gate = CountDownLatch(1)
            progressFlow.value = MoveProgress("Movies", totalFiles = 4, doneFiles = 1, totalBytes = 4_000, doneBytes = 1_000, currentName = "A-s1e2.mp4", currentBytes = 250, currentTotalBytes = 1_000)
        }
        var finished = false
        showFlow(folders, MoveRequest.MoveAllTo("/Volumes/Archive/Movies"), episodes) { finished = true }
        waitFor(text(Res.string.download_folder_confirm_move))
        compose.onNode(hasText(text(Res.string.download_folder_confirm_move)) and isButton()).performClick()

        waitFor(text(Res.string.download_folder_progress_title))
        compose.onNodeWithText(text(Res.string.download_folder_progress_file, 2, 4, "A-s1e2.mp4")).assertExists()
        // Both bars expose their value, so progress is not only a picture.
        compose.onNode(
            hasContentDescription(text(Res.string.download_folder_progress_overall)) and
                SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, androidx.compose.ui.semantics.ProgressBarRangeInfo(0.25f, 0f..1f)),
        ).assertExists()
        compose.onNode(
            hasContentDescription(text(Res.string.download_folder_progress_current)) and
                SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, androidx.compose.ui.semantics.ProgressBarRangeInfo(0.25f, 0f..1f)),
        ).assertExists()

        compose.onNode(hasText(text(Res.string.download_folder_progress_cancel)) and isButton()).performClick()

        assertEquals(1, folders.cancelCalls)
        waitFor(text(Res.string.download_folder_result_title))
        compose.onNodeWithText("2 items moved to Movies").assertExists()
        compose.onNode(hasText(text(Res.string.download_folder_result_ok)) and isButton()).performClick()
        compose.waitUntil(5_000) { finished }
    }

    @Test
    fun moving_from_the_downloads_screen_offers_the_folders_in_use_first_then_asks_for_another() {
        val folders = FakeFolders(
            state(
                location("app", "/app/downloads", default = true, items = 3),
                location("loc-1", "/Volumes/Archive/Movies", items = 1),
                location("loc-2", "/Volumes/Gone/Films", items = 1, availability = FolderAvailability.NotConnected),
            ),
        )
        showFlow(folders, MoveRequest.MoveItems(setOf(episodes[0].id)), episodes)

        waitFor(text(Res.string.downloads_move_destination_title))
        // The location the item is already in is not offered, one that is away cannot be chosen.
        compose.onNode(hasContentDescription(text(Res.string.downloads_move_destination_option, "Movies", "/Volumes/Archive/Movies"))).assertIsEnabled()
        compose.onNode(hasContentDescription(text(Res.string.downloads_move_destination_not_connected, "Films"))).assertIsNotEnabled()
        compose.onNode(hasContentDescription(text(Res.string.downloads_move_destination_option, "downloads", "/app/downloads"))).assertDoesNotExist()
        compose.onNode(hasText(text(Res.string.downloads_move_destination_other)) and isButton()).assertIsEnabled()

        compose.onNode(hasText(text(Res.string.downloads_move_destination_other)) and isButton()).performClick()
        compose.waitUntil(5_000) { folders.pickCalls > 0 }
        assertEquals(1, folders.pickCalls)
    }

    @Test
    fun choosing_a_folder_in_use_goes_to_the_confirmation_for_just_those_items() {
        val folders = FakeFolders(twoLocations).apply { bytes = mapOf(episodes[0].id to 1_000L) }
        showFlow(folders, MoveRequest.MoveItems(setOf(episodes[0].id)), episodes)
        waitFor(text(Res.string.downloads_move_destination_title))

        compose.onNode(hasContentDescription(text(Res.string.downloads_move_destination_option, "Movies", "/Volumes/Archive/Movies"))).performClick()
        waitFor(text(Res.string.download_folder_confirm_move))
        compose.onNode(hasText(text(Res.string.download_folder_confirm_move)) and isButton()).performClick()
        compose.waitUntil(5_000) { folders.moves.isNotEmpty() }

        assertEquals(listOf(setOf(episodes[0].id) to "/Volumes/Archive/Movies"), folders.moves)
        assertTrue(folders.defaults.isEmpty(), "a move from the screen must not change the default")
    }

    @Test
    fun the_report_lists_what_moved_what_failed_and_why_and_what_was_left_alone() {
        val report = MoveReport(
            "/Volumes/Archive/Movies", "Movies", moved = 12,
            failures = listOf(MoveFailure("Film.mp4", MoveFailureReason.NoSpace)), unreachable = 2,
        )
        lateinit var lines: List<String>
        compose.setContent { NuvioTheme { lines = moveReportLines(report); Text("x") } }
        compose.waitForIdle()

        assertEquals("12 items moved to Movies", lines[0])
        assertEquals("1 could not be moved: Film.mp4 (the new folder is full)", lines[1])
        assertEquals(text(Res.string.download_folder_result_unreachable, 2), lines[2])
    }
}
