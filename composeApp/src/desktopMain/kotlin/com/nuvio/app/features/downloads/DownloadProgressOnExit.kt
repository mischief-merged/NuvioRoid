package com.nuvio.app.features.downloads

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay

/** Called off the interface thread; slow or unavailable storage cannot hold quit open forever. */
internal fun flushDownloadProgressOnExit(timeoutMs: Long = 2_000): Boolean {
    val done = CountDownLatch(1)
    var saved = false
    thread(name = "download-exit-save", isDaemon = true) {
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            saved = runBlocking {
                var flushed = DownloadsRepository.prepareForQuit()
                while (!flushed && System.nanoTime() < deadline) {
                    delay(25)
                    flushed = DownloadsRepository.flushProgress()
                }
                flushed
            }
        } finally {
            done.countDown()
        }
    }
    return done.await(timeoutMs, TimeUnit.MILLISECONDS) && saved
}
