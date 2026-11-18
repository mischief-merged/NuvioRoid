package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Desktop
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.awt.EventQueue
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** What the folders need from the list of downloads. The app wires them to [DownloadsRepository]. */
internal interface DownloadsHooks {
    fun items(): List<DownloadItem>

    /** Pauses a running download and waits until it has stopped writing. False when it did not stop. */
    fun pauseAndWait(downloadId: String): Boolean
    fun resume(downloadId: String)
    fun resumeHeld()
    fun refreshOfflineIndex() = Unit

    /** The profile whose list [items] is. A move belongs to the one that started it. */
    fun profileId(): Int = 0

    /** Every record of every profile. Null when the list of any profile cannot be read. */
    fun allRecords(): List<RecordRef>? = items().map { RecordRef.of(profileId(), it) }

    /** Every record of every profile other than [profileId]. Null when another profile's list cannot be read. */
    fun otherProfileRecords(profileId: Int): List<RecordRef>? =
        allRecords()?.filter { it.profileId != profileId }

    /** The record [downloadId] in the list of [profileId], whichever profile is active. */
    fun recordOf(profileId: Int, downloadId: String): DownloadItem?

    /** Runs [block] holding the lock every save of a list takes, across processes. */
    fun <T> withListLock(block: () -> T): T = block()

    /** The one compare-and-relocate: see [DownloadsRepository.compareAndRelocate]. */
    fun compareAndRelocate(
        profileId: Int,
        downloadId: String,
        matches: (DownloadItem) -> Boolean,
        relocate: (DownloadItem) -> DownloadItem,
    ): RelocateResult
}

/** The stored lists of every profile, in the store the repository saves them in. */
internal object DesktopProfileLists : ProfileLists {
    private val store get() = DesktopStorage.store("nuvio_downloads")

    // Read again from the file under its lock: another copy of the app may have saved since.
    override fun load(profileId: Int): String? = store.getFreshString(ProfileScopedKey.of("downloads", profileId))

    override fun saveDurably(profileId: Int, payload: String): Boolean = store.exclusive {
        store.putString(ProfileScopedKey.of("downloads", profileId), payload)
        store.sync()
    }
}

/** The records of every profile's stored list. Null when one of them cannot be read. */
internal fun storedRecordsOfAllProfiles(
    store: DesktopStorage.Store,
    excludeProfileId: Int? = null,
): List<RecordRef>? {
    // As the file is now, read again under its lock.
    val entries = store.freshEntries()
    return entries.keys.mapNotNull { key ->
        key.removePrefix("downloads_")
            .takeIf { key.startsWith("downloads_") && it.isNotEmpty() && it.all(Char::isDigit) }
            ?.toIntOrNull()
            ?.takeIf { it != excludeProfileId }
            ?.let { it to key }
    }.flatMap { (profileId, key) ->
        val items = decodeStoredDownloads(entries[key].orEmpty()) ?: return null
        items.map { RecordRef.of(profileId, it) }
    }
}

internal object RepositoryDownloadsHooks : DownloadsHooks {
    override fun items(): List<DownloadItem> {
        DownloadsRepository.ensureLoaded()
        return DownloadsRepository.uiState.value.items
    }

    override fun pauseAndWait(downloadId: String): Boolean = DownloadsRepository.pauseAndWait(downloadId)
    override fun resume(downloadId: String) = DownloadsRepository.resumeDownload(downloadId)
    override fun resumeHeld() = DownloadsRepository.resumeHeldByFolder()
    override fun refreshOfflineIndex() = DownloadsRepository.refreshOfflineIndex()
    override fun profileId(): Int = ProfileRepository.activeProfileId

    override fun <T> withListLock(block: () -> T): T = DownloadsStorage.withListLock(block)

    // Without the repository's state lock: the mover asks this while it holds the list lock.
    override fun allRecords(): List<RecordRef>? {
        val loaded = DownloadsRepository.loadedProfile() ?: return null
        val inMemory = DownloadsRepository.uiState.value.items
        val stored = storedRecordsOfAllProfiles(DesktopStorage.store("nuvio_downloads"), excludeProfileId = loaded) ?: return null
        return inMemory.map { RecordRef.of(loaded, it) } + stored
    }

    override fun otherProfileRecords(profileId: Int): List<RecordRef>? =
        storedRecordsOfAllProfiles(DesktopStorage.store("nuvio_downloads"), excludeProfileId = profileId)

    override fun recordOf(profileId: Int, downloadId: String): DownloadItem? =
        DownloadsRepository.recordOf(profileId, downloadId, DesktopProfileLists)

    override fun compareAndRelocate(
        profileId: Int,
        downloadId: String,
        matches: (DownloadItem) -> Boolean,
        relocate: (DownloadItem) -> DownloadItem,
    ): RelocateResult = DownloadsRepository.compareAndRelocate(profileId, downloadId, DesktopProfileLists, matches, relocate)
}

