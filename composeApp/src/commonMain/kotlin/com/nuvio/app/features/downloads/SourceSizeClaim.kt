package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem
import kotlin.math.abs
import kotlin.math.round

/*
 * The size a source says its file has, read from the text it shows when it does not state one in a
 * structured field. Such text is written for people ("[💾 9.53 GB] Title.2026.1080p.mkv"), so every
 * reading here is careful: a number is taken only with a size unit, a bitrate or a count is never
 * taken, and when the text names more than one size without saying which is the file's, nothing is
 * claimed. A claim that is not there is better than a wrong one: a wrong one makes a good file look
 * like a placeholder.
 */

private const val Kilo = 1000.0
private const val Kibi = 1024.0

/** A size smaller than this is not the size of a video file. */
private const val SmallestClaimBytes = 1_000_000L

/** A size larger than this is not the size of one video file. */
private const val LargestClaimBytes = 1_000_000_000_000L

// A number, then a size unit that is not the start of a longer word and not a rate ("10 Mb/s", "5 Mbps").
private val SizeToken = Regex(
    """(?<![\p{L}\p{N}.,])(\d{1,4}(?:[.,]\d{1,3})?)\s?(TiB|TB|GiB|GB|MiB|MB)(?![\p{L}\p{N}]|\s?/\s?s|\s?ps\b)""",
    RegexOption.IGNORE_CASE,
)

private val SizeWordBefore = Regex("""size\s*[:=\-]?\s*$""", RegexOption.IGNORE_CASE)

private const val DiskSymbol = "💾"

// Text that says the file is one of many: a season pack, a complete series, a collection, a range of
// episodes or "x of y files". The size such text states is the size of the whole pack, not of the one
// file that was fetched from it. When in doubt this says pack: a claim not made is better than a wrong one.
private val PackMarker = Regex(
    """(?<![\p{L}\p{N}])(complete|pack|batch|collection|box\s?set|trilogy|saga|anthology|full\s+season)(?![\p{L}\p{N}])""" +
        // "Season 1", "Season 1-3": a season named without an episode of it.
        """|(?<![\p{L}\p{N}])seasons?[\s._-]*\d{1,2}(?![\p{L}\p{N}])(?![\s._-]*(episode|ep|e)[\s._-]*\d)""" +
        """|(?<![\p{L}\p{N}])S\d{1,2}\s*[-\u2013\u2014]\s*S?\d{1,2}(?![\p{L}\p{N}])""" +
        // "S01" with no episode after it.
        """|(?<![\p{L}\p{N}])S\d{1,2}(?![\p{L}\p{N}])(?![\s._-]*E\d)""" +
        // A range or list of episodes: "S01E01-E10", "E01-E10", "S01E01E02".
        """|(?<!\p{L})E\d{1,3}\s*[-\u2013\u2014]\s*E?\d{1,3}(?![\p{L}\p{N}])""" +
        """|(?<!\p{L})E\d{1,3}E\d{1,3}(?![\p{L}\p{N}])""" +
        // "10 files", "3 of 10 files".
        """|(?<![\p{L}\p{N}])\d+\s*(of|/)\s*\d+\s*files?(?![\p{L}\p{N}])|(?<![\p{L}\p{N}])\d+\s+files(?![\p{L}\p{N}])""",
    RegexOption.IGNORE_CASE,
)

/** One size found in a text: [bytes], and whether the text marks it as the size of the file. */
private class SizeMention(val bytes: Long, val marked: Boolean)

private fun unitBytes(unit: String): Double = when (unit.lowercase()) {
    "mb" -> Kilo * Kilo
    "gb" -> Kilo * Kilo * Kilo
    "tb" -> Kilo * Kilo * Kilo * Kilo
    "mib" -> Kibi * Kibi
    "gib" -> Kibi * Kibi * Kibi
    "tib" -> Kibi * Kibi * Kibi * Kibi
    else -> 0.0
}

private fun mentionsIn(text: String): List<SizeMention> = SizeToken.findAll(text).mapNotNull { match ->
    val number = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
    val bytes = (number * unitBytes(match.groupValues[2])).toLong()
    if (bytes !in SmallestClaimBytes..LargestClaimBytes) return@mapNotNull null
    val before = text.substring((match.range.first - 12).coerceAtLeast(0), match.range.first)
        .trimEnd('[', '(', ' ', '\t', '\uFE0F')
    val marked = before.endsWith(DiskSymbol) || SizeWordBefore.containsMatchIn(before)
    SizeMention(bytes, marked)
}.toList()

/**
 * The size in bytes that [text] states for a file, or null. A size marked with a disk symbol or the
 * word "size" wins over unmarked ones; with no marked size, a single size (however often it is
 * written) is the claim. Several different sizes with no mark to tell them apart claim nothing.
 * Decimal units (GB, MB) count 1000, binary ones (GiB, MiB) 1024, as the source wrote them.
 */
internal fun parseClaimedSizeBytes(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    val mentions = mentionsIn(text)
    val marked = mentions.filter { it.marked }.map { it.bytes }.distinct()
    if (marked.size == 1) return marked.single()
    if (marked.size > 1) return null
    return mentions.map { it.bytes }.distinct().singleOrNull()
}

/** Whether [text] describes a pack of files, whose stated size is not the size of one file. */
internal fun looksLikePack(text: String?): Boolean = !text.isNullOrBlank() && PackMarker.containsMatchIn(text)

/**
 * The size a source claims in its title and description. Null when they state none, are not clear
 * about which size is the file's, or describe a pack, where the size is that of the whole pack.
 */
