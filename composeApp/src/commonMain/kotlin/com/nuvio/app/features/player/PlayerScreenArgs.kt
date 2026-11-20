package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.features.party.PartyMedia

internal typealias PlayerReleaseBeforeBack = (
    onReleased: () -> Unit,
    onReleaseFailed: (String) -> Unit,
) -> Unit
internal typealias PlayerBackRequest = (releaseBeforeBack: PlayerReleaseBeforeBack) -> Unit

/**
 * Where to go once the player has been left through the usual Back path. It carries the show and
 * episode that are playing now, which can differ from what the player was opened with.
 */
internal sealed interface PlayerExitTarget {
    data class Show(val type: String, val id: String, val title: String) : PlayerExitTarget

    data class Episode(
        val type: String,
        val id: String,
        val title: String,
        val videoId: String?,
        val seasonNumber: Int?,
        val episodeNumber: Int?,
    ) : PlayerExitTarget
}

internal data class PlayerScreenArgs(
    val profileId: Int,
    val title: String,
    val sourceUrl: String,
    val sourceAudioUrl: String?,
    val sourceHeaders: Map<String, String>,
    val sourceResponseHeaders: Map<String, String>,
    val streamType: String?,
    val providerName: String,
    val streamTitle: String,
    val streamSubtitle: String?,
    val initialBingeGroup: String?,
    val pauseDescription: String?,
    val onBack: PlayerBackRequest,
    val onSystemBackHandlerChanged: (handler: (() -> Unit)?) -> Unit = {},
    /** Told, before every Back request, where to go after it: null for a plain Back. */
    val onExitTarget: (PlayerExitTarget?) -> Unit = {},
    val onOpenInExternalPlayer: ((ExternalPlayerPlaybackRequest) -> Unit)?,
    val onOpenExternalUrl: ((String) -> Unit)?,
    val modifier: Modifier,
    val logo: String?,
    val poster: String?,
    val background: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeTitle: String?,
    val episodeThumbnail: String?,
    val contentType: String?,
    val videoId: String?,
    val parentMetaId: String,
    val parentMetaType: String,
    val providerAddonId: String?,
    val externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle> = emptyList(),
    val startupSubtitleSelection: StartupSubtitleSelection? = null,
    val torrentInfoHash: String?,
    val torrentFileIdx: Int?,
    val torrentFilename: String?,
    val torrentTrackers: List<String>,
    val initialPositionMs: Long,
    val initialProgressFraction: Float?,
    val contentLanguage: String? = null,
    val subtitleExtras: SubtitleRequestExtras? = null,
    val launchId: Long? = null,
    val sourceFilename: String? = null,
    val onOpenPartyMedia: (PartyMedia) -> Unit = {},
    val stream: com.nuvio.app.features.streams.StreamItem? = null,
    val sourceFromLinkCache: Boolean = false,
    val sourceAutoSelected: Boolean = false,
)
