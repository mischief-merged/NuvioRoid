package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object DownloadsStorage {
    private val store = DesktopStorage.store("nuvio_downloads")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("downloads"))

    actual fun loadPayload(profileId: Int): String? =
        store.getString(ProfileScopedKey.of("downloads", profileId))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("downloads"), payload)
    }

    actual fun savePayload(profileId: Int, payload: String) {
        store.putString(ProfileScopedKey.of("downloads", profileId), payload)
    }

    actual fun loadPreviousPayload(): String? =
        store.getPreviousString(ProfileScopedKey.of("downloads"))

    actual fun loadPreviousPayload(profileId: Int): String? =
        store.getPreviousString(ProfileScopedKey.of("downloads", profileId))

    actual fun saveUnreadablePayload(payload: String, atEpochMs: Long) {
        store.putString("${ProfileScopedKey.of("downloads_unreadable")}_$atEpochMs", payload)
    }

    actual fun saveUnreadablePayload(profileId: Int, payload: String, atEpochMs: Long) {
        store.putString("${ProfileScopedKey.of("downloads_unreadable", profileId)}_$atEpochMs", payload)
    }

    // Another copy of the app may have saved since this one read the list: it is read again under the lock.
    actual val rereadsPayload: Boolean = true

    actual fun loadFreshPayload(): String? = store.getFreshString(ProfileScopedKey.of("downloads"))

    actual fun loadFreshPayload(profileId: Int): String? = store.getFreshString(ProfileScopedKey.of("downloads", profileId))

    actual fun <T> withListLock(block: () -> T): T = store.exclusive(block)

    actual fun syncPayload(): Boolean = store.sync()
}
