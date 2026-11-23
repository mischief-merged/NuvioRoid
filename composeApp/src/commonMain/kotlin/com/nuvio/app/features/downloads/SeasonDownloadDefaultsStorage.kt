package com.nuvio.app.features.downloads

internal expect object SeasonDownloadDefaultsStorage {
    fun loadPayload(profileId: Int): String?
    fun savePayload(profileId: Int, payload: String)

    /** The answer to the first-start preferences dialog, kept apart from the defaults themselves. */
    fun loadSetupAnswer(profileId: Int): String?
    fun saveSetupAnswer(profileId: Int, answer: String)

    /** The last season, movie-set and movie runs, shown on the Downloads screen until dismissed. */
    fun loadBatchRunsPayload(profileId: Int): String?
    fun saveBatchRunsPayload(profileId: Int, payload: String)
    fun loadTripQueue(profileId: Int): String?
    fun saveTripQueue(profileId: Int, payload: String)
}
