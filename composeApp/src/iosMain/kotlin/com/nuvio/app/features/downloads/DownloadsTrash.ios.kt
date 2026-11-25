package com.nuvio.app.features.downloads

internal actual object DownloadsTrash {
    actual val usesSystemTrash: Boolean = false
    actual val usesUndoableSystemTrash: Boolean = false
    actual val supportsWatchedCleanup: Boolean = false

    actual fun moveAway(localFileUri: String): Boolean = false

    actual fun moveWatchedAway(localFileUri: String, stillWanted: () -> Boolean): AutoDownloadRemovalResult =
        AutoDownloadRemovalResult.NotMoved

    actual fun moveWatchedAwayWithUndo(
        localFileUri: String,
        allowHoldingWhenNoSystemTrash: Boolean,
        includeSubtitles: Boolean,
        stillWanted: () -> Boolean,
        onMoved: (TrashedWatchedFile?) -> Boolean,
    ): WatchedTrashOutcome = WatchedTrashOutcome(AutoDownloadRemovalResult.NotMoved)

    actual fun restoreFromTrash(trashed: TrashedWatchedFile): UndoTrashResult = UndoTrashResult.NotRestored

    actual fun restoreFromTrashWithOutcome(trashed: TrashedWatchedFile): UndoRestoreOutcome =
        UndoRestoreOutcome(UndoTrashResult.NotRestored)

    actual fun trashSubtitlesForUndoRollback(
        trashed: TrashedWatchedFile,
        onMoved: (TrashedWatchedFile) -> Boolean,
    ): WatchedTrashOutcome = WatchedTrashOutcome(AutoDownloadRemovalResult.NotMoved)

    actual fun isSameFile(firstFileUri: String, secondFileUri: String): Boolean = firstFileUri == secondFileUri

    actual fun purgeHeldFiles(nowEpochMs: Long): Int = 0
}
