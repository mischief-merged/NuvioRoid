package com.nuvio.app.features.downloads

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.DesktopEscapeShortcut
import com.nuvio.app.core.ui.DesktopKeyboardShortcutHost
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.PlatformEscapeShortcut
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_section_shows
import nuvio.composeapp.generated.resources.episodes_season
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Before
import org.junit.Rule
import java.awt.Canvas
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DownloadsShowEscapeTest {
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

    private fun completedEpisode(): String {
        backend.files += "file:/ep1.mkv"
        return """{"id":"ep1","contentType":"series","parentMetaId":"tt1","parentMetaType":"series","videoId":"tt1:2:3","title":"Pantheon",""" +
            """"episodeTitle":"Episode 3","seasonNumber":2,"episodeNumber":3,"streamTitle":"1080p","providerName":"Addon",""" +
            """"sourceUrl":"https://example.com/ep1.mkv","fileName":"ep1.mkv","status":"Completed","localFileUri":"file:/ep1.mkv",""" +
            """"downloadedBytes":100,"totalBytes":100,"createdAtEpochMs":1,"updatedAtEpochMs":2}"""
    }

    private fun sendAwtEscape(): Boolean {
        val event = KeyEvent(
            Canvas(),
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            0,
            KeyEvent.VK_ESCAPE,
            KeyEvent.CHAR_UNDEFINED,
        )
        return KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchKeyEvent(event)
    }

    @Test
    fun escape_on_downloads_show_page_returns_to_downloads_list_before_leaving_downloads() {
        backend.payload = """{"items":[${completedEpisode()}]}"""
        DownloadsRepository.ensureLoaded()
        var rootBackCalls = 0

        compose.setContent {
            NuvioTheme {
                DesktopKeyboardShortcutHost()
                PlatformEscapeShortcut { rootBackCalls += 1 }
                DownloadsScreenContent(
                    onBack = { rootBackCalls += 1 },
                    onOpenDownload = {},
                    showAutoDownloads = false,
                )
            }
        }
        compose.waitForIdle()

        // Open the show page from the downloads list.
        compose.onNodeWithText("Pantheon").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(text(Res.string.episodes_season, 2)).assertIsDisplayed()

        // First Escape returns from the show page to the downloads list without leaving Downloads.
        assertTrue(sendAwtEscape())
        compose.waitForIdle()

        assertEquals(0, rootBackCalls, "Escape left Downloads instead of returning to the downloads list")
        compose.onNodeWithText(text(Res.string.downloads_section_shows)).assertIsDisplayed()

        // Wait past the Escape debounce window; second Escape leaves Downloads via root back.
        Thread.sleep(350)
        assertTrue(sendAwtEscape())
        compose.waitForIdle()

        assertEquals(1, rootBackCalls)
    }

    @Test
    fun local_escape_handlers_unwind_in_lifo_order_before_root_back() {
        val order = mutableListOf<String>()
        val root: () -> Unit = { order += "root" }
        val outer: () -> Unit = { order += "outer" }
        val inner: () -> Unit = { order += "inner" }

        DesktopEscapeShortcut.register(root)
        DesktopEscapeShortcut.registerLocal(outer)
        DesktopEscapeShortcut.registerLocal(inner)
        try {
            assertTrue(DesktopEscapeShortcut.dispatchBack())
            DesktopEscapeShortcut.unregisterLocal(inner)
            assertTrue(DesktopEscapeShortcut.dispatchBack())
            DesktopEscapeShortcut.unregisterLocal(outer)
            assertTrue(DesktopEscapeShortcut.dispatchBack())

            assertEquals(listOf("inner", "outer", "root"), order)
        } finally {
            DesktopEscapeShortcut.unregisterLocal(inner)
            DesktopEscapeShortcut.unregisterLocal(outer)
            DesktopEscapeShortcut.unregister(root)
        }
    }
}
