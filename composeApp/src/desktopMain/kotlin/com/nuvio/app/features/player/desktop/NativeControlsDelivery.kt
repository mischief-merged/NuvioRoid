package com.nuvio.app.features.player.desktop

import java.security.MessageDigest
import java.util.concurrent.Executors

/** One worker and one replaceable pending value, including while encoding is in progress. */
internal class LatestControlsUpdate<T : Any>(
    private val schedule: (() -> Unit) -> Unit = { ControlsWorkers.executor.execute(it) },
    private val prepare: (T) -> (() -> Unit),
) {
    private val lock = Any()
    private var pending: T? = null
    private var lastOffered: T? = null
    private var scheduled = false

    fun offer(value: T) {
        val start = synchronized(lock) {
            if (value == lastOffered) return
            lastOffered = value
            pending = value
            if (scheduled) false else { scheduled = true; true }
        }
        if (start) schedule(::drain)
    }

    private fun drain() {
        try {
            val value = synchronized(lock) { pending.also { pending = null } } ?: return
            val send = prepare(value)
            if (synchronized(lock) { pending == null }) send()
        } catch (_: Exception) {
            synchronized(lock) { lastOffered = null }
        } finally {
            val again = synchronized(lock) {
                scheduled = pending != null
                scheduled
            }
            if (again) schedule(::drain)
        }
    }
}

internal object ControlsWorkers {
    val executor = Executors.newCachedThreadPool { work -> Thread(work, "player-controls").apply { isDaemon = true } }
}

/** The document carries stable addresses; image bytes travel only in responses to image requests. */
internal class ControlImageReferences {
    val sources = linkedMapOf<String, String>()

    fun reference(url: String): String {
        if (!(url.startsWith("https://", true) || url.startsWith("http://", true) || url.startsWith("file:", true))) return ""
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        sources[key] = url
        return "nuvio-image://$key"
    }
}
