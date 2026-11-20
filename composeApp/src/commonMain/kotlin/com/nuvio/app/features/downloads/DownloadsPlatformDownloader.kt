package com.nuvio.app.features.downloads

internal data class RefreshedSourceLink(
    val url: String,
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val filename: String? = null,
    val videoSize: Long? = null,
)

internal sealed interface SourceLinkRefreshResult {
    data class Refreshed(val link: RefreshedSourceLink) : SourceLinkRefreshResult

    /** No fresh link can be obtained for this source; fail without retrying link refresh. */
    data object Unavailable : SourceLinkRefreshResult

    /** Temporary error while resolving a fresh link; may be retried within bounds. */
    data class Failed(val message: String? = null) : SourceLinkRefreshResult
}

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
    /** Records and flushes the actual final address before the partial name is removed. */
    val onPublished: (localFileUri: String, sizeBytes: Long) -> Boolean = { _, _ -> true },
    /** Releases the publishing wait even if platform work ends before it can report a result. */
    val onFinished: () -> Unit = {},
    /** Told the server's file validators and total size once a transfer response is accepted. */
    val onSourceIdentityObserved: (totalBytes: Long?, eTag: String?, lastModified: String?) -> Unit = { _, _, _ -> },
    /** Re-resolves the same source for a fresh URL when the current link returns 403/404/410. */
    val refreshSourceLink: (suspend (DownloadItem) -> SourceLinkRefreshResult)? = null,
    /** Told when link refresh obtained a fresh URL/headers for the same source and whether join verification is pending. */
    val onSourceRefreshed: (link: RefreshedSourceLink, pendingVerification: Boolean) -> Unit = { _, _ -> },
    /**
     * Told when a refreshed link is not provably the same file as the existing partial:
     * keeps [kept] untouched and switches the active partial to [newPartialFileName].
     */
    val onSwitchedToNewPartial: (newPartialFileName: String, kept: KeptPartialFile?) -> Unit = { _, _ -> },
    /**
     * Asked after the success callback returns. A repository that checks the published file returns
     * true only after accepting it; rejected or interrupted completions keep their original partials.
     */
    val isCompletionAccepted: () -> Boolean = { true },
    /** Told once superseded partials have been retired after the new file was accepted. */
    val onSupersededPartialsRetired: (retiredKeys: Set<String>) -> Unit = {},
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
