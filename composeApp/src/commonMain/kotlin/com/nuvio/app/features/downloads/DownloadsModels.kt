package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.SubtitleAddonRequest
import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.serialization.Serializable
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_enqueue_missing_url
import nuvio.composeapp.generated.resources.downloads_enqueue_replaced
import nuvio.composeapp.generated.resources.downloads_enqueue_started
import nuvio.composeapp.generated.resources.downloads_enqueue_unsupported_format
import org.jetbrains.compose.resources.getString

@Serializable
enum class DownloadStatus {
    Downloading,
    Paused,
    Completed,
    Failed,
}

/** Why a download failed, kept so a retry can do the right thing. Not stored for a download that failed before this was recorded. */
@Serializable
enum class DownloadFailureKind {
    /** The source gave a file that did not pass its checks. Fetching it again gives the same file. */
    BadFile,

    /** The source refused the request, or the link no longer works. */
    SourceRefused,

    /** The disk had too little room for the file. */
    NotEnoughSpace,

    /** Anything else: a lost connection, a server error, a file that went missing. Trying again may work. */
    Other,
}

@Serializable
data class KeptPartialFile(
    val fileName: String,
    val partialFileKey: String,
    val locationId: String? = null,
)

@Serializable
data class DownloadClientResolve(
    val type: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val magnetUri: String? = null,
    val sources: List<String> = emptyList(),
    val torrentName: String? = null,
    val filename: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val service: String? = null,
    val isCached: Boolean? = null,
    val rawFilename: String? = null,
    val rawSize: Long? = null,
    val debridProviderId: String? = null,
    val debridProviderName: String? = null,
)

@Serializable
data class DownloadItem(
    val id: String,
    val contentType: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val videoId: String,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val streamTitle: String,
    val streamSubtitle: String? = null,
    val providerName: String,
    val providerAddonId: String? = null,
    val sourceUrl: String,
    val sourceHeaders: Map<String, String> = emptyMap(),
    val sourceResponseHeaders: Map<String, String> = emptyMap(),
    val subtitleRequests: List<SubtitleAddonRequest> = emptyList(),
    val sourceSubtitles: List<StreamSubtitle> = emptyList(),
    val localFileUri: String? = null,
    val fileName: String,
    val status: DownloadStatus,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val errorMessage: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    /** The OpenSubtitles hash the source stated for its file, to check the finished file against. */
    val expectedVideoHash: String? = null,
    /** The size in bytes the source stated for its file. */
    val expectedSizeBytes: Long? = null,
    /** What the finished file was checked against. Null for a download that finished before this was recorded. */
    val verifiedBy: DownloadVerification? = null,
    /**
     * What a look at decoded frames from the start and the end of the finished file came to. Not
     * checked for a download that finished before this was recorded and where nothing could look.
     */
    val framesCheck: DownloadFramesCheck = DownloadFramesCheck.NotChecked,
    /**
     * The audio language playback of this file starts on: set when the language chosen for the
     * download is in the file but another track comes first.
     */
    val preferredAudioLanguage: String? = null,
    /** The automatic-download rule that queued this item. Null for a download the user started. */
    val autoDownloadRuleKey: String? = null,
    /** Queued by a trip fill; kept for storage accounting across restarts. */
    val tripFill: Boolean = false,
    /** The limit that paused this download. Cleared when it is resumed or paused by hand. */
    val autoDownloadStop: AutoDownloadStop? = null,
    /** The size in bytes a download waiting on a storage limit needs before it can fit. */
    val waitingSizeBytes: Long? = null,
    /** Why this source failed. Also set on a finished file rejected by a batch; null when no failure was recorded. */
    val failureKind: DownloadFailureKind? = null,
    /**
     * The download location this item lives in or is going to. Null for an item written before
     * locations existed, and on platforms without them: that is the app's own downloads folder.
     */
    val locationId: String? = null,
    /** Paused because its location cannot be reached or is being moved. It resumes by itself when it can. */
    val pausedByFolder: Boolean = false,
    /**
     * Waiting because the connection is limited (a hotspot, a Low Data Mode network) and the setting
     * to pause on such connections is on. It goes on by itself, from its partial file, when that ends.
     */
    val pausedByConnection: Boolean = false,
    /** Waiting because the drive of its download location dropped below the free-space floor. Resumes when space returns. */
    val pausedByFreeSpace: Boolean = false,
    /**
     * The identity of the partial file this download created, recorded when it was created, so that only
     * that file is ever resumed, cut back or removed; a file of the same name that does not have it is not
     * this download's. Null before a partial file was created.
     */
    val partialFileKey: String? = null,
    /** The published file is saved but still awaits its checks. Resuming checks never starts a transfer. */
    val awaitingCheck: Boolean = false,
    /** A verified body digest kept across a restart while its published file awaits checks. */
    val publishedDigestVerified: Boolean = false,
    /** The identity of the published file, saved before its partial name is removed. */
    val publishedFileKey: String? = null,
    /**
     * Every source already downloaded for this title, as [sourceIdentityKeys] write them, this one's and
     * those of the downloads it replaced or sits beside. A source the app picks by itself is never one of them.
     */
    val triedSources: List<String> = emptyList(),
    /** How many sources the app has started by itself for this title, each after a source that was bad. */
    val autoSourceAttempts: Int = 0,
    /** The app looked for a working source by itself and found none. Only the user picks one now. */
    val noWorkingSourceFound: Boolean = false,
    /** Normalized torrent info hash of the chosen source, when present. */
    val sourceInfoHash: String? = null,
    /** File index within a multi-file torrent of the chosen source, when present. */
    val sourceFileIdx: Int? = null,
    /** Release or file name stated by the source or debrid resolver, when present. */
    val sourceFilename: String? = null,
    /** File size in bytes stated by the source or debrid resolver, when present. */
    val sourceFileSizeBytes: Long? = null,
    /** Binge group identifier of the chosen source, when present. */
    val sourceBingeGroup: String? = null,
    /** Whether the download URL was obtained through Nuvio's local debrid resolver. */
    val sourceDebridResolved: Boolean = false,
    /** Metadata needed to re-resolve the same source directly with the debrid resolver. */
    val sourceClientResolve: DownloadClientResolve? = null,
    /** Strong ETag returned by the server for the active partial file. */
    val sourceETag: String? = null,
    /** Last-Modified header returned by the server for the active partial file. */
    val sourceLastModified: String? = null,
    /** Active partial file name inside the download's folder when it differs from `$fileName.part`. */
    val partialFileName: String? = null,
    /** Older partials kept untouched while a refreshed link downloads from scratch into a new partial. */
    val supersededPartials: List<KeptPartialFile> = emptyList(),
    /** Whether the current [sourceUrl] came from a link refresh and still needs verification before joining the active partial. */
    val pendingLinkVerification: Boolean = false,
) {
    val activePartialFileName: String
        get() = partialFileName?.takeIf { it.isNotBlank() } ?: "$fileName.part"

    val isEpisode: Boolean
        get() = seasonNumber != null && episodeNumber != null

    /** A failed download whose source is the problem, so that trying the same source again cannot help. */
    val needsAnotherSource: Boolean
        get() = status == DownloadStatus.Failed &&
            (failureKind == DownloadFailureKind.BadFile || failureKind == DownloadFailureKind.SourceRefused)

    /**
     * A finished download whose file is not suitable: a placeholder, or a source rejected by the batch
     * checks (including the chosen audio language). It keeps its file for the user to trash, but it
     * does not play, does not count as available offline and is replaced by a fetch from another source.
     */
    val isPlaceholder: Boolean
        get() = status == DownloadStatus.Completed &&
            (framesCheck == DownloadFramesCheck.NotTheRealVideo || failureKind == DownloadFailureKind.BadFile)

    /** Its source is the problem: a file that is not the video, or a source that failed in a way trying it again cannot fix. */
    val hasBadSource: Boolean
        get() = needsAnotherSource || isPlaceholder

    val isPlayable: Boolean
        get() = status == DownloadStatus.Completed && !localFileUri.isNullOrBlank() && !isPlaceholder

    val displaySubtitle: String
        get() = episodeTitle.orEmpty()

    val progressFraction: Float
        get() {
            val total = totalBytes?.takeIf { it > 0L } ?: return 0f
            return (downloadedBytes.toDouble() / total.toDouble())
                .toFloat()
                .coerceIn(0f, 1f)
        }

    val logicalContentKey: String
        get() = if (isEpisode) {
            "${parentMetaId.trim()}|${seasonNumber ?: -1}|${episodeNumber ?: -1}"
        } else {
            "${parentMetaId.trim()}|movie"
        }
}

