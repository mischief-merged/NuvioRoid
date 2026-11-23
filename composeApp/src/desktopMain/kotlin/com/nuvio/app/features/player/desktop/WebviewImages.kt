package com.nuvio.app.features.player.desktop

import com.nuvio.app.core.memory.BoundedCache
import com.nuvio.app.core.network.ArtworkMaxBytes
import com.nuvio.app.core.network.guardedHttpClient
import com.nuvio.app.core.network.readBounded
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.Base64
import kotlin.math.roundToInt
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import okhttp3.Request

internal object WebviewImages {
    private const val SIZE = 112
    /** Longest side of a remote picture. Local control pictures stay small squares. */
    private const val ARTWORK_EDGE = 1024
    private val images = BoundedCache<String, String>(64)
    private val rejected = ConcurrentHashMap<String, Long>()
    private val client by lazy { guardedHttpClient().callTimeout(30, TimeUnit.SECONDS).build() }
    private val loader = WebviewImageLoader(images, ::rememberRejection, ::encode)

    private fun rememberRejection(url: String) {
        if (!url.startsWith("http", ignoreCase = true)) return
        if (rejected.size > 256) rejected.clear()
        rejected[url] = System.currentTimeMillis()
    }

    fun resolve(url: String, onReady: () -> Unit): String {
        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            val failedAt = rejected[url]
            if (failedAt != null && System.currentTimeMillis() - failedAt < 60_000) return ""
        }
        return loader.resolve(url, onReady)
    }

    internal fun encode(url: String): String? {
        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            val bytes = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.readBounded(ArtworkMaxBytes) ?: return null
            }
            val source = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { input ->
                readSubsampled(input, ARTWORK_EDGE, longestSide = true)
            } ?: return null
            return scaledToFit(source, ARTWORK_EDGE)
        }
        val file = File(URI(url))
        if (!file.isFile || file.length() > ArtworkMaxBytes) return null
        val source = ImageIO.createImageInputStream(file)?.use { input ->
            readSubsampled(input, SIZE, longestSide = false)
        } ?: return null
        return scaledThumbnail(source)
    }

    private fun readSubsampled(
        input: javax.imageio.stream.ImageInputStream,
        edge: Int,
        longestSide: Boolean,
    ): BufferedImage? {
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
        try {
            reader.input = input
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            if (width <= 0 || height <= 0 || width.toLong() * height > 40_000_000L) return null
            val basis = if (longestSide) maxOf(width, height) else minOf(width, height)
            val sample = (basis / edge).coerceAtLeast(1)
            val options = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
            return reader.read(0, options)
        } finally { reader.dispose() }
    }

    private fun scaledToFit(source: BufferedImage, maxEdge: Int): String {
        val longest = maxOf(source.width, source.height)
        val scale = if (longest <= maxEdge) 1.0 else maxEdge.toDouble() / longest
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = if (width == source.width && height == source.height) source
            else BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        return try {
            if (scaled !== source) {
                scaled.createGraphics().apply {
                    try {
                        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                        drawImage(source, 0, 0, width, height, null)
                    } finally { dispose() }
                }
            }
            val bytes = ByteArrayOutputStream().also { ImageIO.write(scaled, "png", it) }.toByteArray()
            "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
        } finally {
            source.flush()
            if (scaled !== source) scaled.flush()
        }
    }

    private fun scaledThumbnail(source: BufferedImage): String {
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

internal class WebviewImageLoader(
    private val images: BoundedCache<String, String>,
    private val onRejected: (String) -> Unit = {},
    private val encode: (String) -> String?,
) : AutoCloseable {
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val workers = ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, SynchronousQueue(),
        ThreadFactory { work -> Thread(work, "controls-image").apply { isDaemon = true } })
    val pendingCount: Int get() = loading.size
    val maximumWorkers: Int get() = workers.maximumPoolSize

    fun resolve(url: String, onReady: () -> Unit): String {
        val allowed = url.startsWith("file:", ignoreCase = true) ||
            url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
        if (!allowed) return ""
        images[url]?.let { return it }
        if (loading.add(url)) {
            try {
                workers.execute {
                    var image: String? = null
                    try {
                        image = runCatching { encode(url) }.getOrNull()
                        if (image != null) {
                            images[url] = image
                            runCatching(onReady)
                        } else {
                            onRejected(url)
                        }
                    } finally { loading.remove(url) }
                }
            } catch (_: RejectedExecutionException) { loading.remove(url) }
        }
        return ""
    }

    override fun close() { workers.shutdown() }
}
