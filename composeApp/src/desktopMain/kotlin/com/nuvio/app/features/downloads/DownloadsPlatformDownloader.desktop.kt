package com.nuvio.app.features.downloads

import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_error_not_enough_space
import nuvio.composeapp.generated.resources.downloads_error_not_durable
import nuvio.composeapp.generated.resources.downloads_error_private_address
import org.jetbrains.compose.resources.getString
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import nuvio.composeapp.generated.resources.downloads_error_file_checksum
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories

private val desktopDownloadHttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(60))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

internal actual object DownloadsPlatformDownloader {
    /** The folder of the app's own downloads, and the one the "open" button shows when the default is away. */
    private val appDownloadsDir: File
        get() = File(DesktopStorage.rootDir.resolve("downloads").also { it.createDirectories() }.toUri())

    /**
     * A download that is stored as downloading was cut off when the app was closed. It is left as it
     * is, so that it is started again when the list is loaded and goes on from its partial file.
     * One the user paused is stored as paused and stays paused.
     */
    /** Whether the internet cannot be reached right now, asked when a source keeps failing. */
    internal var isOffline: suspend () -> Boolean = { !NetworkStatusRepository.isInternetReachable() }

    /** Free space on the volume of the downloads folder, or null when the system does not tell. Replaced in tests. */
    internal var freeBytesProvider: () -> Long? = UsePerLocationFreeSpace

    /** Free space on the volume of the location the item goes to, unless a test replaced [freeBytesProvider]. */
    private fun freeBytesFor(item: DownloadItem): Long? =
        if (freeBytesProvider !== UsePerLocationFreeSpace) freeBytesProvider() else DesktopDownloads.folders.freeBytesOf(item)

    /** How often the repository is told how far a transfer has come. The stored list is written each time. */
    internal var progressIntervalMs: Long = ProgressIntervalMs

    internal var waitBeforeRetry: suspend (delayMs: Long) -> Unit = { delay(it) }

    /** A transfer that receives nothing for this long is cut off and tried again. */
    internal var stallTimeoutMs: Long = 60_000L

    /** Subtitles tried at the start of a transfer are given up after this long. */
    internal const val StartSubtitlesTimeoutMs = 60_000L

    /**
     * A file is found only where its record says. Nothing is adopted by name or size: a finished record
     * without an address shows "File not found", and only the user can point it at a file ("Locate the file…").
     */
    actual fun restoreItem(item: DownloadItem): DownloadItem = item

    actual fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val handle = DesktopDownloadsTaskHandle(job)

        scope.launch {
            // A download goes to the location of its record. When that is away, or a move is
            // carrying the item, it waits and goes on by itself when it can.
            val folders = DesktopDownloads.folders
            if (folders.heldByFolder(request.item)) {
                onPaused()
                return@launch
            }
            // One writer per download. One that was paused may still be closing its file: it is waited for.
            if (!WriterTokens.acquire(request.item.id, WriterTokens.Kind.Writer, WriterWaitMs)) {
                onPaused()
                return@launch
            }
            try {
            val location = folders.locations.locationOfRecord(request.item.locationId, null)
            val downloadsDir = File(location.path.also { if (location.id == DownloadLocations.AppId) it.createDirectories() }.toUri())
            var request = request
            var destination = File(downloadsDir, request.destinationFileName)
            var tempFile = File(downloadsDir, "${request.destinationFileName}.part")
            // The partial file is resumed, cut back or removed only when it is this download's own, proven by the
            // identity recorded when it was created. A file of that name that is not: left alone, and this download
            // takes a name that is free.
            val partial = OwnedPartial(request.item.partialFileKey, onCreated = { key -> request.onPartialCreated(key) })
            fun takeFreeName() {
                val name = freeDownloadName(downloadsDir.toPath(), request.destinationFileName)
                request = request.copy(item = request.item.copy(fileName = name, partialFileKey = null))
                request.onFileNameChanged(name)
                destination = File(downloadsDir, name)
                tempFile = File(downloadsDir, "$name.part")
                partial.key = null
            }
            // A partial file from before identities were recorded is adopted once, only when it is provably this
            // download's (see legacyPartialKey); adoption records its identity and changes nothing else.
            if (partial.key == null && Files.exists(tempFile.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                val profileId = com.nuvio.app.features.profiles.ProfileRepository.activeProfileId
                runCatching { legacyPartialKey(request.item, profileId, tempFile.toPath(), folders.locations, RepositoryDownloadsHooks.allRecords()) }
                    .getOrNull()?.let { key ->
                        partial.key = key
                        request.onPartialCreated(key)
                    }
            }
            if (Files.exists(tempFile.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) && !partial.proven(tempFile.toPath())) takeFreeName()

            // Subtitles are tried now, beside the transfer, so that a partly watched file has them.
            // What cannot be fetched now is tried again when the file is done (DownloadCompanions).
            if (DownloadSubtitles.hasSources(request.item)) launch {
                runCatching { withTimeoutOrNull(StartSubtitlesTimeoutMs) { DownloadSubtitles.prepare(request.item, destination.toURI().toString()) } }
                    .onFailure { if (it is CancellationException) throw it }
            }

            // Dropped connections are retried in place (resuming from the .part file) with backoff.
            // The retry budget resets whenever an attempt makes progress, so long downloads survive
            // periodic disconnects and only fail after repeated attempts that receive nothing while
            // the internet can be reached. Without internet the download waits for it to come back.
            val retries = DownloadRetries(isOffline, waitBeforeRetry)
            while (true) {
                val bytesBeforeAttempt = tempFile.partialLength()
                try {
                    val transfer = try {
                        transferToTempFile(request, tempFile, onProgress, partial = partial)
                    } catch (_: PartialNotOurs) {
                        takeFreeName()
                        continue
                    }
                    val totalBytes = transfer.totalBytes
                    if (transfer.digestMatched == false) {
                        // Not the body the server said it sent. Nothing of it is worth resuming: removed only while it is ours.
                        partial.removeIfOurs(tempFile.toPath())
                        error(runBlocking { getString(Res.string.downloads_error_file_checksum) })
                    }
                    if (transfer.digestMatched == true) request.onDigestVerified()

                    // From here the file is published: a pause that came first keeps the partial file, and
                    // none that comes after may stop the transfer (publishing removes the partial file).
                    currentCoroutineContext().ensureActive()
                    if (!request.mayPublish()) {
                        onPaused()
                        return@launch
                    }

                    // Reported finished only once the file is on disk under its final name, which never replaces
                    // a file this download did not create (a taken name gets " (2)", " (3)", ...).
                    val published = try {
                        publishDurably(tempFile.toPath(), destination.toPath(), beforeRemovingPartial = { path ->
                            val key = rememberPublishedFile(path)
                            request.onPublished(path.toFile().toURI().toString(), Files.size(path), key)
                        }).toFile()
                    } catch (_: DownloadNotDurable) {
                        // The partial file keeps its name: the download is not finished and goes on from it.
                        onFailure(runBlocking { getString(Res.string.downloads_error_not_durable) })
                        return@launch
                    }

                    val finalSize = published.length()
                    // Remembered, so that a file found bad right after this can be removed only while it is still this one.
                    rememberPublishedFile(published.toPath())
                    onSuccess(published.toURI().toString(), totalBytes ?: finalSize)
                    return@launch
                } catch (error: CancellationException) {
                    onPaused()
                    throw error
                } catch (error: TransferStoppedByLimit) {
                    request.limits?.onStopped?.invoke(error.reason)
                    return@launch
                } catch (error: IOException) {
                    // A full disk is still full after a wait, so it is not tried again.
                    if (classifyDownloadFailure(error.message.orEmpty()) == DownloadFailureKind.NotEnoughSpace) {
                        onFailure(runBlocking { getString(Res.string.downloads_error_not_enough_space) })
                        return@launch
                    }
                    // A network that blocks downloads fails them like this; enough of it makes downloads wait.
                    transferFailureKind(error)?.let { kind -> reportTransferFailure(request.sourceUrl, kind) }
                    if (!retries.awaitNextAttempt(madeProgress = tempFile.partialLength() > bytesBeforeAttempt)) {
                        onFailure(error.message?.takeIf { it.isNotBlank() && it != "closed" } ?: "Connection lost")
                        return@launch
                    }
                } catch (error: Throwable) {
                    transferFailureKind(error)?.let { kind -> reportTransferFailure(request.sourceUrl, kind) }
                    onFailure(error.message ?: "Download failed")
                    return@launch
                }
            }
            } finally {
                // Only now, with the file closed, can anything else write or move it.
                WriterTokens.release(request.item.id)
            }
        }

        return handle
    }

    /** The identity of each file this downloader published in this run, by address. */
    private val publishedKeys = java.util.concurrent.ConcurrentHashMap<String, String>()

    internal fun rememberPublishedFile(path: java.nio.file.Path): String? =
        DownloadMover.identityOf(path)?.also { publishedKeys[path.toFile().toURI().toString()] = it }

    /** Uses the saved identity after a restart, or this run's publication identity for older records. */
    fun removeJustPublished(localFileUri: String, fileKey: String? = null): Boolean =
        removeJustPublished(localFileUri, fileKey, DownloadsTrash::moveToTrash)

    internal fun removeJustPublished(localFileUri: String, move: (java.nio.file.Path) -> Boolean): Boolean =
        removeJustPublished(localFileUri, null, move)

    internal fun removeJustPublished(localFileUri: String, fileKey: String?, move: (java.nio.file.Path) -> Boolean): Boolean {
        val path = localFileUri.toLocalFileOrNull()?.toPath() ?: return false
        val uri = path.toFile().toURI().toString()
        val key = fileKey ?: publishedKeys[uri] ?: return false
        val moved = trashPublishedFile(path, key, move)
        if (moved) publishedKeys.remove(uri, key)
        return moved
    }

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val file = localFileUri.toLocalFileOrNull() ?: return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    actual fun removePartialFile(destinationFileName: String): Boolean {
        // Without a record to say where, only the app's own folder: never a file of that name elsewhere.
        // Subtitles saved at the start of a download that never became a file go with it. Never when the file exists.
        val video = File(appDownloadsDir, destinationFileName)
        if (!video.exists()) runCatching { DownloadSubtitleStorage(video.toURI().toString()).remove() }
        val tempFile = File(appDownloadsDir, "$destinationFileName.part")
        return !tempFile.exists() || runCatching { tempFile.delete() }.getOrDefault(false)
    }

    /** The recorded address when a file is there. Never a file found by its name instead. */
    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        localFileUri
            ?.toLocalFileOrNull()
            ?.takeIf { it.isFile }
            ?.let { return it.toURI().toString() }
        return (DesktopDownloads.folders.locations.locate(null, localFileUri, "") as? FileLookup.Found)?.uri
    }

    actual fun openDownloadsDirectory(): Boolean {
        val folders = DesktopDownloads.folders
        val defaultLocation = folders.locations.defaultLocation()
        val directory = if (folders.locations.availability(defaultLocation) == FolderAvailability.Available) {
            defaultLocation.path.also { runCatching { it.createDirectories() } }.toFile()
        } else {
            appDownloadsDir
        }
        val desktop = runCatching { Desktop.getDesktop() }.getOrNull()

        if (desktop != null && Desktop.isDesktopSupported() && desktop.isSupported(Desktop.Action.OPEN)) {
            val opened = runCatching { desktop.open(directory) }.isSuccess
            if (opened) return true
        }

        return openDirectoryWithPlatformCommand(directory)
    }

    internal class Transferred(
        val totalBytes: Long?,
        /** Whether the body matched the digest the server sent. Null when there was none to compare. */
        val digestMatched: Boolean?,
    )

    /**
     * Downloads into [tempFile], resuming from its current length when possible. Reports the total
     * size if known. A digest the server sent with the body is compared with what was written, but
     * only for a body that was received whole in this transfer: a transfer that resumed a partial
     * file saw only part of the body, so its digest is not checked.
     */
    internal suspend fun transferToTempFile(
        request: DownloadPlatformRequest,
        tempFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        limiter: DownloadSpeedLimiter = DownloadBandwidth.limiter.also { DownloadBandwidth.applyStoredLimit() },
        /** Which partial file is this transfer's own. Callers that made the file themselves may trust it. */
        partial: OwnedPartial = OwnedPartial.trustingCaller(),
    ): Transferred {
        if (Files.exists(tempFile.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) && !partial.proven(tempFile.toPath())) throw PartialNotOurs()
        // The address check at queue time reads only the text of the URL. Here the name is resolved.
        if (request.item.autoDownloadRuleKey != null && !resolvesToPublicAddresses(request.sourceUrl)) {
            error(runBlocking { getString(Res.string.downloads_error_private_address) })
        }

        var resumeFromBytes = tempFile.partialLength()
        var attemptedRangeRequest = resumeFromBytes > 0L
        var response = sendDownloadRequest(request, if (attemptedRangeRequest) resumeFromBytes else null)

        if (attemptedRangeRequest && response.statusCode() == 416) {
            response.body().close()
            if (!partial.removeIfOurs(tempFile.toPath())) throw PartialNotOurs()
            resumeFromBytes = 0L
            attemptedRangeRequest = false
            response = sendDownloadRequest(request, null)
        }

        val statusCode = response.statusCode()
        if (statusCode !in 200..299) {
            response.body().close()
            if (statusCode == 408 || statusCode == 429 || statusCode in 500..599) {
                throw IOException("Download failed with HTTP $statusCode")
            }
            error("Download failed with HTTP $statusCode")
        }

        // A web page where a video was asked for: a sign-in page of the network, usually. Nothing of it is
        // written and the partial file is left as it is; the transfer is tried again like a dropped connection.
        if (isWebPage(response.headers().firstValue("Content-Type").orElse(null))) {
            response.body().close()
            throw PageInsteadOfFile()
        }

        val isPartialResume = attemptedRangeRequest && statusCode == 206 && resumeFromBytes > 0L
        val appendToTemp = isPartialResume
        val startingBytes = if (appendToTemp) resumeFromBytes else 0L
        // An earlier partial that cannot be continued is discarded only after the checks below have
        // passed, so a download that is refused for lack of room keeps what it already has.
        val discardPartial = !appendToTemp && tempFile.exists()
        val reclaimedBytes = if (discardPartial) tempFile.partialLength() else 0L

        val totalBytes = resolveTotalBytes(
            startingBytes = startingBytes,
            isPartialResume = isPartialResume,
            contentRangeHeader = response.headers().firstValue("Content-Range").orElse(null),
            contentLength = response.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull(),
        )
        var downloadedBytes = startingBytes
        val traffic = DownloadTraffic.app
        val reporter = ThrottledProgress(progressIntervalMs, System::nanoTime) { bytes -> onProgress(bytes, totalBytes) }
        // Checked before anything of the body is written, for every download: a file announced as
        // larger than the room on the disk fails with a plain message instead of a system error.
        if (totalBytes != null) {
            val free = freeBytesFor(request.item)?.plus(reclaimedBytes)
            if (free != null && totalBytes - startingBytes > free) {
                response.body().close()
                error(runBlocking { getString(Res.string.downloads_error_not_enough_space) })
            }
        }
        // Checked before anything of the body is written: a file that is announced as too large for
        // the limits of an automatic download is not started.
        val limits = request.limits
        limits?.stopBeforeBody(downloadedBytes, totalBytes, freeBytesFor(request.item)?.plus(reclaimedBytes))?.let { reason ->
            response.body().close()
            throw TransferStoppedByLimit(reason)
        }
        if (discardPartial && !partial.removeIfOurs(tempFile.toPath())) {
            response.body().close()
            throw PartialNotOurs()
        }
        onProgress(downloadedBytes, totalBytes)
        val expectedDigest = if (statusCode == 200 && !appendToTemp) {
            expectedBodyDigest { name -> response.headers().firstValue(name).orElse(null) }
        } else {
            null
        }
        val digest = expectedDigest?.let { runCatching { MessageDigest.getInstance(it.algorithm) }.getOrNull() }

        response.body().use { input ->
            coroutineScope {
                // A connection that goes silent never ends the read by itself. Closing the body
                // ends it, and the transfer is then tried again like any other dropped connection.
                val lastReadAtNanos = AtomicLong(System.nanoTime())
                val stalled = AtomicBoolean(false)
                // Cancelling the transfer closes the body, which ends a read that is blocked on a
                // silent connection, so a pause or a move does not wait for data that never comes.
                val closer = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        runCatching { input.close() }
                    }
                }
                val stallWatch = launch {
                    val timeoutMs = stallTimeoutMs
                    while (true) {
                        delay((timeoutMs / 4).coerceAtLeast(1L))
                        if (System.nanoTime() - lastReadAtNanos.get() > timeoutMs * 1_000_000L) {
                            stalled.set(true)
                            runCatching { input.close() }
                            break
                        }
                    }
                }
                // Counted as a running transfer only while the body is read; the finally below ends it.
                val trafficToken = traffic.begin(endpointOf(request.sourceUrl))
                try {
                    openPartial(tempFile.toPath(), appendToTemp).also { if (!appendToTemp) partial.created(tempFile.toPath()) }.use { output ->
                        val buffer = ByteArray(ReadBufferBytes)
                        var nextLimitCheckAtBytes = downloadedBytes + LimitCheckIntervalBytes
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer, 0, readChunkSize(limiter.bytesPerSecond))
                            if (read <= 0) break
                            lastReadAtNanos.set(System.nanoTime())
                            output.write(buffer, 0, read)
                            traffic.add(read)
                            digest?.update(buffer, 0, read)
                            downloadedBytes += read.toLong()
                            reporter.offer(downloadedBytes)
                            limiter.throttle(read)
                            // Waiting for the speed limit is not a connection that went silent.
                            lastReadAtNanos.set(System.nanoTime())
                            // A body without an announced size, or one that is longer than announced, is
                            // caught here, as is free space that other writers used up meanwhile.
                            if (limits != null && downloadedBytes >= nextLimitCheckAtBytes) {
                                nextLimitCheckAtBytes = downloadedBytes + LimitCheckIntervalBytes
                                limits.stopFor(downloadedBytes, totalBytes, freeBytesFor(request.item))?.let { reason ->
                                    output.flush()
                                    throw TransferStoppedByLimit(reason)
                                }
                            }
                        }
                        output.flush()
                    }
                } catch (error: IOException) {
                    // A read that ended because the transfer was cancelled is a cancellation, not a dropped connection.
                    currentCoroutineContext().ensureActive()
                    throw if (stalled.get()) IOException("Connection stalled") else error
                } finally {
                    traffic.end(trafficToken)
                    stallWatch.cancel()
                    closer.cancel()
                    // The last value is always reported, whether the transfer ended, failed or was stopped.
                    reporter.flush(downloadedBytes)
                }
                if (stalled.get()) throw IOException("Connection stalled")
            }
        }

        // A server that ends the stream early would otherwise leave a truncated file marked as completed.
        if (totalBytes != null && downloadedBytes < totalBytes) {
            throw IOException("Connection closed after $downloadedBytes of $totalBytes bytes")
        }
        return Transferred(
            totalBytes = totalBytes,
            digestMatched = if (digest != null && expectedDigest != null) digest.digest().contentEquals(expectedDigest.value) else null,
        )
    }

    private suspend fun sendDownloadRequest(
        request: DownloadPlatformRequest,
        rangeStart: Long?,
    ): HttpResponse<java.io.InputStream> {
        val builder = HttpRequest.newBuilder()
            .uri(URI(request.sourceUrl))
            .timeout(Duration.ofSeconds(60))
            .GET()
        request.sourceHeaders.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) {
                builder.header(key, value)
            }
        }
        if (rangeStart != null && rangeStart > 0L) {
            builder.header("Range", "bytes=$rangeStart-")
        }
        // Interrupted when the transfer is cancelled, so that waiting for a server does not hold it.
        return runInterruptible(Dispatchers.IO) {
            desktopDownloadHttpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        }
    }
}

