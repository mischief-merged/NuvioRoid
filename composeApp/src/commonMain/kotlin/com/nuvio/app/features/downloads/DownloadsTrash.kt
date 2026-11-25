package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable

/** Address and identity of a watched file moved to the Trash, so Undo can put it back while still there. */
@Serializable
internal data class TrashedWatchedFile(
    val originalFileUri: String,
    val trashedFileUri: String? = null,
    val originalSubtitlesUri: String? = null,
    val trashedSubtitlesUri: String? = null,
    val fileKey: String? = null,
    val heldInArchive: Boolean = false,
) {
    val canRestore: Boolean
        get() = !trashedFileUri.isNullOrBlank()
}

internal enum class UndoTrashResult {
    Restored,
    FileGone,
    TargetTaken,
    NotReachable,
    NotRestored,
}

internal data class WatchedTrashOutcome(
    val result: AutoDownloadRemovalResult,
    val trashed: TrashedWatchedFile? = null,
)

internal data class UndoRestoreOutcome(
    val result: UndoTrashResult,
    val updatedTrashed: TrashedWatchedFile? = null,
)

/** Takes the file of a download out of the downloads folder without deleting it. */
internal expect object DownloadsTrash {
    /**
     * True when files go to the trash of the system. False when there is none the app can use:
     * the file then stays in the archive folder until you remove it or the Trash is available.
     */
    val usesSystemTrash: Boolean

    /** True when the file is out of the downloads folder, or was already gone. */
    fun moveAway(localFileUri: String): Boolean

    /** Moves to system Trash with a recoverable copy until the final check succeeds. */
    fun moveWatchedAway(localFileUri: String, stillWanted: () -> Boolean): AutoDownloadRemovalResult

    /** Moves to Trash and returns the trashed address when Undo can restore it. */
    fun moveWatchedAwayWithUndo(
        localFileUri: String,
        allowHoldingWhenNoSystemTrash: Boolean = false,
        stillWanted: () -> Boolean = { true },
        onMoved: (TrashedWatchedFile?) -> Boolean = { true },
    ): WatchedTrashOutcome

    /** Restores a trashed watched download back to its original folder if it is still in the Trash. */
    fun restoreFromTrash(trashed: TrashedWatchedFile): UndoTrashResult

    /** Whether two addresses name the same file, however they are spelled. */
    fun isSameFile(firstFileUri: String, secondFileUri: String): Boolean

    /**
     * When the system Trash can be used, moves aged held files there. Otherwise leaves them.
     * Returns how many were moved.
     */
    fun purgeHeldFiles(nowEpochMs: Long): Int
}

internal const val HeldFileDays = 7
