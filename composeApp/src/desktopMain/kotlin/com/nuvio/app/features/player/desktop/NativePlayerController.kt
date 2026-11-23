package com.nuvio.app.features.player.desktop

import nuvio.composeapp.generated.resources.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.PlayerControlAddonSubtitleItem
import com.nuvio.app.features.player.PlayerControlEpisodeItem
import com.nuvio.app.features.player.PlayerControlFilterItem
import com.nuvio.app.features.player.PlayerControlPartyState
import com.nuvio.app.features.player.PlayerControlSeasonItem
import com.nuvio.app.features.player.PlayerControlShortcutItem
import com.nuvio.app.features.player.PlayerControlSourceItem
import com.nuvio.app.features.player.PlayerControlSubtitleCueItem
import com.nuvio.app.features.player.PlayerControlSubtitleLanguageItem
import com.nuvio.app.features.player.PlayerControlSubtitleOptionItem
import com.nuvio.app.features.player.AudioTrack
import com.nuvio.app.features.player.ParentalWarning
import com.nuvio.app.features.player.PlayerControlsAction
import com.nuvio.app.features.player.PlayerControlsState
import com.nuvio.app.features.player.PlayerChapter
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerMediaInfo
import com.nuvio.app.features.player.mpvMediaInfo
import com.nuvio.app.features.player.PlayerNowPlayingInfo
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.player.SUBTITLE_DELAY_MAX_MS
import com.nuvio.app.features.player.findPreferredAudioTrackIndex
import com.nuvio.app.features.player.SUBTITLE_DELAY_MIN_MS
import com.nuvio.app.features.player.StartupSubtitleSelection
import com.nuvio.app.features.player.SubtitleColorSwatches
import com.nuvio.app.features.player.SubtitleFontResolver
import com.nuvio.app.features.player.SubtitleLocalFilePlatform
import com.nuvio.app.features.player.SubtitleOutlineColorSwatches
import com.nuvio.app.features.player.SubtitleStartupCoordinator
import com.nuvio.app.features.player.SubtitleStyleState
import com.nuvio.app.features.player.SubtitleTrack
import com.nuvio.app.features.player.inferForcedSubtitleTrack
import com.nuvio.app.features.player.isLocalSubtitleUri
import com.nuvio.app.features.player.toStorageHexString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.awt.Component
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import kotlin.concurrent.Volatile
import kotlin.math.roundToInt

/**
 * Player-basics tests inject seek and controls lambdas. Production passes none,
 * so the real backend is used unchanged.
 */
private fun NativePlayerBackend.withTestCommandOverrides(
    seekByOverride: ((Long, Long) -> Unit)?,
    updateControlsOverride: ((Long, String) -> Unit)?,
): NativePlayerBackend {
    if (seekByOverride == null && updateControlsOverride == null) return this
    val delegate = this
    return object : NativePlayerBackend by delegate {
        override fun seekBy(handle: Long, offsetMs: Long) {
            val seek = seekByOverride
            if (seek != null) seek(handle, offsetMs) else delegate.seekBy(handle, offsetMs)
        }

        override fun updateControls(handle: Long, controlsJson: String) {
            val update = updateControlsOverride
            if (update != null) update(handle, controlsJson) else delegate.updateControls(handle, controlsJson)
        }
    }
}

internal typealias NativePlayerCreate = (
    Long,
    String,
    Array<String>,
    Boolean,
    Long,
    String,
    Int,
    Boolean,
    Boolean,
    NativePlayerEventSink,
) -> Long

internal interface NativeSubtitleBridgeOps {
    fun configureStartupSubtitles(
        handle: Long,
        urls: Array<String>,
        titles: Array<String>,
        languages: Array<String>,
        selectedIndex: Int,
        disableAutoSelect: Boolean,
    )
    fun addSubtitleTrack(
        handle: Long,
        url: String,
        title: String,
        language: String,
        select: Boolean,
    )
    fun clearExternalSubtitles(handle: Long)
    fun clearExternalSubtitlesAndSelect(handle: Long, trackId: Int)
    fun selectSubtitleTrack(handle: Long, trackId: Int)
    fun subtitleTracksJson(handle: Long): String
}

/** The subtitle calls the controller makes, sent through the same backend as every other native call. */
internal class BackendSubtitleBridgeOps(private val backend: NativePlayerBackend) : NativeSubtitleBridgeOps {
    override fun configureStartupSubtitles(
        handle: Long,
        urls: Array<String>,
        titles: Array<String>,
        languages: Array<String>,
        selectedIndex: Int,
        disableAutoSelect: Boolean,
    ) = backend.configureStartupSubtitles(handle, urls, titles, languages, selectedIndex, disableAutoSelect)

    override fun addSubtitleTrack(handle: Long, url: String, title: String, language: String, select: Boolean) =
        backend.addSubtitleTrack(handle, url, title, language, select)

    override fun clearExternalSubtitles(handle: Long) = backend.clearExternalSubtitles(handle)
    override fun clearExternalSubtitlesAndSelect(handle: Long, trackId: Int) =
        backend.clearExternalSubtitlesAndSelect(handle, trackId)
    override fun selectSubtitleTrack(handle: Long, trackId: Int) = backend.selectSubtitleTrack(handle, trackId)
    override fun subtitleTracksJson(handle: Long): String = backend.subtitleTracksJson(handle)
}

