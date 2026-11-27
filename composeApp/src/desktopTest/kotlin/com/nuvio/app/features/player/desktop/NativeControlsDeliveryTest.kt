package com.nuvio.app.features.player.desktop

import com.nuvio.app.core.memory.BoundedCache
import com.nuvio.app.features.player.PlayerControlEpisodeItem
import com.nuvio.app.features.player.PlayerControlSeasonItem
import com.nuvio.app.features.player.PlayerControlsState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeControlsDeliveryTest {
    @Test
    fun four_hundred_episodes_never_put_image_bytes_in_the_controls_document() {
        @Suppress("UNCHECKED_CAST")
        val images = WebviewImages.javaClass.getDeclaredField("images").let {
            it.isAccessible = true
            it.get(WebviewImages) as BoundedCache<String, String>
        }
        val address = "file:/controls-test-thumbnail.png"
        images[address] = "data:image/png;base64," + "A".repeat(64 * 1024)
        val sent = CountDownLatch(1)
        val payload = AtomicReference("")
        val backend = object : NativePlayerBackend by NoOpNativePlayerBackend {
            override fun updateControls(handle: Long, controlsJson: String) {
                payload.set(controlsJson)
                sent.countDown()
            }
        }
        val controller = NativePlayerController(NativePlayerHost(), backend = backend, nativeDispose = {})
        controller.assignNativeHandleForTest(1L)
        try {
            SwingUtilities.invokeAndWait {
                controller.updateControls(PlayerControlsState(
                    showEpisodes = true,
                    episodeSeasons = (1..20).map { PlayerControlSeasonItem(season = it, isSelected = it == 1) },
                    episodeItems = (0 until 400).map {
                        PlayerControlEpisodeItem(index = it, id = "episode-$it", title = "Episode $it",
                            thumbnail = address, season = it / 20 + 1, episode = it % 20 + 1)
                    },
                ))
            }
            assertTrue(sent.await(5, TimeUnit.SECONDS))
            assertFalse(payload.get().contains("data:image"), "controls documents must contain image references only")
            assertTrue(payload.get().toByteArray().size < 128 * 1024, "controls document exceeded the 400-episode budget")
        } finally {
            controller.dispose()
            images.clear()
        }
    }
    @Test
    fun slow_images_and_identical_states_do_not_resend_the_document_and_seasons_remain_reachable() {
        val scheduled = java.util.ArrayDeque<() -> Unit>()
        val payloads = mutableListOf<String>()
        val requested = CountDownLatch(2)
        val finishImages = CountDownLatch(1)
        val imagesSent = CountDownLatch(20)
        val backend = object : NativePlayerBackend by NoOpNativePlayerBackend {
            override fun updateControls(handle: Long, controlsJson: String) { payloads += controlsJson }
            override fun setControlsImage(handle: Long, key: String, data: String?) { imagesSent.countDown() }
        }
        val controller = NativePlayerController(NativePlayerHost(), backend = backend, nativeDispose = {},
            controlsScheduler = { scheduled.add(it) },
            loadControlsImage = { requested.countDown(); finishImages.await(); "data:image/png;base64,AA==" })
        controller.assignNativeHandleForTest(2L)
        val state = PlayerControlsState(showEpisodes = true,
            episodeSeasons = (1..20).map { PlayerControlSeasonItem(season = it, isSelected = it == 1) },
            episodeItems = (0 until 400).map { PlayerControlEpisodeItem(index = it, id = "$it", title = "Episode $it",
                thumbnail = "https://images.example.invalid/$it.png", season = it / 20 + 1, episode = it % 20 + 1) })
        fun event(type: String, value: Double = 0.0) {
            controller.javaClass.getDeclaredMethod("handlePlayerEvent", String::class.java, Double::class.javaPrimitiveType)
                .also { it.isAccessible = true }.invoke(controller, type, value)
        }
        try {
            SwingUtilities.invokeAndWait { repeat(1_000) { controller.updateControls(state.copy(positionMs = it.toLong())) } }
            assertEquals(1, scheduled.size)
            scheduled.removeFirst().invoke()
            val first = Json.parseToJsonElement(payloads.single()).jsonObject
            val episodes = first.getValue("episodeItems").jsonArray
            assertEquals(20, episodes.size)
            assertEquals(20, first.getValue("episodeSeasons").jsonArray.size)
            SwingUtilities.invokeAndWait {
                episodes.forEach { event("controlsImage:" + it.jsonObject.getValue("thumbnail").jsonPrimitive.content.substringAfter("://")) }
            }
            assertTrue(requested.await(5, TimeUnit.SECONDS))
            assertEquals(1, payloads.size)
            assertEquals(0, scheduled.size)
            finishImages.countDown()
            assertTrue(imagesSent.await(5, TimeUnit.SECONDS))
            assertEquals(1, payloads.size, "image completion must never rebuild controls")
            assertEquals(0, scheduled.size)
            SwingUtilities.invokeAndWait { event("episodeSeason", 20.0) }
            assertEquals(1, scheduled.size)
            scheduled.removeFirst().invoke()
            val last = Json.parseToJsonElement(payloads.last()).jsonObject.getValue("episodeItems").jsonArray
            assertEquals(20, last.size)
            assertEquals(380, last.first().jsonObject.getValue("index").jsonPrimitive.int)
            assertTrue(payloads.all { it.toByteArray().size < 128 * 1024 && !it.contains("data:image") })
        } finally { finishImages.countDown(); controller.dispose() }
    }

    @Test
    fun a_busy_encoder_keeps_only_the_newest_pending_state() {
        val scheduled = java.util.ArrayDeque<() -> Unit>()
        val sent = mutableListOf<Int>()
        lateinit var worker: LatestControlsUpdate<Int>
        worker = LatestControlsUpdate({ scheduled.add(it) }) { value ->
            if (value == 0) repeat(1_000) { worker.offer(it + 1) }
            val send: () -> Unit = { sent += value }
            send
        }
        worker.offer(0)
        assertEquals(1, scheduled.size)
        scheduled.removeFirst().invoke()
        assertEquals(emptyList(), sent)
        assertEquals(1, scheduled.size)
        scheduled.removeFirst().invoke()
        assertEquals(listOf(1_000), sent)
        worker.offer(1_000)
        assertEquals(0, scheduled.size)
    }

}
