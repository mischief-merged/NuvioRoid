package com.nuvio.app.features.player

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

internal actual object SubtitleLocalFilePlatform {
    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun readLocalSubtitleText(uriOrPath: String, maxBytes: Int): String? =
        withContext(Dispatchers.IO) {
            val path = resolvePath(uriOrPath) ?: return@withContext null
            val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: return@withContext null
            val size = (attrs[NSFileSize] as? Number)?.toLong() ?: return@withContext null
            if (size <= 0L || size > maxBytes.toLong()) return@withContext null
            NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
        }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    actual suspend fun writeCachedSubtitleText(fileName: String, content: String): String? =
        withContext(Dispatchers.IO) {
            val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
                .firstOrNull() as? String ?: return@withContext null
            val dir = "$caches/subtitles"
            val fileManager = NSFileManager.defaultManager
            if (!fileManager.createDirectoryAtPath(dir, true, null, null)) return@withContext null
            val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(96)
                .ifBlank { "startup_subtitle.srt" }
            val path = "$dir/$safeName"
            val written = NSString.create(string = content).writeToFile(path, true, NSUTF8StringEncoding, null)
            if (written) NSURL.fileURLWithPath(path).absoluteString else null
        }

    actual fun resolveLocalFilePath(uriOrPath: String): String? =
        resolvePath(uriOrPath)?.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
}

private fun resolvePath(uriOrPath: String): String? {
    val trimmed = uriOrPath.trim()
    if (trimmed.isEmpty()) return null
    return if (trimmed.startsWith("file:", ignoreCase = true)) {
        NSURL(string = trimmed).path
    } else if (trimmed.startsWith("/")) {
        trimmed
    } else {
        null
    }
}
