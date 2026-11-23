package com.nuvio.app.features.player

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.URLDecoder

private const val MAX_CACHED_STARTUP_SUBTITLES = 24
private const val STARTUP_CACHE_PREFIX = "startup_"

internal actual object SubtitleLocalFilePlatform {
    actual suspend fun readLocalSubtitleText(uriOrPath: String, maxBytes: Int): String? =
        withContext(Dispatchers.IO) {
            val trimmed = uriOrPath.trim()
            if (trimmed.startsWith("content:", ignoreCase = true)) {
                val context = SubtitleFileCache.contextOrNull() ?: return@withContext null
                return@withContext readContentUriBounded(context, trimmed, maxBytes)
            }
            val file = uriOrPathToFile(trimmed) ?: return@withContext null
            if (!file.isFile || !file.canRead()) return@withContext null
            val length = file.length()
            if (length <= 0L || length > maxBytes.toLong()) return@withContext null
            runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
        }

    actual suspend fun writeCachedSubtitleText(fileName: String, content: String): String? =
        withContext(Dispatchers.IO) {
            val baseDir = SubtitleFileCache.startupCacheDir()
                ?: File(System.getProperty("java.io.tmpdir"), "nuvio-subtitles").also { it.mkdirs() }
            val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(96)
                .ifBlank { "${STARTUP_CACHE_PREFIX}subtitle.srt" }
            val target = File(baseDir, safeName)
            val tmp = File(baseDir, "$safeName.tmp")
            val written = runCatching {
                tmp.writeText(content, Charsets.UTF_8)
                if (target.exists()) target.delete()
                tmp.renameTo(target)
            }.getOrDefault(false)
            if (!written) {
                tmp.delete()
                return@withContext null
            }
            val startupFiles = baseDir.listFiles { file ->
                file.isFile && file != target && file.name.startsWith(STARTUP_CACHE_PREFIX)
            }.orEmpty()
            if (startupFiles.size > MAX_CACHED_STARTUP_SUBTITLES) {
                startupFiles.sortedBy { it.lastModified() }
                    .take(startupFiles.size - MAX_CACHED_STARTUP_SUBTITLES)
                    .forEach { it.delete() }
            }
            target.toURI().toString()
        }

    actual fun resolveLocalFilePath(uriOrPath: String): String? =
        uriOrPathToFile(uriOrPath)?.takeIf { it.isFile && it.length() > 0L }?.absolutePath
}

private fun uriOrPathToFile(uriOrPath: String): File? {
    val trimmed = uriOrPath.trim()
    if (trimmed.isEmpty()) return null
    if (!trimmed.startsWith("file:", ignoreCase = true)) {
        return if (trimmed.startsWith("/")) File(trimmed) else null
    }
    runCatching { File(URI(trimmed)) }.getOrNull()?.let { return it }
    val stripped = trimmed.replaceFirst(Regex("^file:/{1,3}", RegexOption.IGNORE_CASE), "/")
    val decoded = runCatching { URLDecoder.decode(stripped, "UTF-8") }.getOrDefault(stripped)
    return File(decoded)
}

private fun readContentUriBounded(context: Context, uriString: String, maxBytes: Int): String? =
    runCatching {
        context.contentResolver.openInputStream(Uri.parse(uriString))?.use { stream ->
            val buffer = ByteArray(minOf(maxBytes + 1, 64 * 1024))
            val out = java.io.ByteArrayOutputStream()
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                total += read
                if (total > maxBytes) return@runCatching null
                out.write(buffer, 0, read)
            }
            if (total == 0) null else out.toString(Charsets.UTF_8.name())
        }
    }.getOrNull()
