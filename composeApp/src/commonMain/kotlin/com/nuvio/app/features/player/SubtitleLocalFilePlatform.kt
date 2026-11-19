package com.nuvio.app.features.player

/**
 * Platform file operations for reading local subtitle files and storing validated stream
 * subtitle copies in the app's cache directory.
 */
internal expect object SubtitleLocalFilePlatform {
    suspend fun readLocalSubtitleText(uriOrPath: String, maxBytes: Int): String?

    suspend fun writeCachedSubtitleText(fileName: String, content: String): String?

    fun resolveLocalFilePath(uriOrPath: String): String?
}
