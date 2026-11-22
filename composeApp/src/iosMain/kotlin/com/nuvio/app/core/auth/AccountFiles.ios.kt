package com.nuvio.app.core.auth

import com.nuvio.app.core.storage.LocalAccountDataCleaner

internal actual object AccountFiles {
    actual fun onLostSession() {
        LocalAccountDataCleaner.wipe()
    }

    actual fun beforeSignOut(): Result<Unit> = Result.success(Unit)
}
