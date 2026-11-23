package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object SeasonDownloadDefaultsStorage {
    private const val payloadKey = "season_download_defaults_payload"
    private const val setupAnswerKey = "preference_setup_answer"
    private const val batchRunsKey = "batch_runs_payload"

    actual fun loadPayload(profileId: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(payloadKey, profileId))

    actual fun savePayload(profileId: Int, payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of(payloadKey, profileId))
    }

    actual fun loadSetupAnswer(profileId: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(setupAnswerKey, profileId))

    actual fun saveSetupAnswer(profileId: Int, answer: String) {
        NSUserDefaults.standardUserDefaults.setObject(answer, forKey = ProfileScopedKey.of(setupAnswerKey, profileId))
    }

    actual fun loadBatchRunsPayload(profileId: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(batchRunsKey, profileId))

    actual fun saveBatchRunsPayload(profileId: Int, payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of(batchRunsKey, profileId))
    }
    actual fun loadTripQueue(profileId: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of("trip_queue", profileId))

    actual fun saveTripQueue(profileId: Int, payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of("trip_queue", profileId))
    }
}
