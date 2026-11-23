package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object DownloadsStorage {
    private const val payloadKey = "downloads_payload"
    private const val unreadablePayloadKey = "downloads_unreadable_payload"

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of(payloadKey))
    }

    // Counter batching needs an atomic list update; keep ordinary saves on this platform.
    actual val batchesProgress: Boolean = false
    actual fun saveProgress(profileId: Int, items: List<DownloadItem>): Boolean = false

    actual fun loadPreviousPayload(): String? = null

    actual fun saveUnreadablePayload(payload: String, atEpochMs: Long) {
        NSUserDefaults.standardUserDefaults.setObject(
            payload,
            forKey = "${ProfileScopedKey.of(unreadablePayloadKey)}_$atEpochMs",
        )
    }

    // One copy of the app per device here: nothing to re-read and no lock across processes.
    actual val rereadsPayload: Boolean = false

    actual fun loadFreshPayload(): String? = null

    actual fun syncPayload(): Boolean = true

    actual fun <T> withListLock(block: () -> T): T = block()
}
