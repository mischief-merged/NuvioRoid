package com.nuvio.app.features.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.isDesktop
import com.nuvio.app.isIos
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.playbackSettingsContent(
    isTablet: Boolean,
    page: SettingsPage,
    onPageChange: (SettingsPage) -> Unit,
) {
    item {
        val settings by remember {
            PlayerSettingsRepository.ensureLoaded()
            PlayerSettingsRepository.uiState
        }.collectAsStateWithLifecycle()
        when (page) {
            SettingsPage.Playback -> {
                val sections = visiblePlaybackSections(isIos, settings.androidPlaybackEngine, P2pSettingsRepository.isVisible)
                SettingsGroup(isTablet = isTablet) {
                    sections.forEachIndexed { index, section ->
                        if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(section.page.titleRes),
                            description = stringResource(section.descriptionRes),
                            isTablet = isTablet,
                            onClick = { onPageChange(section.page) },
                        )
                    }
                    if (isDesktop) {
                        DesktopDownloadSettings(isTablet)
                    }
                }
            }
            SettingsPage.PlaybackPlayer -> PlaybackPlayerSettings(isTablet, settings)
            SettingsPage.PlaybackStreamSelection -> PlaybackStreamSettings(isTablet, settings)
            SettingsPage.PlaybackPreferences -> PlaybackPreferencesSettings(
                isTablet = isTablet,
                settings = settings,
                onOpenStreamAutoPlay = { onPageChange(SettingsPage.PlaybackStreamSelection) },
            )
            SettingsPage.PlaybackUpNext -> PlaybackUpNextSettings(isTablet, settings)
            SettingsPage.PlaybackSkipSegments -> PlaybackSkipSettings(isTablet, settings)
            SettingsPage.PlaybackInterface -> PlaybackInterfaceSettings(isTablet, settings)
            SettingsPage.PlaybackAudio -> PlaybackAudioSettings(isTablet, settings)
            SettingsPage.PlaybackSubtitles -> PlaybackSubtitleSettings(isTablet, settings)
            SettingsPage.PlaybackVideo -> PlaybackVideoSettings(isTablet, settings)
            SettingsPage.PlaybackBuffer -> if (!isIos) PlaybackBufferSettings(isTablet, settings)
            SettingsPage.PlaybackP2p -> if (P2pSettingsRepository.isVisible) PlaybackP2pSettings(isTablet)
            else -> Unit
        }
    }
}

private const val DownloadSettingsHighlightMs = 4_000L

/**
 * Download speed, hotspot, letting other apps go first, the download folder, and delete after watching.
 * When Downloads asked for this section, it is scrolled into view and marked for a moment.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DesktopDownloadSettings(isTablet: Boolean) {
    val bringIntoView = remember { BringIntoViewRequester() }
    var highlighted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!SettingsSectionFocus.consume(SettingsSectionFocus.PlaybackDownloads)) return@LaunchedEffect
        highlighted = true
        withFrameNanos { }
        bringIntoView.bringIntoView()
        delay(DownloadSettingsHighlightMs)
        highlighted = false
    }
    Column(
        modifier = Modifier
            .bringIntoViewRequester(bringIntoView)
            .then(
                if (highlighted) {
                    Modifier.background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        RoundedCornerShape(12.dp),
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        SettingsGroupDivider(isTablet = isTablet)
        DownloadSpeedSetting(isTablet)
        SettingsGroupDivider(isTablet = isTablet)
        LimitedConnectionSetting(isTablet)
        SettingsGroupDivider(isTablet = isTablet)
        TravelModeSettings(isTablet)
        SettingsGroupDivider(isTablet = isTablet)
        PoliteDownloadsSetting(isTablet)
        KeepDownloadingSetting(isTablet)
        SettingsGroupDivider(isTablet = isTablet)
        DownloadFolderSetting(isTablet)
        SettingsGroupDivider(isTablet = isTablet)
        FreeSpaceFloorSetting(isTablet)
        SettingsGroupDivider(isTablet = isTablet)
        val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
        val activeProfileId = profileState.activeProfile?.profileIndex ?: ProfileRepository.activeProfileId
        TotalStorageLimitSetting(isTablet, profileId = activeProfileId)
        SettingsGroupDivider(isTablet = isTablet)
        AutoDownloadStorageLimitSetting(isTablet, profileId = activeProfileId)
        SettingsGroupDivider(isTablet = isTablet)
        MakeRoomAutomaticallySetting(isTablet, profileId = activeProfileId)
        SettingsGroupDivider(isTablet = isTablet)
        DeleteAfterWatchingSetting(isTablet, profileId = activeProfileId)
    }
}
