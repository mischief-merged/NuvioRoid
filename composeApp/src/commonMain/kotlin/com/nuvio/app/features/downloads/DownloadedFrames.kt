package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable

/** Which part of a video a decoded frame was taken from. */
internal enum class FrameWindow { Start, Middle, End }

/**
 * One look at the picture at [positionMs]: [pixels] is a [FramePixelCount]-byte grayscale picture of
 * [FrameSide] by [FrameSide] pixels, or null when no frame could be decoded there.
 */
internal class FrameSample(
    val positionMs: Long,
    val window: FrameWindow,
    val pixels: ByteArray?,
)

/** What a look at decoded frames of a finished download found: the samples and what the container says it is called. */
internal class FrameSamples(
    val durationMs: Long,
    val samples: List<FrameSample>,
    /** The title the container states, where it states one. */
    val containerTitle: String? = null,
)

internal const val FrameSide = 32
internal const val FramePixelCount = FrameSide * FrameSide

/** Where in a video frames are taken, as fractions of its duration. */
internal val FrameSamplePositions: List<Pair<Double, FrameWindow>> = listOf(
    0.02 to FrameWindow.Start,
    0.06 to FrameWindow.Start,
    0.12 to FrameWindow.Start,
    0.50 to FrameWindow.Middle,
    0.88 to FrameWindow.End,
    0.94 to FrameWindow.End,
    0.98 to FrameWindow.End,
)

/** Where more frames are taken to confirm that a file is blank or one picture. Their window is [FrameWindow.Middle]. */
internal val ConfirmingFramePositions: List<Double> = listOf(0.20, 0.30, 0.40, 0.60, 0.70, 0.80)

/**
 * Takes frames of the file of a download at [FrameSamplePositions]. Null where the platform cannot
 * look, or the file could not be looked at reliably (a tool timed out), which is never a fault.
 */
internal expect fun sampleDownloadedFrames(localFileUri: String): FrameSamples?

/**
 * Takes the frames at [ConfirmingFramePositions] of a file of [durationMs]. Null where the platform
 * cannot look, or could not reliably.
 */
internal expect fun sampleConfirmingDownloadedFrames(localFileUri: String, durationMs: Long): FrameSamples?

/** Why decoded frames show that a file is not the video that was asked for. */
internal enum class DownloadedFramesProblem {
    /** Not one frame could be decoded. */
    Undecodable,

    /** The start decodes but the end does not: cut short, padded or filled with junk. */
    BrokenEnd,

    /** The end decodes but the start does not. */
    BrokenStart,

    /** Every frame that decoded is flat. */
    Blank,

    /** The same picture the whole time, such as a notice shown for the full running time. */
    StillPicture,
}

/** What a look at frames came to for a download. */
@Serializable
enum class DownloadFramesCheck {
    /** Not looked at: the platform cannot, ffmpeg is missing, a look timed out, or the download is older than the check. */
    NotChecked,

    /** Frames from the start and the end were decoded and looked like a video. */
    Passed,

    /** Passed, but the title in the container names another episode. That proves little, so the file is kept. */
    Suspect,

    /**
     * Compared with what the source claimed and with the running time of the title, the file is not
     * the video but a placeholder from the source (see [placeholderVerdict]). The file is kept and
     * marked, never removed by itself; the reason is in [DownloadItem.errorMessage].
     */
    NotTheRealVideo,
}

/*
 * Calibration, from a small sample of 29 real episodes. It is not a general bound, which is why
 * [confirmedFramesProblem] looks again, at more frames, before a file is rejected as blank or one
 * picture. The smallest largest-difference between two frames of one episode was 28, so a still
 * picture (below 2) is far from any of those. Real end-credit frames had a mean of 1 to 5 and a
 * variance of 40 to 250, so only a variance below 20 in every decoded frame, not just the last
 * ones, means a blank video. Pixel values and variance are on the scale 0 to 255.
 */

/** A frame whose pixel variance is below this is flat. */
internal const val FlatFrameVariance = 20.0

/** Frames whose largest mean absolute difference between two of them is below this are one picture. */
internal const val StillPictureDifference = 2.0

/** The fewest decoded frames that [DownloadedFramesProblem.Blank] and [DownloadedFramesProblem.StillPicture] judge. */
internal const val MinFramesToJudgeContent = 4

private val EpisodeMarker = Regex(
    "(?<![A-Za-z0-9])S(\\d{1,3})E(\\d{1,3})((?:-?E\\d{1,3}|-\\d{1,3}(?![0-9pPiIkK]))*)(?!\\d)",
    RegexOption.IGNORE_CASE,
)
private val EpisodeTail = Regex("(-)?E?(\\d{1,3})", RegexOption.IGNORE_CASE)

private fun FrameSamples.decodedPictures(): List<ByteArray> =
    samples.mapNotNull { sample -> sample.pixels?.takeIf { it.size == FramePixelCount } }

