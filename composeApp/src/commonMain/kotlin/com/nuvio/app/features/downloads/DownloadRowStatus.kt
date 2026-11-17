package com.nuvio.app.features.downloads

import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_announce_checking
import nuvio.composeapp.generated.resources.downloads_announce_completed
import nuvio.composeapp.generated.resources.downloads_announce_downloading
import nuvio.composeapp.generated.resources.downloads_announce_failed
import nuvio.composeapp.generated.resources.downloads_announce_drive_not_connected
import nuvio.composeapp.generated.resources.downloads_announce_moving
import nuvio.composeapp.generated.resources.downloads_announce_folder_not_set_up
import nuvio.composeapp.generated.resources.downloads_status_moving
import nuvio.composeapp.generated.resources.downloads_status_file_not_found
import nuvio.composeapp.generated.resources.downloads_announce_file_not_found
import nuvio.composeapp.generated.resources.downloads_status_folder_not_set_up
import nuvio.composeapp.generated.resources.downloads_announce_folder_not_found
import nuvio.composeapp.generated.resources.downloads_announce_waiting_drive
import nuvio.composeapp.generated.resources.downloads_status_drive_not_connected
import nuvio.composeapp.generated.resources.downloads_status_folder_not_found
import nuvio.composeapp.generated.resources.downloads_status_waiting_drive
import nuvio.composeapp.generated.resources.downloads_announce_paused
import nuvio.composeapp.generated.resources.downloads_announce_paused_low_disk
import nuvio.composeapp.generated.resources.downloads_announce_paused_storage_limit
import nuvio.composeapp.generated.resources.downloads_announce_reconnecting
import nuvio.composeapp.generated.resources.downloads_announce_waiting_network
import nuvio.composeapp.generated.resources.downloads_announce_waiting_connection
import nuvio.composeapp.generated.resources.downloads_announce_waiting_hotspot
import nuvio.composeapp.generated.resources.downloads_announce_waiting_low_data
import nuvio.composeapp.generated.resources.downloads_announce_waiting_slow
import nuvio.composeapp.generated.resources.downloads_announce_waiting_blocked
import nuvio.composeapp.generated.resources.downloads_status_waiting_slow
import nuvio.composeapp.generated.resources.downloads_status_waiting_blocked
import nuvio.composeapp.generated.resources.downloads_status_waiting_connection
import nuvio.composeapp.generated.resources.downloads_status_waiting_hotspot
import nuvio.composeapp.generated.resources.downloads_status_waiting_low_data
import nuvio.composeapp.generated.resources.downloads_result_basic
import nuvio.composeapp.generated.resources.downloads_result_could_not_compare
import nuvio.composeapp.generated.resources.downloads_status_not_real_video
import nuvio.composeapp.generated.resources.downloads_result_not_checked
import nuvio.composeapp.generated.resources.downloads_result_suspect
import nuvio.composeapp.generated.resources.downloads_result_verified_fingerprint
import nuvio.composeapp.generated.resources.downloads_result_verified_server
import nuvio.composeapp.generated.resources.downloads_result_verified_size
import nuvio.composeapp.generated.resources.downloads_status_checking
import nuvio.composeapp.generated.resources.downloads_status_completed
import nuvio.composeapp.generated.resources.downloads_status_downloading
import nuvio.composeapp.generated.resources.downloads_status_failed
import nuvio.composeapp.generated.resources.downloads_status_paused
import nuvio.composeapp.generated.resources.downloads_status_paused_low_disk
import nuvio.composeapp.generated.resources.downloads_status_paused_storage_limit
import nuvio.composeapp.generated.resources.downloads_status_reconnecting
import nuvio.composeapp.generated.resources.downloads_status_waiting_network
import org.jetbrains.compose.resources.StringResource

/** A running transfer that has received nothing for this long is shown as reconnecting. */
internal const val DownloadStalledAfterMs = 20_000L