/** False when the host of [url] cannot be resolved or any of its addresses is not a public one. */
private fun resolvesToPublicAddresses(url: String): Boolean {
    val addresses = runCatching { InetAddress.getAllByName(URI(url).host) }.getOrNull()
    return !addresses.isNullOrEmpty() && addresses.none(::isPrivateNetworkAddress)
}

/** Loopback, link-local, private-network and carrier-shared addresses, and the unspecified address. */
internal fun isPrivateNetworkAddress(address: InetAddress): Boolean {
    val bytes = address.address
    val first = bytes[0].toInt() and 0xff
    val second = bytes[1].toInt() and 0xff
    return address.isLoopbackAddress ||
        address.isAnyLocalAddress ||
        address.isLinkLocalAddress ||
        address.isSiteLocalAddress ||
        (address is Inet6Address && (first and 0xfe) == 0xfc) ||
        (bytes.size == 4 && (first == 0 || (first == 100 && second in 64..127)))
}

private fun openDirectoryWithPlatformCommand(directory: File): Boolean {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    val command = when {
        osName.contains("mac") -> listOf("open", directory.absolutePath)
        osName.contains("win") -> listOf("explorer", directory.absolutePath)
        else -> listOf("xdg-open", directory.absolutePath)
    }
    return runCatching { ProcessBuilder(command).start() }.isSuccess
}

