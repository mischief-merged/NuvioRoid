package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.VideoHashChunkBytes
import com.nuvio.app.features.player.littleEndianLongSum
import com.nuvio.app.features.player.videoHashOf
import java.io.File
import java.io.RandomAccessFile
import java.net.URI

internal actual fun inspectDownloadedFile(localFileUri: String): DownloadedFileFacts? {
    val file = runCatching { if (localFileUri.startsWith("file:")) File(URI(localFileUri)) else File(localFileUri) }.getOrNull()
        ?: return DownloadedFileFacts(exists = false)
    if (!file.isFile) return DownloadedFileFacts(exists = false)
    return runCatching {
        RandomAccessFile(file, "r").use { input ->
            val size = input.length()
            fun read(offset: Long, length: Int): ByteArray? {
                if (offset < 0 || length <= 0 || offset >= size) return null
                val bytes = ByteArray(minOf(length.toLong(), size - offset).toInt())
                input.seek(offset)
                input.readFully(bytes)
                return bytes
            }
            val info = matroskaInfo(size, ::read)
            DownloadedFileFacts(
                exists = true,
                sizeBytes = size,
                isVideoContainer = startsLikeVideoContainer(read(0, VideoContainerHeaderBytes) ?: ByteArray(0)),
                durationMs = info?.durationMs ?: mp4DurationMs(size, ::read),
                containerTitle = info?.title,
                audioLanguages = runCatching { audioTrackLanguages(size, ::read) }.getOrNull(),
                videoHash = if (size >= 2L * VideoHashChunkBytes) {
                    val head = read(0, VideoHashChunkBytes)
                    val tail = read(size - VideoHashChunkBytes, VideoHashChunkBytes)
                    if (head != null && tail != null) videoHashOf(size, littleEndianLongSum(head), littleEndianLongSum(tail)) else null
                } else {
                    null
                },
            )
        }
    // A file that is there but cannot be read is not a download that can be vouched for.
    }.getOrElse { DownloadedFileFacts(exists = true, sizeBytes = file.length(), isVideoContainer = false) }
}
