package com.nuvio.app

import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.app_already_open
import com.nuvio.app.features.autosync.AutoSyncPreferencesDesktop
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.SwingWindow
import androidx.compose.ui.configureSwingGlobalsForCompose
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.nuvio.app.features.downloads.flushDownloadProgressOnExit
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.deeplink.handleAppUrl
import com.nuvio.app.core.diagnostics.SentryInitializer
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.ProvideDesktopWindowInsets
import com.nuvio.app.core.ui.platformTouchScroll
import com.nuvio.app.features.discordrpc.DiscordPresenceManager
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadTraffic
import com.nuvio.app.features.downloads.DownloadRechecks
import com.nuvio.app.features.downloads.SourceReplacements
import com.nuvio.app.features.downloads.MacDownloadPowerNative
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.plugins.configureDesktopQuickJsLibrary
import com.nuvio.app.features.player.PlatformPlayerSurface
import com.nuvio.app.features.player.desktop.DesktopAppFullscreenController
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.DesktopWindowGeometry
import com.nuvio.app.features.player.desktop.DesktopWindowModeStorage
import com.nuvio.app.features.player.desktop.WindowGeometryWrites
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import com.nuvio.app.features.player.desktop.applyNativeDesktopWindowChrome
import com.nuvio.app.features.player.desktop.configureMacosWindowBeforePeer
import com.nuvio.app.features.player.desktop.installDesktopAppFullscreenShortcuts
import com.nuvio.app.features.player.desktop.macosTitleBarDoubleClick
import com.nuvio.app.features.player.desktop.MacosAppMenuBar
import com.nuvio.app.features.player.desktop.preloadNativePlayerBridgeAsync
import com.nuvio.app.features.player.desktop.registerDesktopAppFullscreenToggle
import com.nuvio.app.features.player.desktop.trackMaximizedBoundsForCurrentScreen
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.settings.AppIconRepository
import com.nuvio.app.features.settings.BackgroundDownloadPreferences
import com.nuvio.app.features.settings.applyDesktopRendererPreference
import com.nuvio.app.features.settings.transparentPreviewResource
import java.awt.Desktop
import javax.imageio.ImageIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.awt.Color as AwtColor
import javax.swing.JComponent
import javax.swing.SwingUtilities
import java.awt.desktop.AppReopenedListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.downloads_sleep_reason
import org.jetbrains.compose.resources.stringResource

