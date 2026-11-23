package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_download_speed_limit_title
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackDownloadsFocusTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun clear_focus() {
        SettingsSectionFocus.clear()
    }

    @Test
    fun a_request_is_consumed_once_and_other_pages_drop_it() {
        SettingsSectionFocus.request(SettingsSectionFocus.PlaybackDownloads)
        assertEquals(SettingsSectionFocus.PlaybackDownloads, SettingsSectionFocus.pending)
        assertTrue(SettingsSectionFocus.consume(SettingsSectionFocus.PlaybackDownloads))
        assertFalse(SettingsSectionFocus.consume(SettingsSectionFocus.PlaybackDownloads))
        assertNull(SettingsSectionFocus.pending)

        SettingsSectionFocus.request(SettingsSectionFocus.PlaybackDownloads)
        SettingsSectionFocus.clear()
        assertNull(SettingsSectionFocus.pending)
    }

    @Test
    fun the_playback_page_consumes_a_download_settings_request_and_shows_the_section() {
        SettingsSectionFocus.request(SettingsSectionFocus.PlaybackDownloads)
        compose.setContent {
            NuvioTheme {
                Box(Modifier.height(240.dp)) {
                    LazyColumn {
                        playbackSettingsContent(isTablet = false, page = SettingsPage.Playback) {}
                    }
                }
            }
        }
        compose.waitForIdle()

        assertNull(SettingsSectionFocus.pending)
        compose.onNodeWithText(runBlocking { getString(Res.string.settings_download_speed_limit_title) }).assertExists()
    }
}
