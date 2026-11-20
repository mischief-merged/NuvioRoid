package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.random.Random

/** Treats the folders under a temporary "Volumes" folder as separate drives. Moving one aside unplugs it. */
internal class FakeVolumes(private val volumesDir: Path) : DownloadVolumes {
    override fun mountPoints(): List<Path> = Files.list(volumesDir).use { it.toList() }

    override fun anchorOf(path: Path): Path? {
        val absolute = path.toAbsolutePath().normalize()
        return if (absolute.startsWith(volumesDir) && absolute.nameCount > volumesDir.nameCount) {
            volumesDir.resolve(absolute.getName(volumesDir.nameCount).toString())
        } else {
            null
        }
    }
}

/** A temporary place with an app folder, a few drives and locations over them. Nothing outside it is touched. */
internal class FolderWorld(val root: Path = Files.createTempDirectory("nuvio-folders").toRealPath()) {
    val volumes: Path = root.resolve("Volumes").createDirectories()
    val appFolder: Path = root.resolve("app-data/downloads")
    val appData: Path = root.resolve("app-data")
    val config = DownloadLocationConfig(DesktopStorage.Store(root.resolve("folders.properties")))
    val locations = DownloadLocations(config, { appFolder.also { it.createDirectories() } }, FakeVolumes(volumes))
    val validator = DownloadFolderValidator(
        locations = locations,
        appDataDir = appData,
        cacheDir = root.resolve("cache"),
        programDirs = { listOf(root.resolve("program")) },
    )

    /** A drive that is plugged in, and a folder on it registered as a location. */
    fun driveLocation(drive: String, folder: String = "Movies"): DesktopDownloadLocation {
        val directory = volumes.resolve(drive).resolve(folder).createDirectories()
        return locations.register(directory)
    }

    fun unplug(drive: String) {
        Files.move(volumes.resolve(drive), root.resolve("$drive.unplugged"))
    }

    fun replug(drive: String) {
        Files.move(root.resolve("$drive.unplugged"), volumes.resolve(drive))
    }

    /** The folder of a location that can be used now, by id: what the mover is given. */
    fun resolver(): (String) -> Path? = { id ->
        locations.location(id)?.takeIf { locations.availability(it) == FolderAvailability.Available }?.path
    }

    /** The Trash of this world: what the mover sends there is kept, never deleted. */
    val trash = TestTrash(root.resolve("Trash"))

    /** A mover over these locations. Journals go to a folder of their own. */
    fun mover(
        checkpoint: (String) -> Unit = {},
        copy: (Path, java.nio.channels.FileChannel, (Long) -> Unit, () -> Boolean) -> String = DownloadMover::copyFile,
        syncDirectory: (Path) -> Boolean = { true },
        createLink: (Path, Path) -> Unit = { link, existing -> Files.createLink(link, existing) },
        randomName: () -> String = { DownloadMover.randomHex(8) },
        txId: () -> String = { DownloadMover.randomHex(16) },
        beforeJournalWrite: (MoveJournal) -> Unit = {},
        fileKey: (Path) -> String? = DownloadMover::identityOf,
        hashOf: (Path) -> String = DownloadMover::sha256Of,
        trash: (Path) -> Boolean = this.trash::send,
        resolve: (String) -> Path? = resolver(),
        onOutputOpened: (java.nio.channels.FileChannel) -> Unit = {},
    ) = DownloadMover(
        journalDir = root.resolve("moves"),
        resolve = resolve,
        trash = trash,
        locationPath = { id -> locations.location(id)?.path },
        checkpoint = checkpoint,
        copy = copy,
        hashOf = hashOf,
        syncDirectory = syncDirectory,
        createLink = createLink,
        randomName = randomName,
        txIdFactory = txId,
        beforeJournalWrite = beforeJournalWrite,
        fileKeyOf = fileKey,
        onOutputOpened = onOutputOpened,
    )

    /** The temporary files of any move left in [folder]. */
    fun tempsIn(folder: Path): List<Path> =
        if (!Files.isDirectory(folder)) emptyList() else Files.list(folder).use { s -> s.filter { it.fileName.toString().startsWith(".nuvio-") && it.fileName.toString().endsWith(".tmp") }.toList() }

    fun close() {
        root.toFile().deleteRecursively()
    }
}

/** A small, valid video file with [mediaBytes] of media that differ by [seed]. Big enough for the file's hash when above 128 KB. */
internal fun videoFile(seed: Int, mediaBytes: Int = 200_000): ByteArray = mp4File(1) + Random(seed).nextBytes(mediaBytes)

