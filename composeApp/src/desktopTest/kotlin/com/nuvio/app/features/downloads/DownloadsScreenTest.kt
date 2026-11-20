package com.nuvio.app.features.downloads

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.NuvioToastController
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.downloads_kept
import nuvio.composeapp.generated.resources.downloads_undo_keep
import nuvio.composeapp.generated.resources.downloads_keep_description
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.downloads_announce_checking
import nuvio.composeapp.generated.resources.downloads_announce_downloading
import nuvio.composeapp.generated.resources.downloads_announce_row
import nuvio.composeapp.generated.resources.downloads_delete_description
import nuvio.composeapp.generated.resources.downloads_empty_browse
import nuvio.composeapp.generated.resources.downloads_empty_hint
import nuvio.composeapp.generated.resources.downloads_empty_hint_seasons
import nuvio.composeapp.generated.resources.downloads_pause_description
import nuvio.composeapp.generated.resources.downloads_play_description
import nuvio.composeapp.generated.resources.downloads_resume_description
import nuvio.composeapp.generated.resources.downloads_section_active
import nuvio.composeapp.generated.resources.downloads_section_movies
import nuvio.composeapp.generated.resources.downloads_section_shows
import nuvio.composeapp.generated.resources.episodes_season
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.downloads_delete_confirm_partial
import nuvio.composeapp.generated.resources.downloads_delete_confirm_trash
import nuvio.composeapp.generated.resources.downloads_delete_message_held
import nuvio.composeapp.generated.resources.downloads_auto_keep_description
import nuvio.composeapp.generated.resources.downloads_row_deletes_in_hours
import nuvio.composeapp.generated.resources.downloads_row_deletes_soon
import com.nuvio.app.features.details.MetaDetails
import kotlinx.coroutines.flow.MutableStateFlow
import nuvio.composeapp.generated.resources.downloads_delete_auto_note
import nuvio.composeapp.generated.resources.downloads_delete_message_partial
import nuvio.composeapp.generated.resources.downloads_delete_message_trash
import nuvio.composeapp.generated.resources.downloads_delete_not_moved_toast
import nuvio.composeapp.generated.resources.downloads_delete_title
import nuvio.composeapp.generated.resources.downloads_choose_another_source
import nuvio.composeapp.generated.resources.downloads_episode_details_description
import nuvio.composeapp.generated.resources.downloads_go_to_season_description
import nuvio.composeapp.generated.resources.downloads_go_to_show_description
import nuvio.composeapp.generated.resources.nav_episode_details
import nuvio.composeapp.generated.resources.nav_go_to_season
import nuvio.composeapp.generated.resources.downloads_run_dismiss_description
import nuvio.composeapp.generated.resources.downloads_choose_another_source_description
import nuvio.composeapp.generated.resources.downloads_run_problem_failed
import nuvio.composeapp.generated.resources.downloads_run_problem_not_found
import nuvio.composeapp.generated.resources.downloads_run_problem_row
import nuvio.composeapp.generated.resources.downloads_run_failed
import nuvio.composeapp.generated.resources.downloads_run_not_found
import nuvio.composeapp.generated.resources.downloads_run_existing
import nuvio.composeapp.generated.resources.downloads_run_added
import nuvio.composeapp.generated.resources.downloads_run_title_season
import nuvio.composeapp.generated.resources.downloads_runs_heading
import nuvio.composeapp.generated.resources.downloads_speed_limit_change_description
import nuvio.composeapp.generated.resources.downloads_speed_limit_line
import nuvio.composeapp.generated.resources.settings_download_speed_limit_value
import nuvio.composeapp.generated.resources.downloads_clear_failed
import nuvio.composeapp.generated.resources.downloads_error_not_enough_space
import nuvio.composeapp.generated.resources.downloads_failed_count
import nuvio.composeapp.generated.resources.downloads_failed_next_bad_file
import nuvio.composeapp.generated.resources.downloads_failed_next_not_enough_space
import nuvio.composeapp.generated.resources.downloads_failed_next_other
import nuvio.composeapp.generated.resources.downloads_failed_next_source_refused
import nuvio.composeapp.generated.resources.downloads_empty_title
import nuvio.composeapp.generated.resources.downloads_open_directory
import nuvio.composeapp.generated.resources.downloads_result_picture_ok
import nuvio.composeapp.generated.resources.downloads_store_problem_earlier_copy
import nuvio.composeapp.generated.resources.downloads_store_problem_not_saving
import nuvio.composeapp.generated.resources.downloads_store_problem_started_empty
import nuvio.composeapp.generated.resources.downloads_store_problem_title
import nuvio.composeapp.generated.resources.downloads_retry_all_failed
import nuvio.composeapp.generated.resources.downloads_status_failed_reason
import nuvio.composeapp.generated.resources.downloads_try_source_again
import nuvio.composeapp.generated.resources.downloads_status_auto
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Before
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Downloads screen, driven by the real repository over an in-memory store. No network, no real folder, no real Trash. */
@OptIn(ExperimentalTestApi::class)
class DownloadsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    internal class FakeBackend : DownloadsBackend {
        var payload: String? = null
        val started = mutableListOf<String>()
        val progress = mutableMapOf<String, (Long, Long?) -> Unit>()
        var previousPayload: String? = null
        var puttingAsideFails = false
        val trashed = mutableListOf<String>()
        val removedOutright = mutableListOf<String?>()
        var trashSucceeds = true
        override var usesSystemTrash = true

        override fun trashFile(localFileUri: String): Boolean {
            if (trashSucceeds) trashed += localFileUri
            return trashSucceeds
        }

