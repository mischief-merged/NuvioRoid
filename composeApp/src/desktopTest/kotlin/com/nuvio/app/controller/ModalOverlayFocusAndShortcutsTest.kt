package com.nuvio.app.controller

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.nuvio.app.AppScreenTab
import com.nuvio.app.core.ui.ComposePopupFocus
import com.nuvio.app.core.ui.DesktopEscapeShortcut
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.PlatformKeyboardNavigationHost
import com.nuvio.app.features.profiles.SidebarProfileSwitcherStack
import com.nuvio.app.navigation.DetailRoute
import com.nuvio.app.navigation.TabsRoute
import java.awt.Canvas
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
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

    @Test
    fun desktop_dropdown_owns_modal_input_and_blocks_shell_shortcuts() {
        var bumperRightCalls = 0
        var searchCalls = 0
        var openNavCalls = 0

        compose.setContent {
            NuvioTheme {
                ControllerNavigationHost(
                    currentRoute = TabsRoute,
                    selectedTab = AppScreenTab.Library,
                    onBack = { false },
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
                NuvioDropdownChip(
                    title = "Filter",
                    label = "All Types",
                    options = listOf(
                        NuvioDropdownOption("all", "All Types"),
                        NuvioDropdownOption("movie", "Movies"),
                    ),
                    selectedKey = "all",
                    onSelected = {},
                )
            }
        }
        compose.waitForIdle()
        assertFalse(ControllerFocusEngine.isModalOverlayActive)

        // Open the desktop dropdown menu.
        compose.onNodeWithText("All Types").performClick()
        compose.waitForIdle()

        assertTrue(ControllerFocusEngine.isModalOverlayActive)
        assertFalse(ControllerManager.dispatchKeyboardShortcut(XboxButton.RB))
        assertFalse(ControllerManager.dispatchKeyboardShortcut(XboxButton.Y))
        assertFalse(ControllerManager.dispatchKeyboardShortcut(XboxButton.Menu))
        assertFalse(ControllerFocusEngine.handleEvent(ControllerEvent.ButtonPressed(XboxButton.RB)))
        assertEquals(0, bumperRightCalls)
        assertEquals(0, searchCalls)
        assertEquals(0, openNavCalls)

        // Gamepad B closes the dropdown, releasing modal ownership so shell shortcuts work again.
        assertTrue(ControllerFocusEngine.handleEvent(ControllerEvent.ButtonPressed(XboxButton.B)))
        compose.waitForIdle()
        assertFalse(ControllerFocusEngine.isModalOverlayActive)
        assertTrue(ControllerManager.dispatchKeyboardShortcut(XboxButton.RB))
        assertEquals(1, bumperRightCalls)
    }

    @Test
    fun sidebar_profile_switcher_blocks_shell_shortcuts_while_open() {
        var switcherOpen by mutableStateOf(true)
        var bumperRightCalls = 0

        compose.setContent {
            NuvioTheme {
                ControllerNavigationHost(
                    currentRoute = TabsRoute,
                    selectedTab = AppScreenTab.Home,
                    onBack = { false },
                    onBumperRight = {
                        bumperRightCalls += 1
                        true
                    },
                )
                if (switcherOpen) {
                    SidebarProfileSwitcherStack(
                        onProfileSelected = {},
                        onAddProfileRequested = {},
                        onDismissRequest = { switcherOpen = false },
                    )
                }
            }
        }
        compose.waitForIdle()

        assertTrue(ControllerFocusEngine.isModalOverlayActive)
        assertFalse(ControllerManager.dispatchKeyboardShortcut(XboxButton.RB))
        assertFalse(ControllerFocusEngine.handleEvent(ControllerEvent.ButtonPressed(XboxButton.RB)))
        assertEquals(0, bumperRightCalls)

        assertTrue(ControllerFocusEngine.handleEvent(ControllerEvent.ButtonPressed(XboxButton.B)))
        compose.waitForIdle()
        assertFalse(switcherOpen)
        assertFalse(ControllerFocusEngine.isModalOverlayActive)
        assertTrue(ControllerManager.dispatchKeyboardShortcut(XboxButton.RB))
        assertEquals(1, bumperRightCalls)
    }

    @Test
    fun background_page_does_not_scroll_while_modal_sheet_is_open() {
        var sheetOpen by mutableStateOf(true)

        compose.setContent {
            NuvioTheme {
                val listState = rememberLazyListState()
                RegisterControllerVerticalScroll(listState)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.height(300.dp),
                ) {
                    items(30) { index ->
                        Box(modifier = Modifier.height(60.dp)) {
                            Text("Row $index")
                        }
                    }
                }
                if (sheetOpen) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    NuvioModalBottomSheet(
                        onDismissRequest = { sheetOpen = false },
                        sheetState = sheetState,
                    ) {
                        Button(onClick = {}) { Text("Only control") }
                    }
                }
            }
        }
        compose.waitForIdle()

        assertTrue(ControllerFocusEngine.isModalOverlayActive)
        assertFalse(ControllerFocusEngine.scrollActivePage(down = true))
        assertFalse(ControllerFocusEngine.handleEvent(ControllerEvent.AnalogScroll(0f, 1f)))

        sheetOpen = false
        compose.waitForIdle()

        assertFalse(ControllerFocusEngine.isModalOverlayActive)
        assertTrue(ControllerFocusEngine.scrollActivePage(down = true))
    }

    @Test
    fun explicit_navigation_entry_survives_subsequent_content_registration() {
        var showLateContent by mutableStateOf(false)

        compose.setContent {
            NuvioTheme {
                ControllerNavigationHost(
                    currentRoute = TabsRoute,
                    selectedTab = AppScreenTab.Home,
                    onBack = { false },
                    onOpenNavigation = {
                        ControllerFocusEngine.focusNavigationItem("top_nav_home", "sidebar_home")
                    },
                )
                Column {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .controllerFocusable(
                                id = "sidebar_home",
                                zone = ControllerFocusZone.Navigation,
                                isDefaultFocus = true,
                                onSelect = {},
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .controllerFocusable(
                                id = "bg_poster_initial",
                                zone = ControllerFocusZone.Content,
                                groupKey = "shelf_1",
                                onSelect = {},
                            ),
                    )
                    if (showLateContent) {
                        Box(
                            modifier = Modifier
                                .size(120.dp)
                                .controllerFocusable(
                                    id = "bg_poster_late",
                                    zone = ControllerFocusZone.Content,
                                    groupKey = "shelf_1",
                                    isDefaultFocus = true,
                                    onSelect = {},
                                ),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        // Explicitly enter navigation rail via Ctrl+B / Menu shortcut.
        assertTrue(ControllerManager.dispatchKeyboardShortcut(XboxButton.Menu))
        assertEquals("sidebar_home", ControllerFocusEngine.focusedNodeId.value)
        assertTrue(ControllerFocusEngine.isNavigationZoneExpanded.value)

        // A late-arriving default content node (e.g. catalog load or window resize) must not steal focus.
        showLateContent = true
        compose.waitForIdle()

        assertEquals("sidebar_home", ControllerFocusEngine.focusedNodeId.value)
    }

    @Test
    fun tab_in_mixed_bottom_sheet_clears_spatial_selection_so_enter_targets_compose_control() {
        var sheetOpen by mutableStateOf(true)
        var glowEnabled by mutableStateOf(false)
        var adaptiveClicks = 0

        compose.setContent {
            NuvioTheme {
                PlatformKeyboardNavigationHost()
                if (sheetOpen) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    NuvioModalBottomSheet(
                        onDismissRequest = { sheetOpen = false },
                        sheetState = sheetState,
                    ) {
                        NuvioBottomSheetActionRow(
                            title = "Adaptive",
                            focusId = "sheet_style_adaptive",
                            selected = true,
                            onClick = { adaptiveClicks += 1 },
                        )
                        NuvioBottomSheetActionRow(
                            title = "Floating",
                            focusId = "sheet_style_floating",
                            selected = false,
                            onClick = {},
                        )
                        Button(onClick = { glowEnabled = !glowEnabled }) {
                            Text(if (glowEnabled) "Glow On" else "Glow Off")
                        }
                        Button(onClick = { sheetOpen = false }) {
                            Text("Done")
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        // Spatially select the first action row as if the user entered the sheet with arrows/gamepad.
        ControllerManager.notifyNavigationInput()
        assertTrue(ControllerFocusEngine.focusFirstInGroup("bottom_sheet_actions"))
        assertEquals("sheet_style_adaptive", ControllerFocusEngine.focusedNodeId.value)

        // Press Tab via AWT dispatcher: must yield focus to Compose and clear the stale spatial row.
        val tabEvent = KeyEvent(
            Canvas(),
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            0,
            KeyEvent.VK_TAB,
            KeyEvent.CHAR_UNDEFINED,
        )
        KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(tabEvent)
        assertTrue(ControllerFocusEngine.isYieldedToCompose)
        assertNull(ControllerFocusEngine.focusedNodeId.value)

        // Pressing Enter via AWT dispatcher must NOT activate the stale spatial action row.
        val enterEvent = KeyEvent(
            Canvas(),
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            0,
            KeyEvent.VK_ENTER,
            KeyEvent.CHAR_UNDEFINED,
        )
        assertFalse(ControllerManager.dispatchKeyboardSelect())
        KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(enterEvent)
        assertEquals(0, adaptiveClicks)

        // Recomposing the sheet (e.g. after toggling Glow) must not steal focus back to spatial rows.
        glowEnabled = true
        compose.waitForIdle()
        assertTrue(ControllerFocusEngine.isYieldedToCompose)
        assertNull(ControllerFocusEngine.focusedNodeId.value)
        assertFalse(ControllerManager.dispatchKeyboardNavigation(ControllerDirection.Down))
        assertNull(ControllerFocusEngine.focusedNodeId.value)

        // Gamepad B still dismisses the sheet and resets the Compose handoff.
        assertTrue(ControllerFocusEngine.handleEvent(ControllerEvent.ButtonPressed(XboxButton.B)))
        compose.waitForIdle()
        assertFalse(sheetOpen)
        assertFalse(ControllerFocusEngine.isYieldedToCompose)
    }

    @Test
    fun arrow_down_past_last_sheet_action_row_hands_focus_to_compose_via_awt_dispatcher() {
        var sheetOpen by mutableStateOf(true)
        var floatingClicks = 0

        compose.setContent {
            NuvioTheme {
                PlatformKeyboardNavigationHost()
                if (sheetOpen) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    NuvioModalBottomSheet(
                        onDismissRequest = { sheetOpen = false },
                        sheetState = sheetState,
                    ) {
                        NuvioBottomSheetActionRow(
                            title = "Adaptive",
                            focusId = "sheet_row_a",
                            onClick = {},
                        )
                        NuvioBottomSheetActionRow(
                            title = "Floating",
                            focusId = "sheet_row_b",
                            onClick = { floatingClicks += 1 },
                        )
                        Button(onClick = { sheetOpen = false }) {
                            Text("Done")
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        // Focus the last spatial action row in the sheet.
        ControllerManager.notifyNavigationInput()
        assertTrue(ControllerFocusEngine.requestFocus("sheet_row_b", triggerScroll = false))
        assertEquals("sheet_row_b", ControllerFocusEngine.focusedNodeId.value)

        // Arrow Down past the last action row moves Compose focus into the sheet controls and clears spatial selection.
        val downEvent = KeyEvent(
            Canvas(),
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            0,
            KeyEvent.VK_DOWN,
            KeyEvent.CHAR_UNDEFINED,
        )
        assertTrue(KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(downEvent))
        compose.waitForIdle()

        assertTrue(ControllerFocusEngine.isYieldedToCompose)
        assertNull(ControllerFocusEngine.focusedNodeId.value)
        assertFalse(ControllerManager.dispatchKeyboardSelect())
        assertEquals(0, floatingClicks)
    }

    @Test
    fun desktop_dropdown_registers_its_own_popup_focus_manager_for_tab_handoff() {
        var outerFocusManager: FocusManager? = null

        compose.setContent {
            NuvioTheme {
                outerFocusManager = LocalFocusManager.current
                PlatformKeyboardNavigationHost()
                NuvioDropdownChip(
                    title = "Filter",
                    label = "All Types",
                    options = listOf(
                        NuvioDropdownOption("all", "All Types"),
                        NuvioDropdownOption("movie", "Movies"),
                        NuvioDropdownOption("series", "Series"),
                    ),
                    selectedKey = "all",
                    onSelected = {},
                )
            }
        }
        compose.waitForIdle()
        assertNull(ComposePopupFocus.active)

        compose.onNodeWithText("All Types").performClick()
        compose.waitForIdle()

        // The desktop dropdown popup must register its own FocusManager distinct from the page's.
        val menuFocusManager = ComposePopupFocus.active
        assertNotNull(menuFocusManager)
        assertNotNull(outerFocusManager)
        assertNotSame(outerFocusManager, menuFocusManager)

        // Tab yields spatial focus to the dropdown popup's FocusManager, never the page behind it.
        ControllerManager.notifyNavigationInput()
        assertTrue(ControllerFocusEngine.requestFocus("dropdown_item_0", triggerScroll = false))
        val tabEvent = KeyEvent(
            Canvas(),
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            0,
            KeyEvent.VK_TAB,
            KeyEvent.CHAR_UNDEFINED,
        )
        KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(tabEvent)
        assertTrue(ControllerFocusEngine.isYieldedToCompose)
        assertNull(ControllerFocusEngine.focusedNodeId.value)
        assertEquals(menuFocusManager, ComposePopupFocus.active)

        // Closing the dropdown clears both popup focus and Compose handoff.
        assertTrue(ControllerFocusEngine.handleEvent(ControllerEvent.ButtonPressed(XboxButton.B)))
        compose.waitForIdle()
        assertNull(ComposePopupFocus.active)
        assertFalse(ControllerFocusEngine.isModalOverlayActive)
        assertFalse(ControllerFocusEngine.isYieldedToCompose)
    }

    @Test
    fun dialog_above_bottom_sheet_scopes_overlay_focus_and_gamepad_back_to_dialog() {
        var sheetOpen by mutableStateOf(true)
        var dialogOpen by mutableStateOf(false)
        var adaptiveClicks = 0
        var sheetFocusManager: FocusManager? = null

        compose.setContent {
            NuvioTheme {
                if (sheetOpen) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    NuvioModalBottomSheet(
                        onDismissRequest = { sheetOpen = false },
                        sheetState = sheetState,
                    ) {
                        sheetFocusManager = LocalFocusManager.current
                        NuvioBottomSheetActionRow(
                            title = "Adaptive",
                            focusId = "stacked_sheet_adaptive",
                            onClick = { adaptiveClicks += 1 },
                        )
                        NuvioBottomSheetActionRow(
                            title = "Floating",
                            focusId = "stacked_sheet_floating",
                            onClick = {},
                        )
                    }
                }
                if (dialogOpen) {
                    DialogSurface(
                        onDismissRequest = { dialogOpen = false },
                        title = "Quit while downloading?",
                    ) {
                        Button(onClick = { dialogOpen = false }) { Text("Keep open") }
                    }
                }
            }
        }
        compose.waitForIdle()

        // Focus a row inside the open bottom sheet.
        ControllerManager.notifyNavigationInput()
        assertTrue(ControllerFocusEngine.focusFirstInGroup("bottom_sheet_actions"))
        assertEquals("stacked_sheet_adaptive", ControllerFocusEngine.focusedNodeId.value)
        assertNotNull(sheetFocusManager)

        // Open a confirmation dialog on top of the sheet (e.g. QuitWithDownloadsDialog).
        dialogOpen = true
        compose.waitForIdle()

        // The dialog takes top modal ownership: sheet selection is cleared and sheet rows are unreachable.
        assertNull(ControllerFocusEngine.focusedNodeId.value)
        val dialogFocusManager = ComposePopupFocus.active
        assertNotNull(dialogFocusManager)
        assertNotSame(sheetFocusManager, dialogFocusManager)
        assertFalse(ControllerFocusEngine.requestFocus("stacked_sheet_adaptive", triggerScroll = false))
        assertFalse(ControllerFocusEngine.focusFirstInGroup("bottom_sheet_actions"))
        assertFalse(ControllerManager.dispatchKeyboardNavigation(ControllerDirection.Down))
        assertFalse(ControllerManager.dispatchKeyboardSelect())
        assertFalse(ControllerFocusEngine.handleEvent(ControllerEvent.ButtonPressed(XboxButton.A)))
        assertEquals(0, adaptiveClicks)

        // Gamepad B dismisses ONLY the top dialog, keeping the underlying sheet open.
        assertTrue(ControllerFocusEngine.handleEvent(ControllerEvent.ButtonPressed(XboxButton.B)))
        compose.waitForIdle()
        assertFalse(dialogOpen)
        assertTrue(sheetOpen)
        assertTrue(ControllerFocusEngine.isModalOverlayActive)
        assertEquals(sheetFocusManager, ComposePopupFocus.active)

        // Now the sheet regains Overlay focus and the next Gamepad B dismisses the sheet.
        assertEquals("stacked_sheet_adaptive", ControllerFocusEngine.focusedNodeId.value)
        assertTrue(ControllerFocusEngine.focusFirstInGroup("bottom_sheet_actions"))
        assertEquals("stacked_sheet_adaptive", ControllerFocusEngine.focusedNodeId.value)
        assertTrue(ControllerFocusEngine.handleEvent(ControllerEvent.ButtonPressed(XboxButton.B)))
        compose.waitForIdle()
        assertFalse(sheetOpen)
        assertFalse(ControllerFocusEngine.isModalOverlayActive)
    }

    @Test
    fun nested_dialog_dismissal_preserves_underlying_sheet_compose_focus_ownership() {
        var sheetOpen by mutableStateOf(true)
        var dialogOpen by mutableStateOf(false)
        var adaptiveClicks = 0
        var floatingClicks = 0

        compose.setContent {
            NuvioTheme {
                PlatformKeyboardNavigationHost()
                if (sheetOpen) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    NuvioModalBottomSheet(
                        onDismissRequest = { sheetOpen = false },
                        sheetState = sheetState,
                    ) {
                        NuvioBottomSheetActionRow(
                            title = "Adaptive",
                            focusId = "nested_sheet_adaptive",
                            onClick = { adaptiveClicks += 1 },
                        )
                        NuvioBottomSheetActionRow(
                            title = "Floating",
                            focusId = "nested_sheet_floating",
                            onClick = { floatingClicks += 1 },
                        )
                        Button(onClick = {}) { Text("Glow") }
                        Button(onClick = { sheetOpen = false }) { Text("Done") }
                    }
                }
                if (dialogOpen) {
                    DialogSurface(
                        onDismissRequest = { dialogOpen = false },
                        title = "Quit while downloading?",
                    ) {
                        Button(onClick = { dialogOpen = false }) { Text("Keep open") }
                    }
                }
            }
        }
        compose.waitForIdle()

        // Start in spatial navigation on the sheet, then Tab to the Compose controls (Glow / Done).
        ControllerManager.notifyNavigationInput()
        assertTrue(ControllerFocusEngine.focusFirstInGroup("bottom_sheet_actions"))
        assertEquals("nested_sheet_adaptive", ControllerFocusEngine.focusedNodeId.value)

        val tabEvent = KeyEvent(
            Canvas(),
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            0,
            KeyEvent.VK_TAB,
            KeyEvent.CHAR_UNDEFINED,
        )
        KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(tabEvent)
        assertTrue(ControllerFocusEngine.isYieldedToCompose)
        assertNull(ControllerFocusEngine.focusedNodeId.value)
        assertTrue(ControllerManager.isNavigationActive.value)

        // Open quit confirmation dialog above the sheet, then dismiss it via Gamepad B.
        dialogOpen = true
        compose.waitForIdle()
        assertFalse(ControllerFocusEngine.isYieldedToCompose)
        assertNull(ControllerFocusEngine.focusedNodeId.value)

        assertTrue(ControllerFocusEngine.handleEvent(ControllerEvent.ButtonPressed(XboxButton.B)))
        compose.waitForIdle()
        assertFalse(dialogOpen)
        assertTrue(sheetOpen)

        // Underlying sheet must keep Compose focus ownership instead of re-selecting a spatial row.
        assertTrue(ControllerFocusEngine.isYieldedToCompose)
        assertNull(ControllerFocusEngine.focusedNodeId.value)
        assertFalse(ControllerManager.dispatchKeyboardSelect())
        val enterEvent = KeyEvent(
            Canvas(),
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            0,
            KeyEvent.VK_ENTER,
            KeyEvent.CHAR_UNDEFINED,
        )
        KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(enterEvent)
        assertEquals(0, adaptiveClicks)
        assertEquals(0, floatingClicks)

        // Re-open the dialog and dismiss via DesktopEscapeShortcut (Escape path): same guarantee.
        dialogOpen = true
        compose.waitForIdle()
        assertTrue(DesktopEscapeShortcut.dispatchBack())
        compose.waitForIdle()
        assertFalse(dialogOpen)
        assertTrue(sheetOpen)
        assertTrue(ControllerFocusEngine.isYieldedToCompose)
        assertNull(ControllerFocusEngine.focusedNodeId.value)
        assertFalse(ControllerManager.dispatchKeyboardSelect())
        assertEquals(0, adaptiveClicks)
        assertEquals(0, floatingClicks)

        // Dismissing the sheet restores base route state.
        assertTrue(ControllerFocusEngine.handleEvent(ControllerEvent.ButtonPressed(XboxButton.B)))
        compose.waitForIdle()
        assertFalse(sheetOpen)
        assertFalse(ControllerFocusEngine.isModalOverlayActive)
        assertFalse(ControllerFocusEngine.isYieldedToCompose)
    }

    @Test
    fun nested_dialog_dismissal_restores_underlying_sheet_spatial_row_after_tab_in_dialog() {
        var sheetOpen by mutableStateOf(true)
        var dialogOpen by mutableStateOf(false)

        compose.setContent {
            NuvioTheme {
                PlatformKeyboardNavigationHost()
                if (sheetOpen) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    NuvioModalBottomSheet(
                        onDismissRequest = { sheetOpen = false },
                        sheetState = sheetState,
                    ) {
                        NuvioBottomSheetActionRow(
                            title = "Adaptive",
                            focusId = "restore_sheet_row_1",
                            onClick = {},
                        )
                        NuvioBottomSheetActionRow(
                            title = "Floating",
                            focusId = "restore_sheet_row_2",
                            onClick = {},
                        )
                    }
                }
                if (dialogOpen) {
                    DialogSurface(
                        onDismissRequest = { dialogOpen = false },
                        title = "Quit while downloading?",
                    ) {
                        Button(onClick = { dialogOpen = false }) { Text("Keep open") }
                    }
                }
            }
        }
        compose.waitForIdle()

        // Spatially select the SECOND row in the sheet (not the default first row).
        ControllerManager.notifyNavigationInput()
        assertTrue(ControllerFocusEngine.requestFocus("restore_sheet_row_2", triggerScroll = false))
        assertEquals("restore_sheet_row_2", ControllerFocusEngine.focusedNodeId.value)

        // Open dialog on top and press Tab inside the dialog so the dialog yields to Compose.
        dialogOpen = true
        compose.waitForIdle()
        val tabEvent = KeyEvent(
            Canvas(),
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            0,
            KeyEvent.VK_TAB,
            KeyEvent.CHAR_UNDEFINED,
        )
        KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(tabEvent)
        assertTrue(ControllerFocusEngine.isYieldedToCompose)

        // Dismissing the dialog must restore the sheet's spatial mode and exact previously selected row.
        assertTrue(ControllerFocusEngine.handleEvent(ControllerEvent.ButtonPressed(XboxButton.B)))
        compose.waitForIdle()
        assertFalse(dialogOpen)
        assertTrue(sheetOpen)
        assertFalse(ControllerFocusEngine.isYieldedToCompose)
        assertEquals("restore_sheet_row_2", ControllerFocusEngine.focusedNodeId.value)

        // Clean up.
        assertTrue(ControllerFocusEngine.handleEvent(ControllerEvent.ButtonPressed(XboxButton.B)))
        compose.waitForIdle()
        assertFalse(sheetOpen)
    }
}
