package com.nuvio.app.features.downloads

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TravelModeReviewTest {
    private val home = NetworkPathReport.Known(false, false, "en0/wifi|10.0.0.1@11:12:13:14:15:16")

    private fun failedWake(throws: Boolean) {
        val hold = ConnectionHold()
        hold.report(home)
        val gate = WakeNetworkRecheck(hold) {}
        var stopped = false
        var starts = 0
        val session = NetworkPathMonitorSession(hold, gate) {
            object : NetworkPathDetector {
                override fun start(onReport: (NetworkPathReport) -> Unit): Boolean {
                    starts++
                    if (starts == 1) { onReport(home); return true }
                    if (throws) error("monitor unavailable")
                    return false
                }
                override fun stop() { stopped = true }
            }
        }
        session.restart()
        gate.sleep()
        gate.wake()
        session.restart()
        assertTrue(stopped)
        assertFalse(hold.state.value.checkingNetwork)
        assertNull(hold.holdReasonFor(false))
        assertFalse(hold.state.value.travel.canRemember)
    }

    @Test
    fun wake_monitor_returning_false_releases_the_checking_gate() = failedWake(false)

    @Test
    fun wake_monitor_throwing_releases_the_checking_gate() = failedWake(true)

    @Test
    fun a_saved_address_without_router_hardware_cannot_claim_another_router() {
        val oldKey = "en0/wifi|10.0.0.1"
        var saved = TravelPreferences(networks = setOf(oldKey))
        val hold = ConnectionHold(loadTravel = { saved }, saveTravel = { saved = it })
        hold.report(home)
        assertEquals(setOf(oldKey), saved.networks)
        assertFalse(hold.state.value.travel.remembered)
        assertFalse(hold.state.value.travel.enabled)
    }

    @Test
    fun remembering_a_network_waits_until_the_router_hardware_is_known() {
        var saves = 0
        val hold = ConnectionHold(saveTravel = { saves++ })
        hold.report(home.copy(networkKey = "en0/wifi|10.0.0.1"))
        assertFalse(hold.state.value.travel.canRemember)
        hold.rememberTravelNetwork(true)
        assertEquals(0, saves)
        hold.report(home)
        assertTrue(hold.state.value.travel.canRemember)
        hold.rememberTravelNetwork(true)
        assertTrue(hold.state.value.travel.remembered)
    }

    @Test
    fun sleep_and_an_offline_wake_cannot_remember_the_previous_network() = runBlocking {
        var saves = 0
        val hold = ConnectionHold(saveTravel = { saves++ })
        hold.report(home)
        val gate = WakeNetworkRecheck(hold) {}
        gate.sleep()
        assertFalse(hold.state.value.travel.canRemember)
        gate.wake()
        gate.report(pathReport(false, false, false, "", ""))
        hold.rememberTravelNetwork(true)
        assertTrue(hold.state.value.checkingNetwork)
        assertFalse(hold.state.value.travel.canRemember)
        assertEquals(0, saves)
    }

    @Test
    fun a_slow_preference_write_cannot_delay_the_sleep_gate() {
        val writing = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val returned = java.util.concurrent.CountDownLatch(1)
        val hold = ConnectionHold(saveTravel = {
            writing.countDown()
            release.await(5, TimeUnit.SECONDS)
        })
        val writer = Thread { hold.setTravelEnabled(true) }
        val sleeper = Thread { WakeNetworkRecheck(hold) {}.sleep(); returned.countDown() }
        try {
            writer.start()
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            sleeper.start()
            assertTrue(returned.await(1, TimeUnit.SECONDS), "sleep waited for a preference write")
            assertEquals(LimitedConnectionReason.CheckingNetwork, hold.holdReasonFor(false))
        } finally {
            release.countDown()
            writer.join(5000)
            if (sleeper.state != Thread.State.NEW) sleeper.join(5000)
        }
    }

    @Test
    fun power_callbacks_publish_the_gate_without_running_repository_work_on_the_calling_thread() {
        val hold = ConnectionHold()
        val gate = WakeNetworkRecheck(hold) {}
        val threads = LinkedBlockingQueue<Thread>()
        val caller = Thread.currentThread()
        hold.listen { threads.put(Thread.currentThread()) }
        gate.sleep()
        assertEquals(LimitedConnectionReason.CheckingNetwork, hold.holdReasonFor(false))
        val notifiedOn = threads.poll(5, TimeUnit.SECONDS)
        kotlin.test.assertNotNull(notifiedOn)
        assertNotEquals(caller, notifiedOn)
        hold.report(home)
        threads.clear()
        gate.wake()
        assertEquals(LimitedConnectionReason.CheckingNetwork, hold.holdReasonFor(false))
        val wokeOn = threads.poll(5, TimeUnit.SECONDS)
        kotlin.test.assertNotNull(wokeOn)
        assertNotEquals(caller, wokeOn)
    }
}