private val NuvioDesktopNativeBackground = AwtColor(0x0D, 0x0D, 0x0D)
private const val MacosDarkAquaAppearance = "NSAppearanceNameDarkAqua"

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    // One copy of the app per app data folder: a second copy says so and stops before touching anything.
    if (!com.nuvio.app.core.storage.DesktopSingleInstance.acquireForApp(com.nuvio.app.core.storage.DesktopStorage.rootDir)) {
        showAlreadyOpen()
        return
    }
    // On Linux, initialize GTK BEFORE AWT/Compose/Skia to prevent GdkDisplayManager
    // type registration conflict (Skiko partially loads GDK without full GTK init).
    if (System.getProperty("os.name", "").lowercase().contains("linux")) {
        runCatching { NativePlayerBridge.initGtkEarly() }
    }
    applyDesktopRendererPreference()
    SentryInitializer.start()
    configureDesktopQuickJsLibrary()
    configureDesktopChrome()
    configureLinuxSwingGlobalsBeforeAwt()
    installDesktopOpenUriHandler()
    handleDesktopLaunchArgs(args)
    preloadNativePlayerBridgeAsync()
    // Load cached profile data synchronously so the profile color is available
    // on the very first Compose frame (matching Android's SharedPreferences behavior).
    ProfileRepository.loadCachedProfiles()
    // Starts settling a cut-short folder move on a background thread. The downloads list is read
    // there, after that finishes, so neither one runs on the interface thread.
    runCatching { com.nuvio.app.features.downloads.DesktopDownloads.install() }
    // Watches for hotspots and Low Data Mode networks, on which downloads wait (when the setting is on).
    runCatching { com.nuvio.app.features.downloads.DesktopLimitedConnections.install() }
    // Downloads leave the connection to other apps, and wait on a very slow network or one that blocks them.
    runCatching { com.nuvio.app.features.downloads.DesktopPoliteDownloads.install() }
    AppIconRepository.ensureLoaded()
    AutoSyncPreferencesDesktop.initialize()
    DiscordPresenceManager.start()
    Runtime.getRuntime().addShutdownHook(Thread({ flushDownloadProgressOnExit() }, "download-exit-checkpoint"))

    application {
        DisposableEffect(Unit) {
            val watchdog = com.nuvio.app.core.memory.HeapWatchdog()
            onDispose { watchdog.close() }
        }
        val appIconState by AppIconRepository.state.collectAsState()
        val smokePlayerUrl = (
            System.getProperty("nuvio.desktop.smokePlayerUrl")
                ?: System.getenv("NUVIO_DESKTOP_SMOKE_PLAYER_URL")
            )
            ?.takeIf { it.isNotBlank() }
        val wasFullscreenOnLastExit = remember { DesktopWindowModeStorage.loadWasFullscreen() }
        val wasMaximizedOnLastExit = remember { DesktopWindowModeStorage.loadWasMaximized() }
        val savedGeometry = remember { DesktopWindowModeStorage.loadWindowedGeometry() }
        val restoresMaximizedWindowPlacement = DesktopHostOs.current != DesktopHostOs.MACOS
        val initialPlacement = when {
            wasFullscreenOnLastExit && DesktopHostOs.current != DesktopHostOs.WINDOWS -> {
                WindowPlacement.Fullscreen
            }
            wasMaximizedOnLastExit == false && savedGeometry != null -> {
                WindowPlacement.Floating
            }
            restoresMaximizedWindowPlacement -> {
                WindowPlacement.Maximized
            }
            else -> WindowPlacement.Floating
        }
        val isStartingMaximizedOrFullscreen =
            initialPlacement == WindowPlacement.Maximized || initialPlacement == WindowPlacement.Fullscreen
        val maxScreenBounds = remember {
            runCatching {
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            }.getOrNull()
        }
        val initialWidth = when {
            isStartingMaximizedOrFullscreen && maxScreenBounds != null -> maxScreenBounds.width.dp
            savedGeometry != null -> savedGeometry.width.dp
            else -> 1280.dp
        }
        val initialHeight = when {
            isStartingMaximizedOrFullscreen && maxScreenBounds != null -> maxScreenBounds.height.dp
            savedGeometry != null -> savedGeometry.height.dp
            else -> 820.dp
        }
        val windowState = rememberWindowState(
            width = initialWidth,
            height = initialHeight,
            position = savedGeometry?.let { WindowPosition.Absolute(x = it.x.dp, y = it.y.dp) }
                ?: WindowPosition.PlatformDefault,
            // Windows fullscreen is emulated natively (see DesktopAppFullscreenController)
            // rather than driven by WindowPlacement, so it's restored separately below.
            placement = initialPlacement,
        )
        val fullscreenController = remember { DesktopAppFullscreenController() }
        // One save for position and size, after the window has stopped moving.
        val geometryWrites = remember {
            WindowGeometryWrites { maximized, geometry ->
                DesktopWindowModeStorage.saveSnapshot(maximized, geometry)
            }
        }
        val isMac = DesktopHostOs.current == DesktopHostOs.MACOS
        val trayProgress by remember {
            DownloadsRepository.uiState
                .map { downloadTrayProgress(it.items) }
                .distinctUntilChanged()
        }.collectAsState(remember { downloadTrayProgress(DownloadsRepository.uiState.value.items) })
        val keepAfterClose by BackgroundDownloadPreferences.keepAfterClose.collectAsState()
        val rechecking by DownloadRechecks.app.isRunning.collectAsState()
        val replacementSearches by SourceReplacements.progress.collectAsState()
        val scope = rememberCoroutineScope()
        var windowVisible by remember { mutableStateOf(true) }
        var mainWindow by remember { mutableStateOf<androidx.compose.ui.awt.ComposeWindow?>(null) }
        val showWindow = {
            windowVisible = true
            windowState.isMinimized = false
            SwingUtilities.invokeLater {
                mainWindow?.let { it.isVisible = true; it.toFront(); it.requestFocus() }
            }
        }
        val sleepReason = stringResource(Res.string.downloads_sleep_reason)
        val sleepGuard = remember(isMac, sleepReason) {
            if (isMac) runCatching { MacDownloadPowerNative.guard(sleepReason) }
                .onFailure { System.err.println("Download sleep prevention could not start: ${it.message}") }
                .getOrNull() else null
        }
        LaunchedEffect(sleepGuard) {
            val guard = sleepGuard ?: return@LaunchedEffect
            withContext(Dispatchers.IO) {
                coroutineScope {
                    launch { DownloadTraffic.app.activeCount.collect { guard.update(it) } }
                    // Checks battery state if notifications are unavailable.
                    while (true) {
                        delay(5_000)
                        guard.update(DownloadTraffic.app.activeCount.value)
                    }
                }
            }
        }
        DisposableEffect(sleepGuard) {
            onDispose { sleepGuard?.close() }
        }
        var quitting by remember { mutableStateOf(false) }
        val closeApplication: () -> Unit = {
            if (!quitting) {
                quitting = true
                scope.launch {
                    DownloadsRepository.awaitFinishingDownloads()
                    withContext(Dispatchers.IO) { flushDownloadProgressOnExit() }
                    geometryWrites.flushNow()
                    sleepGuard?.close()
                    P2pStreamingEngine.shutdown()
                    DiscordPresenceManager.shutdown()
                    SentryInitializer.close()
                    exitApplication()
                }
            }
        }
        // The number of running downloads the user is being asked about, or null when no question is open.
        var askedAboutDownloads by remember { mutableStateOf<Int?>(null) }
        val requestExit: (Boolean) -> Unit = requestExit@{ explicitQuit ->
            if (quitting) return@requestExit
            val running = activeDownloadCount(DownloadsRepository.uiState.value.items)
            when (downloadCloseDecision(
                running = running,
                keepAfterClose = keepAfterClose,
                isMac = isMac,
                explicitQuit = explicitQuit,
                backgroundWork = rechecking || replacementSearches.isNotEmpty(),
            )) {
                DownloadCloseDecision.QuitNow -> closeApplication()
                DownloadCloseDecision.HideWindow -> windowVisible = false
                DownloadCloseDecision.AskToQuit -> {
                    showWindow()
                    askedAboutDownloads = running
                }
            }
        }
        val requestClose = { requestExit(false) }
        val requestQuit = { requestExit(true) }
        val currentShow by rememberUpdatedState(showWindow)
        val currentQuit by rememberUpdatedState(requestQuit)
        DisposableEffect(isMac) {
            val desktop = if (isMac && Desktop.isDesktopSupported()) Desktop.getDesktop() else null
            val reopen = AppReopenedListener { SwingUtilities.invokeLater { currentShow() } }
            desktop?.addAppEventListener(reopen)
            if (desktop?.isSupported(Desktop.Action.APP_QUIT_HANDLER) == true) {
                desktop.setQuitHandler { _, response ->
                    response.cancelQuit()
                    SwingUtilities.invokeLater { currentQuit() }
                }
            }
            onDispose {
                desktop?.removeAppEventListener(reopen)
                if (desktop?.isSupported(Desktop.Action.APP_QUIT_HANDLER) == true) desktop.setQuitHandler(null)
            }
        }

        if (isMac && (trayProgress.running > 0 || trayProgress.paused > 0 || !windowVisible)) {
            DesktopDownloadTray(
                progress = trayProgress,
                onShow = showWindow,
                onPause = { scope.launch(Dispatchers.IO) { DownloadsRepository.pauseActiveDownloads() }; Unit },
                onResume = {
                    scope.launch(Dispatchers.IO) {
                        DownloadsRepository.uiState.value.items.filter { it.status == DownloadStatus.Paused }
                            .forEach { DownloadsRepository.resumeDownload(it.id) }
                    }
                    Unit
                },
                onQuit = requestQuit,
            )
        }

        SwingWindow(
            onCloseRequest = requestClose,
            visible = windowVisible,
            title = if (smokePlayerUrl == null) "Nuvio" else "Nuvio Player Smoke",
            state = windowState,
            icon = painterResource(appIconState.selected.transparentPreviewResource),
            init = ::configureMacosWindowBeforePeer,
        ) {
            MacosAppMenuBar(
                onCloseRequest = requestClose,
                onMinimizeRequest = { windowState.isMinimized = true },
            )
            SideEffect {
                mainWindow = window
                window.background = NuvioDesktopNativeBackground
                window.rootPane.background = NuvioDesktopNativeBackground
                window.contentPane.background = NuvioDesktopNativeBackground
                (window.contentPane as? JComponent)?.isOpaque = true
            }
            LaunchedEffect(window, appIconState.selected) {
                val backgroundSuffix = "-transparent"
                val iconPath = "icons/app-icon-${appIconState.selected.key}$backgroundSuffix.png"
                Thread.currentThread().contextClassLoader.getResourceAsStream(iconPath)?.use { stream ->
                    ImageIO.read(stream)?.let { image ->
                        window.iconImages = listOf(image)
                    }
                }
            }

            LaunchedEffect(window) {
                applyNativeDesktopWindowChrome(window)
                installLinuxExtendedMouseButtons()
                installWindowsTouchScroll()
                // Windows fullscreen is emulated natively and isn't reflected by
                // WindowPlacement, so it must be re-applied once the window peer exists.
                fullscreenController.applyRestoredFullscreenState(window, windowState, wasFullscreenOnLastExit)
            }
            LaunchedEffect(windowState) {
                // Covers OS-driven placement changes too (e.g. the native macOS
                // green-button fullscreen toggle), not just our own shortcuts.
                if (DesktopHostOs.current != DesktopHostOs.WINDOWS) {
                    snapshotFlow { windowState.placement }
                        .collect { placement ->
                            DesktopWindowModeStorage.saveWasFullscreen(placement == WindowPlacement.Fullscreen)
                        }
                }
            }
            LaunchedEffect(windowState) {
                // Only persist geometry while windowed: fullscreen/native-Windows-fullscreen
                // coordinates aren't a meaningful "windowed position" to restore later.
                // The save waits until the window has stayed still, so a drag writes once.
                snapshotFlow { Triple(windowState.placement, windowState.position, windowState.size) }
                    .collect { (placement, position, size) ->
                        val isFullscreen = fullscreenController.isFullscreen(window, windowState)
                        val maximized = if (!isFullscreen && restoresMaximizedWindowPlacement) {
                            placement == WindowPlacement.Maximized
                        } else {
                            null
                        }
                        val isWindowed = placement == WindowPlacement.Floating && !isFullscreen
                        val geometry = if (isWindowed && position.isSpecified) {
                            DesktopWindowGeometry(
                                x = position.x.value,
                                y = position.y.value,
                                width = size.width.value,
                                height = size.height.value,
                            )
                        } else {
                            null
                        }
                        if (maximized != null || geometry != null) {
                            geometryWrites.note(maximized, geometry)
                        }
                    }
            }
            LaunchedEffect(geometryWrites) {
                // Wakes only while a position is waiting, then waits until the window has stayed still.
                // A failed save is tried again after the same quiet interval. Closing still writes once.
                while (isActive) {
                    geometryWrites.wakeups().receive()
                    while (isActive) {
                        val wait = geometryWrites.millisUntilDue() ?: break
                        if (wait > 0L) {
                            delay(wait)
                            continue
                        }
                        if (geometryWrites.flushIfDue()) break
                        delay(geometryWrites.quietMs)
                    }
                }
            }
            DisposableEffect(geometryWrites) {
                onDispose { geometryWrites.flushNow() }
            }
            DisposableEffect(window, windowState) {
                val unregisterFullscreenToggle = registerDesktopAppFullscreenToggle(
                    handler = { targetWindow ->
                        if (targetWindow == null || targetWindow === window) {
                            fullscreenController.toggle(window, windowState)
                            DesktopWindowModeStorage.saveWasFullscreen(
                                fullscreenController.isFullscreen(window, windowState),
                            )
                        }
                    },
                    isFullscreen = { targetWindow ->
                        (targetWindow == null || targetWindow === window) &&
                            fullscreenController.isFullscreen(window, windowState)
                    },
                )
                val uninstallFullscreenShortcuts = installDesktopAppFullscreenShortcuts(window)
                val untrackMaximizedBounds = window.trackMaximizedBoundsForCurrentScreen()
                onDispose {
                    fullscreenController.dispose(window)
                    uninstallFullscreenShortcuts()
                    untrackMaximizedBounds()
                    unregisterFullscreenToggle()
                }
            }

            if (smokePlayerUrl == null) {
                ClickableHandCursor(window)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .platformTouchScroll()
                        .macosTitleBarDoubleClick(window = window, windowState = windowState),
                ) {
                    ProvideDesktopWindowInsets(
                        isFullscreen = windowState.placement == WindowPlacement.Fullscreen,
                    ) {
                        MiddleClickAutoscroll { App() }
                    }
                    askedAboutDownloads?.let { running ->
                        AppEnvironment {
                            QuitWithDownloadsDialog(
                                activeDownloads = running,
                                onQuit = closeApplication,
                                onKeepOpen = { askedAboutDownloads = null },
                            )
                        }
                    }
                }
            } else {
                // The player surface reads LocalNuvioPlatformDensity, which only
                // NuvioTheme provides — the bare smoke harness must supply it too.
                NuvioTheme {
                    PlatformPlayerSurface(
                        sourceUrl = smokePlayerUrl,
                        modifier = Modifier.fillMaxSize(),
                        onControllerReady = {},
                        onSnapshot = {},
                        onError = {},
                    )
                }
            }
        }
    }
}

