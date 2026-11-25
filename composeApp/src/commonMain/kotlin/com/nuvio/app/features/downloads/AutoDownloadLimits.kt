package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable

/** Why an automatic transfer was stopped, or not started, by a limit. */
@Serializable
enum class AutoDownloadStop {
    /** The storage limit for automatic downloads would be exceeded. */
    StorageLimit,

    /** The storage limit for all downloads together would be exceeded. */
    TotalStorageLimit,

    /** The downloads volume would be left with less free space than automatic downloads must leave. */
    LowDiskSpace,
}

/** What limits downloads: the automatic-only limit and the combined limit for all downloads. */
internal interface AutoDownloadLimits {
    /** The most bytes all automatic downloads together may take, or null for no limit. */
    fun storageLimitBytes(): Long?

    /** The most bytes all downloads together (hand-started and automatic) may take, or null for no limit. */
    fun totalStorageLimitBytes(): Long? = null

    /** Free space on the downloads volume that an automatic transfer must leave untouched. */
    val minFreeBytes: Long

    /** Free space on the downloads volume that all downloads must leave when the user set a floor. */
    fun userFreeSpaceFloorBytes(): Long? = null
}

internal object SavedAutoDownloadLimits : AutoDownloadLimits {
    const val MinFreeBytes = 10L * BytesPerGigabyte

    override fun storageLimitBytes(): Long? = AutoDownloads.storageLimitBytes()

    override fun totalStorageLimitBytes(): Long? = AutoDownloads.totalStorageLimitBytes()

    override val minFreeBytes: Long get() = MinFreeBytes
}

internal const val BytesPerGigabyte = 1_000_000_000L

/**
 * The limits of one transfer, asked by the platform downloader before it writes the body
 * and again while it writes.
 */
internal class DownloadTransferLimits(
    /** The most bytes this file may have, given what the other automatic downloads take. Null: no limit. */
    private val maxTotalBytes: () -> Long?,
    private val minFreeBytes: Long,
    /** Called once when the transfer stops because of a limit. Nothing else is reported for it. */
    val onStopped: (AutoDownloadStop) -> Unit,
    /**
     * Runs the check made before the body and, when it allows the transfer, the reservation of the
     * announced size as one step, so that two transfers cannot both take the same remaining room.
     */
    private val checkAndReserve: (check: () -> AutoDownloadStop?, announcedBytes: Long?) -> AutoDownloadStop? =
        { check, _ -> check() },
    /** The most bytes this file may have under the combined limit for all downloads. Null: no limit. */
    private val maxAllDownloadsBytes: () -> Long? = { null },
) {
    /** The size in bytes of the transfer when a limit last stopped it, if known. */
    @kotlin.concurrent.Volatile
    var lastRefusedSizeBytes: Long? = null
        private set

    /** [stopFor] for a transfer that is about to write its body. A size that is allowed is reserved. */
    fun stopBeforeBody(downloadedBytes: Long, totalBytes: Long?, freeBytes: Long?): AutoDownloadStop? =
        checkAndReserve({ stopFor(downloadedBytes, totalBytes, freeBytes) }, totalBytes)

    /**
     * The limit that does not allow the transfer to go on, if there is one. [totalBytes] is the size
     * the server announced, when it did, and [freeBytes] the free space on the downloads volume,
     * when it is known.
     */
    fun stopFor(downloadedBytes: Long, totalBytes: Long?, freeBytes: Long?): AutoDownloadStop? {
        val size = maxOf(totalBytes ?: 0L, downloadedBytes)
        val maxAll = maxAllDownloadsBytes()
        if (maxAll != null && size > maxAll) {
            lastRefusedSizeBytes = size.takeIf { it > 0L }
            return AutoDownloadStop.TotalStorageLimit
        }
        val max = maxTotalBytes()
        if (max != null && size > max) {
            lastRefusedSizeBytes = size.takeIf { it > 0L }
            return AutoDownloadStop.StorageLimit
        }
        if (minFreeBytes > 0L && freeBytes != null && freeBytes - (size - downloadedBytes) < minFreeBytes) {
            lastRefusedSizeBytes = size.takeIf { it > 0L }
            return AutoDownloadStop.LowDiskSpace
        }
        return null
    }
}
