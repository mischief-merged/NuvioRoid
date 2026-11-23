package com.nuvio.app.features.downloads

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_check_again
import nuvio.composeapp.generated.resources.downloads_choose_another_source
import nuvio.composeapp.generated.resources.downloads_pick_source_myself
import nuvio.composeapp.generated.resources.downloads_pick_source_myself_description
import nuvio.composeapp.generated.resources.downloads_delete_description
import nuvio.composeapp.generated.resources.downloads_source_search_none_found
import nuvio.composeapp.generated.resources.downloads_source_search_trying
import nuvio.composeapp.generated.resources.downloads_source_search_waiting
import nuvio.composeapp.generated.resources.downloads_try_source_again
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Before
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a row with a bad source says while the app looks for another source by itself, and once it has given up. */
@OptIn(ExperimentalTestApi::class)
class SourceReplacementScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val backend = DownloadsScreenTest.FakeBackend()
    private val realBackend = DownloadsRepository.backend
    private var chosen: DownloadItem? = null
    private val reason = "Not the real video: 8.1 MB arrived, the source said 9.5 GB"

    @Before
    fun use_an_in_memory_store() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
    }

    @After
    fun restore_the_real_repository() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
    }

    private fun text(resource: StringResource, vararg arguments: Any) = runBlocking { getString(resource, *arguments) }

    private fun flagged(extra: String = "") =
        """{"id":"a","contentType":"movie","parentMetaType":"movie","videoId":"tt1","parentMetaId":"tt1","title":"Disclosure Day",""" +
            """"streamTitle":"1080p","providerName":"Addon","sourceUrl":"https://example.com/a.mkv","fileName":"a.mkv",""" +
            """"status":"Completed","createdAtEpochMs":1,"updatedAtEpochMs":1,"localFileUri":"file:/a.mkv",""" +
            """"downloadedBytes":8509628,"totalBytes":8509628,"framesCheck":"NotTheRealVideo","errorMessage":"$reason"$extra}"""

    private fun failed(extra: String = "") =
        """{"id":"f","contentType":"movie","parentMetaType":"movie","videoId":"tt1","parentMetaId":"tt1","title":"Disclosure Day",""" +
            """"streamTitle":"1080p","providerName":"Addon","sourceUrl":"https://example.com/f.mkv","fileName":"f.mkv",""" +
            """"status":"Failed","failureKind":"BadFile","errorMessage":"$reason","createdAtEpochMs":1,"updatedAtEpochMs":1$extra}"""

    private fun show(item: String, searches: Map<String, SourceSearchProgress> = emptyMap()) {
        backend.payload = """{"items":[$item]}"""
        compose.setContent {
            NuvioTheme {
                DownloadsScreenContent(
                    onBack = {},
                    onOpenDownload = {},
                    showAutoDownloads = false,
                    onChooseAnotherSource = { chosen = it },
                    sourceSearches = MutableStateFlow(searches),
                )
            }
        }
    }

    @Test
    fun while_the_app_tries_another_source_the_row_says_which_try_it_is_and_offers_no_choice_yet() {
        show(flagged(), mapOf("tt1|movie" to SourceSearchProgress(2)))

        compose.onNodeWithText(reason).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_source_search_trying, 2, 3)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_pick_source_myself)).assertDoesNotExist()
        compose.onNodeWithText(text(Res.string.downloads_choose_another_source)).assertDoesNotExist()
        compose.onNodeWithText(text(Res.string.downloads_check_again)).assertDoesNotExist()
    }

    @Test
    fun before_the_search_starts_the_row_says_another_source_will_be_tried() {
        show(flagged())

        compose.onNodeWithText(text(Res.string.downloads_source_search_waiting)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_pick_source_myself)).assertDoesNotExist()
    }

    @Test
    fun when_no_working_source_was_found_the_user_picks_one_or_deletes_it() {
        show(flagged(""","noWorkingSourceFound":true"""))

        compose.onNodeWithText(text(Res.string.downloads_source_search_none_found)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_check_again)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(Res.string.downloads_delete_description, "Disclosure Day")).assertIsDisplayed()
        assertNull(chosen)
        compose.onNodeWithContentDescription(text(Res.string.downloads_pick_source_myself_description, "Disclosure Day")).performClick()
        assertEquals("a", chosen?.id)
    }

    @Test
    fun a_failed_source_given_up_on_offers_only_the_users_own_pick() {
        show(failed(""","noWorkingSourceFound":true"""))

        compose.onNodeWithText(text(Res.string.downloads_source_search_none_found)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_pick_source_myself)).performClick()
        assertEquals("f", chosen?.id)
        compose.onNodeWithText(text(Res.string.downloads_try_source_again)).assertDoesNotExist()
    }

    @Test
    fun a_retained_failed_file_with_a_good_copy_does_not_promise_another_search() {
        val good = """{"id":"good","contentType":"movie","parentMetaType":"movie","videoId":"tt1","parentMetaId":"tt1","title":"Disclosure Day",""" +
            """"streamTitle":"2160p","providerName":"Addon","sourceUrl":"https://example.com/good.mkv","fileName":"good.mkv",""" +
            """"status":"Completed","createdAtEpochMs":2,"updatedAtEpochMs":2,"localFileUri":"file:/good.mkv",""" +
            """"downloadedBytes":9000000000,"totalBytes":9000000000}"""
        show(failed(""","awaitingCheck":true,"localFileUri":"file:/retained.mkv"""") + "," + good)

        compose.onNodeWithText(text(Res.string.downloads_source_search_waiting)).assertDoesNotExist()
        compose.onNodeWithText(text(Res.string.downloads_choose_another_source)).performClick()
        assertEquals("f", chosen?.id)
    }

    @Test
    fun a_download_the_app_started_in_place_of_a_bad_one_says_which_try_it_is() {
        show(
            """{"id":"d","contentType":"movie","parentMetaType":"movie","videoId":"tt1","parentMetaId":"tt1","title":"Disclosure Day",""" +
                """"streamTitle":"1080p","providerName":"Addon","sourceUrl":"https://example.com/d.mkv","fileName":"d.mkv",""" +
                """"status":"Downloading","createdAtEpochMs":1,"updatedAtEpochMs":1,"autoSourceAttempts":1}""",
            mapOf("tt1|movie" to SourceSearchProgress(1)),
        )

        compose.onNodeWithText(text(Res.string.downloads_source_search_trying, 1, 3)).assertIsDisplayed()
    }
}
