package com.nuvio.app.core.ui

/**
 * Routes the desktop Escape shortcut to the active navigation back handler.
 *
 * Compose Desktop only delivers key events to the focused node, so a root-level
 * [androidx.compose.ui.input.key.onPreviewKeyEvent] cannot observe Escape while a
 * card or list still holds focus. An AWT dispatcher sits in front of the whole
 * dispatch chain and therefore sees every press, which lets Escape behave like
 * the system Back button on every screen, including the player.
 *
 * On top of the app-level handler, [PlatformBackHandler] pushes a modal handler
 * while it is enabled. Escape then closes the active dialog/sheet or leaves the
 * current settings page before it can reach the app-level "navigate back", which
 * used to jump straight to Home from a settings sub-page.
 */
internal object DesktopEscapeShortcut {
    @Volatile
    private var baseHandler: (() -> Unit)? = null

    private val modalHandlers = mutableListOf<() -> Unit>()
    private val localHandlers = mutableListOf<() -> Unit>()

    /** Highest-priority active Escape handler: modal dialog, then local back handler, then root. */
    val onBack: (() -> Unit)?
        get() = synchronized(modalHandlers) { modalHandlers.lastOrNull() }
            ?: synchronized(localHandlers) { localHandlers.lastOrNull() }
            ?: baseHandler

    fun register(handler: () -> Unit) {
        baseHandler = handler
    }

    fun unregister(handler: () -> Unit) {
        if (baseHandler === handler) baseHandler = null
    }

    fun registerModal(handler: () -> Unit) {
        synchronized(modalHandlers) {
            modalHandlers.removeAll { it === handler }
            modalHandlers.add(handler)
        }
    }

    fun unregisterModal(handler: () -> Unit) {
        synchronized(modalHandlers) {
            modalHandlers.removeAll { it === handler }
        }
    }

    fun hasOpenModal(): Boolean = synchronized(modalHandlers) { modalHandlers.isNotEmpty() }

    fun registerLocal(handler: () -> Unit) {
        synchronized(localHandlers) {
            localHandlers.removeAll { it === handler }
            localHandlers.add(handler)
        }
    }

    fun unregisterLocal(handler: () -> Unit) {
        synchronized(localHandlers) {
            localHandlers.removeAll { it === handler }
        }
    }

    /** Runs the innermost active modal back handler, returning true if one handled back. */
    fun dispatchModalBack(): Boolean {
        val modal = synchronized(modalHandlers) { modalHandlers.lastOrNull() } ?: return false
        modal()
        return true
    }

    /** Runs the innermost active modal or local back handler, returning true if one handled back. */
    fun dispatchLocalBack(): Boolean {
        if (dispatchModalBack()) return true
        val local = synchronized(localHandlers) { localHandlers.lastOrNull() } ?: return false
        local()
        return true
    }

    /** Runs the innermost active modal or local back handler, or falls back to the root navigation back handler. */
    fun dispatchBack(): Boolean {
        val handler = onBack ?: return false
        handler()
        return true
    }
}
