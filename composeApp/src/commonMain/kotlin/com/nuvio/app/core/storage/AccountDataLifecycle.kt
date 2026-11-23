package com.nuvio.app.core.storage

import com.nuvio.app.core.sync.SyncManager
import kotlinx.atomicfu.atomic

internal sealed interface AccountOwnerCandidate {
    val userId: String

    data class Remote(
        val serverUrl: String,
        override val userId: String,
    ) : AccountOwnerCandidate
}

internal sealed interface RemoteSignInDecision {
    data object Approved : RemoteSignInDecision
    data object AskAdoption : RemoteSignInDecision
    data class Mismatch(val message: String) : RemoteSignInDecision
    data class Refused(val message: String) : RemoteSignInDecision
}

internal enum class LifecycleQuitOutcome {
    Completed,
    Cancelled,
}

internal enum class AccountDeleteLifecycleOutcome {
    WipedInPlace,
    ExitingProcess,
}

internal interface AccountDataLifecycleHandler {
    fun isTransitionQuitPending(): Boolean
    fun evaluateRemoteSignIn(candidate: AccountOwnerCandidate.Remote): RemoteSignInDecision
    fun confirmRemoteAdoption(candidate: AccountOwnerCandidate.Remote): RemoteSignInDecision
    suspend fun onSignOut()
    suspend fun onLostSession()
    suspend fun onPrepareForServerSwitch()
    suspend fun switchDesktopServerAndQuit(saveConfiguration: () -> Boolean): Result<LifecycleQuitOutcome>
    suspend fun precheckBeforeAccountDelete(): Result<Unit>
    suspend fun onAccountDeleted(): Result<AccountDeleteLifecycleOutcome>
    suspend fun startFresh(): Result<LifecycleQuitOutcome>
}

internal object DefaultMobileAccountDataLifecycleHandler : AccountDataLifecycleHandler {
    override fun isTransitionQuitPending(): Boolean = false

    override fun evaluateRemoteSignIn(candidate: AccountOwnerCandidate.Remote): RemoteSignInDecision =
        RemoteSignInDecision.Approved

    override fun confirmRemoteAdoption(candidate: AccountOwnerCandidate.Remote): RemoteSignInDecision =
        RemoteSignInDecision.Approved

    override suspend fun onSignOut() {
        LocalAccountDataCleaner.wipe()
    }

    override suspend fun onLostSession() {
        LocalAccountDataCleaner.wipe()
    }

    override suspend fun onPrepareForServerSwitch() {
        SyncManager.cancelAccountSyncAndWait()
    }

    override suspend fun switchDesktopServerAndQuit(saveConfiguration: () -> Boolean): Result<LifecycleQuitOutcome> =
        Result.failure(UnsupportedOperationException("Desktop server-switch quit is only supported on desktop"))

    override suspend fun precheckBeforeAccountDelete(): Result<Unit> = Result.success(Unit)

    override suspend fun onAccountDeleted(): Result<AccountDeleteLifecycleOutcome> = runCatching {
        LocalAccountDataCleaner.wipe()
        AccountDeleteLifecycleOutcome.WipedInPlace
    }

    override suspend fun startFresh(): Result<LifecycleQuitOutcome> =
        Result.failure(UnsupportedOperationException("Start fresh is only supported on desktop"))
}

internal object AccountDataLifecycle {
    private val installed = atomic<AccountDataLifecycleHandler>(DefaultMobileAccountDataLifecycleHandler)

    fun install(handler: AccountDataLifecycleHandler) {
        installed.value = handler
    }

    fun resetToDefault() {
        installed.value = DefaultMobileAccountDataLifecycleHandler
    }

    fun isTransitionQuitPending(): Boolean =
        installed.value.isTransitionQuitPending()

    fun evaluateRemoteSignIn(candidate: AccountOwnerCandidate.Remote): RemoteSignInDecision =
        installed.value.evaluateRemoteSignIn(candidate)

    fun confirmRemoteAdoption(candidate: AccountOwnerCandidate.Remote): RemoteSignInDecision =
        installed.value.confirmRemoteAdoption(candidate)

    suspend fun onSignOut() {
        installed.value.onSignOut()
    }

    suspend fun onLostSession() {
        installed.value.onLostSession()
    }

    suspend fun onPrepareForServerSwitch() {
        installed.value.onPrepareForServerSwitch()
    }

    suspend fun switchDesktopServerAndQuit(saveConfiguration: () -> Boolean): Result<LifecycleQuitOutcome> =
        installed.value.switchDesktopServerAndQuit(saveConfiguration)

    suspend fun precheckBeforeAccountDelete(): Result<Unit> =
        installed.value.precheckBeforeAccountDelete()

    suspend fun onAccountDeleted(): Result<AccountDeleteLifecycleOutcome> =
        installed.value.onAccountDeleted()

    suspend fun startFresh(): Result<LifecycleQuitOutcome> =
        installed.value.startFresh()
}
