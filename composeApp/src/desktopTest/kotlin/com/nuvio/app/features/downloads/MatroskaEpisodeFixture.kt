package com.nuvio.app.features.downloads

import java.nio.ByteBuffer

/** A segment with length and title in its info header, followed by media of unknown length. */
internal fun matroskaEpisode(title: String = "Show S01E01", scale: Long = 1_000_000L, duration: Double = 2_700_000.0): ByteArray {
    fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()
    fun element(id: ByteArray, content: ByteArray) = id + bytes(0x80 or content.size) + content
    val info = element(bytes(0x2a, 0xd7, 0xb1), ByteBuffer.allocate(8).putLong(scale).array()) +
        element(bytes(0x44, 0x89), ByteBuffer.allocate(8).putDouble(duration).array()) +
        element(bytes(0x7b, 0xa9), title.toByteArray())
    return bytes(0x1a, 0x45, 0xdf, 0xa3, 0x80, 0x18, 0x53, 0x80, 0x67, 0xff) +
        element(bytes(0x15, 0x49, 0xa9, 0x66), info) + bytes(0x1f, 0x43, 0xb6, 0x75, 0xff)
}
