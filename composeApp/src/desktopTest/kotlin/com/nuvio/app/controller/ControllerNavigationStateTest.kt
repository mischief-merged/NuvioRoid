package com.nuvio.app.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.nuvio.app.AppScreenTab
import com.nuvio.app.MiniPlayerVideoHeight
import com.nuvio.app.features.player.MiniPlayerControlsHeight
import com.nuvio.app.features.player.PlayerPresentationMode
import com.nuvio.app.fullPlayerOwnsControllerInput
import com.nuvio.app.miniPlayerHintClearance
import com.nuvio.app.navigation.CatalogRoute
import com.nuvio.app.navigation.DetailRoute
import com.nuvio.app.navigation.PlayerRoute
import com.nuvio.app.navigation.TabsRoute
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule

class ControllerNavigationStateTest {
    @get:Rule
    val compose = createComposeRule()

    @AfterTest
    fun resetNavigationState() {
        ControllerFocusEngine.deferNavigationToCompose = false
        ControllerFocusEngine.isHomeTabActive = false
        ControllerManager.updateScreenContext(ControllerScreenContext())
    }

    @Test
    fun full_player_owns_input_over_a_browsing_route_and_mini_gives_it_back() {
        val catalog = CatalogRoute(launchId = 4L, title = "Movies")
        val full = resolveControllerNavigation(
            currentRoute = catalog,
            selectedTab = AppScreenTab.Home,
            fullPlayerOwnsInput = true,
        )
        assertEquals(ControllerScreenType.Player, full.screenContext.screenType)
        assertTrue(full.deferNavigationToCompose)
        assertTrue(full.hideHintToolbar)

        val browsing = resolveControllerNavigation(
            currentRoute = catalog,
            selectedTab = AppScreenTab.Home,
            fullPlayerOwnsInput = false,
        )
        assertEquals(ControllerScreenType.Catalog, browsing.screenContext.screenType)
        assertFalse(browsing.deferNavigationToCompose)
        assertFalse(browsing.hideHintToolbar)
        assertEquals(20.dp, browsing.hintBottomInset)
    }

    @Test
    fun minimized_playback_lifts_the_hint_row_above_the_dock() {
        val clearance = miniPlayerHintClearance(PlayerPresentationMode.Mini)
        assertEquals(MiniPlayerVideoHeight + MiniPlayerControlsHeight + 12.dp, clearance)
        assertEquals(0.dp, miniPlayerHintClearance(PlayerPresentationMode.Full))
        assertEquals(0.dp, miniPlayerHintClearance(PlayerPresentationMode.PictureInPicture))
        assertEquals(0.dp, miniPlayerHintClearance(null))
        assertTrue(fullPlayerOwnsControllerInput(PlayerPresentationMode.Full))
        assertFalse(fullPlayerOwnsControllerInput(PlayerPresentationMode.Mini))
        assertFalse(fullPlayerOwnsControllerInput(PlayerPresentationMode.PictureInPicture))

        val mini = resolveControllerNavigation(
            currentRoute = DetailRoute(type = "series", id = "tt1", title = "Series"),
            selectedTab = AppScreenTab.Home,
            fullPlayerOwnsInput = false,
            miniDockClearance = clearance,
        )
        assertEquals(ControllerScreenType.Details, mini.screenContext.screenType)
        assertTrue(mini.deferNavigationToCompose)
        assertFalse(mini.hideHintToolbar)
        assertEquals(20.dp + clearance, mini.hintBottomInset)
    }

    @Test
    fun a_player_route_still_hides_hints_when_no_session_is_active() {
        val player = resolveControllerNavigation(
            currentRoute = PlayerRoute(launchId = 9L, title = "Player"),
            selectedTab = AppScreenTab.Home,
            fullPlayerOwnsInput = false,
        )
        assertEquals(ControllerScreenType.Player, player.screenContext.screenType)
        assertTrue(player.hideHintToolbar)
        assertTrue(player.deferNavigationToCompose)
    }

    @Test
    fun the_navigation_host_publishes_player_context_only_while_the_full_player_is_up() {
        var fullPlayer by mutableStateOf(true)
        compose.setContent {
            ControllerNavigationHost(
                currentRoute = TabsRoute,
                selectedTab = AppScreenTab.Home,
                onBack = { true },
                fullPlayerOwnsInput = fullPlayer,
                miniDockClearance = if (fullPlayer) 0.dp else 40.dp,
            )
        }
        compose.waitForIdle()
        assertEquals(ControllerScreenType.Player, ControllerManager.screenContext.value.screenType)
        assertTrue(ControllerFocusEngine.deferNavigationToCompose)
        assertFalse(ControllerFocusEngine.isHomeTabActive)

        fullPlayer = false
        compose.waitForIdle()
        assertEquals(ControllerScreenType.Home, ControllerManager.screenContext.value.screenType)
        assertFalse(ControllerFocusEngine.deferNavigationToCompose)
        assertTrue(ControllerFocusEngine.isHomeTabActive)
    }
}