private class DesktopDownloadsTaskHandle(
    private val job: Job,
) : DownloadsTaskHandle {
    override fun cancel() {
        job.cancel()
    }

    override fun cancelAndWait(timeoutMs: Long): Boolean {
        job.cancel()
        return runBlocking { withTimeoutOrNull(timeoutMs) { job.children.toList().forEach { it.join() } } } != null
    }
}

/** How long a new transfer waits for a writer that was paused and is still closing its file. */
internal const val WriterWaitMs = 30_000L

/** The bytes read from the connection at a time. */
internal const val ReadBufferBytes = 256 * 1024

/** The least time between two reports of progress. */
internal const val ProgressIntervalMs = 250L

/**
 * Passes progress on at most once every [intervalMs], and the last value always, through [flush].
 * Without it every read would reach the stored list.
 */
internal class ThrottledProgress(
    private val intervalMs: Long,
    private val nanoTime: () -> Long,
    private val report: (Long) -> Unit,
) {
    private var lastReportedNanos = Long.MIN_VALUE
    private var lastReportedBytes = -1L

    fun offer(bytes: Long) {
        val now = nanoTime()
        if (lastReportedNanos == Long.MIN_VALUE || now - lastReportedNanos >= intervalMs * 1_000_000L) {
            lastReportedNanos = now
            lastReportedBytes = bytes
            report(bytes)
        }
    }

    fun flush(bytes: Long) {
        if (bytes != lastReportedBytes) {
            lastReportedBytes = bytes
            report(bytes)
        }
    }
}

