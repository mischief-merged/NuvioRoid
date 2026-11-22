package com.nuvio.app.features.downloads

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import com.nuvio.app.core.ui.NuvioTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class DownloadCustomPosterTest {
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

    private fun movieWithoutArtwork() = DownloadItem(
        id = "m1",
        contentType = "movie",
        parentMetaId = "tt2543164",
        parentMetaType = "movie",
        videoId = "tt2543164",
        title = "Arrival",
        poster = null,
        background = null,
        episodeThumbnail = null,
        streamTitle = "1080p",
        providerName = "Addon",
        sourceUrl = "https://example.com/arrival.mkv",
        fileName = "arrival.mkv",
        status = DownloadStatus.Completed,
        localFileUri = "file:/arrival.mkv",
    )

    private fun episodeWithoutArtwork() = DownloadItem(
        id = "e1",
        contentType = "series",
        parentMetaId = "tt11680642",
        parentMetaType = "series",
        videoId = "tt11680642:1:1",
        title = "Pantheon",
        episodeTitle = "Episode 1",
        seasonNumber = 1,
        episodeNumber = 1,
        poster = null,
        background = null,
        episodeThumbnail = null,
        streamTitle = "1080p",
        providerName = "Addon",
        sourceUrl = "https://example.com/pantheon.mkv",
        fileName = "pantheon.mkv",
        status = DownloadStatus.Completed,
        localFileUri = "file:/pantheon.mkv",
    )

    @Test
    fun custom_poster_url_resolves_when_original_artwork_is_missing() {
        val pattern = "https://posters.example/{type}/{imdb_id}.jpg"
        val movie = movieWithoutArtwork()
        val episode = episodeWithoutArtwork()

        assertNull(movie.posterArtwork())
        assertNull(episode.posterArtwork())
        assertEquals("https://posters.example/movie/tt2543164.jpg", movie.resolvedPosterUrl(pattern))
        assertEquals("https://posters.example/series/tt11680642.jpg", episode.resolvedPosterUrl(pattern))
        assertNull(movie.resolvedPosterUrl(""))
    }

    @Test
    fun downloads_screen_composes_custom_posters_for_movies_and_shows_without_original_artwork() {
        backend.files += "file:/arrival.mkv"
        backend.files += "file:/pantheon.mkv"
        backend.payload = """{"items":[
            {"id":"m1","contentType":"movie","parentMetaId":"tt2543164","parentMetaType":"movie","videoId":"tt2543164","title":"Arrival","streamTitle":"1080p","providerName":"Addon","sourceUrl":"https://example.com/arrival.mkv","fileName":"arrival.mkv","status":"Completed","localFileUri":"file:/arrival.mkv","downloadedBytes":100,"totalBytes":100,"createdAtEpochMs":1,"updatedAtEpochMs":2},
            {"id":"e1","contentType":"series","parentMetaId":"tt11680642","parentMetaType":"series","videoId":"tt11680642:1:1","title":"Pantheon","episodeTitle":"Episode 1","seasonNumber":1,"episodeNumber":1,"streamTitle":"1080p","providerName":"Addon","sourceUrl":"https://example.com/pantheon.mkv","fileName":"pantheon.mkv","status":"Completed","localFileUri":"file:/pantheon.mkv","downloadedBytes":100,"totalBytes":100,"createdAtEpochMs":1,"updatedAtEpochMs":2}
        ]}"""
        DownloadsRepository.ensureLoaded()

        compose.setContent {
            NuvioTheme {
                DownloadsScreenContent(
                    onBack = {},
                    onOpenDownload = {},
                    showAutoDownloads = false,
                    posterPatternOverride = "https://posters.example/{type}/{imdb_id}.jpg",
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Arrival", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Pantheon", useUnmergedTree = true).assertExists()
    }

    @Test
    fun downloads_screen_omits_poster_when_neither_custom_nor_original_artwork_exists() {
        backend.files += "file:/arrival.mkv"
        backend.files += "file:/pantheon.mkv"
        backend.payload = """{"items":[
            {"id":"m1","contentType":"movie","parentMetaId":"tt2543164","parentMetaType":"movie","videoId":"tt2543164","title":"Arrival","streamTitle":"1080p","providerName":"Addon","sourceUrl":"https://example.com/arrival.mkv","fileName":"arrival.mkv","status":"Completed","localFileUri":"file:/arrival.mkv","downloadedBytes":100,"totalBytes":100,"createdAtEpochMs":1,"updatedAtEpochMs":2},
            {"id":"e1","contentType":"series","parentMetaId":"tt11680642","parentMetaType":"series","videoId":"tt11680642:1:1","title":"Pantheon","episodeTitle":"Episode 1","seasonNumber":1,"episodeNumber":1,"streamTitle":"1080p","providerName":"Addon","sourceUrl":"https://example.com/pantheon.mkv","fileName":"pantheon.mkv","status":"Completed","localFileUri":"file:/pantheon.mkv","downloadedBytes":100,"totalBytes":100,"createdAtEpochMs":1,"updatedAtEpochMs":2}
        ]}"""
        DownloadsRepository.ensureLoaded()

        compose.setContent {
            NuvioTheme {
                DownloadsScreenContent(
                    onBack = {},
                    onOpenDownload = {},
                    showAutoDownloads = false,
                    posterPatternOverride = "",
                )
            }
        }
        compose.waitForIdle()

        compose.onAllNodesWithContentDescription("Arrival", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Pantheon", useUnmergedTree = true).assertCountEquals(0)
    }
}