/** What a download row says it is doing, worked out from the item alone. */
internal enum class DownloadPhase(
    val message: StringResource,
    /** Whether the message has a place for "downloaded / total". */
    val showsSize: Boolean,
    /**
     * What a screen reader says when the row enters this phase. It has no sizes, so that it changes
     * only when the phase does and not with every chunk that arrives.
     */
    val announcement: StringResource,
) {
    Downloading(Res.string.downloads_status_downloading, showsSize = true, Res.string.downloads_announce_downloading),

    /** The internet cannot be reached: the transfer waits and goes on by itself. */
    WaitingForNetwork(Res.string.downloads_status_waiting_network, showsSize = true, Res.string.downloads_announce_waiting_network),

    /** The internet looks reachable but nothing arrives: the transfer is being tried again. */
    Reconnecting(Res.string.downloads_status_reconnecting, showsSize = true, Res.string.downloads_announce_reconnecting),

    /** All bytes are in; the file and its pictures are being looked at. */
    CheckingFile(Res.string.downloads_status_checking, showsSize = false, Res.string.downloads_announce_checking),

    Paused(Res.string.downloads_status_paused, showsSize = true, Res.string.downloads_announce_paused),
    PausedStorageLimit(Res.string.downloads_status_paused_storage_limit, showsSize = true, Res.string.downloads_announce_paused_storage_limit),
    PausedLowDiskSpace(Res.string.downloads_status_paused_low_disk, showsSize = true, Res.string.downloads_announce_paused_low_disk),
    Completed(Res.string.downloads_status_completed, showsSize = true, Res.string.downloads_announce_completed),

    /** The file is there but is not the video: a placeholder the source sent. The row says why. */
    NotTheRealVideo(Res.string.downloads_status_not_real_video, showsSize = false, Res.string.downloads_status_not_real_video),
    Failed(Res.string.downloads_status_failed, showsSize = false, Res.string.downloads_announce_failed),

    /** The drive the file is on is not connected. The download is kept and plays again when it is back. */
    DriveNotConnected(Res.string.downloads_status_drive_not_connected, showsSize = false, Res.string.downloads_announce_drive_not_connected),

    /** The drive is there but the folder is not. */
    FolderNotFound(Res.string.downloads_status_folder_not_found, showsSize = false, Res.string.downloads_announce_folder_not_found),

    /** A finished download that has no recorded file. Only the user can say which file is its own. */
    FileNotFound(Res.string.downloads_status_file_not_found, showsSize = false, Res.string.downloads_announce_file_not_found),

    /** The item is in the hands of a move. */
    Moving(Res.string.downloads_status_moving, showsSize = false, Res.string.downloads_announce_moving),

    /** The folder is there but is not set up as a download location. Nothing is lost. */
    FolderNotSetUp(Res.string.downloads_status_folder_not_set_up, showsSize = false, Res.string.downloads_announce_folder_not_set_up),

    /** Paused because its location is away or being moved. It goes on by itself. */
    WaitingForDrive(Res.string.downloads_status_waiting_drive, showsSize = true, Res.string.downloads_announce_waiting_drive),

    /** Waiting because the Mac is on a hotspot or tethered. It goes on by itself when that ends. */
    WaitingOnHotspot(Res.string.downloads_status_waiting_hotspot, showsSize = true, Res.string.downloads_announce_waiting_hotspot),

    /** Waiting because the network uses Low Data Mode. It goes on by itself when that ends. */
    WaitingOnLowDataMode(Res.string.downloads_status_waiting_low_data, showsSize = true, Res.string.downloads_announce_waiting_low_data),

    /** Waiting because the network has been very slow. Automatic downloads only. */
    WaitingOnSlowNetwork(Res.string.downloads_status_waiting_slow, showsSize = true, Res.string.downloads_announce_waiting_slow),

    /** Waiting because the network seems to block downloads. */
    WaitingOnBlockedNetwork(Res.string.downloads_status_waiting_blocked, showsSize = true, Res.string.downloads_announce_waiting_blocked),

    /** Waiting for a better connection, at a moment when the reason is not known (it is just ending). */
    WaitingForConnection(Res.string.downloads_status_waiting_connection, showsSize = true, Res.string.downloads_announce_waiting_connection),
}

/** The row phase of a download that waits because the connection is limited for [reason]. */
internal fun waitingPhaseFor(reason: LimitedConnectionReason?): DownloadPhase = when (reason) {
    LimitedConnectionReason.Hotspot -> DownloadPhase.WaitingOnHotspot
    LimitedConnectionReason.LowDataMode -> DownloadPhase.WaitingOnLowDataMode
    LimitedConnectionReason.SlowConnection -> DownloadPhase.WaitingOnSlowNetwork
    LimitedConnectionReason.NetworkBlocksDownloads -> DownloadPhase.WaitingOnBlockedNetwork
    null -> DownloadPhase.WaitingForConnection
}

/** What was checked about a finished file, in the words the row shows. */
internal enum class DownloadResult(val message: StringResource) {
    /** The hash the source stated for its file matched. */
    VerifiedBySource(Res.string.downloads_result_verified_fingerprint),

    /** The fingerprint the server sent with the file matched. */
    VerifiedByServer(Res.string.downloads_result_verified_server),

    /** The size the server or source announced matched. */
    VerifiedBySize(Res.string.downloads_result_verified_size),

    /** Only that the file is there and is a video. */
    BasicCheck(Res.string.downloads_result_basic),

    /** Nothing is recorded: the download finished before checks were recorded. */
    NotChecked(Res.string.downloads_result_not_checked),

    /** The file's own title names another episode. */
    Suspect(Res.string.downloads_result_suspect),

    /** Not checked, and nothing to check it against: the source claimed no size and the title's running time is not known. */
    CouldNotCompare(Res.string.downloads_result_could_not_compare),
}

internal data class DownloadRowStatus(
    val phase: DownloadPhase,
    /** Set for a finished download. */
    val result: DownloadResult? = null,
    /** Frames of the finished file were decoded and looked like a video. */
    val pictureChecked: Boolean = false,
    /** Queued by an automatic-download rule. */
    val isAuto: Boolean = false,
)