/**
 * How much to read at a time. A low limit gets small reads, so that the wait for the limit is spread
 * over the transfer and not paid in one long pause after a large chunk.
 */
internal fun readChunkSize(limitBytesPerSecond: Long?): Int =
    if (limitBytesPerSecond == null) ReadBufferBytes
    else (limitBytesPerSecond / 10).coerceIn(4 * 1024L, ReadBufferBytes.toLong()).toInt()

private val UsePerLocationFreeSpace: () -> Long? = { null }

private class TransferStoppedByLimit(val reason: AutoDownloadStop) : RuntimeException()

private const val LimitCheckIntervalBytes = 256L * 1024

private fun File.partialLength(): Long =
    takeIf { it.exists() }?.length()?.coerceAtLeast(0L) ?: 0L

private fun String.toLocalFileOrNull(): File? =
    runCatching {
        if (startsWith("file:")) {
            File(URI(this))
        } else {
            File(this)
        }
    }.getOrNull()

private fun resolveTotalBytes(
    startingBytes: Long,
    isPartialResume: Boolean,
    contentRangeHeader: String?,
    contentLength: Long?,
): Long? {
    parseContentRangeTotal(contentRangeHeader)?.let { return it }
    val normalizedLength = contentLength?.takeIf { it > 0L } ?: return null
    return if (isPartialResume && startingBytes > 0L) {
        startingBytes + normalizedLength
    } else {
        normalizedLength
    }
}

