package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import java.util.UUID

/** A folder downloads are kept in. [id] is stable; the app's own folder has [DownloadLocations.AppId]. */
internal data class DesktopDownloadLocation(val id: String, val path: Path)

/** Which volume a path is on, for telling a drive that is not connected from a folder that is gone. */
internal interface DownloadVolumes {
    /** The mount point of the drive that holds [path], or null for the system volume, which is always there. */
    fun anchorOf(path: Path): Path?

    /** The drives that are mounted now, for finding a drive that came back under another name. */
    fun mountPoints(): List<Path> = emptyList()
}

/** Runs a file-system call that may hang on its own thread and gives up after a time. */
internal object FsGuard {
    private val pool = java.util.concurrent.ThreadPoolExecutor(
        0, 4, 30, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue(),
        java.util.concurrent.ThreadFactory { runnable -> Thread(runnable, "download-fs").apply { isDaemon = true } },
    )

    fun <T> call(timeoutMs: Long, onTimeout: T, block: () -> T): T {
        val future = try { pool.submit(java.util.concurrent.Callable { block() }) }
        catch (_: java.util.concurrent.RejectedExecutionException) { return onTimeout }
        return try {
            future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            future.cancel(true)
            onTimeout
        } catch (_: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            onTimeout
        } catch (error: java.util.concurrent.ExecutionException) {
            throw error.cause ?: error
        }
    }
}

internal class OsDownloadVolumes(
    private val osName: String = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT),
    private val systemDrive: String? = System.getenv("SystemDrive"),
) : DownloadVolumes {
    override fun mountPoints(): List<Path> = runCatching {
        when {
            osName.contains("mac") -> Files.list(Paths.get("/Volumes")).use { it.toList() }
            osName.contains("win") -> java.io.File.listRoots().map { it.toPath() }
            else -> listOf("/media", "/run/media", "/mnt").map(Paths::get).filter(Files::isDirectory).flatMap { base ->
                Files.list(base).use { it.toList() }.flatMap { child ->
                    if (base.toString() == "/mnt") listOf(child) else Files.list(child).use { it.toList() }
                }
            }
        }
    }.getOrDefault(emptyList())

    override fun anchorOf(path: Path): Path? {
        val absolute = path.toAbsolutePath().normalize()
        val root = absolute.root ?: return null
        val parts = absolute.map { it.toString() }
        return when {
            osName.contains("mac") ->
                if (parts.size >= 2 && parts[0] == "Volumes") root.resolve("Volumes").resolve(parts[1]) else null
            osName.contains("win") ->
                root.takeUnless { systemDrive != null && it.toString().startsWith(systemDrive, ignoreCase = true) }
            else -> when {
                parts.size >= 3 && parts[0] == "media" -> root.resolve("media").resolve(parts[1]).resolve(parts[2])
                parts.size >= 4 && parts[0] == "run" && parts[1] == "media" ->
                    root.resolve("run").resolve("media").resolve(parts[2]).resolve(parts[3])
                parts.size >= 2 && parts[0] == "mnt" -> root.resolve("mnt").resolve(parts[1])
                else -> null
            }
        }
    }
}

@Serializable
internal data class StoredLocation(val id: String, val path: String)

/** What is saved about locations, on this device only. */
internal class DownloadLocationConfig(private val store: DesktopStorage.Store) {
    private val json = Json { ignoreUnknownKeys = true }

    fun locations(): List<StoredLocation> =
        store.getString(LocationsKey)?.let { runCatching { json.decodeFromString<List<StoredLocation>>(it) }.getOrNull() }.orEmpty()

    fun saveLocations(locations: List<StoredLocation>) {
        store.putString(LocationsKey, json.encodeToString(locations))
    }

    fun defaultId(): String? = store.getString(DefaultKey)

    fun saveDefaultId(id: String) {
        store.putString(DefaultKey, id)
    }

    private companion object {
        const val LocationsKey = "locations"
        const val DefaultKey = "default_location"
    }
}

/**
 * The folders downloads live in. The app's own folder is always there. Others are added when the
 * user chooses them, and are told apart from a path that merely looks the same by a marker file
 * that holds their id.
 */
