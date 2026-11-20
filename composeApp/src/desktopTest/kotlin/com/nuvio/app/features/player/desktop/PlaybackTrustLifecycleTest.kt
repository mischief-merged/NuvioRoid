package com.nuvio.app.features.player.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.test.*

class PlaybackTrustLifecycleTest {
    private fun NativePlayerController.handle(value: Long) {
        javaClass.getDeclaredField("handle").apply { isAccessible = true }.setLong(this, value)
    }
    private fun NativePlayerController.attachSource(source: String) = attach(source, emptyMap(), true, 0, 0, false) {}

    @Test fun `certificate event from a replaced source cannot prompt for the replacement`() {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        var oldSink: NativePlayerEventSink? = null
        val prompts = AtomicInteger()
        var displayable = true
        val controller = NativePlayerController(NativePlayerHost(),
            nativeCreate = { _, _, _, _, _, _, _, _, sink -> oldSink = sink; started.countDown(); finish.await(); 41L },
            nativeDispose = { disposed.countDown() }, isHostDisplayable = { displayable }, resolveHostView = { 1L },
            networkIdentity = { "session:1" }, confirmUnverified = { prompts.incrementAndGet(); false })
        try {
            controller.attachSource("https://example.invalid/a")
            assertTrue(started.await(5, TimeUnit.SECONDS))
            displayable = false
            controller.attachSource("https://example.invalid/b")
            controller.handle(42L)
            oldSink!!.onPlayerEvent("certificateFailure", 0.0)
            SwingUtilities.invokeAndWait {}
            assertEquals(0, prompts.get())
        } finally {
            controller.handle(0L)
            controller.dispose()
            finish.countDown()
            assertTrue(disposed.await(5, TimeUnit.SECONDS))
        }
    }

    @Test fun `native retry holds the handle alive until the retry returns`() {
        val inRetry = CountDownLatch(1)
        val finishRetry = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        val controller = NativePlayerController(NativePlayerHost(), nativeDispose = { disposed.countDown() },
            networkIdentity = { "session:1" }, confirmUnverified = { true }, rememberPlaybackNetwork = {},
            nativeRetryUnverified = { _, _ -> inRetry.countDown(); finishRetry.await(); true })
        controller.handle(42L)
        val method = controller.javaClass.getDeclaredMethod("handlePlayerEvent", String::class.java, Double::class.javaPrimitiveType, Any::class.java).apply { isAccessible = true }
        SwingUtilities.invokeLater { method.invoke(controller, "certificateFailure", 0.0, null) }
        assertTrue(inRetry.await(5, TimeUnit.SECONDS))
        val teardown = thread { controller.dispose() }
        try { assertFalse(disposed.await(200, TimeUnit.MILLISECONDS), "handle was freed during retry") }
        finally { finishRetry.countDown(); teardown.join(5000); SwingUtilities.invokeAndWait {} }
        assertTrue(disposed.await(5, TimeUnit.SECONDS))
    }
}
