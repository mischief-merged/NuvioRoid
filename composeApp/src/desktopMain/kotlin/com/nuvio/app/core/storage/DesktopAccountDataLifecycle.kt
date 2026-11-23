package com.nuvio.app.core.storage

import co.touchlab.kermit.Logger
import com.nuvio.app.activeDownloadCount
import com.nuvio.app.QuitDecision
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthStorage
import com.nuvio.app.core.diagnostics.SentryInitializer
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.ServerDiscoveryPolicy
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.sync.SyncManager
import com.nuvio.app.features.discordrpc.DiscordPresenceManager
import com.nuvio.app.features.downloads.DesktopDownloads
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.flushDownloadProgressOnExit
import com.nuvio.app.features.downloads.DownloadsTrash
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.party.PartyCompanion
import com.nuvio.app.features.party.PartyRepository
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.quitDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.account_durability_flush_failed
import nuvio.composeapp.generated.resources.account_owner_record_unreadable
import nuvio.composeapp.generated.resources.account_start_fresh_session_clear_failed
import nuvio.composeapp.generated.resources.account_start_fresh_unresolved_moves
import nuvio.composeapp.generated.resources.auth_account_owner_conflict
import nuvio.composeapp.generated.resources.auth_account_owner_mismatch
import nuvio.composeapp.generated.resources.auth_closing_for_account_change
import nuvio.composeapp.generated.resources.quit_blocked_title
import nuvio.composeapp.generated.resources.quit_downloads_finishing_message
import nuvio.composeapp.generated.resources.quit_save_progress_failed_message
import nuvio.composeapp.generated.resources.quit_save_progress_failed_title
import org.jetbrains.compose.resources.getString
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

@Serializable
internal sealed interface StampOwner {
    @Serializable
    @SerialName("remote")
    data class Remote(
        val serverUrl: String,
        val userId: String,
    ) : StampOwner

    @Serializable
    @SerialName("adopting")
    data class AdoptingRemote(
        val serverUrl: String,
        val userId: String,
    ) : StampOwner

    @Serializable
    @SerialName("profile_user")
    data class ProfileUser(
        val userId: String,
    ) : StampOwner

    @Serializable
    @SerialName("conflict")
    data class Conflicted(
        val sessionUserId: String,
        val profileUserId: String,
    ) : StampOwner

    @Serializable
    @SerialName("vacant")
    data object Vacant : StampOwner

    @Serializable
    @SerialName("unknown")
    data object Unknown : StampOwner
}

internal sealed interface StampState {
    data object Missing : StampState
    data class Valid(val owner: StampOwner) : StampState
    data object Corrupt : StampState
}

@Serializable
internal enum class ArchiveStage {
    Staged,
    Archiving,
    Archived,
}

@Serializable
internal data class TransitionJournal(
    val version: Int = 1,
    val previousOwner: StampOwner,
    val nextOwner: StampOwner = StampOwner.Vacant,
    val archiveDirName: String? = null,
    val entries: List<String>? = null,
    val stage: ArchiveStage = ArchiveStage.Staged,
)

internal enum class ArchiveStep {
    AfterAdoptionStarted,
    AfterStageWritten,
    AfterArchiveDirCreated,
    AfterManifestWritten,
    AfterEachEntryMoved,
    AfterAllEntriesMoved,
    AfterOwnerStamped,
}

internal sealed interface DesktopLifecycleUiState {
    data object Ready : DesktopLifecycleUiState
    data class Blocked(val message: String) : DesktopLifecycleUiState
}

private sealed interface ProfileEvidence {
    data object Absent : ProfileEvidence
    data class User(val userId: String) : ProfileEvidence
    data object Unreadable : ProfileEvidence
}

internal object DesktopAccountDataLifecycle {
    private const val FallbackUnreadableMessage =
        "The account record in the app's data folder could not be read. Nothing was changed."
    private const val FallbackMismatchMessage =
        "The data on this Mac belongs to another account. Sign in with that account, or choose Start fresh."
    private const val FallbackUnresolvedMovesMessage =
        "An interrupted download folder move has not been settled yet. Open Downloads or connect its drive before starting fresh."
    private const val FallbackSessionClearFailedMessage =
        "Could not clear the saved sign-in on this Mac. Nothing was changed."
    private const val FallbackDurabilityFailedMessage =
        "The app's data folder could not be saved safely to disk. Nothing was changed."
    private const val FallbackQuitProgressFailedMessage =
        "Your latest watch progress could not be saved to disk, so the app stayed open. Check your disk space and try quitting again."
    private const val FallbackQuitProgressFailedTitle =
        "Could not save watch progress"
    private const val FallbackQuitBlockedTitle =
        "Could not close Nuvio"
    private const val FallbackQuitDownloadsFinishingMessage =
        "A download is still being checked and saved to disk, so the app stayed open. Wait a moment and try quitting again."
    private const val FallbackConflictMessage =
        "The saved sign-in and profile records on this Mac name different accounts. Choose Start fresh before signing in."
    private const val FallbackClosingForTransitionMessage =
        "The app is closing to finish an account change. Wait for it to close or cancel quitting first."

    private val log = Logger.withTag("DesktopAccountDataLifecycle")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val stateLock = Any()

    private val _uiState = MutableStateFlow<DesktopLifecycleUiState>(DesktopLifecycleUiState.Ready)
    val uiState: StateFlow<DesktopLifecycleUiState> = _uiState.asStateFlow()

    private val _quitError = MutableStateFlow<String?>(null)
    val quitError: StateFlow<String?> = _quitError.asStateFlow()

    private val _quitErrorTitle = MutableStateFlow<String?>(null)
    val quitErrorTitle: StateFlow<String?> = _quitErrorTitle.asStateFlow()

    @Volatile
    var didArchiveOnStartup: Boolean = false
        private set

    private var rootDirProvider: () -> Path = { DesktopStorage.rootDir }
    private var currentServerUrl: () -> String = { ServerConfigurationRepository.active.value.backendUrl }
    private var storedSessionUserId: suspend () -> String? = { SupabaseProvider.loadStoredSessionUserId() }
    private var nowEpochMs: () -> Long = { System.currentTimeMillis() }
    private var exitApp: () -> Unit = { exitProcess(0) }
    private var onArchiveStep: (ArchiveStep) -> Unit = {}
    private var syncDirectory: (Path) -> Boolean = ::defaultSyncDirectory
    private var moveCleanCheck: (Path) -> Boolean = DesktopDownloads::recoverAndVerifyMoveStateClean
    private var confirmQuitWithActiveDownloadsForTest: (Int) -> Boolean = { true }
    private var windowRequestQuitHandler: ((allowCancel: Boolean) -> Unit)? = null
    private var windowRequestQuitRegistrationToken: Any? = null

    private var pendingStartFreshPreviousOwner: StampOwner? = null
    private var pendingServerSwitchSave: (() -> Boolean)? = null
    private var activeQuitWaiter: CompletableDeferred<Result<LifecycleQuitOutcome>>? = null
    private var confirmedQuitScheduled: Boolean = false
    private var executingConfirmedQuit: Boolean = false
    // Set once the quit gates have passed. The exit callback returns before the process
    // is gone, and a second close must not run the gates again. Cleared only by a new launch.
    private var quitCommitted: Boolean = false
    private var transitionExitCommitted: Boolean = false

