package com.nuvio.app.core.storage

/** What happened to local data when an account finished signing in. */
internal enum class AccountDataPrep {
    /** Nothing had an owner yet, so this account takes the data that is already here. */
    Adopted,

    /** The same account came back, so its data stays where it is. */
    Reused,

    /** A different account signed in. The previous account's data was set aside first. */
    Moved,
}

/**
 * Remembers which account owns the local data. A different account must be separated
 * before anything is uploaded. Data with no owner is kept and claimed by the first account.
 */
internal expect object AccountDataOwner {
    fun prepareForAccount(accountId: String): AccountDataPrep
}
