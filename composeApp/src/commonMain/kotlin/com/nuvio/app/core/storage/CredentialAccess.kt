package com.nuvio.app.core.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class SecureStorageUnavailable : IllegalStateException("Secure storage is unavailable") {
    init { credentialAccess.block() }
}

internal class CredentialAccess {
    private val blockedState = MutableStateFlow(false)
    val blocked = blockedState.asStateFlow()
    suspend fun run(operation: suspend () -> Unit): Boolean {
        return try {
            operation()
            true
        } catch (_: SecureStorageUnavailable) {
            blockedState.value = true
            false
        }
    }
    fun block() { blockedState.value = true }
    fun retry() { blockedState.value = false }
}

internal val credentialAccess = CredentialAccess()
