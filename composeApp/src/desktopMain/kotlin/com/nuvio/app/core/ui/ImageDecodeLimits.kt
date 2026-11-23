package com.nuvio.app.core.ui

internal const val MaxEncodedImageBytes = 16L * 1024 * 1024
internal const val MaxDecodedSourcePixels = 8_294_400L
internal const val MaxImageFrames = 1000

internal fun acceptsImageDimensions(width: Int, height: Int, frames: Int): Boolean =
    width > 0 && height > 0 && width.toLong() * height <= MaxDecodedSourcePixels && frames in 1..MaxImageFrames

internal fun okio.BufferedSource.readBoundedImageBytes(maximum: Long = MaxEncodedImageBytes): ByteArray {
    require(maximum > 0 && maximum < Long.MAX_VALUE)
    val buffer = okio.Buffer()
    while (buffer.size <= maximum) {
        if (read(buffer, minOf(8192L, maximum + 1 - buffer.size)) < 0) break
    }
    check(buffer.size <= maximum)
    return buffer.readByteArray()
}

internal fun svgRasterScale(width: Float, height: Float): Float {
    require(width.isFinite() && height.isFinite() && width > 0 && height > 0)
    return minOf(800f / width, 2048f / height, 4f)
}
