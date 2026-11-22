package com.nuvio.app.features.player

import com.nuvio.app.features.player.desktop.NativePlayerBackend
import com.nuvio.app.features.player.desktop.NativePlayerController
import com.nuvio.app.features.player.desktop.NativePlayerEventSink
import com.nuvio.app.features.player.desktop.NativePlayerHost
import com.nuvio.app.features.player.desktop.NativePlayerLifecycleCoordinator
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Deterministic [NativePlayerBackend] for Compose desktop tests that records native create/dispose
 * ordering, concurrent active handle counts, remote-command ownership flags, mini-player mode
 * transitions, and property reads without opening a real OpenGL/mpv window.
 */
internal class CountingNativePlayerBackend(
    private val onDisposeHook: (Long) -> Unit = {},
) : NativePlayerBackend {
    data class HandleState(
        val handle: Long,
        val sourceUrl: String,
        @Volatile var paused: Boolean,
        @Volatile var loading: Boolean = false,
        @Volatile var ended: Boolean = false,
        @Volatile var positionMs: Long,
        @Volatile var durationMs: Long = 1_800_000L,
        @Volatile var bufferedPositionMs: Long = 300_000L,
        @Volatile var speed: Float = 1f,
        @Volatile var remoteCommandsEnabled: Boolean = true,
        @Volatile var miniPlayerMode: Boolean = false,
        val eventSink: NativePlayerEventSink,
    )

    private val handleCounter = AtomicLong(0L)
    private val activeHandleIds = LinkedHashSet<Long>()
    private val stateLock = Any()

    val statesByHandle = ConcurrentHashMap<Long, HandleState>()
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val createdSources: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val disposedHandles: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    val videoDimensionQueries = AtomicInteger(0)

    @Volatile
    var maxConcurrentHandles: Int = 0
        private set

    val createCount: Int
        get() = createdSources.size

    val disposeCount: Int
        get() = disposedHandles.size

    val activeHandleCount: Int
        get() = synchronized(stateLock) { activeHandleIds.size }

    fun currentHandleState(): HandleState? {
        val current = synchronized(stateLock) { activeHandleIds.lastOrNull() } ?: return null
        return statesByHandle[current]
    }

    fun toDesktopHooks(
        coordinator: NativePlayerLifecycleCoordinator = NativePlayerLifecycleCoordinator(),
    ): DesktopNativePlayerHooks = DesktopNativePlayerHooks(
        createController = { host: NativePlayerHost, enableRemoteCommands: Boolean ->
            NativePlayerController(
                host = host,
                backend = this,
                isHostDisplayable = { true },
                resolveHostView = { 1L },
                lifecycleCoordinator = coordinator,
                remoteCommandsEnabledByDefault = enableRemoteCommands,
            )
        },
        forceHostReady = true,
        renderSwingPanel = false,
        attachDelayMs = 0L,
    )

    override fun controlsPageUrl(): String = "file:///controls.html"

    override fun create(
        hostViewPtr: Long,
        sourceUrl: String,
        headerLines: Array<String>,
        playWhenReady: Boolean,
        initialPositionMs: Long,
        controlsPageUrl: String,
        decoderPriority: Int,
        nvidiaRtxSuperResolutionEnabled: Boolean,
        nvidiaRtxHdrEnabled: Boolean,
        eventSink: NativePlayerEventSink,
    ): Long {
        val handle = handleCounter.incrementAndGet()
        val state = HandleState(
            handle = handle,
            sourceUrl = sourceUrl,
            paused = !playWhenReady,
            loading = false,
            ended = false,
            positionMs = initialPositionMs.coerceAtLeast(0L),
            eventSink = eventSink,
        )
        statesByHandle[handle] = state
        synchronized(stateLock) {
            activeHandleIds += handle
            if (activeHandleIds.size > maxConcurrentHandles) {
                maxConcurrentHandles = activeHandleIds.size
            }
        }
        createdSources += sourceUrl
        events += "create-$handle:$sourceUrl"
        return handle
    }

    override fun dispose(handle: Long) {
        events += "dispose-start-$handle"
        try {
            onDisposeHook(handle)
        } finally {
            synchronized(stateLock) {
                activeHandleIds.remove(handle)
            }
            disposedHandles += handle
            events += "dispose-end-$handle"
        }
    }

    override fun updateControls(handle: Long, controlsJson: String) {
        val inMini = controlsJson.contains("\"isInMiniPlayer\":true")
        statesByHandle[handle]?.miniPlayerMode = inMini
    }

    override fun setNowPlayingMetadata(handle: Long, title: String, subtitle: String, artworkUrl: String) = Unit

    override fun setRemoteCommandsEnabled(handle: Long, enabled: Boolean) {
        statesByHandle[handle]?.remoteCommandsEnabled = enabled
        events += "remote-commands-$handle:$enabled"
    }

    override fun setMiniPlayerMode(handle: Long, inMiniPlayer: Boolean) {
        statesByHandle[handle]?.miniPlayerMode = inMiniPlayer
        events += "mini-mode-$handle:$inMiniPlayer"
    }

    override fun requestFocus(handle: Long) = Unit
    override fun beginWindowDrag(handle: Long) = Unit
    override fun reparentSurface(handle: Long, hostViewPtr: Long): Boolean = true
    override fun layoutNativeSubviews(handle: Long) = Unit

    override fun setPaused(handle: Long, paused: Boolean) {
        statesByHandle[handle]?.paused = paused
    }

    override fun seekTo(handle: Long, positionMs: Long) {
        statesByHandle[handle]?.positionMs = positionMs.coerceAtLeast(0L)
    }

    override fun seekBy(handle: Long, offsetMs: Long) {
        val state = statesByHandle[handle] ?: return
        state.positionMs = (state.positionMs + offsetMs).coerceIn(0L, state.durationMs)
    }

    override fun setSpeed(handle: Long, speed: Float) {
        statesByHandle[handle]?.speed = speed
    }

    override fun setVolume(handle: Long, level: Float) = Unit
    override fun setResizeMode(handle: Long, mode: Int) = Unit
    override fun durationMs(handle: Long): Long = statesByHandle[handle]?.durationMs ?: 0L
    override fun positionMs(handle: Long): Long = statesByHandle[handle]?.positionMs ?: 0L
    override fun bufferedPositionMs(handle: Long): Long = statesByHandle[handle]?.bufferedPositionMs ?: 0L
    override fun isLoading(handle: Long): Boolean = statesByHandle[handle]?.loading ?: true
    override fun isEnded(handle: Long): Boolean = statesByHandle[handle]?.ended ?: false
    override fun isPaused(handle: Long): Boolean = statesByHandle[handle]?.paused ?: true
    override fun speed(handle: Long): Float = statesByHandle[handle]?.speed ?: 1f
    override fun audioTracksJson(handle: Long): String = "[]"
    override fun subtitleTracksJson(handle: Long): String = "[]"

    override fun stringProperty(handle: Long, name: String): String = when (name) {
        "video-out-params/dw" -> {
            videoDimensionQueries.incrementAndGet()
            "1920"
        }
        "video-out-params/dh" -> {
            videoDimensionQueries.incrementAndGet()
            "1080"
        }
        else -> ""
    }

    override fun selectAudioTrack(handle: Long, trackId: Int) = Unit
    override fun setAudioLanguages(handle: Long, languages: String) = Unit
    override fun selectSubtitleTrack(handle: Long, trackId: Int) = Unit
    override fun addSubtitleUrl(handle: Long, url: String) = Unit
    override fun clearExternalSubtitles(handle: Long) = Unit
    override fun clearExternalSubtitlesAndSelect(handle: Long, trackId: Int) = Unit
    override fun setSubtitleVisible(handle: Long, visible: Boolean) = Unit
    override fun setSubtitleDelayMs(handle: Long, delayMs: Int) = Unit

    override fun applySubtitleStyle(
        handle: Long,
        textColor: String,
        backgroundColor: String,
        outlineColor: String,
        outlineSize: Float,
        bold: Boolean,
        fontSize: Float,
        subPos: Int,
        useLibass: Boolean,
        stripSdh: Boolean,
    ) = Unit

    override fun setSubtitleAppearance(
        handle: Long,
        font: String,
        fontsDir: String,
        shadowColor: String,
        shadowOffset: Float,
        shadowBlur: Float,
    ) = Unit
}
