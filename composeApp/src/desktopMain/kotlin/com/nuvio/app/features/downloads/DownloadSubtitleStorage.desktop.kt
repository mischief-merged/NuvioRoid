package com.nuvio.app.features.downloads

import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * The folder beside a video, `<video file name>.subtitles/`, where everything saved for it lives. The app
 * only ever writes into, replaces in or removes from a folder it made itself, which it marks when it makes
 * it ([SidecarOwnership.MarkerName], created with create-new). A folder of that name that is already there
 * without the mark is someone else's: nothing is written there, unless its contents are exactly what this
 * app saves (a one-time adoption for folders made before folders were marked).
 */
internal actual class DownloadSubtitleStorage actual constructor(localVideoUri: String) {
    private val video = File(URI(localVideoUri))
    private val directory = File(video.path + ".subtitles")

    actual fun read(fileName: String): String? =
        runCatching { file(fileName).takeIf(File::isFile)?.readText(Charsets.UTF_8) }.getOrNull()

    actual fun write(fileName: String, text: String) = writeBytes(fileName, text.toByteArray(Charsets.UTF_8))

    /**
     * Writes to a temporary file of its own (created with create-new, never through a link) and renames it
     * over the target, so that a crash leaves the old file or the new one and never half of one. Only in a
     * folder of the app's own; anywhere else this refuses and writes nothing.
     */
    actual fun writeBytes(fileName: String, bytes: ByteArray) {
        val target = file(fileName)
        if (!SidecarOwnership.ensureOwned(directory.toPath(), video.name)) throw IOException("The folder ${directory.name} is not the app's")
        SidecarOwnership.removeOwnTemporaries(directory.toPath(), video.name)
        val temporary = directory.toPath().resolve(SidecarOwnership.newTemporaryName())
        try {
            FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            // The target is a file of the app's own, in a folder of the app's own.
            try {
                Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    actual fun localFileUri(fileName: String): String? =
        file(fileName).takeIf(File::isFile)?.toURI()?.toString()

    /** Keeps the complete folder in the Trash or holding folder, and only when it is the app's own. */
    actual fun remove() {
        if (SidecarOwnership.isOwned(directory.toPath(), video.name)) DesktopDownloads.folders.moveToTrash(directory.toPath())
    }

    actual fun refused(): Boolean = SidecarOwnership.refuses(directory.toPath(), video.name)

    private fun file(fileName: String): File {
        require(
            fileName.isNotBlank() && File(fileName).name == fileName && fileName != "." && fileName != ".." &&
                !fileName.startsWith(SidecarOwnership.TemporaryPrefix) && fileName != SidecarOwnership.MarkerName,
        )
        return File(directory, fileName)
    }
}

/** Which sidecar folders are the app's own, and the one-time adoption of folders made before they were marked. */
internal object SidecarOwnership {
    const val MarkerName = ".nuvio-sidecar"

    /** Temporary files of the app's writes: `.nuvio-tmp-<16 hex>`. The only names it ever cleans up, and only in a marked folder. */
    const val TemporaryPrefix = ".nuvio-tmp-"
    private val temporaryName = Regex("""^\.nuvio-tmp-[0-9a-f]{16}$""")
    private const val MarkerHeader = "nuvio-sidecar 1"

    fun newTemporaryName(): String = TemporaryPrefix + DownloadMover.randomHex(8)

    fun isTemporaryName(name: String): Boolean = temporaryName.matches(name)

    private fun markerText(videoName: String) = "$MarkerHeader\nvideo=$videoName\n"

    /** The folder is there, is a plain folder, and carries the app's mark for [videoName]. */
    fun isOwned(folder: Path, videoName: String): Boolean {
        if (Files.isSymbolicLink(folder) || !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return false
        val marker = folder.resolve(MarkerName)
        if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) return false
        return runCatching { Files.size(marker) < 4096 && String(Files.readAllBytes(marker), Charsets.UTF_8) == markerText(videoName) }.getOrDefault(false)
    }

    /** A folder of that name is there and is not the app's (and cannot be adopted): nothing may be saved in it. */
    fun refuses(folder: Path, videoName: String): Boolean =
        Files.exists(folder, LinkOption.NOFOLLOW_LINKS) && !isOwned(folder, videoName) && !adoptable(folder)

    /**
     * Makes sure the folder is the app's own: creates and marks it when it is not there; adopts it when every
     * entry in it is positively the app's ([adoptable]); otherwise false, and nothing is touched. Adoption only
     * adds the mark: it never removes or changes anything.
     */
    fun ensureOwned(folder: Path, videoName: String): Boolean {
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(folder)
            } catch (_: FileAlreadyExistsException) {
                return ensureOwned(folder, videoName)
            }
            return createMarker(folder, videoName)
        }
        if (isOwned(folder, videoName)) return true
        if (Files.exists(folder.resolve(MarkerName), LinkOption.NOFOLLOW_LINKS)) return false
        return adoptable(folder) && createMarker(folder, videoName)
    }

    /**
     * A folder made by this app before folders were marked, recognised only when EVERY entry is positively the
     * app's, by name and by content: a `manifest.json` that reads as this app's manifest; exactly the subtitle
     * files it lists, no more and no fewer; and beyond that only `skip-times.json` and `episodes.json` that read
     * as this app's formats, `poster.*` / `thumbnail.*` that are real images within the size the app saves, and
     * `.none` markers that read as the app's marker (or its old plain number). Anything else (a temporary-looking
     * file, an unknown name, a file that does not read as ours, a link, a folder) and the folder is not adopted.
     * An empty folder is adopted.
     */
    fun adoptable(folder: Path): Boolean = runCatching {
        if (Files.isSymbolicLink(folder) || !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return false
        val entries = Files.list(folder).use { it.toList() }
        if (entries.any { Files.isSymbolicLink(it) || !Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }) return false
        val byName = entries.associateBy { it.fileName.toString() }
        val listed = byName["manifest.json"]?.let { manifest ->
            if (Files.size(manifest) > MaxTextBytes) return false
            subtitleManifestFileNames(String(Files.readAllBytes(manifest), Charsets.UTF_8)) ?: return false
        } ?: emptyList()
        // Exactly the subtitle files the manifest lists.
        if (listed.toSet().size != listed.size || !listed.all { it in byName && it != "manifest.json" }) return false
        byName.all { (name, file) ->
            name == "manifest.json" || name in listed || validatesAsOurs(name, file)
        }
    }.getOrDefault(false)

    private const val MaxTextBytes = 1_000_000L

    private fun text(file: Path): String? = if (Files.size(file) > MaxTextBytes) null else String(Files.readAllBytes(file), Charsets.UTF_8)

    private fun validatesAsOurs(name: String, file: Path): Boolean = when {
        name == SkipTimesSidecar.FILE_NAME -> text(file)?.let { SkipTimesSidecar.decode(it) is SavedSkipTimesRead.Saved } == true
        name == EpisodeListSidecar.FILE_NAME -> text(file)?.let { EpisodeListSidecar.decode(it) } != null
        else -> {
            val stem = name.substringBeforeLast('.', "")
            val extension = name.substringAfterLast('.', "")
            when {
                ArtworkRole.entries.none { it.fileStem == stem } -> false
                extension == "none" -> text(file)?.let { ArtworkMarker.decode(it) != null || it.trim().toLongOrNull() != null } == true
                extension in SavedArtwork.extensions -> isImage(file)
                else -> false
            }
        }
    }

    /** A real image of a kind the app saves (JPEG, PNG, WebP, GIF), within the size it saves. */
    private fun isImage(file: Path): Boolean {
        val size = Files.size(file)
        if (size < 12 || size > SavedArtwork.MaxBytes) return false
        val head = Files.newByteChannel(file, java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(12)
            while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
            buffer.array()
        }
        fun at(offset: Int, vararg bytes: Int) = bytes.withIndex().all { (i, b) -> head[offset + i] == b.toByte() }
        return at(0, 0xFF, 0xD8, 0xFF) || at(0, 0x89, 0x50, 0x4E, 0x47) || at(0, 0x47, 0x49, 0x46, 0x38) ||
            (at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50))
    }

    /** Removes the app's own temporary files ([isTemporaryName]) from a folder that carries its mark. Nothing else. */
    fun removeOwnTemporaries(folder: Path, videoName: String) {
        if (!isOwned(folder, videoName)) return
        runCatching {
            Files.list(folder).use { it.toList() }
                .filter { isTemporaryName(it.fileName.toString()) && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
                .forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun createMarker(folder: Path, videoName: String): Boolean = runCatching {
        FileChannel.open(folder.resolve(MarkerName), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val bytes = ByteBuffer.wrap(markerText(videoName).toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        true
    }.getOrDefault(false)
}
