package com.nuvio.app.features.downloads

internal expect object AutoDownloadStorage {
    fun loadPayload(profileId: Int): String?
    fun loadFreshPayload(profileId: Int): String?
    fun savePayload(profileId: Int, payload: String)

    /** Keeps a payload that could not be read, apart from the one [savePayload] writes. */
    fun saveUnreadablePayload(profileId: Int, payload: String)

    /** Flushes the saved store to disk where supported. */
    fun syncPayload(): Boolean
}
