package com.nuvio.app.features.player.desktop

import com.nuvio.app.core.storage.DesktopStorage

/**
 * Persists desktop window mode/geometry across launches:
 * - whether the app was last closed in fullscreen mode
 * - the last windowed (non-fullscreen) position and size
 *
 * This is intentionally a global (non profile-scoped) preference: it reflects
 * the state of the OS window itself rather than per-user app data.
 */
internal data class DesktopWindowGeometry(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

internal object DesktopWindowModeStorage {
    private const val WasFullscreenKey = "was_fullscreen"
    private const val WasMaximizedKey = "was_maximized"
    private const val WindowXKey = "window_x"
    private const val WindowYKey = "window_y"
    private const val WindowWidthKey = "window_width"
    private const val WindowHeightKey = "window_height"
    private val store = DesktopStorage.store("nuvio_window_state")

    fun loadWasFullscreen(): Boolean =
        store.getBoolean(WasFullscreenKey) ?: false

    fun saveWasFullscreen(fullscreen: Boolean) {
        store.putBoolean(WasFullscreenKey, fullscreen)
    }

    fun loadWasMaximized(): Boolean? =
        store.getBoolean(WasMaximizedKey)

    fun saveWasMaximized(maximized: Boolean) {
        store.putBoolean(WasMaximizedKey, maximized)
    }

    fun loadWindowedGeometry(): DesktopWindowGeometry? {
        val x = store.getFloat(WindowXKey) ?: return null
        val y = store.getFloat(WindowYKey) ?: return null
        val width = store.getFloat(WindowWidthKey) ?: return null
        val height = store.getFloat(WindowHeightKey) ?: return null
        return DesktopWindowGeometry(x = x, y = y, width = width, height = height)
    }

    fun saveWindowedGeometry(geometry: DesktopWindowGeometry) {
        saveSnapshot(wasMaximized = null, geometry = geometry)
    }

    /** Writes the maximized flag and the windowed geometry in one save. A null part is left as it was. */
    internal fun saveSnapshot(
        wasMaximized: Boolean?,
        geometry: DesktopWindowGeometry?,
        target: DesktopStorage.Store = store,
    ) {
        val entries = LinkedHashMap<String, String?>()
        if (wasMaximized != null) entries[WasMaximizedKey] = wasMaximized.toString()
        if (geometry != null) {
            entries[WindowXKey] = geometry.x.toString()
            entries[WindowYKey] = geometry.y.toString()
            entries[WindowWidthKey] = geometry.width.toString()
            entries[WindowHeightKey] = geometry.height.toString()
        }
        if (entries.isNotEmpty()) target.putAll(entries)
    }

    internal fun readSnapshot(target: DesktopStorage.Store = store): Pair<Boolean?, DesktopWindowGeometry?> {
        val geometry = target.getFloat(WindowXKey)?.let { x ->
            val y = target.getFloat(WindowYKey) ?: return@let null
            val width = target.getFloat(WindowWidthKey) ?: return@let null
            val height = target.getFloat(WindowHeightKey) ?: return@let null
            DesktopWindowGeometry(x = x, y = y, width = width, height = height)
        }
        return target.getBoolean(WasMaximizedKey) to geometry
    }
}

/**
 * Holds window position and size until the window has stayed still for [quietMs], then asks for
 * one save. A drag therefore writes once, when it ends, and closing the window writes whatever is
 * still waiting.
 */
internal class WindowGeometryWrites(
    private val quietMs: Long = 300L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val persist: (wasMaximized: Boolean?, geometry: DesktopWindowGeometry?) -> Unit,
) {
    private var pendingMax: Boolean? = null
    private var pendingGeometry: DesktopWindowGeometry? = null
    private var notedAt = 0L
    private var dirty = false

    /** How many times a save was asked for. */
    var writes: Int = 0
        private set

    fun note(wasMaximized: Boolean?, geometry: DesktopWindowGeometry?, now: Long = clock()) {
        if (wasMaximized != null) pendingMax = wasMaximized
        if (geometry != null) pendingGeometry = geometry
        notedAt = now
        dirty = true
    }

    /** Saves when [quietMs] has passed since the latest [note]. */
    fun flushIfDue(now: Long = clock()): Boolean {
        if (!dirty || now - notedAt < quietMs) return false
        write()
        return true
    }

    /** Saves immediately. Used when the window is closing, so the last position is not dropped. */
    fun flushNow() {
        if (!dirty) return
        write()
    }

    private fun write() {
        dirty = false
        writes++
        persist(pendingMax, pendingGeometry)
    }
}
