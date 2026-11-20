package com.nuvio.app.features.downloads

import com.nuvio.app.features.addons.httpGetSubtitleText
import com.nuvio.app.features.player.PlayerSubtitleCueParser
import com.nuvio.app.features.player.AddonSubtitle
import com.nuvio.app.features.player.loadAddonSubtitles
import com.nuvio.app.features.player.SubtitleAddonRequest
import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** What one call to [DownloadSubtitles.prepare] did. */
internal data class SubtitleSaveResult(
    /** Subtitle files saved for the video now, old and new. */
    val saved: Int,
    /** Files saved by this call. */
    val newlySaved: Int,
    /** Subtitles that were tried and could not be fetched, as opposed to ones that were not subtitles. */
    val unreachable: Int,
)

internal object DownloadSubtitles {
    // Defaults are written too, so that an older reader sees every field it knows.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Fetches the text of a subtitle. Replaced in tests. */
    internal var fetchText: suspend (url: String, headers: Map<String, String>) -> String =
        { url, headers -> httpGetSubtitleText(url, headers) }

    /** Asks the subtitle add-ons. Replaced in tests. */
    internal var loadAddon: suspend (List<SubtitleAddonRequest>) -> List<AddonSubtitle> =
        { requests -> loadAddonSubtitles(requests) }

    /** A look that found no subtitle at all is repeated only after this long. */
    const val RECHECK_EMPTY_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** A track that could not be fetched is tried again after this long, and after longer with each further failure. */
    const val RETRY_COOLDOWN_MS = 30L * 60 * 1000

    /** How long a track that failed [attempts] times in a row waits: four times longer each time, at most [RECHECK_EMPTY_AGE_MS]. */
    fun cooldownAfter(attempts: Int): Long {
        var cooldown = RETRY_COOLDOWN_MS
        repeat((attempts - 1).coerceAtLeast(0)) {
            if (cooldown >= RECHECK_EMPTY_AGE_MS) return RECHECK_EMPTY_AGE_MS
            cooldown *= 4
        }
        return cooldown.coerceAtMost(RECHECK_EMPTY_AGE_MS)
    }

    /** Whether the item names anywhere to get subtitles from. */
    fun hasSources(item: DownloadItem): Boolean = item.subtitleRequests.isNotEmpty() || item.sourceSubtitles.isNotEmpty()

    /** How many subtitle files are saved for the video. */
    fun savedCount(localVideoUri: String): Int {
        if (!localVideoUri.startsWith("file:")) return 0
        val storage = runCatching { DownloadSubtitleStorage(localVideoUri) }.getOrNull() ?: return 0
        return readManifest(storage).tracks.count { storage.localFileUri(it.fileName) != null }
    }

    /**
     * Whether a look for subtitles is worth making for this video now: never made, not finished,
     * a saved file gone, or nothing found long enough ago. A video whose item names no source never is.
     */
    fun isDue(item: DownloadItem, localVideoUri: String, nowEpochMs: Long): Boolean {
        if (!hasSources(item) || !localVideoUri.startsWith("file:")) return false
        val storage = runCatching { DownloadSubtitleStorage(localVideoUri) }.getOrNull() ?: return false
        val manifest = readManifest(storage)
        val saved = manifest.tracks.count { storage.localFileUri(it.fileName) != null }
        return when {
            // A look that was never finished (the app was closed in the middle of it).
            !manifest.complete && manifest.failed.isEmpty() -> true
            saved < manifest.tracks.size -> true
            // Tracks that failed are tried again, each after its own cooldown.
            manifest.failed.isNotEmpty() -> manifest.failed.any { failureIsDue(it, nowEpochMs) }
            saved > 0 -> false
            manifest.checkedAtEpochMs <= 0L -> true
            else -> nowEpochMs - manifest.checkedAtEpochMs !in 0 until RECHECK_EMPTY_AGE_MS
        }
    }

    private fun failureIsDue(failure: FailedSubtitle, nowEpochMs: Long): Boolean {
        val age = nowEpochMs - failure.atEpochMs
        return age < 0 || age >= cooldownAfter(failure.attempts)
    }

