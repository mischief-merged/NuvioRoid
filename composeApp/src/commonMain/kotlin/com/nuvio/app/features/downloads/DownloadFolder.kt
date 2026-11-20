package com.nuvio.app.features.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether a download location can be used right now. */
internal enum class FolderAvailability {
    Available,

    /** The drive the location is on is not there. */
    NotConnected,

    /** The drive is there but the folder is not. */
    NotFound,

    /** The folder is there but does not carry the marker that makes it a download location. Nothing is lost: choose it again. */
    NotSetUp,
}

/** What a look for the file of a download found. */
internal sealed interface FileLookup {
    data class Found(val uri: String, val locationId: String?) : FileLookup

    /** The file cannot be looked for: its location is away. It is not lost. */
    data class Unreachable(val locationId: String?, val availability: FolderAvailability) : FileLookup

    data object Missing : FileLookup
}

internal enum class FolderProblem {
    NotAFolder,
    NotConnected,
    CannotCreate,
    NotWritable,
    InsideAppData,
    InsideProgram,
    IsTrash,
    OverlapsDownloadFolder,
    NotEnoughSpace,

    /** Another move, in this window or another, holds the lock. */
    MoveInProgress,

    /** An earlier move left something unresolved. */
    PreviousMoveUnresolved,

    /** The folder holds a file at the marker's name that is not this app's. Nothing was changed. */
    MarkerTaken,
}

internal sealed interface FolderCheck {
    data object Ok : FolderCheck

    data class Problem(
        val kind: FolderProblem,
        val neededBytes: Long = 0L,
        val freeBytes: Long? = null,
    ) : FolderCheck
}

internal data class DownloadLocationInfo(
    val id: String,
    val path: String,
    /** The last part of the path, for a short label. */
    val name: String,
    /** New downloads go here. */
    val isDefault: Boolean,
    /** The app's own downloads folder. */
    val isAppFolder: Boolean,
    val availability: FolderAvailability,
    val itemCount: Int,
    val totalBytes: Long,
    val freeBytes: Long?,
    /** What the downloads of this location take on disk (partial and companion files included). */
    val usedBytes: Long = totalBytes,
    /** The location is away, so [usedBytes] is what was known when it was last there. */
    val usedIsLastKnown: Boolean = false,
)

internal data class DownloadFolderState(
    val locations: List<DownloadLocationInfo> = emptyList(),
    /** True on macOS, where the file manager is Finder. */
    val isMac: Boolean = false,
    /** True while a move is running. */
    val moving: Boolean = false,
    /** The downloads a move has in hand. They cannot be deleted, played or started until it is done. */
    val movingIds: Set<String> = emptySet(),
    /** What automatic downloads take on disk, in the locations that are connected. */
    val autoDownloadUsedBytes: Long = 0L,
    /** Free space of the volumes of the connected locations, each volume counted once. Null when not known. */
    val totalFreeBytes: Long? = null,
    /** False until the first measurement of what is on disk has finished. */
    val usageMeasured: Boolean = false,
    /** Files that exist in two places after an interrupted or imperfect move. Only the user resolves them. */
    val leftovers: List<LeftoverCopy> = emptyList(),
) {
    /** What downloads take on disk in the locations that can be reached. */
    val totalUsedBytes: Long
        get() = locations.filter { it.availability == FolderAvailability.Available }.sumOf { it.usedBytes }

    val defaultLocation: DownloadLocationInfo? get() = locations.firstOrNull { it.isDefault }

    /** The locations that hold downloads or take new ones. */
    val inUse: List<DownloadLocationInfo> get() = locations.filter { it.isDefault || it.itemCount > 0 }

    val hasSeveralLocations: Boolean get() = inUse.size > 1

    fun location(id: String?): DownloadLocationInfo? = locations.firstOrNull { it.id == id } ?: locations.firstOrNull { it.isAppFolder }.takeIf { id == null }

    val unreachable: List<DownloadLocationInfo>
        get() = locations.filter { it.availability != FolderAvailability.Available && (it.isDefault || it.itemCount > 0) }
}

internal enum class MoveFailureReason {
    NoSpace,
    ChangedWhileCopying,
    CopyDidNotMatch,
    NameTaken,
    CouldNotRead,
    CouldNotWrite,
    Other,

    /** The download is a link. Links are never moved. */
    Symlink,

    /** The list changed while the move ran: the item was deleted or points somewhere else. */
    RecordChanged,

