package com.nuvio.app.features.downloads

internal expect object DownloadsStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
    val batchesProgress: Boolean
    fun saveProgress(profileId: Int, items: List<DownloadItem>): Boolean

    /** The payload from before the last one that was saved, where the platform keeps it. */
    fun loadPreviousPayload(): String?

    /** Keeps a payload that could not be read under a key of its own, so that it is never overwritten. */
    fun saveUnreadablePayload(payload: String, atEpochMs: Long)

    /** Whether [loadFreshPayload] reads the payload again from where it is kept (another copy of the app may have saved). */
    val rereadsPayload: Boolean

    /** The payload as it is kept now, read again; null where the platform does not re-read. */
    fun loadFreshPayload(): String?

    /** Flushes the saved list to disk, file and folder. True where the platform has nothing more to do. */
    fun syncPayload(): Boolean

    /** Runs [block] holding the lock every save of the list takes, across processes where the platform has one. */
    fun <T> withListLock(block: () -> T): T
}