private fun configureDesktopChrome() {
    if (System.getProperty("os.name").contains("mac", ignoreCase = true)) {
        System.setProperty("apple.awt.application.appearance", MacosDarkAquaAppearance)
        // AWT only installs a window's JMenuBar in the macOS screen menu bar when this is set
        // before AWT starts; otherwise it would be drawn inside our full-window-content window.
        System.setProperty("apple.laf.useScreenMenuBar", "true")
    }
}

// application {} applies Compose's Swing globals, which on Linux include Skiko's
// display-scale detection (it sets sun.java2d.uiScale). AWT reads that property
// once, when its graphics environment starts, and installDesktopOpenUriHandler()
// starts it before application {} runs, which left the UI at 1x on HiDPI Linux
// desktops (#514). Apply the globals before anything touches AWT.
@OptIn(ExperimentalComposeUiApi::class)
private fun configureLinuxSwingGlobalsBeforeAwt() {
    if (DesktopHostOs.current != DesktopHostOs.LINUX) return
    if (System.getProperty("compose.application.configure.swing.globals") != "true") return
    // Skiko's detection overwrites sun.java2d.uiScale, so keep an explicitly
    // set scale (the documented #514 workaround) working by skipping it.
    configureSwingGlobalsForCompose(
        useAutoDpiOnLinux = System.getProperty("sun.java2d.uiScale") == null &&
            System.getProperty("skiko.linux.autodpi", "true") == "true",
    )
}

