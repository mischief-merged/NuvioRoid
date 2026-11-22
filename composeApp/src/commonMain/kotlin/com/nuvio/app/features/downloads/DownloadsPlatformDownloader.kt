package com.nuvio.app.features.downloads

internal data class DownloadPlatformRequest(
    val item: DownloadItem,
    /** Called before success is reported when the body matched a digest the server sent with it. */
    val onDigestVerified: () -> Unit = {},
    /** Set for an automatic download. The desktop downloader is the one that applies them. */
    val limits: DownloadTransferLimits? = null,
    /** Told the identity of the partial file the transfer just created, to be kept with the download. */
    val onPartialCreated: (String) -> Unit = {},
    /** Told the new file name when the old one was taken by a file that is not this download's. */
    val onFileNameChanged: (String) -> Unit = {},
    /**
     * Asked once the whole body is in, before the file is published under its final name. True: the
     * download is still this transfer's and nothing may stop it now. False: it was paused meanwhile;
     * the partial file is kept as it is and the transfer ends as paused.
     */
    val mayPublish: () -> Boolean = { true },
    /** Saves the final address durably before the partial name may be removed. False keeps both names. */
    val onPublished: (localFileUri: String, size: Long) -> Boolean = { _, _ -> true },
) {
    val sourceUrl: String get() = item.sourceUrl
    val sourceHeaders: Map<String, String> get() = item.sourceHeaders
    val destinationFileName: String get() = item.fileName
}

internal interface DownloadsTaskHandle {
    fun cancel()

    /**
     * Cancels and waits until the transfer has stopped writing and closed its file. False when it
     * did not stop within [timeoutMs]. A platform that cannot wait says it stopped.
     */
    fun cancelAndWait(timeoutMs: Long): Boolean {
        cancel()
        return true
    }
}

internal expect object DownloadsPlatformDownloader {
    fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle

    fun restoreItem(item: DownloadItem): DownloadItem

    fun removeFile(localFileUri: String?): Boolean

    fun removePartialFile(destinationFileName: String): Boolean

    fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String?

    fun openDownloadsDirectory(): Boolean
}