internal class NativePlayerController(
    private val host: NativePlayerHost,
    backend: NativePlayerBackend = RealNativePlayerBackend,
    nativeCreate: NativePlayerCreate? = null,
    nativeDispose: ((Long) -> Unit)? = null,
    nativeSeekTo: ((Long, Long) -> Unit)? = null,
    nativeSeekBy: ((Long, Long) -> Unit)? = null,
    nativeUpdateControls: ((Long, String) -> Unit)? = null,
    private val isHostDisplayable: () -> Boolean = { host.isDisplayable },
    private val resolveHostView: () -> Long = { AwtNativeViewResolver.resolveNativeViewPointer(host) },
    private val isComponentDisplayable: (Component) -> Boolean = { component -> component.isDisplayable },
    private val resolveComponentView: (Component) -> Long = { component ->
        AwtNativeViewResolver.resolveNativeViewPointer(component)
    },
    private val createWaitTimeoutMs: Long = 5_000L,
    private val releaseTimeoutMs: Long = 10_000L,
    private val onCreateWaitCompleted: () -> Unit = {},
    private val hideTimerNowMs: () -> Long = System::currentTimeMillis,
    private val hideTimerScheduler: DesktopTimerScheduler = SwingDesktopTimerScheduler,
    private val setHostControlsVisible: (Boolean) -> Unit = host::setControlsVisible,
    private val networkIdentity: () -> String = NativePlayerBridge::playbackNetworkIdentity,
    private val nativeRetryUnverified: (Long, String) -> Boolean = NativePlayerBridge::retryUnverified,
    private val rememberPlaybackNetwork: (String) -> Unit = PlaybackNetworkTrust.choices::remember,
    private val confirmUnverified: () -> Boolean = {
        val labels = kotlinx.coroutines.runBlocking {
            listOf(Res.string.playback_tls_title, Res.string.playback_tls_message, Res.string.playback_tls_stop, Res.string.playback_tls_anyway)
                .map { org.jetbrains.compose.resources.getString(it) }
        }
        javax.swing.JOptionPane.showOptionDialog(host, labels[1], labels[0],
            javax.swing.JOptionPane.DEFAULT_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE, null,
            arrayOf(labels[2], labels[3]), labels[2]) == 1
    },
    private val lifecycleCoordinator: NativePlayerLifecycleCoordinator? = null,
    remoteCommandsEnabledByDefault: Boolean = true,
    private val resolvePlayableLocalSubtitle: suspend (
        url: String,
        headers: Map<String, String>,
        languageHint: String?,
    ) -> String? = SubtitleStartupCoordinator::resolvePlayableLocalSubtitle,
    private val resolveLocalFilePath: (String) -> String? = SubtitleLocalFilePlatform::resolveLocalFilePath,
    subtitleBridgeOps: NativeSubtitleBridgeOps? = null,
) : PlayerEngineController {
    private val usesInjectedCreateStub = nativeCreate != null && backend === RealNativePlayerBackend
    private val backend: NativePlayerBackend =
        (if (usesInjectedCreateStub) NoOpNativePlayerBackend else backend)
            .withTestCommandOverrides(nativeSeekBy, nativeUpdateControls)
    private val subtitleOps: NativeSubtitleBridgeOps = subtitleBridgeOps ?: BackendSubtitleBridgeOps(this.backend)
    private val nativeCreateOverride: NativePlayerCreate? = nativeCreate
    private val nativeDispose: (Long) -> Unit = nativeDispose ?: this.backend::dispose
    private val nativeSeekTo: (Long, Long) -> Unit = nativeSeekTo ?: this.backend::seekTo
    private val controlsPageUrlProvider: () -> String = this.backend::controlsPageUrl
    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val log = Logger.withTag("NativePlayerControls")

        /** Cap on waiting for the previous player's teardown so a hung one cannot block playback. */
        const val TEARDOWN_WAIT_MS = 5_000L

        @Volatile
        var rememberedVolumeLevel: Float = DesktopPlayerVolumeStorage.loadVolumeLevel() ?: 1f

        @Volatile
        var rememberedResizeMode: PlayerResizeMode = PlayerResizeMode.Fit
    }

    private data class ReleaseCallback(
        val onReleased: () -> Unit,
        val onFailed: (String) -> Unit,
    )

    private val lifecycleLock = Any()
    private var currentNowPlayingTitle: String = ""
    private var currentNowPlayingSubtitle: String = ""
    private var currentArtworkUrl: String = ""
    private var explicitNowPlayingMetadataSet: Boolean = false
    private var fallbackHideTimer: DesktopPlayerHideTimer? = null

    @Volatile
    private var handle: Long = 0L

    /** Reparented surface target (for example the floating PiP holder), preserved across handle replacements. */
    @Volatile
    private var surfaceHostOverride: Component? = null

    /** Native teardown of the previous player, if one is still running. */
    @Volatile
    private var disposeInFlight: Thread? = null

    @Volatile
    private var pendingSource: PendingSource? = null
    @Volatile
    private var releaseRequested: Boolean = false
    val isReleasing: Boolean
        get() = releaseRequested
    private val createsInFlight = mutableSetOf<Thread>()
    private var createWaitInFlight: Thread? = null
    private val releaseCallbacks = mutableListOf<ReleaseCallback>()
    private var releaseInFlight: Thread? = null
    private var releaseWatchdog: Thread? = null
    private var releaseTimedOut: Boolean = false
    private var terminalReleaseFailure: String? = null
    private var certificateFailurePending: Any? = null
    private var activeEventToken: Any? = null
    private var controlsState = PlayerControlsState()
    private var pictureInPictureActive = false
    private var remoteCommandsEnabled = remoteCommandsEnabledByDefault
    private var miniPlayerMode = false
    private val _manualRefreshTick = MutableStateFlow(0L)
    val manualRefreshTick: StateFlow<Long> = _manualRefreshTick.asStateFlow()
    @Volatile
    private var lastKnownVideoSize: IntSize = IntSize.Zero
    @Volatile
    private var currentVolumeLevel = rememberedVolumeLevel.coerceDesktopPlayerVolumeLevel()
    private var pendingSubtitleDelayMs: Int? = null
    private var pendingSubtitleStyle: SubtitleStyleState? = null
    private var pendingUseLibass: Boolean = false
    private var lastSentControlsStructureKey: NativeControlsStructureKey? = null
    private var onAction: (PlayerControlsAction) -> Boolean = { false }
    private var onEvent: (String, Double) -> Boolean = { _, _ -> false }
    private var onScrubChange: (Long) -> Boolean = { false }
    private var onScrubFinished: (Long) -> Boolean = { false }
    private var onExternalSubtitleLoadFailed: ((String) -> Unit)? = null
    private val subtitleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val subtitleRequestGeneration = AtomicLong(0L)
    @Volatile
    private var activeSubtitleJob: Job? = null
    @Volatile
    private var startupConfiguredForSource: String? = null
    @Volatile
    private var startupLoadedLocalPaths: Set<String> = emptySet()
    @Volatile
    private var startupSelectedLocalPath: String? = null
    private fun eventSink(pending: PendingSource, token: Any) = NativePlayerEventSink { type, value ->
        if (type == "stateSynchronized") {
            val current = synchronized(lifecycleLock) {
                !releaseRequested && pendingSource === pending && activeEventToken === token
            }
            if (current) _manualRefreshTick.update { it + 1L }
            return@NativePlayerEventSink
        }
        SwingUtilities.invokeLater {
            val current = synchronized(lifecycleLock) {
                !releaseRequested && pendingSource === pending && activeEventToken === token
            }
            if (current) handlePlayerEvent(type, value, token)
        }
    }

    init {
        lifecycleCoordinator?.registerController(this)
    }

    fun attach(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        playWhenReady: Boolean,
        initialPositionMs: Long,
        decoderPriority: Int,
        nvidiaRtxSuperResolutionEnabled: Boolean,
        nvidiaRtxHdrEnabled: Boolean = false,
        externalSubtitles: List<StreamSubtitle> = emptyList(),
        startupSubtitleSelection: StartupSubtitleSelection? = null,
        onError: (String?) -> Unit,
    ) {
        val headerLines = try { sourceHeaders.toHeaderLines() } catch (error: IllegalArgumentException) {
            onError(error.message)
            return
        }
        val pending = PendingSource(
            sourceUrl = sourceUrl,
            headerLines = headerLines,
            playWhenReady = playWhenReady,
            initialPositionMs = initialPositionMs.coerceAtLeast(0L),
            decoderPriority = decoderPriority,
            nvidiaRtxSuperResolutionEnabled = nvidiaRtxSuperResolutionEnabled,
            nvidiaRtxHdrEnabled = nvidiaRtxHdrEnabled,
            externalSubtitles = externalSubtitles,
            startupSubtitleSelection = startupSubtitleSelection,
            onError = onError,
        )
        startupConfiguredForSource = null
        startupLoadedLocalPaths = emptySet()
        startupSelectedLocalPath = null
        cancelInFlightSubtitleLoad()
        var terminalFailure: String? = null
        val accepted = synchronized(lifecycleLock) {
            when {
                releaseRequested -> false
                terminalReleaseFailure != null -> {
                    terminalFailure = terminalReleaseFailure
                    false
                }
                else -> {
                    pendingSource = pending
                    activeEventToken = null
                    certificateFailurePending = null
                    true
                }
            }
        }
        if (!accepted) {
            terminalFailure?.let { message -> SwingUtilities.invokeLater { pending.onError(message) } }
            return
        }
        log.d {
            "attach requested source=${sourceUrl.toPlaybackLogKey()} headers=${sourceHeaders.size} " +
                "playWhenReady=$playWhenReady initialPositionMs=$initialPositionMs decoderPriority=$decoderPriority"
        }
        host.onPeerReady = { attachPending() }
        if (isHostDisplayable()) {
            attachPending()
        }
    }

    private fun attachPending() {
        val pending = pendingSource ?: return
        SwingUtilities.invokeLater {
            val terminalFailure = synchronized(lifecycleLock) { terminalReleaseFailure }
            if (terminalFailure != null) {
                if (!releaseRequested && pendingSource === pending) pending.onError(terminalFailure)
                return@invokeLater
            }
            if (
                releaseRequested ||
                pendingSource !== pending ||
                !isHostDisplayable()
            ) {
                return@invokeLater
            }
            if (deferAttachUntilCreatesComplete()) return@invokeLater
            disposePlayerHandle()
            val localTeardown = disposeInFlight?.takeIf { it.isAlive }
            val externalTeardowns = lifecycleCoordinator?.pendingTeardownsExcluding(this).orEmpty()
            if (localTeardown == null && externalTeardowns.isEmpty()) {
                createPlayer(pending)
                return@invokeLater
            }
            // The previous player is still tearing down natively. Waiting off the EDT prevents
            // overlapping two native players across either source switches or session replacements.
            val teardownsToAwait = buildList {
                if (localTeardown != null) add(localTeardown)
                addAll(externalTeardowns)
            }
            Thread({
                val deadlineNanos = System.nanoTime() + TEARDOWN_WAIT_MS * 1_000_000L
                for (worker in teardownsToAwait) {
                    while (worker.isAlive) {
                        val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L
                        if (remainingMs <= 0L) break
                        runCatching { worker.join(remainingMs.coerceAtLeast(1L)) }
                    }
                }
                val initialCompleted = teardownsToAwait.none { it.isAlive }
                SwingUtilities.invokeLater {
                    val terminalFailure = synchronized(lifecycleLock) { terminalReleaseFailure }
                    if (terminalFailure != null) {
                        if (!releaseRequested && pendingSource === pending) pending.onError(terminalFailure)
                        return@invokeLater
                    }
                    if (!releaseRequested && isHostDisplayable() && pendingSource === pending) {
                        val remainingExternal = lifecycleCoordinator?.pendingTeardownsExcluding(this).orEmpty()
                        when {
                            initialCompleted && remainingExternal.isEmpty() -> createPlayer(pending)
                            initialCompleted && System.nanoTime() < deadlineNanos -> attachPending()
                            else -> {
                                log.w { "attach aborted because previous native teardown exceeded ${TEARDOWN_WAIT_MS}ms" }
                                pending.onError("Previous player is still shutting down. Please try again.")
                            }
                        }
                    }
                }
            }, "nuvio-player-attach").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun deferAttachUntilCreatesComplete(): Boolean = synchronized(lifecycleLock) {
        createsInFlight.removeAll { worker -> !worker.isAlive }
        if (createsInFlight.isEmpty()) return@synchronized false
        if (createWaitInFlight?.isAlive == true) return@synchronized true
        val workers = createsInFlight.toList()
        val pendingAtWaitStart = pendingSource
        val worker = Thread({
            val deadlineNanos = System.nanoTime() + createWaitTimeoutMs * 1_000_000L
            var interrupted = false
            workers.forEach { create ->
                while (create.isAlive) {
                    val remainingNanos = deadlineNanos - System.nanoTime()
                    if (remainingNanos <= 0L) break
                    try {
                        val millis = (remainingNanos / 1_000_000L).coerceAtLeast(1L)
                        create.join(millis)
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
            synchronized(lifecycleLock) {
                if (createWaitInFlight === Thread.currentThread()) createWaitInFlight = null
            }
            runCatching { onCreateWaitCompleted() }
            SwingUtilities.invokeLater {
                if (releaseRequested || !runCatching { isHostDisplayable() }.getOrDefault(false)) {
                    return@invokeLater
                }
                val (current, terminalFailure) = synchronized(lifecycleLock) {
                    createsInFlight.removeAll { create -> !create.isAlive }
                    pendingSource to terminalReleaseFailure
                }
                if (terminalFailure != null) {
                    current?.onError(terminalFailure)
                    return@invokeLater
                }
                val capturedCreatesRemain = workers.any { create -> create.isAlive }
                when {
                    current == null -> Unit
                    !capturedCreatesRemain -> attachPending()
                    current !== pendingAtWaitStart -> attachPending()
                    else -> current.onError("Previous player is still starting. Please try again.")
                }
            }
        }, "nuvio-player-create-wait").apply { isDaemon = true }
        createWaitInFlight = worker
        worker.start()
        true
    }

    private fun resolveEffectiveHostViewPtr(): Long {
        val overrideHost = surfaceHostOverride
        if (overrideHost != null) {
            if (isComponentDisplayable(overrideHost)) {
                return resolveComponentView(overrideHost)
            }
            surfaceHostOverride = null
        }
        return resolveHostView()
    }

    private fun createPlayer(pending: PendingSource) {
        val terminalFailure = synchronized(lifecycleLock) { terminalReleaseFailure }
        if (terminalFailure != null) {
            if (!releaseRequested && pendingSource === pending) pending.onError(terminalFailure)
            return
        }
        // Resolving the AWT peer must happen on the EDT; everything after it must not.
        val hostViewPtr = runCatching { resolveEffectiveHostViewPtr() }
            .getOrElse { error ->
                log.w(error) { "attach failed to resolve host source=${pending.sourceUrl.toPlaybackLogKey()}" }
                if (!releaseRequested && pendingSource === pending) pending.onError(playbackFailureText(error.message))
                return
            }
        val resolvedSource = if (pending.sourceUrl.startsWith("file:", ignoreCase = true)) {
            runCatching { java.io.File(java.net.URI(pending.sourceUrl)).absolutePath }.getOrElse {
                val stripped = pending.sourceUrl.replaceFirst(Regex("^file:/{1,3}", RegexOption.IGNORE_CASE), "")
                runCatching { java.net.URLDecoder.decode(stripped, "UTF-8") }.getOrDefault(stripped)
            }
        } else {
            pending.sourceUrl
        }

        val initialMiniPlayer = miniPlayerMode || controlsState.isInMiniPlayer
        val initialRemoteCommands = remoteCommandsEnabled

        // Native create blocks until the player's own UI thread finishes initialising, and that
        // thread creates child windows of the AWT host, which needs the EDT to keep pumping
        // messages. Creating on the EDT is therefore the same circular wait that the teardown had:
        // the app stops responding and Windows closes it as "stopped interacting" (Hang 1002).
        // Create off the EDT and come back to it for the parts that touch Swing state.
        val eventToken = Any()
        val createWorker = Thread({
            try {
                runCatching {
                    val createOverride = nativeCreateOverride
                    val createdHandle = if (createOverride != null) {
                        createOverride(
                            hostViewPtr,
                            resolvedSource,
                            pending.headerLines.toTypedArray(),
                            pending.playWhenReady,
                            pending.initialPositionMs,
                            controlsPageUrlProvider(),
                            pending.decoderPriority,
                            pending.nvidiaRtxSuperResolutionEnabled,
                            pending.nvidiaRtxHdrEnabled,
                            eventSink(pending, eventToken),
                        )
                    } else {
                        backend.create(
                            hostViewPtr = hostViewPtr,
                            sourceUrl = resolvedSource,
                            headerLines = pending.headerLines.toTypedArray(),
                            playWhenReady = pending.playWhenReady,
                            initialPositionMs = pending.initialPositionMs,
                            controlsPageUrl = controlsPageUrlProvider(),
                            decoderPriority = pending.decoderPriority,
                            nvidiaRtxSuperResolutionEnabled = pending.nvidiaRtxSuperResolutionEnabled,
                            nvidiaRtxHdrEnabled = pending.nvidiaRtxHdrEnabled,
                            eventSink = eventSink(pending, eventToken),
                            inMiniPlayer = initialMiniPlayer,
                            remoteCommandsEnabled = initialRemoteCommands,
                        )
                    }
                    createdHandle.also { if (it == 0L) error("Native player did not return a handle.") }
                }.onSuccess { created ->
                    val accepted = synchronized(lifecycleLock) {
                        if (!releaseRequested && terminalReleaseFailure == null && pendingSource === pending && activeEventToken === eventToken) {
                            handle = created
                            lastKnownVideoSize = IntSize.Zero
                            true
                        } else {
                            false
                        }
                    }
                    if (!accepted) {
                        runCatching { nativeDispose(created) }
                            .onFailure { error -> recordTerminalDisposeFailure(error, "superseded create", created) }
                        return@onSuccess
                    }
                    configureStartupSubtitlesSafe(created, pending)
                    SwingUtilities.invokeLater {
                        val shouldConfigure = synchronized(lifecycleLock) {
                            when {
                                handle != created -> false
                                pendingSource !== pending || !isHostDisplayable() -> {
                                    handle = 0L
                                    lastSentControlsStructureKey = null
                                    startTrackedDisposeLocked(created)
                                    false
                                }
                                else -> true
                            }
                        }
                        if (!shouldConfigure) return@invokeLater
                        log.d {
                            "attach created handle=$created source=${resolvedSource.toPlaybackLogKey()} " +
                                "initialPositionMs=${pending.initialPositionMs}"
                        }
                        val currentHostViewPtr = runCatching { resolveEffectiveHostViewPtr() }.getOrNull()
                        if (currentHostViewPtr != null && currentHostViewPtr != hostViewPtr) {
                            backend.reparentSurface(created, currentHostViewPtr)
                        }
                        backend.setRemoteCommandsEnabled(created, remoteCommandsEnabled)
                        if (miniPlayerMode) {
                            backend.setMiniPlayerMode(created, true)
                        }
                        applyRememberedVolume()
                        updateControls(controlsState)
                        setResizeMode(rememberedResizeMode)
                        if (currentNowPlayingTitle.isNotEmpty() || currentArtworkUrl.isNotEmpty()) {
                            setNowPlayingMetadata(currentNowPlayingTitle, currentNowPlayingSubtitle, currentArtworkUrl)
                        }
                        applyPendingSubtitleSettings()
                        if (certificateFailurePending === eventToken) {
                            certificateFailurePending = null
                            handlePlayerEvent("certificateFailure", 0.0, eventToken)
                        }
                        _manualRefreshTick.update { it + 1L }
                        triggerFallbackRemoteStartupSubtitleIfNeeded(pending)
                    }
                }.onFailure { error ->
                    log.w(error) { "attach failed source=${pending.sourceUrl.toPlaybackLogKey()}" }
                    SwingUtilities.invokeLater {
                        if (!releaseRequested && pendingSource === pending) pending.onError(playbackFailureText(error.message))
                    }
                }
            } finally {
                synchronized(lifecycleLock) {
                    createsInFlight.remove(Thread.currentThread())
                    lifecycleCoordinator?.unregisterTeardown(Thread.currentThread())
                }
            }
        }, "nuvio-player-create").apply { isDaemon = true }
        var admissionFailure: String? = null
        val admitted = synchronized(lifecycleLock) {
            when {
                releaseRequested || pendingSource !== pending -> false
                terminalReleaseFailure != null -> {
                    admissionFailure = terminalReleaseFailure
                    false
                }
                else -> {
                    activeEventToken = eventToken
                    createsInFlight += createWorker
                    createWorker.start()
                    true
                }
            }
        }
        if (!admitted) {
            admissionFailure?.let { message ->
                SwingUtilities.invokeLater {
                    if (!releaseRequested && pendingSource === pending) pending.onError(message)
                }
            }
            return
        }
    }

    fun setControlCallbacks(
        onAction: (PlayerControlsAction) -> Boolean,
        onEvent: (String, Double) -> Boolean,
        onScrubChange: (Long) -> Boolean,
        onScrubFinished: (Long) -> Boolean,
    ) {
        this.onAction = onAction
        this.onEvent = onEvent
        this.onScrubChange = onScrubChange
        this.onScrubFinished = onScrubFinished
        log.d { "control callbacks attached handle=$handle" }
        host.onCursorActivity = {
            this.onEvent("cursorActivity", 0.0)
        }
    }

    // The embedded controls WebView can lose keyboard focus when the desktop
    // window is reactivated (for example after alt-tab). Re-apply native focus
    // whenever the host window gains focus. Returns the uninstaller; null until
    // the host is inside a window (the ancestor does not exist before first paint).
    fun installWindowFocusForwarding(): (() -> Unit)? {
        val window = SwingUtilities.getWindowAncestor(host) ?: return null
        val listener = object : WindowAdapter() {
            override fun windowGainedFocus(event: WindowEvent) {
                requestKeyboardFocus()
            }
        }
        window.addWindowFocusListener(listener)
        return { window.removeWindowFocusListener(listener) }
    }

    /** The dock is on the browsing window, so auto-hide must not take the cursor away there. */
    private fun publishHostControlsVisible(
        visible: Boolean,
        inMiniPlayer: Boolean = controlsState.isInMiniPlayer,
    ) {
        setHostControlsVisible(visible || inMiniPlayer)
    }

    @Synchronized
    fun updateControls(state: PlayerControlsState) {
        val cursorVisible = DesktopPlayerHideTimer.computeDesktopCursorVisible(
            controlsVisible = state.controlsVisible,
            hasError = state.playbackErrorMessage.isNotBlank(),
            isLoading = state.isLoading,
        )
        publishHostControlsVisible(cursorVisible, state.isInMiniPlayer)
        val currentHandle = handle
        val current = currentHandle.takeIf { it != 0L } ?: run {
            controlsState = state
            return
        }
        val stateWithVolume = state.copy(
            volumeLevel = resolveDesktopPlayerVolumeLevel(
                requestedLevel = state.volumeLevel,
                currentLevel = currentVolumeLevel,
                rememberedLevel = rememberedVolumeLevel,
            ),
        )
        currentVolumeLevel = stateWithVolume.volumeLevel ?: currentVolumeLevel
        controlsState = stateWithVolume
        val isFullscreen = isDesktopAppFullscreen(SwingUtilities.getWindowAncestor(host))
        val structureKey = NativeControlsStructureKey(
            state = stateWithVolume.nativeControlsStructureKey(),
            isFullscreen = isFullscreen,
            isInPip = DesktopPlayerPictureInPicture.isEnabled,
        )
        if (structureKey == lastSentControlsStructureKey) return
        lastSentControlsStructureKey = structureKey
        log.d {
            "updateControls handle=$current title=${stateWithVolume.title.take(40)} " +
                "pos=${stateWithVolume.positionMs} duration=${stateWithVolume.durationMs} " +
                "speed=${stateWithVolume.playbackSpeedLabel} audioLabel=${stateWithVolume.audioLabel} " +
                "subsLabel=${stateWithVolume.subtitlesLabel} fullscreen=$isFullscreen"
        }
        backend.updateControls(current, stateWithVolume.toControlsJson(isFullscreen, ::refreshControlsForImage))
    }

    @Synchronized
    fun setRemoteCommandsEnabled(enabled: Boolean) {
        remoteCommandsEnabled = enabled
        handle.takeIf { it != 0L }?.let { current ->
            backend.setRemoteCommandsEnabled(current, enabled)
        }
    }

    @Synchronized
    fun setMiniPlayerMode(inMiniPlayer: Boolean) {
        if (miniPlayerMode == inMiniPlayer) return
        miniPlayerMode = inMiniPlayer
        handle.takeIf { it != 0L }?.let { current ->
            backend.setMiniPlayerMode(current, inMiniPlayer)
        }
    }

    private fun refreshControlsForImage() {
        SwingUtilities.invokeLater {
            if (releaseRequested) return@invokeLater
            lastSentControlsStructureKey = null
            updateControls(controlsState)
        }
    }

    fun onDesktopFullscreenChanged() {
        lastSentControlsStructureKey = null
        updateControls(controlsState)
        requestKeyboardFocus()
    }

    fun reparentSurface(host: Component): Boolean {
        if (!isComponentDisplayable(host)) return false
        val pointer = runCatching { resolveComponentView(host) }
            .onFailure { error -> log.w(error) { "failed to resolve PiP native host ${host.javaClass.name}" } }
            .getOrNull() ?: return false
        val nextOverride = host.takeIf { it !== this.host }
        val (current, hasPendingOrCreating) = synchronized(lifecycleLock) {
            createsInFlight.removeAll { !it.isAlive }
            handle to (!releaseRequested && (pendingSource != null || createsInFlight.isNotEmpty()))
        }
        if (current != 0L) {
            if (!backend.reparentSurface(current, pointer)) return false
            surfaceHostOverride = nextOverride
            return true
        }
        if (hasPendingOrCreating) {
            surfaceHostOverride = nextOverride
            return true
        }
        return false
    }

    fun layoutNativeSubviews() {
        val current = handle.takeIf { it != 0L } ?: return
        backend.layoutNativeSubviews(current)
    }

    fun requestKeyboardFocus() {
        SwingUtilities.invokeLater {
            val current = handle.takeIf { it != 0L } ?: return@invokeLater
            if (DesktopPlayerPictureInPicture.isEnabled) {
                DesktopPlayerPictureInPicture.pipWindow?.videoHolderPanel?.requestFocusInWindow()
                backend.requestFocus(current)
                return@invokeLater
            }
            if (controlsState.isInMiniPlayer || !isHostDisplayable()) return@invokeLater
            host.requestFocusInWindow()
            backend.requestFocus(current)
        }
    }

    fun releaseKeyboardFocusToWindow() {
        SwingUtilities.invokeLater {
            host.resetCursorVisibility()
            val window = SwingUtilities.getWindowAncestor(host) ?: return@invokeLater
            if (host.hasFocus()) {
                window.requestFocusInWindow()
            }
        }
    }

    override fun updateNowPlayingMetadata(info: PlayerNowPlayingInfo) {
        explicitNowPlayingMetadataSet = true
        setNowPlayingMetadata(info.title, info.subtitle, info.artworkUrl)
    }

    override fun clearNowPlayingInfo() {
        explicitNowPlayingMetadataSet = false
        setNowPlayingMetadata("", "", "")
    }

    fun updatePresenceNowPlayingFallback(title: String?, subtitle: String?, artworkUrl: String?) {
        if (explicitNowPlayingMetadataSet) return
        setNowPlayingMetadata(title, subtitle, artworkUrl)
    }

    fun setNowPlayingMetadata(title: String?, subtitle: String?, artworkUrl: String?) {
        if (DesktopHostOs.current != DesktopHostOs.MACOS) return
        synchronized(lifecycleLock) {
            currentNowPlayingTitle = title.orEmpty()
            currentNowPlayingSubtitle = subtitle.orEmpty()
            currentArtworkUrl = artworkUrl.orEmpty()
            val url = currentArtworkUrl
            val image = WebviewImages.resolve(url) {
                SwingUtilities.invokeLater {
                    synchronized(lifecycleLock) {
                        if (currentArtworkUrl == url && !releaseRequested) {
                            setNowPlayingMetadata(currentNowPlayingTitle, currentNowPlayingSubtitle, url)
                        }
                    }
                }
            }
            if (!releaseRequested && handle != 0L) {
                backend.setNowPlayingMetadata(
                    handle,
                    currentNowPlayingTitle,
                    currentNowPlayingSubtitle,
                    image,
                )
            }
        }
    }

    @Synchronized
    fun setResizeMode(mode: PlayerResizeMode) {
        rememberedResizeMode = mode
        applyResizeMode()
    }

    @Synchronized
    fun setPictureInPictureActive(active: Boolean) {
        pictureInPictureActive = active
        if (!active) {
            surfaceHostOverride = null
        }
        applyResizeMode()
    }

    private fun applyResizeMode() {
        val mode = desktopEffectiveResizeMode(rememberedResizeMode, pictureInPictureActive)
        handle.takeIf { it != 0L }?.let { current ->
            backend.setResizeMode(
                handle = current,
                mode = when (mode) {
                    PlayerResizeMode.Fit -> 0
                    PlayerResizeMode.Fill -> 1
                    PlayerResizeMode.Zoom -> 2
                    PlayerResizeMode.Stretch -> 3
                },
            )
        }
    }

    private fun handlePlayerEvent(type: String, value: Double) {
        handlePlayerEvent(type, value, activeEventToken)
    }

    private fun handlePlayerEvent(type: String, value: Double, eventToken: Any?) {
        if (type.shouldLogNativeControlEvent()) {
            log.d { "event received handle=$handle type=$type value=$value" }
        }
        when (type) {
            "certificateFailure" -> {
                val (current, source) = synchronized(lifecycleLock) {
                    if (releaseRequested || activeEventToken !== eventToken) return
                    if (handle == 0L) { certificateFailurePending = eventToken; return }
                    handle to pendingSource
                }
                val network = networkIdentity()
                if (network.isBlank()) return
                val approved = confirmUnverified()
                // The dialog runs a nested event loop. Revalidate its source, network and handle
                // afterward, and keep ownership locked through the native call.
                synchronized(lifecycleLock) {
                    if (approved && handle == current && activeEventToken === eventToken && pendingSource === source && !releaseRequested && networkIdentity() == network) {
                        if (nativeRetryUnverified(current, network)) rememberPlaybackNetwork(network)
                    }
                }
            }
            "cursorActivity" -> {
                if (!onEvent(type, value)) {
                    ensureFallbackHideTimer().onMouseMoved()
                }
            }
            "keepChromeVisible" -> {
                if (!onEvent(type, value)) {
                    val timer = ensureFallbackHideTimer()
                    if (value >= 0.5) {
                        timer.onInteractionStarted()
                    } else {
                        if (timer.currentState().isPointerDown) {
                            timer.onInteractionEnded()
                        }
                        timer.onMouseMoved()
                    }
                }
            }
            "chromeInteraction" -> {
                if (!onEvent(type, value)) {
                    val timer = ensureFallbackHideTimer()
                    if (value >= 0.5) {
                        timer.onInteractionStarted()
                    } else {
                        timer.onInteractionEnded()
                    }
                }
            }
            "hideChrome" -> {
                publishHostControlsVisible(false)
                if (!onEvent(type, value)) {
                    ensureFallbackHideTimer().onControlsHidden()
                }
            }
            "stateSynchronized" -> _manualRefreshTick.update { it + 1L }
            "scrubChange" -> {
                val handled = onScrubChange(value.toLong())
                log.d { "scrubChange positionMs=${value.toLong()} handled=$handled handle=$handle" }
                if (!handled) {
                    ensureFallbackHideTimer().onScrubbingChanged(true)
                    updateLocalProgress(value.toLong())
                }
            }
            "scrubFinish" -> {
                val scrubHandled = onScrubFinished(value.toLong())
                log.d { "scrubFinish positionMs=${value.toLong()} handled=$scrubHandled handle=$handle" }
                if (!scrubHandled) {
                    ensureFallbackHideTimer().onScrubbingChanged(false)
                    seekTo(value.toLong())
                }
            }
            "toggleFullscreen" -> {
                if (onEvent(type, value)) 
                    return
                if (DesktopPlayerPictureInPicture.isEnabled) {
                    DesktopPlayerPictureInPicture.toggle()
                } else {
                    val targetWindow = SwingUtilities.getWindowAncestor(host)
                    if (value >= 0.5) {
                        exitDesktopAppFullscreen(targetWindow)
                    } else {
                        toggleDesktopAppFullscreen(targetWindow)
                    }
                    onDesktopFullscreenChanged()
                }
            }
            "mediaPlay",
            "mediaPause" -> {
                if (onEvent(type, value)) return
                applyFallbackPlaybackState(play = type == "mediaPlay")
            }
            "mediaNext" -> {
                if (onEvent(type, value)) return
                fallbackSeekBy(10_000L)
            }
            "mediaPrevious" -> {
                if (onEvent(type, value)) return
                fallbackSeekBy(-10_000L)
            }
            "setPlaybackState",
            "setPlaybackStateQuiet" -> {
                if (onEvent(type, value)) return
                applyFallbackPlaybackState(play = value >= 0.5)
            }
            "dragWindow" -> backend.beginWindowDrag(handle)
            "volumeChange" -> setFallbackVolume(value.toFloat())
            "volumeChangeTemporary" -> setTemporaryVolume(value.toFloat())
            "setPlaybackSpeed" -> {
                if (onEvent(type, value)) return
                setPlaybackSpeed(value.toFloat())
            }
            else -> {
                val eventHandled = onEvent(type, value)
                if (type.shouldLogNativeControlEvent()) {
                    log.d { "event delegated type=$type handled=$eventHandled handle=$handle" }
                }
                if (eventHandled) return
                val action = type.toPlayerControlsAction()
                if (action == null) return
                val actionHandled = onAction(action)
                log.d { "action delegated action=$action handled=$actionHandled handle=$handle" }
                if (!actionHandled) {
                    handleFallbackAction(action)
                }
            }
        }
    }

    @Synchronized
    private fun ensureFallbackHideTimer(): DesktopPlayerHideTimer {
        fallbackHideTimer?.let { return it }
        return DesktopPlayerHideTimer(
            nowMs = hideTimerNowMs,
            scheduler = hideTimerScheduler,
        ) { hideState ->
            val current = handle
            if (current == 0L || releaseRequested) return@DesktopPlayerHideTimer
            SwingUtilities.invokeLater {
                if (!releaseRequested && controlsState.controlsVisible != hideState.controlsVisible) {
                    updateControls(controlsState.copy(controlsVisible = hideState.controlsVisible))
                } else if (!releaseRequested) {
                    publishHostControlsVisible(hideState.cursorVisible)
                }
            }
        }.also { fallbackHideTimer = it }
    }

    private fun applyFallbackPlaybackState(play: Boolean) {
        val current = handle
        if (current == 0L) return
        if (play && NativePlayerBridge.isEnded(current)) {
            NativePlayerBridge.seekTo(current, 0L)
        }
        NativePlayerBridge.setPaused(current, !play)
    }

    @Synchronized
    private fun updateLocalProgress(positionMs: Long) {
        controlsState = controlsState.copy(positionMs = positionMs)
        updateControls(controlsState)
    }

    private fun handleFallbackAction(action: PlayerControlsAction) {
        log.d { "fallback action=$action handle=$handle" }
        when (action) {
            PlayerControlsAction.TogglePlayback,
            PlayerControlsAction.KeyboardTogglePlayback -> {
                val current = handle
                if (current == 0L) return
                val isEnded = backend.isEnded(current)
                val isPaused = backend.isPaused(current)
                if (isEnded) {
                    nativeSeekTo(current, 0L)
                    backend.setPaused(current, false)
                } else {
                    backend.setPaused(current, !isPaused)
                }
            }
            PlayerControlsAction.SeekBack,
            PlayerControlsAction.KeyboardSeekBack -> fallbackSeekBy(-10_000L)
            PlayerControlsAction.SeekForward,
            PlayerControlsAction.KeyboardSeekForward -> fallbackSeekBy(10_000L)
            PlayerControlsAction.KeyboardVolumeDown -> adjustFallbackVolume(-10f)
            PlayerControlsAction.KeyboardVolumeUp -> adjustFallbackVolume(10f)
            PlayerControlsAction.PictureInPicture -> togglePictureInPictureFromShortcut()
            PlayerControlsAction.Speed -> cycleFallbackSpeed()
            else -> Unit
        }
    }

    fun togglePictureInPictureFromShortcut() {
        DesktopPlayerPictureInPicture.toggle()
    }

    override fun adjustVolumeBy(deltaFraction: Float): Boolean {
        adjustFallbackVolume(deltaFraction * 100f)
        return true
    }

    @Synchronized
    private fun adjustFallbackVolume(delta: Float) {
        val current = handle
        if (current != 0L) {
            val currentLevel = resolveDesktopPlayerVolumeLevel(
                requestedLevel = null,
                currentLevel = currentVolumeLevel,
                rememberedLevel = rememberedVolumeLevel,
            )
            val nextLevel = (currentLevel + (delta / 100f)).coerceDesktopPlayerVolumeLevel()
            setFallbackVolume(nextLevel)
        }
    }

    @Synchronized
    private fun setFallbackVolume(level: Float) {
        val current = handle
        if (current != 0L) {
            val nextLevel = level.coerceDesktopPlayerVolumeLevel()
            rememberedVolumeLevel = nextLevel
            currentVolumeLevel = nextLevel
            DesktopPlayerVolumeStorage.saveVolumeLevel(nextLevel)
            backend.setVolume(current, nextLevel)
            controlsState = controlsState.copy(volumeLevel = nextLevel)
            updateControls(controlsState)
        }
    }

    @Synchronized
    private fun setTemporaryVolume(level: Float) {
        val current = handle
        if (current != 0L) {
            val nextLevel = level.coerceDesktopPlayerVolumeLevel()
            currentVolumeLevel = nextLevel
            backend.setVolume(current, nextLevel)
            controlsState = controlsState.copy(volumeLevel = nextLevel)
            updateControls(controlsState)
        }
    }

    /**
     * Quietens playback for a sleep-timer fade. Scales the level being heard now,
     * so a mute stays silent. The slider and the saved volume stay put.
     * A fraction of 1 puts the heard level back.
     */
    @Synchronized
    override fun setVolumeFade(fraction: Float) {
        val current = handle
        if (current == 0L) return
        val scaled = sleepTimerFadeOutputLevel(
            audibleLevel = currentVolumeLevel,
            rememberedLevel = rememberedVolumeLevel,
            fraction = fraction,
        )
        NativePlayerBridge.setVolume(current, scaled)
    }

    @Synchronized
    private fun applyRememberedVolume() {
        val current = handle
        if (current == 0L) return
        val level = rememberedVolumeLevel.coerceDesktopPlayerVolumeLevel()
        currentVolumeLevel = level
        backend.setVolume(current, level)
        controlsState = controlsState.copy(volumeLevel = level)
        log.d { "applied remembered volume level=$level handle=$current" }
    }

    private fun fallbackSeekBy(offsetMs: Long) {
        val current = handle
        if (current != 0L) {
            backend.seekBy(current, offsetMs)
            _manualRefreshTick.update { it + 1L }
        }
    }

    private fun cycleFallbackSpeed() {
        val current = handle
        if (current == 0L) return
        val speeds = listOf(1f, 1.25f, 1.5f, 2f)
        val currentSpeed = backend.speed(current)
        val next = speeds.firstOrNull { it > currentSpeed + 0.01f } ?: speeds.first()
        backend.setSpeed(current, next)
    }

    fun snapshot(includeVideoSize: Boolean = true): PlayerPlaybackSnapshot {
        val current = handle
        if (current == 0L) return PlayerPlaybackSnapshot(isLoading = true)
        return runCatching {
            val isLoading = backend.isLoading(current)
            val isEnded = backend.isEnded(current)
            val cachedSize = lastKnownVideoSize
            val videoSize = if (includeVideoSize || cachedSize == IntSize.Zero) {
                desktopMpvVideoSize { name -> backend.stringProperty(current, name) }.also { resolved ->
                    if (resolved != IntSize.Zero) {
                        lastKnownVideoSize = resolved
                    }
                }
            } else {
                cachedSize
            }
            PlayerPlaybackSnapshot(
                isLoading = isLoading,
                isPlaying = !backend.isPaused(current) && !isLoading && !isEnded,
                isEnded = isEnded,
                durationMs = backend.durationMs(current),
                positionMs = backend.positionMs(current),
                bufferedPositionMs = backend.bufferedPositionMs(current),
                playbackSpeed = backend.speed(current),
                videoWidth = videoSize.width,
                videoHeight = videoSize.height,
            )
        }.getOrDefault(PlayerPlaybackSnapshot(isLoading = true))
    }

    fun releaseBeforeNavigation(onReleased: () -> Unit) {
        releaseBeforeNavigation(onReleased, onReleaseFailed = {})
    }

    override fun releaseBeforeNavigation(
        onReleased: () -> Unit,
        onReleaseFailed: (String) -> Unit,
    ) {
        synchronized(lifecycleLock) {
            releaseRequested = true
            activeEventToken = null
            certificateFailurePending = null
            createsInFlight.removeAll { !it.isAlive }
            lifecycleCoordinator?.registerTeardowns(this, createsInFlight)
            lifecycleCoordinator?.registerTeardown(this, disposeInFlight)
        }
        host.resetCursorVisibility()
        invalidateForRelease()
        val callback = ReleaseCallback(onReleased, onReleaseFailed)
        var shouldStart = false
        var immediateFailure: String? = null
        val worker = synchronized(lifecycleLock) {
            val active = releaseInFlight?.takeIf { it.isAlive }
            when {
                terminalReleaseFailure != null -> {
                    immediateFailure = terminalReleaseFailure
                    active ?: Thread.currentThread()
                }
                active != null && releaseTimedOut -> {
                    immediateFailure = "Native player is still shutting down. Please retry or quit Nuvio."
                    active
                }
                else -> {
                    releaseCallbacks += callback
                    active ?: Thread({
                        try {
                            awaitThreadCompletion(disposeInFlight, "native teardown")
                            awaitInFlightCreates()
                            // A create worker publishes/configures its result on the EDT before leaving
                            // createsInFlight. Release must drain those already-queued callbacks before
                            // taking the final disposal snapshot: a stale publication can reject its
                            // handle and start a tracked disposal after the create thread has exited.
                            awaitSwingCallbacksQueuedByCreates()
                            awaitThreadCompletion(disposeInFlight, "native teardown queued by create")
                            var failureMessage = synchronized(lifecycleLock) { terminalReleaseFailure }
                            val current = if (failureMessage == null) detachPlayerHandle() else 0L
                            if (current != 0L) {
                                log.d { "pre-navigation dispose started handle=$current" }
                                runCatching { nativeDispose(current) }
                                    .onSuccess { log.d { "pre-navigation dispose completed handle=$current" } }
                                    .onFailure { error ->
                                        failureMessage = "Native player shutdown failed. Quit Nuvio before removing the player."
                                        log.w(error) { "pre-navigation dispose failed handle=$current" }
                                    }
                            }
                            val callbacks = synchronized(lifecycleLock) {
                                releaseInFlight = null
                                releaseTimedOut = false
                                if (failureMessage != null) terminalReleaseFailure = failureMessage
                                releaseCallbacks.toList().also { releaseCallbacks.clear() }
                            }
                            SwingUtilities.invokeLater {
                                callbacks.forEach { pending ->
                                    val invoke = if (failureMessage == null) {
                                        { pending.onReleased() }
                                    } else {
                                        { pending.onFailed(failureMessage) }
                                    }
                                    runCatching(invoke)
                                        .onFailure { error -> log.w(error) { "release callback failed" } }
                                }
                            }
                        } finally {
                            val watchdogToStop = synchronized(lifecycleLock) {
                                releaseWatchdog.also { releaseWatchdog = null }
                            }
                            watchdogToStop?.interrupt()
                            lifecycleCoordinator?.unregisterTeardown(Thread.currentThread())
                            lifecycleCoordinator?.unregisterController(this)
                        }
                    }, "nuvio-player-release").apply {
                        isDaemon = true
                        releaseInFlight = this
                        releaseTimedOut = false
                        shouldStart = true
                    }
                }
            }
        }
        if (immediateFailure != null) {
            SwingUtilities.invokeLater {
                runCatching { callback.onFailed(immediateFailure) }
                    .onFailure { error -> log.w(error) { "immediate release failure callback failed" } }
            }
            return
        }
        if (shouldStart) {
            lifecycleCoordinator?.registerTeardown(this, worker, allowUnstarted = true)
            worker.start()
            val startedWatchdog = startReleaseWatchdog(worker)
            synchronized(lifecycleLock) {
                if (releaseInFlight === worker && worker.isAlive) {
                    releaseWatchdog = startedWatchdog
                } else {
                    startedWatchdog.interrupt()
                }
            }
        }
    }

    private fun startReleaseWatchdog(worker: Thread): Thread =
        Thread({
            try {
                Thread.sleep(releaseTimeoutMs)
            } catch (_: InterruptedException) {
                return@Thread
            }
            val callbacks = synchronized(lifecycleLock) {
                if (releaseInFlight !== worker || !worker.isAlive) return@synchronized emptyList()
                releaseTimedOut = true
                releaseCallbacks.toList().also { releaseCallbacks.clear() }
            }
            if (callbacks.isEmpty()) return@Thread
            SwingUtilities.invokeLater {
                callbacks.forEach { pending ->
                    runCatching {
                        pending.onFailed("Native player shutdown timed out. Please retry or quit Nuvio.")
                    }.onFailure { error -> log.w(error) { "release timeout callback failed" } }
                }
            }
        }, "nuvio-player-release-watchdog").apply {
            isDaemon = true
            start()
        }

    fun dispose() {
        fallbackHideTimer?.dispose()
        fallbackHideTimer = null
        host.resetCursorVisibility()
        val accepted = synchronized(lifecycleLock) {
            if (releaseRequested) {
                false
            } else {
                pendingSource = null
                activeEventToken = null
                certificateFailurePending = null
                createsInFlight.removeAll { !it.isAlive }
                lifecycleCoordinator?.registerTeardowns(this, createsInFlight)
                true
            }
        }
        if (!accepted) return
        host.onPeerReady = null
        disposePlayerHandle()
        val canUnregisterNow = synchronized(lifecycleLock) {
            handle == 0L && disposeInFlight == null && createsInFlight.isEmpty()
        }
        if (canUnregisterNow) {
            lifecycleCoordinator?.unregisterController(this)
        }
    }

    private fun cancelPendingAttachment() {
        synchronized(lifecycleLock) {
            pendingSource = null
            activeEventToken = null
            certificateFailurePending = null
        }
        host.onPeerReady = null
    }

    private fun invalidateForRelease() {
        fallbackHideTimer?.dispose()
        fallbackHideTimer = null
        cancelPendingAttachment()
        surfaceHostOverride = null
        cancelInFlightSubtitleLoad()
        host.onCursorActivity = null
        onAction = { false }
        onEvent = { _, _ -> false }
        onScrubChange = { false }
        onScrubFinished = { false }
        onExternalSubtitleLoadFailed = null
    }

    private fun awaitInFlightCreates() {
        while (true) {
            val workers = synchronized(lifecycleLock) {
                createsInFlight.removeAll { worker -> !worker.isAlive }
                createsInFlight.toList()
            }
            if (workers.isEmpty()) return
            workers.forEach { worker -> awaitThreadCompletion(worker, "native create") }
        }
    }

    private fun awaitSwingCallbacksQueuedByCreates() {
        val completed = CountDownLatch(1)
        SwingUtilities.invokeLater { completed.countDown() }
        var interrupted = false
        while (true) {
            try {
                completed.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt()
            log.w { "interrupted while awaiting queued native create callbacks; completion was preserved" }
        }
    }

    private fun awaitThreadCompletion(thread: Thread?, operation: String) {
        if (thread == null || thread === Thread.currentThread()) return
        var interrupted = false
        while (thread.isAlive) {
            try {
                thread.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt()
            log.w { "interrupted while awaiting $operation; completion was preserved" }
        }
    }

    private fun detachPlayerHandle(): Long = synchronized(lifecycleLock) {
        val current = handle
        handle = 0L
        activeEventToken = null
        certificateFailurePending = null
        lastSentControlsStructureKey = null
        current
    }

    private fun recordTerminalDisposeFailure(error: Throwable, operation: String, nativeHandle: Long) {
        synchronized(lifecycleLock) {
            terminalReleaseFailure = "Native player shutdown failed. Quit Nuvio before removing the player."
        }
        log.w(error) { "$operation dispose failed handle=$nativeHandle" }
    }

    /** Must be called while holding [lifecycleLock]. */
    private fun startTrackedDisposeLocked(current: Long) {
        val prior = disposeInFlight?.takeIf { it.isAlive }
        val worker = Thread(
            {
                try {
                    awaitThreadCompletion(prior, "prior native teardown")
                    runCatching { nativeDispose(current) }
                        .onFailure { error -> recordTerminalDisposeFailure(error, "native", current) }
                } finally {
                    val shouldUnregisterController = synchronized(lifecycleLock) {
                        if (disposeInFlight === Thread.currentThread()) {
                            disposeInFlight = null
                        }
                        handle == 0L && pendingSource == null && createsInFlight.isEmpty()
                    }
                    lifecycleCoordinator?.unregisterTeardown(Thread.currentThread())
                    if (shouldUnregisterController) {
                        lifecycleCoordinator?.unregisterController(this)
                    }
                }
            },
            "nuvio-player-dispose",
        ).apply {
            isDaemon = true
        }
        disposeInFlight = worker
        lifecycleCoordinator?.registerTeardown(this, worker, allowUnstarted = true)
        worker.start()
    }

    private fun disposePlayerHandle() {
        synchronized(lifecycleLock) {
            if (releaseRequested) return
            activeEventToken = null
            certificateFailurePending = null
            val current = handle
            if (current == 0L) return
            handle = 0L
            lastSentControlsStructureKey = null
            // Register and start teardown atomically so terminal release cannot sample an empty
            // worker slot between detaching the handle and starting native disposal.
            startTrackedDisposeLocked(current)
        }
    }

    override fun play() {
        log.d { "play handle=$handle" }
        handle.takeIf { it != 0L }?.let { backend.setPaused(it, false) }
        _manualRefreshTick.update { it + 1L }
    }

    override fun pause() {
        log.d { "pause handle=$handle" }
        handle.takeIf { it != 0L }?.let { backend.setPaused(it, true) }
        _manualRefreshTick.update { it + 1L }
    }

    override fun seekTo(positionMs: Long) {
        log.d { "seekTo positionMs=$positionMs handle=$handle" }
        handle.takeIf { it != 0L }?.let { nativeSeekTo(it, positionMs) }
        _manualRefreshTick.update { it + 1L }
    }

    override fun trySeekTo(positionMs: Long): Boolean {
        val current = handle.takeIf { it != 0L } ?: return false
        log.d { "trySeekTo positionMs=$positionMs handle=$current" }
        nativeSeekTo(current, positionMs)
        _manualRefreshTick.update { it + 1L }
        return true
    }

    override fun seekBy(offsetMs: Long) {
        log.d { "seekBy offsetMs=$offsetMs handle=$handle" }
        handle.takeIf { it != 0L }?.let { backend.seekBy(it, offsetMs) }
        _manualRefreshTick.update { it + 1L }
    }

    override fun retry() {
        val pending = pendingSource ?: return
        attach(
            sourceUrl = pending.sourceUrl,
            sourceHeaders = pending.headerLines.toHeaderMap(),
            playWhenReady = pending.playWhenReady,
            initialPositionMs = pending.initialPositionMs,
            decoderPriority = pending.decoderPriority,
            nvidiaRtxSuperResolutionEnabled = pending.nvidiaRtxSuperResolutionEnabled,
            nvidiaRtxHdrEnabled = pending.nvidiaRtxHdrEnabled,
            externalSubtitles = pending.externalSubtitles,
            startupSubtitleSelection = pending.startupSubtitleSelection,
            onError = pending.onError,
        )
    }

    override fun setPlaybackSpeed(speed: Float) {
        log.d { "setPlaybackSpeed speed=$speed handle=$handle" }
        handle.takeIf { it != 0L }?.let { backend.setSpeed(it, speed) }
        _manualRefreshTick.update { it + 1L }
    }

    override fun getAudioTracks(): List<AudioTrack> =
        decodeTracks { backend.audioTracksJson(it) }.map { track ->
            AudioTrack(
                index = track.index,
                id = track.id,
                label = track.label,
                language = track.language.takeUnless(String::isBlank),
                isSelected = track.selected,
            )
        }

    override suspend fun getChapters(): List<PlayerChapter> {
        if (!PlayerSettingsRepository.uiState.value.androidExoChaptersEnabled) return emptyList()
        val current = handle.takeIf { it != 0L } ?: return emptyList()
        val count = backend.stringProperty(current, "chapter-list/count").toIntOrNull() ?: return emptyList()
        return (0 until count).mapNotNull { index ->
            val seconds = backend.stringProperty(current, "chapter-list/$index/time").toDoubleOrNull()
                ?: return@mapNotNull null
            PlayerChapter(
                startMs = (seconds * 1000.0).toLong(),
                title = backend.stringProperty(current, "chapter-list/$index/title").takeIf { it.isNotBlank() },
            )
        }
    }

    override suspend fun getMediaInfo(): PlayerMediaInfo {
        val current = handle.takeIf { it != 0L } ?: return PlayerMediaInfo()
        return mpvMediaInfo { name -> backend.stringProperty(current, name).takeIf { it.isNotBlank() } }
    }

    override fun getSubtitleTracks(): List<SubtitleTrack> =
        decodeTracks { backend.subtitleTracksJson(it) }.map { track ->
            SubtitleTrack(
                index = track.index,
                id = track.id,
                label = track.label,
                language = track.language.takeUnless(String::isBlank),
                isSelected = track.selected,
                isForced = track.forced || inferForcedSubtitleTrack(
                    label = track.label,
                    language = track.language,
                    trackId = track.id,
                ),
            )
        }

    override fun applyAudioLanguagePreferences(languages: List<String>) {
        handle.takeIf { it != 0L }?.let { backend.setAudioLanguages(it, languages.joinToString(",")) }
        val trackIndex = findPreferredAudioTrackIndex(getAudioTracks(), languages)
        if (trackIndex >= 0) selectAudioTrack(trackIndex)
    }

    override fun selectAudioTrack(index: Int) {
        val current = handle.takeIf { it != 0L } ?: return
        val tracks = decodeTracks { backend.audioTracksJson(it) }
        val trackId = resolveTrackId(index, tracks) ?: run {
            log.w { "selectAudioTrack missing track index=$index count=${tracks.size} handle=$current" }
            return
        }
        log.d { "selectAudioTrack index=$index trackId=$trackId count=${tracks.size} handle=$current" }
        backend.selectAudioTrack(current, trackId)
    }

    override fun setOnExternalSubtitleLoadFailed(listener: ((String) -> Unit)?) {
        onExternalSubtitleLoadFailed = listener
    }

    override fun selectSubtitleTrack(index: Int) {
        cancelInFlightSubtitleLoad()
        val current = handle.takeIf { it != 0L } ?: return
        if (index < 0) {
            log.d { "selectSubtitleTrack off handle=$current" }
            subtitleOps.selectSubtitleTrack(current, -1)
            return
        }
        val tracks = decodeTracks { subtitleOps.subtitleTracksJson(it) }
        val trackId = resolveTrackId(index, tracks) ?: run {
            log.w { "selectSubtitleTrack missing track index=$index count=${tracks.size} handle=$current" }
            return
        }
        log.d { "selectSubtitleTrack index=$index trackId=$trackId count=${tracks.size} handle=$current" }
        subtitleOps.selectSubtitleTrack(current, trackId)
        applyPendingSubtitleSettings()
    }

    override fun setSubtitleUri(url: String) {
        val currentHandle = handle.takeIf { it != 0L } ?: return
        val generation = subtitleRequestGeneration.incrementAndGet()
        activeSubtitleJob?.cancel()
        val subtitleMeta = pendingSource?.externalSubtitles?.firstOrNull { it.url == url }
        val targetPathIfLocal = url.takeIf { it.isLocalSubtitleUri() }
            ?.let(resolveLocalFilePath)
        if (targetPathIfLocal != null && targetPathIfLocal == startupSelectedLocalPath) {
            val existingTracks = decodeTracks { subtitleOps.subtitleTracksJson(it) }
            val matchingIndex = existingTracks.indexOfFirst { track ->
                subtitleMeta?.name?.let { name -> track.label.contains(name, ignoreCase = true) } ?: false
            }.takeIf { it >= 0 } ?: existingTracks.lastIndex.takeIf { it >= 0 }
            if (matchingIndex != null) {
                val trackId = resolveTrackId(matchingIndex, existingTracks)
                if (trackId != null) {
                    subtitleOps.selectSubtitleTrack(currentHandle, trackId)
                    applyPendingSubtitleSettings()
                    return
                }
            }
        }
        log.d { "setSubtitleUri async resolve ${url.toPlaybackLogKey()} handle=$currentHandle" }
        activeSubtitleJob = subtitleScope.launch {
            val localUri = resolvePlayableLocalSubtitle(
                url,
                subtitleMeta?.headers.orEmpty(),
                subtitleMeta?.language,
            )
            val localPath = localUri?.let(resolveLocalFilePath)
            if (subtitleRequestGeneration.get() != generation || releaseRequested) return@launch
            val activeHandle = handle
            if (activeHandle == 0L || activeHandle != currentHandle) return@launch

            if (localPath != null) {
                startupSelectedLocalPath = localPath
                subtitleOps.clearExternalSubtitles(activeHandle)
                subtitleOps.addSubtitleTrack(
                    handle = activeHandle,
                    url = localPath,
                    title = subtitleMeta?.name.orEmpty(),
                    language = subtitleMeta?.language.orEmpty(),
                    select = true,
                )
            } else {
                log.w { "setSubtitleUri timed out or failed validation; falling back to none for ${url.toPlaybackLogKey()}" }
                startupSelectedLocalPath = null
                subtitleOps.clearExternalSubtitles(activeHandle)
                SwingUtilities.invokeLater {
                    if (!releaseRequested && subtitleRequestGeneration.get() == generation) {
                        onExternalSubtitleLoadFailed?.invoke(url)
                    }
                }
            }
        }
    }

    override fun clearExternalSubtitle() {
        cancelInFlightSubtitleLoad()
        log.d { "clearExternalSubtitle handle=$handle" }
        handle.takeIf { it != 0L }?.let(subtitleOps::clearExternalSubtitles)
    }

    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        cancelInFlightSubtitleLoad()
        val current = handle.takeIf { it != 0L } ?: return
        val trackId = if (trackIndex < 0) {
            -1
        } else {
            val tracks = decodeTracks { subtitleOps.subtitleTracksJson(it) }
            resolveTrackId(trackIndex, tracks) ?: run {
                log.w { "clearExternalSubtitleAndSelect missing track index=$trackIndex count=${tracks.size} handle=$current" }
                return
            }
        }
        log.d { "clearExternalSubtitleAndSelect trackIndex=$trackIndex trackId=$trackId handle=$current" }
        subtitleOps.clearExternalSubtitlesAndSelect(current, trackId)
        applyPendingSubtitleSettings()
    }

    internal fun applyStartupSubtitlesIfNeeded(
        sourceUrl: String,
        externalSubtitles: List<StreamSubtitle>,
        startupSubtitleSelection: StartupSubtitleSelection?,
    ) {
        val current = handle.takeIf { it != 0L } ?: return
        val pending = pendingSource ?: return
        if (pending.sourceUrl != sourceUrl) return
        if (startupConfiguredForSource == sourceUrl &&
            pending.externalSubtitles == externalSubtitles &&
            pending.startupSubtitleSelection == startupSubtitleSelection
        ) {
            return
        }
        // The attachment keeps its identity: events and the certificate prompt compare against it.
        pending.startupSubtitles = StartupSubtitles(externalSubtitles, startupSubtitleSelection)
        if (externalSubtitles.any { it.url.isLocalSubtitleUri() }) {
            subtitleOps.clearExternalSubtitles(current)
            configureStartupSubtitlesSafe(current, pending)
        } else {
            triggerFallbackRemoteStartupSubtitleIfNeeded(pending)
        }
    }

    private fun cancelInFlightSubtitleLoad() {
        subtitleRequestGeneration.incrementAndGet()
        activeSubtitleJob?.cancel()
        activeSubtitleJob = null
    }

    private fun configureStartupSubtitlesSafe(nativeHandle: Long, pending: PendingSource) {
        val localEntries = pending.externalSubtitles.mapNotNull { subtitle ->
            if (!subtitle.url.isLocalSubtitleUri()) return@mapNotNull null
            val path = resolveLocalFilePath(subtitle.url) ?: return@mapNotNull null
            StartupSubtitleEntry(
                originalUrl = subtitle.url,
                localPath = path,
                title = subtitle.name?.takeIf { it.isNotBlank() } ?: subtitle.language,
                language = subtitle.language,
            )
        }
        val selection = pending.startupSubtitleSelection
        val selectedIndex = when {
            localEntries.isEmpty() -> -1
            selection != null -> selection.selectedUrl?.let { chosen ->
                localEntries.indexOfFirst { it.originalUrl == chosen }
            } ?: -1
            else -> 0
        }
        val disableAutoSelect = selection?.disableAutoSelect == true || selection?.exhaustedOrTimedOut == true
        startupConfiguredForSource = pending.sourceUrl
        startupLoadedLocalPaths = localEntries.mapTo(LinkedHashSet()) { it.localPath }
        startupSelectedLocalPath = localEntries.getOrNull(selectedIndex)?.localPath

        if (localEntries.isEmpty() && !disableAutoSelect) return

        val urls = localEntries.map { it.localPath }.toTypedArray()
        val titles = localEntries.map { it.title }.toTypedArray()
        val languages = localEntries.map { it.language }.toTypedArray()
        subtitleOps.configureStartupSubtitles(
            handle = nativeHandle,
            urls = urls,
            titles = titles,
            languages = languages,
            selectedIndex = selectedIndex,
            disableAutoSelect = disableAutoSelect,
        )
    }

    private fun triggerFallbackRemoteStartupSubtitleIfNeeded(pending: PendingSource) {
        if (pending.startupSubtitleSelection?.disableAutoSelect == true ||
            pending.startupSubtitleSelection?.exhaustedOrTimedOut == true
        ) {
            return
        }
        if (pending.externalSubtitles.any { it.url.isLocalSubtitleUri() }) return
        val remoteCandidate = pending.externalSubtitles.firstOrNull { !it.url.isLocalSubtitleUri() } ?: return
        setSubtitleUri(remoteCandidate.url)
    }

    private data class StartupSubtitleEntry(
        val originalUrl: String,
        val localPath: String,
        val title: String,
        val language: String,
    )

    override fun setSubtitleVisible(visible: Boolean) {
        handle.takeIf { it != 0L }?.let { backend.setSubtitleVisible(it, visible) }
    }

    override fun setSubtitleDelayMs(delayMs: Int) {
        val clamped = delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
        pendingSubtitleDelayMs = clamped
        handle.takeIf { it != 0L }?.let { current ->
            backend.setSubtitleDelayMs(current, clamped)
        }
    }

    override fun applySubtitleStyle(style: SubtitleStyleState, useLibass: Boolean) {
        pendingSubtitleStyle = style
        pendingUseLibass = useLibass
        handle.takeIf { it != 0L }?.let { current ->
            applySubtitleStyle(current, style, useLibass)
        }
    }

    private fun applyPendingSubtitleSettings() {
        val current = handle.takeIf { it != 0L } ?: return
        pendingSubtitleDelayMs?.let { delayMs ->
            backend.setSubtitleDelayMs(current, delayMs)
        }
        pendingSubtitleStyle?.let { style ->
            applySubtitleStyle(current, style, pendingUseLibass)
        }
    }

    private fun applySubtitleStyle(handle: Long, style: SubtitleStyleState, useLibass: Boolean) {
        val shadow = style.toMpvSubtitleShadow()
        backend.applySubtitleStyle(
            handle = handle,
            textColor = style.textColor.toMpvColorString(),
            backgroundColor = style.backgroundColor.toMpvColorString(),
            outlineColor = shadow.outlineColor,
            outlineSize = shadow.outlineSize,
            bold = style.bold,
            fontSize = style.toMpvSubtitleFontSize(),
            subPos = style.toMpvSubtitlePosition(),
            useLibass = useLibass,
            stripSdh = style.stripSdh,
        )
        backend.setSubtitleAppearance(
            handle = handle,
            font = SubtitleFontResolver.mpvFontName(style.appearance.fontId),
            fontsDir = SubtitleFontResolver.fontsDir(),
            shadowColor = shadow.color,
            shadowOffset = shadow.offset,
            shadowBlur = shadow.blur,
        )
    }

    private fun decodeTracks(readJson: (Long) -> String): List<NativeMpvTrack> {
        val current = handle.takeIf { it != 0L } ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<NativeMpvTrack>>(readJson(current))
        }.getOrDefault(emptyList())
    }
}

private fun String.toPlaybackLogKey(): String {
    val scheme = substringBefore(':', missingDelimiterValue = "unknown")
        .takeIf { it.isNotBlank() }
        ?: "unknown"
    return "scheme=$scheme length=$length hash=${hashCode()}"
}

private fun String.shouldLogNativeControlEvent(): Boolean {
    val normalized = lowercase()
    return normalized.contains("audio") ||
        normalized.contains("subtitle") ||
        normalized.contains("speed") ||
        normalized.contains("scrub") ||
        normalized.contains("seek") ||
        normalized.contains("episode") ||
        normalized == "resize" ||
        normalized == "toggle"
}

@Serializable
private data class NativeMpvTrack(
    val index: Int = 0,
    val id: String = "",
    val label: String = "",
    val language: String = "",
    val selected: Boolean = false,
    val forced: Boolean = false,
)

private fun resolveTrackId(index: Int, tracks: List<NativeMpvTrack>): Int? =
    tracks.firstNotNullOfOrNull { track ->
        if (track.index == index) {
            track.id.toIntOrNull()
        } else {
            null
        }
    } ?: tracks.getOrNull(index)?.id?.toIntOrNull()

private fun Color.toMpvColorString(): String {
    val alphaInt = (alpha * 255f).toInt().coerceIn(0, 255)
    val redInt = (red * 255f).toInt().coerceIn(0, 255)
    val greenInt = (green * 255f).toInt().coerceIn(0, 255)
    val blueInt = (blue * 255f).toInt().coerceIn(0, 255)
    return buildString {
        append('#')
        append(alphaInt.toHexByte())
        append(redInt.toHexByte())
        append(greenInt.toHexByte())
        append(blueInt.toHexByte())
    }
}

private fun SubtitleStyleState.toMpvSubtitlePosition(): Int =
    (100 - (bottomOffset / 2)).coerceIn(0, 150)

private const val OUTLINED_SHADOW_OPACITY_SCALE = 0.47f

private data class MpvSubtitleShadow(
    val color: String,
    val offset: Float,
    val blur: Float,
    val outlineColor: String,
    val outlineSize: Float,
)

private fun SubtitleStyleState.toMpvSubtitleShadow(): MpvSubtitleShadow {
    val outlineSize = if (outlineEnabled) outlineWidth.toFloat() else 0f
    val outlineColor = outlineColor.toMpvColorString()
    if (!appearance.shadowEnabled) {
        return MpvSubtitleShadow(
            color = "#00000000",
            offset = 0f,
            blur = 0f,
            outlineColor = outlineColor,
            outlineSize = outlineSize,
        )
    }
    val softened = backgroundColor.alpha == 0f
    val needsGlyphGuard = softened && outlineSize == 0f
    val opacityScale = if (softened && !needsGlyphGuard) OUTLINED_SHADOW_OPACITY_SCALE else 1f
    val shadowAlpha = (appearance.shadowOpacity * 255 / 100 * opacityScale).roundToInt().coerceIn(0, 255)
    return MpvSubtitleShadow(
        color = "#" + shadowAlpha.toHexByte() + "000000",
        offset = appearance.shadowSize.toFloat(),
        blur = if (softened) (appearance.shadowSize * 0.5f + 1f).coerceIn(1.5f, 4f) else 0f,
        outlineColor = if (needsGlyphGuard) "#00000000" else outlineColor,
        outlineSize = if (needsGlyphGuard) 1f else outlineSize,
    )
}

private fun SubtitleStyleState.toMpvSubtitleFontSize(): Float =
    (fontSizeSp * 3f).coerceIn(18f, 96f)

private fun Int.toHexByte(): String {
    val digits = "0123456789ABCDEF"
    val value = coerceIn(0, 255)
    return buildString {
        append(digits[value / 16])
        append(digits[value % 16])
    }
}

private class StartupSubtitles(
    val externalSubtitles: List<StreamSubtitle>,
    val selection: StartupSubtitleSelection?,
)

/**
 * One attachment. Native events, errors and the certificate prompt all check that the controller still
 * holds this same object, so it is never replaced: the startup subtitles are swapped in place when they
 * arrive late.
 */
private class PendingSource(
    val sourceUrl: String,
    val headerLines: List<String>,
    val playWhenReady: Boolean,
    val initialPositionMs: Long,
    val decoderPriority: Int,
    val nvidiaRtxSuperResolutionEnabled: Boolean,
    val nvidiaRtxHdrEnabled: Boolean,
    externalSubtitles: List<StreamSubtitle> = emptyList(),
    startupSubtitleSelection: StartupSubtitleSelection? = null,
    val onError: (String?) -> Unit,
) {
    @Volatile
    var startupSubtitles: StartupSubtitles = StartupSubtitles(externalSubtitles, startupSubtitleSelection)

    val externalSubtitles: List<StreamSubtitle> get() = startupSubtitles.externalSubtitles
    val startupSubtitleSelection: StartupSubtitleSelection? get() = startupSubtitles.selection
}

internal fun Map<String, String>.toHeaderLines(): List<String> =
    entries.mapNotNull { (key, value) ->
        require(key.none { it == '\r' || it == '\n' } && value.none { it == '\r' || it == '\n' }) {
            kotlinx.coroutines.runBlocking { org.jetbrains.compose.resources.getString(nuvio.composeapp.generated.resources.Res.string.playback_invalid_headers) }
        }
        val cleanKey = key.trim()
        val cleanValue = value.trim()
        if (cleanKey.isBlank() || cleanValue.isBlank()) {
            null
        } else {
            "$cleanKey: $cleanValue"
        }
    }

private fun List<String>.toHeaderMap(): Map<String, String> =
    mapNotNull { line ->
        val separator = line.indexOf(':')
        if (separator <= 0) return@mapNotNull null
        line.substring(0, separator).trim() to line.substring(separator + 1).trim()
    }.toMap()

private fun String.toPlayerControlsAction(): PlayerControlsAction? =
    when (this) {
        "toggleChrome" -> PlayerControlsAction.ToggleChrome
        "back" -> PlayerControlsAction.Back
        "minimize" -> PlayerControlsAction.Minimize
        "close" -> PlayerControlsAction.Close
        "toggle" -> PlayerControlsAction.TogglePlayback
        "keyboardToggle" -> PlayerControlsAction.KeyboardTogglePlayback
        "seekBack" -> PlayerControlsAction.SeekBack
        "keyboardSeekBack" -> PlayerControlsAction.KeyboardSeekBack
        "seekForward" -> PlayerControlsAction.SeekForward
        "keyboardSeekForward" -> PlayerControlsAction.KeyboardSeekForward
        "keyboardVolumeDown" -> PlayerControlsAction.KeyboardVolumeDown
        "keyboardVolumeUp" -> PlayerControlsAction.KeyboardVolumeUp
        "pictureInPicture", "pip" -> PlayerControlsAction.PictureInPicture
        "resize" -> PlayerControlsAction.ResizeMode
        "speed" -> PlayerControlsAction.Speed
        "subtitles" -> PlayerControlsAction.Subtitles
        "audio" -> PlayerControlsAction.Audio
        "sources" -> PlayerControlsAction.Sources
        "episodes" -> PlayerControlsAction.Episodes
        "external" -> PlayerControlsAction.OpenExternalPlayer
        "submitIntro" -> PlayerControlsAction.SubmitIntro
        "videoSettings" -> PlayerControlsAction.VideoSettings
        else -> null
    }

private data class NativeControlsStructureKey(
    val state: PlayerControlsState,
    val isFullscreen: Boolean,
    val isInPip: Boolean,
)

private fun PlayerControlsState.toControlsJson(isFullscreen: Boolean, onImageReady: () -> Unit): String =
    buildString {
        append('{')
        appendJsonField("title", title)
        append(',')
        appendJsonField("episodeText", episodeText)
        append(',')
        appendJsonField("streamTitle", streamTitle)
        append(',')
        appendJsonField("providerName", providerName)
        append(',')
        appendJsonField("pauseOverlayEnabled", pauseOverlayEnabled)
        append(',')
        appendJsonField("pauseOverlayWatchingLabel", pauseOverlayWatchingLabel)
        append(',')
        appendJsonField("pauseOverlayLogo", WebviewImages.resolve(pauseOverlayLogo.orEmpty(), onImageReady))
        append(',')
        appendJsonField("pauseOverlayEpisodeInfo", pauseOverlayEpisodeInfo)
        append(',')
        appendJsonField("pauseOverlayEpisodeTitle", pauseOverlayEpisodeTitle)
        append(',')
        appendJsonField("pauseOverlayDescription", pauseOverlayDescription)
        append(',')
        appendJsonField("resizeModeLabel", resizeModeLabel)
        append(',')
        appendJsonField("playbackSpeedLabel", playbackSpeedLabel)
        append(',')
        appendJsonField("holdToSpeedEnabled", holdToSpeedEnabled)
        append(',')
        appendJsonField("holdToSpeedValue", holdToSpeedValue)
        append(',')
        appendJsonField("isFullscreen", isFullscreen)
        append(',')
        appendJsonField("volumeLevel", volumeLevel)
        append(',')
        appendJsonField("subtitlesLabel", subtitlesLabel)
        append(',')
        appendJsonField("audioLabel", audioLabel)
        append(',')
        appendJsonField("sourcesLabel", sourcesLabel)
        append(',')
        appendJsonField("availableOfflineLabel", availableOfflineLabel)
        append(',')
        appendJsonField("episodesLabel", episodesLabel)
        append(',')
        appendJsonField("externalPlayerLabel", externalPlayerLabel)
        append(',')
        appendJsonField("playLabel", playLabel)
        append(',')
        appendJsonField("pauseLabel", pauseLabel)
        append(',')
        appendJsonField("closeLabel", closeLabel)
        append(',')
        appendJsonField("minimizeLabel", minimizeLabel)
        append(',')
        appendJsonField("isInMiniPlayer", isInMiniPlayer)
        append(',')
        appendJsonField("mutedLabel", mutedLabel)
        append(',')
        appendJsonField("volumeLevelLabelFormat", volumeLevelLabelFormat)
        append(',')
        appendJsonField("submitIntroLabel", submitIntroLabel)
        append(',')
        appendJsonField("videoSettingsLabel", videoSettingsLabel)
        append(',')
        appendJsonField("playbackErrorTitle", playbackErrorTitle)
        append(',')
        appendJsonField("playbackErrorMessage", playbackErrorMessage)
        append(',')
        appendJsonField("playbackErrorActionLabel", playbackErrorActionLabel)
        append(',')
        appendJsonField("sourcesPanelTitle", sourcesPanelTitle)
        append(',')
        appendJsonField("episodesPanelTitle", episodesPanelTitle)
        append(',')
        appendJsonField("streamsPanelTitle", streamsPanelTitle)
        append(',')
        appendJsonField("allFilterLabel", allFilterLabel)
        append(',')
        appendJsonField("reloadLabel", reloadLabel)
        append(',')
        appendJsonField("backLabel", backLabel)
        append(',')
        appendJsonField("panelCloseLabel", panelCloseLabel)
        append(',')
        appendJsonField("cancelLabel", cancelLabel)
        append(',')
        appendJsonField("playingLabel", playingLabel)
        append(',')
        appendJsonField("noStreamsLabel", noStreamsLabel)
        append(',')
        appendJsonField("noEpisodesLabel", noEpisodesLabel)
        append(',')
        appendJsonField("submitIntroPanelTitle", submitIntroPanelTitle)
        append(',')
        appendJsonField("submitIntroSegmentTypeLabel", submitIntroSegmentTypeLabel)
        append(',')
        appendJsonField("submitIntroSegmentIntroLabel", submitIntroSegmentIntroLabel)
        append(',')
        appendJsonField("submitIntroSegmentRecapLabel", submitIntroSegmentRecapLabel)
        append(',')
        appendJsonField("submitIntroSegmentOutroLabel", submitIntroSegmentOutroLabel)
        append(',')
        appendJsonField("submitIntroStartTimeLabel", submitIntroStartTimeLabel)
        append(',')
        appendJsonField("submitIntroEndTimeLabel", submitIntroEndTimeLabel)
        append(',')
        appendJsonField("submitIntroCaptureLabel", submitIntroCaptureLabel)
        append(',')
        appendJsonField("submitIntroSubmitLabel", submitIntroSubmitLabel)
        append(',')
        appendJsonField("p2pConsentTitle", p2pConsentTitle)
        append(',')
        appendJsonField("p2pConsentBody", p2pConsentBody)
        append(',')
        appendJsonField("p2pConsentEnableLabel", p2pConsentEnableLabel)
        append(',')
        appendJsonField("p2pConsentCancelLabel", p2pConsentCancelLabel)
        append(',')
        appendJsonField("speedPanelTitle", speedPanelTitle)
        append(',')
        appendJsonField("audioTracksPanelTitle", audioTracksPanelTitle)
        append(',')
        appendJsonField("noAudioTracksLabel", noAudioTracksLabel)
        append(',')
        appendJsonField("subtitlesPanelTitle", subtitlesPanelTitle)
        append(',')
        appendJsonField("subtitleLanguagesLabel", subtitleLanguagesLabel)
        append(',')
        appendJsonField("subtitleBuiltInTabLabel", subtitleBuiltInTabLabel)
        append(',')
        appendJsonField("subtitleAddonsTabLabel", subtitleAddonsTabLabel)
        append(',')
        appendJsonField("subtitleStyleTabLabel", subtitleStyleTabLabel)
        append(',')
        appendJsonField("forcedLabel", forcedLabel)
        append(',')
        appendJsonField("noneLabel", noneLabel)
        append(',')
        appendJsonField("fetchSubtitlesLabel", fetchSubtitlesLabel)
        append(',')
        appendJsonField("subtitleDelayLabel", subtitleDelayLabel)
        append(',')
        appendJsonField("resetLabel", resetLabel)
        append(',')
        appendJsonField("autoSyncLabel", autoSyncLabel)
        append(',')
        appendJsonField("reloadSmallLabel", reloadSmallLabel)
        append(',')
        appendJsonField("captureLineLabel", captureLineLabel)
        append(',')
        appendJsonField("selectAddonSubtitleFirstLabel", selectAddonSubtitleFirstLabel)
        append(',')
        appendJsonField("loadingSubtitleLinesLabel", loadingSubtitleLinesLabel)
        append(',')
        appendJsonField("fontSizeLabel", fontSizeLabel)
        append(',')
        appendJsonField("outlineLabel", outlineLabel)
        append(',')
        appendJsonField("boldLabel", boldLabel)
        append(',')
        appendJsonField("bottomOffsetLabel", bottomOffsetLabel)
        append(',')
        appendJsonField("colorLabel", colorLabel)
        append(',')
        appendJsonField("textOpacityLabel", textOpacityLabel)
        append(',')
        appendJsonField("outlineColorLabel", outlineColorLabel)
        append(',')
        appendJsonField("fontLabel", fontLabel)
        append(',')
        appendJsonField("backgroundColorLabel", backgroundColorLabel)
        append(',')
        appendJsonField("backgroundOpacityLabel", backgroundOpacityLabel)
        append(',')
        appendJsonField("fontImportLabel", fontImportLabel)
        append(',')
        appendJsonField("fontImportHint", fontImportHint)
        append(',')
        appendJsonField("fontImportError", fontImportError)
        append(',')
        appendJsonField("doneLabel", doneLabel)
        append(',')
        appendJsonField("fontRemoveLabel", fontRemoveLabel)
        append(',')
        appendJsonField("subtitleFontImportFailed", subtitleFontImportFailed)
        append(',')
        appendJsonField("shadowLabel", shadowLabel)
        append(',')
        appendJsonField("shadowOpacityLabel", shadowOpacityLabel)
        append(',')
        appendJsonField("shadowSizeLabel", shadowSizeLabel)
        append(',')
        appendJsonArrayField("subtitleFonts", subtitleFonts) {
            append("{\"id\":").append(it.id.toJsonString())
            append(",\"label\":").append(it.label.toJsonString())
            append('}')
        }
        append(',')
        appendJsonField("noSubtitleLinesFoundLabel", noSubtitleLinesFoundLabel)
        append(',')
        appendJsonField("resetDefaultsLabel", resetDefaultsLabel)
        append(',')
        appendJsonField("onLabel", onLabel)
        append(',')
        appendJsonField("offLabel", offLabel)
        append(',')
        appendJsonField("themeAccentColor", themeAccentColor)
        append(',')
        appendJsonArrayField("themeAccentGradientColors", themeAccentGradientColors) { append(it.toJsonString()) }
        append(',')
        appendJsonField("themeAccentStrongColor", themeAccentStrongColor)
        append(',')
        appendJsonField("themeOnAccentColor", themeOnAccentColor)
        append(',')
        appendJsonField("themeFocusColor", themeFocusColor)
        append(',')
        appendJsonField("themeSelectedSurfaceColor", themeSelectedSurfaceColor)
        append(',')
        appendJsonField("themeSelectedSurfaceHoverColor", themeSelectedSurfaceHoverColor)
        append(',')
        appendJsonField("themeSelectedRingColor", themeSelectedRingColor)
        append(',')
        appendJsonField("themeTimelineFillColor", themeTimelineFillColor)
        append(',')
        appendJsonField("themeTimelineTrackColor", themeTimelineTrackColor)
        append(',')
        appendJsonField("themeBufferingColor", themeBufferingColor)
        append(',')
        appendJsonField("themeBufferingTrackColor", themeBufferingTrackColor)
        append(',')
        appendJsonField("themeControlForegroundColor", themeControlForegroundColor)
        append(',')
        appendJsonField("themeSurfaceElevatedColor", themeSurfaceElevatedColor)
        append(',')
        appendJsonField("themeSurfaceCardColor", themeSurfaceCardColor)
        append(',')
        appendJsonField("themeSurfacePopoverColor", themeSurfacePopoverColor)
        append(',')
        appendJsonField("themeTextPrimaryColor", themeTextPrimaryColor)
        append(',')
        appendJsonField("themeTextSecondaryColor", themeTextSecondaryColor)
        append(',')
        appendJsonField("themeTextMutedColor", themeTextMutedColor)
        append(',')
        appendJsonField("themeBorderDefaultColor", themeBorderDefaultColor)
        append(',')
        appendJsonField("themeDangerColor", themeDangerColor)
        append(',')
        appendJsonField("isPlaying", isPlaying)
        append(',')
        appendJsonField("isLoading", isLoading)
        append(',')
        appendJsonField(
            "pipLabel",
            if (DesktopHostOs.current == DesktopHostOs.WINDOWS ||
                DesktopHostOs.current == DesktopHostOs.MACOS
            ) {
                if (DesktopPlayerPictureInPicture.isEnabled && pipRestoreLabel.isNotBlank()) {
                    pipRestoreLabel
                } else {
                    pipLabel
                }
            } else {
                ""
            },
        )
        append(',')
        appendJsonField("lockLabel", lockLabel)
        append(',')
        appendJsonField("unlockLabel", unlockLabel)
        append(',')
        appendJsonField("isInPip", DesktopPlayerPictureInPicture.isEnabled)
        append(',')
        appendJsonField("isLocked", isLocked)
        append(',')
        appendJsonField("controlsVisible", controlsVisible)
        append(',')
        appendJsonArrayField("parentalWarnings", parentalWarnings) { appendParentalWarningJson(it) }
        append(',')
        appendJsonField("showParentalGuide", showParentalGuide)
        append(',')
        appendJsonField("showOpeningOverlay", showOpeningOverlay)
        append(',')
        appendJsonField("openingArtwork", WebviewImages.resolve(openingArtwork.orEmpty(), onImageReady))
        append(',')
        appendJsonField("openingLogo", WebviewImages.resolve(openingLogo.orEmpty(), onImageReady))
        append(',')
        appendJsonField("openingTitle", openingTitle)
        append(',')
        appendJsonField("openingMessage", openingMessage.orEmpty())
        append(',')
        appendJsonField("openingProgress", openingProgress)
        append(',')
        appendJsonField("skipPromptVisible", skipPromptVisible)
        append(',')
        appendJsonField("skipPromptLabel", skipPromptLabel)
        append(',')
        appendJsonField("skipPromptStartMs", skipPromptStartMs)
        append(',')
        appendJsonField("skipPromptEndMs", skipPromptEndMs)
        append(',')
        appendJsonField("skipPromptDismissed", skipPromptDismissed)
        append(',')
        appendJsonField("nextEpisodeVisible", nextEpisodeVisible)
        append(',')
        appendJsonField("nextEpisodeHeaderLabel", nextEpisodeHeaderLabel)
        append(',')
        appendJsonField("nextEpisodeTitle", nextEpisodeTitle)
        append(',')
        appendJsonField("nextEpisodeThumbnail", WebviewImages.resolve(nextEpisodeThumbnail, onImageReady))
        append(',')
        appendJsonField("nextEpisodeThumbnailBlurred", nextEpisodeThumbnailBlurred)
        append(',')
        appendJsonField("nextEpisodeStatus", nextEpisodeStatus)
        append(',')
        appendJsonField("nextEpisodeActionLabel", nextEpisodeActionLabel)
        append(',')
        appendJsonField("nextEpisodePlayable", nextEpisodePlayable)
        append(',')
        appendJsonField("showSubmitIntro", showSubmitIntro)
        append(',')
        appendJsonField("showVideoSettings", showVideoSettings)
        append(',')
        appendJsonField("showSources", showSources)
        append(',')
        appendJsonField("showEpisodes", showEpisodes)
        append(',')
        appendJsonField("showExternalPlayer", showExternalPlayer)
        append(',')
        appendJsonField("durationMs", durationMs)
        append(',')
        appendJsonField("positionMs", positionMs)
        append(',')
        appendJsonField("bufferedPositionMs", bufferedPositionMs)
        append(',')
        appendJsonField("sourceIsLoading", sourceIsLoading)
        append(',')
        appendJsonArrayField("sourceFilters", sourceFilters) { appendFilterItemJson(it) }
        append(',')
        appendJsonArrayField("sourceItems", sourceItems) { appendSourceItemJson(it, onImageReady) }
        append(',')
        appendJsonArrayField("episodeItems", episodeItems) { appendEpisodeItemJson(it, onImageReady) }
        append(',')
        appendJsonArrayField("episodeSeasons", episodeSeasons) { appendSeasonItemJson(it) }
        append(',')
        appendJsonField("episodeStreamsVisible", episodeStreamsVisible)
        append(',')
        appendJsonField("episodeStreamsIsLoading", episodeStreamsIsLoading)
        append(',')
        appendJsonField("selectedEpisodeLabel", selectedEpisodeLabel)
        append(',')
        appendJsonArrayField("episodeStreamFilters", episodeStreamFilters) { appendFilterItemJson(it) }
        append(',')
        appendJsonArrayField("episodeStreamItems", episodeStreamItems) { appendSourceItemJson(it, onImageReady) }
        append(',')
        appendJsonField("blurUnwatchedEpisodes", blurUnwatchedEpisodes)
        append(',')
        appendJsonField("submitIntroSegmentType", submitIntroSegmentType)
        append(',')
        appendJsonField("submitIntroContentKey", submitIntroContentKey)
        append(',')
        appendJsonField("submitIntroStartTime", submitIntroStartTime)
        append(',')
        appendJsonField("submitIntroEndTime", submitIntroEndTime)
        append(',')
        appendJsonField("isSubmitIntroSubmitting", isSubmitIntroSubmitting)
        append(',')
        appendJsonField("submitIntroStatusMessage", submitIntroStatusMessage)
        append(',')
        appendJsonField("showP2pConsent", showP2pConsent)
        append(',')
        appendJsonField("subtitleActiveTab", subtitleActiveTab)
        append(',')
        appendJsonArrayField("subtitleLanguageItems", subtitleLanguageItems) { appendSubtitleLanguageItemJson(it) }
        append(',')
        appendJsonArrayField("subtitleOptionItems", subtitleOptionItems) { appendSubtitleOptionItemJson(it) }
        append(',')
        appendJsonField("selectedSubtitleLanguageKey", selectedSubtitleLanguageKey)
        append(',')
        appendJsonField("selectedSubtitleOptionId", selectedSubtitleOptionId)
        append(',')
        appendJsonArrayField("addonSubtitleItems", addonSubtitleItems) { appendAddonSubtitleItemJson(it) }
        append(',')
        appendJsonField("isLoadingAddonSubtitles", isLoadingAddonSubtitles)
        append(',')
        appendJsonField("selectedAddonSubtitleId", selectedAddonSubtitleId)
        append(',')
        appendJsonField("useCustomSubtitles", useCustomSubtitles)
        append(',')
        appendJsonField("subtitleDelayMs", subtitleDelayMs)
        append(',')
        appendJsonField("hasSelectedAddonSubtitle", hasSelectedAddonSubtitle)
        append(',')
        appendJsonField("subtitleAutoSyncCapturedPositionMs", subtitleAutoSyncCapturedPositionMs)
        append(',')
        appendJsonArrayField("subtitleAutoSyncCues", subtitleAutoSyncCues) { appendSubtitleCueItemJson(it) }
        append(',')
        appendJsonField("subtitleAutoSyncIsLoading", subtitleAutoSyncIsLoading)
        append(',')
        appendJsonField("subtitleAutoSyncErrorMessage", subtitleAutoSyncErrorMessage)
        append(',')
        appendJsonField("subtitleStyle", subtitleStyle)
        append(',')
        appendJsonArrayField("subtitleColorSwatches", SubtitleColorSwatches.map { it.toStorageHexString() }) { append(it.toJsonString()) }
        append(',')
        appendJsonArrayField("subtitleOutlineColorSwatches", SubtitleOutlineColorSwatches.map { it.toStorageHexString() }) { append(it.toJsonString()) }
        append(',')
        appendJsonField("closeModalsToken", closeModalsToken)
        append(',')
        appendJsonField("modalNavigationAction", modalNavigationAction)
        append(',')
        appendJsonField("modalNavigationToken", modalNavigationToken)
        append(',')
        appendJsonField("submitIntroSuccessToken", submitIntroSuccessToken)
        append(',')
        appendJsonField("notificationMessage", notificationMessage)
        append(',')
        appendJsonField("notificationToken", notificationToken)
        append(',')
        appendJsonArrayField("badges", badges) {
            append("{\"name\":").append(it.name.toJsonString())
            append(",\"imageUrl\":").append(it.imageURL.toJsonString())
            append(",\"tagColor\":").append(it.tagColor.toJsonString())
            append(",\"tagStyle\":").append(it.tagStyle.toJsonString())
            append(",\"borderColor\":").append(it.borderColor.toJsonString())
            append('}')
        }
        append(',')
        appendJsonArrayField("chapters", chapters) {
            append("{\"startMs\":").append(it.startMs)
            append(",\"title\":").append(it.title.toJsonString())
            append('}')
        }
        append(',')
        appendJsonField("chapterTitleEnabled", chapterTitleEnabled)
        append(',')
        appendJsonField("chaptersLabel", chaptersLabel)
        append(',')
        appendJsonField("chaptersPanelTitle", chaptersPanelTitle)
        append(',')
        appendJsonField("chapterRemainingTemplate", chapterRemainingTemplate)
        append(',')
        appendJsonField("autoSyncedLabel", autoSyncedLabel)
        append(',')
        appendJsonField("streamInfoLabel", streamInfoLabel)
        append(',')
        appendJsonField("shortcutsLabel", shortcutsLabel)
        append(',')
        appendJsonField("shortcutsGroupPlaybackLabel", shortcutsGroupPlaybackLabel)
        append(',')
        appendJsonField("shortcutsGroupNavigationLabel", shortcutsGroupNavigationLabel)
        append(',')
        val resolvedShortcutItems = shortcutsItems.ifEmpty {
            defaultDesktopPlayerShortcuts().toControlShortcutItems()
        }
        appendJsonArrayField("shortcutsItems", resolvedShortcutItems) { shortcut ->
            append("{\"keys\":").append(shortcut.keys.toJsonString())
            append(",\"label\":").append(shortcut.label.toJsonString())
            append(",\"group\":").append(shortcut.group.toJsonString())
            append('}')
        }
        append(',')
        appendJsonArrayField("streamInfoSections", streamInfoSections) { section ->
            append("{\"title\":").append(section.title.toJsonString())
            append(",\"logo\":").append((section.logo ?: "").toJsonString())
            append(",\"name\":").append((section.name ?: "").toJsonString())
            append(",\"subtitle\":").append((section.subtitle ?: "").toJsonString())
            append(",\"description\":").append((section.description ?: "").toJsonString())
            append(",\"items\":[")
            section.items.forEachIndexed { index, item ->
                if (index > 0) append(',')
                append("{\"label\":").append(item.label.toJsonString())
                append(",\"value\":").append(item.value.toJsonString())
                append('}')
            }
            append("]}")
        }
        append(',')
        appendJsonField("sleepTimerLabel", sleepTimerLabel)
        append(',')
        appendJsonField("sleepTimerPanelTitle", sleepTimerPanelTitle)
        append(',')
        appendJsonField("sleepTimerOffLabel", sleepTimerOffLabel)
        append(',')
        appendJsonField("sleepTimer15Label", sleepTimer15Label)
        append(',')
        appendJsonField("sleepTimer30Label", sleepTimer30Label)
        append(',')
        appendJsonField("sleepTimer45Label", sleepTimer45Label)
        append(',')
        appendJsonField("sleepTimer60Label", sleepTimer60Label)
        append(',')
        appendJsonField("sleepTimer90Label", sleepTimer90Label)
        append(',')
        appendJsonField("sleepTimerEndLabel", sleepTimerEndLabel)
        append(',')
        appendJsonField("sleepTimerStopAfterLabel", sleepTimerStopAfterLabel)
        append(',')
        appendJsonField("sleepTimerShortcutLabel", sleepTimerShortcutLabel)
        append(',')
        appendJsonField("sleepTimerOption", sleepTimerOption)
        append(',')
        appendJsonField("sleepTimerStopAfter", sleepTimerStopAfter)
        append(',')
        appendJsonField("sleepTimerRemainingLabel", sleepTimerRemainingLabel)
        append(',')
        appendJsonField("sleepTimerFading", sleepTimerFading)
        append(',')
        appendJsonField("sleepTimerActive", sleepTimerActive)
        append(",\"party\":")
        append(partyJson.encodeToString(PlayerControlPartyState.serializer(), party.withWebviewImages(onImageReady)))
        append('}')
    }

private val partyJson = Json { encodeDefaults = true }

private fun PlayerControlPartyState.withWebviewImages(onImageReady: () -> Unit): PlayerControlPartyState =
    copy(profile = profile.copy(imageUrl = if (active) "" else WebviewImages.resolve(profile.imageUrl, onImageReady)))

private fun PlayerControlsState.nativeControlsStructureKey(): PlayerControlsState =
    copy(
        isPlaying = false,
        isLoading = false,
        durationMs = 0L,
        positionMs = 0L,
        bufferedPositionMs = 0L,
    )

private fun StringBuilder.appendJsonField(name: String, value: String) {
    append('"').append(name).append("\":")
    append(value.toJsonString())
}

private fun StringBuilder.appendJsonField(name: String, value: Boolean) {
    append('"').append(name).append("\":").append(value)
}

private fun StringBuilder.appendJsonField(name: String, value: Long) {
    append('"').append(name).append("\":").append(value)
}

private fun StringBuilder.appendJsonField(name: String, value: Float?) {
    append('"').append(name).append("\":")
    if (value == null || value.isNaN() || value.isInfinite()) {
        append("null")
    } else if (name == "holdToSpeedValue") {
        append(value.coerceIn(1f, 4f))
    } else {
        append(value.coerceIn(0f, 1f))
    }
}

private fun StringBuilder.appendJsonField(name: String, value: Int) {
    append('"').append(name).append("\":").append(value)
}

private fun StringBuilder.appendJsonField(name: String, value: SubtitleStyleState) {
    append('"').append(name).append("\":")
    appendSubtitleStyleJson(value)
}

private inline fun <T> StringBuilder.appendJsonArrayField(
    name: String,
    values: List<T>,
    appendValue: StringBuilder.(T) -> Unit,
) {
    append('"').append(name).append("\":[")
    values.forEachIndexed { index, value ->
        if (index > 0) append(',')
        appendValue(value)
    }
    append(']')
}

private fun StringBuilder.appendFilterItemJson(item: PlayerControlFilterItem) {
    append('{')
    appendJsonField("id", item.id)
    append(',')
    appendJsonField("label", item.label)
    append(',')
    appendJsonField("isSelected", item.isSelected)
    append(',')
    appendJsonField("isLoading", item.isLoading)
    append(',')
    appendJsonField("hasError", item.hasError)
    append('}')
}

private fun StringBuilder.appendSeasonItemJson(item: PlayerControlSeasonItem) {
    append('{')
    appendJsonField("season", item.season)
    append(',')
    appendJsonField("label", item.label)
    append(',')
    appendJsonField("isSelected", item.isSelected)
    append('}')
}

private fun StringBuilder.appendSourceItemJson(item: PlayerControlSourceItem, onImageReady: () -> Unit) {
    append('{')
    appendJsonField("index", item.index)
    append(',')
    appendJsonField("filterId", item.filterId)
    append(',')
    appendJsonField("label", item.label)
    append(',')
    appendJsonField("subtitle", item.subtitle)
    append(',')
    appendJsonField("addonName", item.addonName)
    append(',')
    appendJsonField("addonLogo", WebviewImages.resolve(item.addonLogo, onImageReady))
    append(',')
    appendJsonField("showAddonLogo", item.showAddonLogo)
    append(',')
    appendJsonField("isCurrent", item.isCurrent)
    append(',')
    appendJsonField("isEnabled", item.isEnabled)
    append(',')
    appendJsonField("formattedSize", item.formattedSize)
    append(',')
    appendJsonField("badgePlacement", item.badgePlacement)
    append(',')
    appendJsonArrayField("badges", item.badges) { badge ->
        append('{')
        appendJsonField("name", badge.name)
        append(',')
        appendJsonField("imageURL", WebviewImages.resolve(badge.imageURL, onImageReady))
        append(',')
        appendJsonField("tagColor", badge.tagColor)
        append(',')
        appendJsonField("tagStyle", badge.tagStyle)
        append(',')
        appendJsonField("borderColor", badge.borderColor)
        append('}')
    }
    append('}')
}

private fun StringBuilder.appendEpisodeItemJson(item: PlayerControlEpisodeItem, onImageReady: () -> Unit) {
    append('{')
    appendJsonField("index", item.index)
    append(',')
    appendJsonField("id", item.id)
    append(',')
    appendJsonField("title", item.title)
    append(',')
    appendJsonField("code", item.code)
    append(',')
    appendJsonField("overview", item.overview)
    append(',')
    appendJsonField("thumbnail", WebviewImages.resolve(item.thumbnail, onImageReady))
    append(',')
    appendJsonField("released", item.released)
    append(',')
    appendJsonField("season", item.season)
    append(',')
    appendJsonField("episode", item.episode)
    append(',')
    appendJsonField("isCurrent", item.isCurrent)
    append(',')
    appendJsonField("isWatched", item.isWatched)
    append(',')
    appendJsonField("isAvailableOffline", item.isAvailableOffline)
    append(',')
    appendJsonField("isEnabled", item.isEnabled)
    append('}')
}

private fun StringBuilder.appendAddonSubtitleItemJson(item: PlayerControlAddonSubtitleItem) {
    append('{')
    appendJsonField("index", item.index)
    append(',')
    appendJsonField("id", item.id)
    append(',')
    appendJsonField("display", item.display)
    append(',')
    appendJsonField("language", item.language)
    append(',')
    appendJsonField("languageLabel", item.languageLabel)
    append(',')
    appendJsonField("addonName", item.addonName)
    append(',')
    appendJsonField("isSelected", item.isSelected)
    append('}')
}

private fun StringBuilder.appendSubtitleLanguageItemJson(item: PlayerControlSubtitleLanguageItem) {
    append('{')
    appendJsonField("key", item.key)
    append(',')
    appendJsonField("label", item.label)
    append(',')
    appendJsonField("count", item.count)
    append(',')
    appendJsonField("isSelected", item.isSelected)
    append('}')
}

private fun StringBuilder.appendSubtitleOptionItemJson(item: PlayerControlSubtitleOptionItem) {
    append('{')
    appendJsonField("id", item.id)
    append(',')
    appendJsonField("languageKey", item.languageKey)
    append(',')
    appendJsonField("kind", item.kind)
    append(',')
    appendJsonField("index", item.index)
    append(',')
    appendJsonField("sourceLabel", item.sourceLabel)
    append(',')
    appendJsonField("title", item.title)
    append(',')
    appendJsonField("metadata", item.metadata)
    append(',')
    appendJsonField("isSelected", item.isSelected)
    append(',')
    appendJsonField("isAutoSynced", item.isAutoSynced)
    append('}')
}

private fun StringBuilder.appendSubtitleCueItemJson(item: PlayerControlSubtitleCueItem) {
    append('{')
    appendJsonField("index", item.index)
    append(',')
    appendJsonField("timeMs", item.timeMs)
    append(',')
    appendJsonField("timeLabel", item.timeLabel)
    append(',')
    appendJsonField("text", item.text)
    append('}')
}

private fun StringBuilder.appendParentalWarningJson(item: ParentalWarning) {
    append('{')
    appendJsonField("label", item.label)
    append(',')
    appendJsonField("severity", item.severity)
    append('}')
}

private fun StringBuilder.appendSubtitleStyleJson(style: SubtitleStyleState) {
    append('{')
    appendJsonField("textColor", style.textColor.toStorageHexString())
    append(',')
    appendJsonField("outlineColor", style.outlineColor.toStorageHexString())
    append(',')
    appendJsonField("backgroundColor", style.backgroundColor.toStorageHexString())
    append(',')
    appendJsonField("outlineEnabled", style.outlineEnabled)
    append(',')
    appendJsonField("bold", style.bold)
    append(',')
    appendJsonField("fontSizeSp", style.fontSizeSp)
    append(',')
    appendJsonField("bottomOffset", style.bottomOffset)
    append(',')
    appendJsonField("fontId", style.appearance.fontId)
    append(',')
    appendJsonField("shadowEnabled", style.appearance.shadowEnabled)
    append(',')
    appendJsonField("shadowOpacity", style.appearance.shadowOpacity)
    append(',')
    appendJsonField("shadowSize", style.appearance.shadowSize)
    append('}')
}

private fun String.toJsonString(): String =
    buildString(length + 2) {
        append('"')
        for (char in this@toJsonString) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        append("\\u")
                        append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
        append('"')
    }

private fun playbackFailureText(message: String?): String? =
    if (message?.contains("PLAYBACK_TRUST_SETUP") == true) kotlinx.coroutines.runBlocking {
        org.jetbrains.compose.resources.getString(Res.string.playback_trust_setup_failed)
    } else message
