package com.nuvio.app.features.player.desktop

import com.nuvio.app.core.memory.BoundedCache
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

internal object WebviewImages {
    private const val SIZE = 112
    private val images = BoundedCache<String, String>(64)
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val workers = ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, SynchronousQueue(),
        ThreadFactory { work -> Thread(work, "controls-image").apply { isDaemon = true } })

    fun resolve(url: String, onReady: () -> Unit): String {
        if (!url.startsWith("file:", ignoreCase = true)) return url
        images[url]?.let { return it }
        if (loading.add(url)) {
            try {
                workers.execute {
                    try {
                        val image = runCatching { encode(url) }.getOrNull()
                        if (image != null) {
                            images[url] = image
                            runCatching(onReady)
                        }
                    } finally { loading.remove(url) }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) { loading.remove(url) }
        }
        return ""
    }

    internal fun encode(url: String): String? {
        val source = ImageIO.createImageInputStream(File(URI(url)))?.use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.input = input
                val sample = (minOf(reader.getWidth(0), reader.getHeight(0)) / SIZE).coerceAtLeast(1)
                val options = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
                reader.read(0, options)
            } finally { reader.dispose() }
        } ?: return null
        val scaled = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB)
        return try {
            val side = minOf(source.width, source.height)
            val left = (source.width - side) / 2
            val top = (source.height - side) / 2
            scaled.createGraphics().apply {
                try {
                    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    drawImage(source, 0, 0, SIZE, SIZE, left, top, left + side, top + side, null)
                } finally { dispose() }
            }
            val bytes = ByteArrayOutputStream().also { ImageIO.write(scaled, "png", it) }.toByteArray()
            "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
        } finally { source.flush(); scaled.flush() }
    }
}
