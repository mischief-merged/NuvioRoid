package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.build.UPSTREAM_DESKTOP_VERSION
import com.nuvio.app.core.ui.NuvioTheme
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.fork_about_based_on
import nuvio.composeapp.generated.resources.fork_about_based_on_with_core
import nuvio.composeapp.generated.resources.fork_about_version
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class ForkAttributionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun formatsVersionAndBuildInTheEnglishResource() = runBlocking {
        assertEquals("Version 1.1.38.1 (build 3801)", getString(Res.string.fork_about_version, "1.1.38.1", 3801))
    }

    @Test
    fun formatsUpstreamWithAndWithoutTheCoreVersion() = runBlocking {
        assertEquals("Based on Nuvio Desktop 0.1.38", getString(Res.string.fork_about_based_on, "0.1.38"))
        assertEquals(
            "Based on Nuvio Desktop 0.1.38 (Nuvio 1.1.0-beta.2)",
            getString(Res.string.fork_about_based_on_with_core, "0.1.38", "1.1.0-beta.2"),
        )
    }

    @Test
    fun settingsFooterShowsTheCompiledReleaseAndUpstream() {
        compose.setContent {
            NuvioTheme { LazyColumn { settingsFooterContent(isTablet = false) } }
        }
        assertCurrentReleaseIsDisplayed()
    }

    @Test
    fun attributionWrapperShowsTheSameReleaseOnTablet() {
        compose.setContent {
            NuvioTheme { SettingsAttribution(isTablet = true) }
        }
        assertCurrentReleaseIsDisplayed()
    }

    private fun assertCurrentReleaseIsDisplayed() {
        // Read the compiled release values so the next release only changes the properties file.
        val parts = AppVersionConfig.DESKTOP_VERSION_NAME.split('.')
        val encodedPatch = parts[2].toInt()
        val forkVersion = "${parts[0]}.${parts[1]}.${encodedPatch / 100}.${encodedPatch % 100}"
        val core = AppVersionConfig.VERSION_NAME.takeIf { it != AppVersionConfig.DESKTOP_VERSION_NAME }
        val basedOn = "Based on Nuvio Desktop $UPSTREAM_DESKTOP_VERSION" + (core?.let { " (Nuvio $it)" } ?: "")
        compose.onNodeWithText("NuvioRoid").assertIsDisplayed()
        compose.onNodeWithText("Version $forkVersion (build ${AppVersionConfig.DESKTOP_VERSION_CODE})").assertIsDisplayed()
        compose.onNodeWithText(basedOn).assertIsDisplayed()
    }
}