internal class DownloadLocations(
    private val config: DownloadLocationConfig,
    private val appFolder: () -> Path,
    val volumes: DownloadVolumes = OsDownloadVolumes(),
    /** The longest a look at a volume may take before it counts as not answering. */
    private val timeoutMs: Long = 3_000L,
) {
    private val lock = Any()

    // The saved list is read once and again only after this class changes it: it is asked for every
    // row of the list, many times a second.
    private var cachedAll: List<DesktopDownloadLocation>? = null

    fun appLocation(): DesktopDownloadLocation = DesktopDownloadLocation(AppId, appFolder().toAbsolutePath().normalize())

    /** The app's folder first, then the others in the order they were added. */
    fun all(): List<DesktopDownloadLocation> = synchronized(lock) {
        cachedAll ?: (
            listOf(appLocation()) + config.locations()
                .filter { it.id != AppId }
                .mapNotNull { stored -> runCatching { DesktopDownloadLocation(stored.id, Paths.get(stored.path).toAbsolutePath().normalize()) }.getOrNull() }
            ).also { cachedAll = it }
    }

    fun defaultLocation(): DesktopDownloadLocation = synchronized(lock) {
        val id = config.defaultId()
        all().firstOrNull { it.id == id } ?: appLocation()
    }

    fun location(id: String?): DesktopDownloadLocation? = if (id == null) appLocation() else all().firstOrNull { it.id == id }

    /** [registerChecked], for callers that have already checked the folder; throws when it is refused. */
    fun register(path: Path): DesktopDownloadLocation =
        registerChecked(path) ?: throw IllegalStateException("The folder has a file at the marker's name that is not ours")

    /**
     * The location of [path], or a new one. The folder is not created here. The marker is only ever
     * created with create-new: a marker already there is read without following links and adopted when it
     * is ours and no other location holds its id; anything else at that name (a file of the user's, a
     * link, another location's marker) refuses the folder (null) and nothing is touched.
     */
    fun registerChecked(path: Path): DesktopDownloadLocation? = synchronized(lock) {
        val wanted = path.toAbsolutePath().normalize()
        all().firstOrNull { samePath(it.path, wanted) }?.let { existing ->
            if (existing.id == AppId || !Files.isDirectory(wanted)) return existing
            return when (val marker = markerAt(wanted)) {
                Marker.Missing -> existing.takeIf { createMarker(wanted, existing.id) || markerAt(wanted) == Marker.Ours(existing.id) }
                is Marker.Ours -> existing.takeIf { marker.id == existing.id }
                Marker.Foreign -> null
            }
        }
        val id = when (val marker = if (Files.isDirectory(wanted)) markerAt(wanted) else Marker.Missing) {
            Marker.Foreign -> return null
            is Marker.Ours -> marker.id.takeIf { held -> all().none { it.id == held } } ?: return null
            Marker.Missing -> "loc-" + UUID.randomUUID().toString().take(8)
        }
        if (Files.isDirectory(wanted) && markerAt(wanted) == Marker.Missing && !createMarker(wanted, id)) return null
        val location = DesktopDownloadLocation(id, wanted)
        config.saveLocations(config.locations() + StoredLocation(location.id, wanted.toString()))
        cachedAll = null
        location
    }

    /** Whether [folder] could be registered: no marker, or a marker of ours that no other location holds. */
    fun markerAllows(folder: Path): Boolean {
        val wanted = folder.toAbsolutePath().normalize()
        if (!Files.isDirectory(wanted)) return true
        val known = all().firstOrNull { samePath(it.path, wanted) }
        return when (val marker = markerAt(wanted)) {
            Marker.Missing -> true
            Marker.Foreign -> false
            is Marker.Ours -> if (known != null) marker.id == known.id else all().none { it.id == marker.id }
        }
    }

    private sealed interface Marker {
        data object Missing : Marker
        data class Ours(val id: String) : Marker

        /** A link, a folder, or a file that is not a marker of this app. */
        data object Foreign : Marker
    }

    private fun markerAt(folder: Path): Marker {
        val file = folder.resolve(MarkerName)
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Marker.Missing
        val id = readMarker(file) ?: return Marker.Foreign
        return Marker.Ours(id)
    }

    /** The id in a marker file, read without following a link; null when it is not a marker of this app. */
    private fun readMarker(file: Path): String? = runCatching {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 64) return null
        val text = Files.newByteChannel(file, java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = java.nio.ByteBuffer.allocate(64)
            while (channel.read(buffer) > 0) Unit
            String(buffer.array(), 0, buffer.position(), Charsets.UTF_8).trim()
        }
        text.takeIf { MarkerId.matches(it) }
    }.getOrNull()

    /** Creates the marker with create-new; never over anything. False when something is already there or it failed. */
    private fun createMarker(folder: Path, id: String): Boolean = runCatching {
        java.nio.channels.FileChannel.open(
            folder.resolve(MarkerName),
            java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val bytes = java.nio.ByteBuffer.wrap(id.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        true
    }.getOrDefault(false)

    fun setDefault(path: Path): DesktopDownloadLocation = synchronized(lock) {
        val location = register(path)
        config.saveDefaultId(location.id)
        location
    }

    fun setDefaultId(id: String) = synchronized(lock) { config.saveDefaultId(id) }

    /** Forgets locations that are not [keepIds], not the default and not the app's own. */
    fun forgetExcept(keepIds: Set<String>) = synchronized(lock) {
        val keep = keepIds + config.defaultId().orEmpty()
        val remaining = config.locations().filter { it.id in keep }
        if (remaining.size != config.locations().size) {
            config.saveLocations(remaining)
            cachedAll = null
        }
    }

    /**
     * Whether the location can be used. Every file-system call here may hang on a drive that is
     * failing or a network volume that stopped answering, so it runs on a helper thread and is given
     * [timeoutMs]; a location that does not answer counts as not connected, and nothing is touched.
     */
    fun availability(location: DesktopDownloadLocation): FolderAvailability {
        if (location.id == AppId) return FolderAvailability.Available
        return FsGuard.call(timeoutMs, FolderAvailability.NotConnected) {
            val anchor = volumes.anchorOf(location.path)
            when {
                anchor != null && !Files.exists(anchor) -> FolderAvailability.NotConnected
                !Files.isDirectory(location.path) -> FolderAvailability.NotFound
                markerIdOf(location.path) == location.id -> FolderAvailability.Available
                else -> FolderAvailability.NotSetUp
            }
        }
    }

    private fun markerIdOf(folder: Path): String? = readMarker(folder.resolve(MarkerName))

    /**
     * Looks for a location whose drive came back under another name, on any other mounted drive, by
     * the id its marker holds and never by a file name. The saved path is updated when it is found.
     */
    fun rediscover(location: DesktopDownloadLocation): DesktopDownloadLocation? {
        if (location.id == AppId) return null
        return FsGuard.call(timeoutMs * 2, null) {
            val anchor = volumes.anchorOf(location.path) ?: return@call null
            val relative = anchor.relativize(location.path.toAbsolutePath().normalize())
            volumes.mountPoints()
                .filter { it != anchor && Files.isDirectory(it) }
                .map { it.resolve(relative.toString()) }
                .firstOrNull { candidate -> Files.isDirectory(candidate) && markerIdOf(candidate) == location.id }
                ?.let { moved -> moveLocation(location.id, moved) }
        }
    }

    private fun moveLocation(id: String, path: Path): DesktopDownloadLocation = synchronized(lock) {
        val normalized = path.toAbsolutePath().normalize()
        config.saveLocations(config.locations().map { if (it.id == id) it.copy(path = normalized.toString()) else it })
        cachedAll = null
        DesktopDownloadLocation(id, normalized)
    }

    /** The location of [path], judged by the names alone. */
    fun locationContaining(path: Path): DesktopDownloadLocation? {
        val absolute = path.toAbsolutePath().normalize()
        return all().filter { absolute.startsWith(it.path) }.maxByOrNull { it.path.nameCount }
    }

    /** The location a record names, or the one its address is in; null when it names one that is not known. */
    fun ownerOf(locationId: String?, localFileUri: String?): DesktopDownloadLocation? {
        if (locationId != null) return location(locationId)
        localFileUri?.let(::fileOfUri)?.let { file -> locationContaining(file.toPath())?.let { return it } }
        return appLocation()
    }

    /** [ownerOf], and the app folder for a record whose location is not known (for counting and labels only). */
    fun locationOfRecord(locationId: String?, localFileUri: String?): DesktopDownloadLocation =
        ownerOf(locationId, localFileUri) ?: localFileUri?.let(::fileOfUri)?.let { locationContaining(it.toPath()) } ?: appLocation()

    /**
     * Looks for the file of a record at the address the record holds, in the location the record names,
     * and nowhere else. When that location cannot be used the answer is that it is away. No address, or
     * nothing at it, is Missing: a file of the same name is another file and is never substituted
     * ([fileName] is not used to look). A drive that came back under another mount point keeps the path
     * below its mount point, so an address on the old mount point is read at the same path on the new one.
     */
    @Suppress("UNUSED_PARAMETER")
    fun locate(locationId: String?, localFileUri: String?, fileName: String): FileLookup {
        val owner = ownerOf(locationId, localFileUri) ?: return FileLookup.Unreachable(locationId, FolderAvailability.NotFound)
        val state = availability(owner)
        if (state != FolderAvailability.Available) return FileLookup.Unreachable(owner.id, state)
        val recorded = localFileUri?.let(::fileOfUri)?.toPath()?.toAbsolutePath()?.normalize() ?: return FileLookup.Missing
        return FsGuard.call(timeoutMs, FileLookup.Unreachable(owner.id, FolderAvailability.NotConnected)) {
            // The address counts only inside a location that can be used, marker and all.
            val recordedOwner = locationContaining(recorded)
            if (recordedOwner != null && recordedOwner.id != owner.id) {
                val other = availability(recordedOwner)
                if (other != FolderAvailability.Available) return@call FileLookup.Unreachable(recordedOwner.id, other)
            }
            val (location, file) = when {
                recordedOwner != null -> recordedOwner to recorded
                else -> owner to (onRemountedDrive(owner, recorded) ?: recorded)
            }
            val anchor = volumes.anchorOf(file)
            when {
                Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) -> FileLookup.Found(file.toFile().toURI().toString(), location.id)
                // An address on a drive that is not there is not a file that is gone.
                anchor != null && !Files.exists(anchor) -> FileLookup.Unreachable(location.id, FolderAvailability.NotConnected)
                else -> FileLookup.Missing
            }
        }
    }

    /**
     * Where a recorded address is now, by the same rule [locate] uses and without looking at the disk: the
     * address itself, or, when its drive came back under another mount point, the same path below the new
     * one. Null when the record names a location that is not known.
     */
    fun recordedPath(locationId: String?, localFileUri: String?): Path? {
        val recorded = localFileUri?.let(::fileOfUri)?.toPath()?.toAbsolutePath()?.normalize() ?: return null
        val owner = ownerOf(locationId, localFileUri) ?: return null
        if (locationContaining(recorded) != null) return recorded
        return onRemountedDrive(owner, recorded) ?: recorded
    }

    /** [recorded], an address on the mount point the drive of [owner] had before, at the same path on its mount point now. */
    private fun onRemountedDrive(owner: DesktopDownloadLocation, recorded: Path): Path? {
        val anchorNow = volumes.anchorOf(owner.path) ?: return null
        val anchorThen = volumes.anchorOf(recorded) ?: return null
        if (anchorThen == anchorNow) return null
        val moved = anchorNow.resolve(anchorThen.relativize(recorded).toString()).normalize()
        return moved.takeIf { it.startsWith(owner.path) }
    }

    companion object {
        const val AppId = "app"
        const val MarkerName = ".nuvio-downloads"

        /** What a marker of this app holds: a location id. */
        private val MarkerId = Regex("""^loc-[0-9A-Za-z-]{1,40}$""")

        fun fileOfUri(value: String): File? = runCatching {
            if (value.startsWith("file:")) File(URI(value)) else File(value)
        }.getOrNull()

        fun samePath(a: Path, b: Path): Boolean =
            a.normalize() == b.normalize() ||
                (Files.exists(a) && Files.exists(b) && runCatching { Files.isSameFile(a, b) }.getOrDefault(false))
    }
}

