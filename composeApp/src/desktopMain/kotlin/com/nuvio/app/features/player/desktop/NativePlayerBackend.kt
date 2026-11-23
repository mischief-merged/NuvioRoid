package com.nuvio.app.features.player.desktop

import java.lang.ref.WeakReference

/**
 * Coordinates background native player teardowns across distinct [NativePlayerController]
 * instances so an unmounted surface finishing [NativePlayerController.dispose] or a
 * superseded create worker cannot overlap with the next session's native player creation,
 * and allows desktop quit to await all native shutdowns without retaining closed controllers.
 */
internal class NativePlayerLifecycleCoordinator {
    companion object {
        val Default = NativePlayerLifecycleCoordinator()
    }

    private class TrackedWorker(
        owner: Any,
        val thread: Thread,
    ) {
        val ownerRef = WeakReference(owner)
    }

    private val lock = Any()
    private val activeTeardowns = mutableListOf<TrackedWorker>()
    private val activeControllers = mutableListOf<WeakReference<NativePlayerController>>()

    fun registerController(controller: NativePlayerController) {
        synchronized(lock) {
            activeControllers.removeAll { it.get() == null }
            if (activeControllers.none { it.get() === controller }) {
                activeControllers += WeakReference(controller)
            }
        }
    }

    fun unregisterController(controller: NativePlayerController) {
        synchronized(lock) {
            activeControllers.removeAll { ref ->
                val target = ref.get()
                target == null || target === controller
            }
        }
    }

    fun registerTeardown(owner: Any, thread: Thread?, allowUnstarted: Boolean = false) {
        if (thread == null) return
        if (!allowUnstarted && !thread.isAlive && thread.state == Thread.State.TERMINATED) return
        synchronized(lock) {
            pruneCompletedLocked()
            if (!allowUnstarted && !thread.isAlive) return
            if (activeTeardowns.none { it.thread === thread }) {
                activeTeardowns += TrackedWorker(owner = owner, thread = thread)
            }
        }
    }

    fun registerTeardowns(owner: Any, threads: Collection<Thread>) {
        if (threads.isEmpty()) return
        synchronized(lock) {
            pruneCompletedLocked()
            threads.forEach { thread ->
                if (thread.isAlive && activeTeardowns.none { it.thread === thread }) {
                    activeTeardowns += TrackedWorker(owner = owner, thread = thread)
                }
            }
        }
    }

    fun unregisterTeardown(thread: Thread?) {
        if (thread == null) return
        synchronized(lock) {
            activeTeardowns.removeAll {
                it.thread === thread ||
                    (!it.thread.isAlive && it.thread.state == Thread.State.TERMINATED)
            }
            activeControllers.removeAll { it.get() == null }
        }
    }

    fun pendingTeardownsExcluding(owner: Any): List<Thread> = synchronized(lock) {
        pruneCompletedLocked()
        activeTeardowns
            .filter { it.ownerRef.get() !== owner && it.thread.isAlive }
            .map { it.thread }
    }

    fun shutdownAllAndAwait(timeoutMs: Long = 5_000L) {
        val controllers = synchronized(lock) {
            activeControllers.mapNotNull { it.get() }.also {
                activeControllers.removeAll { ref -> ref.get() == null }
            }
        }
        controllers.forEach { controller ->
            runCatching { controller.dispose() }
        }
        awaitAllPending(timeoutMs)
    }

