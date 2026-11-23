package com.nuvio.app.features.player

import com.nuvio.app.core.storage.DesktopStorage
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
            val file = uriOrPathToFile(uriOrPath) ?: return@withContext null
            if (!file.isFile || !file.canRead()) return@withContext null
            val length = file.length()
            if (length <= 0L || length > maxBytes.toLong()) return@withContext null
            runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
        }

    actual suspend fun writeCachedSubtitleText(fileName: String, content: String): String? =
        withContext(Dispatchers.IO) {
            val dir = DesktopStorage.cacheDir.resolve("subtitles").toFile().also { it.mkdirs() }
            val safeName = sanitizeCacheFileName(fileName)
            val target = File(dir, safeName)
            val tmp = File(dir, "$safeName.tmp")
            val written = runCatching {
                tmp.writeText(content, Charsets.UTF_8)
                if (target.exists()) target.delete()
                tmp.renameTo(target)
            }.getOrDefault(false)
            if (!written) {
                tmp.delete()
                return@withContext null
            }
            pruneOldStartupCacheFiles(dir, keep = target)
            target.toURI().toString()
        }

    actual fun resolveLocalFilePath(uriOrPath: String): String? =
        uriOrPathToFile(uriOrPath)?.takeIf { it.isFile && it.length() > 0L }?.absolutePath
}

private fun uriOrPathToFile(uriOrPath: String): File? {
    val trimmed = uriOrPath.trim()
    if (trimmed.isEmpty()) return null
    if (!trimmed.startsWith("file:", ignoreCase = true)) {
        return File(trimmed)
    }
    runCatching { File(URI(trimmed)) }.getOrNull()?.let { return it }
    val stripped = trimmed.replaceFirst(Regex("^file:/{1,3}", RegexOption.IGNORE_CASE), "/")
    val decoded = runCatching { URLDecoder.decode(stripped, "UTF-8") }.getOrDefault(stripped)
    return File(decoded)
}

private fun sanitizeCacheFileName(raw: String): String {
    val cleaned = raw.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(96)
    return cleaned.ifBlank { "${STARTUP_CACHE_PREFIX}subtitle.srt" }
}

private fun pruneOldStartupCacheFiles(dir: File, keep: File) {
    val startupFiles = dir.listFiles { file ->
        file.isFile && file != keep && file.name.startsWith(STARTUP_CACHE_PREFIX)
    } ?: return
    if (startupFiles.size <= MAX_CACHED_STARTUP_SUBTITLES) return
    startupFiles.sortedBy { it.lastModified() }
        .take(startupFiles.size - MAX_CACHED_STARTUP_SUBTITLES)
        .forEach { it.delete() }
}