private fun parseContentRangeTotal(headerValue: String?): Long? {
    val value = headerValue?.trim().orEmpty()
    if (value.isBlank()) return null
    val slashIndex = value.lastIndexOf('/')
    if (slashIndex == -1 || slashIndex == value.lastIndex) return null
    val totalPart = value.substring(slashIndex + 1).trim()
    if (totalPart == "*") return null
    return totalPart.toLongOrNull()?.takeIf { it > 0L }
}

/** The finished file could not be confirmed on disk: the download is not marked finished and keeps its partial file. */
internal class DownloadNotDurable(message: String) : IOException(message)

/** Whether folders can be flushed here at all. Windows does not let a folder be opened for it. */
private val foldersCanBeFlushed = !System.getProperty("os.name").orEmpty().lowercase().contains("win")

/**
 * Gives the finished partial file [part] its final name, durably and without replacing anything:
 * 1. its contents are forced to disk;
 * 2. a hard link is made under the first free name (`name.ext`, then `name (2).ext`, ...), or, where links
 *    are not supported, a copy into a file created with create-new, forced; a copy that fails half way is
 *    removed while it is still the file this call created, so nothing piles up beside the partial file;
 * 3. the folder is flushed; when that fails, the new name is removed again (it is this call's own) and
 *    [DownloadNotDurable] is thrown with the partial file still under its name;
 * 4. only then does the partial name go. The final name is returned.
 * Where the platform cannot flush folders at all (Windows), step 3 is skipped and completion rests on the
 * forced file contents.
 */
