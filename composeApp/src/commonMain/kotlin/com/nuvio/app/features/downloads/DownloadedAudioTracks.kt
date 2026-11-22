package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DebridStreamLanguage

/**
 * The languages of the audio tracks of a Matroska, WebM, MP4 or QuickTime file, in the order of the
 * tracks, as the container states them; an empty string stands for a track without a language.
 * Null when the file is of another kind or its track list is not found. Only the track headers are
 * read, through [read], which returns [length] bytes at [offset] or null when they cannot be read.
 */
internal fun audioTrackLanguages(fileSize: Long, read: (offset: Long, length: Int) -> ByteArray?): List<String>? {
    val start = read(0L, 4) ?: return null
    val isMatroska = start.size == 4 && uint(start, 0, 4) == EbmlHeaderId
    return if (isMatroska) matroskaAudioLanguages(fileSize, read) else mp4AudioLanguages(fileSize, read)
}

private fun uint(bytes: ByteArray, at: Int, length: Int): Long =
    (0 until length).fold(0L) { value, index -> (value shl 8) or (bytes[at + index].toLong() and 0xff) }

private fun String.asTrackLanguage(): String =
    trim { it <= ' ' }.lowercase().takeUnless { it == "und" || it == "mis" || it == "zxx" }.orEmpty()

private class EbmlElement(val id: Long, val contentStart: Long, val size: Long?)

/** The number of bytes of a variable-length EBML number that starts with [first]. */
private fun ebmlLength(first: Byte, max: Int): Int? =
    (1..max).firstOrNull { (first.toInt() and 0xff) and (0x80 shr (it - 1)) != 0 }

private fun ebmlElementAt(offset: Long, read: (offset: Long, length: Int) -> ByteArray?): EbmlElement? {
    val header = read(offset, 12)?.takeIf { it.isNotEmpty() } ?: return null
    val idLength = ebmlLength(header[0], 4) ?: return null
    if (header.size <= idLength) return null
    val sizeLength = ebmlLength(header[idLength], 8) ?: return null
    if (header.size < idLength + sizeLength) return null
    val firstSizeByte = (header[idLength].toLong() and 0xff) and (0xffL shr sizeLength)
    val size = (1 until sizeLength).fold(firstSizeByte) { value, index ->
        (value shl 8) or (header[idLength + index].toLong() and 0xff)
    }
    // All bits set means the size was not known when the file was written.
    val unknown = size == (1L shl (7 * sizeLength)) - 1
    return EbmlElement(uint(header, 0, idLength), offset + idLength + sizeLength, size.takeUnless { unknown })
}

/** The elements that fill [start] to [end]. Null when they cannot all be read, so that no part is taken for the whole. */
private fun ebmlChildren(start: Long, end: Long, read: (offset: Long, length: Int) -> ByteArray?): List<EbmlElement>? {
    val children = mutableListOf<EbmlElement>()
    var offset = start
    while (offset < end) {
        if (children.size >= MaxElementsPerLevel) return null
        val element = ebmlElementAt(offset, read) ?: return null
        val size = element.size ?: return null
        if (element.contentStart + size > end) return null
        children += element
        offset = element.contentStart + size
    }
    return children
}

internal data class MatroskaInfo(val durationMs: Long?, val title: String?)

/** Reads only segment info before the first media cluster, with bounded header and field reads. */
internal fun matroskaInfo(fileSize: Long, read: (Long, Int) -> ByteArray?): MatroskaInfo? {
    val header = ebmlElementAt(0L, read)?.takeIf { it.id == EbmlHeaderId } ?: return null
    val segment = ebmlElementAt(header.contentStart + (header.size ?: return null), read)
        ?.takeIf { it.id == SegmentId } ?: return null
    val end = segment.size?.let { size ->
        if (size > fileSize - segment.contentStart) return null
        segment.contentStart + size
    } ?: fileSize
    var offset = segment.contentStart
    repeat(MaxElementsPerLevel) {
        if (offset >= end) return null
        val element = ebmlElementAt(offset, read) ?: return null
        if (element.id == ClusterId) return null
        val size = element.size ?: return null
        if (size > end - element.contentStart) return null
        if (element.id == 0x1549a966L) {
            if (size > 65_536L) return null
            val fields = ebmlChildren(element.contentStart, element.contentStart + size, read) ?: return null
            fun field(id: Long, max: Int): ByteArray? = fields.firstOrNull { it.id == id }?.let {
                val length = it.size ?: return null
                if (length !in 1L..max.toLong()) return null
                read(it.contentStart, length.toInt())?.takeIf { bytes -> bytes.size.toLong() == length }
            }
            val scale = field(0x2ad7b1L, 8)?.let { uint(it, 0, it.size) } ?: 1_000_000L
            val duration = field(0x4489L, 8)?.let {
                when (it.size) {
                    4 -> Float.fromBits(uint(it, 0, 4).toInt()).toDouble()
                    8 -> Double.fromBits(uint(it, 0, 8))
                    else -> null
                }
            }
            val millis = duration?.times(scale.toDouble() / 1_000_000.0)
                ?.takeIf { scale > 0L && it.isFinite() && it > 0.0 && it < Long.MAX_VALUE.toDouble() }?.toLong()
            val title = field(0x7ba9L, 4096)?.decodeToString()?.trimEnd('\u0000')?.takeIf { it.isNotBlank() }
            return MatroskaInfo(millis, title)
        }
        offset = element.contentStart + size
    }
    return null
}