        override fun loadPayload(): String? = payload
        override fun loadPreviousPayload(): String? = previousPayload
        override fun saveUnreadablePayload(payload: String, atEpochMs: Long) {
            if (puttingAsideFails) error("the store is read-only")
        }

        override fun savePayload(payload: String) {
            this.payload = payload
        }

        override fun restoreItem(item: DownloadItem): DownloadItem = item

        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
            onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
            onFailure: (message: String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle {
            started += request.item.id
            progress[request.item.id] = onProgress
            return object : DownloadsTaskHandle {
                override fun cancel() = Unit
            }
        }

        override fun removeFile(localFileUri: String?): Boolean {
            removedOutright += localFileUri
            return true
        }

        override fun removePartialFile(destinationFileName: String): Boolean = true
        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri
    }

    private val backend = FakeBackend()
    private val realBackend = DownloadsRepository.backend

    private fun clearBatchRuns() {
        SeasonDownloadDefaultsStorage.saveBatchRunsPayload(com.nuvio.app.features.profiles.ProfileRepository.activeProfileId, "")
    }

    @Before
    fun use_an_in_memory_store() {
        clearBatchRuns()
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
    }

    @After
    fun restore_the_real_repository() {
        clearBatchRuns()
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
    }

    private fun text(resource: StringResource, vararg arguments: Any) = runBlocking { getString(resource, *arguments) }

    private fun text(resource: PluralStringResource, quantity: Int) = runBlocking { getPluralString(resource, quantity, quantity) }

    /** One stored download with the fields that have no default, plus [extra]. */
    private fun stored(
        id: String,
        status: String,
        title: String = "Pantheon",
        season: Int? = 2,
        episode: Int? = 3,
        extra: String = "",
        updatedAt: Long = System.currentTimeMillis(),
    ): String {
        val kind = if (season != null) {
            """"contentType":"series","parentMetaType":"series","seasonNumber":$season,"episodeNumber":$episode,"videoId":"tt1:$season:$episode","parentMetaId":"tt1","""
        } else {
            """"contentType":"movie","parentMetaType":"movie","videoId":"tt2","parentMetaId":"tt2","""
        }
        return """{"id":"$id",$kind"title":"$title","streamTitle":"1080p","providerName":"Addon",""" +
            """"sourceUrl":"https://example.com/$id.mkv","fileName":"$id.mkv","status":"$status",""" +
            """"createdAtEpochMs":1,"updatedAtEpochMs":$updatedAt$extra}"""
    }

    private var chooseSourceFor: DownloadItem? = null
    private var folderOpened = 0
    private var episodeLinks: DownloadEpisodeLinks? = null
    private val linkCalls = mutableListOf<String>()

    private fun recordingLinks() = DownloadEpisodeLinks(
        onEpisodeDetails = { linkCalls += "details:${it.id}" },
        onGoToSeason = { linkCalls += "season:${it.id}" },
        onGoToShow = { linkCalls += "show:${it.id}" },
    )
    private val rememberedDeletions = mutableListOf<Pair<String, Int>>()

    private fun show(vararg items: String, withChooseSource: Boolean = true) {
        backend.payload = """{"items":[${items.joinToString(",")}]}"""
        showPayload(withChooseSource)
    }

    private fun showPayload(withChooseSource: Boolean = true) {
        compose.setContent {
            NuvioTheme {
                DownloadsScreen(
                    onBack = {},
                    onOpenDownload = {},
                    showAutoDownloads = false,
                    onChooseAnotherSource = if (withChooseSource) ({ chooseSourceFor = it }) else null,
                    openDownloadsFolder = {
                        folderOpened++
                        true
                    },
                    rememberDeletion = { rememberedDeletions += it.id to DownloadsRepository.uiState.value.items.size },
                    episodeLinks = episodeLinks,
                )
            }
        }
    }

    private fun failedRow(id: String, kind: String?, message: String, episode: Int = 3) = stored(
        id, "Failed", episode = episode,
        extra = ""","errorMessage":"$message"${kind?.let { ""","failureKind":"$it"""" }.orEmpty()}""",
    )

    @Test
    fun a_download_waiting_on_a_silent_connection_says_it_is_reconnecting() {
        show(
            stored("a", "Downloading", extra = ""","downloadedBytes":10,"totalBytes":100""", updatedAt = 1),
        )

        compose.onNodeWithText(text(DownloadPhase.Reconnecting.message, "10 B / 100 B")).assertIsDisplayed()
    }

    @Test
    fun a_download_with_all_its_bytes_says_it_is_checking_the_file() {
        show(stored("a", "Downloading", extra = ""","downloadedBytes":100,"totalBytes":100""", updatedAt = 1))

        compose.onNodeWithText(text(DownloadPhase.CheckingFile.message)).assertIsDisplayed()
    }

    @Test
    fun a_download_stopped_by_the_storage_limit_says_so_and_is_marked_auto() {
        show(
            stored(
                "a", "Paused",
                extra = ""","downloadedBytes":10,"totalBytes":100,"autoDownloadRuleKey":"r","autoDownloadStop":"StorageLimit"""",
            ),
        )

        compose.onNodeWithText(
            text(Res.string.downloads_status_auto) + " • " + text(DownloadPhase.PausedStorageLimit.message, "10 B / 100 B"),
        ).assertIsDisplayed()
    }

    @Test
    fun a_finished_movie_says_what_it_was_checked_against() {
        show(
            stored(
                "m", "Completed", title = "Film", season = null, episode = null,
                extra = ""","localFileUri":"file:/m.mkv","downloadedBytes":100,"totalBytes":100,"verifiedBy":"Hash","framesCheck":"Passed"""",
            ),
        )

        compose.onNodeWithText(
            text(DownloadResult.VerifiedBySource.message) + " • " + text(Res.string.downloads_result_picture_ok),
        ).assertIsDisplayed()
    }