/** The record is exactly the one a move planned for: same location, address, name and state. */
internal fun DownloadItem.isAsPlanned(item: MoveItem): Boolean =
    locationId == item.recordLocationId && localFileUri == item.recordUri && fileName == item.recordFileName &&
        (status == DownloadStatus.Completed) == item.completed

/** The desktop side of download locations: where files live, what is reachable, and moving. */
internal class DesktopDownloadFolders(
    val locations: DownloadLocations,
    private val validator: DownloadFolderValidator,
    private val mover: DownloadMover,
    private val hooks: DownloadsHooks = RepositoryDownloadsHooks,
    private val picker: () -> String? = { null },
    private val reveal: (Path) -> Boolean = ::openInFileManager,
    private val isMac: Boolean = false,
    private val onReturned: () -> Unit = {},
    /** Whether the player has this download open. A playing download is not moved. */
    private val isPlaying: (DownloadItem) -> Boolean = { false },
    internal val usage: DownloadStorageUsage = DownloadStorageUsage(locations),
    /** How long a move waits for a writer that was stopped and is still closing its file. */
    private val writerWaitMs: Long = 30_000L,
    /** Sends one file or folder inside a location to the Trash. Never deletes. */
    private val trashPath: (Path) -> Boolean = DownloadsTrash::moveToTrash,
    /** The native file picker. Blocks until it closes. */
    private val filePicker: () -> String? = { null },
    /** The usable space of the volume of a folder. Replaced in tests, where the real one changes between two reads. */
    private val freeSpaceOf: (File) -> Long? = { f -> f.usableSpace.takeIf { it > 0L || f.totalSpace > 0L } },
    /** The underlying file store identifier of a folder, used to deduplicate volumes. */
    private val fileStoreKeyOf: (Path) -> String = { path -> Files.getFileStore(path).toString() },
) : DownloadFolderController {
    override val supported: Boolean = true

    private val _state = MutableStateFlow(DownloadFolderState(isMac = isMac))
    override val state: StateFlow<DownloadFolderState> = _state.asStateFlow()

    private val _progress = MutableStateFlow<MoveProgress?>(null)
    override val progress: StateFlow<MoveProgress?> = _progress.asStateFlow()

    @Volatile private var movingNow: Set<String> = emptySet()
    private val cancelRequested = AtomicBoolean(false)

    internal fun moveToTrash(path: Path): Boolean = trashPath(path)
    private val moveRunning = AtomicBoolean(false)
    private val lastAvailability = java.util.concurrent.ConcurrentHashMap<String, FolderAvailability>()
    private val pollLock = Any()
    private val watchers = AtomicInteger(0)
    private val usageWatched = AtomicBoolean(false)
    private val recoveryDone = CountDownLatch(1)
    private var seenSignature = false
    private var lastSignature: List<Pair<String, DownloadStatus>> = emptyList()
    private val snapshotLock = Any()
    private var lastLocationSnapshot: Map<String, Pair<FolderAvailability, String>>? = null
    private var monitor: Thread? = null
    @Volatile private var stopped = false

    /** The thread that settles a cut-short move. Null until [start] has begun it. */
    @Volatile
    internal var recoveryRunner: Thread? = null
        private set

    override val isMoveRunning: Boolean get() = moveRunning.get()

    override fun pickFolder(): String? = picker()

    override fun check(path: String, requiredBytes: Long): FolderCheck = validator.check(path, requiredBytes)

    override fun freeBytesAt(path: String): Long? =
        runCatching { Paths.get(path) }.getOrNull()?.let(::existingAncestor)
            ?.let { dir -> FsGuard.call(3_000L, null) { DownloadFolderValidator.freeBytes(dir) } }

    override fun appFolderPath(): String = locations.appLocation().path.toString()

    override fun locationIdOf(item: DownloadItem): String = locations.locationOfRecord(item.locationId, item.localFileUri).id

    override fun lookup(item: DownloadItem): FileLookup = locations.locate(item.locationId, item.localFileUri, item.fileName)

    override fun isConnected(item: DownloadItem): Boolean {
        val owner = locations.ownerOf(item.locationId, item.localFileUri) ?: return false
        return locations.availability(owner) == FolderAvailability.Available
    }

    override fun locationConnected(locationId: String?): Boolean =
        locations.location(locationId)?.let { locations.availability(it) == FolderAvailability.Available } ?: false

    override fun heldByFolder(item: DownloadItem): Boolean =
        ItemLocks.isHeld(item.id) || WriterTokens.kindOf(item.id) == WriterTokens.Kind.Mover || !isConnected(item)

    override fun placementFor(items: List<DownloadItem>, parentMetaId: String, isEpisode: Boolean): String =
        placementForNewDownload(items, parentMetaId, isEpisode, locations.defaultLocation().id) { locationIdOf(it) }

    /**
     * The partial file of this download, in its own location only, goes to the Trash, never deleted outright;
     * only when no record of any profile other than this one uses it. A link is never followed.
     */
    override fun removeActivePartialOf(item: DownloadItem): Boolean {
        val owner = locations.ownerOf(item.locationId, item.localFileUri) ?: return false
        if (locations.availability(owner) != FolderAvailability.Available) return false
        return trashOwnedPartial(owner, item, item.activePartialFileName, item.partialFileKey)
    }

    override fun removePartialOf(item: DownloadItem): Boolean {
        val owner = locations.ownerOf(item.locationId, item.localFileUri) ?: return false
        if (locations.availability(owner) != FolderAvailability.Available) return false
        var allRemoved = trashOwnedPartial(owner, item, item.activePartialFileName, item.partialFileKey)
        item.supersededPartials.forEach { kept ->
            val keptOwner = locations.ownerOf(kept.locationId ?: item.locationId, null)
            val removed = keptOwner != null &&
                locations.availability(keptOwner) == FolderAvailability.Available &&
                trashOwnedPartial(keptOwner, item, kept.fileName, kept.partialFileKey)
            if (!removed) allRemoved = false
        }
        return allRemoved
    }

    private fun trashOwnedPartial(
        owner: DesktopDownloadLocation,
        item: DownloadItem,
        fileName: String,
        expectedKey: String?,
    ): Boolean {
        val partial = owner.path.resolve(fileName)
        if (!Files.exists(partial, LinkOption.NOFOLLOW_LINKS)) return true
        if (Files.isSymbolicLink(partial)) return false
        // Only the partial file this download created, proven by the identity it recorded then.
        if (expectedKey == null || DownloadMover.identityOf(partial) != expectedKey) return false
        if (ownershipOfPath(item, partial) != FileOwnership.Free) return false
        return runCatching { trashPath(partial) }.getOrDefault(false) && !Files.exists(partial, LinkOption.NOFOLLOW_LINKS)
    }

    private fun ownershipOfPath(item: DownloadItem, path: Path): FileOwnership {
        val all = hooks.allRecords() ?: return FileOwnership.Unknown
        val ownership = DownloadOwnership(locations, all)
        val profile = hooks.profileId()
        val sidecar = path.resolveSibling(DownloadSidecarFiles.sidecarFolderName(path.fileName.toString()))
        return if (ownership.otherOwner(profile, item.id, path) != null || ownership.otherOwner(profile, item.id, sidecar) != null) {
            FileOwnership.Owned
        } else {
            FileOwnership.Free
        }
    }

    override fun ownershipOf(item: DownloadItem, fileUri: String): FileOwnership =
        DownloadLocations.fileOfUri(fileUri)?.toPath()?.let { ownershipOfPath(item, it) } ?: FileOwnership.Unknown

    /** A cancelled download's file and its sidecar folder go to the Trash. True when the file is out of its place. */
    override fun discardFile(localFileUri: String): Boolean {
        val file = DownloadLocations.fileOfUri(localFileUri)?.toPath() ?: return false
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return true
        if (Files.isSymbolicLink(file)) return false
        if (!(runCatching { trashPath(file) }.getOrDefault(false) && !Files.exists(file, LinkOption.NOFOLLOW_LINKS))) return false
        file.resolveSibling(DownloadSidecarFiles.sidecarFolderName(file.fileName.toString()))
            .takeIf { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }?.let { runCatching { trashPath(it) } }
        return true
    }

    override fun setDefault(path: String): Boolean {
        val target = runCatching { Paths.get(path.trim()) }.getOrNull() ?: return false
        if (!Files.isDirectory(target)) return false
        // A folder whose marker name is taken by something that is not ours is refused, and nothing is written.
        if (locations.registerChecked(target) == null) return false
        locations.setDefault(target)
        refresh()
        return true
    }

    /** The room for new downloads of [item]: the free space of the volume of its location. */
    fun freeBytesOf(item: DownloadItem): Long? {
        val location = locations.locationOfRecord(item.locationId, item.localFileUri)
        return existingAncestor(location.path)?.let { dir ->
            FsGuard.call(3_000L, null) {
                freeSpaceOf(File(dir.toUri()))
            }
        }
    }

    private fun existingAncestor(path: Path): Path? {
        var current: Path? = path.toAbsolutePath().normalize()
        while (current != null && !Files.exists(current)) current = current.parent
        return current
    }

    override fun setUsageWatched(watched: Boolean) {
        val count = if (watched) watchers.incrementAndGet() else watchers.updateAndGet { (it - 1).coerceAtLeast(0) }
        usageWatched.set(count > 0)
    }

    override fun refresh() = synchronized(pollLock) { refreshLocked() }

    private fun refreshLocked() {
        // A drive that came back under another name is found by the id in its marker.
        locations.all().filter { it.id != DownloadLocations.AppId }.forEach { location ->
            if (locations.availability(location) != FolderAvailability.Available) locations.rediscover(location)
        }
        val items = hooks.items()
        val byLocation = items.groupBy { locationIdOf(it) }
        val defaultId = locations.defaultLocation().id
        val states = locations.all().associate { it.id to locations.availability(it) }
        val measured = usage.current(items, states, watching = usageWatched.get())
        val infos = locations.all().map { location ->
            val availability = states.getValue(location.id)
            val members = byLocation[location.id].orEmpty()
            val used = measured.perLocation[location.id]
            DownloadLocationInfo(
                id = location.id,
                path = location.path.toString(),
                name = location.path.fileName?.toString() ?: location.path.toString(),
                isDefault = location.id == defaultId,
                isAppFolder = location.id == DownloadLocations.AppId,
                availability = availability,
                itemCount = members.size,
                totalBytes = members.sumOf { it.moveBytes },
                usedBytes = used?.usedBytes ?: members.sumOf { it.moveBytes },
                usedIsLastKnown = used?.lastKnown ?: false,
                volumeKey = if (availability == FolderAvailability.Available) {
                    FsGuard.call(3_000L, location.id) {
                        runCatching { fileStoreKeyOf(location.path) }.getOrDefault(location.id)
                    }
                } else {
                    location.id
                },
                freeBytes = if (availability == FolderAvailability.Available) {
                    FsGuard.call(3_000L, null) { DownloadFolderValidator.freeBytes(location.path) }
                } else {
                    null
                },
            )
        }
        // Each volume counted once, so two folders on one drive do not count its free space twice.
        val freeTotal = infos.filter { it.availability == FolderAvailability.Available && it.freeBytes != null }
            .distinctBy { it.volumeKey }
            .sumOf { it.freeBytes ?: 0L }
            .takeIf { infos.any { it.availability == FolderAvailability.Available && it.freeBytes != null } }
        _state.value = DownloadFolderState(
            infos, isMac, moving = moveRunning.get(), movingIds = movingNow,
            autoDownloadUsedBytes = measured.autoDownloadBytes, totalFreeBytes = freeTotal, usageMeasured = true,
            leftovers = runCatching { mover.leftovers() }.getOrDefault(emptyList()),
        )
        if (noteAvailability(states)) {
            hooks.resumeHeld()
            onReturned()
        }
        noteLocationSnapshot(infos.associate { it.id to (it.availability to it.path) })
    }

    /**
     * One pass of the location watcher. While nobody is looking at sizes, this only checks that each
     * folder can be reached. Sizes are measured when a transfer's status changes, or while a screen
     * is showing them.
     */
    internal fun poll() = synchronized(pollLock) {
        if (!moveRunning.get() && mover.unresolvedJournals().isNotEmpty()) runCatching { mover.recover() }
        val items = runCatching { hooks.items() }.getOrDefault(emptyList())
        val signature = items.map { it.id to it.status }
        val changed = seenSignature && signature != lastSignature
        seenSignature = true
        lastSignature = signature
        if (changed) usage.invalidate()
        if (usageWatched.get() || changed) refreshLocked() else noteReachability()
    }

    /** Whether each folder can be used, without listing what is inside it. */
    private fun noteReachability() {
        locations.all().filter { it.id != DownloadLocations.AppId }.forEach { location ->
            if (locations.availability(location) != FolderAvailability.Available) locations.rediscover(location)
        }
        val allLocations = locations.all()
        val states = allLocations.associate { it.id to locations.availability(it) }
        val current = _state.value
        if (current.locations.isNotEmpty()) {
            _state.value = current.copy(
                locations = current.locations.map { info ->
                    val availability = states[info.id] ?: info.availability
                    info.copy(
                        availability = availability,
                        freeBytes = if (availability == FolderAvailability.Available) info.freeBytes else null,
                    )
                },
            )
        }
        if (noteAvailability(states)) {
            hooks.resumeHeld()
            onReturned()
        }
        noteLocationSnapshot(allLocations.associate { it.id to (states.getValue(it.id) to it.path.toString()) })
    }

    private fun noteLocationSnapshot(snapshot: Map<String, Pair<FolderAvailability, String>>) {
        val snapshotChanged = synchronized(snapshotLock) {
            val changed = lastLocationSnapshot != snapshot
            lastLocationSnapshot = snapshot
            changed
        }
        if (snapshotChanged) {
            hooks.refreshOfflineIndex()
        }
    }

    /** True when a folder that was away can be used again. */
    private fun noteAvailability(states: Map<String, FolderAvailability>): Boolean {
        lastAvailability.keys.retainAll(states.keys)
        var returned = false
        states.forEach { (id, availability) ->
            val before = lastAvailability.put(id, availability)
            if (before != null && before != FolderAvailability.Available && availability == FolderAvailability.Available) returned = true
        }
        return returned
    }

    /**
     * Checks every few seconds, off the interface thread. The first thing it does is settle a cut-short move.
     * [afterRecovery] runs on that same thread once the move is settled, before the watcher loops.
     */
    fun start(afterRecovery: () -> Unit = {}) {
        if (monitor != null) return
        stopped = false
        monitor = Thread({
            recoveryRunner = Thread.currentThread()
            try {
                runCatching { recoverInterrupted() }
            } finally {
                recoveryDone.countDown()
            }
            runCatching { afterRecovery() }
            while (!stopped && !Thread.currentThread().isInterrupted) {
                runCatching { poll() }.onFailure { if (it is InterruptedException) return@Thread }
                try {
                    Thread.sleep(CheckIntervalMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "download-locations").apply {
            isDaemon = true
            start()
        }
    }

    /** Blocks until the opening recovery has finished. [start] counts that down even when there was nothing to settle. */
    internal fun awaitRecovery() {
        recoveryDone.await()
    }

    /** Stops the watcher. Tests use this so the thread does not keep running. */
    internal fun stop() {
        stopped = true
        val running = monitor ?: return
        running.interrupt()
        running.join(2_000)
        monitor = null
    }

    /** At start: settles an interrupted move. It touches no user file and changes no record. */
    fun recoverInterrupted(): RecoveryReport? = if (mover.hasJournals()) mover.recover() else null

    private class Planned(val items: List<MoveItem>, val unreachable: Int, val failures: List<MoveFailure>)

    private fun rel(root: Path, path: Path): String = root.relativize(path).toString()

    /**
     * What to move for each item, by its record and nothing else. With [ownership], a path of the item, or
     * a destination path, that another record of any profile owns stops that item; without it (null) every
     * item stops, unless [forSizeOnly].
     */
    private fun plan(
        ready: List<DownloadItem>,
        destination: DesktopDownloadLocation,
        ownership: DownloadOwnership?,
        profile: Int,
        forSizeOnly: Boolean = false,
    ): Planned {
        val moveItems = mutableListOf<MoveItem>()
        val failures = mutableListOf<MoveFailure>()
        var unreachable = 0
        fun planOne(item: DownloadItem) {
            // Its final file must not be planned as a partial file or assigned a different location.
            if (item.awaitingCheck && item.localFileUri != null) {
                failures += MoveFailure(item.fileName, MoveFailureReason.Busy)
                return
            }
            if (ownership == null && !forSizeOnly) {
                failures += MoveFailure(item.fileName, MoveFailureReason.OwnedByAnother, "the downloads of another profile could not be read")
                return
            }
            fun ownedBy(paths: List<Path>): String? = ownership?.let { o -> paths.firstNotNullOfOrNull { o.otherOwner(profile, item.id, it)?.label } }
            if (item.status == DownloadStatus.Completed) {
                when (val found = locations.locate(item.locationId, item.localFileUri, item.fileName)) {
                    is FileLookup.Found -> if (found.locationId != destination.id) {
                        val video = DownloadLocations.fileOfUri(found.uri)?.toPath() ?: return
                        val root = locations.location(found.locationId)?.path ?: return
                        val companions = DownloadSidecarFiles.of(video)
                        if (Files.isSymbolicLink(video) || companions.skippedLinks.isNotEmpty()) {
                            failures += MoveFailure(item.fileName, MoveFailureReason.Symlink)
                            return
                        }
                        val sources = listOf(video) + companions.files.map { it.path }
                        val targets = listOf(destination.path.resolve(video.fileName.toString())) + companions.files.map { destination.path.resolve(it.relativeName) }
                        ownedBy(sources + targets)?.let { other ->
                            failures += MoveFailure(item.fileName, MoveFailureReason.OwnedByAnother, other)
                            return
                        }
                        val entries = listOf(
                            MoveEntry(found.locationId.orEmpty(), rel(root, video), destination.id, video.fileName.toString(), MoveKind.File, Files.size(video)),
                        ) + companions.files.map { companion ->
                            MoveEntry(found.locationId.orEmpty(), rel(root, companion.path), destination.id, companion.relativeName, MoveKind.Companion, Files.size(companion.path))
                        }
                        val newUri = destination.path.resolve(video.fileName.toString()).toFile().toURI().toString()
                        moveItems += MoveItem(profile, item.id, item.locationId, item.localFileUri, item.fileName, true, newUri, entries)
                    }
                    is FileLookup.Unreachable -> unreachable++
                    // A link at the recorded address is never followed or moved; anything else missing cannot be read.
                    FileLookup.Missing -> failures += MoveFailure(
                        item.fileName,
                        if (item.localFileUri?.let(DownloadLocations::fileOfUri)?.toPath()?.let(Files::isSymbolicLink) == true) MoveFailureReason.Symlink else MoveFailureReason.CouldNotRead,
                    )
                }
            } else {
                val owner = locations.ownerOf(item.locationId, item.localFileUri)
                when {
                    owner == null || locations.availability(owner) != FolderAvailability.Available -> unreachable++
                    owner.id == destination.id -> Unit
                    else -> {
                        val partialName = item.activePartialFileName
                        val partial = owner.path.resolve(partialName)
                        val targets = listOf(destination.path.resolve(partialName), destination.path.resolve(item.fileName))
                        val other = ownedBy(listOf(partial) + targets)
                        when {
                            other != null -> failures += MoveFailure(item.fileName, MoveFailureReason.OwnedByAnother, other)
                            Files.isSymbolicLink(partial) -> failures += MoveFailure(item.fileName, MoveFailureReason.Symlink)
                            // The download will write its final name there when it finishes: that name must be free.
                            targets.any { Files.exists(it, LinkOption.NOFOLLOW_LINKS) } -> failures += MoveFailure(item.fileName, MoveFailureReason.NameTaken)
                            else -> {
                                val entries = if (Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS)) {
                                    listOf(MoveEntry(owner.id, partialName, destination.id, partialName, MoveKind.Partial, Files.size(partial)))
                                } else {
                                    emptyList()
                                }
                                moveItems += MoveItem(profile, item.id, item.locationId, item.localFileUri, item.fileName, false, null, entries)
                            }
                        }
                    }
                }
            }
        }
        ready.forEach { item ->
            // A file that goes away while it is looked at (a delete of an item no move holds) is reported, not thrown.
            try {
                planOne(item)
            } catch (_: java.io.IOException) {
                failures += MoveFailure(item.fileName, MoveFailureReason.CouldNotRead)
            }
        }
        return Planned(moveItems, unreachable, failures)
    }

    /** Every byte a move copies, on every volume: there is no rename, and the originals stay in the Trash until it is emptied. */
    override fun bytesToCopy(itemIds: Set<String>, destinationPath: String): Long {
        val destination = runCatching { Paths.get(destinationPath).toAbsolutePath().normalize() }.getOrNull() ?: return 0L
        val known = locations.all().firstOrNull { DownloadLocations.samePath(it.path, destination) } ?: DesktopDownloadLocation("?", destination)
        val items = hooks.items().filter { it.id in itemIds }
        return plan(items, known, null, hooks.profileId(), forSizeOnly = true).items.flatMap { it.entries }.sumOf { it.size }
    }

    private fun acquireWriterToken(id: String): Boolean {
        var waited = 0L
        while (!WriterTokens.tryAcquire(id, WriterTokens.Kind.Mover)) {
            if (waited >= writerWaitMs) return false
            Thread.sleep(10)
            waited += 10
        }
        return true
    }

    /** What the mover asks of the lists: answered from each item's own profile, never from the active one. */
    internal val records: MoveRecords = object : MoveRecords {
        override fun relocate(item: MoveItem, destinationId: String): RelocateResult =
            hooks.compareAndRelocate(item.profileId, item.itemId, { it.isAsPlanned(item) }) { record ->
                val keptLocationFallback = record.locationId ?: DownloadLocations.AppId
                record.copy(
                    locationId = destinationId,
                    localFileUri = if (record.status == DownloadStatus.Completed) item.newUri else record.localFileUri,
                    // A moved partial file is a new file: its identity is recorded with the same step, so the download
                    // goes on from it instead of taking it for someone else's.
                    partialFileKey = if (record.status == DownloadStatus.Completed) {
                        record.partialFileKey
                    } else {
                        item.main?.takeIf { it.kind == MoveKind.Partial }?.let { it.finalKey ?: it.tempKey } ?: record.partialFileKey
                    },
                    supersededPartials = if (record.status == DownloadStatus.Completed || record.supersededPartials.isEmpty()) {
                        record.supersededPartials
                    } else {
                        record.supersededPartials.map { kept ->
                            if (kept.locationId == null) kept.copy(locationId = keptLocationFallback) else kept
                        }
                    },
                )
            }

        override fun sideOf(item: MoveItem): RecordSide {
            val record = hooks.recordOf(item.profileId, item.itemId) ?: return RecordSide.Missing
            val main = item.main ?: return RecordSide.Missing
            return when {
                record.isAsPlanned(item) -> RecordSide.AtSource
                record.locationId == main.dstLocationId && record.fileName == item.recordFileName &&
                    (record.status == DownloadStatus.Completed) == item.completed &&
                    (!item.completed || record.localFileUri == item.newUri) -> RecordSide.AtDestination
                else -> RecordSide.Missing
            }
        }

        override fun <T> underListLock(block: () -> T): T = hooks.withListLock(block)

        override fun otherOwner(item: MoveItem, path: Path): String? {
            val all = hooks.allRecords() ?: return "the downloads of another profile could not be read"
            return DownloadOwnership(locations, all).otherOwner(item.profileId, item.itemId, path)?.label
        }
    }

    override fun move(itemIds: Set<String>, destinationPath: String): MoveReport {
        val path = runCatching { Paths.get(destinationPath.trim()) }.getOrNull()
        val name = path?.fileName?.toString() ?: destinationPath
        if (path == null) return MoveReport(destinationPath, name, 0, problem = FolderProblem.NotAFolder)
        // The same checks as before the question was asked: a folder inside a download folder, the Trash,
        // a drive that is not there, no room.
        when (val check = validator.check(destinationPath, bytesToCopy(itemIds, destinationPath))) {
            is FolderCheck.Problem -> return MoveReport(destinationPath, name, 0, problem = check.kind)
            FolderCheck.Ok -> Unit
        }
        if (!moveRunning.compareAndSet(false, true)) return MoveReport(destinationPath, name, 0, problem = FolderProblem.MoveInProgress)
        // From this moment switching profile is refused.
        _state.value = _state.value.copy(moving = true)
        cancelRequested.set(false)
        val startedProfile = hooks.profileId()
        val locked = mutableListOf<String>()
        val tokens = mutableListOf<String>()
        val paused = mutableListOf<String>()
        val prefailed = mutableListOf<MoveFailure>()
        try {
            if (mover.unresolvedJournals().isNotEmpty()) return MoveReport(destinationPath, name, 0, problem = FolderProblem.PreviousMoveUnresolved)
            val destination = locations.registerChecked(path)
                ?: return MoveReport(destinationPath, name, 0, problem = FolderProblem.MarkerTaken)
            if (locations.availability(destination) != FolderAvailability.Available) {
                return MoveReport(destinationPath, name, 0, problem = FolderProblem.NotConnected)
            }
            hooks.items().filter { it.id in itemIds }.forEach { item ->
                when {
                    isPlaying(item) -> prefailed += MoveFailure(item.fileName, MoveFailureReason.Playing)
                    !ItemLocks.tryAcquire(item.id) -> prefailed += MoveFailure(item.fileName, MoveFailureReason.Busy)
                    else -> {
                        locked += item.id
                        // Exactly one writer: a running download is asked to stop, and the item's writer token is
                        // taken whatever its status, waiting for a writer that is still closing its file.
                        val wasRunning = item.status == DownloadStatus.Downloading
                        if (wasRunning) paused += item.id
                        val stopped = !wasRunning || hooks.pauseAndWait(item.id)
                        if (stopped && acquireWriterToken(item.id)) {
                            tokens += item.id
                        } else {
                            prefailed += MoveFailure(item.fileName, MoveFailureReason.StillWriting)
                        }
                    }
                }
            }
            movingNow = tokens.toSet()
            _state.value = _state.value.copy(moving = true, movingIds = movingNow)
            // The list is the started profile's only while that profile is active; otherwise nothing is planned.
            val list = if (hooks.profileId() == startedProfile) hooks.items().takeIf { hooks.profileId() == startedProfile }.orEmpty() else emptyList()
            val ownership = hooks.allRecords()?.let { DownloadOwnership(locations, it) }
            val planned = plan(list.filter { it.id in movingNow }, destination, ownership, startedProfile)
            val report = mover.run(
                plan = MovePlan(destination.id, name, planned.items),
                isCancelled = { cancelRequested.get() || hooks.profileId() != startedProfile },
                onProgress = { _progress.value = it },
                records = records,
            )
            // A location that no record of any profile and no journal names is forgotten, after a move that ended normally.
            val journalled = mover.referencedLocations()
            val used = hooks.allRecords()?.flatMapTo(mutableSetOf()) { record ->
                buildList {
                    add(locations.locationOfRecord(record.locationId, record.localFileUri).id)
                    record.supersededPartials.forEach { kept ->
                        kept.locationId?.let { add(locations.locationOfRecord(it, null).id) }
                    }
                }
            }
            if (report.problem == null && journalled != null && used != null) locations.forgetExcept(used + journalled)
            return report.copy(
                unreachable = planned.unreachable,
                failures = prefailed + planned.failures + report.failures,
            )
        } finally {
            // Every release on its own, so that one failure cannot keep anything else held.
            try {
                movingNow = emptySet()
                runCatching { usage.invalidate() }
                tokens.forEach { id -> runCatching { WriterTokens.release(id) } }
                locked.forEach { id -> runCatching { ItemLocks.release(id) } }
                paused.distinct().forEach { id -> runCatching { hooks.resume(id) } }
            } finally {
                _progress.value = null
                moveRunning.set(false)
                runCatching { refresh() }
                runCatching { hooks.resumeHeld() }
            }
        }
    }

    override fun removeExtraCopy(leftoverId: String): LeftoverOutcome =
        mover.removeExtraCopy(leftoverId, records) { id -> if (ItemLocks.tryAcquire(id)) java.io.Closeable { ItemLocks.release(id) } else null }
            .also { runCatching { refresh() } }

    override fun keepBoth(leftoverId: String) {
        mover.keepBoth(leftoverId)
        runCatching { refresh() }
    }

    override fun removeJustPublished(localFileUri: String, fileKey: String?): Boolean =
        DownloadsPlatformDownloader.removeJustPublished(localFileUri, fileKey)

    override fun pickFile(): String? = filePicker()

    override fun adoptFile(item: DownloadItem, path: String): Boolean {
        val file = runCatching { Paths.get(path).toAbsolutePath().normalize() }.getOrNull() ?: return false
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false
        // Inside a download folder that can be used now, so that it can be moved and found like any other.
        val location = locations.locationContaining(file)?.takeIf { locations.availability(it) == FolderAvailability.Available } ?: return false
        if (file.parent?.let { DownloadFolderValidator.isTrash(it) } == true) return false
        if (ownershipOfPath(item, file) != FileOwnership.Free) return false
        val uri = file.toFile().toURI().toString()
        return hooks.compareAndRelocate(
            hooks.profileId(),
            item.id,
            { it.status == DownloadStatus.Completed && it.localFileUri == null && it.locationId == item.locationId },
        ) { it.copy(localFileUri = uri, locationId = location.id) } == RelocateResult.Confirmed
    }

    override fun scanDownloadsOnDisk(
        activeProfileId: Int,
        inMemoryItems: List<DownloadItem>,
        nextDownloadId: (Long) -> String,
        nowEpochMs: Long,
    ): DiskScanResult {
        if (moveRunning.get()) return DiskScanResult(busy = true)
        val scanner = DownloadDiskScanner(
            locations = locations,
            allRecords = { profileId, items ->
                val others = hooks.otherProfileRecords(profileId) ?: return@DownloadDiskScanner null
                items.map { RecordRef.of(profileId, it) } + others
            },
            leftoverPaths = {
                runCatching { mover.leftovers() }.getOrDefault(emptyList())
                    .flatMap { listOfNotNull(it.sourcePath.takeIf(String::isNotBlank), it.destinationPath.takeIf(String::isNotBlank)) }
                    .mapNotNull { runCatching { DownloadOwnership.keyOf(Paths.get(it)) }.getOrNull() }
                    .toSet()
            },
        )
        return scanner.scan(activeProfileId, inMemoryItems, nextDownloadId, nowEpochMs)
    }

    override fun setAside(leftoverId: String): Boolean = mover.setAside(leftoverId).also { runCatching { refresh() } }

    override fun cancelMove() {
        cancelRequested.set(true)
        _progress.value = _progress.value?.copy(cancelling = true)
    }

    override fun showInFileManager(locationId: String): Boolean {
        val location = locations.location(locationId) ?: return false
        if (locations.availability(location) != FolderAvailability.Available) return false
        if (locationId == DownloadLocations.AppId) runCatching { Files.createDirectories(location.path) }
        return reveal(location.path)
    }

    override fun onLocationReturned() {
        hooks.refreshOfflineIndex()
        onReturned()
    }

    companion object {
        private const val CheckIntervalMs = 3_000L

        fun openInFileManager(path: Path): Boolean {
            val desktop = runCatching { Desktop.getDesktop() }.getOrNull()
            if (desktop != null && Desktop.isDesktopSupported() && desktop.isSupported(Desktop.Action.OPEN)) {
                if (runCatching { desktop.open(path.toFile()) }.isSuccess) return true
            }
            val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
            val command = when {
                os.contains("mac") -> listOf("open", path.toString())
                os.contains("win") -> listOf("explorer", path.toString())
                else -> listOf("xdg-open", path.toString())
            }
            return runCatching { ProcessBuilder(command).start() }.isSuccess
        }
    }
}

/** The folders of the running app. Replaced in tests. */
internal object DesktopDownloads {
    @Volatile
    private var override: DesktopDownloadFolders? = null

    private val real: DesktopDownloadFolders by lazy {
        val appFolder = DesktopStorage.rootDir.resolve("downloads")
        val locations = DownloadLocations(DownloadLocationConfig(DesktopStorage.store("nuvio_download_folders")), { appFolder })
        val isMac = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("mac")
        DesktopDownloadFolders(
            locations = locations,
            validator = DownloadFolderValidator(locations, DesktopStorage.rootDir, DesktopStorage.cacheDir),
            mover = DownloadMover(
                journalDir = DesktopStorage.rootDir.resolve("moves"),
                resolve = { id -> locations.location(id)?.takeIf { locations.availability(it) == FolderAvailability.Available }?.path },
                trash = DownloadsTrash::moveToTrash,
                locationPath = { id -> locations.location(id)?.path },
            ),
            picker = { NativeFolderPicker.pick(isMac, locations.defaultLocation().path.toFile().takeIf { it.isDirectory }) },
            filePicker = { NativeFolderPicker.pickFile(isMac, locations.defaultLocation().path.toFile().takeIf { it.isDirectory }) },
            isMac = isMac,
            onReturned = { runCatching { AutoDownloads.checkNow() } },
            isPlaying = { item -> AutoDownloads.isPlaying(item) },
        )
    }

    val folders: DesktopDownloadFolders get() = override ?: real

    /** For tests: use [folders], or the real ones again with null. */
    fun replaceForTest(folders: DesktopDownloadFolders?) {
        override = folders
    }

    /**
     * Called once when the app starts. Recovery runs on the location thread, and the downloads list
     * is read only after that, never on the interface thread.
     */
    fun install() {
        val folders = real
        DownloadsRepository.deferLoadOnThisThread = { EventQueue.isDispatchThread() }
        DownloadsRepository.beforeCatalogLoad = {
            if (Thread.currentThread() !== folders.recoveryRunner) folders.awaitRecovery()
        }
        DownloadFolders.controller = folders
        // Read the list on the location thread, after recovery, so the interface thread never decodes it.
        folders.start(afterRecovery = { DownloadsRepository.ensureLoaded() })
    }
}