internal fun publishDurably(
    part: java.nio.file.Path,
    destination: java.nio.file.Path,
    flushFolder: (java.nio.file.Path) -> Boolean = { folder -> !foldersCanBeFlushed || DownloadMover.flushDirectory(folder) },
    link: (java.nio.file.Path, java.nio.file.Path) -> Unit = { name, existing -> Files.createLink(name, existing) },
    beforeRemovingPartial: (java.nio.file.Path) -> Boolean = { true },
    copy: (java.nio.channels.FileChannel, java.nio.channels.FileChannel) -> Unit = { input, out ->
        var position = 0L
        val size = input.size()
        while (position < size) position += input.transferTo(position, size - position, out)
    },
): java.nio.file.Path {
    val nofollow = java.nio.file.LinkOption.NOFOLLOW_LINKS
    java.nio.channels.FileChannel.open(part, java.nio.file.StandardOpenOption.WRITE, nofollow).use { it.force(true) }
    val partKey = DownloadMover.identityOf(part) ?: throw IOException("The partial file cannot be read")
    val name = destination.fileName.toString()
    val base = name.substringBeforeLast('.', name)
    val extension = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
    for (n in 1..999) {
        val candidate = if (n == 1) destination else destination.resolveSibling("$base ($n)$extension")
        val createdKey: String = try {
            link(candidate, part)
            partKey
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            continue
        } catch (_: Exception) {
            val out = try {
                java.nio.channels.FileChannel.open(candidate, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE, nofollow)
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                continue
            }
            val key = DownloadMover.identityOf(candidate)
            try {
                out.use { channel ->
                    java.nio.channels.FileChannel.open(part, java.nio.file.StandardOpenOption.READ, nofollow).use { input -> copy(input, channel) }
                    channel.force(true)
                }
            } catch (error: IOException) {
                removeIfStill(candidate, key)
                throw error
            }
            key ?: run {
                removeIfStill(candidate, key)
                throw IOException("The finished file cannot be read")
            }
        }
        if (!flushFolder(candidate.parent)) {
            removeIfStill(candidate, createdKey)
            throw DownloadNotDurable("The folder of the download could not be flushed")
        }
        if (!beforeRemovingPartial(candidate)) {
            // Both names keep their bytes when the record could not be made durable.
            throw DownloadNotDurable("The finished file address could not be saved")
        }
        Files.deleteIfExists(part)
        flushFolder(candidate.parent)
        return candidate
    }
    throw IOException("No free name for $name")
}