    @Test
    fun a_suspect_finished_movie_says_so() {
        show(
            stored(
                "m", "Completed", title = "Film", season = null, episode = null,
                extra = ""","localFileUri":"file:/m.mkv","downloadedBytes":100,"totalBytes":100,"verifiedBy":"Size","framesCheck":"Suspect"""",
            ),
        )

        compose.onNodeWithText(text(DownloadResult.Suspect.message)).assertIsDisplayed()
    }

    @Test
    fun a_download_from_before_checks_were_recorded_with_a_size_to_compare_says_not_checked() {
        show(
            stored(
                "m", "Completed", title = "Film", season = null, episode = null,
                extra = ""","localFileUri":"file:/m.mkv","downloadedBytes":100,"totalBytes":100,"streamSubtitle":"[size: 9.53 GB] Film.mkv"""",
            ),
        )

        compose.onNodeWithText(text(DownloadResult.NotChecked.message)).assertIsDisplayed()
    }

    @Test
    fun a_download_with_no_size_and_no_running_time_to_compare_says_why_it_could_not_be_checked() {
        show(stored("m", "Completed", title = "Film", season = null, episode = null, extra = ""","localFileUri":"file:/m.mkv","downloadedBytes":100,"totalBytes":100"""))

        compose.onNodeWithText(text(DownloadResult.CouldNotCompare.message)).assertIsDisplayed()
    }

