package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.network.ServerConfiguration
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.network.extractJwtSubClaim
import com.nuvio.app.core.storage.AccountDataLifecycle
import com.nuvio.app.core.storage.AccountDeleteLifecycleOutcome
import com.nuvio.app.core.storage.AccountOwnerCandidate
import com.nuvio.app.core.storage.LifecycleQuitOutcome
import com.nuvio.app.core.storage.RemoteSignInDecision
import com.nuvio.app.features.auth.ServerSwitchFailure
import com.nuvio.app.features.auth.ServerSwitchResult
import com.nuvio.app.features.auth.ServerSwitchStageException
import com.nuvio.app.features.party.PartyCompanion
import com.nuvio.app.features.party.PartyRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

private const val KEY_HAD_ACCOUNT_SESSION = "had_account_session"
private const val KEY_SIGNED_OUT_NOTICE = "signed_out_notice"

data class PendingAccountAdoption(
    val userId: String,
    val email: String?,
)

private class QuarantinedCandidateSession(
    val throwawayClient: SupabaseClient,
    val session: UserSession,
    val user: UserInfo,
    val server: ServerConfiguration,
    val epoch: Long,
)

private sealed interface StartupValidationResult {
    data class Valid(val user: UserInfo) : StartupValidationResult
    data object InvalidRemoteSession : StartupValidationResult
    data object Unresolvable : StartupValidationResult
}

private sealed interface IsolatedRefreshOutcome {
    data class Refreshed(val accessToken: String) : IsolatedRefreshOutcome
    data object StillValidOrSuperseded : IsolatedRefreshOutcome
    data object InvalidRefreshToken : IsolatedRefreshOutcome
    data object TransientFailure : IsolatedRefreshOutcome
}

internal class StartFreshSessionSnapshot(
    val storedSession: UserSession?,
    val anonymousUserId: String?,
    val hadAccountSession: Boolean,
    val signedOutNotice: Boolean,
)

object AuthRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AuthRepository")

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _pendingAdoption = MutableStateFlow<PendingAccountAdoption?>(null)
    val pendingAdoption: StateFlow<PendingAccountAdoption?> = _pendingAdoption.asStateFlow()

    // Lock order everywhere: isolatedRefreshMutex -> authCoordinatorMutex -> refreshMutex. Never reverse this order.
    private val isolatedRefreshMutex = Mutex()
    private val authCoordinatorMutex = Mutex()
    private val refreshMutex = Mutex()
    private val authSessionEpoch = atomic(0L)

    @Volatile
    private var initialized = false
    private var sessionStatusJob: Job? = null

    @Volatile
    private var approvedRemoteSessionActive = false
    private var approvedRemoteUserId: String? = null
    private var approvedServerUrl: String? = null
    private var lastHandledAccessToken: String? = null
    private var pendingAdoptionHolder: QuarantinedCandidateSession? = null

    @Volatile
    private var approvedSessionExpiryMark: TimeSource.Monotonic.ValueTimeMark? = null

    @Volatile
    private var approvedSessionLifetimeSeconds: Long = 0L
    private var proactiveRefreshJob: Job? = null

    @Volatile
    private var startupHasCandidateSession = false

    @Volatile
    private var transitionQuitInFlight = false

    internal var beforeHandleSessionStatusForTest: (suspend (SessionStatus) -> Unit)? = null

    private fun isClosingForAccountTransition(): Boolean =
        transitionQuitInFlight || AccountDataLifecycle.isTransitionQuitPending()

    private suspend fun closingForTransitionMessage(): String =
        runCatching { getString(Res.string.auth_closing_for_account_change) }
            .getOrDefault("The app is closing to finish an account change. Wait for it to close or cancel quitting first.")

    internal fun currentAuthEpoch(): Long = authSessionEpoch.value

    internal fun hasStartupCandidateSession(): Boolean = startupHasCandidateSession

    fun isRemoteAccountAuthenticated(expectedUserId: String? = null): Boolean {
        val current = _state.value as? AuthState.Authenticated ?: return false
        if (current.isAnonymous || !approvedRemoteSessionActive) return false
        if (expectedUserId != null && current.userId != expectedUserId) return false
        return SupabaseProvider.peekClient()?.auth?.currentSessionOrNull() != null
    }

    fun initialize() {
        if (initialized) return
        initialized = true

        val savedAnonId = AuthStorage.loadAnonymousUserId()?.takeIf { it.isNotBlank() }
        if (savedAnonId != null) {
            startupHasCandidateSession = true
            _state.value = AuthState.Authenticated(
                userId = savedAnonId,
                email = null,
                isAnonymous = true,
            )
            val capturedServer = ServerConfigurationRepository.active.value
            val capturedClient = SupabaseProvider.client
            startSessionStatusCollector(capturedClient, capturedServer.backendUrl)
            return
        }

        val attemptEpoch = authSessionEpoch.value
        val attemptServer = ServerConfigurationRepository.active.value
        sessionStatusJob = scope.launch {
            if (AuthStorage.loadFlag(KEY_SIGNED_OUT_NOTICE)) {
                _error.value = runCatching { getString(Res.string.auth_notice_signed_out) }
                    .getOrDefault("You have been signed out of Nuvio. Please sign in again.")
            }

            restoreSavedStartupSession(
                attemptEpoch = attemptEpoch,
                attemptServer = attemptServer,
            )

            val currentServer = ServerConfigurationRepository.active.value
            if (currentServer.backendUrl == attemptServer.backendUrl) {
                val sharedClient = SupabaseProvider.client
                sharedClient.auth.sessionStatus.collect { status ->
                    beforeHandleSessionStatusForTest?.invoke(status)
                    handleSessionStatus(
                        status = status,
                        sourceClient = sharedClient,
                        sourceServerUrl = currentServer.backendUrl,
                    )
                }
            }
        }
    }

    private fun startSessionStatusCollector(client: SupabaseClient, serverUrl: String) {
        sessionStatusJob?.cancel()
        sessionStatusJob = scope.launch {
            client.auth.sessionStatus.collect { status ->
                beforeHandleSessionStatusForTest?.invoke(status)
                handleSessionStatus(
                    status = status,
                    sourceClient = client,
                    sourceServerUrl = serverUrl,
                )
            }
        }
    }

    private suspend fun restoreSavedStartupSession(
        attemptEpoch: Long,
        attemptServer: ServerConfiguration,
    ) {
        val storedSession = SupabaseProvider.loadStoredSession()
        if (storedSession == null) {
            authCoordinatorMutex.withLock {
                if (
                    isClosingForAccountTransition() ||
                    authSessionEpoch.value != attemptEpoch ||
                    ServerConfigurationRepository.active.value.backendUrl != attemptServer.backendUrl
                ) {
                    return@withLock
                }
                startupHasCandidateSession = false
                runCatching { SupabaseProvider.awaitSharedAuthReady().auth.clearSession() }
                if (AuthStorage.loadFlag(KEY_HAD_ACCOUNT_SESSION)) {
                    resetAfterLostSessionLocked()
                } else if (_state.value is AuthState.Loading) {
                    _state.value = AuthState.Unauthenticated
                }
            }
            return
        }

        startupHasCandidateSession = true
        val throwaway = SupabaseProvider.createThrowawayAuthClient(attemptServer)
        var handedOff = false
        try {
            throwaway.auth.importSession(storedSession, autoRefresh = false)
            val storedUserId = throwaway.auth.currentSessionOrNull()?.user?.id?.trim()?.takeIf { it.isNotEmpty() }
                ?: extractJwtSubClaim(storedSession.accessToken)

            // Check for an explicit owner mismatch before refreshing or treating expiry as a lost session.
            if (!storedUserId.isNullOrEmpty()) {
                val preDecision = AccountDataLifecycle.evaluateRemoteSignIn(
                    AccountOwnerCandidate.Remote(
                        serverUrl = attemptServer.backendUrl,
                        userId = storedUserId,
                    ),
                )
                if (preDecision is RemoteSignInDecision.Mismatch || preDecision is RemoteSignInDecision.Refused) {
                    authCoordinatorMutex.withLock {
                        if (
                            authSessionEpoch.value != attemptEpoch ||
                            ServerConfigurationRepository.active.value.backendUrl != attemptServer.backendUrl
                        ) {
                            return@withLock
                        }
                        startupHasCandidateSession = false
                        if (preDecision is RemoteSignInDecision.Mismatch) {
                            runCatching { throwaway.auth.signOut() }
                            SupabaseProvider.clearAndVerifyStoredSession()
                            _error.value = preDecision.message
                        } else if (preDecision is RemoteSignInDecision.Refused) {
                            _error.value = preDecision.message
                        }
                        runCatching { throwaway.close() }
                        handedOff = true
                        if (_state.value !is AuthState.Authenticated) {
                            _state.value = AuthState.Unauthenticated
                        }
                    }
                    return
                }
            }

            when (val validation = validateStartupCandidateOnThrowaway(throwaway, storedSession)) {
                is StartupValidationResult.Valid -> {
                    val current = throwaway.auth.currentSessionOrNull() ?: storedSession
                    if (current.user?.id != validation.user.id || current.user == null) {
                        throwaway.auth.importSession(current.copy(user = validation.user), autoRefresh = false)
                    }
                    authCoordinatorMutex.withLock {
                        handedOff = true
                        approveAndImportLocked(
                            throwaway = throwaway,
                            attemptServer = attemptServer,
                            attemptEpoch = attemptEpoch,
                            isStartupRestore = true,
                        )
                    }
                }
                StartupValidationResult.InvalidRemoteSession -> {
                    authCoordinatorMutex.withLock {
                        if (
                            authSessionEpoch.value != attemptEpoch ||
                            ServerConfigurationRepository.active.value.backendUrl != attemptServer.backendUrl
                        ) {
                            return@withLock
                        }
                        startupHasCandidateSession = false
                        SupabaseProvider.clearAndVerifyStoredSession()
                        resetAfterLostSessionLocked()
                    }
                }
                StartupValidationResult.Unresolvable -> {
                    authCoordinatorMutex.withLock {
                        if (
                            authSessionEpoch.value != attemptEpoch ||
                            ServerConfigurationRepository.active.value.backendUrl != attemptServer.backendUrl
                        ) {
                            return@withLock
                        }
                        startupHasCandidateSession = false
                        runCatching { SupabaseProvider.awaitSharedAuthReady().auth.clearSession() }
                        if (_state.value is AuthState.Loading) {
                            _state.value = AuthState.Unauthenticated
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.w(e) { "Failed to restore saved Supabase session" }
            authCoordinatorMutex.withLock {
                if (
                    authSessionEpoch.value == attemptEpoch &&
                    ServerConfigurationRepository.active.value.backendUrl == attemptServer.backendUrl &&
                    _state.value is AuthState.Loading
                ) {
                    startupHasCandidateSession = false
                    _state.value = AuthState.Unauthenticated
                }
            }
        } finally {
            if (!handedOff) {
                runCatching { throwaway.close() }
            }
        }
    }

    private suspend fun validateStartupCandidateOnThrowaway(
        throwaway: SupabaseClient,
        storedSession: UserSession,
    ): StartupValidationResult {
        val cachedUser = storedSession.user?.takeIf { it.id.isNotBlank() }
        if (storedSession.expiresAt <= Clock.System.now()) {
            return refreshStartupCandidateOnThrowaway(throwaway, cachedUser)
        }
        return runCatching {
            val fetched = throwaway.auth.retrieveUserForCurrentSession(false)
            if (fetched.id.isBlank()) StartupValidationResult.Unresolvable else StartupValidationResult.Valid(fetched)
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            when {
                e.isJwtExpiredError() -> refreshStartupCandidateOnThrowaway(throwaway, cachedUser)
                e.isInvalidSessionError() -> StartupValidationResult.InvalidRemoteSession
                cachedUser != null -> StartupValidationResult.Valid(cachedUser)
                else -> StartupValidationResult.Unresolvable
            }
        }
    }

    private suspend fun refreshStartupCandidateOnThrowaway(
        throwaway: SupabaseClient,
        cachedUser: UserInfo?,
    ): StartupValidationResult =
        try {
            throwaway.auth.refreshCurrentSession()
            val refreshed = throwaway.auth.currentSessionOrNull()
            val resolvedUser = refreshed?.user?.takeIf { it.id.isNotBlank() }
                ?: refreshed?.accessToken?.let { token ->
                    runCatching { throwaway.auth.retrieveUser(token) }.getOrNull()?.takeIf { it.id.isNotBlank() }
                }
                ?: cachedUser
            if (resolvedUser != null) {
                StartupValidationResult.Valid(resolvedUser)
            } else {
                StartupValidationResult.Unresolvable
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.w(e) { "Startup session refresh on isolated client failed" }
            if (e.isInvalidRefreshError() || e.isInvalidSessionError()) {
                StartupValidationResult.InvalidRemoteSession
            } else if (cachedUser != null) {
                StartupValidationResult.Valid(cachedUser)
            } else {
                StartupValidationResult.Unresolvable
            }
        }

    @OptIn(SupabaseInternal::class)
    private suspend fun handleSessionStatus(
        status: SessionStatus,
        sourceClient: SupabaseClient,
        sourceServerUrl: String,
    ) = authCoordinatorMutex.withLock {
        if (
            isClosingForAccountTransition() ||
            SupabaseProvider.peekClient() !== sourceClient ||
            ServerConfigurationRepository.active.value.backendUrl != sourceServerUrl
        ) {
            return@withLock
        }
        if (AuthStorage.loadAnonymousUserId()?.isNotBlank() == true) return@withLock

        when (status) {
            is SessionStatus.Authenticated -> {
                val sessionUserId = status.session.user?.id?.trim()
                    ?: extractJwtSubClaim(status.session.accessToken)
                if (
                    !approvedRemoteSessionActive ||
                    approvedServerUrl != sourceServerUrl ||
                    approvedRemoteUserId == null ||
                    (sessionUserId != null && sessionUserId != approvedRemoteUserId)
                ) {
                    // Stale or unapproved session appeared on shared client (e.g. late SDK refresh after sign-out).
                    refreshMutex.withLock {
                        runCatching { sourceClient.auth.clearSession() }
                        runCatching { sourceClient.auth.sessionManager.deleteSession() }
                    }
                    return@withLock
                }
                val liveSession = sourceClient.auth.currentSessionOrNull()
                if (liveSession == null || liveSession.accessToken != status.session.accessToken) {
                    return@withLock
                }
                val token = status.session.accessToken
                if (token.isNotEmpty() && token == lastHandledAccessToken) return@withLock
                lastHandledAccessToken = token.takeIf { it.isNotEmpty() }
                val activeId = approvedRemoteUserId ?: return@withLock
                val email = status.session.user?.email ?: (_state.value as? AuthState.Authenticated)?.email
                _state.value = AuthState.Authenticated(
                    userId = activeId,
                    email = email,
                    isAnonymous = false,
                )
            }
            is SessionStatus.NotAuthenticated -> {
                if (
                    sourceClient.auth.sessionStatus.value !is SessionStatus.NotAuthenticated ||
                    sourceClient.auth.currentSessionOrNull() != null
                ) {
                    // Superseded by a session already imported onto sourceClient before this callback ran.
                    return@withLock
                }
                lastHandledAccessToken = null
                if (approvedRemoteSessionActive && AuthStorage.loadFlag(KEY_HAD_ACCOUNT_SESSION)) {
                    resetAfterLostSessionLocked()
                } else if (!approvedRemoteSessionActive && _state.value !is AuthState.Authenticated) {
                    _state.value = AuthState.Unauthenticated
                }
            }
            is SessionStatus.Initializing -> Unit
            is SessionStatus.RefreshFailure -> Unit
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun signInAnonymously() {
        DeviceLinkAuthRepository.cancel()
        val userId = Uuid.random().toString()
        if (authCoordinatorMutex.tryLock()) {
            try {
                applyAnonymousSignInLocked(userId)
            } finally {
                authCoordinatorMutex.unlock()
            }
        } else {
            scope.launch {
                authCoordinatorMutex.withLock {
                    applyAnonymousSignInLocked(userId)
                }
            }
        }
    }

    private fun applyAnonymousSignInLocked(userId: String) {
        if (isClosingForAccountTransition()) {
            _error.value = "The app is closing to finish an account change. Wait for it to close or cancel quitting first."
            return
        }
        _error.value = null
        _notice.value = null
        authSessionEpoch.incrementAndGet()
        dismissPendingAdoptionLocked(signOutThrowaway = true)
        clearApprovedSessionTrackingLocked()
        AuthStorage.saveAnonymousUserId(userId)
        _state.value = AuthState.Authenticated(
            userId = userId,
            email = null,
            isAnonymous = true,
        )
    }

    suspend fun signUpWithEmail(email: String, password: String): Result<Unit> = runCatching {
        _error.value = null
        _notice.value = null
        val attemptEpoch = authSessionEpoch.value
        val attemptServer = ServerConfigurationRepository.active.value
        val throwaway = SupabaseProvider.createThrowawayAuthClient(attemptServer)
        var handedOff = false
        try {
            throwaway.auth.signUpWith(Email) {
                this.email = email
                this.password = password
            }
            val session = throwaway.auth.currentSessionOrNull()
            if (session == null) {
                authCoordinatorMutex.withLock {
                    if (
                        authSessionEpoch.value == attemptEpoch &&
                        ServerConfigurationRepository.active.value.backendUrl == attemptServer.backendUrl
                    ) {
                        _notice.value = runCatching { getString(Res.string.auth_notice_confirm_email) }
                            .getOrDefault("Check your email to confirm your account, then sign in.")
                        if (_state.value !is AuthState.Authenticated) {
                            _state.value = AuthState.Unauthenticated
                        }
                    }
                }
                return@runCatching
            }

            ensureThrowawayUserResolved(throwaway, session)
            authCoordinatorMutex.withLock {
                handedOff = true
                approveAndImportLocked(
                    throwaway = throwaway,
                    attemptServer = attemptServer,
                    attemptEpoch = attemptEpoch,
                    isStartupRestore = false,
                )
            }
        } finally {
            if (!handedOff) {
                runCatching { throwaway.close() }
            }
        }
    }.onFailure { e ->
        if (e is CancellationException) throw e
        log.e(e) { "Email sign-up failed" }
        _error.value = e.safeAuthErrorDescription()
            ?: runCatching { getString(Res.string.auth_sign_up_failed) }.getOrDefault("Sign-up failed")
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> = runCatching {
        _error.value = null
        _notice.value = null
        val attemptEpoch = authSessionEpoch.value
        val attemptServer = ServerConfigurationRepository.active.value
        val throwaway = SupabaseProvider.createThrowawayAuthClient(attemptServer)
        var handedOff = false
        try {
            throwaway.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            val session = throwaway.auth.currentSessionOrNull()
                ?: error("Sign-in completed without returning a session")
            ensureThrowawayUserResolved(throwaway, session)
            authCoordinatorMutex.withLock {
                handedOff = true
                approveAndImportLocked(
                    throwaway = throwaway,
                    attemptServer = attemptServer,
                    attemptEpoch = attemptEpoch,
                    isStartupRestore = false,
                )
            }
        } finally {
            if (!handedOff) {
                runCatching { throwaway.close() }
            }
        }
    }.onFailure { e ->
        if (e is CancellationException) throw e
        log.e(e) { "Email sign-in failed" }
        _error.value = e.safeAuthErrorDescription()
            ?: runCatching { getString(Res.string.auth_sign_in_failed) }.getOrDefault("Sign-in failed")
    }

    internal suspend fun completeDeviceLinkSignIn(
        throwaway: SupabaseClient,
        attemptServer: ServerConfiguration,
        attemptEpoch: Long,
    ) {
        val session = throwaway.auth.currentSessionOrNull()
            ?: error("Device link completed without a session")
        ensureThrowawayUserResolved(throwaway, session)
        authCoordinatorMutex.withLock {
            approveAndImportLocked(
                throwaway = throwaway,
                attemptServer = attemptServer,
                attemptEpoch = attemptEpoch,
                isStartupRestore = false,
            )
        }
    }

    private suspend fun ensureThrowawayUserResolved(
        throwaway: SupabaseClient,
        session: UserSession,
    ) {
        if (session.user?.id?.isNotBlank() == true) return
        val fetched = throwaway.auth.retrieveUser(session.accessToken)
        check(fetched.id.isNotBlank()) { "Authenticated session has an empty user id" }
        throwaway.auth.importSession(session.copy(user = fetched), autoRefresh = false)
    }

    private suspend fun approveAndImportLocked(
        throwaway: SupabaseClient,
        attemptServer: ServerConfiguration,
        attemptEpoch: Long,
        isStartupRestore: Boolean,
    ) {
        if (isClosingForAccountTransition()) {
            if (!isStartupRestore) {
                runCatching { throwaway.auth.signOut() }
            }
            runCatching { throwaway.close() }
            _notice.value = null
            _error.value = closingForTransitionMessage()
            if (_state.value !is AuthState.Authenticated) {
                _state.value = AuthState.Unauthenticated
            }
            return
        }
        if (
            authSessionEpoch.value != attemptEpoch ||
            ServerConfigurationRepository.active.value.backendUrl != attemptServer.backendUrl
        ) {
            if (!isStartupRestore) {
                runCatching { throwaway.auth.signOut() }
            }
            runCatching { throwaway.close() }
            return
        }

        val candidateSession = throwaway.auth.currentSessionOrNull()
        val resolvedUser = candidateSession?.user
        val userId = resolvedUser?.id?.trim().orEmpty()
        if (candidateSession == null || resolvedUser == null || userId.isEmpty()) {
            if (!isStartupRestore) {
                runCatching { throwaway.auth.signOut() }
            }
            runCatching { throwaway.close() }
            if (_state.value !is AuthState.Authenticated) {
                _state.value = AuthState.Unauthenticated
            }
            return
        }

        dismissPendingAdoptionLocked(signOutThrowaway = true)

        when (
            val decision = AccountDataLifecycle.evaluateRemoteSignIn(
                AccountOwnerCandidate.Remote(
                    serverUrl = attemptServer.backendUrl,
                    userId = userId,
                ),
            )
        ) {
            RemoteSignInDecision.Approved -> {
                // Match: stamp has been verified/written first; close throwaway WITHOUT signOut, then import once.
                runCatching { throwaway.close() }
                importApprovedSessionLocked(
                    session = candidateSession,
                    user = resolvedUser,
                    serverUrl = attemptServer.backendUrl,
                )
            }
            RemoteSignInDecision.AskAdoption -> {
                val holdEpoch = authSessionEpoch.incrementAndGet()
                pendingAdoptionHolder = QuarantinedCandidateSession(
                    throwawayClient = throwaway,
                    session = candidateSession,
                    user = resolvedUser,
                    server = attemptServer,
                    epoch = holdEpoch,
                )
                _pendingAdoption.value = PendingAccountAdoption(
                    userId = userId,
                    email = resolvedUser.email,
                )
                _error.value = null
                _notice.value = null
                if (_state.value !is AuthState.Authenticated) {
                    _state.value = AuthState.Unauthenticated
                }
            }
            is RemoteSignInDecision.Mismatch -> {
                // Mismatch: signOut the throwaway, close it, stay signed out, show mismatch message.
                runCatching { throwaway.auth.signOut() }
                runCatching { throwaway.close() }
                if (isStartupRestore) {
                    SupabaseProvider.clearAndVerifyStoredSession()
                }
                _notice.value = null
                _error.value = decision.message
                if (_state.value !is AuthState.Authenticated) {
                    _state.value = AuthState.Unauthenticated
                }
            }
            is RemoteSignInDecision.Refused -> {
                if (!isStartupRestore) {
                    runCatching { throwaway.auth.signOut() }
                }
                runCatching { throwaway.close() }
                _notice.value = null
                _error.value = decision.message
                if (_state.value !is AuthState.Authenticated) {
                    _state.value = AuthState.Unauthenticated
                }
            }
        }
    }

    private suspend fun importApprovedSessionLocked(
        session: UserSession,
        user: UserInfo,
        serverUrl: String,
    ) {
        val userId = user.id.trim()
        val importedEpoch = authSessionEpoch.incrementAndGet()
        AuthStorage.clearAnonymousUserId()
        approvedRemoteSessionActive = true
        approvedRemoteUserId = userId
        approvedServerUrl = serverUrl
        lastHandledAccessToken = session.accessToken
        startupHasCandidateSession = false
        val sharedClient = SupabaseProvider.awaitSharedAuthReady()
        refreshMutex.withLock {
            sharedClient.auth.importSession(session, autoRefresh = false)
        }
        recordApprovedSessionExpiryLocked(
            client = sharedClient,
            serverUrl = serverUrl,
            epoch = importedEpoch,
            session = session,
            callerJob = currentCoroutineContext()[Job],
        )
        AuthStorage.saveFlag(KEY_HAD_ACCOUNT_SESSION, true)
        AuthStorage.saveFlag(KEY_SIGNED_OUT_NOTICE, false)
        _error.value = null
        _notice.value = null
        _state.value = AuthState.Authenticated(
            userId = userId,
            email = user.email,
            isAnonymous = false,
        )
    }

    suspend fun confirmPendingAdoption(): Result<Unit> = authCoordinatorMutex.withLock {
        val holder = pendingAdoptionHolder
            ?: return@withLock Result.failure(IllegalStateException("No account adoption is awaiting confirmation"))
        pendingAdoptionHolder = null
        _pendingAdoption.value = null

        if (isClosingForAccountTransition()) {
            runCatching { holder.throwawayClient.auth.signOut() }
            runCatching { holder.throwawayClient.close() }
            val msg = closingForTransitionMessage()
            _error.value = msg
            return@withLock Result.failure(IllegalStateException(msg))
        }

        if (
            holder.epoch != authSessionEpoch.value ||
            ServerConfigurationRepository.active.value.backendUrl != holder.server.backendUrl
        ) {
            runCatching { holder.throwawayClient.auth.signOut() }
            runCatching { holder.throwawayClient.close() }
            return@withLock Result.failure(IllegalStateException("Sign-in session is no longer current"))
        }

        when (
            val decision = AccountDataLifecycle.confirmRemoteAdoption(
                AccountOwnerCandidate.Remote(
                    serverUrl = holder.server.backendUrl,
                    userId = holder.user.id.trim(),
                ),
            )
        ) {
            RemoteSignInDecision.Approved -> {
                runCatching { holder.throwawayClient.close() }
                importApprovedSessionLocked(
                    session = holder.session,
                    user = holder.user,
                    serverUrl = holder.server.backendUrl,
                )
                Result.success(Unit)
            }
            is RemoteSignInDecision.Mismatch -> {
                runCatching { holder.throwawayClient.auth.signOut() }
                runCatching { holder.throwawayClient.close() }
                _error.value = decision.message
                _state.value = AuthState.Unauthenticated
                Result.failure(IllegalStateException(decision.message))
            }
            is RemoteSignInDecision.Refused -> {
                runCatching { holder.throwawayClient.auth.signOut() }
                runCatching { holder.throwawayClient.close() }
                _error.value = decision.message
                _state.value = AuthState.Unauthenticated
                Result.failure(IllegalStateException(decision.message))
            }
            RemoteSignInDecision.AskAdoption -> {
                runCatching { holder.throwawayClient.close() }
                Result.failure(IllegalStateException("Unexpected adoption state"))
            }
        }
    }

    suspend fun declinePendingAdoption(): Result<Unit> {
        authCoordinatorMutex.withLock {
            val holder = pendingAdoptionHolder
            pendingAdoptionHolder = null
            _pendingAdoption.value = null
            authSessionEpoch.incrementAndGet()
            if (holder != null) {
                runCatching { holder.throwawayClient.auth.signOut() }
                runCatching { holder.throwawayClient.close() }
            }
            _state.value = AuthState.Unauthenticated
        }
        return startFresh()
    }

    suspend fun startFresh(): Result<Unit> {
        DeviceLinkAuthRepository.cancel()
        val prepareFailure = authCoordinatorMutex.withLock {
            prepareStartFreshLocked()
        }
        if (prepareFailure != null) {
            return Result.failure(prepareFailure)
        }

        var completedExit = false
        return try {
            val outcome = AccountDataLifecycle.startFresh()
            outcome.fold(
                onSuccess = { quitOutcome ->
                    if (quitOutcome == LifecycleQuitOutcome.Completed) {
                        completedExit = true
                    }
                    Result.success(Unit)
                },
                onFailure = { err ->
                    authCoordinatorMutex.withLock {
                        _error.value = err.message
                    }
                    Result.failure(err)
                },
            )
        } finally {
            if (!completedExit) {
                authCoordinatorMutex.withLock {
                    transitionQuitInFlight = false
                }
            }
        }
    }

    private suspend fun prepareStartFreshLocked(): Throwable? {
        if (isClosingForAccountTransition()) {
            val msg = closingForTransitionMessage()
            _error.value = msg
            return IllegalStateException(msg)
        }
        if (_state.value is AuthState.Authenticated) {
            return IllegalStateException("Sign out before choosing Start fresh.")
        }
        _error.value = null
        _notice.value = null
        transitionQuitInFlight = true
        authSessionEpoch.incrementAndGet()
        dismissPendingAdoptionLocked(signOutThrowaway = true)
        clearApprovedSessionTrackingLocked()
        startupHasCandidateSession = false
        return null
    }

    internal suspend fun clearSessionBeforeStartFreshJournal(): StartFreshSessionSnapshot? = authCoordinatorMutex.withLock {
        val snapshot = StartFreshSessionSnapshot(
            storedSession = SupabaseProvider.loadStoredSession(),
            anonymousUserId = runCatching { AuthStorage.loadAnonymousUserId() }.getOrNull(),
            hadAccountSession = runCatching { AuthStorage.loadFlag(KEY_HAD_ACCOUNT_SESSION) }.getOrDefault(false),
            signedOutNotice = runCatching { AuthStorage.loadFlag(KEY_SIGNED_OUT_NOTICE) }.getOrDefault(false),
        )
        authSessionEpoch.incrementAndGet()
        dismissPendingAdoptionLocked(signOutThrowaway = true)
        clearApprovedSessionTrackingLocked()
        startupHasCandidateSession = false
        AuthStorage.clearAnonymousUserId()
        AuthStorage.saveFlag(KEY_HAD_ACCOUNT_SESSION, false)
        AuthStorage.saveFlag(KEY_SIGNED_OUT_NOTICE, false)
        if (_state.value is AuthState.Authenticated) {
            _state.value = AuthState.Unauthenticated
        }
        val cleared = refreshMutex.withLock {
            SupabaseProvider.clearAndVerifyStoredSession()
        }
        if (cleared) {
            snapshot
        } else {
            restoreStartFreshSessionSnapshotLocked(snapshot)
            null
        }
    }

    internal suspend fun restoreAfterAbortedStartFreshJournal(snapshot: StartFreshSessionSnapshot) =
        authCoordinatorMutex.withLock {
            restoreStartFreshSessionSnapshotLocked(snapshot)
        }

    private suspend fun restoreStartFreshSessionSnapshotLocked(snapshot: StartFreshSessionSnapshot) {
        if (!snapshot.anonymousUserId.isNullOrBlank()) {
            runCatching { AuthStorage.saveAnonymousUserId(snapshot.anonymousUserId) }
        }
        runCatching { AuthStorage.saveFlag(KEY_HAD_ACCOUNT_SESSION, snapshot.hadAccountSession) }
        runCatching { AuthStorage.saveFlag(KEY_SIGNED_OUT_NOTICE, snapshot.signedOutNotice) }
        refreshMutex.withLock {
            SupabaseProvider.restoreStoredSessionIfPresent(snapshot.storedSession)
        }
    }

    @OptIn(SupabaseInternal::class)
    suspend fun signOut(): Result<Unit> {
        DeviceLinkAuthRepository.cancel()
        return authCoordinatorMutex.withLock {
            _error.value = null
            _notice.value = null
            authSessionEpoch.incrementAndGet()
            dismissPendingAdoptionLocked(signOutThrowaway = true)

            PartyRepository.requestStopForSignOut()
            runCatching { PartyCompanion.stop() }

            val anonymousRead = runCatching { AuthStorage.loadAnonymousUserId() }
            val wasAnonymous = anonymousRead.getOrNull() != null
            val anonymousClear = runCatching { AuthStorage.clearAnonymousUserId() }
            AuthStorage.saveFlag(KEY_HAD_ACCOUNT_SESSION, false)
            AuthStorage.saveFlag(KEY_SIGNED_OUT_NOTICE, false)
            clearApprovedSessionTrackingLocked()
            startupHasCandidateSession = false

            var remoteSignOut: Result<Unit> = Result.success(Unit)
            var sessionClear: Result<Unit> = Result.success(Unit)
            refreshMutex.withLock {
                val activeClient = SupabaseProvider.awaitSharedAuthReady()
                remoteSignOut = if (wasAnonymous) {
                    Result.success(Unit)
                } else {
                    runCatching { activeClient.auth.signOut() }
                }
                sessionClear = runCatching {
                    activeClient.auth.clearSession()
                    activeClient.auth.sessionManager.deleteSession()
                }.onFailure { error ->
                    log.w(error) { "Failed to clear Supabase session during sign-out" }
                }
            }

            val localCleanup = runCatching { AccountDataLifecycle.onSignOut() }

            refreshMutex.withLock {
                val activeClient = SupabaseProvider.peekClient()
                if (
                    activeClient != null &&
                    (
                        activeClient.auth.currentSessionOrNull() != null ||
                            activeClient.auth.sessionStatus.value is SessionStatus.Authenticated ||
                            runCatching { activeClient.auth.sessionManager.loadSession() }.getOrNull() != null
                        )
                ) {
                    runCatching {
                        activeClient.auth.clearSession()
                        activeClient.auth.sessionManager.deleteSession()
                    }
                }
            }

            _state.value = AuthState.Unauthenticated

            val cancellation = remoteSignOut.exceptionOrNull() as? CancellationException
                ?: sessionClear.exceptionOrNull() as? CancellationException
                ?: localCleanup.exceptionOrNull() as? CancellationException
            if (cancellation != null) throw cancellation

            val failure = anonymousRead.exceptionOrNull()
                ?: anonymousClear.exceptionOrNull()
                ?: remoteSignOut.exceptionOrNull()
                ?: sessionClear.exceptionOrNull()
                ?: localCleanup.exceptionOrNull()
            if (failure == null) {
                Result.success(Unit)
            } else {
                log.e(failure) { "Sign-out did not complete cleanly; all local cleanup steps were attempted" }
                _error.value = failure.message ?: runCatching {
                    getString(Res.string.auth_sign_out_failed)
                }.getOrDefault("Sign out failed")
                Result.failure(failure)
            }
        }
    }

    suspend fun prepareForServerSwitch(): Result<Unit> {
        DeviceLinkAuthRepository.cancel()
        return authCoordinatorMutex.withLock {
            clearSessionForServerSwitchLocked()
        }
    }

    internal suspend fun switchServerSerialized(
        stageOnlyForRestart: Boolean,
        saveConfiguration: () -> Boolean,
    ): ServerSwitchResult {
        DeviceLinkAuthRepository.cancel()
        if (stageOnlyForRestart) {
            val canStart = authCoordinatorMutex.withLock {
                if (isClosingForAccountTransition()) {
                    false
                } else {
                    transitionQuitInFlight = true
                    dismissPendingAdoptionLocked(signOutThrowaway = true)
                    true
                }
            }
            if (!canStart) return ServerSwitchResult.Failed(ServerSwitchFailure.Restart)

            var completedExit = false
            return try {
                AccountDataLifecycle.switchDesktopServerAndQuit(saveConfiguration).fold(
                    onSuccess = { outcome ->
                        if (outcome == LifecycleQuitOutcome.Completed) {
                            completedExit = true
                            ServerSwitchResult.Completed
                        } else {
                            ServerSwitchResult.Cancelled
                        }
                    },
                    onFailure = { error ->
                        val stageReason = (error as? ServerSwitchStageException)?.failure
                            ?: ServerSwitchFailure.Restart
                        ServerSwitchResult.Failed(stageReason)
                    },
                )
            } finally {
                if (!completedExit) {
                    authCoordinatorMutex.withLock {
                        transitionQuitInFlight = false
                    }
                }
            }
        }

        return authCoordinatorMutex.withLock {
            val previousServer = ServerConfigurationRepository.active.value
            val saved = runCatching { saveConfiguration() }.getOrDefault(false)
            if (!saved) {
                return@withLock ServerSwitchResult.Failed(ServerSwitchFailure.Save)
            }
            if (clearSessionForServerSwitchLocked().isFailure) {
                runCatching {
                    if (previousServer.isCustom) {
                        ServerConfigurationRepository.saveCustom(previousServer)
                    } else {
                        ServerConfigurationRepository.useOfficial()
                    }
                }
                return@withLock ServerSwitchResult.Failed(ServerSwitchFailure.SessionClear)
            }
            runCatching {
                SupabaseProvider.reset()
                reinitializeLocked()
                NetworkStatusRepository.requestRefresh(force = true)
            }.onFailure { return@withLock ServerSwitchResult.Failed(ServerSwitchFailure.Restart) }
            ServerSwitchResult.Completed
        }
    }

    internal suspend fun commitConfirmedDesktopServerSwitch(saveConfiguration: () -> Boolean) {
        authCoordinatorMutex.withLock {
            val saveAttempt = runCatching { saveConfiguration() }
            if (saveAttempt.getOrDefault(false) != true) {
                throw ServerSwitchStageException(
                    failure = ServerSwitchFailure.Save,
                    message = saveAttempt.exceptionOrNull()?.message
                        ?: "Could not save the selected server configuration.",
                    cause = saveAttempt.exceptionOrNull(),
                )
            }
            val clearOutcome = clearSessionForServerSwitchLocked()
            if (clearOutcome.isFailure) {
                runCatching { ServerConfigurationRepository.restoreActiveToStorage() }
                throw ServerSwitchStageException(
                    failure = ServerSwitchFailure.SessionClear,
                    message = clearOutcome.exceptionOrNull()?.message
                        ?: "Could not clear the saved sign-in before switching servers.",
                    cause = clearOutcome.exceptionOrNull(),
                )
            }
        }
    }

    @OptIn(SupabaseInternal::class)
    private suspend fun clearSessionForServerSwitchLocked(): Result<Unit> {
        _error.value = null
        _notice.value = null
        authSessionEpoch.incrementAndGet()
        dismissPendingAdoptionLocked(signOutThrowaway = true)
        PartyRepository.requestStopForSignOut()
        runCatching { PartyCompanion.stop() }

        val lifecycleStop = runCatching { AccountDataLifecycle.onPrepareForServerSwitch() }
        val anonymousClear = runCatching { AuthStorage.clearAnonymousUserId() }
        AuthStorage.saveFlag(KEY_HAD_ACCOUNT_SESSION, false)
        AuthStorage.saveFlag(KEY_SIGNED_OUT_NOTICE, false)
        clearApprovedSessionTrackingLocked()
        startupHasCandidateSession = false

        val sessionClear = refreshMutex.withLock {
            runCatching {
                check(SupabaseProvider.clearAndVerifyStoredSession()) {
                    "Failed to clear stored session before switching servers"
                }
            }
        }
        _state.value = AuthState.Unauthenticated

        val cancellation = lifecycleStop.exceptionOrNull() as? CancellationException
            ?: sessionClear.exceptionOrNull() as? CancellationException
        if (cancellation != null) throw cancellation

        val failure = lifecycleStop.exceptionOrNull()
            ?: anonymousClear.exceptionOrNull()
            ?: sessionClear.exceptionOrNull()
        return if (failure == null) Result.success(Unit) else Result.failure(failure)
    }

    fun reinitialize() {
        sessionStatusJob?.cancel()
        sessionStatusJob = null
        initialized = false
        transitionQuitInFlight = false
        clearApprovedSessionTrackingLocked()
        _state.value = AuthState.Loading
        initialize()
    }

    private fun reinitializeLocked() {
        sessionStatusJob?.cancel()
        sessionStatusJob = null
        initialized = false
        transitionQuitInFlight = false
        clearApprovedSessionTrackingLocked()
        _state.value = AuthState.Loading
        initialize()
    }

    private fun clearApprovedSessionTrackingLocked() {
        proactiveRefreshJob?.cancel()
        proactiveRefreshJob = null
        approvedSessionExpiryMark = null
        approvedSessionLifetimeSeconds = 0L
        approvedRemoteSessionActive = false
        approvedRemoteUserId = null
        approvedServerUrl = null
        lastHandledAccessToken = null
    }

    private fun recordApprovedSessionExpiryLocked(
        client: SupabaseClient,
        serverUrl: String,
        epoch: Long,
        session: UserSession,
        callerJob: Job?,
    ) {
        val remaining = (session.expiresAt - Clock.System.now()).coerceAtLeast(Duration.ZERO)
        approvedSessionLifetimeSeconds = session.expiresIn.coerceAtLeast(0L)
        approvedSessionExpiryMark = TimeSource.Monotonic.markNow() + remaining
        val previousJob = proactiveRefreshJob
        if (previousJob != null && previousJob !== callerJob) {
            previousJob.cancel()
        }
        proactiveRefreshJob = if (remaining > Duration.ZERO) {
            val remainingMs = remaining.inWholeMilliseconds
            val delayMs = (remainingMs - 30_000L)
                .coerceAtLeast((remainingMs * 3L / 4L).coerceAtLeast(250L))
            val tokenAtSchedule = session.accessToken
            scope.launch {
                delay(delayMs)
                proactiveRefreshIfCurrent(
                    observedEpoch = epoch,
                    observedClient = client,
                    observedServerUrl = serverUrl,
                    observedAccessToken = tokenAtSchedule,
                )
            }
        } else {
            null
        }
    }

    private fun isApprovedSessionExpiredOrNearExpiry(): Boolean {
        val mark = approvedSessionExpiryMark ?: return false
        val margin = if (approvedSessionLifetimeSeconds > 60L) 30.seconds else Duration.ZERO
        return (mark - margin).hasPassedNow()
    }

    internal suspend fun resolveRequestAccessToken(
        observedClient: SupabaseClient,
        requestBearerToken: String?,
    ): String? {
        if (!approvedRemoteSessionActive || SupabaseProvider.peekClient() !== observedClient) return null
        val observedEpoch = authSessionEpoch.value
        val currentSession = observedClient.auth.currentSessionOrNull() ?: return null
        if (!isApprovedSessionExpiredOrNearExpiry()) {
            return if (
                authSessionEpoch.value == observedEpoch &&
                approvedRemoteSessionActive &&
                !requestBearerToken.isNullOrBlank() &&
                requestBearerToken != observedClient.supabaseKey &&
                currentSession.accessToken.isNotBlank() &&
                currentSession.accessToken != requestBearerToken
            ) {
                currentSession.accessToken
            } else {
                null
            }
        }
        val observedServerUrl = ServerConfigurationRepository.active.value.backendUrl
        return when (
            val outcome = refreshSharedSessionIsolated(
                observedEpoch = observedEpoch,
                observedClient = observedClient,
                observedServerUrl = observedServerUrl,
                observedAccessToken = currentSession.accessToken,
                onlyWhenExpired = true,
            )
        ) {
            is IsolatedRefreshOutcome.Refreshed -> outcome.accessToken.takeIf { it.isNotBlank() }
            IsolatedRefreshOutcome.InvalidRefreshToken -> {
                invalidateIfEpochCurrent(
                    observedEpoch = observedEpoch,
                    observedClient = observedClient,
                    observedServerUrl = observedServerUrl,
                    source = "Pre-request session refresh",
                )
                null
            }
            IsolatedRefreshOutcome.StillValidOrSuperseded,
            IsolatedRefreshOutcome.TransientFailure -> null
        }
    }

    private suspend fun proactiveRefreshIfCurrent(
        observedEpoch: Long,
        observedClient: SupabaseClient,
        observedServerUrl: String,
        observedAccessToken: String,
    ) {
        val outcome = refreshSharedSessionIsolated(
            observedEpoch = observedEpoch,
            observedClient = observedClient,
            observedServerUrl = observedServerUrl,
            observedAccessToken = observedAccessToken,
            onlyWhenExpired = false,
        )
        if (outcome == IsolatedRefreshOutcome.InvalidRefreshToken) {
            invalidateIfEpochCurrent(
                observedEpoch = observedEpoch,
                observedClient = observedClient,
                observedServerUrl = observedServerUrl,
                source = "Proactive session refresh",
            )
        }
    }

    private suspend fun invalidateIfEpochCurrent(
        observedEpoch: Long,
        observedClient: SupabaseClient,
        observedServerUrl: String,
        source: String,
        cause: Throwable? = null,
    ): Boolean = authCoordinatorMutex.withLock {
        if (
            authSessionEpoch.value != observedEpoch ||
            !approvedRemoteSessionActive ||
            SupabaseProvider.peekClient() !== observedClient ||
            ServerConfigurationRepository.active.value.backendUrl != observedServerUrl
        ) {
            return@withLock false
        }
        if (cause != null) {
            log.w(cause) { "$source failed because the current Supabase account/session is no longer valid; clearing local auth" }
        } else {
            log.w { "$source rejected the current Supabase session; clearing local auth" }
        }
        clearLocalSessionAfterRemoteInvalidationLocked()
        true
    }

    suspend fun signOutIfSessionInvalid(error: Throwable, source: String): Boolean {
        val currentState = _state.value as? AuthState.Authenticated ?: return false
        if (currentState.isAnonymous || !approvedRemoteSessionActive) return false
        val observedEpoch = authSessionEpoch.value
        val observedClient = SupabaseProvider.peekClient() ?: return false
        val observedAccessToken = observedClient.auth.currentSessionOrNull()?.accessToken ?: return false
        val observedServerUrl = ServerConfigurationRepository.active.value.backendUrl

        val invalid = if (error.isJwtExpiredError()) {
            refreshRejected(
                observedEpoch = observedEpoch,
                observedClient = observedClient,
                observedServerUrl = observedServerUrl,
                observedAccessToken = observedAccessToken,
            )
        } else {
            error.isDeletedAccountError()
        }
        if (!invalid) return false

        return invalidateIfEpochCurrent(
            observedEpoch = observedEpoch,
            observedClient = observedClient,
            observedServerUrl = observedServerUrl,
            source = source,
            cause = error,
        )
    }

    private suspend fun refreshRejected(
        observedEpoch: Long,
        observedClient: SupabaseClient,
        observedServerUrl: String,
        observedAccessToken: String,
    ): Boolean =
        refreshSharedSessionIsolated(
            observedEpoch = observedEpoch,
            observedClient = observedClient,
            observedServerUrl = observedServerUrl,
            observedAccessToken = observedAccessToken,
            onlyWhenExpired = false,
        ) == IsolatedRefreshOutcome.InvalidRefreshToken

    @OptIn(SupabaseInternal::class)
    private suspend fun refreshSharedSessionIsolated(
        observedEpoch: Long,
        observedClient: SupabaseClient,
        observedServerUrl: String,
        observedAccessToken: String,
        onlyWhenExpired: Boolean,
    ): IsolatedRefreshOutcome = isolatedRefreshMutex.withLock outer@{
        val callerJob = currentCoroutineContext()[Job]
        val snapshot = authCoordinatorMutex.withLock {
            if (
                authSessionEpoch.value != observedEpoch ||
                !approvedRemoteSessionActive ||
                SupabaseProvider.peekClient() !== observedClient ||
                ServerConfigurationRepository.active.value.backendUrl != observedServerUrl
            ) {
                return@withLock null
            }
            val currentSession = observedClient.auth.currentSessionOrNull()
                ?: return@withLock null
            if (onlyWhenExpired && !isApprovedSessionExpiredOrNearExpiry()) {
                return@outer IsolatedRefreshOutcome.Refreshed(currentSession.accessToken)
            }
            if (currentSession.accessToken != observedAccessToken) {
                return@outer if (onlyWhenExpired) {
                    IsolatedRefreshOutcome.Refreshed(currentSession.accessToken)
                } else {
                    IsolatedRefreshOutcome.StillValidOrSuperseded
                }
            }
            ServerConfigurationRepository.active.value to currentSession
        } ?: return@outer IsolatedRefreshOutcome.StillValidOrSuperseded

        val (server, sessionToRefresh) = snapshot
        val throwaway = SupabaseProvider.createThrowawayAuthClient(server)
        try {
            throwaway.auth.importSession(sessionToRefresh, autoRefresh = false)
            throwaway.auth.refreshCurrentSession()
            val refreshed = throwaway.auth.currentSessionOrNull()
                ?: return@outer IsolatedRefreshOutcome.TransientFailure
            val merged = if (refreshed.user == null && sessionToRefresh.user != null) {
                refreshed.copy(user = sessionToRefresh.user)
            } else {
                refreshed
            }
            authCoordinatorMutex.withLock {
                if (
                    authSessionEpoch.value == observedEpoch &&
                    approvedRemoteSessionActive &&
                    SupabaseProvider.peekClient() === observedClient &&
                    ServerConfigurationRepository.active.value.backendUrl == observedServerUrl &&
                    observedClient.auth.currentSessionOrNull()?.accessToken == observedAccessToken
                ) {
                    lastHandledAccessToken = merged.accessToken.takeIf { it.isNotEmpty() }
                    refreshMutex.withLock {
                        observedClient.auth.importSession(merged, autoRefresh = false)
                    }
                    recordApprovedSessionExpiryLocked(
                        client = observedClient,
                        serverUrl = observedServerUrl,
                        epoch = observedEpoch,
                        session = merged,
                        callerJob = callerJob,
                    )
                    IsolatedRefreshOutcome.Refreshed(merged.accessToken)
                } else {
                    IsolatedRefreshOutcome.StillValidOrSuperseded
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val stillCurrent = authCoordinatorMutex.withLock {
                authSessionEpoch.value == observedEpoch &&
                    approvedRemoteSessionActive &&
                    SupabaseProvider.peekClient() === observedClient &&
                    ServerConfigurationRepository.active.value.backendUrl == observedServerUrl
            }
            if (!stillCurrent) {
                return@outer IsolatedRefreshOutcome.StillValidOrSuperseded
            }
            log.w(e) { "Supabase session refresh failed" }
            if (e.isInvalidRefreshError()) {
                IsolatedRefreshOutcome.InvalidRefreshToken
            } else {
                IsolatedRefreshOutcome.TransientFailure
            }
        } finally {
            runCatching { throwaway.close() }
        }
    }

    private suspend fun clearLocalSessionAfterRemoteInvalidationLocked() {
        authSessionEpoch.incrementAndGet()
        clearApprovedSessionTrackingLocked()
        refreshMutex.withLock {
            runCatching { SupabaseProvider.clearAndVerifyStoredSession() }
                .onFailure { e ->
                    log.w(e) { "Failed to clear Supabase session after remote invalidation; continuing local reset" }
                }
        }
        resetAfterLostSessionLocked()
    }

    private suspend fun resetAfterLostSessionLocked() {
        authSessionEpoch.incrementAndGet()
        dismissPendingAdoptionLocked(signOutThrowaway = true)
        PartyRepository.requestStopForSignOut()
        runCatching { PartyCompanion.stop() }
        val notice = runCatching { getString(Res.string.auth_notice_signed_out) }
            .getOrDefault("You have been signed out of Nuvio. Please sign in again.")
        AuthStorage.clearAnonymousUserId()
        clearApprovedSessionTrackingLocked()
        startupHasCandidateSession = false
        val localCleanup = runCatching { AccountDataLifecycle.onLostSession() }
        AuthStorage.saveFlag(KEY_HAD_ACCOUNT_SESSION, false)
        AuthStorage.saveFlag(KEY_SIGNED_OUT_NOTICE, true)
        _notice.value = null
        _error.value = notice
        _state.value = AuthState.Unauthenticated
        localCleanup.onFailure { error ->
            log.e(error) { "Local account cleanup failed after losing the session" }
        }
    }

    @OptIn(SupabaseInternal::class)
    suspend fun deleteAccount(): Result<Unit> = runCatching {
        _error.value = null
        _notice.value = null
        authCoordinatorMutex.withLock {
            if (isClosingForAccountTransition()) {
                error(closingForTransitionMessage())
            }
            AccountDataLifecycle.precheckBeforeAccountDelete().getOrThrow()
        }
        SupabaseProvider.client.functions.invoke("delete-account")
        DeviceLinkAuthRepository.cancel()
        var completedProcessExit = false
        try {
            authCoordinatorMutex.withLock {
                transitionQuitInFlight = true
                authSessionEpoch.incrementAndGet()
                dismissPendingAdoptionLocked(signOutThrowaway = true)
                PartyRepository.requestStopForSignOut()
                runCatching { PartyCompanion.stop() }
                AuthStorage.clearAnonymousUserId()
                AuthStorage.saveFlag(KEY_HAD_ACCOUNT_SESSION, false)
                AuthStorage.saveFlag(KEY_SIGNED_OUT_NOTICE, false)
                clearApprovedSessionTrackingLocked()
                startupHasCandidateSession = false

                val cleared = refreshMutex.withLock {
                    val activeClient = SupabaseProvider.client
                    runCatching { activeClient.auth.signOut() }
                        .onFailure { signOutErr ->
                            log.w(signOutErr) { "Remote sign-out after account deletion failed; clearing local session" }
                        }
                    SupabaseProvider.clearAndVerifyStoredSession()
                }
                _state.value = AuthState.Unauthenticated
                if (!cleared) {
                    val msg = runCatching { getString(Res.string.account_start_fresh_session_clear_failed) }
                        .getOrDefault("Could not clear the saved sign-in on this Mac. Nothing was changed.")
                    error(msg)
                }
            }
            val deleteOutcome = AccountDataLifecycle.onAccountDeleted().getOrThrow()
            if (deleteOutcome == AccountDeleteLifecycleOutcome.ExitingProcess) {
                completedProcessExit = true
            }
        } finally {
            if (!completedProcessExit) {
                authCoordinatorMutex.withLock {
                    transitionQuitInFlight = false
                }
            }
        }
    }.onFailure { e ->
        if (e is CancellationException) throw e
        log.e(e) { "Account deletion failed" }
        _error.value = e.message
            ?: runCatching { getString(Res.string.auth_account_deletion_failed) }.getOrDefault("Account deletion failed")
    }

    private fun dismissPendingAdoptionLocked(signOutThrowaway: Boolean) {
        val holder = pendingAdoptionHolder ?: return
        pendingAdoptionHolder = null
        _pendingAdoption.value = null
        scope.launch {
            if (signOutThrowaway) {
                runCatching { holder.throwawayClient.auth.signOut() }
            }
            runCatching { holder.throwawayClient.close() }
        }
    }

    fun clearError() {
        _error.value = null
        _notice.value = null
    }

    internal suspend fun resetForTest() {
        val job = sessionStatusJob
        sessionStatusJob = null
        job?.cancelAndJoin()
        val proactive = proactiveRefreshJob
        proactiveRefreshJob = null
        proactive?.cancelAndJoin()
        val holder = pendingAdoptionHolder
        pendingAdoptionHolder = null
        _pendingAdoption.value = null
        if (holder != null) {
            runCatching { holder.throwawayClient.close() }
        }
        initialized = false
        transitionQuitInFlight = false
        beforeHandleSessionStatusForTest = null
        authSessionEpoch.incrementAndGet()
        clearApprovedSessionTrackingLocked()
        startupHasCandidateSession = false
        _state.value = AuthState.Loading
        _error.value = null
        _notice.value = null
    }

    private fun Throwable.safeAuthErrorDescription(): String? =
        findCause<AuthRestException>()
            ?.errorDescription
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: findCause<RestException>()
                ?.description
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
}
