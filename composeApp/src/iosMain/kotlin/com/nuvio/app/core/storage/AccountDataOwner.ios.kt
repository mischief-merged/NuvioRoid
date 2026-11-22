package com.nuvio.app.core.storage

import platform.Foundation.NSUserDefaults

internal actual object AccountDataOwner {
    private var memoryOwner: String? = null

    actual fun prepareForAccount(accountId: String): AccountDataPrep {
        val defaults = NSUserDefaults.standardUserDefaults
        val recorded = defaults.stringForKey(OWNER_KEY)?.takeIf { it.isNotBlank() } ?: memoryOwner
        val result = when {
            recorded.isNullOrBlank() -> AccountDataPrep.Adopted
            recorded == accountId -> AccountDataPrep.Reused
            else -> AccountDataPrep.Moved
        }
        memoryOwner = accountId
        defaults.setObject(accountId, forKey = OWNER_KEY)
        return result
    }

    private companion object {
        const val OWNER_KEY = "account_owner_id"
    }
}
