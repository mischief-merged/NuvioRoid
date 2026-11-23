package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object DownloadsStorage {
    private val store = DesktopStorage.store("nuvio_downloads")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("downloads"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("downloads"), payload)
    }

    actual val batchesProgress: Boolean = true

    actual fun saveProgress(profileId: Int, items: List<DownloadItem>): Boolean =
        saveDownloadProgress(store, profileId, items)

    actual fun loadPreviousPayload(): String? =
        store.getPreviousString(ProfileScopedKey.of("downloads"))

    actual fun saveUnreadablePayload(payload: String, atEpochMs: Long) {
        store.putString("${ProfileScopedKey.of("downloads_unreadable")}_$atEpochMs", payload)
    }

    // Another copy of the app may have saved since this one read the list: it is read again under the lock.
    actual val rereadsPayload: Boolean = true

    actual fun loadFreshPayload(): String? = store.getFreshString(ProfileScopedKey.of("downloads"))

    actual fun <T> withListLock(block: () -> T): T = store.exclusive(block)

    actual fun syncPayload(): Boolean = store.sync()
}

/** Shares the write's reload instead of re-reading the whole list before saving it. */
internal fun saveDownloadProgress(store: DesktopStorage.Store, profileId: Int, items: List<DownloadItem>): Boolean {
    var readable = true
    store.updateString(ProfileScopedKey.of("downloads", profileId)) { payload ->
        payload?.let { DownloadsCodec.withProgress(it, items).also { next -> readable = next != null } ?: payload }
    }
    return readable
}
