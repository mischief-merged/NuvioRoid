package com.nuvio.app.core.storage

internal actual object AccountDataOwner {
    actual fun prepareForAccount(accountId: String): AccountDataPrep =
        prepareAndroidAccountOwner(accountId)
}