    private val handler = object : AccountDataLifecycleHandler {
        override fun isTransitionQuitPending(): Boolean =
            synchronized(stateLock) { isTransitionQuitPendingLocked() }

        override fun evaluateRemoteSignIn(candidate: AccountOwnerCandidate.Remote): RemoteSignInDecision =
            decideRemoteSignIn(candidate)

        override fun confirmRemoteAdoption(candidate: AccountOwnerCandidate.Remote): RemoteSignInDecision =
            adoptRemoteOwner(candidate)

        override suspend fun onSignOut() {
            SyncManager.cancelAccountSyncAndWait()
        }

        override suspend fun onLostSession() {
            SyncManager.cancelAccountSyncAndWait()
        }

        override suspend fun onPrepareForServerSwitch() {
            SyncManager.cancelAccountSyncAndWait()
        }

        override suspend fun switchDesktopServerAndQuit(saveConfiguration: () -> Boolean): Result<LifecycleQuitOutcome> =
            switchServerAndQuitInternal(saveConfiguration)

        override suspend fun precheckBeforeAccountDelete(): Result<Unit> =
            precheckDestructiveTransition()

        override suspend fun onAccountDeleted(): Result<AccountDeleteLifecycleOutcome> =
            startFreshInternal(allowCancel = false).map { outcome ->
                when (outcome) {
                    LifecycleQuitOutcome.Completed -> AccountDeleteLifecycleOutcome.ExitingProcess
                    LifecycleQuitOutcome.Cancelled -> AccountDeleteLifecycleOutcome.WipedInPlace
                }
            }

        override suspend fun startFresh(): Result<LifecycleQuitOutcome> =
            startFreshInternal(allowCancel = true)
    }

    private fun isTransitionQuitPendingLocked(): Boolean =
        quitCommitted ||
            transitionExitCommitted ||
            executingConfirmedQuit ||
            confirmedQuitScheduled ||
            pendingStartFreshPreviousOwner != null ||
            pendingServerSwitchSave != null ||
            activeQuitWaiter != null

    fun bootstrapAndInstall(
        rootDirProvider: () -> Path = { DesktopStorage.rootDir },
        currentServerUrl: () -> String = { ServerConfigurationRepository.active.value.backendUrl },
        storedSessionUserId: suspend () -> String? = { SupabaseProvider.loadStoredSessionUserId() },
        nowEpochMs: () -> Long = { System.currentTimeMillis() },
        exitApp: () -> Unit = { exitProcess(0) },
        onArchiveStep: (ArchiveStep) -> Unit = {},
        syncDirectory: (Path) -> Boolean = ::defaultSyncDirectory,
        moveCleanCheck: (Path) -> Boolean = DesktopDownloads::recoverAndVerifyMoveStateClean,
        confirmQuitWithActiveDownloadsForTest: (Int) -> Boolean = { true },
    ) {
        val orphanedWaiter = synchronized(stateLock) {
            this.rootDirProvider = rootDirProvider
            this.currentServerUrl = currentServerUrl
            this.storedSessionUserId = storedSessionUserId
            this.nowEpochMs = nowEpochMs
            this.exitApp = exitApp
            this.onArchiveStep = onArchiveStep
            this.syncDirectory = syncDirectory
            this.moveCleanCheck = moveCleanCheck
            this.confirmQuitWithActiveDownloadsForTest = confirmQuitWithActiveDownloadsForTest
            this.pendingStartFreshPreviousOwner = null
            this.pendingServerSwitchSave = null
            this.confirmedQuitScheduled = false
            this.executingConfirmedQuit = false
            this.quitCommitted = false
            this.transitionExitCommitted = false
            this.activeQuitWaiter.also { this.activeQuitWaiter = null }
        }
        orphanedWaiter?.complete(Result.success(LifecycleQuitOutcome.Cancelled))
        didArchiveOnStartup = false
        _quitError.value = null
        _quitErrorTitle.value = null
        AccountDataLifecycle.install(handler)
        runBlocking {
            bootstrap()
        }
    }

    internal fun registerWindowRequestQuitHandler(handler: (allowCancel: Boolean) -> Unit): () -> Unit {
        val token = Any()
        synchronized(stateLock) {
            windowRequestQuitHandler = handler
            windowRequestQuitRegistrationToken = token
        }
        return {
            val orphanedWaiter = synchronized(stateLock) {
                if (windowRequestQuitRegistrationToken !== token) {
                    null
                } else {
                    windowRequestQuitHandler = null
                    windowRequestQuitRegistrationToken = null
                    if (!quitCommitted && !executingConfirmedQuit && !confirmedQuitScheduled) {
                        activeQuitWaiter.also {
                            activeQuitWaiter = null
                            pendingStartFreshPreviousOwner = null
                            pendingServerSwitchSave = null
                        }
                    } else {
                        null
                    }
                }
            }
            orphanedWaiter?.complete(Result.success(LifecycleQuitOutcome.Cancelled))
        }
    }

    internal fun bindWindowRequestQuitHandler(handler: ((allowCancel: Boolean) -> Unit)?) {
        val orphanedWaiter = synchronized(stateLock) {
            windowRequestQuitHandler = handler
            windowRequestQuitRegistrationToken = if (handler != null) Any() else null
            if (handler == null && !quitCommitted && !executingConfirmedQuit && !confirmedQuitScheduled) {
                activeQuitWaiter.also {
                    activeQuitWaiter = null
                    pendingStartFreshPreviousOwner = null
                    pendingServerSwitchSave = null
                }
            } else {
                null
            }
        }
        orphanedWaiter?.complete(Result.success(LifecycleQuitOutcome.Cancelled))
    }

    internal fun tryScheduleConfirmedQuit(): Boolean = synchronized(stateLock) {
        if (quitCommitted || executingConfirmedQuit || confirmedQuitScheduled) {
            false
        } else {
            confirmedQuitScheduled = true
            true
        }
    }

    /** True after the quit gates have passed, including while native teardown is still running. */
    internal fun isConfirmedQuitLatched(): Boolean = synchronized(stateLock) { quitCommitted }

    internal fun clearQuitError() {
        _quitError.value = null
        _quitErrorTitle.value = null
    }

