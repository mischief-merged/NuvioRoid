package com.nuvio.app.features.player.desktop

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import com.nuvio.app.core.network.ArtworkMaxBytes
import com.nuvio.app.core.network.guardedHttpClient
import com.nuvio.app.core.network.readBounded
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.concurrent.thread

internal object WebviewImages {
    private const val SIZE = 112
    private val images = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 16
    })
    private val workers = Executors.newFixedThreadPool(2) { Thread(it, "player-artwork").apply { isDaemon = true } }
    private val client = guardedHttpClient().callTimeout(30, TimeUnit.SECONDS).build()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val rejected = ConcurrentHashMap<String, Long>()

    fun resolve(url: String, onReady: () -> Unit): String {
        if (url.isBlank()) return ""
        if (!url.startsWith("file:", true) && !url.startsWith("http://", true) && !url.startsWith("https://", true)) return ""
        images[url]?.let { return it }
        if (System.currentTimeMillis() - (rejected[url] ?: 0L) < 60_000) return ""
        if (loading.add(url)) {
            workers.execute {
                val image = runCatching { encode(url) }.getOrNull()
                if (image != null) images[url] = image else {
                    if (rejected.size > 256) rejected.clear()
                    rejected[url] = System.currentTimeMillis()
                }
                loading.remove(url)
                if (image != null) onReady()
            }
        }
        return ""
    }

    private fun encode(url: String): String? {
        if (url.startsWith("http", true)) {
            val bytes = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.readBounded(ArtworkMaxBytes) ?: return null
            }
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
                try {
                    reader.input = input
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (width.toLong() * height > 40_000_000) return null
                    val params = reader.defaultReadParam
                    val sample = maxOf(1, maxOf(width, height) / 1024)
                    params.setSourceSubsampling(sample, sample, 0, 0)
                    val source = reader.read(0, params)
                    val output = ByteArrayOutputStream().also { ImageIO.write(source, "png", it) }.toByteArray()
                    return "data:image/png;base64," + Base64.getEncoder().encodeToString(output)
                } finally { reader.dispose() }
            }
        }
        val file = File(URI(url))
        if (file.length() > ArtworkMaxBytes) return null
        val source = ImageIO.read(file) ?: return null
        val side = minOf(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        val scaled = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB)
        scaled.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(source, 0, 0, SIZE, SIZE, left, top, left + side, top + side, null)
            dispose()
        }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(scaled, "png", it) }.toByteArray()
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
    }
}