private fun matroskaAudioLanguages(fileSize: Long, read: (offset: Long, length: Int) -> ByteArray?): List<String>? {
    val header = ebmlElementAt(0L, read) ?: return null
    val segment = ebmlElementAt(header.contentStart + (header.size ?: return null), read)?.takeIf { it.id == SegmentId }
        ?: return null
    val segmentEnd = segment.size?.let { segment.contentStart + it }?.coerceAtMost(fileSize) ?: fileSize

    // The tracks come before the first cluster of media. They are looked for only up to there.
    var offset = segment.contentStart
    repeat(MaxElementsPerLevel) {
        val element = ebmlElementAt(offset, read) ?: return null
        val size = element.size ?: return null
        if (element.id == ClusterId || element.contentStart + size > segmentEnd) return null
        if (element.id == TracksId) {
            if (size > MaxTrackListBytes) return null
            val tracks = read(element.contentStart, size.toInt())?.takeIf { it.size.toLong() == size } ?: return null
            return matroskaAudioLanguages(tracks)
        }
        offset = element.contentStart + size
    }
    return null
}

private fun matroskaAudioLanguages(tracks: ByteArray): List<String>? {
    val read = { offset: Long, length: Int ->
        if (offset < 0 || offset >= tracks.size) null
        else tracks.copyOfRange(offset.toInt(), minOf(offset + length, tracks.size.toLong()).toInt())
    }
    fun text(element: EbmlElement) =
        tracks.copyOfRange(element.contentStart.toInt(), (element.contentStart + element.size!!).toInt())
            .takeWhile { it.toInt() != 0 }
            .toByteArray()
            .decodeToString()

    val entries = ebmlChildren(0L, tracks.size.toLong(), read) ?: return null
    return entries.filter { it.id == TrackEntryId }.mapNotNull { entry ->
        val fields = ebmlChildren(entry.contentStart, entry.contentStart + entry.size!!, read) ?: return null
        val type = fields.firstOrNull { it.id == TrackTypeId }
            ?.let { uint(tracks, it.contentStart.toInt(), it.size!!.toInt().coerceAtMost(8)) }
            ?: return null
        if (type != MatroskaAudioTrackType) return@mapNotNull null
        val language = fields.firstOrNull { it.id == LanguageBcp47Id } ?: fields.firstOrNull { it.id == LanguageId }
        language?.let(::text).orEmpty().asTrackLanguage()
    }
}

private class Mp4Box(val type: String, val contentStart: Long, val end: Long)

/** The boxes that fill [start] to [end]. Null when they cannot all be read, so that no part is taken for the whole. */
private fun mp4Boxes(start: Long, end: Long, read: (offset: Long, length: Int) -> ByteArray?): List<Mp4Box>? {
    val boxes = mutableListOf<Mp4Box>()
    return boxes.takeIf { mp4Box(start, end, read) { boxes += it; boxes.size < MaxElementsPerLevel } }
}

/** Hands the boxes from [start] on to [next] until it returns false. False when a box cannot be read before that. */
private fun mp4Box(start: Long, end: Long, read: (offset: Long, length: Int) -> ByteArray?, next: (Mp4Box) -> Boolean): Boolean {
    var offset = start
    while (offset < end) {
        if (offset + 8 > end) return false
        val header = read(offset, (end - offset).coerceAtMost(16L).toInt())?.takeIf { it.size >= 8 } ?: return false
        var size = uint(header, 0, 4)
        var headerSize = 8L
        if (size == 1L) {
            if (header.size < 16) return false
            size = uint(header, 8, 8)
            headerSize = 16L
        } else if (size == 0L) {
            size = end - offset
        }
        if (size < headerSize || offset + size > end) return false
        val box = Mp4Box(CharArray(4) { (header[4 + it].toInt() and 0xff).toChar() }.concatToString(), offset + headerSize, offset + size)
        offset += size
        if (!next(box)) return offset >= end
    }
    return true
}