    /**
     * Saves the subtitles of [item] beside the video. Never removes one that is saved. With
     * [recheck] a manifest that says "complete" is still looked at again, so that what could not be
     * fetched at the start is tried when the video is done.
     */
    suspend fun prepare(
        item: DownloadItem,
        localVideoUri: String,
        recheck: Boolean = false,
        nowEpochMs: Long = DownloadsClock.nowEpochMs(),
        /**
         * Runs each write. Returns false when it did not run (the video is gone), and then the save
         * stops at once. The default just writes.
         */
        guardedWrite: (action: () -> Unit) -> Boolean = { it(); true },
    ): SubtitleSaveResult = withContext(Dispatchers.Default) {
        // One run per video at a time: a second request waits, then finds little or nothing left to do.
        runMutex(localVideoUri).withLock { prepareOnce(item, localVideoUri, recheck, nowEpochMs, guardedWrite) }
    }

    private val runsGuard = SynchronizedObject()
    private val runs = mutableMapOf<String, Mutex>()

    private fun runMutex(localVideoUri: String): Mutex = synchronized(runsGuard) {
        runs.getOrPut(localVideoUri.trim().removePrefix("file:").trimStart('/')) { Mutex() }
    }

    private suspend fun prepareOnce(
        item: DownloadItem,
        localVideoUri: String,
        recheck: Boolean,
        nowEpochMs: Long,
        guardedWrite: (action: () -> Unit) -> Boolean,
    ): SubtitleSaveResult {
        val storage = DownloadSubtitleStorage(localVideoUri)
        val manifest = readManifest(storage)
        val startSaved = manifest.tracks.count { storage.localFileUri(it.fileName) != null }
        if (!recheck && manifest.complete && startSaved == manifest.tracks.size) {
            return SubtitleSaveResult(startSaved, 0, 0)
        }
        var newlySaved = 0
        val previousFailed = manifest.failed.associateBy { it.sourceUrl }
        val newFailures = mutableListOf<FailedSubtitle>()
        val attempted = mutableSetOf<String>()
        var unreachable = 0
        val addonSubtitles = loadAddon(item.subtitleRequests).map {
            StreamSubtitle(url = it.url, language = it.language, name = it.display)
        }
        val savedUrls = manifest.tracks.filter { storage.localFileUri(it.fileName) != null }.map { it.sourceUrl }.toSet()
        val pending = (item.sourceSubtitles + addonSubtitles)
            .distinctBy { it.url }
            .filter { subtitle ->
                (subtitle.url.startsWith("https://", true) || subtitle.url.startsWith("http://", true)) &&
                    subtitle.url !in savedUrls &&
                    // A track that failed waits out its cooldown.
                    previousFailed[subtitle.url]?.let { failureIsDue(it, nowEpochMs) } != false
            }
        attempted += pending.map { it.url }
        for (batch in pending.chunked(4)) {
            val fetched = coroutineScope {
                batch.map { subtitle ->
                    async {
                        var failed = false
                        val body = try {
                            val text = withTimeoutOrNull(15_000L) {
                                fetchText(subtitle.url, subtitle.headers.orEmpty())
                            }
                            if (text == null) failed = true
                            text?.takeIf { it.isNotBlank() && PlayerSubtitleCueParser.parse(it, subtitle.url).isNotEmpty() }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            // An error status or unreadable text means the server answered; anything else did not.
                            if (error !is IllegalStateException && error !is IllegalArgumentException) failed = true
                            null
                        }
                        Triple(subtitle, body, failed)
                    }
                }.awaitAll()
            }
            for ((subtitle, body, failed) in fetched) {
                currentCoroutineContext().ensureActive()
                if (body == null) {
                    if (failed) {
                        unreachable++
                        newFailures += FailedSubtitle(subtitle.url, nowEpochMs, (previousFailed[subtitle.url]?.attempts ?: 0) + 1)
                    }
                    continue
                }
                val extension = PlayerSubtitleCueParser.fileExtension(body, subtitle.url)
                try {
                    var stored = false
                    val ran = guardedWrite {
                        // Read the manifest again here, under the write lock: another run may have saved
                        // tracks since this one started. Merge into it; never replace it with a snapshot.
                        val current = readManifest(storage)
                        if (current.tracks.any { it.sourceUrl == subtitle.url && storage.localFileUri(it.fileName) != null }) return@guardedWrite
                        val fileName = freshFileName(current, storage, extension)
                        storage.write(fileName, body)
                        val track = DownloadedSubtitle(subtitle.url, fileName, subtitle.language, subtitle.name)
                        storage.write(
                            "manifest.json",
                            json.encodeToString(
                                current.copy(
                                    tracks = current.tracks + track,
                                    failed = current.failed.filterNot { it.sourceUrl == subtitle.url },
                                ),
                            ),
                        )
                        stored = true
                    }
                    if (!ran) return SubtitleSaveResult(saved = savedCount(localVideoUri), newlySaved = newlySaved, unreachable = unreachable)
                    if (stored) newlySaved++
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                }
            }
        }
        currentCoroutineContext().ensureActive()
        guardedWrite {
            runCatching {
                // Under the lock, from the manifest as it is now. The tracks are exactly the ones found there, so
                // this run never writes fewer than it finds. What failed is merged the same way: records of
                // other runs stay, those of tracks that are saved now go, and ours are added.
                val current = readManifest(storage)
                val savedNow = current.tracks.map { it.sourceUrl }.toSet()
                val merged = LinkedHashMap<String, FailedSubtitle>()
                current.failed.filter { it.sourceUrl !in attempted && it.sourceUrl !in savedNow }.forEach { merged[it.sourceUrl] = it }
                newFailures.filter { it.sourceUrl !in savedNow }.forEach { mine ->
                    val other = merged[mine.sourceUrl]
                    merged[mine.sourceUrl] = if (other == null || mine.attempts >= other.attempts) mine else other
                }
                val failed = merged.values.toList()
                val checkedAt = if (failed.isEmpty()) nowEpochMs else 0L
                storage.write(
                    "manifest.json",
                    json.encodeToString(current.copy(complete = failed.isEmpty(), checkedAtEpochMs = checkedAt, failed = failed)),
                )
            }
        }
        return SubtitleSaveResult(saved = savedCount(localVideoUri), newlySaved = newlySaved, unreachable = unreachable)
    }