internal fun Path.writeVideo(seed: Int, mediaBytes: Int = 200_000): Path {
    parent?.createDirectories()
    Files.write(this, videoFile(seed, mediaBytes))
    return this
}

internal fun episodeItem(
    show: String,
    episode: Int,
    status: DownloadStatus = DownloadStatus.Completed,
    locationId: String? = null,
    file: Path? = null,
    bytes: Long = 0L,
    ruleKey: String? = null,
    updatedAt: Long = 2L,
    season: Int = 1,
) = DownloadItem(
    id = "$show-$season-$episode",
    contentType = "series",
    parentMetaId = show,
    parentMetaType = "series",
    videoId = "$show:$season:$episode",
    title = "Show $show",
    seasonNumber = season,
    episodeNumber = episode,
    streamTitle = "1080p",
    providerName = "Addon",
    sourceUrl = "https://example.com/$show/$episode.mp4",
    localFileUri = file?.toFile()?.toURI()?.toString(),
    fileName = "$show-s${season}e$episode.mp4",
    status = status,
    downloadedBytes = bytes,
    totalBytes = bytes.takeIf { it > 0L },
    createdAtEpochMs = 1L,
    updatedAtEpochMs = updatedAt,
    autoDownloadRuleKey = ruleKey,
    locationId = locationId,
)

internal fun movieItem(id: String, locationId: String? = null, file: Path? = null, bytes: Long = 0L) = DownloadItem(
    id = id,
    contentType = "movie",
    parentMetaId = "tt$id",
    parentMetaType = "movie",
    videoId = "tt$id",
    title = "Film $id",
    streamTitle = "1080p",
    providerName = "Addon",
    sourceUrl = "https://example.com/$id.mp4",
    localFileUri = file?.toFile()?.toURI()?.toString(),
    fileName = "$id.mp4",
    status = DownloadStatus.Completed,
    downloadedBytes = bytes,
    totalBytes = bytes.takeIf { it > 0L },
    createdAtEpochMs = 1L,
    updatedAtEpochMs = 2L,
    locationId = locationId,
)

/** A backend over real temporary locations, for the list of downloads. Nothing is fetched. */
internal class LocationsBackend(private val world: FolderWorld) : DownloadsBackend {
    var payload: String? = null
    val trashed = mutableListOf<String>()
    val removedOutright = mutableListOf<String?>()
    var trashSucceeds = true
    val started = mutableListOf<String>()

    override fun loadPayload(): String? = payload
    override fun savePayload(payload: String) {
        this.payload = payload
    }

    override fun restoreItem(item: DownloadItem): DownloadItem = item

    override fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle {
        started += request.item.id
        return object : DownloadsTaskHandle {
            override fun cancel() = Unit
        }
    }

    override fun removeFile(localFileUri: String?): Boolean {
        removedOutright += localFileUri
        return true
    }

    override fun removePartialFile(destinationFileName: String): Boolean = true

    override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? =
        (world.locations.locate(null, localFileUri, destinationFileName) as? FileLookup.Found)?.uri

    override fun inspectFile(localFileUri: String): DownloadedFileFacts? = inspectDownloadedFile(localFileUri)

    override fun trashWatchedFile(localFileUri: String, stillWanted: () -> Boolean): AutoDownloadRemovalResult {
        if (!stillWanted()) return AutoDownloadRemovalResult.Changed
        return if (trashFile(localFileUri)) AutoDownloadRemovalResult.Removed else AutoDownloadRemovalResult.NotMoved
    }

    override fun trashFile(localFileUri: String): Boolean {
        if (trashSucceeds) trashed += localFileUri
        return trashSucceeds
    }

    override fun locate(item: DownloadItem): FileLookup = world.locations.locate(item.locationId, item.localFileUri, item.fileName)

    override fun isConnected(item: DownloadItem): Boolean =
        world.locations.availability(world.locations.locationOfRecord(item.locationId, item.localFileUri)) == FolderAvailability.Available

    override fun locationConnected(locationId: String?): Boolean =
        world.locations.location(locationId)?.let { world.locations.availability(it) == FolderAvailability.Available } ?: false

    override fun heldByFolder(item: DownloadItem): Boolean = !isConnected(item)

    override fun placementFor(items: List<DownloadItem>, parentMetaId: String, isEpisode: Boolean): String =
        placementForNewDownload(items, parentMetaId, isEpisode, world.locations.defaultLocation().id) {
            world.locations.locationOfRecord(it.locationId, it.localFileUri).id
        }
}