    fun awaitAllPending(timeoutMs: Long = 5_000L) {
        val deadlineNanos = System.nanoTime() + timeoutMs.coerceAtLeast(1L) * 1_000_000L
        var interrupted = false
        while (true) {
            if (deadlineNanos - System.nanoTime() <= 0L) break
            val pending = synchronized(lock) {
                pruneCompletedLocked()
                activeTeardowns
                    .filter {
                        it.thread !== Thread.currentThread() &&
                            (it.thread.isAlive || it.thread.state == Thread.State.NEW)
                    }
                    .map { it.thread }
            }
            if (pending.isEmpty()) break
            pending.forEach { worker ->
                val waitMs = ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)
                if (worker.state == Thread.State.NEW) {
                    Thread.yield()
                } else {
                    try {
                        worker.join(waitMs)
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            }
        }
        synchronized(lock) {
            pruneCompletedLocked()
        }
        if (interrupted) {
            Thread.currentThread().interrupt()
        }
    }

    internal val trackedWorkerCountForTest: Int
        get() = synchronized(lock) { activeTeardowns.size }

    private fun pruneCompletedLocked() {
        activeTeardowns.removeAll {
            !it.thread.isAlive && it.thread.state == Thread.State.TERMINATED
        }
        activeControllers.removeAll { it.get() == null }
    }
}

internal interface NativePlayerBackend {
    fun controlsPageUrl(): String
    fun create(
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
        inMiniPlayer: Boolean = false,
        remoteCommandsEnabled: Boolean = true,
    ): Long

    fun dispose(handle: Long)
    fun updateControls(handle: Long, controlsJson: String)
    fun setNowPlayingMetadata(handle: Long, title: String, subtitle: String, artworkUrl: String)
    fun setRemoteCommandsEnabled(handle: Long, enabled: Boolean)
    fun setMiniPlayerMode(handle: Long, inMiniPlayer: Boolean)
    fun requestFocus(handle: Long)
    fun beginWindowDrag(handle: Long)
    fun reparentSurface(handle: Long, hostViewPtr: Long): Boolean
    fun layoutNativeSubviews(handle: Long)
    fun setPaused(handle: Long, paused: Boolean)
    fun seekTo(handle: Long, positionMs: Long)
    fun seekBy(handle: Long, offsetMs: Long)
    fun setSpeed(handle: Long, speed: Float)
    fun setVolume(handle: Long, level: Float)
    fun setResizeMode(handle: Long, mode: Int)
    fun durationMs(handle: Long): Long
    fun positionMs(handle: Long): Long
    fun bufferedPositionMs(handle: Long): Long
    fun isLoading(handle: Long): Boolean
    fun isEnded(handle: Long): Boolean
    fun isPaused(handle: Long): Boolean
    fun speed(handle: Long): Float
    fun audioTracksJson(handle: Long): String
    fun subtitleTracksJson(handle: Long): String
    fun stringProperty(handle: Long, name: String): String
    fun selectAudioTrack(handle: Long, trackId: Int)
    fun setAudioLanguages(handle: Long, languages: String)
    fun selectSubtitleTrack(handle: Long, trackId: Int)
    fun addSubtitleUrl(handle: Long, url: String)
    fun clearExternalSubtitles(handle: Long)
    fun clearExternalSubtitlesAndSelect(handle: Long, trackId: Int)
    fun setSubtitleVisible(handle: Long, visible: Boolean)
    fun setSubtitleDelayMs(handle: Long, delayMs: Int)
    fun applySubtitleStyle(
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
    )

    fun setSubtitleAppearance(
        handle: Long,
        font: String,
        fontsDir: String,
        shadowColor: String,
        shadowOffset: Float,
        shadowBlur: Float,
    )
}

internal object NoOpNativePlayerBackend : NativePlayerBackend {
    override fun controlsPageUrl(): String = "about:blank"
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
        inMiniPlayer: Boolean,
        remoteCommandsEnabled: Boolean,
    ): Long = 0L

