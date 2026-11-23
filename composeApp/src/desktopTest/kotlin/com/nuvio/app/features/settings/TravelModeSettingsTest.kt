package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.downloads.ConnectionHold
import com.nuvio.app.features.downloads.NetworkPathReport
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TravelModeSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun travel_controls_save_and_apply_download_changes_off_the_ui_thread() {
        val clickThread = Thread.currentThread()
        val saves = java.util.concurrent.LinkedBlockingQueue<Thread>()
        val updates = java.util.concurrent.LinkedBlockingQueue<Thread>()
        val hold = ConnectionHold(saveTravel = { saves.put(Thread.currentThread()) })
        hold.report(NetworkPathReport.Known(false, false, "en0/wifi|10.0.0.1@01:02:03:04:05:06"))
        hold.listen { updates.put(Thread.currentThread()) }
        var uiThread: Thread? = null
        compose.setContent { NuvioTheme { Column { TravelModeSettings(false, hold) } } }
        compose.runOnIdle { uiThread = Thread.currentThread() }
        fun checkWorkers() {
            val savedOn = saves.poll(5, java.util.concurrent.TimeUnit.SECONDS)
            val appliedOn = updates.poll(5, java.util.concurrent.TimeUnit.SECONDS)
            kotlin.test.assertNotNull(savedOn)
            kotlin.test.assertNotNull(appliedOn)
            kotlin.test.assertNotEquals(clickThread, savedOn)
            kotlin.test.assertNotEquals(clickThread, appliedOn)
            kotlin.test.assertNotEquals(uiThread, savedOn)
            kotlin.test.assertNotEquals(uiThread, appliedOn)
        }
        compose.onNodeWithText(runBlocking { getString(Res.string.travel_mode_title) }).performClick()
        checkWorkers()
        compose.onNodeWithText(runBlocking { getString(Res.string.travel_network_title) }).performClick()
        checkWorkers()
        compose.onNodeWithText(runBlocking { getString(Res.string.travel_speed_title) }).performClick()
        compose.onNodeWithText(runBlocking { getString(Res.string.travel_speed_value, 500) }).performClick()
        checkWorkers()
    }

    @Test
    fun settings_can_enable_travel_save_the_network_and_adjust_its_cap() {
        val hold = ConnectionHold()
        hold.report(NetworkPathReport.Known(false, false, "en0/wifi|10.0.0.1@01:02:03:04:05:06"))
        compose.setContent { NuvioTheme { Column { TravelModeSettings(false, hold) } } }
        val title = runBlocking { getString(Res.string.travel_mode_title) }
        compose.onNodeWithText(title).performClick()
        compose.waitUntil(5000) { hold.state.value.travel.enabled }
        compose.runOnIdle { assertTrue(hold.state.value.travel.enabled) }
        compose.onNodeWithText(runBlocking { getString(Res.string.travel_network_title) }).performClick()
        compose.waitUntil(5000) { hold.state.value.travel.remembered }
        compose.runOnIdle { assertTrue(hold.state.value.travel.remembered) }
        compose.onNodeWithText(runBlocking { getString(Res.string.travel_speed_title) }).performClick()
        compose.onNodeWithText(runBlocking { getString(Res.string.travel_speed_value, 500) }).performClick()
        compose.waitUntil(5000) { hold.state.value.travel.capBytesPerSecond == 500_000L }
        compose.runOnIdle { assertEquals(500_000L, hold.state.value.travel.capBytesPerSecond) }
        compose.onNodeWithText(title).performClick()
        compose.waitUntil(5000) { !hold.state.value.travel.enabled }
        compose.runOnIdle {
            assertFalse(hold.state.value.travel.enabled)
            assertFalse(hold.state.value.travel.remembered)
        }
        compose.onNodeWithText(runBlocking { getString(Res.string.travel_speed_value, 500) }).assertIsDisplayed()
    }
}