    @Test
    fun a_file_that_failed_its_check_says_why_and_offers_another_source_before_the_same_one() {
        show(failedRow("a", "BadFile", "The downloaded video is blank"))

        compose.onNodeWithText(text(Res.string.downloads_status_failed_reason, "The downloaded video is blank")).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_failed_next_bad_file)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_choose_another_source)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_try_source_again)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.action_retry)).assertDoesNotExist()
    }

    @Test
    fun choosing_another_source_hands_over_the_failed_download() {
        show(failedRow("a", "BadFile", "The downloaded video is blank"))

        compose.onNodeWithText(text(Res.string.downloads_choose_another_source)).performClick()

        assertEquals("a", chooseSourceFor?.id)
        assertEquals(0, backend.started.count { it == "a" }, "choosing another source fetched the old one again")
    }

    @Test
    fun trying_the_same_source_again_is_a_separate_labelled_action() {
        show(failedRow("a", "BadFile", "The downloaded video is blank"))
        val startsBefore = backend.started.count { it == "a" }

        compose.onNodeWithText(text(Res.string.downloads_try_source_again)).performClick()

        compose.waitForIdle()
        assertEquals(startsBefore + 1, backend.started.count { it == "a" })
        assertNull(chooseSourceFor)
    }

    @Test
    fun without_a_way_to_open_the_sources_the_action_is_not_offered() {
        show(failedRow("a", "BadFile", "The downloaded video is blank"), withChooseSource = false)

        compose.onNodeWithText(text(Res.string.downloads_choose_another_source)).assertDoesNotExist()
        compose.onNodeWithText(text(Res.string.downloads_try_source_again)).assertIsDisplayed()
    }

    @Test
    fun a_lost_connection_offers_a_plain_retry_and_another_source() {
        show(failedRow("a", "Other", "Connection lost"))

        compose.onNodeWithText(text(Res.string.downloads_status_failed_reason, "Connection lost")).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_failed_next_other)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.action_retry)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_choose_another_source)).assertIsDisplayed()
    }

    @Test
    fun a_full_disk_says_not_enough_space_and_not_the_system_message() {
        show(failedRow("a", "NotEnoughSpace", "No space left on device"))

        compose.onNodeWithText(text(Res.string.downloads_status_failed_reason, text(Res.string.downloads_error_not_enough_space))).assertIsDisplayed()
        compose.onNodeWithText("No space left on device", substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(Res.string.downloads_failed_next_not_enough_space)).assertIsDisplayed()
    }

    @Test
    fun a_refusal_is_not_shown_as_an_http_code() {
        show(failedRow("a", "SourceRefused", "Download failed with HTTP 404"))

        compose.onNodeWithText("HTTP", substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(Res.string.downloads_failed_next_source_refused)).assertIsDisplayed()
    }

    @Test
    fun the_list_says_how_many_failed_and_offers_retry_all_and_clear() {
        show(failedRow("a", "Other", "Connection lost", 1), failedRow("b", "Other", "Connection lost", 2), failedRow("c", "BadFile", "The downloaded video is blank", 3))

        compose.onNodeWithText(text(Res.plurals.downloads_failed_count, 3)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_retry_all_failed)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_clear_failed)).assertIsDisplayed()
    }

    @Test
    fun retry_all_starts_each_failed_download_that_can_be_tried_again() {
        show(failedRow("a", "Other", "Connection lost", 1), failedRow("b", "Other", "Connection lost", 2), failedRow("c", "BadFile", "The downloaded video is blank", 3))

        compose.onNodeWithText(text(Res.string.downloads_retry_all_failed)).performClick()

        compose.waitForIdle()
        val items = DownloadsRepository.uiState.value.items.associateBy { it.id }
        assertEquals(DownloadStatus.Downloading, items.getValue("a").status)
        assertEquals(DownloadStatus.Downloading, items.getValue("b").status)
        assertEquals(DownloadStatus.Failed, items.getValue("c").status)
    }

    @Test
    fun retry_all_is_not_offered_when_every_failure_needs_another_source() {
        show(failedRow("c", "BadFile", "The downloaded video is blank", 3))

        compose.onNodeWithText(text(Res.string.downloads_retry_all_failed)).assertDoesNotExist()
        compose.onNodeWithText(text(Res.string.downloads_clear_failed)).assertIsDisplayed()
    }

    @Test
    fun clear_failed_empties_the_list_of_failures_and_leaves_the_rest() {
        show(failedRow("a", "Other", "Connection lost", 1), stored("r", "Paused", episode = 2, extra = ""","downloadedBytes":5"""))

        compose.onNodeWithText(text(Res.string.downloads_clear_failed)).performClick()

        compose.waitForIdle()
        assertEquals(listOf("r"), DownloadsRepository.uiState.value.items.map { it.id })
        compose.onNodeWithText(text(Res.string.downloads_clear_failed)).assertDoesNotExist()
    }

    private fun completedEpisode(id: String, episode: Int = 3) = stored(
        id, "Completed", episode = episode,
        extra = ""","localFileUri":"file:/$id.mkv","downloadedBytes":100,"totalBytes":100,"verifiedBy":"Size"""",
    )

    private fun deleteTitle() = text(Res.string.downloads_delete_title, "Pantheon S02E03")

    @Test
    fun deleting_a_finished_download_names_it_and_says_it_moves_to_the_trash() {
        show(completedEpisode("a"), withChooseSource = false)
        openShow()

        pressDelete()

        compose.onNodeWithText(deleteTitle()).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_delete_message_trash)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_delete_confirm_trash)).assertIsDisplayed()
        assertTrue(backend.trashed.isEmpty(), "nothing is deleted before the answer")
    }

    @Test
    fun confirming_moves_the_file_to_the_trash_and_never_deletes_it_outright() {
        show(completedEpisode("a"))
        openShow()
        pressDelete()

        compose.onNodeWithText(text(Res.string.downloads_delete_confirm_trash)).performClick()

        compose.waitForIdle()
        assertEquals(listOf("file:/a.mkv"), backend.trashed)
        assertTrue(backend.removedOutright.isEmpty(), "the file was deleted outright")
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
    }

    @Test
    fun cancelling_keeps_the_download_and_its_file() {
        show(completedEpisode("a"))
        openShow()
        pressDelete()

        compose.onNodeWithText(text(Res.string.action_cancel)).performClick()

        compose.waitForIdle()
        assertTrue(backend.trashed.isEmpty())
        assertEquals(1, DownloadsRepository.uiState.value.items.size)
        compose.onNodeWithText(deleteTitle()).assertDoesNotExist()
    }

    @Test
    fun without_a_system_trash_the_dialog_says_the_file_is_held_for_days() {
        backend.usesSystemTrash = false
        show(completedEpisode("a"))
        openShow()

        pressDelete()

        compose.onNodeWithText(text(Res.string.downloads_delete_message_held)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_delete_message_trash)).assertDoesNotExist()
    }

    @Test
    fun a_download_that_is_not_finished_says_it_removes_what_was_downloaded() {
        show(stored("p", "Paused", extra = ""","downloadedBytes":5"""))

        pressDelete()

        compose.onNodeWithText(deleteTitle()).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_delete_message_partial)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_delete_confirm_partial)).performClick()
        compose.waitForIdle()
        assertTrue(backend.trashed.isEmpty(), "a partial file is not a thing for the Trash")
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
    }

    @Test
    fun a_file_that_cannot_be_moved_stays_listed_and_the_user_is_told() {
        backend.trashSucceeds = false
        show(completedEpisode("a"))
        openShow()
        pressDelete()

        compose.onNodeWithText(text(Res.string.downloads_delete_confirm_trash)).performClick()

        compose.waitForIdle()
        assertEquals(1, DownloadsRepository.uiState.value.items.size)
        assertTrue(backend.removedOutright.isEmpty())
        assertEquals(text(Res.string.downloads_delete_not_moved_toast), NuvioToastController.currentToast.value?.message)
    }

    private val autoPaused = ""","downloadedBytes":5,"autoDownloadRuleKey":"series|tt1""""

    @Test
    fun deleting_an_automatic_download_says_it_will_not_come_back_and_is_remembered_before_it_goes() {
        show(stored("p", "Paused", extra = autoPaused))

        pressDelete()

        compose.onNodeWithText(text(Res.string.downloads_delete_message_partial) + " " + text(Res.string.downloads_delete_auto_note))
            .assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_delete_confirm_partial)).performClick()
        compose.waitForIdle()
        // Remembered while the download was still listed, then removed.
        assertEquals(listOf("p" to 1), rememberedDeletions)
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
    }

    @Test
    fun cancelling_the_delete_of_an_automatic_download_remembers_nothing() {
        show(stored("p", "Paused", extra = autoPaused))
        pressDelete()

        compose.onNodeWithText(text(Res.string.action_cancel)).performClick()

        compose.waitForIdle()
        assertTrue(rememberedDeletions.isEmpty())
        assertEquals(1, DownloadsRepository.uiState.value.items.size)
    }

    @Test
    fun deleting_a_download_the_user_started_has_no_auto_download_note() {
        show(stored("p", "Paused", extra = ""","downloadedBytes":5"""))

        pressDelete()

        compose.onNodeWithText(text(Res.string.downloads_delete_message_partial)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_delete_auto_note), substring = true).assertDoesNotExist()
    }

    private class SchedulingController : AutoDownloadController {
        override val rules = MutableStateFlow(emptyList<AutoDownloadRule>())
        override val statuses = MutableStateFlow(emptyMap<String, AutoDownloadStatus>())
        override val storageLimitGb = MutableStateFlow<Int?>(50)
        override val removals = MutableStateFlow(emptyList<AutoDownloadRemoval>())
        override var usesSystemTrash = true
        val kept = mutableListOf<String>()
        override val keptDownloadIds = MutableStateFlow<Set<String>>(emptySet())

        override fun save(profileId: Int, meta: MetaDetails, keepCount: Int, scope: AutoDownloadScope, deleteAfterWatching: Boolean) = Unit
        override fun turnOff(profileId: Int, ruleKey: String) = Unit
        override fun setStorageLimit(profileId: Int, limitGb: Int?) = Unit
        override fun keepDownload(profileId: Int, downloadId: String) {
            kept += downloadId
            keptDownloadIds.value += downloadId
            removals.value = removals.value.filterNot { it.downloadId == downloadId }
        }
        override fun undoKeepDownload(profileId: Int, downloadId: String) {
            keptDownloadIds.value -= downloadId
        }
        override fun deleteDownloadNow(profileId: Int, downloadId: String) = Unit
        override fun checkNow() = Unit
    }

    private fun showShowWith(controller: AutoDownloadController, vararg items: String, showId: String? = "tt1") {
        backend.payload = """{"items":[${items.joinToString(",")}]}"""
        compose.setContent {
            NuvioTheme {
                DownloadsScreenContent(
                    onBack = {},
                    onOpenDownload = {},
                    initialShowId = showId,
                    showAutoDownloads = false,
                    autoDownloads = controller,
                )
            }
        }
    }

    private fun keepDescription() = text(Res.string.downloads_auto_keep_description, "Pantheon", 2, 3)

    @Test
    fun an_episode_that_will_be_deleted_after_watching_says_when_and_has_a_keep_button() {
        val controller = SchedulingController()
        controller.removals.value = listOf(
            AutoDownloadRemoval("a", "series|tt1", dueAtEpochMs = DownloadsClock.nowEpochMs() + 20 * 3_600_000L + 60_000L),
        )
        showShowWith(controller, completedEpisode("a"), completedEpisode("b", episode = 4))

        compose.onNodeWithText(text(Res.plurals.downloads_row_deletes_in_hours, 20)).assertIsDisplayed()
        // Only the episode with a scheduled deletion has the countdown.
        compose.onAllNodesWithText(text(Res.plurals.downloads_row_deletes_in_hours, 20)).assertCountEquals(1)
        compose.onNodeWithContentDescription(keepDescription())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHasClickAction()
            .performClick()

        assertEquals(listOf("a"), controller.kept)
    }

    @Test
    fun the_last_half_hour_before_a_deletion_is_said_in_words() {
        val controller = SchedulingController()
        controller.removals.value = listOf(AutoDownloadRemoval("a", "series|tt1", dueAtEpochMs = DownloadsClock.nowEpochMs() + 10 * 60_000L))
        showShowWith(controller, completedEpisode("a"))

        compose.onNodeWithText(text(Res.string.downloads_row_deletes_soon)).assertIsDisplayed()
    }

    @Test
    fun an_episode_with_nothing_scheduled_can_be_kept_and_keep_can_be_undone() {
        showShowWith(SchedulingController(), completedEpisode("a"))

        compose.onNodeWithText(text(Res.string.downloads_row_deletes_soon)).assertDoesNotExist()
        compose.onNodeWithContentDescription(keepDescription()).performClick()
        compose.onNodeWithText(text(Res.string.downloads_kept)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_undo_keep)).performClick()
        compose.onNodeWithText(text(Res.string.downloads_kept)).assertDoesNotExist()
        compose.onNodeWithContentDescription(keepDescription()).assertHasClickAction()
    }

    @Test
    fun a_manual_movie_shows_its_countdown_and_lasting_keep_action() {
        val controller = SchedulingController()
        controller.removals.value = listOf(AutoDownloadRemoval("movie", null, DownloadsClock.nowEpochMs() + 10 * 60_000L))
        showShowWith(controller, stored("movie", "Completed", season = null, episode = null), showId = null)
        compose.onNodeWithText(text(Res.string.downloads_row_deletes_soon)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(Res.string.downloads_keep_description, "Pantheon")).performClick()
        compose.onNodeWithText(text(Res.string.downloads_kept)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_row_deletes_soon)).assertDoesNotExist()
        compose.onNodeWithText(text(Res.string.downloads_undo_keep)).performClick()
        compose.onNodeWithText(text(Res.string.downloads_kept)).assertDoesNotExist()
        assertEquals(listOf("movie"), controller.kept)
    }

    private fun pressDelete(name: String = "Pantheon S02E03") {
        compose.onNodeWithContentDescription(text(Res.string.downloads_delete_description, name)).performClick()
    }

    private fun openShow() {
        compose.onNodeWithText("Pantheon").performClick()
    }

    @Test
    fun a_list_that_could_not_be_read_says_so_instead_of_no_downloads_yet() {
        backend.payload = "this is not a list"
        showPayload()

        compose.onNodeWithText(text(Res.string.downloads_store_problem_title)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_store_problem_started_empty)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_empty_title)).assertDoesNotExist()
    }

    @Test
    fun the_banner_title_is_a_heading() {
        backend.payload = "this is not a list"
        showPayload()

        compose.onNode(
            hasText(text(Res.string.downloads_store_problem_title)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
        ).assertIsDisplayed()
    }

    @Test
    fun an_earlier_copy_is_said_to_be_an_earlier_copy_and_its_downloads_are_listed() {
        backend.payload = "{\"items\": 12}"
        backend.previousPayload = """{"items":[${stored("a", "Paused", extra = ""","downloadedBytes":5""")}]}"""
        showPayload()

        compose.onNodeWithText(text(Res.string.downloads_store_problem_earlier_copy)).assertIsDisplayed()
        compose.onNodeWithText(text(DownloadPhase.Paused.message, "5 B")).assertIsDisplayed()
    }

    @Test
    fun a_list_that_cannot_be_kept_says_that_changes_are_not_saved() {
        backend.payload = "garbage"
        backend.puttingAsideFails = true
        showPayload()

        compose.onNodeWithText(text(Res.string.downloads_store_problem_not_saving)).assertIsDisplayed()
    }

    @Test
    fun the_banner_offers_the_downloads_folder_where_the_files_still_are() {
        backend.payload = "garbage"
        showPayload()

        compose.onAllNodesWithText(text(Res.string.downloads_open_directory)).onLast().performClick()

        assertEquals(1, folderOpened)
    }

    @Test
    fun a_readable_list_has_no_banner() {
        show(stored("a", "Paused"))

        compose.onNodeWithText(text(Res.string.downloads_store_problem_title)).assertDoesNotExist()
    }

    // Accessibility

    private fun description(resource: StringResource, name: String) = text(resource, name)

    @Test
    fun every_row_action_names_the_download_it_acts_on() {
        show(
            stored("d", "Downloading", episode = 1, extra = ""","downloadedBytes":5,"totalBytes":100"""),
            stored("p", "Paused", episode = 2, extra = ""","downloadedBytes":5,"totalBytes":100"""),
        )

        compose.onNodeWithContentDescription(description(Res.string.downloads_pause_description, "Pantheon S02E01")).assertHasClickAction()
        compose.onNodeWithContentDescription(description(Res.string.downloads_resume_description, "Pantheon S02E02")).assertHasClickAction()
        compose.onNodeWithContentDescription(description(Res.string.downloads_delete_description, "Pantheon S02E01")).assertHasClickAction()
        compose.onNodeWithContentDescription(description(Res.string.downloads_delete_description, "Pantheon S02E02")).assertHasClickAction()
    }

    @Test
    fun the_play_and_delete_buttons_of_a_finished_download_name_it() {
        show(completedEpisode("a"))
        openShow()

        compose.onNodeWithContentDescription(description(Res.string.downloads_play_description, "Pantheon S02E03")).assertHasClickAction()
        compose.onNodeWithContentDescription(description(Res.string.downloads_delete_description, "Pantheon S02E03")).assertHasClickAction()
    }

    @Test
    fun no_row_action_is_named_by_the_bare_verb() {
        show(
            stored("d", "Downloading", episode = 1, extra = ""","downloadedBytes":5,"totalBytes":100"""),
            stored("p", "Paused", episode = 2, extra = ""","downloadedBytes":5,"totalBytes":100"""),
            failedRow("f", "Other", "Connection lost", 3),
        )

        listOf("Pause", "Resume", "Delete", "Play", "Retry").forEach { verb ->
            compose.onAllNodesWithContentDescription(verb).assertCountEquals(0)
        }
    }

    @Test
    fun a_row_that_can_be_played_is_one_button_that_reads_its_title() {
        show(completedEpisode("a"))
        openShow()

        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) and hasText("Pantheon", substring = true))
            .assertCountEquals(1)
            .onFirst()
            .assertHasClickAction()
    }

    @Test
    fun a_row_that_cannot_be_played_is_one_item_with_its_title_and_status_together_and_no_button_role() {
        show(stored("p", "Paused", extra = ""","downloadedBytes":5"""))

        compose.onNode(hasText("Pantheon", substring = true) and hasText(text(DownloadPhase.Paused.message, "5 B")))
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
    }

    @Test
    fun the_show_card_is_a_button() {
        show(completedEpisode("a"))

        compose.onNode(hasText("Pantheon") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHasClickAction()
    }

    @Test
    fun the_section_titles_are_headings() {
        show(
            stored("p", "Paused", episode = 1, extra = ""","downloadedBytes":5"""),
            stored("m", "Completed", title = "Film", season = null, episode = null, extra = ""","localFileUri":"file:/m.mkv","downloadedBytes":100,"totalBytes":100"""),
            completedEpisode("a", episode = 2),
        )

        listOf(Res.string.downloads_section_active, Res.string.downloads_section_movies, Res.string.downloads_section_shows).forEach { title ->
            compose.onNode(hasText(text(title)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertIsDisplayed()
        }
    }

    @Test
    fun the_season_titles_of_a_show_are_headings() {
        show(completedEpisode("a"))
        openShow()

        compose.onNode(hasText(text(Res.string.episodes_season, 2)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertIsDisplayed()
    }

    private fun statusNode(name: String, phase: StringResource) =
        compose.onNodeWithContentDescription(text(Res.string.downloads_announce_row, name, text(phase)), useUnmergedTree = true)

    @Test
    fun the_status_of_a_row_is_a_polite_live_region_that_names_the_download() {
        show(stored("d", "Downloading", extra = ""","downloadedBytes":5,"totalBytes":100"""))

        statusNode("Pantheon S02E03", Res.string.downloads_announce_downloading)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun bytes_arriving_change_what_is_shown_but_not_what_is_announced() {
        show(stored("d", "Downloading", extra = ""","downloadedBytes":5,"totalBytes":100"""))
        val announced = text(Res.string.downloads_announce_row, "Pantheon S02E03", text(Res.string.downloads_announce_downloading))
        compose.onNodeWithText(text(DownloadPhase.Downloading.message, "5 B / 100 B")).assertIsDisplayed()

        listOf(10L, 20L, 40L, 80L).forEach { bytes -> backend.progress.getValue("d")(bytes, 100L) }
        compose.waitForIdle()

        compose.onNodeWithText(text(DownloadPhase.Downloading.message, "80 B / 100 B")).assertIsDisplayed()
        compose.onNodeWithContentDescription(announced, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun a_change_of_phase_changes_what_is_announced() {
        show(stored("d", "Downloading", extra = ""","downloadedBytes":50,"totalBytes":100"""))
        statusNode("Pantheon S02E03", Res.string.downloads_announce_downloading).assertIsDisplayed()

        backend.progress.getValue("d")(100L, 100L)
        compose.waitForIdle()

        statusNode("Pantheon S02E03", Res.string.downloads_announce_checking).assertIsDisplayed()
        statusNode("Pantheon S02E03", Res.string.downloads_announce_downloading).assertDoesNotExist()
    }

    @Test
    fun a_failed_row_announces_its_reason() {
        show(failedRow("f", "NotEnoughSpace", "No space left on device"))

        compose.onNodeWithContentDescription(
            text(
                Res.string.downloads_announce_row, "Pantheon S02E03",
                text(Res.string.downloads_status_failed_reason, text(Res.string.downloads_error_not_enough_space)),
            ),
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }

    private fun withSpeedLimit(megabytesPerSecond: Int?, body: () -> Unit) {
        DownloadBandwidth.setUserCap(megabytesPerSecond?.let { it * 1_000_000L })
        try {
            body()
        } finally {
            DownloadBandwidth.setUserCap(null)
        }
    }

    private fun speedLine(megabytesPerSecond: Int) = text(
        Res.string.downloads_speed_limit_line,
        text(Res.string.settings_download_speed_limit_value, megabytesPerSecond),
    )

    @Test
    fun a_speed_limit_that_is_set_is_stated_at_the_top_with_a_link_to_the_setting() {
        withSpeedLimit(5) {
            val opened = mutableListOf<com.nuvio.app.features.settings.SettingsPage>()
            backend.payload = null
            compose.setContent {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.nuvio.app.features.settings.LocalOpenSettingsPage provides { page -> opened += page },
                ) {
                    NuvioTheme {
                        DownloadsScreen(onBack = {}, onOpenDownload = {}, showAutoDownloads = false)
                    }
                }
            }

            compose.onNodeWithText(speedLine(5)).assertIsDisplayed()
            compose.onNodeWithContentDescription(text(Res.string.downloads_speed_limit_change_description)).performClick()

            assertEquals(listOf(com.nuvio.app.features.settings.SettingsPage.Playback), opened)
        }
    }

    @Test
    fun without_a_speed_limit_the_screen_does_not_mention_one() {
        withSpeedLimit(null) {
            show()

            compose.onNodeWithText(speedLine(5)).assertDoesNotExist()
            compose.onNodeWithContentDescription(text(Res.string.downloads_speed_limit_change_description)).assertDoesNotExist()
        }
    }

    @Test
    fun the_speed_limit_is_stated_without_a_link_where_settings_cannot_be_opened() {
        withSpeedLimit(2) {
            show()

            compose.onNodeWithText(speedLine(2)).assertIsDisplayed()
            compose.onNodeWithContentDescription(text(Res.string.downloads_speed_limit_change_description)).assertDoesNotExist()
        }
    }

    private val runKey = "tt1|2"

    private fun runRecord(withProblems: Boolean = true) = BatchRunRecord(
        key = runKey,
        kind = BatchRunKind.Season,
        title = "Pantheon",
        seasonNumber = 2,
        added = 3,
        alreadyThere = 1,
        notFound = if (withProblems) 1 else 0,
        failed = if (withProblems) 1 else 0,
        problems = if (withProblems) {
            listOf(
                BatchRunProblem(BatchRunProblemKind.NotFound, "series", "tt1", "series", "tt1:2:4", "Pantheon", seasonNumber = 2, episodeNumber = 4, episodeTitle = "Four"),
                BatchRunProblem(BatchRunProblemKind.Failed, "series", "tt1", "series", "tt1:2:5", "Pantheon", seasonNumber = 2, episodeNumber = 5),
            )
        } else {
            emptyList()
        },
    )

    private fun withStoredRun(record: BatchRunRecord = runRecord(), body: () -> Unit) {
        val profile = com.nuvio.app.features.profiles.ProfileRepository.activeProfileId
        BatchRunSummaries.record(profile, record)
        try {
            body()
        } finally {
            BatchRunSummaries.dismiss(profile, record.key)
        }
    }

    @Test
    fun the_last_season_run_stays_on_the_screen_with_its_counts_and_the_episodes_that_need_attention() {
        withStoredRun {
            show()

            compose.onNodeWithText(text(Res.string.downloads_runs_heading)).assertIsDisplayed()
            compose.onNodeWithText(text(Res.string.downloads_run_title_season, "Pantheon", text(Res.string.episodes_season, 2))).assertIsDisplayed()
            compose.onNodeWithText(
                listOf(
                    text(Res.string.downloads_run_added, 3),
                    text(Res.string.downloads_run_existing, 1),
                    text(Res.string.downloads_run_not_found, 1),
                    text(Res.string.downloads_run_failed, 1),
                ).joinToString(", "),
            ).assertIsDisplayed()
            compose.onNodeWithText(
                text(Res.string.downloads_run_problem_row, "S2 E4 \u2022 Four", text(Res.string.downloads_run_problem_not_found)),
            ).assertIsDisplayed()
            compose.onNodeWithText(
                text(Res.string.downloads_run_problem_row, "S2 E5", text(Res.string.downloads_run_problem_failed)),
            ).assertIsDisplayed()
        }
    }

    @Test
    fun an_episode_that_needs_attention_offers_to_choose_another_source() {
        withStoredRun {
            show()

            compose.onNodeWithContentDescription(text(Res.string.downloads_choose_another_source_description, "S2 E4 \u2022 Four")).performClick()

            assertEquals("tt1:2:4", chooseSourceFor?.videoId)
            assertEquals("tt1", chooseSourceFor?.parentMetaId)
            assertEquals(2, chooseSourceFor?.seasonNumber)
            assertEquals(4, chooseSourceFor?.episodeNumber)
        }
    }

    @Test
    fun without_a_way_to_open_the_sources_a_run_shows_what_failed_but_no_button() {
        withStoredRun {
            show(withChooseSource = false)

            compose.onNodeWithText(text(Res.string.downloads_run_problem_row, "S2 E5", text(Res.string.downloads_run_problem_failed))).assertIsDisplayed()
            compose.onNodeWithText(text(Res.string.downloads_choose_another_source)).assertDoesNotExist()
        }
    }

    @Test
    fun a_run_is_dismissed_by_name_and_stays_dismissed() {
        withStoredRun {
            show()

            compose.onNodeWithContentDescription(
                text(Res.string.downloads_run_dismiss_description, text(Res.string.downloads_run_title_season, "Pantheon", text(Res.string.episodes_season, 2))),
            ).performClick()
            compose.waitForIdle()

            compose.onNodeWithText(text(Res.string.downloads_runs_heading)).assertDoesNotExist()
            assertTrue(BatchRunSummaries.load(com.nuvio.app.features.profiles.ProfileRepository.activeProfileId).none { it.key == runKey })
        }
    }

    @Test
    fun a_run_without_problems_shows_only_its_counts() {
        withStoredRun(runRecord(withProblems = false)) {
            show()

            compose.onNodeWithText(
                listOf(text(Res.string.downloads_run_added, 3), text(Res.string.downloads_run_existing, 1)).joinToString(", "),
            ).assertIsDisplayed()
            compose.onNodeWithText(text(Res.string.downloads_choose_another_source)).assertDoesNotExist()
        }
    }

    @Test
    fun the_empty_state_says_how_to_get_a_download() {
        show()

        compose.onNodeWithText(text(Res.string.downloads_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_empty_hint)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_empty_hint_seasons)).assertDoesNotExist()
    }

    @Test
    fun the_empty_state_mentions_seasons_where_they_can_be_downloaded() {
        backend.payload = null
        compose.setContent {
            NuvioTheme {
                DownloadsScreen(onBack = {}, onOpenDownload = {}, showAutoDownloads = false, showSeasonHint = true)
            }
        }

        compose.onNodeWithText(text(Res.string.downloads_empty_hint_seasons)).assertIsDisplayed()
    }

    @Test
    fun the_empty_state_offers_browsing_only_where_the_screen_can_do_it() {
        show()
        compose.onNodeWithText(text(Res.string.downloads_empty_browse)).assertDoesNotExist()
    }

    @Test
    fun browsing_from_the_empty_state_calls_the_hook() {
        var browsed = 0
        compose.setContent {
            NuvioTheme {
                DownloadsScreen(onBack = {}, onOpenDownload = {}, showAutoDownloads = false, onBrowse = { browsed++ })
            }
        }

        compose.onNodeWithText(text(Res.string.downloads_empty_browse)).performClick()

        assertEquals(1, browsed)
    }

    @Test
    fun the_empty_state_is_gone_once_there_is_a_download() {
        show(stored("p", "Paused"))

        compose.onNodeWithText(text(Res.string.downloads_empty_title)).assertDoesNotExist()
        compose.onNodeWithText(text(Res.string.downloads_empty_hint)).assertDoesNotExist()
    }

    @Test
    fun an_episode_download_offers_links_to_its_episode_season_and_show() {
        episodeLinks = recordingLinks()
        show(stored("a", "Downloading", extra = ""","downloadedBytes":10,"totalBytes":100""", updatedAt = 1))

        compose.onNodeWithText(text(Res.string.nav_episode_details)).assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText(text(Res.string.nav_go_to_season)).assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithContentDescription(text(Res.string.downloads_episode_details_description, "Pantheon S02E03")).performClick()
        compose.onNodeWithContentDescription(text(Res.string.downloads_go_to_season_description, "Pantheon S02E03")).performClick()
        compose.onNodeWithContentDescription(text(Res.string.downloads_go_to_show_description, "Pantheon S02E03")).performClick()

        assertEquals(listOf("details:a", "season:a", "show:a"), linkCalls)
    }

    @Test
    fun an_episode_download_has_no_links_where_none_are_given() {
        show(stored("a", "Downloading", extra = ""","downloadedBytes":10,"totalBytes":100""", updatedAt = 1))

        compose.onNodeWithText(text(Res.string.nav_episode_details)).assertDoesNotExist()
        compose.onNodeWithText(text(Res.string.nav_go_to_season)).assertDoesNotExist()
    }

    @Test
    fun a_movie_download_has_no_episode_links() {
        episodeLinks = recordingLinks()
        show(stored("m", "Downloading", title = "Film", season = null, episode = null, extra = ""","downloadedBytes":10,"totalBytes":100""", updatedAt = 1))

        compose.onNodeWithText(text(Res.string.nav_episode_details)).assertDoesNotExist()
    }
}
