package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
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
import kotlin.io.path.createDirectories

internal actual object DownloadsTrash {
    private val trash = DesktopDownloadsTrash(
        downloadsDir = { DesktopStorage.rootDir.resolve("downloads").also { it.createDirectories() } },
        systemTrash = ::moveToSystemTrash.takeIf { systemTrashIsSupported() },
        otherDirs = {
            // Every other location that can be reached: its files are held on its own volume.
            val folders = DesktopDownloads.folders
            folders.locations.all()
                .filter { it.id != DownloadLocations.AppId && folders.locations.availability(it) == FolderAvailability.Available }
                .map { it.path }
        },
    )

    actual val usesSystemTrash: Boolean get() = trash.usesSystemTrash

    actual fun moveAway(localFileUri: String): Boolean = trash.moveAway(localFileUri)

    actual fun moveWatchedAway(localFileUri: String, stillWanted: () -> Boolean): AutoDownloadRemovalResult =
        trash.moveWatchedAway(localFileUri, stillWanted)

    actual fun isSameFile(firstFileUri: String, secondFileUri: String): Boolean =
        trash.isSameFile(firstFileUri, secondFileUri)

    actual fun purgeHeldFiles(nowEpochMs: Long): Int = trash.purgeHeldFiles(nowEpochMs)

    /** For the mover: one file or folder inside a location, to the Trash. Never deleted. */
    fun moveToTrash(path: Path): Boolean = trash.moveToTrash(path)
}

private fun systemTrashIsSupported(): Boolean = runCatching {
    Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)
}.getOrDefault(false)

private fun moveToSystemTrash(file: File): Boolean =
    runCatching { Desktop.getDesktop().moveToTrash(file) }.getOrDefault(false)

/**
 * Moves a finished download, and the folder of subtitles beside it, out of [downloadsDir].
 *
 * Only a regular file directly inside the downloads folder is moved. A symbolic link is refused and
 * never followed, so nothing outside the folder is touched. With [systemTrash] the file goes to the
 * trash of the system, and a trash that fails leaves the file where it is. Without one the file is
 * moved to a holding folder inside the downloads folder and listed in an index there;
 * [purgeHeldFiles] deletes only what that index lists, once it was held for [HeldFileDays] days.
 */
