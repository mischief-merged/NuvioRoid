package com.nuvio.app.controller

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.nuvio.app.AppScreenTab
import com.nuvio.app.core.ui.ComposePopupFocus
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.navigation.DetailRoute
import com.nuvio.app.navigation.TabsRoute
import org.junit.After
import org.junit.Before
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3Api::class)
class ModalOverlayFocusAndShortcutsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        ControllerManager.forceDeactivateController()
        ControllerFocusEngine.deferNavigationToCompose = false
        ControllerFocusEngine.setNavigationZoneExpanded(false)
        ControllerFocusEngine.clearFocus()
    }

    @After
    fun tearDown() {
        ControllerManager.forceDeactivateController()
        ControllerFocusEngine.deferNavigationToCompose = false
        ControllerFocusEngine.setNavigationZoneExpanded(false)
        ControllerFocusEngine.clearFocus()
    }

    @Test
    fun bottom_sheet_captures_popup_focus_manager_and_never_leaks_focus_to_background_posters() {
        var outerFocusManager: FocusManager? = null
        var sheetOpen by mutableStateOf(true)
        var showFormInsteadOfRows by mutableStateOf(false)

        compose.setContent {
            NuvioTheme {
                outerFocusManager = LocalFocusManager.current
                ControllerNavigationHost(
                    currentRoute = DetailRoute(type = "series", id = "tt1", title = "Show"),
                    selectedTab = AppScreenTab.Home,
                    onBack = { false },
                )
                Column {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .controllerFocusable(
                                id = "bg_poster_1",
                                zone = ControllerFocusZone.Content,
                                groupKey = "shelf_1",
                                onSelect = {},
                            ),
                    )
                    if (sheetOpen) {
                        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                        NuvioModalBottomSheet(
                            onDismissRequest = { sheetOpen = false },
                            sheetState = sheetState,
                        ) {
                            if (!showFormInsteadOfRows) {
                                NuvioBottomSheetActionRow(
                                    title = "Mark watched",
                                    focusId = "sheet_row_1",
                                    onClick = {},
                                )
                                NuvioBottomSheetActionRow(
                                    title = "Rate",
                                    focusId = "sheet_row_2",
                                    onClick = {},
                                )
                            } else {
                                Button(onClick = {}) { Text("Submit rating") }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        // Route deferral stays intact (never mutated to false by the sheet) while modal overlay is active.
        assertTrue(ControllerFocusEngine.deferNavigationToCompose)
        assertTrue(ControllerFocusEngine.isModalOverlayActive)

        // ComposePopupFocus must hold the ModalBottomSheet popup's own FocusManager, not the outer screen's.
        val popupFocusManager = ComposePopupFocus.active
        assertNotNull(popupFocusManager)
        assertNotNull(outerFocusManager)
        assertNotSame(outerFocusManager, popupFocusManager)

        // Spatial navigation works inside Overlay action rows even on a Compose-driven route.
        assertTrue(ControllerFocusEngine.focusFirstInGroup("bottom_sheet_actions"))
        assertEquals("sheet_row_1", ControllerFocusEngine.focusedNodeId.value)
        assertTrue(ControllerManager.dispatchKeyboardNavigation(ControllerDirection.Down))
        assertEquals("sheet_row_2", ControllerFocusEngine.focusedNodeId.value)

        // Swapping from action rows to a Compose form removes all Overlay nodes while the sheet stays open.
        // Background posters must NOT receive spatial focus, and arrows must fall through to Compose.
        showFormInsteadOfRows = true
        compose.waitForIdle()

        assertTrue(ControllerFocusEngine.isModalOverlayActive)
        assertNull(ControllerFocusEngine.focusedNodeId.value)
        assertFalse(ControllerManager.dispatchKeyboardNavigation(ControllerDirection.Down))
        assertNull(ControllerFocusEngine.focusedNodeId.value)

        // Dismissing the sheet clears both modal overlay ownership and popup focus tracking.
        sheetOpen = false
        compose.waitForIdle()

        assertFalse(ControllerFocusEngine.isModalOverlayActive)
        assertNull(ComposePopupFocus.active)
        assertTrue(ControllerFocusEngine.deferNavigationToCompose)
    }

    @Test
    fun shell_shortcuts_yield_while_modal_sheet_is_open_and_resume_after_close() {
        var sheetOpen by mutableStateOf(true)
        var bumperLeftCalls = 0
        var bumperRightCalls = 0
        var searchCalls = 0
        var openNavCalls = 0

        compose.setContent {
            NuvioTheme {
                ControllerNavigationHost(
                    currentRoute = TabsRoute,
                    selectedTab = AppScreenTab.Home,
                    onBack = { false },
                    onBumperLeft = {
                        bumperLeftCalls += 1
                        true
                    },
                    onBumperRight = {
                        bumperRightCalls += 1
                        true
                    },
                    onSearch = {
                        searchCalls += 1
                        true
                    },
                    onOpenNavigation = {
                        openNavCalls += 1
                        true
                    },
                )
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .controllerFocusable(
                            id = "bg_home_poster",
                            zone = ControllerFocusZone.Content,
                            groupKey = "shelf_1",
                            onSelect = {},
                        ),
                )
                if (sheetOpen) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    NuvioModalBottomSheet(
                        onDismissRequest = { sheetOpen = false },
                        sheetState = sheetState,
                    ) {
                        NuvioBottomSheetActionRow(
                            title = "Resume",
                            focusId = "cw_row_1",
                            onClick = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        assertTrue(ControllerFocusEngine.isModalOverlayActive)
        assertFalse(ControllerManager.dispatchKeyboardShortcut(XboxButton.RB))
        assertFalse(ControllerManager.dispatchKeyboardShortcut(XboxButton.LB))
        assertFalse(ControllerManager.dispatchKeyboardShortcut(XboxButton.Y))
        assertFalse(ControllerManager.dispatchKeyboardShortcut(XboxButton.Menu))
        assertFalse(ControllerFocusEngine.handleEvent(ControllerEvent.ButtonPressed(XboxButton.RB)))
        assertFalse(ControllerFocusEngine.handleEvent(ControllerEvent.ButtonPressed(XboxButton.LB)))
        assertEquals(0, bumperLeftCalls)
        assertEquals(0, bumperRightCalls)
        assertEquals(0, searchCalls)
        assertEquals(0, openNavCalls)

        // Gamepad B closes the sheet.
        assertTrue(ControllerFocusEngine.handleEvent(ControllerEvent.ButtonPressed(XboxButton.B)))
        compose.waitForIdle()
        assertFalse(sheetOpen)
        assertFalse(ControllerFocusEngine.isModalOverlayActive)

        // After closing the sheet, shell shortcuts work again.
        assertTrue(ControllerManager.dispatchKeyboardShortcut(XboxButton.RB))
        assertTrue(ControllerManager.dispatchKeyboardShortcut(XboxButton.LB))
        assertTrue(ControllerManager.dispatchKeyboardShortcut(XboxButton.Y))
        assertEquals(1, bumperRightCalls)
        assertEquals(1, bumperLeftCalls)
        assertEquals(1, searchCalls)
    }

    @Test
    fun navigation_shortcut_is_not_swallowed_on_compose_driven_detail_route() {
        compose.setContent {
            NuvioTheme {
                ControllerNavigationHost(
                    currentRoute = DetailRoute(type = "movie", id = "tt2", title = "Movie"),
                    selectedTab = AppScreenTab.Home,
                    onBack = { false },
                    onOpenNavigation = { false },
                )
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .controllerFocusable(
                            id = "sidebar_home",
                            zone = ControllerFocusZone.Navigation,
                            onSelect = {},
                        ),
                )
            }
        }
        compose.waitForIdle()

        assertTrue(ControllerFocusEngine.deferNavigationToCompose)
        assertFalse(ControllerFocusEngine.requestFocus("sidebar_home", triggerScroll = false))
        assertNull(ControllerFocusEngine.focusedNodeId.value)
        assertFalse(ControllerManager.dispatchKeyboardShortcut(XboxButton.Menu))
        assertFalse(ControllerFocusEngine.isNavigationZoneExpanded.value)
        assertNull(ControllerFocusEngine.focusedNodeId.value)
    }
}
