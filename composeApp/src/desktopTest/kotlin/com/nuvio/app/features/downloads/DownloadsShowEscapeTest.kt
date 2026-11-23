package com.nuvio.app.features.downloads

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.AppScreenTab
import com.nuvio.app.controller.ControllerEvent
import com.nuvio.app.controller.ControllerFocusEngine
import com.nuvio.app.controller.ControllerNavigationHost
import com.nuvio.app.controller.XboxButton
import com.nuvio.app.core.ui.DesktopEscapeShortcut
import com.nuvio.app.core.ui.DesktopKeyboardShortcutHost
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.PlatformEscapeShortcut
import com.nuvio.app.core.ui.dispatchPlatformLocalBack
import com.nuvio.app.navigation.DownloadsRoute
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_delete_description
import nuvio.composeapp.generated.resources.downloads_delete_title
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
        return KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(event)
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
    fun gamepad_b_on_downloads_show_page_returns_to_downloads_list_before_leaving_downloads() {
        backend.payload = """{"items":[${completedEpisode()}]}"""
        DownloadsRepository.ensureLoaded()
        var rootBackCalls = 0

        compose.setContent {
            NuvioTheme {
                ControllerNavigationHost(
                    currentRoute = DownloadsRoute(),
                    selectedTab = AppScreenTab.Library,
                    onBack = {
                        if (!dispatchPlatformLocalBack()) {
                            rootBackCalls += 1
                        }
                        true
                    },
                )
                DownloadsScreenContent(
                    onBack = { rootBackCalls += 1 },
                    onOpenDownload = {},
                    showAutoDownloads = false,
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Pantheon").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(text(Res.string.episodes_season, 2)).assertIsDisplayed()

        // First gamepad B runs the show page's PlatformBackHandler without leaving Downloads.
        assertTrue(ControllerFocusEngine.handleEvent(ControllerEvent.ButtonPressed(XboxButton.B)))
        compose.waitForIdle()

        assertEquals(0, rootBackCalls, "Gamepad B left Downloads instead of returning to the downloads list")
        compose.onNodeWithText(text(Res.string.downloads_section_shows)).assertIsDisplayed()

        // Second gamepad B leaves Downloads via root back.
        assertTrue(ControllerFocusEngine.handleEvent(ControllerEvent.ButtonPressed(XboxButton.B)))
        compose.waitForIdle()

        assertEquals(1, rootBackCalls)
    }

    @Test
    fun escape_on_downloads_show_page_with_delete_dialog_dismisses_dialog_before_leaving_show() {
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

        // Open the show page and open the Delete confirmation dialog.
        compose.onNodeWithText("Pantheon").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(text(Res.string.episodes_season, 2)).assertIsDisplayed()

        val deleteButtonDesc = text(Res.string.downloads_delete_description, "Pantheon S02E03")
        val deleteDialogTitle = text(Res.string.downloads_delete_title, "Pantheon S02E03")
        compose.onNodeWithContentDescription(deleteButtonDesc).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(deleteDialogTitle).assertIsDisplayed()

        // First Escape dismisses the Delete dialog while staying on the show page.
        assertTrue(sendAwtEscape())
        compose.waitForIdle()
        compose.onAllNodesWithText(deleteDialogTitle).assertCountEquals(0)
        compose.onNodeWithText(text(Res.string.episodes_season, 2)).assertIsDisplayed()
        assertEquals(0, rootBackCalls)

        // Second Escape returns from the show page to the downloads list.
        Thread.sleep(350)
        assertTrue(sendAwtEscape())
        compose.waitForIdle()
        compose.onNodeWithText(text(Res.string.downloads_section_shows)).assertIsDisplayed()
        assertEquals(0, rootBackCalls)

        // Third Escape leaves Downloads via root back.
        Thread.sleep(350)
        assertTrue(sendAwtEscape())
        compose.waitForIdle()
        assertEquals(1, rootBackCalls)
    }

    @Test
    fun modal_handlers_take_priority_over_local_page_handlers() {
        val order = mutableListOf<String>()
        val root: () -> Unit = { order += "root" }
        val pageBefore: () -> Unit = { order += "pageBefore" }
        val modal: () -> Unit = { order += "modal" }
        val pageAfter: () -> Unit = { order += "pageAfter" }

        DesktopEscapeShortcut.register(root)
        DesktopEscapeShortcut.registerLocal(pageBefore)
        DesktopEscapeShortcut.registerModal(modal)
        DesktopEscapeShortcut.registerLocal(pageAfter)
        try {
            assertTrue(DesktopEscapeShortcut.dispatchBack())
            DesktopEscapeShortcut.unregisterModal(modal)
            assertTrue(DesktopEscapeShortcut.dispatchBack())
            DesktopEscapeShortcut.unregisterLocal(pageAfter)
            assertTrue(DesktopEscapeShortcut.dispatchBack())
            DesktopEscapeShortcut.unregisterLocal(pageBefore)
            assertTrue(DesktopEscapeShortcut.dispatchBack())

            assertEquals(listOf("modal", "pageAfter", "pageBefore", "root"), order)
        } finally {
            DesktopEscapeShortcut.unregisterModal(modal)
            DesktopEscapeShortcut.unregisterLocal(pageAfter)
            DesktopEscapeShortcut.unregisterLocal(pageBefore)
            DesktopEscapeShortcut.unregister(root)
        }
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