internal class DesktopDownloadsTrash(
    private val downloadsDir: () -> Path,
    private val systemTrash: ((File) -> Boolean)?,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    /** The other folders downloads are kept in. A file in one of them is held in that folder. */
    private val otherDirs: () -> List<Path> = { emptyList() },
) {
    val usesSystemTrash: Boolean get() = systemTrash != null

    fun moveAway(localFileUri: String): Boolean = synchronized(this) {
        val file = runCatching { Paths.get(URI(localFileUri)) }.getOrNull() ?: return false
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return true
        val root = roots().firstOrNull { file.isRegularFileInside(it) } ?: return false
        val subtitles = file.resolveSibling("${file.fileName}$SubtitlesSuffix").takeIf { it.isDirectoryInside(root) }

        val trash = systemTrash
        if (trash != null) {
            if (!trash(file.toFile())) return false
            // The subtitles are small. When the trash refuses them they stay beside where the file was.
            subtitles?.let { trash(it.toFile()) }
            return true
        }
        if (runCatching { hold(root, file) }.isFailure) return false
        // The file is out. Subtitles that cannot follow it stay beside where it was.
        subtitles?.let { runCatching { hold(root, it) } }
        return true
    }

    /**
     * Keep hard links outside the expiring holding folder until the move is accepted. They use no
     * extra video storage and let cancellation restore both the video and subtitles even after the
     * system has moved them. If links cannot be made on this volume, leave the download alone.
     */
    fun moveWatchedAway(localFileUri: String, stillWanted: () -> Boolean): AutoDownloadRemovalResult = synchronized(this) {
        val trash = systemTrash ?: return AutoDownloadRemovalResult.NotMoved
        if (!stillWanted()) return AutoDownloadRemovalResult.Changed
        val file = runCatching { Paths.get(URI(localFileUri)) }.getOrNull() ?: return AutoDownloadRemovalResult.NotMoved
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return if (stillWanted()) AutoDownloadRemovalResult.Removed else AutoDownloadRemovalResult.Changed
        }
        val root = roots().firstOrNull { file.isRegularFileInside(it) } ?: return AutoDownloadRemovalResult.NotMoved
        val subtitles = file.resolveSibling("${file.fileName}$SubtitlesSuffix").takeIf { it.isDirectoryInside(root) }
        val sources = listOfNotNull(file, subtitles)
        val recovery = runCatching { Files.createTempDirectory(root, ".watched-removal-") }.getOrNull()
            ?: return AutoDownloadRemovalResult.NotMoved
        try {
            sources.forEach { linkForRecovery(it, recovery.resolve(it.fileName)) }
        } catch (_: Exception) {
            runCatching { deleteWithoutFollowingLinks(recovery) }
            return AutoDownloadRemovalResult.NotMoved
        }

        fun restore() {
            sources.forEach { source ->
                val saved = recovery.resolve(source.fileName)
                restoreRecovery(saved, source)
            }
        }

        var accepted = false
        try {
            if (!stillWanted()) return AutoDownloadRemovalResult.Changed
            val moved = runCatching { trash(file.toFile()) }.getOrDefault(false)
            if (!stillWanted()) return AutoDownloadRemovalResult.Changed
            if (!moved || Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return AutoDownloadRemovalResult.NotMoved
            subtitles?.let { runCatching { trash(it.toFile()) } }
            if (!stillWanted()) return AutoDownloadRemovalResult.Changed
            accepted = true
            AutoDownloadRemovalResult.Removed
        } finally {
            // If restoring fails, keep the recovery links. Never discard the only remaining copy.
            if (accepted || runCatching { restore() }.isSuccess) {
                runCatching { deleteWithoutFollowingLinks(recovery) }
            }
        }
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
        if (Files.isSymbolicLink(path) || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false
        val real = runCatching { path.parent.toRealPath().resolve(path.fileName) }.getOrNull() ?: return false
        val root = roots().firstOrNull { real.startsWith(it) && real != it } ?: return false
        if (real.startsWith(root.resolve(HoldingFolderName))) return false
        val trash = systemTrash
        if (trash != null) return trash(real.toFile()) && !Files.exists(real, LinkOption.NOFOLLOW_LINKS)
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
        roots().sumOf { purgeHeldFilesIn(it, nowEpochMs) }
    }

    private fun roots(): List<Path> =
        (listOf(runCatching { downloadsDir() }.getOrNull()) + runCatching { otherDirs() }.getOrDefault(emptyList()))
            .filterNotNull()
            .mapNotNull { runCatching { it.toRealPath() }.getOrNull() }
            .distinct()

    private fun purgeHeldFilesIn(root: Path, nowEpochMs: Long): Int {
        val holding = root.resolve(HoldingFolderName)
        if (!holding.isDirectoryInside(root)) return 0
        val maxAgeMs = HeldFileDays * 24L * 60 * 60 * 1000
        var purged = 0
        val remaining = readIndex(holding).filter { (heldAtEpochMs, name) ->
            if (nowEpochMs - heldAtEpochMs < maxAgeMs) return@filter true
            val entry = holding.resolve(name)
            // Only a name this class wrote, directly inside the holding folder, and never through a link.
            val isOwn = entry.parent == holding && entry.fileName.toString() == name
            when {
                !isOwn -> false
                !Files.exists(entry, LinkOption.NOFOLLOW_LINKS) -> false
                runCatching { deleteWithoutFollowingLinks(entry) }.isSuccess -> {
                    purged++
                    false
                }
                else -> true
            }
        }
        runCatching { writeIndex(holding, remaining) }
        return purged
    }

    private fun hold(root: Path, source: Path) {
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

    private fun Path.isRegularFileInside(root: Path): Boolean =
        !Files.isSymbolicLink(this) &&
            Files.isRegularFile(this, LinkOption.NOFOLLOW_LINKS) &&
            runCatching { toRealPath().parent == root }.getOrDefault(false)

    private fun Path.isDirectoryInside(root: Path): Boolean =
        !Files.isSymbolicLink(this) &&
            Files.isDirectory(this, LinkOption.NOFOLLOW_LINKS) &&
            runCatching { toRealPath().parent == root }.getOrDefault(false)

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
        private const val SubtitlesSuffix = ".subtitles"
    }
}