/** What went wrong with the stored list of downloads when it was loaded. */
enum class DownloadsStoreProblem {
    /** The list could not be read and was kept aside. An earlier copy of it is shown instead. */
    EarlierCopyShown,

    /** The list could not be read and was kept aside. Nothing could be read in its place, so the list starts empty. */
    StartedEmpty,

    /** The list could not be read and could not be kept aside. It is left as it is, and nothing is saved until the app restarts. */
    NotSaving,
}

data class DownloadsUiState(
    val items: List<DownloadItem> = emptyList(),
    /** Set when the stored list could not be read. The screen must not present the list as simply empty then. */
    val storeProblem: DownloadsStoreProblem? = null,
    /** Transfers whose bodies are finished but whose files are still being checked. Never persisted. */
    val checkingIds: Set<String> = emptySet(),
    /** Files in download folders that could not be matched confidently during a scan. */
    val unrecognisedFiles: List<UnrecognisedFile> = emptyList(),
) {
    /** What is not downloaded: running, paused, failed, and finished files that are not the video. */
    val activeItems: List<DownloadItem>
        get() = items.filter { it.status != DownloadStatus.Completed || it.isPlaceholder }

    val completedItems: List<DownloadItem> = items.filter { it.status == DownloadStatus.Completed && !it.isPlaceholder }

    /** The screen's structure; running rows subscribe to their own counters. */
    internal fun withoutProgress() = copy(items = items.map { item ->
        if (item.status == DownloadStatus.Downloading) item.copy(downloadedBytes = 0, totalBytes = null, updatedAtEpochMs = 0) else item
    })
}

enum class DownloadEnqueueResult {
    Started,
    Replaced,
    MissingUrl,
    UnsupportedFormat;

    fun toastMessage(): String = runBlocking {
        when (this@DownloadEnqueueResult) {
            Started -> getString(Res.string.downloads_enqueue_started)
            Replaced -> getString(Res.string.downloads_enqueue_replaced)
            MissingUrl -> getString(Res.string.downloads_enqueue_missing_url)
            UnsupportedFormat -> getString(Res.string.downloads_enqueue_unsupported_format)
        }
    }
}

internal fun List<DownloadItem>.sortedForSeriesDownloads(): List<DownloadItem> =
    sortedWith(downloadSeriesEpisodeComparator)

internal val downloadSeriesEpisodeComparator: Comparator<DownloadItem> =
    compareBy<DownloadItem> { it.seasonNumber ?: Int.MAX_VALUE }
        .thenBy { it.episodeNumber ?: Int.MAX_VALUE }
        .thenBy { it.episodeTitle?.trim().orEmpty().lowercase() }
        .thenBy { it.title.trim().lowercase() }
        .thenBy { it.id }
