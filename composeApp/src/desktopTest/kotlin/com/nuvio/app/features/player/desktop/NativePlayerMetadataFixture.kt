package com.nuvio.app.features.player.desktop

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reuses the small video/subtitle fixture and adds real PCM samples and two chapters. */
internal fun writePlayerMetadataFixture(target: File) {
    val app = listOf(File("."), File("composeApp")).first { it.resolve("src/androidHostTest/resources/autosync/video_srt.mkv").isFile }
    val source = app.resolve("src/androidHostTest/resources/autosync/video_srt.mkv").readBytes()
    data class Element(val id: Long, val data: ByteArray, val encoded: ByteArray)
    fun elements(bytes: ByteArray): List<Element> {
        var position = 0
        fun number(id: Boolean): Long {
            val start = position
            val first = bytes[position++].toInt() and 255
            var width = 1
            while (first and (0x80 ushr (width - 1)) == 0) width++
            var value = (if (id) first else first and (255 ushr width)).toLong()
            repeat(width - 1) { value = (value shl 8) or (bytes[position++].toLong() and 255) }
            check(position - start == width)
            return value
        }
        return buildList {
            while (position < bytes.size) {
                val start = position
                val id = number(true)
                val length = number(false).toInt()
                val end = position + length
                add(Element(id, bytes.copyOfRange(position, end), bytes.copyOfRange(start, end)))
                position = end
            }
        }
    }
    fun join(parts: List<ByteArray>): ByteArray = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
    fun element(id: Long, data: ByteArray): ByteArray {
        val identity = id.toString(16).padStart(if (id.toString(16).length % 2 == 0) id.toString(16).length else id.toString(16).length + 1, '0')
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        var width = 1
        while (data.size.toLong() >= (1L shl (7 * width)) - 1) width++
        val size = data.size.toLong() or (1L shl (7 * width))
        val encodedSize = ByteArray(width) { (size ushr ((width - it - 1) * 8)).toByte() }
        return identity + encodedSize + data
    }
    fun uint(id: Long, value: Long) = element(id, ByteBuffer.allocate(8).putLong(value).array())
    fun text(id: Long, value: String) = element(id, value.toByteArray())
    val top = elements(source)
    val segment = elements(top.first { it.id == 0x18538067L }.data)
    val originalTracks = segment.first { it.id == 0x1654ae6bL }
    val audio = element(0xAEL, join(listOf(uint(0xD7, 3), uint(0x73C5, 3), uint(0x83, 2), text(0x86, "A_PCM/INT/LIT"),
        element(0xE1, join(listOf(element(0xB5, ByteBuffer.allocate(8).putDouble(44100.0).array()), uint(0x9F, 1), uint(0x6264, 16)))))))
    val chapters = element(0x1043A770, element(0x45B9, join((0..1).map { index ->
        element(0xB6, join(listOf(uint(0x73C4, index + 1L), uint(0x91, index * 2_000_000_000L), uint(0x92, (index + 1L) * 2_000_000_000L),
            element(0x80, text(0x85, if (index == 0) "First" else "Second") + text(0x437C, "eng")))))
    })))
    val wave = app.resolve("src/desktopMain/resources/controller/audio/back.wav").readBytes()
    val dataTag = wave.toString(Charsets.ISO_8859_1).indexOf("data")
    check(dataTag >= 0)
    val sampleBytes = ByteBuffer.wrap(wave, dataTag + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
    val pcm = wave.copyOfRange(dataTag + 8, dataTag + 8 + sampleBytes)
    val audioCluster = element(0x1F43B675, uint(0xE7, 0) + element(0xA3, byteArrayOf(0x83.toByte(), 0, 0, 0x80.toByte()) + pcm))
    val content = join(listOf(segment.first { it.id == 0x1549A966L }.encoded,
        element(0x1654AE6B, originalTracks.data + audio), chapters, audioCluster) +
        segment.filter { it.id == 0x1F43B675L }.map { it.encoded })
    target.writeBytes(top.first { it.id == 0x1A45DFA3L }.encoded + element(0x18538067, content))
}