private fun mp4AudioLanguages(fileSize: Long, read: (offset: Long, length: Int) -> ByteArray?): List<String>? {
    // What follows the movie header at the top of the file does not matter and is not read.
    var movie: Mp4Box? = null
    var seen = 0
    mp4Box(0L, fileSize, read) { box ->
        if (box.type == "moov") movie = box
        movie == null && ++seen < MaxElementsPerLevel
    }
    val (movieStart, movieEnd) = movie?.let { it.contentStart to it.end } ?: return null
    val tracks = mp4Boxes(movieStart, movieEnd, read) ?: return null
    return tracks.filter { it.type == "trak" }.mapNotNull { track ->
        // A track whose kind cannot be told might be an audio track, so nothing is said about the file.
        val media = mp4Boxes(track.contentStart, track.end, read)?.firstOrNull { it.type == "mdia" } ?: return null
        val parts = mp4Boxes(media.contentStart, media.end, read) ?: return null
        val handler = parts.firstOrNull { it.type == "hdlr" }?.let { read(it.contentStart, 12) }?.takeIf { it.size == 12 }
            ?: return null
        if (handler.copyOfRange(8, 12).decodeToString() != "soun") return@mapNotNull null
        val header = parts.firstOrNull { it.type == "mdhd" }?.let { read(it.contentStart, 34) }?.takeIf { it.isNotEmpty() }
            ?: return@mapNotNull ""
        // Three letters of five bits each, after the times and the duration, whose width the version sets.
        val at = if (header[0].toInt() == 1) 32 else 20
        if (header.size < at + 2) return@mapNotNull ""
        val packed = uint(header, at, 2).toInt()
        val letters = CharArray(3) { (((packed shr (10 - 5 * it)) and 0x1f) + 0x60).toChar() }
        if (letters.all { it in 'a'..'z' }) letters.concatToString().asTrackLanguage() else ""
    }
}

/** How the audio tracks of a downloaded file stand to the language that was chosen for the download. */
internal sealed interface AudioTrackCheck {
    /** Nothing to hold against the file: no language chosen, tracks not readable, or a track without a language. */
    data object Unknown : AudioTrackCheck

    /** The first audio track is in the chosen language. */
    data object First : AudioTrackCheck

    /** A track in the chosen language is there but another one comes first. [language] is the one to start on. */
    data class Later(val language: String) : AudioTrackCheck

    /** Every audio track states a language and none is the chosen one. */
    data object Missing : AudioTrackCheck
}

internal fun checkAudioTracks(trackLanguages: List<String>?, chosen: DebridStreamLanguage?): AudioTrackCheck {
    val wanted = chosen?.containerLanguageCodes ?: return AudioTrackCheck.Unknown
    if (trackLanguages.isNullOrEmpty()) return AudioTrackCheck.Unknown
    val matches = trackLanguages.map { it.substringBefore('-') in wanted }
    return when {
        matches.first() -> AudioTrackCheck.First
        matches.any { it } -> AudioTrackCheck.Later(wanted.first())
        trackLanguages.any { it.isEmpty() } -> AudioTrackCheck.Unknown
        else -> AudioTrackCheck.Missing
    }
}

/**
 * The codes a container uses for this language, the two-letter code first. Null for a choice that
 * names no single language.
 */
private val DebridStreamLanguage.containerLanguageCodes: List<String>?
    get() = when (this) {
        DebridStreamLanguage.EN -> listOf("en", "eng")
        DebridStreamLanguage.HI -> listOf("hi", "hin")
        DebridStreamLanguage.IT -> listOf("it", "ita")
        DebridStreamLanguage.ES, DebridStreamLanguage.LA -> listOf("es", "spa")
        DebridStreamLanguage.FR -> listOf("fr", "fre", "fra")
        DebridStreamLanguage.DE -> listOf("de", "ger", "deu")
        DebridStreamLanguage.PT -> listOf("pt", "por")
        DebridStreamLanguage.PL -> listOf("pl", "pol")
        DebridStreamLanguage.CS -> listOf("cs", "cze", "ces")
        DebridStreamLanguage.JA -> listOf("ja", "jpn")
        DebridStreamLanguage.KO -> listOf("ko", "kor")
        DebridStreamLanguage.ZH -> listOf("zh", "chi", "zho", "cmn", "yue")
        DebridStreamLanguage.MULTI, DebridStreamLanguage.UNKNOWN -> null
    }

private const val EbmlHeaderId = 0x1a45dfa3L
private const val SegmentId = 0x18538067L
private const val TracksId = 0x1654ae6bL
private const val ClusterId = 0x1f43b675L
private const val TrackEntryId = 0xaeL
private const val TrackTypeId = 0x83L
private const val LanguageId = 0x22b59cL
private const val LanguageBcp47Id = 0x22b59dL
private const val MatroskaAudioTrackType = 2L
private const val MaxTrackListBytes = 4L * 1024 * 1024
private const val MaxElementsPerLevel = 64
