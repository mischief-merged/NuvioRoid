package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NextEpisodeFallbackPolicyTest {
    private fun stream(
        name: String,
        url: String,
        bingeGroup: String?,
        addonId: String = "comet",
    ) = StreamItem(
        name = name,
        url = url,
        addonName = "Comet",
        addonId = addonId,
        behaviorHints = StreamBehaviorHints(bingeGroup = bingeGroup),
    )

    @Test
    fun manual_mode_with_fallback_when_binge_group_fails_off_is_binge_group_only() {
        val fallbackOff = PlayerSettingsUiState(
            streamAutoPlayMode = StreamAutoPlayMode.MANUAL,
            streamAutoPlayNextEpisodeEnabled = true,
            streamAutoPlayNextEpisodeFallbackEnabled = false,
            streamAutoPlayPreferBingeGroup = true,
        )
        assertTrue(isNextEpisodeBingeGroupOnly(fallbackOff))

        val autoPlayNextOff = PlayerSettingsUiState(
            streamAutoPlayMode = StreamAutoPlayMode.MANUAL,
            streamAutoPlayNextEpisodeEnabled = false,
            streamAutoPlayNextEpisodeFallbackEnabled = true,
            streamAutoPlayPreferBingeGroup = true,
        )
        assertTrue(isNextEpisodeBingeGroupOnly(autoPlayNextOff))

        val fallbackOn = PlayerSettingsUiState(
            streamAutoPlayMode = StreamAutoPlayMode.MANUAL,
            streamAutoPlayNextEpisodeEnabled = true,
            streamAutoPlayNextEpisodeFallbackEnabled = true,
            streamAutoPlayPreferBingeGroup = true,
        )
        assertFalse(isNextEpisodeBingeGroupOnly(fallbackOn))

        val nonManual = PlayerSettingsUiState(
            streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM,
            streamAutoPlayNextEpisodeEnabled = true,
            streamAutoPlayNextEpisodeFallbackEnabled = false,
            streamAutoPlayPreferBingeGroup = true,
        )
        assertFalse(isNextEpisodeBingeGroupOnly(nonManual))
    }

    @Test
    fun binge_group_only_fallback_stays_within_binge_group_and_never_escapes_to_other_groups() {
        val settings = PlayerSettingsUiState(
            streamAutoPlayMode = StreamAutoPlayMode.MANUAL,
            streamAutoPlayNextEpisodeEnabled = true,
            streamAutoPlayNextEpisodeFallbackEnabled = false,
            streamAutoPlayPreferBingeGroup = true,
            streamAutoPlayFallbackOnFailure = true,
        )
        val groupA1 = stream("Group A copy 1", "https://cdn.example/a1.mkv", bingeGroup = "grp-a")
        val groupA2 = stream("Group A copy 2", "https://cdn.example/a2.mkv", bingeGroup = "grp-a")
        val groupB = stream("Group B copy", "https://cdn.example/b1.mkv", bingeGroup = "grp-b")
        val ungrouped = stream("Ungrouped", "https://cdn.example/u1.mkv", bingeGroup = null)
        val all = listOf(groupA1, groupB, ungrouped, groupA2)

        val triedFirst = setOfNotNull(groupA1.playerSourceIdentityKey())
        val second = nextEpisodeFallbackCandidate(
            streams = all,
            settings = settings,
            triedKeys = triedFirst,
            preferredBingeGroup = "grp-a",
            bingeGroupOnly = isNextEpisodeBingeGroupOnly(settings),
            installedAddonNames = setOf("Comet"),
            debridEnabled = false,
            activeResolverProviderId = null,
        )
        assertEquals(groupA2, second, "another untried stream in the same binge group may be tried")

        val triedBothInGroup = setOfNotNull(groupA1.playerSourceIdentityKey(), groupA2.playerSourceIdentityKey())
        val escaped = nextEpisodeFallbackCandidate(
            streams = all,
            settings = settings,
            triedKeys = triedBothInGroup,
            preferredBingeGroup = "grp-a",
            bingeGroupOnly = isNextEpisodeBingeGroupOnly(settings),
            installedAddonNames = setOf("Comet"),
            debridEnabled = false,
            activeResolverProviderId = null,
        )
        assertNull(escaped, "must not escape to another binge group when fallback when binge group fails is off")
    }

    @Test
    fun binge_group_only_with_no_preferred_binge_group_returns_null() {
        val settings = PlayerSettingsUiState(
            streamAutoPlayMode = StreamAutoPlayMode.MANUAL,
            streamAutoPlayNextEpisodeEnabled = true,
            streamAutoPlayNextEpisodeFallbackEnabled = false,
            streamAutoPlayPreferBingeGroup = true,
        )
        val other = stream("Other", "https://cdn.example/other.mkv", bingeGroup = "grp-b")

        val candidate = nextEpisodeFallbackCandidate(
            streams = listOf(other),
            settings = settings,
            triedKeys = emptySet(),
            preferredBingeGroup = null,
            bingeGroupOnly = true,
            installedAddonNames = setOf("Comet"),
            debridEnabled = false,
            activeResolverProviderId = null,
        )
        assertNull(candidate)
    }

    @Test
    fun manual_mode_with_fallback_when_binge_group_fails_on_tries_binge_group_first_then_other_groups() {
        val settings = PlayerSettingsUiState(
            streamAutoPlayMode = StreamAutoPlayMode.MANUAL,
            streamAutoPlayNextEpisodeEnabled = true,
            streamAutoPlayNextEpisodeFallbackEnabled = true,
            streamAutoPlayPreferBingeGroup = true,
        )
        val groupA1 = stream("Group A copy 1", "https://cdn.example/a1.mkv", bingeGroup = "grp-a")
        val groupA2 = stream("Group A copy 2", "https://cdn.example/a2.mkv", bingeGroup = "grp-a")
        val groupB = stream("Group B copy", "https://cdn.example/b1.mkv", bingeGroup = "grp-b")
        val all = listOf(groupB, groupA1, groupA2)

        val second = nextEpisodeFallbackCandidate(
            streams = all,
            settings = settings,
            triedKeys = setOfNotNull(groupA1.playerSourceIdentityKey()),
            preferredBingeGroup = "grp-a",
            bingeGroupOnly = isNextEpisodeBingeGroupOnly(settings),
            installedAddonNames = setOf("Comet"),
            debridEnabled = false,
            activeResolverProviderId = null,
        )
        assertEquals(groupA2, second, "prefers another stream in the same binge group first")

        val third = nextEpisodeFallbackCandidate(
            streams = all,
            settings = settings,
            triedKeys = setOfNotNull(groupA1.playerSourceIdentityKey(), groupA2.playerSourceIdentityKey()),
            preferredBingeGroup = "grp-a",
            bingeGroupOnly = isNextEpisodeBingeGroupOnly(settings),
            installedAddonNames = setOf("Comet"),
            debridEnabled = false,
            activeResolverProviderId = null,
        )
        assertEquals(groupB, third, "falls back to another group once the binge group is exhausted")
    }

    @Test
    fun resolve_with_source_fallback_stops_without_playing_other_group_when_binge_group_only_fails() = runBlocking {
        val settings = PlayerSettingsUiState(
            streamAutoPlayMode = StreamAutoPlayMode.MANUAL,
            streamAutoPlayNextEpisodeEnabled = true,
            streamAutoPlayNextEpisodeFallbackEnabled = false,
            streamAutoPlayPreferBingeGroup = true,
            streamAutoPlayFallbackOnFailure = true,
        )
        val groupA = stream("Group A", "https://cdn.example/a1.mkv", bingeGroup = "grp-a")
        val groupB = stream("Group B", "https://cdn.example/b1.mkv", bingeGroup = "grp-b")
        val attempted = mutableListOf<StreamItem>()

        val run = resolveWithSourceFallback<StreamItem, String>(
            first = groupA,
            maxSources = settings.streamAutoPlayFallbackMaxSources,
            resolve = { candidate ->
                attempted += candidate
                if (candidate == groupB) {
                    PlayerSourceResolveAttempt.Resolved(candidate)
                } else {
                    PlayerSourceResolveAttempt.Failed("expired")
                }
            },
            nextCandidate = { failed, triedKeys ->
                nextEpisodeFallbackCandidate(
                    streams = listOf(groupA, groupB),
                    settings = settings,
                    triedKeys = triedKeys,
                    failedAddonId = failed.addonId,
                    failedInfoHash = failed.p2pInfoHash,
                    preferredBingeGroup = "grp-a",
                    bingeGroupOnly = isNextEpisodeBingeGroupOnly(settings),
                    installedAddonNames = setOf("Comet"),
                    debridEnabled = false,
                    activeResolverProviderId = null,
                )
            },
        )

        assertNull(run.resolved, "must fall back to manual selection instead of playing another binge group")
        assertEquals("expired", run.failure)
        assertEquals(listOf(groupA), attempted)
    }
}