    internal suspend fun bootstrap() {
        ServerConfigurationRepository.reloadFromStorage()
        val root = synchronized(stateLock) { rootDirProvider() }
        Files.createDirectories(root)
        DesktopStorage.resetInMemoryStores()
        didArchiveOnStartup = false

        val transitionFile = root.resolve(DesktopStorage.TransitionJournalFileName)
        if (Files.exists(transitionFile, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(transitionFile) || !Files.isRegularFile(transitionFile, LinkOption.NOFOLLOW_LINKS)) {
                _uiState.value = DesktopLifecycleUiState.Blocked(unreadableRecordMessageAsync())
                return
            }
            val journal = readTransitionJournal(transitionFile)
            if (journal == null) {
                log.w { "Transition journal could not be parsed; moving it aside and keeping current folder active" }
                try {
                    retireUnreadableTransitionJournalOrThrow(root, transitionFile)
                } catch (ioe: IOException) {
                    log.e(ioe) { "Failed to move unreadable transition journal aside durably" }
                    _uiState.value = DesktopLifecycleUiState.Blocked(
                        ioe.message?.takeIf { it.isNotBlank() } ?: durabilityFailedMessageAsync(),
                    )
                    return
                }
            } else {
                if (journal.stage == ArchiveStage.Staged) {
                    val checkMoves = synchronized(stateLock) { moveCleanCheck }
                    if (!checkMoves(root)) {
                        _uiState.value = DesktopLifecycleUiState.Blocked(unresolvedMovesMessageAsync())
                        return
                    }
                }
                try {
                    recoverTransition(root, transitionFile, journal)
                    didArchiveOnStartup = true
                    _uiState.value = DesktopLifecycleUiState.Ready
                } catch (ioe: IOException) {
                    log.e(ioe) { "Failed to complete startup account archive durably" }
                    _uiState.value = DesktopLifecycleUiState.Blocked(
                        ioe.message?.takeIf { it.isNotBlank() } ?: durabilityFailedMessageAsync(),
                    )
                }
                return
            }
        }

        when (val stamp = readStamp(root)) {
            StampState.Corrupt -> {
                _uiState.value = DesktopLifecycleUiState.Blocked(unreadableRecordMessageAsync())
            }
            is StampState.Valid -> {
                if (stamp.owner is StampOwner.AdoptingRemote) {
                    try {
                        migrateProfilesForAdoptedOwnerOrThrow(root, stamp.owner.userId)
                        writeStamp(root, StampOwner.Remote(serverUrl = stamp.owner.serverUrl, userId = stamp.owner.userId))
                        _uiState.value = DesktopLifecycleUiState.Ready
                    } catch (ioe: IOException) {
                        log.e(ioe) { "Failed to finish interrupted profile adoption on startup" }
                        _uiState.value = DesktopLifecycleUiState.Blocked(
                            ioe.message?.takeIf { it.isNotBlank() } ?: durabilityFailedMessageAsync(),
                        )
                    }
                } else {
                    _uiState.value = DesktopLifecycleUiState.Ready
                }
            }
            StampState.Missing -> {
                val initialOwner = resolveInitialOwnerForUnstampedFolder(root)
                try {
                    writeStamp(root, initialOwner)
                    _uiState.value = DesktopLifecycleUiState.Ready
                } catch (ioe: IOException) {
                    log.e(ioe) { "Failed to write initial account owner stamp durably" }
                    _uiState.value = DesktopLifecycleUiState.Blocked(
                        ioe.message?.takeIf { it.isNotBlank() } ?: durabilityFailedMessageAsync(),
                    )
                }
            }
        }
    }

    private suspend fun resolveInitialOwnerForUnstampedFolder(root: Path): StampOwner {
        val hasRealData = listArchivableEntries(root).filterNot(::isIgnoredForEmptyFolderCheck).isNotEmpty()
        if (!hasRealData) return StampOwner.Vacant

        val serverUrl = canonicalServerUrlOrNull(currentServerUrl())
        val sessionUser = storedSessionUserId()?.trim()?.takeIf { it.isNotEmpty() }
        val savedAnon = AuthStorage.loadAnonymousUserId()?.trim()?.takeIf { it.isNotEmpty() }
        val profileEvidence = readStoredProfileEvidence(root, savedAnon)

        if (sessionUser != null) {
            if (profileEvidence is ProfileEvidence.User && profileEvidence.userId != sessionUser) {
                return StampOwner.Conflicted(
                    sessionUserId = sessionUser,
                    profileUserId = profileEvidence.userId,
                )
            }
            return if (serverUrl != null) {
                StampOwner.Remote(serverUrl = serverUrl, userId = sessionUser)
            } else {
                StampOwner.ProfileUser(userId = sessionUser)
            }
        }

        if (profileEvidence is ProfileEvidence.User) {
            // Stored profile payloads carry userId without serverUrl; bind userId now and verify server on sign-in.
            return StampOwner.ProfileUser(userId = profileEvidence.userId)
        }

        return StampOwner.Unknown
    }

    private fun decideRemoteSignIn(candidate: AccountOwnerCandidate.Remote): RemoteSignInDecision =
        synchronized(stateLock) {
            if (isTransitionQuitPendingLocked()) {
                return@synchronized RemoteSignInDecision.Refused(closingForTransitionMessage())
            }
            val currentUi = _uiState.value
            if (currentUi is DesktopLifecycleUiState.Blocked) {
                return@synchronized RemoteSignInDecision.Refused(currentUi.message)
            }

            val targetOwner = candidate.toStampOwner()
                ?: return@synchronized RemoteSignInDecision.Refused(unreadableRecordMessage())
            val root = rootDirProvider()

            when (val stamp = readStamp(root)) {
                StampState.Corrupt -> {
                    val message = unreadableRecordMessage()
                    _uiState.value = DesktopLifecycleUiState.Blocked(message)
                    RemoteSignInDecision.Refused(message)
                }
                StampState.Missing -> {
                    val nonAuthEntries = listArchivableEntries(root).filterNot(::isIgnoredForEmptyFolderCheck)
                    if (nonAuthEntries.isEmpty()) {
                        stampBeforeImportLocked(root, targetOwner)
                    } else {
                        RemoteSignInDecision.AskAdoption
                    }
                }
                is StampState.Valid -> {
                    when (val currentOwner = stamp.owner) {
                        targetOwner -> RemoteSignInDecision.Approved
                        is StampOwner.AdoptingRemote -> {
                            if (
                                currentOwner.serverUrl == targetOwner.serverUrl &&
                                currentOwner.userId == targetOwner.userId
                            ) {
                                completeAdoptionLocked(root, targetOwner)
                            } else {
                                RemoteSignInDecision.Mismatch(mismatchMessage())
                            }
                        }
                        is StampOwner.ProfileUser -> {
                            if (currentOwner.userId != targetOwner.userId) {
                                RemoteSignInDecision.Mismatch(mismatchMessage())
                            } else {
                                val nonAuthEntries = listArchivableEntries(root).filterNot(::isIgnoredForEmptyFolderCheck)
                                if (nonAuthEntries.isEmpty()) {
                                    stampBeforeImportLocked(root, targetOwner)
                                } else {
                                    RemoteSignInDecision.AskAdoption
                                }
                            }
                        }
                        is StampOwner.Conflicted -> RemoteSignInDecision.Refused(conflictMessage())
                        StampOwner.Vacant,
                        StampOwner.Unknown,
                        -> {
                            val nonAuthEntries = listArchivableEntries(root).filterNot(::isIgnoredForEmptyFolderCheck)
                            if (nonAuthEntries.isEmpty()) {
                                stampBeforeImportLocked(root, targetOwner)
                            } else {
                                RemoteSignInDecision.AskAdoption
                            }
                        }
                        is StampOwner.Remote -> RemoteSignInDecision.Mismatch(mismatchMessage())
                    }
                }
            }
        }

