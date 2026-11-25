/*
 * Ported from NuvioDeck (https://github.com/kllakm/NuvioDeck), GPLv3.
 */
package com.nuvio.app.controller

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nuvio.app.AppScreenTab
import com.nuvio.app.navigation.AppRoute
import com.nuvio.app.navigation.CatalogRoute
import com.nuvio.app.navigation.DetailRoute
import com.nuvio.app.navigation.EntityBrowseRoute
import com.nuvio.app.navigation.FolderDetailRoute
import com.nuvio.app.navigation.PersonDetailRoute
import com.nuvio.app.navigation.PlayerRoute
import com.nuvio.app.navigation.StreamRoute
import com.nuvio.app.navigation.TabsRoute

/**
 * Keeps the spatial focus engine in sync with navigation state and registers app-level actions.
 *
 * Mount once, inside the app's root composition, where the current route and the back handler are
 * available.
 */
@Composable
fun ControllerNavigationHost(
    currentRoute: AppRoute?,
    selectedTab: AppScreenTab,
    onBack: () -> Boolean,
    onBumperLeft: (() -> Boolean)? = null,
    onBumperRight: (() -> Boolean)? = null,
    onSearch: (() -> Boolean)? = null,
    onOpenNavigation: (() -> Boolean)? = null,
    fullPlayerOwnsInput: Boolean = false,
    miniDockClearance: Dp = 0.dp,
) {
    val routeIdentity = when (currentRoute) {
        is DetailRoute -> "DetailRoute:${currentRoute.type}:${currentRoute.id}"
        is CatalogRoute -> "CatalogRoute:${currentRoute.launchId}"
        is PersonDetailRoute -> "PersonDetailRoute:${currentRoute.personId}"
        is EntityBrowseRoute -> "EntityBrowseRoute:${currentRoute.entityId}"
        is FolderDetailRoute -> "FolderDetailRoute:${currentRoute.collectionId}:${currentRoute.folderId}"
        null -> ""
        else -> currentRoute::class.simpleName ?: ""
    }

    LaunchedEffect(currentRoute, selectedTab, fullPlayerOwnsInput, miniDockClearance) {
        val navigation = resolveControllerNavigation(
            currentRoute = currentRoute,
            selectedTab = selectedTab,
            fullPlayerOwnsInput = fullPlayerOwnsInput,
            miniDockClearance = miniDockClearance,
        )
        ControllerFocusEngine.isHomeTabActive =
            !fullPlayerOwnsInput && currentRoute is TabsRoute && selectedTab == AppScreenTab.Home
        ControllerFocusEngine.deferNavigationToCompose = navigation.deferNavigationToCompose
        ControllerFocusEngine.onRouteOrTabChanged("$routeIdentity:$selectedTab:$fullPlayerOwnsInput")
        if (navigation.deferNavigationToCompose) {
            // Compose owns focus on these routes; drop any spatial selection so the engine ring
            // does not stay frozen on a node while Compose focus moves (details rails looked
            // like they could not move left/right).
            ControllerFocusEngine.clearFocus()
        }
        ControllerManager.updateScreenContext(navigation.screenContext)
    }

    RegisterControllerHandler(
        key = "app_shell",
        onBack = onBack,
        onBumperLeft = onBumperLeft,
        onBumperRight = onBumperRight,
        onActionY = onSearch,
        onMenuButton = onOpenNavigation,
    )
}

/** Bottom-right controller hint toolbar; hidden while the full player owns the screen. */
@Composable
fun BoxScope.ControllerMainOverlay(
    currentRoute: AppRoute?,
    selectedTab: AppScreenTab = AppScreenTab.Home,
    fullPlayerOwnsInput: Boolean = false,
    miniDockClearance: Dp = 0.dp,
) {
    val navigation = resolveControllerNavigation(
        currentRoute = currentRoute,
        selectedTab = selectedTab,
        fullPlayerOwnsInput = fullPlayerOwnsInput,
        miniDockClearance = miniDockClearance,
    )
    if (!navigation.hideHintToolbar) {
        ControllerHintToolbar(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = navigation.hintBottomInset)
                .zIndex(100f),
        )
    }
}

