package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal actual object AutoDownloadStorage {
    private val store = DesktopStorage.store("nuvio_auto_downloads")

    actual fun loadPayload(profileId: Int): String? =
        store.getString(ProfileScopedKey.of("auto_downloads", profileId))

    actual fun loadFreshPayload(profileId: Int): String? =
        store.getFreshString(ProfileScopedKey.of("auto_downloads", profileId))

    actual fun savePayload(profileId: Int, payload: String) {
        try {
            store.putString(ProfileScopedKey.of("auto_downloads", profileId), payload)
        } catch (error: Throwable) {
            store.clearInMemory()
            throw error
        }
    }

    actual fun saveUnreadablePayload(profileId: Int, payload: String) {
        store.putString(ProfileScopedKey.of("auto_downloads_unreadable", profileId), payload)
    }

    actual fun syncPayload(): Boolean = store.sync()

    fun isArchivePathReferencedByUndo(path: Path): Boolean {
        val normalizedTarget = runCatching { path.toAbsolutePath().normalize() }.getOrNull() ?: return true
        val entries = runCatching { store.freshEntries() }.getOrNull() ?: return true
        if (entries.any { (key, value) -> key.startsWith("auto_downloads_unreadable_") && value.isNotBlank() }) {
            return true
        }
        return entries.any { (key, raw) ->
            if (!key.startsWith("auto_downloads_") || key.startsWith("auto_downloads_unreadable_")) {
                return@any false
            }
            autoDownloadPayloadReferencesUndoUri(raw) { uri ->
                val candidate = runCatching { Paths.get(URI(uri)).toAbsolutePath().normalize() }.getOrNull()
                    ?: return@autoDownloadPayloadReferencesUndoUri false
                candidate == normalizedTarget ||
                    runCatching { Files.isSameFile(candidate, normalizedTarget) }.getOrDefault(false)
            }
        }
    }
}
