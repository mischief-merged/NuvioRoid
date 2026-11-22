package com.nuvio.app.features.downloads

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

    /** Whether two addresses name the same file, however they are spelled. */
    fun isSameFile(firstFileUri: String, secondFileUri: String): Boolean

    /**
     * When the system Trash can be used, moves aged held files there. Otherwise leaves them.
     * Returns how many were moved.
     */
    fun purgeHeldFiles(nowEpochMs: Long): Int
}

internal const val HeldFileDays = 7