/**
 * The sub-status of a download row.
 *
 * [nowEpochMs] is the time of the look and [offline] whether the internet cannot be reached; the
 * rest is read from [item]. Nothing here is stored. [runtimeMinutes], when given, finds the running
 * time of the title: a file that is not checked says "could not check" when there is no size claim
 * and no running time to hold it against. Without it that is not said.
 */
internal fun downloadRowStatus(
    item: DownloadItem,
    nowEpochMs: Long,
    offline: Boolean = false,
    /** Whether the location the item is in can be used. An item in one that cannot is neither failed nor missing. */
    location: FolderAvailability = FolderAvailability.Available,
    /** A move has the item. */
    moving: Boolean = false,
    /** Where locations are handled: a finished item without a recorded file shows as not found, never as found by name. */
    addressRequired: Boolean = false,
    runtimeMinutes: ((DownloadItem) -> Int?)? = null,
    /** Why the connection is limited now, or null. Says why a download that waits for a better connection waits. */
    limitedBy: LimitedConnectionReason? = null,
): DownloadRowStatus {
    val isAuto = item.autoDownloadRuleKey != null
    if (moving) return DownloadRowStatus(DownloadPhase.Moving, isAuto = isAuto)
    if (addressRequired && item.status == DownloadStatus.Completed && item.localFileUri == null) {
        return DownloadRowStatus(DownloadPhase.FileNotFound, isAuto = isAuto)
    }
    if (location != FolderAvailability.Available && (item.status == DownloadStatus.Completed || item.pausedByFolder)) {
        val phase = when {
            item.status != DownloadStatus.Completed -> DownloadPhase.WaitingForDrive
            location == FolderAvailability.NotConnected -> DownloadPhase.DriveNotConnected
            location == FolderAvailability.NotSetUp -> DownloadPhase.FolderNotSetUp
            else -> DownloadPhase.FolderNotFound
        }
        return DownloadRowStatus(phase, isAuto = isAuto)
    }
    if (item.status == DownloadStatus.Paused && item.pausedByFolder) {
        return DownloadRowStatus(DownloadPhase.WaitingForDrive, isAuto = isAuto)
    }
    if (item.status == DownloadStatus.Paused && item.pausedByConnection) {
        return DownloadRowStatus(waitingPhaseFor(limitedBy), isAuto = isAuto)
    }
    return when (item.status) {
        DownloadStatus.Downloading -> {
            val total = item.totalBytes
            val allBytesIn = total != null && total > 0L && item.downloadedBytes >= total
            val silentFor = nowEpochMs - item.updatedAtEpochMs
            val phase = when {
                allBytesIn -> DownloadPhase.CheckingFile
                offline -> DownloadPhase.WaitingForNetwork
                silentFor > DownloadStalledAfterMs -> DownloadPhase.Reconnecting
                else -> DownloadPhase.Downloading
            }
            DownloadRowStatus(phase, isAuto = isAuto)
        }
        DownloadStatus.Paused -> DownloadRowStatus(
            phase = when (item.autoDownloadStop) {
                AutoDownloadStop.StorageLimit -> DownloadPhase.PausedStorageLimit
                AutoDownloadStop.LowDiskSpace -> DownloadPhase.PausedLowDiskSpace
                null -> DownloadPhase.Paused
            },
            isAuto = isAuto,
        )
        DownloadStatus.Failed -> DownloadRowStatus(DownloadPhase.Failed, isAuto = isAuto)
        DownloadStatus.Completed -> if (item.isPlaceholder) {
            DownloadRowStatus(DownloadPhase.NotTheRealVideo, isAuto = isAuto)
        } else {
            val runtime = runtimeMinutes?.invoke(item)
            val cannotCompare = runtimeMinutes != null && item.framesCheck != DownloadFramesCheck.Passed &&
                item.claimedSizeBytes(runtime) == null && runtime == null
            DownloadRowStatus(
                phase = DownloadPhase.Completed,
                result = when {
                    item.framesCheck == DownloadFramesCheck.Suspect -> DownloadResult.Suspect
                    else -> when (item.verifiedBy) {
                        DownloadVerification.Hash -> DownloadResult.VerifiedBySource
                        DownloadVerification.Digest -> DownloadResult.VerifiedByServer
                        DownloadVerification.Size -> DownloadResult.VerifiedBySize
                        DownloadVerification.None -> if (cannotCompare) DownloadResult.CouldNotCompare else DownloadResult.BasicCheck
                        null -> if (cannotCompare) DownloadResult.CouldNotCompare else DownloadResult.NotChecked
                    }
                },
                pictureChecked = item.framesCheck == DownloadFramesCheck.Passed,
                isAuto = isAuto,
            )
        }
    }
}

/** The name of a download in a label that is read out of its row: "Pantheon S02E03", or the title of a movie. */
internal fun DownloadItem.nameForLabels(): String {
    val season = seasonNumber
    val episode = episodeNumber
    if (season == null || episode == null) return title
    return "$title S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
}