    /** The item is playing. */
    Playing,

    /** Something else is changing the item. */
    Busy,

    /** Its download did not stop in time, so its size could not be trusted. */
    StillWriting,

    /** Moved, but the original is still in the old folder: the Trash did not take it, or it could not be checked. Listed as a leftover. */
    OriginalNotRemoved,

    /** A barrier failed: the copy is where it is, the original stays, and the leftover is listed. */
    KeptInBothPlaces,

    /** A file of this download belongs to another record. */
    OwnedByAnother,
}

internal enum class LeftoverKind {
    /** A download whose files exist in two places, or a copy its record does not use. */
    Copy,

    /** A move record that could not be read or settled. New moves wait until it is set aside. */
    Unresolved,
}

/** A file that exists in two places after a move that did not finish cleanly, or a move record to resolve. */
internal data class LeftoverCopy(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val sourcePath: String,
    val destinationPath: String,
    val kind: LeftoverKind = LeftoverKind.Copy,
)

internal data class MoveFailure(val name: String, val reason: MoveFailureReason, val detail: String? = null)

internal data class MoveProgress(
    val destinationName: String,
    val totalFiles: Int,
    val doneFiles: Int,
    val totalBytes: Long,
    val doneBytes: Long,
    val currentName: String? = null,
    val currentBytes: Long = 0L,
    val currentTotalBytes: Long = 0L,
    val cancelling: Boolean = false,
) {
    val overallFraction: Float
        get() = if (totalBytes > 0L) (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
        else if (totalFiles > 0) doneFiles.toFloat() / totalFiles else 0f

    val currentFraction: Float
        get() = if (currentTotalBytes > 0L) (currentBytes.toDouble() / currentTotalBytes).toFloat().coerceIn(0f, 1f) else 0f
}

internal data class MoveReport(
    val destinationPath: String,
    val destinationName: String,
    val moved: Int,
    val failures: List<MoveFailure> = emptyList(),
    val cancelled: Boolean = false,
    /** Items that were asked for but are on a location that is away. */
    val unreachable: Int = 0,
    /** Items not moved because the move was cancelled or stopped. */
    val notReached: Int = 0,
    /** Items that were moved whose original could not be removed. They count as moved. */
    val originalsKept: List<MoveFailure> = emptyList(),
    /** What went wrong before anything moved, if the move did not start. */
    val problem: FolderProblem? = null,
)

/** What the interface needs from download locations. Only the desktop has an implementation. */
internal interface DownloadFolderController {
    val supported: Boolean
    val state: StateFlow<DownloadFolderState>
    val progress: StateFlow<MoveProgress?>

    /** Opens the native folder picker. Blocks until it closes. Null when cancelled. */
    fun pickFolder(): String?

    fun check(path: String, requiredBytes: Long): FolderCheck

    /** The bytes that would have to be copied to move [itemIds] to [destinationPath]. Moves inside one volume cost none. */
    fun bytesToCopy(itemIds: Set<String>, destinationPath: String): Long

    fun freeBytesAt(path: String): Long?

    /** Makes [path] the place new downloads go. Nothing moves. */
    fun setDefault(path: String): Boolean

    fun move(itemIds: Set<String>, destinationPath: String): MoveReport

    fun cancelMove()

    fun showInFileManager(locationId: String): Boolean

    /** Where the item lives: a location id. */
    fun locationIdOf(item: DownloadItem): String

    fun refresh()

    /** The folder path the app keeps its own downloads in. */
    fun appFolderPath(): String

    /** Looks for the file of a finished download across the locations. Null where locations are not handled. */
    fun lookup(item: DownloadItem): FileLookup? = null

    /** The download must not run now: its location is away or a move is carrying it. */
    fun heldByFolder(item: DownloadItem): Boolean = false

    /** Whether the location of [item] can be used now. */
    fun isConnected(item: DownloadItem): Boolean = true

    fun locationConnected(locationId: String?): Boolean = true

    /** The location a new download should go to, or null where locations are not handled. */
    fun placementFor(items: List<DownloadItem>, parentMetaId: String, isEpisode: Boolean): String? = null

    /** Called when a location came back after it was away. */
    fun onLocationReturned() = Unit

    /** Removes the partial file of [item] from its own location only. Null where locations are not handled. */
    fun removePartialOf(item: DownloadItem): Boolean? = null

    /** Removes the extra copy of a leftover after checking it is equal and the record points at the other. Null text means done. */
    fun removeExtraCopy(leftoverId: String): LeftoverOutcome = LeftoverOutcome.Refused
    fun keepBoth(leftoverId: String) = Unit

    /** Keeps an unresolved move record aside, without overwriting anything, so new moves can start. */
    fun setAside(leftoverId: String): Boolean = false

    /** Whether a record of any profile other than [item]'s own uses [fileUri] or its sidecar folder. Null where locations are not handled. */
    fun ownershipOf(item: DownloadItem, fileUri: String): FileOwnership? = null

    /** Sends a download's file and its sidecar folder to the Trash. Null where locations are not handled. */
    fun discardFile(localFileUri: String): Boolean? = null

    /** Removes a file the downloader just published, only while it is that file. Null where locations are not handled. */
    fun removeJustPublished(localFileUri: String): Boolean? = null

    /** Opens the native file picker. Blocks until it closes. Null when cancelled. */
    fun pickFile(): String? = null

    /**
     * Points a finished download that has no recorded file at [path], which the user picked and confirmed.
     * Refused unless it is a regular file inside a download folder that can be used and no other record of
     * any profile uses it. Nothing is moved, copied or deleted.
     */
    fun adoptFile(item: DownloadItem, path: String): Boolean = false

    /** Scans every available storage location for completed downloads whose records are missing. */
    fun scanDownloadsOnDisk(
        activeProfileId: Int,
        inMemoryItems: List<DownloadItem>,
        nextDownloadId: (Long) -> String,
        nowEpochMs: Long,
    ): DiskScanResult = DiskScanResult()
}

internal enum class FileOwnership {
    /** No other record of any profile uses it. */
    Free,

    /** Another record, of this or another profile, uses it or something inside its sidecar folder. */
    Owned,

    /** The lists of every profile could not be read: nothing may be removed. */
    Unknown,
}

internal enum class LeftoverOutcome {
    Removed,

    /** The two copies are not the same, or the record does not point at the one being kept, or it is not this profile. */
    Refused,
}

/**
 * The exclusive right to write the files of one download. A transfer takes it when it starts and gives
 * it back only after it closed its file; pause and cancel only ask it to stop. A move takes it for the
 * whole of its transaction. Nobody else can have it meanwhile.
 */
internal object WriterTokens {
    enum class Kind { Writer, Mover }

    private val lock = kotlinx.atomicfu.locks.SynchronizedObject()
    private val owners = mutableMapOf<String, Kind>()

    fun tryAcquire(id: String, kind: Kind): Boolean =
        kotlinx.atomicfu.locks.synchronized(lock) { if (id in owners) false else { owners[id] = kind; true } }

    fun release(id: String) {
        kotlinx.atomicfu.locks.synchronized(lock) { owners.remove(id) }
    }

    fun kindOf(id: String): Kind? = kotlinx.atomicfu.locks.synchronized(lock) { owners[id] }

    /**
     * Waits up to [timeoutMs] for the token. A writer that is still exiting after a pause is waited for;
     * a move holding it is not (false at once), the download then waits to be resumed.
     */
    suspend fun acquire(id: String, kind: Kind, timeoutMs: Long): Boolean {
        var waited = 0L
        while (true) {
            if (tryAcquire(id, kind)) return true
            if (kindOf(id) == Kind.Mover && kind == Kind.Writer) return false
            if (waited >= timeoutMs) return false
            kotlinx.coroutines.delay(10)
            waited += 10
        }
    }
}

/**
 * Says which downloads are in the hands of a move or of a delete, so that only one thing changes an
 * item at a time. Taking it never waits: whoever cannot have it says the item is busy.
 */
internal object ItemLocks {
    private val lock = kotlinx.atomicfu.locks.SynchronizedObject()
    private val held = mutableSetOf<String>()

    fun tryAcquire(id: String): Boolean = kotlinx.atomicfu.locks.synchronized(lock) { held.add(id) }

    fun release(id: String) {
        kotlinx.atomicfu.locks.synchronized(lock) { held.remove(id) }
    }

    fun isHeld(id: String): Boolean = kotlinx.atomicfu.locks.synchronized(lock) { id in held }

    inline fun <T> withLock(id: String, busy: () -> T, block: () -> T): T {
        if (!tryAcquire(id)) return busy()
        try {
            return block()
        } finally {
            release(id)
        }
    }
}

internal object NoDownloadFolders : DownloadFolderController {
    override val supported: Boolean = false
    override val state: StateFlow<DownloadFolderState> = MutableStateFlow(DownloadFolderState())
    override val progress: StateFlow<MoveProgress?> = MutableStateFlow(null)
    override fun pickFolder(): String? = null
    override fun check(path: String, requiredBytes: Long): FolderCheck = FolderCheck.Problem(FolderProblem.NotAFolder)
    override fun bytesToCopy(itemIds: Set<String>, destinationPath: String): Long = 0L
    override fun freeBytesAt(path: String): Long? = null
    override fun setDefault(path: String): Boolean = false
    override fun move(itemIds: Set<String>, destinationPath: String): MoveReport = MoveReport(destinationPath, destinationPath, 0)
    override fun cancelMove() = Unit
    override fun showInFileManager(locationId: String): Boolean = false
    override fun locationIdOf(item: DownloadItem): String = item.locationId.orEmpty()
    override fun refresh() = Unit
    override fun appFolderPath(): String = ""
}

internal object DownloadFolders {
    /** Replaced at start by the desktop. Where there are no locations it is [NoDownloadFolders]. */
    @kotlin.concurrent.Volatile
    internal var controller: DownloadFolderController = NoDownloadFolders
}

/** One download that can be moved, for the checklist and the totals. */
internal data class MovableItem(
    val id: String,
    val groupKey: String,
    val groupTitle: String,
    val isMovie: Boolean,
    val label: String,
    val bytes: Long,
    val locationId: String,
)

internal data class MovableGroup(
    val key: String,
    val title: String,
    val isMovie: Boolean,
    val items: List<MovableItem>,
) {
    val bytes: Long get() = items.sumOf { it.bytes }
}

/** The bytes a move of this item takes: its file when finished, what was written so far otherwise. */
internal val DownloadItem.moveBytes: Long
    get() = if (status == DownloadStatus.Completed) maxOf(downloadedBytes, totalBytes ?: 0L) else downloadedBytes

/** The downloads grouped by show and by movie, shows and movies in name order, episodes in order. */
internal fun movableGroups(items: List<DownloadItem>, locationOf: (DownloadItem) -> String): List<MovableGroup> =
    items.groupBy { if (it.isEpisode) "show:${it.parentMetaId}" else "movie:${it.id}" }
        .map { (key, members) ->
            val first = members.first()
            MovableGroup(
                key = key,
                title = first.title,
                isMovie = !first.isEpisode,
                items = members.sortedForSeriesDownloads().map { item ->
                    MovableItem(
                        id = item.id,
                        groupKey = key,
                        groupTitle = item.title,
                        isMovie = !item.isEpisode,
                        label = item.nameForLabels(),
                        bytes = item.moveBytes,
                        locationId = locationOf(item),
                    )
                },
            )
        }
        .sortedWith(compareBy({ it.title.lowercase() }, { it.key }))

internal fun selectedBytes(items: List<MovableItem>, selected: Set<String>): Long =
    items.filter { it.id in selected }.sumOf { it.bytes }

internal enum class SelectionFit {
    /** Nothing selected, or room to spare. */
    Fits,

    /** It fits, but with less than 5 percent of the room left. */
    Tight,
    DoesNotFit,

    /** The free space is not known. */
    Unknown,
}

internal fun selectionFit(neededBytes: Long, freeBytes: Long?): SelectionFit = when {
    neededBytes <= 0L -> SelectionFit.Fits
    freeBytes == null -> SelectionFit.Unknown
    neededBytes > freeBytes -> SelectionFit.DoesNotFit
    freeBytes - neededBytes < freeBytes / 20 -> SelectionFit.Tight
    else -> SelectionFit.Fits
}

/**
 * The location a new download of a show goes to: the one that holds the show's downloads, so that a
 * show stays together; otherwise [defaultLocationId]. Several locations: the one of the most
 * recently updated download of the show. A movie has no show and goes to the default.
 */
internal fun placementForNewDownload(
    items: List<DownloadItem>,
    parentMetaId: String,
    isEpisode: Boolean,
    defaultLocationId: String,
    locationOf: (DownloadItem) -> String,
): String {
    if (!isEpisode) return defaultLocationId
    val parent = parentMetaId.trim()
    return items
        .filter { it.isEpisode && it.parentMetaId.trim() == parent }
        .maxByOrNull { it.updatedAtEpochMs }
        ?.let(locationOf)
        ?: defaultLocationId
}
