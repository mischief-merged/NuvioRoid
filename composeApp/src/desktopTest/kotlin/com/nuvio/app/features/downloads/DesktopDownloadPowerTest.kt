package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.desktop.DesktopHostOs
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_sleep_reason
import org.jetbrains.compose.resources.getString
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDownloadPowerTest {
    @Test
    fun native_assertion_prevents_only_idle_system_sleep_and_is_released() = runBlocking {
        if (DesktopHostOs.current != DesktopHostOs.MACOS) return@runBlocking
        val reason = getString(Res.string.downloads_sleep_reason)
        val guard = MacDownloadPowerNative.guard(reason)
        fun assertions(): List<String> {
            val process = ProcessBuilder("/usr/bin/pmset", "-g", "assertions").start()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            return process.inputStream.bufferedReader().readLines().filter {
                it.contains("pid ${ProcessHandle.current().pid()}(") && it.contains("named: \"$reason\"")
            }
        }
        try {
            guard.update(1)
            val held = assertions()
            val mayHold = MacDownloadPowerNative.powerState() !in 0..19
            assertEquals(mayHold, held.any { it.contains("PreventUserIdleSystemSleep") })
            assertFalse(held.any { it.contains("PreventUserIdleDisplaySleep") || it.contains(" PreventSystemSleep ") })
            guard.update(0)
            assertTrue(assertions().isEmpty())
            guard.update(1)
        } finally {
            guard.close()
        }
        assertTrue(assertions().isEmpty())
    }

    @Test
    fun only_transfers_hold_sleep_and_the_twenty_percent_boundary_is_inclusive() {
        assertFalse(shouldHoldDownloadSleepAssertion(0, null))
        assertTrue(shouldHoldDownloadSleepAssertion(1, null))
        assertTrue(shouldHoldDownloadSleepAssertion(2, DownloadPowerState(false, 1)))
        assertFalse(shouldHoldDownloadSleepAssertion(2, DownloadPowerState(true, 19)))
        assertTrue(shouldHoldDownloadSleepAssertion(1, DownloadPowerState(true, 20)))
        assertTrue(shouldHoldDownloadSleepAssertion(1, DownloadPowerState(true, null)))
    }

    @Test
    fun battery_changes_pause_finish_and_quit_release_the_assertion() {
        var power = DownloadPowerState(true, 80)
        val held = mutableListOf<Boolean>()
        val guard = DownloadSleepGuard({ power }, held::add)
        guard.update(2)
        power = DownloadPowerState(true, 19)
        guard.update(2)
        power = DownloadPowerState(false, 19)
        guard.update(2)
        guard.update(0)
        guard.update(1)
        guard.close()
        guard.update(1)
        assertEquals(listOf(true, false, true, false, true, false), held)
    }

    @Test
    fun repeated_policy_checks_and_close_only_call_the_setter_when_the_held_state_changes() {
        val changes = mutableListOf<Boolean>()
        val guard = DownloadSleepGuard({ DownloadPowerState(false, 80) }, changes::add)
        repeat(100) { guard.update(0) }
        assertTrue(changes.isEmpty())
        repeat(100) { guard.update(2) }
        assertEquals(listOf(true), changes)
        repeat(100) { guard.update(1) }
        assertEquals(listOf(true), changes)
        repeat(100) { guard.update(0) }
        guard.close()
        guard.close()
        guard.update(1)
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun transfers_are_counted_until_the_last_body_ends_without_consuming_traffic_samples() {
        val traffic = DownloadTraffic()
        val first = traffic.begin(TransferEndpoint("one.test", 443))
        val second = traffic.begin(TransferEndpoint("two.test", 443))
        traffic.add(512)
        assertEquals(2, traffic.activeCount.value)
        traffic.end(first)
        assertEquals(1, traffic.activeCount.value)
        traffic.end(first)
        assertEquals(1, traffic.activeCount.value)
        traffic.end(second)
        assertEquals(0, traffic.activeCount.value)
        assertEquals(512L, traffic.takeBytes())
    }
}
