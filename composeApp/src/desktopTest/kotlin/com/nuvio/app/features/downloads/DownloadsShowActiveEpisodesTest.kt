package com.nuvio.app.features.downloads

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_choose_another_source_description
import nuvio.composeapp.generated.resources.downloads_pause_description
import nuvio.composeapp.generated.resources.downloads_play_description
import nuvio.composeapp.generated.resources.downloads_resume_description
import nuvio.composeapp.generated.resources.downloads_try_source_again
import nuvio.composeapp.generated.resources.episodes_season
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Before
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DownloadsShowActiveEpisodesTest {
    @get:Rule
    val compose = createComposeRule()

    private val backend = DownloadsScreenTest.FakeBackend()
    private val realBackend = DownloadsRepository.backend

    @Before
    fun setup() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
    }

    @After
    fun tearDown() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
    }

    private fun text(resource: StringResource, vararg arguments: Any) = runBlocking { getString(resource, *arguments) }

    private fun episodeJson(
        id: String,
        episode: Int,
        status: String,
        updatedAtEpochMs: Long = 2L,
        extra: String = "",
    ): String {
        val filePart = if (status == "Completed") ""","localFileUri":"file:/$id.mkv"""" else ""
        return """{"id":"$id","contentType":"series","parentMetaId":"tt1","parentMetaType":"series","videoId":"tt1:2:$episode",""" +
            """"title":"Pantheon","episodeTitle":"Episode $episode","seasonNumber":2,"episodeNumber":$episode,""" +
            """"streamTitle":"1080p","providerName":"Addon","sourceUrl":"https://example.com/$id.mkv","fileName":"$id.mkv",""" +
            """"status":"$status","createdAtEpochMs":1,"updatedAtEpochMs":$updatedAtEpochMs$filePart$extra}"""
    }

    @Test
    fun show_page_lists_completed_downloading_paused_and_failed_episodes_with_controls_and_speed() {
        val now = System.currentTimeMillis()
        val chosenForSource = mutableListOf<String>()
        backend.payload = """{"items":[${
            listOf(
                episodeJson("c", episode = 1, status = "Completed", extra = ""","downloadedBytes":1000,"totalBytes":1000"""),
                episodeJson("d", episode = 2, status = "Downloading", updatedAtEpochMs = now - 1_000L, extra = ""","downloadedBytes":1000000,"totalBytes":10000000"""),
                episodeJson("p", episode = 3, status = "Paused", extra = ""","downloadedBytes":500,"totalBytes":1000"""),
                episodeJson("f", episode = 4, status = "Failed", extra = ""","failureKind":"SourceRefused","errorMessage":"HTTP 403""""),
            ).joinToString(",")
        }]}"""
        DownloadsRepository.ensureLoaded()

        compose.setContent {
            NuvioTheme {
                DownloadsScreenContent(
                    onBack = {},
                    onOpenDownload = {},
                    onChooseAnotherSource = { chosenForSource += it.id },
                    showAutoDownloads = false,
                )
            }
        }
        compose.waitForIdle()

        // Navigate into the show page from the downloads list.
        compose.onNodeWithText("Pantheon").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(text(Res.string.episodes_season, 2)).assertIsDisplayed()

        // Completed, downloading, paused, and failed episodes are all present with their controls.
        compose.onNodeWithContentDescription(text(Res.string.downloads_play_description, "Pantheon S02E01")).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(Res.string.downloads_pause_description, "Pantheon S02E02")).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(Res.string.downloads_resume_description, "Pantheon S02E03")).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_try_source_again)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(Res.string.downloads_choose_another_source_description, "Pantheon S02E04")).assertIsDisplayed()

        // Byte progress on the downloading episode displays live speed on the show page.
        backend.progress.getValue("d")(3_000_000L, 10_000_000L)
        compose.waitForIdle()
        compose.onNode(hasText("/s", substring = true)).assertIsDisplayed()

        // Controls act on the episodes directly from the show page.
        compose.onNodeWithContentDescription(text(Res.string.downloads_pause_description, "Pantheon S02E02")).performClick()
        compose.waitForIdle()
        assertEquals(DownloadStatus.Paused, DownloadsRepository.uiState.value.items.first { it.id == "d" }.status)

        compose.onNodeWithContentDescription(text(Res.string.downloads_resume_description, "Pantheon S02E03")).performClick()
        compose.waitForIdle()
        assertEquals(DownloadStatus.Downloading, DownloadsRepository.uiState.value.items.first { it.id == "p" }.status)

        compose.onNodeWithContentDescription(text(Res.string.downloads_choose_another_source_description, "Pantheon S02E04")).performClick()
        compose.waitForIdle()
        assertEquals(listOf("f"), chosenForSource)
    }

    @Test
    fun show_page_resolves_title_and_lists_episodes_when_show_only_has_active_downloads() {
        backend.payload = """{"items":[${
            episodeJson("p", episode = 1, status = "Paused", extra = ""","downloadedBytes":500,"totalBytes":1000""")
        }]}"""
        DownloadsRepository.ensureLoaded()

        compose.setContent {
            NuvioTheme {
                DownloadsScreenContent(
                    onBack = {},
                    onOpenDownload = {},
                    initialShowId = "tt1",
                    showAutoDownloads = false,
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Pantheon").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(Res.string.downloads_resume_description, "Pantheon S02E01")).assertIsDisplayed()
    }
}
