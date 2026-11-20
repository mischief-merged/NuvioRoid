package com.nuvio.app.features.downloads

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.nuvio.app.core.ui.NuvioTheme
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_auto_storage_limit
import nuvio.composeapp.generated.resources.downloads_auto_storage_limit_none
import nuvio.composeapp.generated.resources.downloads_auto_storage_limit_profile_description
import nuvio.composeapp.generated.resources.downloads_auto_storage_limit_value
import nuvio.composeapp.generated.resources.downloads_storage_auto
import nuvio.composeapp.generated.resources.downloads_storage_auto_no_limit
import nuvio.composeapp.generated.resources.downloads_storage_change_limit
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/** The storage limit control on the storage summary, off the top of the auto-download section. */
@OptIn(ExperimentalTestApi::class)
class DownloadStorageLimitControlTest {
    @get:Rule
    val compose = createComposeRule()

    private val profile = 9_900_007
    private val otherProfile = 9_900_008
    private var shownProfile by mutableIntStateOf(profile)
    private val saved = mutableListOf<Pair<Int, Int?>>()

    private fun text(resource: StringResource, vararg arguments: Any) = runBlocking { getString(resource, *arguments) }

    private fun state(autoUsed: Long = 19_000_000_000L) = DownloadFolderState(
        locations = listOf(
            DownloadLocationInfo(
                id = "app",
                path = "/downloads",
                name = "downloads",
                isDefault = true,
                isAppFolder = true,
                availability = FolderAvailability.Available,
                itemCount = 1,
                totalBytes = autoUsed,
                freeBytes = 100_000_000_000L,
                usedBytes = autoUsed,
            ),
        ),
        usageMeasured = true,
        totalFreeBytes = 100_000_000_000L,
        autoDownloadUsedBytes = autoUsed,
    )

    private fun show(limitGb: Int?) {
        saved.clear()
        shownProfile = profile
        compose.setContent {
            NuvioTheme {
                DownloadStorageSummary(
                    state = state(),
                    autoLimitGb = limitGb,
                    onManageLocations = null,
                    onChangeStorageLimit = { id, limit -> saved += id to limit },
                    profileId = shownProfile,
                )
            }
        }
    }

    @Test
    fun the_summary_shows_the_same_gigabytes_as_the_limit_and_keeps_the_explanation() {
        show(250)

        compose.onNodeWithText(
            text(Res.string.downloads_storage_auto, formatBytes(19_000_000_000L), formatBytes(250L * BytesPerGigabyte)),
        ).assertIsDisplayed()
        assertEquals("250.0 GB", formatBytes(250L * BytesPerGigabyte))
        compose.onNodeWithText(text(Res.string.downloads_auto_storage_limit_profile_description)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_storage_change_limit)).assertIsDisplayed()
    }

    @Test
    fun the_change_control_is_named_by_the_limit_and_shows_the_current_value_as_its_state() {
        show(50)

        compose.onNodeWithContentDescription(text(Res.string.downloads_auto_storage_limit))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(Res.string.downloads_auto_storage_limit_value, 50)))
            .assertHasClickAction()
    }

    @Test
    fun no_limit_is_shown_in_words() {
        show(null)

        compose.onNodeWithText(text(Res.string.downloads_storage_auto_no_limit)).assertIsDisplayed()
    }

    @Test
    fun the_picker_offers_every_limit_and_saves_the_chosen_one_for_the_profile_it_was_opened_for() {
        show(50)

        compose.onNodeWithText(text(Res.string.downloads_storage_change_limit)).performClick()
        compose.onNode(hasText(text(Res.string.downloads_auto_storage_limit_value, 50)) and hasAnyAncestor(isDialog())).assertIsSelected()
        listOf(10, 25, 100, 250).forEach {
            compose.onNodeWithText(text(Res.string.downloads_auto_storage_limit_value, it)).assertExists()
        }
        compose.onNodeWithText(text(Res.string.downloads_auto_storage_limit_none)).assertExists()
        compose.runOnIdle { shownProfile = otherProfile }
        compose.waitForIdle()
        compose.onNodeWithText(text(Res.string.downloads_auto_storage_limit_value, 100)).performClick()

        assertEquals(listOf<Pair<Int, Int?>>(profile to 100), saved)
    }

    @Test
    fun choosing_no_limit_saves_no_limit() {
        show(50)

        compose.onNodeWithContentDescription(text(Res.string.downloads_auto_storage_limit)).performClick()
        compose.onNodeWithText(text(Res.string.downloads_auto_storage_limit_none)).performClick()

        assertEquals(listOf<Pair<Int, Int?>>(profile to null), saved)
    }

    @Test
    fun the_limit_is_chosen_with_the_keyboard_from_named_radio_buttons_of_one_group() {
        show(50)
        compose.onNodeWithContentDescription(text(Res.string.downloads_auto_storage_limit))
            .requestFocus()
            .performKeyInput { pressKey(Key.Enter) }
        val radioButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

        (listOf(text(Res.string.downloads_auto_storage_limit_none)) + listOf(10, 25, 50, 100, 250).map { text(Res.string.downloads_auto_storage_limit_value, it) })
            .forEach { name ->
                compose.onNode(hasContentDescription(name) and radioButton)
                    .assert(hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)))
            }
        compose.onNode(hasContentDescription(text(Res.string.downloads_auto_storage_limit_value, 250)) and radioButton)
            .requestFocus()
            .performKeyInput { pressKey(Key.Enter) }

        assertEquals(listOf<Pair<Int, Int?>>(profile to 250), saved)
    }
}