    override fun dispose(handle: Long) = Unit
    override fun updateControls(handle: Long, controlsJson: String) = Unit
    override fun setNowPlayingMetadata(handle: Long, title: String, subtitle: String, artworkUrl: String) = Unit
    override fun setRemoteCommandsEnabled(handle: Long, enabled: Boolean) = Unit
    override fun setMiniPlayerMode(handle: Long, inMiniPlayer: Boolean) = Unit
    override fun requestFocus(handle: Long) = Unit
    override fun beginWindowDrag(handle: Long) = Unit
    override fun reparentSurface(handle: Long, hostViewPtr: Long): Boolean = true
    override fun layoutNativeSubviews(handle: Long) = Unit
    override fun setPaused(handle: Long, paused: Boolean) = Unit
    override fun seekTo(handle: Long, positionMs: Long) = Unit
    override fun seekBy(handle: Long, offsetMs: Long) = Unit
    override fun setSpeed(handle: Long, speed: Float) = Unit
    override fun setVolume(handle: Long, level: Float) = Unit
    override fun setResizeMode(handle: Long, mode: Int) = Unit
    override fun durationMs(handle: Long): Long = 0L
    override fun positionMs(handle: Long): Long = 0L
    override fun bufferedPositionMs(handle: Long): Long = 0L
    override fun isLoading(handle: Long): Boolean = false
    override fun isEnded(handle: Long): Boolean = false
    override fun isPaused(handle: Long): Boolean = false
    override fun speed(handle: Long): Float = 1f
    override fun audioTracksJson(handle: Long): String = "[]"
    override fun subtitleTracksJson(handle: Long): String = "[]"
    override fun stringProperty(handle: Long, name: String): String = ""
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

internal object RealNativePlayerBackend : NativePlayerBackend {
    override fun controlsPageUrl(): String = NativePlayerBridge.controlsPageUrl

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
        inMiniPlayer: Boolean,
        remoteCommandsEnabled: Boolean,
    ): Long {
        NativePlayerBridge.ensureLoaded()
        if (DesktopHostOs.current == DesktopHostOs.MACOS) {
            val network = NativePlayerBridge.playbackNetworkIdentity()
            val pem = NativePlayerBridge.systemTrustPem()
            val bundle = java.nio.file.Files.createTempFile(
                com.nuvio.app.core.storage.DesktopStorage.cacheDir,
                "playback-trust-",
                ".pem",
            )
            java.nio.file.Files.writeString(bundle, pem)
            try {
                return NativePlayerBridge.createMac(
                    hostViewPtr,
                    sourceUrl,
                    headerLines,
                    playWhenReady,
                    initialPositionMs,
                    controlsPageUrl,
                    decoderPriority,
                    nvidiaRtxSuperResolutionEnabled,
                    inMiniPlayer,
                    remoteCommandsEnabled,
                    eventSink,
                    bundle.toString(),
                    network,
                    PlaybackNetworkTrust.choices.allows(network),
                    kotlinx.coroutines.runBlocking {
                        org.jetbrains.compose.resources.getString(
                            nuvio.composeapp.generated.resources.Res.string.playback_tls_active,
                        )
                    },
                )
            } finally {
                // mpv reads this bundle during later connections, so the native player owns its lifetime.
                bundle.toFile().deleteOnExit()
            }
        }
        return NativePlayerBridge.create(
            hostViewPtr = hostViewPtr,
            sourceUrl = sourceUrl,
            headerLines = headerLines,
            playWhenReady = playWhenReady,
            initialPositionMs = initialPositionMs,
            controlsPageUrl = controlsPageUrl,
            decoderPriority = decoderPriority,
            nvidiaRtxSuperResolutionEnabled = nvidiaRtxSuperResolutionEnabled,
            nvidiaRtxHdrEnabled = nvidiaRtxHdrEnabled,
            eventSink = eventSink,
        )
    }

    override fun dispose(handle: Long) = NativePlayerBridge.dispose(handle)
    override fun updateControls(handle: Long, controlsJson: String) =
        NativePlayerBridge.updateControls(handle, controlsJson)

    override fun setNowPlayingMetadata(handle: Long, title: String, subtitle: String, artworkUrl: String) {
        if (DesktopHostOs.current == DesktopHostOs.MACOS) {
            NativePlayerBridge.setNowPlayingMetadata(handle, title, subtitle, artworkUrl)
        }
    }

    override fun setRemoteCommandsEnabled(handle: Long, enabled: Boolean) {
        if (DesktopHostOs.current == DesktopHostOs.MACOS) {
            NativePlayerBridge.setRemoteCommandsEnabled(handle, enabled)
        }
    }

    override fun setMiniPlayerMode(handle: Long, inMiniPlayer: Boolean) {
        if (DesktopHostOs.current == DesktopHostOs.MACOS) {
            NativePlayerBridge.setMiniPlayerMode(handle, inMiniPlayer)
        }
    }