/** Removes [path] only while it is still the file with [key], which this download created. */
private fun removeIfStill(path: java.nio.file.Path, key: String?) {
    if (key == null) return
    runCatching { if (!Files.isSymbolicLink(path) && DownloadMover.identityOf(path) == key) Files.delete(path) }
}

/**
 * The partial file of a download, opened for writing without following a link: appended to when the
 * download resumes it, created with create-new when it starts it (the old one was removed just before),
 * so a link or another file put at that name is never written through or cut short.
 */
internal fun openPartial(path: java.nio.file.Path, append: Boolean): java.io.OutputStream {
    val options: Array<java.nio.file.OpenOption> = if (append) {
        arrayOf(java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.APPEND, java.nio.file.LinkOption.NOFOLLOW_LINKS)
    } else {
        arrayOf(java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.LinkOption.NOFOLLOW_LINKS)
    }
    return java.nio.channels.Channels.newOutputStream(java.nio.channels.FileChannel.open(path, *options))
}

/** A partial file at a name that is not this download's own: it is left alone and another name is taken. */
internal class PartialNotOurs : IOException("The partial file at that name is not this download's")

/**
 * Which partial file is a transfer's own: the one whose identity was recorded when this download created it.
 * Nothing that cannot be proven this way is resumed, cut back or removed.
 */