    private fun adoptRemoteOwner(candidate: AccountOwnerCandidate.Remote): RemoteSignInDecision =
        synchronized(stateLock) {
            if (isTransitionQuitPendingLocked()) {
                return@synchronized RemoteSignInDecision.Refused(closingForTransitionMessage())
            }
            val currentUi = _uiState.value
            if (currentUi is DesktopLifecycleUiState.Blocked) {
                return@synchronized RemoteSignInDecision.Refused(currentUi.message)
            }
            val targetOwner = candidate.toStampOwner()
                ?: return@synchronized RemoteSignInDecision.Refused(unreadableRecordMessage())
            val root = rootDirProvider()
            when (val currentStamp = readStamp(root)) {
                StampState.Corrupt -> {
                    val message = unreadableRecordMessage()
                    _uiState.value = DesktopLifecycleUiState.Blocked(message)
                    return@synchronized RemoteSignInDecision.Refused(message)
                }
                is StampState.Valid -> {
                    when (val currentOwner = currentStamp.owner) {
                        targetOwner -> return@synchronized RemoteSignInDecision.Approved
                        is StampOwner.Conflicted -> return@synchronized RemoteSignInDecision.Refused(conflictMessage())
                        is StampOwner.Remote -> return@synchronized RemoteSignInDecision.Mismatch(mismatchMessage())
                        is StampOwner.AdoptingRemote -> {
                            if (
                                currentOwner.serverUrl != targetOwner.serverUrl ||
                                currentOwner.userId != targetOwner.userId
                            ) {
                                return@synchronized RemoteSignInDecision.Mismatch(mismatchMessage())
                            }
                        }
                        is StampOwner.ProfileUser -> {
                            if (currentOwner.userId != targetOwner.userId) {
                                return@synchronized RemoteSignInDecision.Mismatch(mismatchMessage())
                            }
                        }
                        StampOwner.Vacant,
                        StampOwner.Unknown,
                        -> Unit
                    }
                }
                StampState.Missing -> Unit
            }
            completeAdoptionLocked(root, targetOwner)
        }

    private fun completeAdoptionLocked(root: Path, targetOwner: StampOwner.Remote): RemoteSignInDecision =
        try {
            writeStamp(root, StampOwner.AdoptingRemote(serverUrl = targetOwner.serverUrl, userId = targetOwner.userId))
            onArchiveStep(ArchiveStep.AfterAdoptionStarted)
            migrateProfilesForAdoptedOwnerOrThrow(root, targetOwner.userId)
            writeStamp(root, targetOwner)
            RemoteSignInDecision.Approved
        } catch (ioe: IOException) {
            log.e(ioe) { "Failed to complete account adoption and profile migration durably" }
            RemoteSignInDecision.Refused(durabilityFailedMessage())
        }

    private fun migrateProfilesForAdoptedOwnerOrThrow(root: Path, ownerUserId: String) {
        val trimmedOwner = ownerUserId.trim()
        if (trimmedOwner.isEmpty()) return
        if (root == DesktopStorage.rootDir) {
            ProfileRepository.adoptLocalProfilesForOwner(trimmedOwner)
        } else {
            rewriteProfileStoreOwnerIfPresent(root.resolve("nuvio_profiles.properties"), trimmedOwner)
        }
        val profilesFile = root.resolve("nuvio_profiles.properties")
        if (Files.isRegularFile(profilesFile, LinkOption.NOFOLLOW_LINKS)) {
            FileChannel.open(profilesFile, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                channel.force(true)
            }
            flushDirectoryOrThrow(root)
        }
    }

    private fun rewriteProfileStoreOwnerIfPresent(profilesFile: Path, ownerUserId: String) {
        if (!Files.isRegularFile(profilesFile, LinkOption.NOFOLLOW_LINKS)) return
        val store = DesktopStorage.Store(profilesFile)
        val raw = store.getString("profiles")?.trim().orEmpty()
        if (raw.isEmpty()) return
        val parsed = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val updatedEntries = parsed.toMutableMap()
        updatedEntries["userId"] = JsonPrimitive(ownerUserId)
        parsed["profiles"]?.let { profilesElem ->
            runCatching { profilesElem.jsonArray }.getOrNull()?.let { profilesArr ->
                updatedEntries["profiles"] = JsonArray(
                    profilesArr.map { item ->
                        val obj = runCatching { item.jsonObject }.getOrNull() ?: return@map item
                        val copy = obj.toMutableMap()
                        if ("user_id" in copy) copy["user_id"] = JsonPrimitive(ownerUserId)
                        if ("userId" in copy) copy["userId"] = JsonPrimitive(ownerUserId)
                        JsonObject(copy)
                    },
                )
            }
        }
        store.putString("profiles", json.encodeToString(JsonObject(updatedEntries)))
    }

    private fun stampBeforeImportLocked(root: Path, owner: StampOwner.Remote): RemoteSignInDecision =
        try {
            writeStamp(root, owner)
            RemoteSignInDecision.Approved
        } catch (ioe: IOException) {
            log.e(ioe) { "Failed to durably write account owner stamp before importing session" }
            RemoteSignInDecision.Refused(durabilityFailedMessage())
        }

    private suspend fun precheckDestructiveTransition(): Result<Unit> {
        val (root, checkMoves) = synchronized(stateLock) {
            val ui = _uiState.value
            if (ui is DesktopLifecycleUiState.Blocked) {
                return Result.failure(IllegalStateException(ui.message))
            }
            rootDirProvider() to moveCleanCheck
        }
        val stamp = withContext(Dispatchers.IO) { readStamp(root) }
        if (stamp is StampState.Corrupt) {
            val msg = unreadableRecordMessageAsync()
            _uiState.value = DesktopLifecycleUiState.Blocked(msg)
            return Result.failure(IllegalStateException(msg))
        }
        val movesClean = withContext(Dispatchers.IO) { checkMoves(root) }
        if (!movesClean) {
            return Result.failure(IllegalStateException(unresolvedMovesMessageAsync()))
        }
        return Result.success(Unit)
    }

    private suspend fun switchServerAndQuitInternal(saveConfiguration: () -> Boolean): Result<LifecycleQuitOutcome> {
        val root = synchronized(stateLock) {
            if (isTransitionQuitPendingLocked()) {
                return Result.failure(IllegalStateException(closingForTransitionMessage()))
            }
            val ui = _uiState.value
            if (ui is DesktopLifecycleUiState.Blocked) {
                return Result.failure(IllegalStateException(ui.message))
            }
            rootDirProvider()
        }
        val stamp = withContext(Dispatchers.IO) { readStamp(root) }
        if (stamp is StampState.Corrupt) {
            val msg = unreadableRecordMessageAsync()
            _uiState.value = DesktopLifecycleUiState.Blocked(msg)
            return Result.failure(IllegalStateException(msg))
        }
        synchronized(stateLock) {
            if (isTransitionQuitPendingLocked()) {
                return Result.failure(IllegalStateException(closingForTransitionMessage()))
            }
            pendingServerSwitchSave = saveConfiguration
        }
        return triggerQuit(allowCancel = true)
    }

