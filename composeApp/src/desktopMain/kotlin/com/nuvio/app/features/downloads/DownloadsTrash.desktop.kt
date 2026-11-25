package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import kotlin.io.path.createDirectories

/** Native macOS NSFileManager trashItemAtURL wrapper in the player bridge library. */
internal object MacTrashNative {
    external fun trashItem(path: String): String?
}

internal sealed interface SystemTrashOutcome {
    data class Moved(val resultingPath: Path?) : SystemTrashOutcome
    data object Failed : SystemTrashOutcome
}

internal actual object DownloadsTrash {
    private val trash = DesktopDownloadsTrash(
        downloadsDir = { DesktopStorage.rootDir.resolve("downloads").also { it.createDirectories() } },
        systemTrash = ::moveToSystemTrash.takeIf { systemTrashIsSupported() },
        systemTrashWithResult = ::moveToSystemTrashWithUndoPath.takeIf { isMacOs },
        isUndoStillPending = AutoDownloadStorage::isArchivePathReferencedByUndo,
        otherDirs = {
            // Every other location that can be reached: its files are held on its own volume.
            val folders = DesktopDownloads.folders
            folders.locations.all()
                .filter { it.id != DownloadLocations.AppId && folders.locations.availability(it) == FolderAvailability.Available }
                .map { it.path }
        },
    )

    actual val usesSystemTrash: Boolean get() = trash.usesSystemTrash
    actual val usesUndoableSystemTrash: Boolean get() = trash.usesUndoableSystemTrash
    actual val supportsWatchedCleanup: Boolean = true

    actual fun moveAway(localFileUri: String): Boolean = trash.moveAway(localFileUri)

    actual fun moveWatchedAway(localFileUri: String, stillWanted: () -> Boolean): AutoDownloadRemovalResult =
        trash.moveWatchedAway(localFileUri, stillWanted)

    actual fun moveWatchedAwayWithUndo(
        localFileUri: String,
        allowHoldingWhenNoSystemTrash: Boolean,
        stillWanted: () -> Boolean,
        onMoved: (TrashedWatchedFile?) -> Boolean,
    ): WatchedTrashOutcome = trash.moveWatchedAwayWithUndo(
        localFileUri = localFileUri,
        allowHoldingWhenNoSystemTrash = allowHoldingWhenNoSystemTrash,
        stillWanted = stillWanted,
        onMoved = onMoved,
    )

    actual fun restoreFromTrash(trashed: TrashedWatchedFile): UndoTrashResult = trash.restoreFromTrash(trashed)

    actual fun isSameFile(firstFileUri: String, secondFileUri: String): Boolean =
        trash.isSameFile(firstFileUri, secondFileUri)

    actual fun purgeHeldFiles(nowEpochMs: Long): Int = trash.purgeHeldFiles(nowEpochMs)

    /** For the mover: one file or folder inside a location, to the Trash. Never deleted. */
    fun moveToTrash(path: Path): Boolean = trash.moveToTrash(path)
}

private val isMacOs: Boolean =
    System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("mac")

private fun systemTrashIsSupported(): Boolean =
    isMacOs || runCatching {
        Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)
    }.getOrDefault(false)

private fun moveToSystemTrash(file: File): Boolean {
    val path = file.toPath()
    if (moveToSystemTrashWithUndoPath(path) is SystemTrashOutcome.Moved) return true
    return runCatching {
        Desktop.isDesktopSupported() &&
            Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH) &&
            Desktop.getDesktop().moveToTrash(file)
    }.getOrDefault(false)
}

/**
 * Moves [path] to the system Trash only when the platform reports the resulting path in Trash so
 * Undo can restore it. Never falls back to [Desktop.moveToTrash], which discards the destination.
 */
private fun moveToSystemTrashWithUndoPath(path: Path): SystemTrashOutcome {
    if (!isMacOs) return SystemTrashOutcome.Failed
    return runCatching {
        NativePlayerBridge.ensureLoaded()
        val resulting = MacTrashNative.trashItem(path.toString())?.takeIf { it.isNotBlank() }?.let(Paths::get)
        if (resulting != null && Files.exists(resulting, LinkOption.NOFOLLOW_LINKS)) {
            SystemTrashOutcome.Moved(resulting)
        } else {
            SystemTrashOutcome.Failed
        }
    }.getOrDefault(SystemTrashOutcome.Failed)
}

