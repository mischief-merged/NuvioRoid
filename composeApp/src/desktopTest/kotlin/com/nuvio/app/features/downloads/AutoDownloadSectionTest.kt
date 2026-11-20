package com.nuvio.app.features.downloads

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.MetaDetails
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.downloads_auto_check_now
import nuvio.composeapp.generated.resources.downloads_auto_delete_now
import nuvio.composeapp.generated.resources.downloads_auto_delete_now_description
import nuvio.composeapp.generated.resources.downloads_auto_delete_now_message_held
import nuvio.composeapp.generated.resources.downloads_auto_delete_now_message_trash
import nuvio.composeapp.generated.resources.downloads_auto_delete_now_title
import nuvio.composeapp.generated.resources.downloads_auto_keep_description
import nuvio.composeapp.generated.resources.downloads_auto_pending_heading
import nuvio.composeapp.generated.resources.downloads_auto_dialog_title
import nuvio.composeapp.generated.resources.downloads_auto_go_to_show
import nuvio.composeapp.generated.resources.downloads_auto_no_rules
import nuvio.composeapp.generated.resources.downloads_auto_storage_limit_profile_description
import nuvio.composeapp.generated.resources.downloads_auto_turn_off
import nuvio.composeapp.generated.resources.downloads_auto_recent_days_ago
import nuvio.composeapp.generated.resources.downloads_auto_recent_heading
import nuvio.composeapp.generated.resources.downloads_auto_recent_row
import nuvio.composeapp.generated.resources.downloads_auto_recent_today
import nuvio.composeapp.generated.resources.downloads_auto_pending_in_hours
import nuvio.composeapp.generated.resources.downloads_auto_pending_row
import nuvio.composeapp.generated.resources.downloads_auto_pending_soon
import nuvio.composeapp.generated.resources.downloads_auto_checking_toast
import nuvio.composeapp.generated.resources.downloads_auto_keep_option
import nuvio.composeapp.generated.resources.downloads_auto_rule_summary
import nuvio.composeapp.generated.resources.downloads_auto_section_title
import nuvio.composeapp.generated.resources.downloads_auto_status_checking
import nuvio.composeapp.generated.resources.downloads_auto_status_could_not_check
import nuvio.composeapp.generated.resources.downloads_auto_status_downloading
import nuvio.composeapp.generated.resources.downloads_auto_status_failed
import nuvio.composeapp.generated.resources.downloads_auto_status_no_source
import nuvio.composeapp.generated.resources.downloads_auto_status_not_checked
import nuvio.composeapp.generated.resources.downloads_auto_status_paused
import nuvio.composeapp.generated.resources.downloads_auto_status_private_sources
import nuvio.composeapp.generated.resources.downloads_auto_status_pending
import nuvio.composeapp.generated.resources.downloads_auto_status_stopped
import nuvio.composeapp.generated.resources.downloads_auto_status_low_disk_space
import nuvio.composeapp.generated.resources.downloads_auto_status_folder_not_connected
import nuvio.composeapp.generated.resources.downloads_auto_status_storage_limit
import nuvio.composeapp.generated.resources.downloads_auto_status_up_to_date
import nuvio.composeapp.generated.resources.downloads_auto_storage_limit
import nuvio.composeapp.generated.resources.downloads_auto_storage_limit_none
import nuvio.composeapp.generated.resources.downloads_auto_storage_limit_value
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AutoDownloadSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private class FakeController : AutoDownloadController {
        override val rules = MutableStateFlow(emptyList<AutoDownloadRule>())
        override val statuses = MutableStateFlow(emptyMap<String, AutoDownloadStatus>())
        override val storageLimitGb = MutableStateFlow<Int?>(50)
        override val removals = MutableStateFlow(emptyList<AutoDownloadRemoval>())
        override val recentlyDeleted = MutableStateFlow(emptyList<AutoDownloadDeleted>())
        override var usesSystemTrash = true
        var checks = 0
        val limitsSet = mutableListOf<Pair<Int, Int?>>()

        override fun setStorageLimit(profileId: Int, limitGb: Int?) {
            limitsSet += profileId to limitGb
        }

        val kept = mutableListOf<Pair<Int, String>>()
        val deletedNow = mutableListOf<Pair<Int, String>>()

        override fun save(profileId: Int, meta: MetaDetails, keepCount: Int, scope: AutoDownloadScope, deleteAfterWatching: Boolean) = Unit

        override fun keepDownload(profileId: Int, downloadId: String) {
            kept += profileId to downloadId
        }

        override fun deleteDownloadNow(profileId: Int, downloadId: String) {
            deletedNow += profileId to downloadId
        }

        val turnedOff = mutableListOf<Pair<Int, String>>()

        override fun turnOff(profileId: Int, ruleKey: String) {
            turnedOff += profileId to ruleKey
        }

        override fun checkNow() {
            checks++
        }
    }

    private val controller = FakeController()
    private val profile = 9_900_007
    private val otherProfile = 9_900_008
    private var shownProfile by mutableIntStateOf(profile)
    private val rule = testRule(keepCount = 5)

    private val statusTexts = mapOf(
        AutoDownloadStatus.NotChecked to Res.string.downloads_auto_status_not_checked,
        AutoDownloadStatus.Checking to Res.string.downloads_auto_status_checking,
        AutoDownloadStatus.UpToDate to Res.string.downloads_auto_status_up_to_date,
        AutoDownloadStatus.Pending to Res.string.downloads_auto_status_pending,
        AutoDownloadStatus.Downloading to Res.string.downloads_auto_status_downloading,
        AutoDownloadStatus.Paused to Res.string.downloads_auto_status_paused,
        AutoDownloadStatus.CouldNotCheck to Res.string.downloads_auto_status_could_not_check,
        AutoDownloadStatus.NoSource to Res.string.downloads_auto_status_no_source,
        AutoDownloadStatus.PrivateSourcesSkipped to Res.string.downloads_auto_status_private_sources,
        AutoDownloadStatus.Failed to Res.string.downloads_auto_status_failed,
        AutoDownloadStatus.Stopped to Res.string.downloads_auto_status_stopped,
        AutoDownloadStatus.StorageLimitReached to Res.string.downloads_auto_status_storage_limit,
        AutoDownloadStatus.LowDiskSpace to Res.string.downloads_auto_status_low_disk_space,
        AutoDownloadStatus.FolderNotConnected to Res.string.downloads_auto_status_folder_not_connected,
    )

    @Test
    fun nothing_is_shown_without_a_rule_or_automatic_downloads() {
        downloads.value = DownloadsUiState()
        show()

        compose.onNodeWithText(text(Res.string.downloads_auto_section_title)).assertDoesNotExist()
    }

    @Test
    fun each_rule_is_listed_with_its_show_and_count_under_a_heading() {
        controller.rules.value = listOf(rule)
        show()

        compose.onNode(
            hasText(text(Res.string.downloads_auto_section_title)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
        ).assertIsDisplayed()
        val count = runBlocking { getPluralString(Res.plurals.downloads_auto_keep_option, 5, 5) }
        compose.onNodeWithText(text(Res.string.downloads_auto_rule_summary, "Show", count)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_auto_status_not_checked)).assertIsDisplayed()
    }

    @Test
    fun every_status_is_shown_as_its_own_text() {
        controller.rules.value = listOf(rule)
        show()
        assertEquals(AutoDownloadStatus.entries.toSet(), statusTexts.keys)
        assertEquals(statusTexts.size, statusTexts.values.map { text(it) }.toSet().size, "two statuses share a text")

        statusTexts.forEach { (status, resource) ->
            compose.runOnIdle { controller.statuses.value = mapOf(rule.key to status) }

            compose.onNodeWithText(text(resource)).assertIsDisplayed()
        }
    }

    @Test
    fun check_now_starts_a_check_and_says_so() {
        controller.rules.value = listOf(rule)
        show()

        compose.onNodeWithContentDescription(text(Res.string.downloads_auto_check_now))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .requestFocus()
            .performKeyInput { pressKey(Key.Enter) }

        assertEquals(1, controller.checks)
        assertEquals(text(Res.string.downloads_auto_checking_toast), NuvioToastController.currentToast.value?.message)
    }

    @Test
    fun the_storage_limit_is_not_at_the_top_of_the_section() {
        controller.rules.value = listOf(rule)
        controller.storageLimitGb.value = 50
        show()

        compose.onNodeWithText(text(Res.string.downloads_auto_storage_limit)).assertDoesNotExist()
        compose.onNodeWithText(text(Res.string.downloads_auto_storage_limit_profile_description)).assertDoesNotExist()
        compose.onNodeWithText(text(Res.string.downloads_auto_section_title)).assertIsDisplayed()
    }

    private val hour = 3_600_000L
    private var shownNow = 1_000L
    private val downloads = MutableStateFlow(
        DownloadsUiState(listOf(testDownload(4, DownloadStatus.Completed, ruleKey = rule.key, id = "watched"))),
    )

    private fun scheduleRemoval(inMs: Long) {
        controller.rules.value = listOf(rule)
        controller.removals.value = listOf(AutoDownloadRemoval("watched", rule.key, dueAtEpochMs = 1_000 + inMs))
    }

    private fun pendingRow(due: String) = text(Res.string.downloads_auto_pending_row, "Show", 1, 4, due)

    @Test
    fun a_watched_episode_that_will_be_removed_is_listed_with_the_hours_left() {
        scheduleRemoval(inMs = 23 * hour + 5)
        show()

        compose.onNode(
            hasText(text(Res.string.downloads_auto_pending_heading)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
        ).assertIsDisplayed()
        val due = runBlocking { getPluralString(Res.plurals.downloads_auto_pending_in_hours, 23, 23) }
        compose.onNodeWithText(pendingRow(due)).assertIsDisplayed()
    }

    @Test
    fun the_hours_left_follow_the_clock_while_the_screen_stays_open() {
        scheduleRemoval(inMs = 3 * hour)
        show()
        compose.onNodeWithText(pendingRow(runBlocking { getPluralString(Res.plurals.downloads_auto_pending_in_hours, 3, 3) })).assertIsDisplayed()

        shownNow = 1_000 + 2 * hour
        compose.mainClock.advanceTimeBy(60_000)

        compose.onNodeWithText(pendingRow(runBlocking { getPluralString(Res.plurals.downloads_auto_pending_in_hours, 1, 1) })).assertIsDisplayed()
    }

    @Test
    fun the_last_half_hour_is_shown_in_words() {
        scheduleRemoval(inMs = hour / 2 - 1)
        show()

        compose.onNodeWithText(pendingRow(text(Res.string.downloads_auto_pending_soon))).assertIsDisplayed()
    }

    @Test
    fun a_removal_whose_download_is_gone_is_not_listed() {
        scheduleRemoval(inMs = hour)
        downloads.value = DownloadsUiState()
        show()

        compose.onNodeWithText(text(Res.string.downloads_auto_pending_heading)).assertDoesNotExist()
    }

    @Test
    fun keep_and_delete_now_are_buttons_named_with_their_episode_and_work_from_the_keyboard() {
        scheduleRemoval(inMs = 5 * hour)
        show()
        val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

        compose.onNodeWithContentDescription(text(Res.string.downloads_auto_delete_now_description, "Show", 1, 4))
            .assert(button)
            .assertHasClickAction()
        compose.onNodeWithContentDescription(text(Res.string.downloads_auto_keep_description, "Show", 1, 4))
            .assert(button)
            .requestFocus()
            .performKeyInput { pressKey(Key.Enter) }

        assertEquals(listOf(profile to "watched"), controller.kept)
    }

    @Test
    fun keep_keeps_the_episode_without_asking() {
        scheduleRemoval(inMs = 5 * hour)
        show()

        compose.onNodeWithContentDescription(text(Res.string.downloads_auto_keep_description, "Show", 1, 4)).performClick()

        assertEquals(listOf(profile to "watched"), controller.kept)
        assertEquals(emptyList(), controller.deletedNow)
    }

    @Test
    fun delete_now_asks_first_and_deletes_nothing_when_it_is_cancelled() {
        scheduleRemoval(inMs = 5 * hour)
        show()

        compose.onNodeWithContentDescription(text(Res.string.downloads_auto_delete_now_description, "Show", 1, 4)).performClick()

        assertEquals(emptyList(), controller.deletedNow)
        compose.onNodeWithText(text(Res.string.downloads_auto_delete_now_title)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_auto_delete_now_message_trash, "Show", 1, 4)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.action_cancel)).performClick()

        assertEquals(emptyList(), controller.deletedNow)
        compose.onNodeWithText(text(Res.string.downloads_auto_delete_now_title)).assertDoesNotExist()
    }

    @Test
    fun confirming_delete_now_deletes_the_episode_for_the_profile_it_was_pressed_for() {
        scheduleRemoval(inMs = 5 * hour)
        show()
        compose.onNodeWithContentDescription(text(Res.string.downloads_auto_delete_now_description, "Show", 1, 4)).performClick()

        compose.runOnIdle { shownProfile = otherProfile }
        compose.waitForIdle()
        compose.onNode(hasText(text(Res.string.downloads_auto_delete_now)) and hasAnyAncestor(isDialog())).performClick()

        assertEquals(listOf(profile to "watched"), controller.deletedNow)
    }

    @Test
    fun the_question_says_the_file_will_be_deleted_for_good_when_there_is_no_trash() {
        controller.usesSystemTrash = false
        scheduleRemoval(inMs = 5 * hour)
        show()

        compose.onNodeWithContentDescription(text(Res.string.downloads_auto_delete_now_description, "Show", 1, 4)).performClick()

        compose.onNodeWithText(text(Res.string.downloads_auto_delete_now_message_held, "Show", 1, 4)).assertIsDisplayed()
    }

    @Test
    fun a_rule_row_is_a_button_with_a_named_action_that_works_from_the_keyboard() {
        controller.rules.value = listOf(rule)
        show()

        compose.onNode(
            hasText(text(Res.string.downloads_auto_status_not_checked)) and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button),
        )
            .assertHasClickAction()
            .assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick))
            .requestFocus()
            .performKeyInput { pressKey(Key.Enter) }

        compose.onNodeWithText(text(Res.string.downloads_auto_dialog_title, "Show")).assertIsDisplayed()
    }

    @Test
    fun the_rule_dialog_turns_the_rule_off_for_the_profile_it_was_opened_for() {
        controller.rules.value = listOf(rule)
        show()
        compose.onNodeWithText(text(Res.string.downloads_auto_status_not_checked)).performClick()

        compose.runOnIdle { shownProfile = otherProfile }
        compose.waitForIdle()
        compose.onNode(hasText(text(Res.string.downloads_auto_turn_off)) and hasAnyAncestor(isDialog())).performClick()

        assertEquals(listOf(profile to rule.key), controller.turnedOff)
        compose.onNodeWithText(text(Res.string.downloads_auto_dialog_title, "Show")).assertDoesNotExist()
    }

    @Test
    fun the_rule_dialog_goes_to_the_show_and_closes() {
        controller.rules.value = listOf(rule)
        show()
        compose.onNodeWithText(text(Res.string.downloads_auto_status_not_checked)).performClick()

        compose.onNodeWithText(text(Res.string.downloads_auto_go_to_show)).performClick()

        assertEquals(listOf("tt1"), wentToShow)
        compose.onNodeWithText(text(Res.string.downloads_auto_dialog_title, "Show")).assertDoesNotExist()
    }

    @Test
    fun go_to_show_is_not_offered_where_the_screen_cannot_open_a_show() {
        controller.rules.value = listOf(rule)
        show(withGoToShow = false)
        compose.onNodeWithText(text(Res.string.downloads_auto_status_not_checked)).performClick()

        compose.onNodeWithText(text(Res.string.downloads_auto_turn_off)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_auto_go_to_show)).assertDoesNotExist()
    }

    @Test
    fun the_dialog_closes_when_its_rule_is_gone() {
        controller.rules.value = listOf(rule)
        show()
        compose.onNodeWithText(text(Res.string.downloads_auto_status_not_checked)).performClick()

        compose.runOnIdle { controller.rules.value = emptyList() }
        compose.waitForIdle()

        compose.onNodeWithText(text(Res.string.downloads_auto_dialog_title, "Show")).assertDoesNotExist()
    }

    @Test
    fun check_now_stays_when_automatic_downloads_exist_but_no_rule_does() {
        downloads.value = DownloadsUiState(listOf(testDownload(4, DownloadStatus.Completed, ruleKey = rule.key)))
        show()

        compose.onNodeWithText(text(Res.string.downloads_auto_storage_limit)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(Res.string.downloads_auto_check_now)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_auto_no_rules)).assertIsDisplayed()
    }

    @Test
    fun a_changed_storage_limit_alone_does_not_keep_the_section_on_screen() {
        downloads.value = DownloadsUiState(emptyList())
        controller.storageLimitGb.value = 100
        show()

        compose.onNodeWithText(text(Res.string.downloads_auto_section_title)).assertDoesNotExist()
    }

    @Test
    fun the_section_is_shown_for_rules_automatic_downloads_or_history_only() {
        assertEquals(false, autoDownloadSectionShown(false, false, false))
        assertEquals(true, autoDownloadSectionShown(true, false, false))
        assertEquals(true, autoDownloadSectionShown(false, true, false))
        assertEquals(true, autoDownloadSectionShown(false, false, true))
    }

    private fun recent(episode: Int, agoMs: Long) = AutoDownloadDeleted("Show", 1, episode, atEpochMs = shownNow - agoMs)

    @Test
    fun an_episode_deleted_after_watching_is_listed_under_recently_deleted_for_a_week() {
        controller.rules.value = listOf(rule)
        controller.recentlyDeleted.value = listOf(
            recent(1, agoMs = 8 * 24 * hour),
            recent(2, agoMs = 2 * 24 * hour),
            recent(3, agoMs = hour),
        )
        show()

        compose.onNode(
            hasText(text(Res.string.downloads_auto_recent_heading)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
        ).assertIsDisplayed()
        val twoDays = runBlocking { getPluralString(Res.plurals.downloads_auto_recent_days_ago, 2, 2) }
        compose.onNodeWithText(text(Res.string.downloads_auto_recent_row, "Show", 1, 3, text(Res.string.downloads_auto_recent_today))).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_auto_recent_row, "Show", 1, 2, twoDays)).assertIsDisplayed()
        compose.onNodeWithText("Show S1 E1", substring = true).assertDoesNotExist()
    }

    @Test
    fun the_list_is_still_there_after_the_last_rule_was_turned_off() {
        controller.recentlyDeleted.value = listOf(recent(3, agoMs = hour))
        show()

        compose.onNodeWithText(text(Res.string.downloads_auto_recent_heading)).assertIsDisplayed()
    }

    @Test
    fun without_removed_episodes_the_heading_is_not_shown() {
        controller.rules.value = listOf(rule)
        controller.recentlyDeleted.value = listOf(recent(1, agoMs = 9 * 24 * hour))
        show()

        compose.onNodeWithText(text(Res.string.downloads_auto_recent_heading)).assertDoesNotExist()
    }

    private val wentToShow = mutableListOf<String>()

    private fun show(withGoToShow: Boolean = true) {
        compose.setContent {
            NuvioTheme {
                AutoDownloadSection(
                    controller = controller,
                    profileId = shownProfile,
                    downloads = downloads,
                    nowEpochMs = { shownNow },
                    onGoToShow = if (withGoToShow) ({ wentToShow += it.parentMetaId }) else null,
                )
            }
        }
    }

    private fun text(resource: StringResource, vararg arguments: Any): String =
        runBlocking { getString(resource, *arguments) }
}
