package com.nuvio.app.core.storage

import com.nuvio.app.core.sync.SyncManager
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.mdblist.MdbListTracker
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.simkl.SimklAuthRepository
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentCache

/**
 * Explicit sign-out and account deletion. Clears the account session and linked secrets only.
 * Library, downloads, watch progress, rules, profiles, and player settings stay on disk.
 * A lost session does not call this.
 */
internal object LocalAccountDataCleaner {
    fun wipe() {
        prepareAccountWipe(SyncManager::cancelAccountSync, ::wipePlatformStorage) {
            dropSecretMemory()
        }
    }

    internal fun dropSecretMemory() {
        TraktAuthRepository.clearLocalState()
        SimklAuthRepository.clearLocalState()
        AddonRepository.clearLocalState()
        MdbListTracker.clearAuthSecrets()
        ServerRepository.dropCredentialMemory()
    }

    internal fun wipePlatformStorage(wipeStorage: () -> Unit = PlatformLocalAccountDataCleaner::wipe) {
        try {
            wipeStorage()
        } finally {
            ContinueWatchingEnrichmentCache.clearLocalState()
        }
    }
}

internal expect object PlatformLocalAccountDataCleaner {
    fun wipe()
}

internal fun prepareAccountWipe(cancelSync: () -> Unit, wipeStorage: () -> Unit, prepareRuntime: () -> Unit) {
    cancelSync()
    wipeStorage()
    prepareRuntime()
}