    override fun requestFocus(handle: Long) = NativePlayerBridge.requestFocus(handle)
    override fun beginWindowDrag(handle: Long) = NativePlayerBridge.beginWindowDrag(handle)
    override fun reparentSurface(handle: Long, hostViewPtr: Long): Boolean =
        NativePlayerBridge.reparentSurface(handle, hostViewPtr)

    override fun layoutNativeSubviews(handle: Long) = NativePlayerBridge.layoutNativeSubviews(handle)
    override fun setPaused(handle: Long, paused: Boolean) = NativePlayerBridge.setPaused(handle, paused)
    override fun seekTo(handle: Long, positionMs: Long) = NativePlayerBridge.seekTo(handle, positionMs)
    override fun seekBy(handle: Long, offsetMs: Long) = NativePlayerBridge.seekBy(handle, offsetMs)
    override fun setSpeed(handle: Long, speed: Float) = NativePlayerBridge.setSpeed(handle, speed)
    override fun setVolume(handle: Long, level: Float) = NativePlayerBridge.setVolume(handle, level)
    override fun setResizeMode(handle: Long, mode: Int) = NativePlayerBridge.setResizeMode(handle, mode)
    override fun durationMs(handle: Long): Long = NativePlayerBridge.durationMs(handle)
    override fun positionMs(handle: Long): Long = NativePlayerBridge.positionMs(handle)
    override fun bufferedPositionMs(handle: Long): Long = NativePlayerBridge.bufferedPositionMs(handle)
    override fun isLoading(handle: Long): Boolean = NativePlayerBridge.isLoading(handle)
    override fun isEnded(handle: Long): Boolean = NativePlayerBridge.isEnded(handle)
    override fun isPaused(handle: Long): Boolean = NativePlayerBridge.isPaused(handle)
    override fun speed(handle: Long): Float = NativePlayerBridge.speed(handle)
    override fun audioTracksJson(handle: Long): String = NativePlayerBridge.audioTracksJson(handle)
    override fun subtitleTracksJson(handle: Long): String = NativePlayerBridge.subtitleTracksJson(handle)
    override fun stringProperty(handle: Long, name: String): String =
        NativePlayerBridge.stringProperty(handle, name)

    override fun selectAudioTrack(handle: Long, trackId: Int) =
        NativePlayerBridge.selectAudioTrack(handle, trackId)

    override fun setAudioLanguages(handle: Long, languages: String) =
        NativePlayerBridge.setAudioLanguages(handle, languages)

    override fun selectSubtitleTrack(handle: Long, trackId: Int) =
        NativePlayerBridge.selectSubtitleTrack(handle, trackId)

    override fun addSubtitleUrl(handle: Long, url: String) = NativePlayerBridge.addSubtitleUrl(handle, url)
    override fun clearExternalSubtitles(handle: Long) = NativePlayerBridge.clearExternalSubtitles(handle)
    override fun clearExternalSubtitlesAndSelect(handle: Long, trackId: Int) =
        NativePlayerBridge.clearExternalSubtitlesAndSelect(handle, trackId)

    override fun setSubtitleVisible(handle: Long, visible: Boolean) =
        NativePlayerBridge.setSubtitleVisible(handle, visible)

    override fun setSubtitleDelayMs(handle: Long, delayMs: Int) =
        NativePlayerBridge.setSubtitleDelayMs(handle, delayMs)

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
    ) = NativePlayerBridge.applySubtitleStyle(
        handle = handle,
        textColor = textColor,
        backgroundColor = backgroundColor,
        outlineColor = outlineColor,
        outlineSize = outlineSize,
        bold = bold,
        fontSize = fontSize,
        subPos = subPos,
        useLibass = useLibass,
        stripSdh = stripSdh,
    )

    override fun setSubtitleAppearance(
        handle: Long,
        font: String,
        fontsDir: String,
        shadowColor: String,
        shadowOffset: Float,
        shadowBlur: Float,
    ) = NativePlayerBridge.setSubtitleAppearance(
        handle = handle,
        font = font,
        fontsDir = fontsDir,
        shadowColor = shadowColor,
        shadowOffset = shadowOffset,
        shadowBlur = shadowBlur,
    )
}