/** Checks a folder before it is accepted as a place for downloads. */
internal class DownloadFolderValidator(
    private val locations: DownloadLocations,
    private val appDataDir: Path,
    private val cacheDir: Path,
    private val programDirs: () -> List<Path> = ::defaultProgramDirs,
) {
    fun check(raw: String, requiredBytes: Long): FolderCheck {
        val path = runCatching { Paths.get(raw.trim()) }.getOrNull()
            ?.takeIf { raw.isNotBlank() && it.isAbsolute }?.normalize()
            ?: return FolderCheck.Problem(FolderProblem.NotAFolder)
        if (Files.exists(path) && !Files.isDirectory(path)) return FolderCheck.Problem(FolderProblem.NotAFolder)
        val anchor = locations.volumes.anchorOf(path)
        if (anchor != null && !Files.exists(anchor)) return FolderCheck.Problem(FolderProblem.NotConnected)
        if (isTrash(path)) return FolderCheck.Problem(FolderProblem.IsTrash)

        val resolved = resolve(path)
        val appFolder = resolve(locations.appLocation().path)
        val isAppFolder = resolved == appFolder
        if (!isAppFolder && (inside(resolved, resolve(appDataDir)) || inside(resolved, resolve(cacheDir)))) {
            return FolderCheck.Problem(FolderProblem.InsideAppData)
        }
        if (programDirs().any { inside(resolved, resolve(it)) }) return FolderCheck.Problem(FolderProblem.InsideProgram)
        locations.all().forEach { known ->
            val other = resolve(known.path)
            if (other != resolved && (inside(resolved, other) || inside(other, resolved)) && known.id != DownloadLocations.AppId) {
                return FolderCheck.Problem(FolderProblem.OverlapsDownloadFolder)
            }
        }
        if (!Files.isDirectory(path)) {
            runCatching { Files.createDirectories(path) }.getOrElse { return FolderCheck.Problem(FolderProblem.CannotCreate) }
            if (!Files.isDirectory(path)) return FolderCheck.Problem(FolderProblem.CannotCreate)
        }
        // A file already at the marker's name that is not a marker of ours: the folder is refused, the file untouched.
        if (!locations.markerAllows(path)) return FolderCheck.Problem(FolderProblem.MarkerTaken)
        if (!canWrite(path)) return FolderCheck.Problem(FolderProblem.NotWritable)
        if (requiredBytes > 0L) {
            val free = freeBytes(path)
            if (free != null && requiredBytes > free) {
                return FolderCheck.Problem(FolderProblem.NotEnoughSpace, neededBytes = requiredBytes, freeBytes = free)
            }
        }
        return FolderCheck.Ok
    }

    private fun canWrite(directory: Path): Boolean {
        // A probe with a random name, created without replacing anything. If the name is taken it is left alone.
        repeat(5) {
            val probe = directory.resolve(".nuvio-write-test-${DownloadMover.randomHex(8)}")
            var key: String? = null
            val created = try {
                Files.newByteChannel(probe, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use {
                    key = DownloadMover.identityOf(probe)
                    it.write(java.nio.ByteBuffer.wrap(ByteArray(16) { b -> b.toByte() }))
                }
                true
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                return@repeat
            } catch (_: Exception) {
                return false
            }
            if (created) {
                // Removed only while it is still the probe just created.
                runCatching {
                    if (key != null && !Files.isSymbolicLink(probe) && DownloadMover.identityOf(probe) == key) Files.delete(probe)
                }
                return true
            }
        }
        return false
    }

    private fun inside(path: Path, parent: Path): Boolean = path.startsWith(parent)

    /** [path] with the links of its existing part resolved, so a link cannot hide where it leads. */
    private fun resolve(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        var existing: Path? = absolute
        val rest = ArrayDeque<Path>()
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing.fileName?.let { rest.addFirst(it) }
            existing = existing.parent
        }
        val base = existing?.let { runCatching { it.toRealPath() }.getOrDefault(it) } ?: absolute
        return rest.fold(base) { acc, part -> acc.resolve(part) }
    }

    companion object {
        fun freeBytes(path: Path): Long? = runCatching { Files.getFileStore(path).usableSpace }.getOrNull()

        fun isTrash(path: Path): Boolean {
            val parts = path.map { it.toString().lowercase(Locale.ROOT) }
            if (parts.any { it == ".trash" || it == ".trashes" || it == "\$recycle.bin" || it == "recycler" }) return true
            return path.toString().replace('\\', '/').lowercase(Locale.ROOT).contains("/.local/share/trash")
        }

        fun defaultProgramDirs(): List<Path> {
            val dirs = mutableListOf<Path>()
            System.getProperty("java.home")?.takeIf { it.isNotBlank() }?.let { dirs.add(Paths.get(it)) }
            System.getProperty("compose.application.resources.dir")?.takeIf { it.isNotBlank() }?.let { dirs.add(Paths.get(it)) }
            runCatching {
                val source = Paths.get(DownloadFolderValidator::class.java.protectionDomain.codeSource.location.toURI())
                val bundle = generateSequence(source) { it.parent }.firstOrNull { it.fileName?.toString()?.endsWith(".app") == true }
                dirs.add(bundle ?: source.parent ?: source)
            }
            return dirs
        }
    }
}