    private suspend fun startFreshInternal(allowCancel: Boolean): Result<LifecycleQuitOutcome> {
        val (root, checkMoves) = synchronized(stateLock) {
            if (isTransitionQuitPendingLocked()) {
                return Result.failure(IllegalStateException(closingForTransitionMessage()))
            }
            val ui = _uiState.value
            if (ui is DesktopLifecycleUiState.Blocked) {
                return Result.failure(IllegalStateException(ui.message))
            }
            rootDirProvider() to moveCleanCheck
        }

        val previousOwner = when (val stamp = withContext(Dispatchers.IO) { readStamp(root) }) {
            StampState.Corrupt -> {
                val msg = unreadableRecordMessageAsync()
                _uiState.value = DesktopLifecycleUiState.Blocked(msg)
                return Result.failure(IllegalStateException(msg))
            }
            is StampState.Valid -> stamp.owner
            StampState.Missing -> StampOwner.Unknown
        }

        val movesClean = withContext(Dispatchers.IO) { checkMoves(root) }
        if (!movesClean) {
            return Result.failure(IllegalStateException(unresolvedMovesMessageAsync()))
        }

        synchronized(stateLock) {
            if (isTransitionQuitPendingLocked()) {
                return Result.failure(IllegalStateException(closingForTransitionMessage()))
            }
            pendingStartFreshPreviousOwner = previousOwner
        }
        return triggerQuit(allowCancel = allowCancel)
    }

    private suspend fun triggerQuit(allowCancel: Boolean): Result<LifecycleQuitOutcome> {
        val (windowHandler, waiter, fallbackPair) = synchronized(stateLock) {
            val wh = windowRequestQuitHandler
            if (wh != null) {
                val createdWaiter = CompletableDeferred<Result<LifecycleQuitOutcome>>()
                activeQuitWaiter = createdWaiter
                Triple(wh, createdWaiter, null)
            } else {
                Triple(null, null, confirmQuitWithActiveDownloadsForTest to exitApp)
            }
        }
        if (windowHandler != null && waiter != null) {
            try {
                windowHandler.invoke(allowCancel)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                cancelPendingQuitWithFailure(t)
                return Result.failure(t)
            }
            return waiter.await()
        }

        val (confirmDownloads, exit) = requireNotNull(fallbackPair)
        if (!allowCancel) {
            return executeConfirmedQuit(onExitReady = exit).map { LifecycleQuitOutcome.Completed }
        }
        return when (val decision = quitDecision(activeDownloadCount(DownloadsRepository.uiState.value.items))) {
            QuitDecision.QuitNow -> executeConfirmedQuit(onExitReady = exit).map { LifecycleQuitOutcome.Completed }
            is QuitDecision.AskFirst -> {
                if (confirmDownloads(decision.activeDownloads)) {
                    executeConfirmedQuit(onExitReady = exit).map { LifecycleQuitOutcome.Completed }
                } else {
                    cancelPendingQuit()
                    Result.success(LifecycleQuitOutcome.Cancelled)
                }
            }
        }
    }

    internal fun cancelPendingQuit() {
        val pending = synchronized(stateLock) {
            // A quit that already passed its gates is leaving. Keep open must not tear that down.
            if (quitCommitted) return@synchronized null
            val currentWaiter = activeQuitWaiter
            activeQuitWaiter = null
            confirmedQuitScheduled = false
            pendingStartFreshPreviousOwner = null
            pendingServerSwitchSave = null
            transitionExitCommitted = false
            rootDirProvider() to currentWaiter
        } ?: return
        val (root, waiter) = pending
        retireTransitionJournalFromRoot(root)
        waiter?.complete(Result.success(LifecycleQuitOutcome.Cancelled))
    }

    private fun cancelPendingQuitWithFailure(error: Throwable) {
        val pending = synchronized(stateLock) {
            if (quitCommitted) return@synchronized null
            val currentWaiter = activeQuitWaiter
            activeQuitWaiter = null
            confirmedQuitScheduled = false
            pendingStartFreshPreviousOwner = null
            pendingServerSwitchSave = null
            transitionExitCommitted = false
            rootDirProvider() to currentWaiter
        } ?: return
        val (root, waiter) = pending
        retireTransitionJournalFromRoot(root)
        waiter?.complete(Result.failure(error))
    }