internal class OwnedPartial(
    @Volatile var key: String?,
    private val onCreated: (String) -> Unit = {},
    private val trustExisting: Boolean = false,
) {
    fun proven(path: java.nio.file.Path): Boolean =
        trustExisting || (key != null && !Files.isSymbolicLink(path) && DownloadMover.identityOf(path) == key)

    /** Records the identity of the partial file just created with create-new, and keeps it with the download. */
    fun created(path: java.nio.file.Path) {
        val identity = DownloadMover.identityOf(path) ?: return
        key = identity
        onCreated(identity)
    }

    /** Removes the partial file only while it is proven to be this download's. True when it is gone. */
    fun removeIfOurs(path: java.nio.file.Path): Boolean {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true
        if (!proven(path)) return false
        return runCatching { Files.delete(path); true }.getOrDefault(false)
    }

    companion object {
        /** For a caller that made the partial file itself (tests of the transfer). */
        fun trustingCaller() = OwnedPartial(null, trustExisting = true)
    }
}

/** The first of `name`, `name (2)`, `name (3)`, ... for which neither the file nor its partial file exists in [folder]. */
internal fun freeDownloadName(folder: java.nio.file.Path, name: String): String {
    val base = name.substringBeforeLast('.', name)
    val extension = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
    for (n in 2..999) {
        val candidate = "$base ($n)$extension"
        val taken = listOf(candidate, "$candidate.part").any { Files.exists(folder.resolve(it), java.nio.file.LinkOption.NOFOLLOW_LINKS) }
        if (!taken) return candidate
    }
    throw IOException("No free name for $name")
}

/** The rejected file goes to the Trash only while its recorded identity still matches. */
internal fun trashPublishedFile(path: java.nio.file.Path, key: String, moveToTrash: (java.nio.file.Path) -> Boolean): Boolean =
    runCatching {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return@runCatching true
        if (Files.isSymbolicLink(path) || DownloadMover.identityOf(path) != key) return@runCatching false
        moveToTrash(path) && !Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
    }.getOrDefault(false)

/** A web page arrived where a video was asked for. Its body is not written. */
internal class PageInsteadOfFile : IOException("The network sent a web page instead of the video")

/** Whether a response of [contentType] is a web page rather than a file. */
internal fun isWebPage(contentType: String?): Boolean {
    val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
    return type == "text/html" || type == "application/xhtml+xml"
}

/** How [error] failed, in the ways a network that blocks downloads makes transfers fail; null for others. */
internal fun transferFailureKind(error: Throwable): TransferFailureKind? {
    val message = error.message.orEmpty()
    return when {
        error is PageInsteadOfFile -> TransferFailureKind.PageInsteadOfFile
        error is javax.net.ssl.SSLException -> TransferFailureKind.SecureConnection
        Regex("HTTP (403|451|5\\d\\d)").containsMatchIn(message) -> TransferFailureKind.Refused
        error is IOException && (message.contains("reset", ignoreCase = true) || message.contains("closed", ignoreCase = true)) -> TransferFailureKind.Reset
        else -> null
    }
}

private fun reportTransferFailure(url: String, kind: TransferFailureKind) {
    val host = runCatching { URI(url).host }.getOrNull() ?: return
    DesktopPoliteDownloads.onTransferFailure(host, kind)
}

/** The host and port a transfer talks to, for the round trip probe. */
internal fun endpointOf(url: String): TransferEndpoint {
    val uri = runCatching { URI(url) }.getOrNull()
    val port = uri?.port?.takeIf { it > 0 } ?: if (uri?.scheme.equals("http", ignoreCase = true)) 80 else 443
    return TransferEndpoint(uri?.host.orEmpty(), port)
}