    /** A file name no track of [current] uses and no file in the folder has, whatever its extension. */
    private fun freshFileName(current: SubtitleManifest, storage: DownloadSubtitleStorage, extension: String): String {
        val used = current.tracks.mapNotNull { it.fileName.substringBefore('.').toIntOrNull() }.toSet()
        var index = (used.maxOrNull() ?: -1) + 1
        while (index in used || SUBTITLE_EXTENSIONS.any { storage.localFileUri("$index.$it") != null } ||
            storage.localFileUri("$index.$extension") != null
        ) index++
        return "$index.$extension"
    }

    private val SUBTITLE_EXTENSIONS = listOf("srt", "vtt", "ass", "ssa", "sub", "ttml", "dfxp", "txt")

    fun localSubtitles(localVideoUri: String): List<StreamSubtitle> {
        if (!localVideoUri.startsWith("file:")) return emptyList()
        val storage = DownloadSubtitleStorage(localVideoUri)
        return readManifest(storage).tracks.mapNotNull { track ->
            val uri = storage.localFileUri(track.fileName) ?: return@mapNotNull null
            StreamSubtitle(url = uri, language = track.language, name = track.name)
        }
    }

    private fun readManifest(storage: DownloadSubtitleStorage): SubtitleManifest =
        runCatching {
            storage.read("manifest.json")?.let { json.decodeFromString<SubtitleManifest>(it) }
        }.getOrNull() ?: SubtitleManifest()
}

@Serializable
internal data class SubtitleManifest(
    val complete: Boolean = false,
    val tracks: List<DownloadedSubtitle> = emptyList(),
    /** When a complete look was made, in milliseconds since 1970. Zero when unknown. */
    val checkedAtEpochMs: Long = 0L,
    /** Tracks that could not be fetched, and when. Older code ignores this key. */
    val failed: List<FailedSubtitle> = emptyList(),
)

@Serializable
internal data class FailedSubtitle(
    val sourceUrl: String,
    val atEpochMs: Long,
    /** Failures in a row; the cooldown grows with it. */
    val attempts: Int = 1,
)

@Serializable
internal data class DownloadedSubtitle(
    val sourceUrl: String,
    val fileName: String,
    val language: String,
    val name: String? = null,
)

internal expect class DownloadSubtitleStorage(localVideoUri: String) {
    fun read(fileName: String): String?
    fun write(fileName: String, text: String)

    /** Writes bytes the same way [write] writes text: replacing the file in one step. */
    fun writeBytes(fileName: String, bytes: ByteArray)
    fun localFileUri(fileName: String): String?
    fun remove()

    /** True when a folder of that name is already beside the video and is not the app's: nothing is saved in it. */
    fun refused(): Boolean
}

/** The subtitle file names a saved manifest lists, or null when [text] is not a manifest of this app. */
internal fun subtitleManifestFileNames(text: String): List<String>? = runCatching {
    kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(SubtitleManifest.serializer(), text).tracks.map { it.fileName }
}.getOrNull()