internal data class ControllerNavigationState(
    val screenContext: ControllerScreenContext,
    val deferNavigationToCompose: Boolean,
    val hideHintToolbar: Boolean,
    val hintBottomInset: Dp,
)

/**
 * Keyboard and gamepad context for the screen the user is actually looking at.
 * A full player session owns input even though it is not a navigation route.
 * Minimized and picture-in-picture playback leave the route underneath in charge,
 * and lift the hint row clear of the dock.
 */
internal fun resolveControllerNavigation(
    currentRoute: AppRoute?,
    selectedTab: AppScreenTab,
    fullPlayerOwnsInput: Boolean,
    miniDockClearance: Dp = 0.dp,
): ControllerNavigationState {
    val routeDefersToCompose = when (currentRoute) {
        // Screens instrumented with controllerFocusable are driven by the spatial engine.
        is TabsRoute, is CatalogRoute, is FolderDetailRoute, is EntityBrowseRoute -> false
        // Details, streams, player and settings leaves rely on Compose focus instead.
        null -> false
        else -> true
    }
    val routeContext = when {
        currentRoute is PlayerRoute -> ControllerScreenContext(
            screenType = ControllerScreenType.Player,
            selectLabel = "Play / Pause",
            backLabel = "Exit Player",
        )
        currentRoute is DetailRoute -> ControllerScreenContext(
            screenType = ControllerScreenType.Details,
            selectLabel = "Select",
            backLabel = "Back",
            rightStickLabel = "Scroll",
        )
        currentRoute is StreamRoute -> ControllerScreenContext(
            screenType = ControllerScreenType.Streams,
            selectLabel = "Play Stream",
            backLabel = "Back",
        )
        currentRoute is FolderDetailRoute || currentRoute is CatalogRoute || currentRoute is EntityBrowseRoute ->
            ControllerScreenContext(
                screenType = ControllerScreenType.Catalog,
                selectLabel = "Open",
                backLabel = "Back",
                rightStickLabel = "Scroll",
            )
        currentRoute != null && currentRoute !is TabsRoute -> ControllerScreenContext(
            screenType = ControllerScreenType.Settings,
            selectLabel = "Select",
            backLabel = "Back",
            rightStickLabel = "Scroll",
        )
        selectedTab == AppScreenTab.Home -> ControllerScreenContext(
            screenType = ControllerScreenType.Home,
            selectLabel = "Open",
            backLabel = "Exit",
            rightStickLabel = "Scroll",
        )
        selectedTab == AppScreenTab.Search -> ControllerScreenContext(
            screenType = ControllerScreenType.Search,
            selectLabel = "Select",
            backLabel = "Home",
            rightStickLabel = "Scroll",
        )
        selectedTab == AppScreenTab.Library -> ControllerScreenContext(
            screenType = ControllerScreenType.Library,
            selectLabel = "Open",
            backLabel = "Home",
            rightStickLabel = "Scroll",
        )
        selectedTab == AppScreenTab.Settings -> ControllerScreenContext(
            screenType = ControllerScreenType.Settings,
            selectLabel = "Select",
            backLabel = "Home",
            rightStickLabel = "Scroll",
        )
        else -> ControllerScreenContext()
    }
    if (!fullPlayerOwnsInput) {
        return ControllerNavigationState(
            screenContext = routeContext,
            deferNavigationToCompose = routeDefersToCompose,
            hideHintToolbar = currentRoute is PlayerRoute,
            hintBottomInset = 20.dp + miniDockClearance,
        )
    }
    return ControllerNavigationState(
        screenContext = ControllerScreenContext(
            screenType = ControllerScreenType.Player,
            selectLabel = "Play / Pause",
            backLabel = "Exit Player",
        ),
        deferNavigationToCompose = true,
        hideHintToolbar = true,
        hintBottomInset = 20.dp + miniDockClearance,
    )
}