private fun FrameSamples.decodedIn(window: FrameWindow): Int =
    samples.count { sample -> sample.window == window && sample.pixels?.size == FramePixelCount }

/** Not a video, or a damaged one: no frame, or none at one end, decodes. */
internal fun FrameSamples.structuralProblem(): DownloadedFramesProblem? {
    val hasStart = samples.any { it.window == FrameWindow.Start }
    val hasEnd = samples.any { it.window == FrameWindow.End }
    return when {
        decodedPictures().isEmpty() -> DownloadedFramesProblem.Undecodable
        hasEnd && decodedIn(FrameWindow.Start) > 0 && decodedIn(FrameWindow.End) == 0 -> DownloadedFramesProblem.BrokenEnd
        hasStart && decodedIn(FrameWindow.End) > 0 && decodedIn(FrameWindow.Start) == 0 -> DownloadedFramesProblem.BrokenStart
        else -> null
    }
}

/** Blank or one picture, judged over every decoded frame of these samples. */
internal fun FrameSamples.contentProblem(): DownloadedFramesProblem? {
    val pictures = decodedPictures()
    return when {
        pictures.size < MinFramesToJudgeContent -> null
        pictures.all { pixelVariance(it) < FlatFrameVariance } -> DownloadedFramesProblem.Blank
        largestDifference(pictures) < StillPictureDifference -> DownloadedFramesProblem.StillPicture
        else -> null
    }
}

/** The problem with a download whose frames were taken, if any, judged on these samples alone. */
internal fun FrameSamples.problem(): DownloadedFramesProblem? = structuralProblem() ?: contentProblem()

/** [FrameSamples] with those of [more] added. */
internal operator fun FrameSamples.plus(more: FrameSamples) =
    FrameSamples(durationMs, samples + more.samples, containerTitle ?: more.containerTitle)

/** What a look at frames came to. [problem] is null for a file that is to be kept. */
internal class FrameVerdict(
    val problem: DownloadedFramesProblem?,
    /** False when the samples suggested a blank or still file and more frames, which would decide it, could not be taken. */
    val conclusive: Boolean = true,
)

/**
 * The verdict on [first]. A damaged file is rejected on them. A file that looks blank or one picture
 * is rejected only if it still does over all frames once [takeMore] has taken more of them; when
 * [takeMore] gives none, the file is kept, but the verdict is not conclusive.
 */
internal fun confirmedFramesProblem(first: FrameSamples, takeMore: () -> FrameSamples?): FrameVerdict {
    first.structuralProblem()?.let { return FrameVerdict(it) }
    if (first.contentProblem() == null) return FrameVerdict(null)
    val more = takeMore() ?: return FrameVerdict(null, conclusive = false)
    return FrameVerdict((first + more).problem())
}

/**
 * Whether the title of the container names another episode than the one asked for. Every SxxEyy
 * marker is read, with the episodes of ranges and lists such as S01E01E02 and S01E01-E03. A title
 * without a marker proves nothing. Only a hint: absolute numbering, packs and stale tags make it wrong.
 */
internal fun FrameSamples.titleNamesAnotherEpisode(expectedSeason: Int?, expectedEpisode: Int?): Boolean {
    return containerTitle.titleNamesAnotherEpisode(expectedSeason, expectedEpisode)
}

internal fun String?.titleNamesAnotherEpisode(expectedSeason: Int?, expectedEpisode: Int?): Boolean {
    val title = this
    if (title == null || expectedSeason == null || expectedEpisode == null) return false
    val markers = EpisodeMarker.findAll(title).map { match ->
        val episodes = mutableSetOf(match.groupValues[2].toInt())
        var previous = match.groupValues[2].toInt()
        EpisodeTail.findAll(match.groupValues[3]).forEach { tail ->
            val episode = tail.groupValues[2].toInt()
            if (tail.groupValues[1].isNotEmpty() && episode in previous..previous + 999) {
                (previous..episode).forEach { episodes += it }
            }
            episodes += episode
            previous = episode
        }
        match.groupValues[1].toInt() to episodes
    }.toList()
    return markers.isNotEmpty() && markers.none { (season, episodes) -> season == expectedSeason && expectedEpisode in episodes }
}

private fun pixelVariance(pixels: ByteArray): Double {
    val values = DoubleArray(pixels.size) { (pixels[it].toInt() and 0xff).toDouble() }
    val mean = values.average()
    return values.sumOf { (it - mean) * (it - mean) } / values.size
}

private fun largestDifference(pictures: List<ByteArray>): Double {
    var largest = 0.0
    for (first in pictures.indices) {
        for (second in first + 1 until pictures.size) {
            var total = 0L
            for (index in 0 until FramePixelCount) {
                total += kotlin.math.abs((pictures[first][index].toInt() and 0xff) - (pictures[second][index].toInt() and 0xff))
            }
            largest = maxOf(largest, total.toDouble() / FramePixelCount)
        }
    }
    return largest
}