/**
 * Moves a finished download, and the folder of subtitles beside it, out of [downloadsDir].
 *
 * Only a regular file directly inside the downloads folder is moved. A symbolic link is refused and
 * never followed, so nothing outside the folder is touched. With [systemTrash] the file goes to the
 * trash of the system, and a trash that fails leaves the file where it is. Without one the file is
 * moved to an archive folder inside the downloads folder and listed in an index there;
 * [purgeHeldFiles] never permanently deletes archived files—once [systemTrash] is available it moves
 * aged archived entries to the system Trash, and otherwise leaves them archived.
 */
internal class DesktopDownloadsTrash(
    private val downloadsDir: () -> Path,
    private val systemTrash: ((File) -> Boolean)?,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    /** The other folders downloads are kept in. A file in one of them is held in that folder. */
    private val otherDirs: () -> List<Path> = { emptyList() },
    /** Optional Trash mover that reports the destination path in Trash so Undo can restore it. */
    private val systemTrashWithResult: ((Path) -> SystemTrashOutcome)? = null,
    /** Whether an archived Undo entry is still referenced by a profile's Recently deleted Undo list. */
    private val isUndoStillPending: ((Path) -> Boolean)? = null,
) {
    val usesSystemTrash: Boolean get() = systemTrash != null || systemTrashWithResult != null
    val usesUndoableSystemTrash: Boolean get() = systemTrashWithResult != null

    private fun invokeSystemTrash(path: Path, requireResultingPath: Boolean = false): SystemTrashOutcome? {
        systemTrashWithResult?.let { return it(path) }
        if (requireResultingPath) return null
        val legacy = systemTrash ?: return null
        return if (legacy(path.toFile())) SystemTrashOutcome.Moved(null) else SystemTrashOutcome.Failed
    }

    fun moveAway(localFileUri: String): Boolean = synchronized(this) {
        val requested = runCatching { Paths.get(URI(localFileUri)) }.getOrNull() ?: return false
        if (!Files.exists(requested, LinkOption.NOFOLLOW_LINKS)) return true
        val (root, file) = locate(requested, directChild = true) ?: return false
        if (!file.isRegularFileInside(root)) return false
        val subtitles = file.resolveSibling("${file.fileName}$SubtitlesSuffix").takeIf { it.isDirectoryInside(root) }

        if (usesSystemTrash) {
            if (invokeSystemTrash(file) !is SystemTrashOutcome.Moved) return false
            // The subtitles are small. When the trash refuses them they stay beside where the file was.
            subtitles?.let { invokeSystemTrash(it) }
            return true
        }
        if (runCatching { hold(root, file) }.isFailure) return false
        // The file is out. Subtitles that cannot follow it stay beside where it was.
        subtitles?.let { runCatching { hold(root, it) } }
        return true
    }

    /**
     * Keep hard links beside the download until the move is accepted. They use no extra video
     * storage and let cancellation restore both the video and subtitles even after the system has
     * moved them. If links cannot be made on this volume, leave the download alone.
     */
    fun moveWatchedAway(localFileUri: String, stillWanted: () -> Boolean): AutoDownloadRemovalResult =
        moveWatchedInternal(
            localFileUri = localFileUri,
            allowHoldingWhenNoSystemTrash = false,
            requireUndo = false,
            stillWanted = stillWanted,
            onMoved = { true },
        ).result

    fun moveWatchedAwayWithUndo(
        localFileUri: String,
        allowHoldingWhenNoSystemTrash: Boolean = false,
        stillWanted: () -> Boolean = { true },
        onMoved: (TrashedWatchedFile?) -> Boolean = { true },
    ): WatchedTrashOutcome = moveWatchedInternal(
        localFileUri = localFileUri,
        allowHoldingWhenNoSystemTrash = allowHoldingWhenNoSystemTrash,
        requireUndo = true,
        stillWanted = stillWanted,
        onMoved = onMoved,
    )

    private fun moveWatchedInternal(
        localFileUri: String,
        allowHoldingWhenNoSystemTrash: Boolean,
        requireUndo: Boolean,
        stillWanted: () -> Boolean,
        onMoved: (TrashedWatchedFile?) -> Boolean,
    ): WatchedTrashOutcome = synchronized(this) {
        val trashAvailable = if (requireUndo) usesUndoableSystemTrash else usesSystemTrash
        if (!trashAvailable && !allowHoldingWhenNoSystemTrash) {
            return WatchedTrashOutcome(AutoDownloadRemovalResult.NotMoved)
        }
        if (!stillWanted()) return WatchedTrashOutcome(AutoDownloadRemovalResult.Changed)
        val requested = runCatching { Paths.get(URI(localFileUri)) }.getOrNull()
            ?: return WatchedTrashOutcome(AutoDownloadRemovalResult.NotMoved)
        if (!Files.exists(requested, LinkOption.NOFOLLOW_LINKS)) {
            if (!stillWanted()) return WatchedTrashOutcome(AutoDownloadRemovalResult.Changed)
            return if (runCatching { onMoved(null) }.getOrDefault(false)) {
                WatchedTrashOutcome(AutoDownloadRemovalResult.Removed)
            } else {
                WatchedTrashOutcome(AutoDownloadRemovalResult.NotMoved)
            }
        }
        val (root, file) = locate(requested, directChild = true)
            ?: return WatchedTrashOutcome(AutoDownloadRemovalResult.NotMoved)
        if (!file.isRegularFileInside(root)) return WatchedTrashOutcome(AutoDownloadRemovalResult.NotMoved)
        val fileIdentity = DownloadMover.identityOf(file)
        val subtitles = file.resolveSibling("${file.fileName}$SubtitlesSuffix").takeIf { it.isDirectoryInside(root) }
        val recordedSubtitlesUri = subtitles?.let { requested.resolveSibling("${requested.fileName}$SubtitlesSuffix").toUri().toString() }

        if (!trashAvailable) {
            if (!stillWanted()) return WatchedTrashOutcome(AutoDownloadRemovalResult.Changed)
            val heldVideo = runCatching { hold(root, file) }.getOrNull()
                ?: return WatchedTrashOutcome(AutoDownloadRemovalResult.NotMoved)
            val heldSubs = if (subtitles != null) {
                runCatching { hold(root, subtitles) }.getOrNull() ?: run {
                    val holding = root.resolve(HoldingFolderName)
                    runCatching { Files.move(heldVideo, file) }
                    if (holding.isDirectoryInside(root)) {
                        val updated = readIndex(holding).filterNot { (_, name) -> name == heldVideo.fileName.toString() }
                        runCatching { writeIndex(holding, updated) }
                    }
                    return WatchedTrashOutcome(AutoDownloadRemovalResult.NotMoved)
                }
            } else {
                null
            }
            val trashed = TrashedWatchedFile(
                originalFileUri = localFileUri,
                trashedFileUri = heldVideo.toUri().toString(),
                originalSubtitlesUri = recordedSubtitlesUri,
                trashedSubtitlesUri = heldSubs?.toUri()?.toString(),
                fileKey = fileIdentity,
                heldInArchive = true,
            )
            if (requireUndo) {
                val holding = root.resolve(HoldingFolderName)
                val addedUndoNames = listOfNotNull(
                    heldVideo.fileName?.toString(),
                    heldSubs?.fileName?.toString(),
                )
                if (runCatching { writeUndoNames(holding, readUndoNames(holding) + addedUndoNames) }.isFailure) {
                    runCatching { restoreFromTrash(trashed) }
                    return WatchedTrashOutcome(AutoDownloadRemovalResult.NotMoved)
                }
            }
            if (!runCatching { onMoved(trashed) }.getOrDefault(false)) {
                runCatching { restoreFromTrash(trashed) }
                return WatchedTrashOutcome(AutoDownloadRemovalResult.NotMoved)
            }
            return WatchedTrashOutcome(
                result = AutoDownloadRemovalResult.Removed,
                trashed = trashed,
            )
        }

        val sources = listOfNotNull(file, subtitles)
        val recovery = runCatching { Files.createTempDirectory(root, ".watched-removal-") }.getOrNull()
            ?: return WatchedTrashOutcome(AutoDownloadRemovalResult.NotMoved)
        try {
            sources.forEach { linkForRecovery(it, recovery.resolve(it.fileName)) }
        } catch (_: Exception) {
            runCatching { deleteWithoutFollowingLinks(recovery) }
            return WatchedTrashOutcome(AutoDownloadRemovalResult.NotMoved)
        }

        var accepted = false
        var trashedVideoPath: Path? = null
        var trashedSubsPath: Path? = null

        fun moveBackFromTrashIfPossible() {
            if (trashedVideoPath != null &&
                Files.exists(trashedVideoPath, LinkOption.NOFOLLOW_LINKS) &&
                !Files.exists(file, LinkOption.NOFOLLOW_LINKS)
            ) {
                runCatching { Files.move(trashedVideoPath, file) }
            }
            if (subtitles != null &&
                trashedSubsPath != null &&
                Files.exists(trashedSubsPath, LinkOption.NOFOLLOW_LINKS) &&
                !Files.exists(subtitles, LinkOption.NOFOLLOW_LINKS)
            ) {
                runCatching { Files.move(trashedSubsPath, subtitles) }
            }
        }

        fun restore() {
            sources.forEach { source ->
                val saved = recovery.resolve(source.fileName)
                restoreRecovery(saved, source)
            }
        }

        try {
            if (!stillWanted()) return WatchedTrashOutcome(AutoDownloadRemovalResult.Changed)
            val videoOutcome = runCatching { invokeSystemTrash(file, requireResultingPath = requireUndo) }.getOrNull()
            trashedVideoPath = (videoOutcome as? SystemTrashOutcome.Moved)?.resultingPath
            if (!stillWanted()) return WatchedTrashOutcome(AutoDownloadRemovalResult.Changed)
            if (videoOutcome !is SystemTrashOutcome.Moved || Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return WatchedTrashOutcome(AutoDownloadRemovalResult.NotMoved)
            }
            if (requireUndo && (trashedVideoPath == null || !Files.exists(trashedVideoPath, LinkOption.NOFOLLOW_LINKS))) {
                return WatchedTrashOutcome(AutoDownloadRemovalResult.NotMoved)
            }
            if (subtitles != null) {
                val subsOutcome = runCatching { invokeSystemTrash(subtitles, requireResultingPath = requireUndo) }.getOrNull()
                trashedSubsPath = (subsOutcome as? SystemTrashOutcome.Moved)?.resultingPath
                if (requireUndo && (
                        subsOutcome !is SystemTrashOutcome.Moved ||
                            Files.exists(subtitles, LinkOption.NOFOLLOW_LINKS) ||
                            trashedSubsPath == null ||
                            !Files.exists(trashedSubsPath, LinkOption.NOFOLLOW_LINKS)
                        )
                ) {
                    return WatchedTrashOutcome(AutoDownloadRemovalResult.NotMoved)
                }
            }
            if (!stillWanted()) return WatchedTrashOutcome(AutoDownloadRemovalResult.Changed)
            val trashed = TrashedWatchedFile(
                originalFileUri = localFileUri,
                trashedFileUri = trashedVideoPath?.toUri()?.toString(),
                originalSubtitlesUri = recordedSubtitlesUri,
                trashedSubtitlesUri = trashedSubsPath?.toUri()?.toString(),
                fileKey = fileIdentity,
                heldInArchive = false,
            )
            if (requireUndo && !trashed.canRestore) {
                return WatchedTrashOutcome(AutoDownloadRemovalResult.NotMoved)
            }
            if (!runCatching { onMoved(trashed) }.getOrDefault(false)) {
                return WatchedTrashOutcome(AutoDownloadRemovalResult.NotMoved)
            }
            accepted = true
            return WatchedTrashOutcome(
                result = AutoDownloadRemovalResult.Removed,
                trashed = trashed,
            )
        } finally {
            // If restoring fails, keep the recovery links. Never discard the only remaining copy.
            if (!accepted) {
                moveBackFromTrashIfPossible()
            }
            if (accepted || runCatching { restore() }.isSuccess) {
                runCatching { deleteWithoutFollowingLinks(recovery) }
            }
        }
    }

    /**
     * Restores a file previously moved via [moveWatchedAwayWithUndo] back to its original download folder.
     * Only touches the Trash path here (when Undo is pressed), never on a background poll.
     */
    fun restoreFromTrash(trashed: TrashedWatchedFile): UndoTrashResult = synchronized(this) {
        val original = runCatching { Paths.get(URI(trashed.originalFileUri)) }.getOrNull()
            ?: return UndoTrashResult.NotRestored
        val (root, target) = locate(original, directChild = true)
            ?: return UndoTrashResult.NotReachable
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return UndoTrashResult.TargetTaken
        }
        val trashedUri = trashed.trashedFileUri?.takeIf { it.isNotBlank() }
            ?: return UndoTrashResult.NotRestored
        val trashedPath = runCatching { Paths.get(URI(trashedUri)) }.getOrNull()
            ?: return UndoTrashResult.NotRestored
        if (!Files.exists(trashedPath, LinkOption.NOFOLLOW_LINKS)) {
            return UndoTrashResult.FileGone
        }
        if (Files.isSymbolicLink(trashedPath) || !Files.isRegularFile(trashedPath, LinkOption.NOFOLLOW_LINKS)) {
            return UndoTrashResult.FileGone
        }
        if (trashed.fileKey != null) {
            val actualKey = DownloadMover.identityOf(trashedPath)
            if (actualKey != null && actualKey != trashed.fileKey) {
                return UndoTrashResult.FileGone
            }
        }
        if (runCatching { Files.move(trashedPath, target) }.isFailure) {
            return if (!Files.exists(trashedPath, LinkOption.NOFOLLOW_LINKS)) UndoTrashResult.FileGone else UndoTrashResult.NotRestored
        }
        if (trashed.heldInArchive) {
            val holding = root.resolve(HoldingFolderName)
            if (holding.isDirectoryInside(root)) {
                val restoredNames = buildSet {
                    add(trashedPath.fileName.toString())
                    trashed.trashedSubtitlesUri?.let { subsUri ->
                        runCatching { Paths.get(URI(subsUri)).fileName?.toString() }.getOrNull()?.let(::add)
                    }
                }
                val updated = readIndex(holding).filterNot { (_, name) -> name in restoredNames }
                runCatching { writeIndex(holding, updated) }
                val updatedUndo = readUndoNames(holding) - restoredNames
                runCatching { writeUndoNames(holding, updatedUndo) }
            }
        }
        val origSubsUri = trashed.originalSubtitlesUri
        val trashedSubsUri = trashed.trashedSubtitlesUri
        if (!origSubsUri.isNullOrBlank() && !trashedSubsUri.isNullOrBlank()) {
            val origSubs = runCatching { Paths.get(URI(origSubsUri)) }.getOrNull()
            val trashedSubs = runCatching { Paths.get(URI(trashedSubsUri)) }.getOrNull()
            val locatedSubs = origSubs?.let { locate(it, directChild = true) }
            if (locatedSubs != null && locatedSubs.root == root && trashedSubs != null &&
                !Files.exists(locatedSubs.file, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(trashedSubs) &&
                Files.isDirectory(trashedSubs, LinkOption.NOFOLLOW_LINKS)
            ) {
                runCatching { Files.move(trashedSubs, locatedSubs.file) }
            }
        }
        return UndoTrashResult.Restored
    }

    private fun restoreRecovery(saved: Path, source: Path) {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.move(saved, source)
        } else if (Files.isDirectory(saved, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(source) && Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
        ) {
            Files.newDirectoryStream(saved).use { children ->
                children.forEach { restoreRecovery(it, source.resolve(it.fileName)) }
            }
        } else if (Files.isSymbolicLink(source) || !Files.isSameFile(saved, source)) {
            throw IOException("The original path has changed; its recovery copy has been kept")
        }
    }

    private fun linkForRecovery(source: Path, destination: Path) {
        if (Files.isSymbolicLink(source)) throw IOException("A link cannot be saved for removal")
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(destination)
            Files.newDirectoryStream(source).use { children ->
                children.forEach { linkForRecovery(it, destination.resolve(it.fileName)) }
            }
        } else {
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw IOException("Not a regular file")
            Files.createLink(destination, source)
        }
    }

    /**
     * Sends one file or folder that is inside a downloads folder (at any depth, never the folder itself
     * or its holding folder) to the Trash of the system, or, without one, to the holding folder of that
     * downloads folder. A link is refused and never followed. Nothing is deleted. True when it is out.
     */
    fun moveToTrash(path: Path): Boolean = synchronized(this) {
        val (root, real) = locate(path) ?: return false
        if (!Files.exists(real, LinkOption.NOFOLLOW_LINKS)) return false
        if (real.startsWith(root.resolve(HoldingFolderName))) return false
        val key = DownloadMover.identityOf(real) ?: return false
        if (usesSystemTrash &&
            runCatching { invokeSystemTrash(real) is SystemTrashOutcome.Moved }.getOrDefault(false) &&
            !Files.exists(real, LinkOption.NOFOLLOW_LINKS)
        ) return true
        // Trash can be supported but unavailable now. Hold only the same file we offered it.
        if (!Files.exists(real, LinkOption.NOFOLLOW_LINKS) || real.hasLinkedComponentBelow(root) ||
            DownloadMover.identityOf(real) != key
        ) return false
        return runCatching { hold(root, real) }.isSuccess
    }

    /** Whether two addresses lead to the same file once links and relative parts are resolved. */
    fun isSameFile(firstFileUri: String, secondFileUri: String): Boolean {
        val first = runCatching { Paths.get(URI(firstFileUri)) }.getOrNull() ?: return false
        val second = runCatching { Paths.get(URI(secondFileUri)) }.getOrNull() ?: return false
        return first.normalize() == second.normalize() ||
            runCatching { Files.isSameFile(first, second) }.getOrDefault(false)
    }

    fun purgeHeldFiles(nowEpochMs: Long): Int = synchronized(this) {
        // Held videos and partials are never permanently deleted; aged entries move to Trash when available.
        if (!usesSystemTrash) return 0
        return roots().map { it.real }.distinct().sumOf { purgeHeldFilesIn(it, nowEpochMs) }
    }

    private data class Root(val configured: Path, val real: Path)
    private data class LocatedPath(val root: Path, val file: Path)

    private fun roots(): List<Root> =
        (listOf(runCatching { downloadsDir() }.getOrNull()) + runCatching { otherDirs() }.getOrDefault(emptyList()))
            .filterNotNull()
            .mapNotNull { path -> runCatching {
                val configured = path.toAbsolutePath().normalize()
                Root(configured, configured.toRealPath())
            }.getOrNull() }
            .distinct()

    /** The configured folder and its real address are trusted; links below either are refused. */
    private fun locate(path: Path, directChild: Boolean = false): LocatedPath? {
        val supplied = path.toAbsolutePath()
        val absolute = supplied.normalize()
        return roots().firstNotNullOfOrNull { root ->
            val anchor = listOf(root.configured, root.real).firstOrNull {
                supplied.startsWith(it) && absolute.startsWith(it) && absolute != it
            } ?: return@firstNotNullOfOrNull null
            if (supplied.hasLinkedComponentBelow(anchor)) return@firstNotNullOfOrNull null
            val real = root.real.resolve(anchor.relativize(absolute))
            if ((directChild && real.parent != root.real) || real.hasLinkedComponentBelow(root.real)) return@firstNotNullOfOrNull null
            if (runCatching { supplied.parent.toRealPath() != real.parent }.getOrDefault(true)) return@firstNotNullOfOrNull null
            LocatedPath(root.real, real)
        }
    }

    private fun purgeHeldFilesIn(root: Path, nowEpochMs: Long): Int {
        // Never permanently delete archived user files: without a working system Trash they stay
        // in the archive folder until Trash is available or the user removes them themselves.
        if (!usesSystemTrash) return 0
        val holding = root.resolve(HoldingFolderName)
        if (!holding.isDirectoryInside(root)) return 0
        val maxAgeMs = HeldFileDays * 24L * 60 * 60 * 1000
        var movedToTrash = 0
        val undoNames = readUndoNames(holding).toMutableSet()
        val remaining = readIndex(holding).filter { (heldAtEpochMs, name) ->
            val entry = holding.resolve(name)
            // Only a name this class wrote, directly inside the archive folder, and never through a link.
            val isOwn = entry.parent == holding && entry.fileName.toString() == name
            if (!isOwn || !Files.exists(entry, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(entry)) {
                undoNames.remove(name)
                return@filter false
            }
            if (name in undoNames) {
                val stillPending = isUndoStillPending?.let { check ->
                    runCatching { check(entry) }.getOrDefault(true)
                } ?: true
                if (stillPending) {
                    return@filter true
                }
                undoNames.remove(name)
            }
            if (nowEpochMs - heldAtEpochMs < maxAgeMs) return@filter true
            if (runCatching { invokeSystemTrash(entry) is SystemTrashOutcome.Moved && !Files.exists(entry, LinkOption.NOFOLLOW_LINKS) }.getOrDefault(false)) {
                movedToTrash++
                false
            } else {
                true
            }
        }
        runCatching { writeIndex(holding, remaining) }
        val remainingNames = remaining.mapTo(HashSet()) { it.second }
        runCatching { writeUndoNames(holding, undoNames.intersect(remainingNames)) }
        return movedToTrash
    }

    private fun hold(root: Path, source: Path): Path {
        val holding = root.resolve(HoldingFolderName)
        if (!Files.exists(holding, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(holding)
        if (!holding.isDirectoryInside(root)) throw IOException("The holding folder is not a folder of the downloads folder")
        val heldAt = nowEpochMs()
        val name = "$heldAt-${source.fileName}"
        val destination = holding.resolve(name)
        // Something that is already there is not this class's to list or to replace.
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw IOException("$name is already held")
        // Listed before the move, so a held file is never left without its entry.
        val listed = readIndex(holding)
        writeIndex(holding, listed + (heldAt to name))
        try {
            Files.move(source, destination)
            return destination
        } catch (error: IOException) {
            runCatching { writeIndex(holding, listed) }
            throw error
        }
    }

    private fun readIndex(holding: Path): List<Pair<Long, String>> {
        val index = holding.resolve(IndexFileName)
        if (!Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return runCatching { Files.readAllLines(index) }.getOrDefault(emptyList()).mapNotNull { line ->
            val heldAt = line.substringBefore('\t').toLongOrNull() ?: return@mapNotNull null
            val name = line.substringAfter('\t', missingDelimiterValue = "")
            if (name.isBlank() || name.contains('/') || name.contains('\\') || name == "." || name == "..") return@mapNotNull null
            heldAt to name
        }
    }

    private fun writeIndex(holding: Path, entries: List<Pair<Long, String>>) {
        val index = holding.resolve(IndexFileName)
        if (Files.isSymbolicLink(index)) throw IOException("The index of held files is a link")
        Files.write(index, entries.map { (heldAt, name) -> "$heldAt\t$name" })
    }

    private fun readUndoNames(holding: Path): Set<String> {
        val undoIndex = holding.resolve(UndoIndexFileName)
        if (!Files.isRegularFile(undoIndex, LinkOption.NOFOLLOW_LINKS)) return emptySet()
        return runCatching { Files.readAllLines(undoIndex) }.getOrDefault(emptyList()).mapNotNullTo(linkedSetOf()) { line ->
            val name = line.trim()
            if (name.isBlank() || name.contains('/') || name.contains('\\') || name == "." || name == "..") null else name
        }
    }

    private fun writeUndoNames(holding: Path, names: Set<String>) {
        val undoIndex = holding.resolve(UndoIndexFileName)
        if (Files.isSymbolicLink(undoIndex)) throw IOException("The Undo index of held files is a link")
        if (names.isEmpty()) {
            Files.deleteIfExists(undoIndex)
        } else {
            Files.write(undoIndex, names.toList())
        }
    }

    private fun Path.hasLinkedComponentBelow(root: Path): Boolean {
        val absolute = toAbsolutePath()
        if (!absolute.startsWith(root) || absolute == root) return true
        return generateSequence(absolute) { it.parent }.takeWhile { it != root }.any { Files.isSymbolicLink(it) }
    }

    private fun Path.isRegularFileInside(root: Path): Boolean =
        !hasLinkedComponentBelow(root) &&
            Files.isRegularFile(this, LinkOption.NOFOLLOW_LINKS) &&
            runCatching { toRealPath().parent == root }.getOrDefault(false)

    private fun Path.isDirectoryInside(root: Path): Boolean =
        !hasLinkedComponentBelow(root) &&
            Files.isDirectory(this, LinkOption.NOFOLLOW_LINKS) &&
            runCatching { toRealPath().parent == root }.getOrDefault(false)

    /** Removes a temporary recovery folder this class just made. Never a held video or partial. */
    private fun deleteWithoutFollowingLinks(entry: Path) {
        Files.walkFileTree(
            entry,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    companion object {
        const val HoldingFolderName = ".removed"
        const val IndexFileName = "index"
        const val UndoIndexFileName = "undo"
        private const val SubtitlesSuffix = ".subtitles"
    }
}