private fun installDesktopOpenUriHandler() {
    if (!Desktop.isDesktopSupported()) return
    val desktop = runCatching { Desktop.getDesktop() }.getOrNull() ?: return
    if (!desktop.isSupported(Desktop.Action.APP_OPEN_URI)) return

    runCatching {
        desktop.setOpenURIHandler { event ->
            event.uri
                ?.toString()
                ?.trim()
                ?.takeIf(::isDesktopAppUrl)
                ?.let(::handleAppUrl)
        }
    }
}

private fun handleDesktopLaunchArgs(args: Array<String>) {
    args.asSequence()
        .map(String::trim)
        .filter(::isDesktopAppUrl)
        .forEach(::handleAppUrl)
}

private fun isDesktopAppUrl(value: String): Boolean =
    value.startsWith("nuvio://", ignoreCase = true) ||
        value.startsWith("stremio://", ignoreCase = true)

/** The plain message a second copy of the app shows before it stops. Nothing of the first copy is touched. */
private fun showAlreadyOpen() {
    val message = runCatching {
        kotlinx.coroutines.runBlocking { org.jetbrains.compose.resources.getString(Res.string.app_already_open) }
    }.getOrDefault("NuvioRoid is already open.")
    runCatching { javax.swing.JOptionPane.showMessageDialog(null, message, "NuvioRoid", javax.swing.JOptionPane.INFORMATION_MESSAGE) }
        .onFailure { System.err.println(message) }
}
