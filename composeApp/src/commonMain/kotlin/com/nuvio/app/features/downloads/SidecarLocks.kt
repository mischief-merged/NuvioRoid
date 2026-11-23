package com.nuvio.app.features.downloads

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.async

/**
 * One lock per video for everything written beside it: subtitles, skip times, next-episode details,
 * artwork, and the app's own delete of the video. A writer takes it only around the write itself
 * (never while it waits for the network), reads what is saved again, checks that the video is still
 * there and that it still has something to do, and only then writes. So two writers cannot undo
 * each other, and a write cannot interleave with a delete: delete takes the same lock, and once the
 * video is gone every later write finds that and does nothing.
 *
 * LOCK ORDER, everywhere: this per-video lock FIRST, then DownloadsRepository's state lock, then the
 * lock every save of a list of downloads takes (the store's lock across processes). A block run under
 * this lock must not take the state lock (and the guard it calls does not); both delete paths take this
 * lock before entering the state lock. The folder move takes this lock around its Trash step and then
 * the list lock inside it, never the state lock. Item locks, writer tokens and the move's OS lock are
 * only ever tried or waited for with a limit, so they are outside this order and cannot invert it.
 *
 * The blocks are short file operations, which is why a plain lock is used: the delete path is not
 * a coroutine.
 */
internal object SidecarLocks {
    private val guard = SynchronizedObject()
    private class Entry(val monitor: SynchronizedObject = SynchronizedObject(), var users: Int = 0)
    private val locks = mutableMapOf<String, Entry>()

    fun <T> withLock(localVideoUri: String, block: () -> T): T {
        val key = keyOf(localVideoUri)
        val entry = synchronized(guard) { locks.getOrPut(key) { Entry() }.also { it.users++ } }
        return try {
            synchronized(entry.monitor) { block() }
        } finally {
            synchronized(guard) {
                entry.users--
                if (entry.users == 0) locks.remove(key)
            }
        }
    }

    private fun keyOf(uri: String) = uri.trim().removePrefix("file:").trimStart('/')
}

/**
 * Whether a video still has a reason to get files saved beside it: it is a finished download that is
 * still listed AND its file is physically there, at that address. A video removed outside the app,
 * on a drive that is not connected, or moved, is not wanted: nothing is written for it, and no
 * folder is created. Called under [SidecarLocks], immediately before every write.
 */
internal object DownloadCompanionGuard {
    internal var stillWanted: (localVideoUri: String) -> Boolean =
        // Never for a download a move or a delete holds, or whose location is away.
        { uri -> !DownloadsRepository.isBusyOrAway(uri) && DownloadsRepository.videoFileIsThere(uri) }
}

/**
 * Reads files beside a video without ever holding the caller (a screen, a playback effect) on a
 * slow or disconnected drive. The read runs on the IO dispatcher; the caller gets the result back on
 * its own dispatcher, or null once [timeoutMs] has passed (the read itself may still finish in the
 * background and is then ignored). A read that throws also gives null.
 */
internal object SidecarIo {
    /** How long a screen or playback waits for a sidecar read. */
    const val ReadTimeoutMs = 2_000L

    private val permits = kotlinx.coroutines.sync.Semaphore(4)
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    suspend fun <T> read(
        timeoutMs: Long = ReadTimeoutMs,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
        block: () -> T,
    ): T? {
        if (!permits.tryAcquire()) return null
        val pending = try { scope.async(dispatcher) { runCatching(block).getOrNull() } }
        catch (error: Throwable) { permits.release(); throw error }
        pending.invokeOnCompletion { permits.release() }
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { pending.await() }
    }
}
