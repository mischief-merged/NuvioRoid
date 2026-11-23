package com.nuvio.app.features.streams

import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.toastMessage
import com.nuvio.app.features.downloads.DownloadEnqueueResult

/** Why the list of sources is open: to play one, or to pick one to download. */
enum class StreamPickPurpose {
    /** A tap on a source plays it. Downloading is the small action beside it. */
    Play,

    /**
     * Opened from a download that needs another source. A tap anywhere on a source downloads it, in
     * place of that download, and nothing in the list plays.
     */
    Download,
}

/** What a tap on [stream], or its "open" action, does for [purpose]: [play] it or [download] it, never both. */
internal fun routeStreamPick(
    purpose: StreamPickPurpose,
    stream: StreamItem,
    play: (StreamItem) -> Unit,
    download: (StreamItem) -> Unit,
) = when (purpose) {
    StreamPickPurpose.Play -> play(stream)
    StreamPickPurpose.Download -> download(stream)
}

/** How downloading a picked source ended. */
internal class StreamDownloadOutcome(
    /** The download was queued: [DownloadEnqueueResult.Started] or [DownloadEnqueueResult.Replaced]. */
    val queued: Boolean,
    /** What to tell the user, or null. */
    val message: String?,
)

/**
 * Downloads a source the user picked: a source that needs a debrid link gets one first ([resolve]),
 * then it is queued by [enqueue], which replaces the title's download that has no good file. The
 * download button and a tap in download mode both come here.
 */
internal suspend fun downloadPickedStream(
    stream: StreamItem,
    needsResolve: Boolean,
    resolve: suspend (StreamItem) -> DirectDebridPlayableResult,
    enqueue: (StreamItem) -> DownloadEnqueueResult,
    message: (DownloadEnqueueResult) -> String? = { it.toastMessage() },
): StreamDownloadOutcome {
    val playable = if (needsResolve) {
        when (val resolved = resolve(stream)) {
            is DirectDebridPlayableResult.Success -> resolved.stream
            else -> return StreamDownloadOutcome(queued = false, message = resolved.toastMessage())
        }
    } else {
        stream
    }
    val result = enqueue(playable)
    val queued = result == DownloadEnqueueResult.Started || result == DownloadEnqueueResult.Replaced
    return StreamDownloadOutcome(queued = queued, message = message(result))
}
