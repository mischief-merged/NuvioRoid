package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable

/** What a look at the file of a download tells. */
internal data class DownloadedFileFacts(
    val exists: Boolean,
    val sizeBytes: Long = 0L,
    /** Whether the file starts like a video container. */
    val isVideoContainer: Boolean = false,
    /** The duration the container states, where it can be read without parsing the file. */
    val durationMs: Long? = null,
    /** The OpenSubtitles hash of the file, the one a source can state as its videoHash. */
    val videoHash: String? = null,
    /** The languages of the audio tracks in track order, where the container could be read. */
    val audioLanguages: List<String>? = null,
    /** The title stated in a readable container header. */
    val containerTitle: String? = null,
)

/** Looks at the file of a download. Null where the platform cannot look, which is never a fault. */
internal expect fun inspectDownloadedFile(localFileUri: String): DownloadedFileFacts?

/** Why a file that was downloaded is not the download. */
internal enum class DownloadedFileProblem {
    Missing,

    /** Smaller or larger than the size the server announced. */
    Incomplete,

    /** Not a video container: an error page or some other answer in place of the file. */
    NotVideo,

    /** Not the file the source said it would be: its hash differs from the one the source stated. */
    ChecksumMismatch,
}

/** What a finished download was checked against, strongest first. */
@Serializable
enum class DownloadVerification {
    /** The hash the source stated for its file. */
    Hash,

    /** A digest of the body that the server sent with it. */
    Digest,

    /** The size the server or the source announced. */
    Size,

    /** Nothing but that the file is there and is a video. */
    None,
}

/**
 * The problem with a file that has just been downloaded, if it has one. [expectedVideoHash] is the
 * hash the source stated; it is compared only when the file's own hash could be worked out.
 */
internal fun DownloadedFileFacts.problem(announcedBytes: Long?, expectedVideoHash: String? = null): DownloadedFileProblem? = when {
    !exists -> DownloadedFileProblem.Missing
    announcedBytes != null && announcedBytes > 0L && sizeBytes != announcedBytes -> DownloadedFileProblem.Incomplete
    !isVideoContainer -> DownloadedFileProblem.NotVideo
    expectedVideoHash != null && videoHash != null && !videoHash.equals(expectedVideoHash, ignoreCase = true) ->
        DownloadedFileProblem.ChecksumMismatch
    else -> null
}

/** What a file that passed [problem] was checked against. */
internal fun DownloadedFileFacts.verification(
    announcedBytes: Long?,
    expectedVideoHash: String?,
    expectedSizeBytes: Long?,
    digestVerified: Boolean,
): DownloadVerification = when {
    expectedVideoHash != null && videoHash != null -> DownloadVerification.Hash
    digestVerified -> DownloadVerification.Digest
    (announcedBytes != null && announcedBytes > 0L) || (expectedSizeBytes != null && expectedSizeBytes == sizeBytes) ->
        DownloadVerification.Size
    else -> DownloadVerification.None
}

/** A source's videoHash when it has the form of an OpenSubtitles hash, 16 hexadecimal digits. */
internal fun String?.asVideoHash(): String? =
    this?.trim()?.lowercase()?.takeIf { hash -> hash.length == 16 && hash.all { it in '0'..'9' || it in 'a'..'f' } }

/** The number of bytes from the start of a file that [startsLikeVideoContainer] wants to see. */
internal const val VideoContainerHeaderBytes = 192

/**
 * Whether [header], the first bytes of a file, are those of a video container: MP4 or QuickTime,
 * Matroska or WebM, AVI, MPEG transport or program stream, Flash video, ASF or Ogg. Nothing is
 * parsed beyond the signature.
 */
internal fun startsLikeVideoContainer(header: ByteArray): Boolean {
    fun at(offset: Int, vararg bytes: Int) =
        header.size >= offset + bytes.size && bytes.indices.all { (header[offset + it].toInt() and 0xff) == bytes[it] }

    fun textAt(offset: Int, text: String) = at(offset, *text.map { it.code }.toIntArray())

    return listOf("ftyp", "moov", "mdat", "free", "skip", "wide").any { textAt(4, it) } ||
        at(0, 0x1a, 0x45, 0xdf, 0xa3) ||
        (textAt(0, "RIFF") && textAt(8, "AVI ")) ||
        (at(0, 0x47) && at(188, 0x47)) ||
        at(0, 0x00, 0x00, 0x01, 0xba) ||
        textAt(0, "FLV") ||
        at(0, 0x30, 0x26, 0xb2, 0x75, 0x8e, 0x66, 0xcf, 0x11) ||
        textAt(0, "OggS")
}

/**
 * The duration an MP4 or QuickTime file states in its movie header, or null when the file is not
 * one or the header is not found. Only box headers are read, through [read], which returns
 * [length] bytes at [offset] or null when they cannot be read.
 */
internal fun mp4DurationMs(fileSize: Long, read: (offset: Long, length: Int) -> ByteArray?): Long? {
    fun uint(bytes: ByteArray, at: Int, length: Int): Long =
        (0 until length).fold(0L) { value, index -> (value shl 8) or (bytes[at + index].toLong() and 0xff) }

    // The box of [type] between [start] and [end]: where its content starts and where it ends.
    fun find(type: String, start: Long, end: Long): Pair<Long, Long>? {
        var offset = start
        repeat(MaxBoxesPerLevel) {
            if (offset + 8 > end) return null
            val header = read(offset, 16.coerceAtMost((end - offset).toInt().coerceAtLeast(8))) ?: return null
            if (header.size < 8) return null
            var size = uint(header, 0, 4)
            var headerSize = 8L
            if (size == 1L) {
                if (header.size < 16) return null
                size = uint(header, 8, 8)
                headerSize = 16L
            } else if (size == 0L) {
                size = end - offset
            }
            if (size < headerSize || offset + size > end) return null
            val name = CharArray(4) { (header[4 + it].toInt() and 0xff).toChar() }.concatToString()
            if (name == type) return (offset + headerSize) to (offset + size)
            offset += size
        }
        return null
    }

    val movie = find("moov", 0L, fileSize) ?: return null
    val (start, end) = find("mvhd", movie.first, movie.second) ?: return null
    val header = read(start, 32.coerceAtMost((end - start).toInt())) ?: return null
    if (header.isEmpty()) return null
    val (timescale, duration) = when (header[0].toInt()) {
        0 -> if (header.size < 20) return null else uint(header, 12, 4) to uint(header, 16, 4)
        1 -> if (header.size < 32) return null else uint(header, 20, 4) to uint(header, 24, 8)
        else -> return null
    }
    if (timescale <= 0L || duration < 0L) return null
    return duration * 1000L / timescale
}

private const val MaxBoxesPerLevel = 64
