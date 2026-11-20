package com.nuvio.app.features.downloads

internal actual object DownloadsTrash {
    actual val usesSystemTrash: Boolean = false

    actual fun moveAway(localFileUri: String): Boolean = false

    actual fun moveWatchedAway(localFileUri: String, stillWanted: () -> Boolean): AutoDownloadRemovalResult =
        AutoDownloadRemovalResult.NotMoved

    actual fun isSameFile(firstFileUri: String, secondFileUri: String): Boolean = firstFileUri == secondFileUri

    actual fun purgeHeldFiles(nowEpochMs: Long): Int = 0
}