    internal suspend fun executeConfirmedQuit(onExitReady: () -> Unit): Result<Unit> {
        val alreadyCommitted = synchronized(stateLock) {
            if (quitCommitted) {
                true
            } else {
                executingConfirmedQuit = true
                confirmedQuitScheduled = false
                false
            }
        }
        if (alreadyCommitted) return Result.success(Unit)
        var quitCompleted = false
        var stoppedDownloads: Set<String> = emptySet()
        var pendingWaiterToComplete: CompletableDeferred<Result<LifecycleQuitOutcome>>? = null
        var pendingWaiterOutcome: Result<LifecycleQuitOutcome>? = null

        fun takeAbortedWaiterAndClearPending(): Pair<Path, CompletableDeferred<Result<LifecycleQuitOutcome>>?> =
            synchronized(stateLock) {
                val currentWaiter = activeQuitWaiter
                activeQuitWaiter = null
                pendingStartFreshPreviousOwner = null
                pendingServerSwitchSave = null
                transitionExitCommitted = false
                rootDirProvider() to currentWaiter
            }

        try {
            _quitError.value = null
            _quitErrorTitle.value = null

            // 1. Stop producers before collecting and draining final local saves (off the UI thread).
            val transferStop = try {
                DownloadsRepository.stopActiveTransfersForQuit()
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Throwable) {
                log.e(err) { "Failed to stop active download transfers before quit; blocking exit" }
                val title = quitBlockedTitleAsync()
                val message = quitDownloadsFinishingMessageAsync()
                _quitErrorTitle.value = title
                _quitError.value = message
                val failure = IllegalStateException(message, err)
                val (abortedRoot, waiter) = takeAbortedWaiterAndClearPending()
                withContext(Dispatchers.IO) { retireTransitionJournalFromRoot(abortedRoot) }
                pendingWaiterToComplete = waiter
                pendingWaiterOutcome = Result.failure(failure)
                return Result.failure(failure)
            }
            stoppedDownloads = transferStop.stoppedDownloadIds
            if (!transferStop.allSettled) {
                log.w { "Timed out waiting for active or finishing download transfers to settle; blocking exit" }
                val title = quitBlockedTitleAsync()
                val message = quitDownloadsFinishingMessageAsync()
                _quitErrorTitle.value = title
                _quitError.value = message
                val failure = IllegalStateException(message)
                val (abortedRoot, waiter) = takeAbortedWaiterAndClearPending()
                withContext(Dispatchers.IO) { retireTransitionJournalFromRoot(abortedRoot) }
                pendingWaiterToComplete = waiter
                pendingWaiterOutcome = Result.failure(failure)
                return Result.failure(failure)
            }

            runCatching { SyncManager.cancelAccountSyncAndWait() }
            runCatching { PartyRepository.stopForSignOut() }
            runCatching { PartyCompanion.stop() }
            withContext(Dispatchers.IO) {
                runCatching { P2pStreamingEngine.shutdown() }
            }

            // 2 & 3. Stop playback, collect the active player's final progress save, and drain persistence.
            // Any failure during snapshot collection, synchronous save, or queued persistence blocks quit before touching auth or server config.
            try {
                withContext(Dispatchers.IO) {
                    WatchProgressRepository.flushActivePlayers()
                    WatchProgressRepository.drainPendingPersistence()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Throwable) {
                log.e(err) { "Failed to collect or drain pending watch progress before quit; blocking exit" }
                val title = quitProgressFailedTitleAsync()
                val message = quitProgressFailedMessageAsync()
                _quitErrorTitle.value = title
                _quitError.value = message
                val failure = IllegalStateException(message, err)
                val (abortedRoot, waiter) = takeAbortedWaiterAndClearPending()
                withContext(Dispatchers.IO) { retireTransitionJournalFromRoot(abortedRoot) }
                pendingWaiterToComplete = waiter
                pendingWaiterOutcome = Result.failure(failure)
                return Result.failure(failure)
            }

            val (root, startFreshOwner, serverSwitchSave, stepCallback) = synchronized(stateLock) {
                Quadruple(
                    rootDirProvider(),
                    pendingStartFreshPreviousOwner,
                    pendingServerSwitchSave,
                    onArchiveStep,
                )
            }

            // 4. Commit a staged desktop server switch only now that quit is confirmed and local saves succeeded.
            if (serverSwitchSave != null) {
                try {
                    withContext(Dispatchers.IO) {
                        AuthRepository.commitConfirmedDesktopServerSwitch(serverSwitchSave)
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (err: Throwable) {
                    log.e(err) { "Failed to commit staged server switch on confirmed quit; blocking exit" }
                    val title = quitBlockedTitleAsync()
                    val message = err.message?.takeIf { it.isNotBlank() } ?: sessionClearFailedMessageAsync()
                    _quitErrorTitle.value = title
                    _quitError.value = message
                    val (abortedRoot, waiter) = takeAbortedWaiterAndClearPending()
                    withContext(Dispatchers.IO) { retireTransitionJournalFromRoot(abortedRoot) }
                    pendingWaiterToComplete = waiter
                    pendingWaiterOutcome = Result.failure(err)
                    return Result.failure(err)
                }
            }

            // 5. Write the Start-fresh transition journal only now that quit is confirmed, saves succeeded, and session is verified null.
            if (startFreshOwner != null) {
                val clearedSnapshot = withContext(Dispatchers.IO) {
                    AuthRepository.clearSessionBeforeStartFreshJournal()
                }
                if (clearedSnapshot == null) {
                    val title = quitBlockedTitleAsync()
                    val message = sessionClearFailedMessageAsync()
                    _quitErrorTitle.value = title
                    _quitError.value = message
                    val failure = IllegalStateException(message)
                    val (abortedRoot, waiter) = takeAbortedWaiterAndClearPending()
                    withContext(Dispatchers.IO) { retireTransitionJournalFromRoot(abortedRoot) }
                    pendingWaiterToComplete = waiter
                    pendingWaiterOutcome = Result.failure(failure)
                    return Result.failure(failure)
                }
                val journal = TransitionJournal(
                    previousOwner = startFreshOwner,
                    nextOwner = StampOwner.Vacant,
                    stage = ArchiveStage.Staged,
                )
                try {
                    withContext(Dispatchers.IO) {
                        writeDurableJson(root.resolve(DesktopStorage.TransitionJournalFileName), journal)
                    }
                } catch (ioe: IOException) {
                    withContext(Dispatchers.IO) {
                        runCatching { AuthRepository.restoreAfterAbortedStartFreshJournal(clearedSnapshot) }
                    }
                    log.e(ioe) { "Failed to write Start-fresh transition journal durably; blocking exit" }
                    val title = quitBlockedTitleAsync()
                    val message = ioe.message?.takeIf { it.isNotBlank() } ?: durabilityFailedMessageAsync()
                    _quitErrorTitle.value = title
                    _quitError.value = message
                    val failure = IllegalStateException(message, ioe)
                    val (abortedRoot, waiter) = takeAbortedWaiterAndClearPending()
                    withContext(Dispatchers.IO) { retireTransitionJournalFromRoot(abortedRoot) }
                    pendingWaiterToComplete = waiter
                    pendingWaiterOutcome = Result.failure(failure)
                    return Result.failure(failure)
                }
                synchronized(stateLock) {
                    pendingStartFreshPreviousOwner = null
                }
                stepCallback(ArchiveStep.AfterStageWritten)
            }

            quitCompleted = true
            val waiter = synchronized(stateLock) {
                if (startFreshOwner != null || serverSwitchSave != null) {
                    transitionExitCommitted = true
                }
                pendingStartFreshPreviousOwner = null
                pendingServerSwitchSave = null
                activeQuitWaiter.also { activeQuitWaiter = null }
            }

            // The quit gates above have finished. Write the last download progress, then shut down, before waking waiters.
            withContext(Dispatchers.IO) {
                flushDownloadProgressOnExit()
                runCatching { DiscordPresenceManager.shutdown() }
                runCatching { SentryInitializer.close() }
            }
            pendingWaiterToComplete = waiter
            pendingWaiterOutcome = Result.success(LifecycleQuitOutcome.Completed)
            // The exit callback returns once native teardown has started, before the process is gone.
            // Stay closed across that gap. A callback that throws is a failed quit and can be retried.
            synchronized(stateLock) { quitCommitted = true }
            try {
                onExitReady()
            } catch (t: Throwable) {
                synchronized(stateLock) { quitCommitted = false }
                throw t
            }
            return Result.success(Unit)
        } catch (ce: CancellationException) {
            pendingWaiterToComplete = synchronized(stateLock) { activeQuitWaiter.also { activeQuitWaiter = null } }
            pendingWaiterOutcome = Result.failure(ce)
            throw ce
        } catch (unexpected: Throwable) {
            pendingWaiterToComplete = synchronized(stateLock) {
                pendingStartFreshPreviousOwner = null
                pendingServerSwitchSave = null
                activeQuitWaiter.also { activeQuitWaiter = null }
            }
            pendingWaiterOutcome = Result.failure(unexpected)
            throw unexpected
        } finally {
            if (!quitCompleted) {
                // Publications were refused for this attempt. The app is staying open, so accept them again.
                runCatching { DownloadsRepository.reopenFinishingAfterAbortedQuit() }
                if (stoppedDownloads.isNotEmpty()) {
                    runCatching { DownloadsRepository.restoreTransfersAfterAbortedQuit(stoppedDownloads) }
                }
                runCatching { WatchProgressRepository.unfreezeActivePlayersAfterAbortedQuit() }
            }
            synchronized(stateLock) {
                if (!quitCommitted) {
                    executingConfirmedQuit = false
                    confirmedQuitScheduled = false
                }
            }
            val outcome = pendingWaiterOutcome
            if (outcome != null) {
                pendingWaiterToComplete?.complete(outcome)
            }
        }
    }

    private fun recoverTransition(
        root: Path,
        transitionFile: Path,
        initialJournal: TransitionJournal,
    ) {
        val stepCallback = synchronized(stateLock) { onArchiveStep }
        var journal = initialJournal

        if (journal.stage == ArchiveStage.Staged || journal.archiveDirName.isNullOrBlank() || journal.entries == null) {
            val entriesToMove = listArchivableEntries(root)
            val archiveDir = createUniqueArchiveDirectory(
                root = root,
                owner = journal.previousOwner,
                timestampMs = synchronized(stateLock) { nowEpochMs() },
            )
            flushDirectoryOrThrow(root)
            stepCallback(ArchiveStep.AfterArchiveDirCreated)

            journal = journal.copy(
                archiveDirName = archiveDir.fileName.toString(),
                entries = entriesToMove,
                stage = ArchiveStage.Archiving,
            )
            writeDurableJson(transitionFile, journal)
            stepCallback(ArchiveStep.AfterManifestWritten)
        }

        val archiveDir = root.resolve(requireNotNull(journal.archiveDirName))
        if (!Files.exists(archiveDir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(archiveDir)
            flushDirectoryOrThrow(root)
        }

        if (journal.stage == ArchiveStage.Archiving) {
            val plannedEntries = journal.entries.orEmpty()
            val extraEntries = listArchivableEntries(root).filterNot { it in plannedEntries }
            for (entryName in plannedEntries + extraEntries) {
                val moved = moveEntryIntoArchive(
                    source = root.resolve(entryName),
                    destination = archiveDir.resolve(entryName),
                )
                if (moved) {
                    stepCallback(ArchiveStep.AfterEachEntryMoved)
                }
            }
            flushDirectoryOrThrow(archiveDir)
            flushDirectoryOrThrow(root)

            journal = journal.copy(stage = ArchiveStage.Archived)
            writeDurableJson(transitionFile, journal)
            stepCallback(ArchiveStep.AfterAllEntriesMoved)
        }

        DesktopStorage.resetInMemoryStores()

        writeStamp(root, journal.nextOwner)
        stepCallback(ArchiveStep.AfterOwnerStamped)

        val transitionTmp = root.resolve("${DesktopStorage.TransitionJournalFileName}.tmp")
        if (Files.exists(transitionTmp, LinkOption.NOFOLLOW_LINKS)) {
            moveEntryIntoArchive(transitionTmp, archiveDir.resolve("${DesktopStorage.TransitionJournalFileName}.tmp"))
        }
        moveEntryIntoArchive(transitionFile, archiveDir.resolve(DesktopStorage.TransitionJournalFileName))
        flushDirectoryOrThrow(archiveDir)
        flushDirectoryOrThrow(root)
    }

    private fun retireUnreadableTransitionJournalOrThrow(root: Path, transitionFile: Path) {
        val retiredDir = Files.createDirectories(root.resolve("cancelled-transitions"))
        val timestamp = synchronized(stateLock) { nowEpochMs() }
        val transitionTmp = root.resolve("${DesktopStorage.TransitionJournalFileName}.tmp")
        if (Files.exists(transitionTmp, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(transitionTmp)) {
            moveEntryIntoArchive(
                source = transitionTmp,
                destination = retiredDir.resolve("${transitionTmp.fileName}-corrupt-$timestamp"),
            )
        }
        moveEntryIntoArchive(
            source = transitionFile,
            destination = retiredDir.resolve("${transitionFile.fileName}-corrupt-$timestamp"),
        )
        flushDirectoryOrThrow(retiredDir)
        flushDirectoryOrThrow(root)
    }

    private fun retireTransitionJournalFromRoot(root: Path) {
        val files = listOf(
            root.resolve("${DesktopStorage.TransitionJournalFileName}.tmp"),
            root.resolve(DesktopStorage.TransitionJournalFileName),
        )
        for (file in files) {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue
            val trashed = runCatching { DownloadsTrash.moveToTrash(file) }.getOrDefault(false) &&
                !Files.exists(file, LinkOption.NOFOLLOW_LINKS)
            if (!trashed && Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                runCatching {
                    val retiredDir = Files.createDirectories(root.resolve("cancelled-transitions"))
                    moveEntryIntoArchive(
                        source = file,
                        destination = retiredDir.resolve("${file.fileName}-${nowEpochMs()}"),
                    )
                }
            }
        }
    }

    internal fun readStamp(root: Path): StampState {
        val stampFile = root.resolve(DesktopStorage.OwnerStampFileName)
        if (!Files.exists(stampFile, LinkOption.NOFOLLOW_LINKS)) return StampState.Missing
        if (Files.isSymbolicLink(stampFile) || !Files.isRegularFile(stampFile, LinkOption.NOFOLLOW_LINKS)) {
            return StampState.Corrupt
        }
        val raw = runCatching { Files.readString(stampFile) }.getOrNull()?.trim().orEmpty()
        if (raw.isEmpty()) return StampState.Corrupt
        val decoded = runCatching { json.decodeFromString<StampOwner>(raw) }.getOrNull() ?: return StampState.Corrupt
        return when (decoded) {
            is StampOwner.Remote -> {
                val canonicalUrl = canonicalServerUrlOrNull(decoded.serverUrl)
                val normalizedId = decoded.userId.trim()
                if (canonicalUrl == null || normalizedId.isEmpty()) {
                    StampState.Corrupt
                } else {
                    StampState.Valid(StampOwner.Remote(serverUrl = canonicalUrl, userId = normalizedId))
                }
            }
            is StampOwner.AdoptingRemote -> {
                val canonicalUrl = canonicalServerUrlOrNull(decoded.serverUrl)
                val normalizedId = decoded.userId.trim()
                if (canonicalUrl == null || normalizedId.isEmpty()) {
                    StampState.Corrupt
                } else {
                    StampState.Valid(StampOwner.AdoptingRemote(serverUrl = canonicalUrl, userId = normalizedId))
                }
            }
            is StampOwner.ProfileUser -> {
                val normalizedId = decoded.userId.trim()
                if (normalizedId.isEmpty()) {
                    StampState.Corrupt
                } else {
                    StampState.Valid(StampOwner.ProfileUser(userId = normalizedId))
                }
            }
            is StampOwner.Conflicted -> {
                val sId = decoded.sessionUserId.trim()
                val pId = decoded.profileUserId.trim()
                if (sId.isEmpty() || pId.isEmpty() || sId == pId) {
                    StampState.Corrupt
                } else {
                    StampState.Valid(StampOwner.Conflicted(sessionUserId = sId, profileUserId = pId))
                }
            }
            StampOwner.Vacant,
            StampOwner.Unknown,
            -> StampState.Valid(decoded)
        }
    }

    private fun readTransitionJournal(transitionFile: Path): TransitionJournal? {
        if (Files.isSymbolicLink(transitionFile) || !Files.isRegularFile(transitionFile, LinkOption.NOFOLLOW_LINKS)) {
            return null
        }
        val raw = runCatching { Files.readString(transitionFile) }.getOrNull()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching { json.decodeFromString<TransitionJournal>(raw) }.getOrNull()
    }

    private fun writeStamp(root: Path, owner: StampOwner) {
        writeDurableJson(root.resolve(DesktopStorage.OwnerStampFileName), owner)
    }

    private inline fun <reified T> writeDurableJson(target: Path, value: T) {
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        if (Files.isSymbolicLink(target) || Files.isSymbolicLink(tmp)) {
            throw IOException("Refusing to write lifecycle file through a symbolic link: $target")
        }
        val bytes = json.encodeToString(value).toByteArray(Charsets.UTF_8)
        FileChannel.open(
            tmp,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
            channel.force(true)
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
        flushDirectoryOrThrow(target.parent)
    }

    internal fun listArchivableEntries(root: Path): List<String> {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return Files.list(root).use { stream ->
            stream
                .map { it.fileName.toString() }
                .filter { !DesktopStorage.isExcludedFromAccountArchive(it) }
                .sorted()
                .toList()
        }
    }

    private fun createUniqueArchiveDirectory(
        root: Path,
        owner: StampOwner,
        timestampMs: Long,
    ): Path {
        val label = sanitizeArchiveOwnerLabel(owner)
        val baseName = "${DesktopStorage.ArchiveDirectoryPrefix}$label-$timestampMs"
        var attempt = 1
        while (true) {
            val folderName = if (attempt == 1) baseName else "$baseName-$attempt"
            val candidate = root.resolve(folderName)
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    return Files.createDirectory(candidate)
                } catch (_: FileAlreadyExistsException) {
                    // Try next suffix.
                }
            }
            attempt += 1
        }
    }

    private fun sanitizeArchiveOwnerLabel(owner: StampOwner): String {
        val raw = when (owner) {
            is StampOwner.Remote -> owner.userId
            is StampOwner.AdoptingRemote -> owner.userId
            is StampOwner.ProfileUser -> owner.userId
            is StampOwner.Conflicted -> "conflict"
            StampOwner.Vacant -> "vacant"
            StampOwner.Unknown -> "unknown"
        }
        val cleaned = raw
            .map { ch -> if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.') ch else '_' }
            .joinToString("")
            .trim('_', '.')
            .take(64)
        return cleaned.ifEmpty { "owner" }
    }

    private fun moveEntryIntoArchive(source: Path, destination: Path): Boolean {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return false
        var target = destination
        var suffix = 2
        while (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            target = destination.resolveSibling("${destination.fileName}-$suffix")
            suffix += 1
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
        return true
    }

    private fun readStoredProfileEvidence(root: Path, savedAnonId: String?): ProfileEvidence {
        val file = root.resolve("nuvio_profiles.properties")
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return ProfileEvidence.Absent
        val store = DesktopStorage.Store(file)
        if (!store.contains("profiles")) return ProfileEvidence.Absent
        val payload = store.getString("profiles")?.trim().orEmpty()
        if (payload.isEmpty()) return ProfileEvidence.Absent
        val parsedUserId = runCatching {
            json.parseToJsonElement(payload).jsonObject["userId"]?.jsonPrimitive?.contentOrNull?.trim()
        }.getOrNull()
        if (parsedUserId.isNullOrEmpty()) return ProfileEvidence.Unreadable
        if (savedAnonId != null && parsedUserId == savedAnonId) return ProfileEvidence.Absent
        return ProfileEvidence.User(parsedUserId)
    }

    private fun isIgnoredForEmptyFolderCheck(entryName: String): Boolean {
        if (entryName.endsWith(".lock") || entryName.endsWith(".tmp")) return true
        return nonUserAmbientStoreNames.any { base ->
            val props = "$base.properties"
            entryName == props || entryName == "$props.bak"
        }
    }

    private val nonUserAmbientStoreNames = setOf(
        "nuvio_auth",
        "nuvio_avatars",
        "nuvio_sync_client_identity",
    )

    private fun AccountOwnerCandidate.Remote.toStampOwner(): StampOwner.Remote? {
        val canonicalServer = canonicalServerUrlOrNull(serverUrl) ?: return null
        val normalizedUser = userId.trim()
        if (normalizedUser.isEmpty()) return null
        return StampOwner.Remote(serverUrl = canonicalServer, userId = normalizedUser)
    }

    private fun flushDirectoryOrThrow(directory: Path) {
        val flushFn = synchronized(stateLock) { syncDirectory }
        if (!flushFn(directory)) {
            throw IOException(durabilityFailedMessage())
        }
    }

    private fun defaultSyncDirectory(directory: Path): Boolean {
        if (DesktopHostOs.current == DesktopHostOs.WINDOWS) return true
        return try {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun unreadableRecordMessage(): String = runCatching {
        runBlocking { getString(Res.string.account_owner_record_unreadable) }
    }.getOrDefault(FallbackUnreadableMessage)

    private suspend fun unreadableRecordMessageAsync(): String = runCatching {
        getString(Res.string.account_owner_record_unreadable)
    }.getOrDefault(FallbackUnreadableMessage)

    private fun mismatchMessage(): String = runCatching {
        runBlocking { getString(Res.string.auth_account_owner_mismatch) }
    }.getOrDefault(FallbackMismatchMessage)

    private fun conflictMessage(): String = runCatching {
        runBlocking { getString(Res.string.auth_account_owner_conflict) }
    }.getOrDefault(FallbackConflictMessage)

    private fun closingForTransitionMessage(): String = runCatching {
        runBlocking { getString(Res.string.auth_closing_for_account_change) }
    }.getOrDefault(FallbackClosingForTransitionMessage)

    private suspend fun unresolvedMovesMessageAsync(): String = runCatching {
        getString(Res.string.account_start_fresh_unresolved_moves)
    }.getOrDefault(FallbackUnresolvedMovesMessage)

    private suspend fun sessionClearFailedMessageAsync(): String = runCatching {
        getString(Res.string.account_start_fresh_session_clear_failed)
    }.getOrDefault(FallbackSessionClearFailedMessage)

    private fun durabilityFailedMessage(): String = runCatching {
        runBlocking { getString(Res.string.account_durability_flush_failed) }
    }.getOrDefault(FallbackDurabilityFailedMessage)

    private suspend fun durabilityFailedMessageAsync(): String = runCatching {
        getString(Res.string.account_durability_flush_failed)
    }.getOrDefault(FallbackDurabilityFailedMessage)

    private suspend fun quitProgressFailedMessageAsync(): String = runCatching {
        getString(Res.string.quit_save_progress_failed_message)
    }.getOrDefault(FallbackQuitProgressFailedMessage)

    private suspend fun quitProgressFailedTitleAsync(): String = runCatching {
        getString(Res.string.quit_save_progress_failed_title)
    }.getOrDefault(FallbackQuitProgressFailedTitle)

    private suspend fun quitBlockedTitleAsync(): String = runCatching {
        getString(Res.string.quit_blocked_title)
    }.getOrDefault(FallbackQuitBlockedTitle)

    private suspend fun quitDownloadsFinishingMessageAsync(): String = runCatching {
        getString(Res.string.quit_downloads_finishing_message)
    }.getOrDefault(FallbackQuitDownloadsFinishingMessage)

    fun resetForTest() {
        val orphanedWaiter = synchronized(stateLock) {
            onArchiveStep = {}
            syncDirectory = ::defaultSyncDirectory
            moveCleanCheck = DesktopDownloads::recoverAndVerifyMoveStateClean
            confirmQuitWithActiveDownloadsForTest = { true }
            windowRequestQuitHandler = null
            windowRequestQuitRegistrationToken = null
            pendingStartFreshPreviousOwner = null
            pendingServerSwitchSave = null
            confirmedQuitScheduled = false
            executingConfirmedQuit = false
            quitCommitted = false
            transitionExitCommitted = false
            activeQuitWaiter.also { activeQuitWaiter = null }
        }
        orphanedWaiter?.complete(Result.success(LifecycleQuitOutcome.Cancelled))
        didArchiveOnStartup = false
        _uiState.value = DesktopLifecycleUiState.Ready
        _quitError.value = null
        _quitErrorTitle.value = null
        SyncManager.resetForTest()
        AccountDataLifecycle.resetToDefault()
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)

internal fun canonicalServerUrlOrNull(url: String): String? =
    ServerDiscoveryPolicy.canonicalBackendUrlOrNull(url)
