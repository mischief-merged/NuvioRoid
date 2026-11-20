package com.nuvio.app.features.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.downloads.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class DeleteAfterWatchingSettingTest {
    @get:Rule
    val compose = createComposeRule()

    private class Controller : AutoDownloadController {
        override val rules = MutableStateFlow(emptyList<AutoDownloadRule>())
        override val statuses = MutableStateFlow(emptyMap<String, AutoDownloadStatus>())
        override val storageLimitGb = MutableStateFlow<Int?>(50)
        override val removals = MutableStateFlow(emptyList<AutoDownloadRemoval>())
        override val deleteAfterWatchingMode = MutableStateFlow(DeleteAfterWatchingMode.AutomaticOnly)
        override val usesSystemTrash = true
        val changes = mutableListOf<Pair<Int, DeleteAfterWatchingMode>>()
        override fun setDeleteAfterWatchingMode(profileId: Int, mode: DeleteAfterWatchingMode) {
            changes += profileId to mode
            deleteAfterWatchingMode.value = mode
        }
        override fun save(profileId: Int, meta: MetaDetails, keepCount: Int, scope: AutoDownloadScope, deleteAfterWatching: Boolean) = Unit
        override fun turnOff(profileId: Int, ruleKey: String) = Unit
        override fun setStorageLimit(profileId: Int, limitGb: Int?) = Unit
        override fun keepDownload(profileId: Int, downloadId: String) = Unit
        override fun deleteDownloadNow(profileId: Int, downloadId: String) = Unit
        override fun checkNow() = Unit
    }

    @Test
    fun all_three_choices_save_for_the_profile_and_show_the_selected_value() {
        val controller = Controller()
        compose.setContent {
            NuvioTheme { DeleteAfterWatchingSetting(isTablet = false, controller = controller, profileId = 7) }
        }
        compose.onNodeWithText("Automatic downloads only").assertIsDisplayed()
        compose.onNodeWithText("Watched downloads move to the Trash 24 hours", substring = true).assertIsDisplayed()
        for ((label, mode) in listOf(
            "All downloads" to DeleteAfterWatchingMode.AllDownloads,
            "Off" to DeleteAfterWatchingMode.Off,
            "Automatic downloads only" to DeleteAfterWatchingMode.AutomaticOnly,
        )) {
            compose.onNodeWithText("Delete after watching").performClick()
            compose.onNode(hasText(label) and hasAnyAncestor(isDialog())).performClick()
            compose.onNodeWithText(label).assertIsDisplayed()
            assertEquals(7 to mode, controller.changes.last())
        }
    }
}
