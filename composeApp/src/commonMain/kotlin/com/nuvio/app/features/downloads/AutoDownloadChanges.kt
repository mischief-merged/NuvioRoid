package com.nuvio.app.features.downloads

import kotlinx.coroutines.CancellationException

/** What became of a request to turn automatic downloads on, or to change the rule, for a show. */
internal enum class AutoDownloadSaveResult {
    /** The show had no rule and has one now. */
    TurnedOn,

    /** The show had a rule and it was changed. */
    Changed,

    /** Nothing was saved: the storage failed, or another profile became the active one. */
    NotSaved,
}

/** Saves [rule] for [profileId]. Never throws: a failure is [AutoDownloadSaveResult.NotSaved]. */
internal fun saveAutoDownloadRule(store: AutoDownloadStore, profileId: Int, rule: AutoDownloadRule): AutoDownloadSaveResult =
    try {
        val save = {
            val existed = store.rule(profileId, rule.key) != null
            when {
                !store.upsert(profileId, rule) -> AutoDownloadSaveResult.NotSaved
                existed -> AutoDownloadSaveResult.Changed
                else -> AutoDownloadSaveResult.TurnedOn
            }
        }
        // The existence check also waits for the lock, so cancel before it when disabling removal.
        if (!rule.deleteAfterWatching) WatchedRemovalGuard.change(save) else save()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        AutoDownloadSaveResult.NotSaved
    }

/** Turns automatic downloads off for [ruleKey]. True when it is off now; false when that could not be saved. */
internal fun removeAutoDownloadRule(store: AutoDownloadStore, profileId: Int, ruleKey: String): Boolean =
    try {
        store.remove(profileId, ruleKey)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        false
    }

/** Saves the storage limit. True when it is saved; false when that could not be done. */
internal fun changeAutoDownloadStorageLimit(store: AutoDownloadStore, profileId: Int, limitGb: Int?): Boolean =
    try {
        store.setStorageLimit(profileId, limitGb)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        false
    }