internal fun storedPayload(items: List<DownloadItem>): String {
    val json = kotlinx.serialization.json.Json { encodeDefaults = true }
    return """{"items":[${items.joinToString(",") { json.encodeToString(DownloadItem.serializer(), it) }}]}"""
}

/** A Trash for tests: files and folders are moved into it under unique names and never deleted. */
internal class TestTrash(private val dir: Path) {
    @Volatile var fails = false
    private val counter = java.util.concurrent.atomic.AtomicInteger()
    val sent = java.util.Collections.synchronizedList(mutableListOf<Pair<Path, Path>>())

    fun send(path: Path): Boolean {
        if (fails) return false
        Files.createDirectories(dir)
        val target = dir.resolve("${counter.incrementAndGet()}-${path.fileName}")
        return runCatching { Files.move(path, target) }.onSuccess { sent += path to target }.isSuccess
    }

    /** Every regular file in the Trash, at any depth. */
    fun files(): List<Path> = if (!Files.isDirectory(dir)) emptyList() else Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) }.toList() }

    fun holds(bytes: ByteArray): Boolean = files().any { Files.size(it) == bytes.size.toLong() && Files.readAllBytes(it).contentEquals(bytes) }
}

/**
 * The lists of downloads of several profiles, with the compare-and-relocate semantics of the repository:
 * one profile is active; a record is re-pointed only in its own profile's list, in one synchronized step.
 */
internal open class ProfileHooks(lists: Map<Int, List<DownloadItem>>, @Volatile var profile: Int = 1) : DownloadsHooks {
    private val lock = Any()
    private val lists = lists.toMutableMap()
    val log = java.util.Collections.synchronizedList(mutableListOf<String>())
    @Volatile var stopFails = false
    @Volatile var saveFails = false
    @Volatile var unreadable = false
    /** Called just before each compare-and-relocate, outside the lock. */
    @Volatile var onRelocate: (String) -> Unit = {}
    @Volatile var onResume: (String) -> Unit = {}

    constructor(items: List<DownloadItem>) : this(mapOf(1 to items))

    var items: List<DownloadItem>
        get() = list(profile)
        set(value) = synchronized(lock) { lists[profile] = value }

    fun list(profileId: Int): List<DownloadItem> = synchronized(lock) { lists[profileId].orEmpty() }

    fun setList(profileId: Int, items: List<DownloadItem>) = synchronized(lock) { lists[profileId] = items }

    fun update(profileId: Int, change: (List<DownloadItem>) -> List<DownloadItem>) = synchronized(lock) { lists[profileId] = change(lists[profileId].orEmpty()) }

    override fun items() = items
    override fun profileId() = profile

    private val listLock = java.util.concurrent.locks.ReentrantLock()

    /** Whether the calling thread holds the list lock now. */
    val holdsListLock: Boolean get() = listLock.isHeldByCurrentThread

    override fun <T> withListLock(block: () -> T): T {
        listLock.lock()
        try {
            return block()
        } finally {
            listLock.unlock()
        }
    }

    override fun pauseAndWait(downloadId: String): Boolean {
        log += "pause $downloadId"
        if (stopFails) return false
        update(profile) { list -> list.map { if (it.id == downloadId) it.copy(status = DownloadStatus.Paused) else it } }
        return true
    }

    override fun resume(downloadId: String) {
        log += "resume $downloadId"
        onResume(downloadId)
    }

    override fun resumeHeld() {
        log += "resumeHeld"
    }

    override fun allRecords(): List<RecordRef>? =
        if (unreadable) null else synchronized(lock) { lists.flatMap { (pid, list) -> list.map { RecordRef.of(pid, it) } } }

    override fun recordOf(profileId: Int, downloadId: String): DownloadItem? = list(profileId).firstOrNull { it.id == downloadId }

    override fun compareAndRelocate(
        profileId: Int,
        downloadId: String,
        matches: (DownloadItem) -> Boolean,
        relocate: (DownloadItem) -> DownloadItem,
    ): RelocateResult {
        onRelocate(downloadId)
        return synchronized(lock) {
            val list = lists[profileId].orEmpty()
            val current = list.firstOrNull { it.id == downloadId } ?: return@synchronized RelocateResult.RecordGone
            if (!matches(current)) return@synchronized RelocateResult.RecordChanged
            if (saveFails) return@synchronized RelocateResult.NotDurable
            lists[profileId] = list.map { if (it.id == downloadId) relocate(it) else it }
            log += "relocate $downloadId"
            RelocateResult.Confirmed
        }
    }
}

/** A simulated crash: an Error, so that nothing in the mover handles it, as nothing would run after a real one. */
internal class Crash(at: String) : Error("crash at $at")
