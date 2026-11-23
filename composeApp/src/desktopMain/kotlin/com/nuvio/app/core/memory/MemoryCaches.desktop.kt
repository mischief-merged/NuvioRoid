package com.nuvio.app.core.memory

import java.lang.ref.WeakReference

internal actual fun registerMemoryCache(cache: BoundedCache<*, *>) = DesktopMemoryCaches.register(cache)

internal object DesktopMemoryCaches {
    private val caches = mutableListOf<WeakReference<BoundedCache<*, *>>>()

    fun register(cache: BoundedCache<*, *>) = synchronized(caches) {
        caches.removeAll { it.get() == null }
        caches.add(WeakReference(cache))
        Unit
    }

    fun trim() {
        val live = synchronized(caches) {
            caches.removeAll { it.get() == null }
            caches.mapNotNull { it.get() }
        }
        live.forEach { runCatching { it.clear() } }
        com.nuvio.app.core.ui.trimImageMemoryCaches()
    }
}
