package com.nuvio.app.core.storage

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException

/**
 * Desktop files when an account session ends.
 * A lost session deletes nothing here. Sign-out copies the data folder (except the videos) into
 * `signed-out-<yyyyMMdd-HHmmss>/` and only then may the caller clear. A failed copy clears nothing.
 * Older sign-out copies are left where they are: copying them again would double the folder each time.
 */
internal object DesktopAccountFiles {
    var root: () -> Path = { DesktopStorage.rootDir }
    var clock: () -> LocalDateTime = { LocalDateTime.now() }

    /** Leaves every file under [root]. The caller only clears the account session. */
    fun onLostSession(root: Path) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return
    }

    fun beforeSignOut(root: Path, now: LocalDateTime = clock()): Result<Unit> {
        val copied = copyBeforeClearing(root, now)
        return copied.map { }
    }

    /**
     * Copies, then [clear]. [clear] is not called when the copy throws.
     * The app's sign-out uses [beforeSignOut] and then its existing clear, which goes through
     * [DesktopStorage.wipeFolder] and therefore keeps the dated copy.
     */
    fun signOut(
        root: Path,
        now: LocalDateTime = clock(),
        transfer: (Path, Path) -> Unit = ::copyEntry,
        clear: (Path) -> Unit = { path -> DesktopStorage.wipeFolder(path, DesktopStorage.KeptOnWipe) },
    ): Result<Path> {
        val copied = copyBeforeClearing(root, now, transfer)
        if (copied.isFailure) return copied
        clear(root)
        return copied
    }

    private fun copyBeforeClearing(
        root: Path,
        now: LocalDateTime,
        transfer: (Path, Path) -> Unit = ::copyEntry,
    ): Result<Path> {
        return try {
            Result.success(copyExceptDownloads(root, now, transfer))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    internal fun copyExceptDownloads(root: Path, now: LocalDateTime, transfer: (Path, Path) -> Unit): Path {
        Files.createDirectories(root)
        val dest = root.resolve(uniqueCopyName(root, now))
        Files.createDirectories(dest)
        val skipTop = mutableSetOf("downloads", dest.fileName.toString())
        Files.newDirectoryStream(root).use { stream ->
            stream.forEach { child ->
                val name = child.fileName.toString()
                if (DesktopStorage.isSignedOutCopyName(name) && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    skipTop.add(name)
                }
            }
        }
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir == root) return FileVisitResult.CONTINUE
                if (skipped(dir)) return FileVisitResult.SKIP_SUBTREE
                transfer(dir, dest.resolve(root.relativize(dir)))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (skipped(file)) return FileVisitResult.CONTINUE
                transfer(file, dest.resolve(root.relativize(file)))
                return FileVisitResult.CONTINUE
            }

            private fun skipped(path: Path): Boolean {
                if (path == dest || path.startsWith(dest)) return true
                val relative = root.relativize(path)
                return relative.nameCount > 0 && relative.getName(0).toString() in skipTop
            }
        })
        return dest
    }

    private fun uniqueCopyName(root: Path, now: LocalDateTime): String {
        val base = "signed-out-${now.format(stamp)}"
        if (!Files.exists(root.resolve(base))) return base
        var n = 2
        while (Files.exists(root.resolve("$base-$n"))) n++
        return "$base-$n"
    }

    private fun copyEntry(source: Path, target: Path) {
        if (Files.isSymbolicLink(source)) {
            Files.createDirectories(target.parent)
            Files.copy(source, target, LinkOption.NOFOLLOW_LINKS)
            return
        }
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(target)
            return
        }
        if (target.parent != null) Files.createDirectories(target.parent)
        Files.copy(source, target, LinkOption.NOFOLLOW_LINKS)
    }

    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
}
