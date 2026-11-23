package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object SeasonDownloadDefaultsStorage {
    private val store = DesktopStorage.store("nuvio_season_download_defaults")

    actual fun loadPayload(profileId: Int): String? =
        store.getString(ProfileScopedKey.of("season_download_defaults", profileId))

    actual fun savePayload(profileId: Int, payload: String) {
        store.putString(ProfileScopedKey.of("season_download_defaults", profileId), payload)
    }

    actual fun loadSetupAnswer(profileId: Int): String? =
        store.getString(ProfileScopedKey.of("preference_setup_answer", profileId))

    actual fun saveSetupAnswer(profileId: Int, answer: String) {
        store.putString(ProfileScopedKey.of("preference_setup_answer", profileId), answer)
    }

    actual fun loadBatchRunsPayload(profileId: Int): String? =
        store.getString(ProfileScopedKey.of("batch_runs", profileId))

    actual fun saveBatchRunsPayload(profileId: Int, payload: String) {
        store.putString(ProfileScopedKey.of("batch_runs", profileId), payload)
    }
    actual fun loadTripQueue(profileId: Int): String? =
        store.getString(ProfileScopedKey.of("trip_queue", profileId))

    actual fun saveTripQueue(profileId: Int, payload: String) {
        store.putString(ProfileScopedKey.of("trip_queue", profileId), payload)
        check(store.sync())
    }
}
