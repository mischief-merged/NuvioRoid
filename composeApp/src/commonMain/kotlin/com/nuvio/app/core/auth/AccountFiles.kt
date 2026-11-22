package com.nuvio.app.core.auth

/**
 * What happens to local files when an account session ends.
 * On a phone, a lost session still clears local account data.
 * On desktop, a lost session leaves those files, and sign-out copies them before the clear.
 */
internal expect object AccountFiles {
    fun onLostSession()

    /** Copies local files on desktop. Failure means sign-out must clear nothing. */
    fun beforeSignOut(): Result<Unit>
}