internal fun claimedSizeFromSourceText(title: String?, subtitle: String?): Long? {
    val text = listOfNotNull(title, subtitle).joinToString("\n")
    if (looksLikePack(text)) return null
    return parseClaimedSizeBytes(text)
}

/**
 * The fastest an episode can plausibly be encoded: 40 Mbit/s, above the best 1080p remux. A claim for
 * a series episode that spread over its runtime comes to more than this is the size of something
 * larger, such as a season, and is not used.
 */
internal const val MaxPlausibleEpisodeBitsPerSecond = 40_000_000L

/** The largest size taken for one episode when the title's runtime is not known: 12 GB. */
internal const val MaxPlausibleEpisodeBytesWithoutRuntime = 12_000_000_000L

/** Whether [bytes] can be one episode of a title that runs [runtimeMinutes] (not known: null). */
internal fun isPlausibleForOneEpisode(bytes: Long, runtimeMinutes: Int?): Boolean {
    val minutes = runtimeMinutes?.takeIf { it > 0 } ?: return bytes <= MaxPlausibleEpisodeBytesWithoutRuntime
    return bytes.toDouble() * 8.0 / (minutes * 60.0) <= MaxPlausibleEpisodeBitsPerSecond.toDouble()
}

/**
 * The size a source claims for the file that is selected, or null. A claim is used only when it is
 * established to describe that file, and abstains when in doubt:
 *  - a stream that selects a file by index (`fileIdx`) is a file of a torrent that may hold many;
 *  - a size the source states in a structured field is not used when the stream is a torrent
 *    (`infoHash`), since it may be the size of the whole torrent;
 *  - text that describes a pack, a season or a range of episodes;
 *  - for a series episode, a size too large for one episode (see [isPlausibleForOneEpisode]).
 */
internal fun usableSizeClaim(stream: StreamItem, isEpisode: Boolean, runtimeMinutes: Int?): Long? {
    if (stream.fileIdx != null) return null
    if (looksLikePack(listOfNotNull(stream.streamLabel, stream.streamSubtitle, stream.title).joinToString("\n"))) return null
    val structured = stream.behaviorHints.videoSize?.takeIf { it > 0L && stream.infoHash == null }
    val claim = structured ?: claimedSizeFromSourceText(stream.streamLabel, stream.streamSubtitle) ?: return null
    return claim.takeIf { !isEpisode || isPlausibleForOneEpisode(it, runtimeMinutes) }
}

/**
 * The size the source claims for this download's file: the size kept with it, or else the one its
 * text names. Pack wording in its text, or a size too large for one episode, makes it null, also for a
 * size kept by an earlier build, which did not look at either.
 */
internal fun DownloadItem.claimedSizeBytes(runtimeMinutes: Int? = null): Long? {
    if (looksLikePack(listOfNotNull(streamTitle, streamSubtitle).joinToString("\n"))) return null
    val claim = expectedSizeBytes?.takeIf { it > 0L } ?: claimedSizeFromSourceText(streamTitle, streamSubtitle) ?: return null
    return claim.takeIf { !isEpisode || isPlausibleForOneEpisode(it, runtimeMinutes) }
}

/** The claim of a source as a size for a sentence: "9.5 GB", in the units the source wrote its size in. */
internal fun formatClaimedSize(bytes: Long): String = formatDecimalSize(bytes)

/** A size in decimal units with one decimal: "8.5 MB", "9.5 GB". */
internal fun formatDecimalSize(bytes: Long): String {
    val value = bytes.toDouble()
    fun one(amount: Double) = "${(amount * 10.0).toLong() / 10.0}"
    return when {
        value >= Kilo * Kilo * Kilo -> "${one(value / (Kilo * Kilo * Kilo))} GB"
        value >= Kilo * Kilo -> "${one(value / (Kilo * Kilo))} MB"
        value >= Kilo -> "${one(value / Kilo)} KB"
        else -> "$bytes B"
    }
}

internal enum class DecimalByteScale(val suffix: String) {
    Bytes("B"),
    Kilobytes("KB"),
    Megabytes("MB"),
    Gigabytes("GB"),
}

internal data class DecimalByteSize(val amount: String, val scale: DecimalByteScale)

/**
 * A size in decimal units, 1000 bytes to a kilobyte, the way the storage limit and macOS Finder
 * count. One decimal place, rounded, so a limit of 250 GB is shown as 250.0 GB and not 232.8.
 */
internal fun decimalByteSize(bytes: Long): DecimalByteSize {
    if (bytes <= 0L) return DecimalByteSize("0", DecimalByteScale.Bytes)
    val value = bytes.toDouble()
    val kilo = 1000.0
    val mega = kilo * 1000.0
    val giga = mega * 1000.0
    fun scaled(amount: Double, scale: DecimalByteScale): DecimalByteSize {
        // A decimal count reprints the point ("10.0.0"), so the rounded tenths stay a whole number.
        val tenths = round(amount * 10.0).toLong()
        val whole = tenths / 10
        val fraction = abs(tenths % 10)
        return DecimalByteSize("$whole.$fraction", scale)
    }
    return when {
        value >= giga -> scaled(value / giga, DecimalByteScale.Gigabytes)
        value >= mega -> scaled(value / mega, DecimalByteScale.Megabytes)
        value >= kilo -> scaled(value / kilo, DecimalByteScale.Kilobytes)
        else -> DecimalByteSize(bytes.toString(), DecimalByteScale.Bytes)
    }
}

/** A size as the Downloads list shows it, in decimal units: "8.5 MB". */
internal fun formatListedSize(bytes: Long): String {
    val size = decimalByteSize(bytes)
    return "${size.amount} ${size.scale.suffix}"
}
