package com.nuvio.app.features.downloads

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object SeasonDownloadDefaultsStorage {
    private const val preferencesName = "nuvio_season_download_defaults"
    private const val payloadKey = "season_download_defaults_payload"
    private const val setupAnswerKey = "preference_setup_answer"
    private const val batchRunsKey = "batch_runs_payload"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPayload(profileId: Int): String? =
        preferences?.getString(ProfileScopedKey.of(payloadKey, profileId), null)

    actual fun savePayload(profileId: Int, payload: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(payloadKey, profileId), payload)
            ?.apply()
    }

    actual fun loadSetupAnswer(profileId: Int): String? =
        preferences?.getString(ProfileScopedKey.of(setupAnswerKey, profileId), null)

    actual fun saveSetupAnswer(profileId: Int, answer: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(setupAnswerKey, profileId), answer)
            ?.apply()
    }

    actual fun loadBatchRunsPayload(profileId: Int): String? =
        preferences?.getString(ProfileScopedKey.of(batchRunsKey, profileId), null)

    actual fun saveBatchRunsPayload(profileId: Int, payload: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(batchRunsKey, profileId), payload)
            ?.apply()
    }
    actual fun loadTripQueue(profileId: Int): String? =
        preferences?.getString(ProfileScopedKey.of("trip_queue", profileId), null)

    actual fun saveTripQueue(profileId: Int, payload: String) {
        check(preferences?.edit()?.putString(ProfileScopedKey.of("trip_queue", profileId), payload)?.commit() == true)
    }
}
