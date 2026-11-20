package com.nuvio.app.features.downloads

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Scans every connected download location for completed video files whose records are missing
 * from the stored list. Strictly read-only: never creates, moves, renames, or deletes any file
 * or folder, never follows symbolic links, and never treats `.part` files as complete.
 */
internal class DownloadDiskScanner(
    private val locations: DownloadLocations,
    private val allRecords: (activeProfileId: Int, inMemoryItems: List<DownloadItem>) -> List<RecordRef>?,
    private val leftoverPaths: () -> Set<String> = { emptySet() },
    private val timeoutMs: Long = 5_000L,
    private val pathKey: (Path) -> String = DownloadOwnership::keyOf,
    private val resolveLocationDir: (Path) -> Path? = { path ->
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) null else path.toRealPath()
    },
) {
    fun scan(
        activeProfileId: Int,
        inMemoryItems: List<DownloadItem>,
        nextDownloadId: (Long) -> String,
        nowEpochMs: Long,
    ): DiskScanResult {
        // Ensure drives that remounted under another path are rediscovered BEFORE building ownership claims.
        val savedLocations = FsGuard.call(timeoutMs, null) { locations.all() } ?: return DiskScanResult()
        val allLocations = savedLocations.mapNotNull { loc ->
            FsGuard.call(timeoutMs, null) {
                if (loc.id == DownloadLocations.AppId || locations.availability(loc) == FolderAvailability.Available) {
                    loc
                } else {
                    locations.rediscover(loc) ?: loc
                }
            }
        }

        // Unknown ownership or move leftovers must stop adoption, rather than be treated as empty.
        // Canonicalising recorded paths can block even when the volume still looks mounted.
        val records = FsGuard.call(timeoutMs, null) {
            allRecords(activeProfileId, inMemoryItems)
        } ?: return DiskScanResult()
        val ownership = FsGuard.call(timeoutMs, null) {
            DownloadOwnership(locations, records, pathKey)
        } ?: return DiskScanResult()
        val leftovers = FsGuard.call(timeoutMs, null) { leftoverPaths() } ?: return DiskScanResult()

        val seenDirectories = mutableSetOf<Path>()
        val candidates = mutableListOf<DiskCandidate>()

        for (location in allLocations) {
            val realDir = FsGuard.call(timeoutMs, null) {
                if (locations.availability(location) != FolderAvailability.Available) null
                else runCatching { resolveLocationDir(location.path) }.getOrNull()
            } ?: continue
            if (!seenDirectories.add(realDir)) continue

            val locationName = location.path.fileName?.toString() ?: location.path.toString()
            val foundInLocation = scanDirectory(
                directory = location.path,
                location = location,
                locationName = locationName,
                activeProfileId = activeProfileId,
                records = records,
                ownership = ownership,
                leftovers = leftovers,
            )
            candidates += foundInLocation
        }

        return matchDiskCandidates(candidates, nextDownloadId, nowEpochMs)
    }

    private fun scanDirectory(
        directory: Path,
        location: DesktopDownloadLocation,
        locationName: String,
        activeProfileId: Int,
        records: List<RecordRef>,
        ownership: DownloadOwnership,
        leftovers: Set<String>,
    ): List<DiskCandidate> {
        val entries = FsGuard.call(timeoutMs, null) {
            runCatching { Files.list(directory).use { it.toList() } }.getOrNull()
        } ?: return emptyList()
        // Each file gets its own deadline. A large, responsive folder can take longer than one
        // filesystem timeout without losing all the files already found there.
        return entries.sortedBy { it.fileName.toString().lowercase() }.mapNotNull { entry ->
            FsGuard.call(timeoutMs, null) {
                scanEntry(entry, location, locationName, activeProfileId, records, ownership, leftovers)
            }
        }
    }

    private fun scanEntry(
        entry: Path,
        location: DesktopDownloadLocation,
        locationName: String,
        activeProfileId: Int,
        records: List<RecordRef>,
        ownership: DownloadOwnership,
        leftovers: Set<String>,
    ): DiskCandidate? {
        val fileName = entry.fileName?.toString() ?: return null
        if (shouldSkipEntry(entry, fileName)) return null
        if (ownership.otherOwner(activeProfileId, "", entry) != null) return null
        if (isClaimedByAddresslessRecord(entry, location, records)) return null
        if (pathKey(entry) in leftovers) return null

        val sizeBytes = runCatching { Files.size(entry) }.getOrNull() ?: return null
        val lastModified = runCatching {
            Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS).toMillis()
        }.getOrDefault(0L)
        val head = readHeadBytes(entry)
        val isWebPage = head != null && startsLikeWebPage(head)
        val isVideo = head != null && startsLikeVideoContainer(head)
        val sidecarDir = entry.resolveSibling(DownloadSidecarFiles.sidecarFolderName(fileName))
        val companion = readCompanion(sidecarDir, fileName)

        return DiskCandidate(
            fileName = fileName,
            localFileUri = entry.toFile().toURI().toString(),
            sizeBytes = sizeBytes,
            locationId = location.id,
            locationName = locationName,
            lastModifiedEpochMs = lastModified,
            isVideoContainer = isVideo,
            startsLikeWebPage = isWebPage,
            companion = companion,
        )
    }

    private fun shouldSkipEntry(entry: Path, fileName: String): Boolean {
        if (Files.isSymbolicLink(entry)) return true
        if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) return true
        if (fileName.startsWith(".")) return true
        if (fileName.equals("Thumbs.db", ignoreCase = true) || fileName.equals("desktop.ini", ignoreCase = true)) return true
        if (fileName.endsWith(".part", ignoreCase = true)) return true
        return false
    }

    private fun isClaimedByAddresslessRecord(
        file: Path,
        location: DesktopDownloadLocation,
        records: List<RecordRef>,
    ): Boolean {
        val fileName = file.fileName.toString()
        return records.any { record ->
            if (!record.completed || record.localFileUri != null || record.fileName.isBlank()) return@any false
            val recordLocation = locations.locationOfRecord(record.locationId, null)
            recordLocation.id == location.id && record.fileName.equals(fileName, ignoreCase = true)
        }
    }

    private fun readHeadBytes(file: Path): ByteArray? = runCatching {
        val toRead = maxOf(SniffBytes, VideoContainerHeaderBytes)
        Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(toRead)
            while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
            buffer.array().copyOf(buffer.position())
        }
    }.getOrNull()

    private fun readCompanion(sidecarDir: Path, videoName: String): CompanionSnapshot? {
        if (!Files.exists(sidecarDir, LinkOption.NOFOLLOW_LINKS)) return null
        if (Files.isSymbolicLink(sidecarDir) || !Files.isDirectory(sidecarDir, LinkOption.NOFOLLOW_LINKS)) {
            return CompanionSnapshot(foreignOrInvalid = true)
        }

        val markerFile = sidecarDir.resolve(SidecarOwnership.MarkerName)
        val markerPresent = Files.exists(markerFile, LinkOption.NOFOLLOW_LINKS)
        val markerOwned = if (markerPresent) SidecarOwnership.isOwned(sidecarDir, videoName) else false
        if (markerPresent && !markerOwned) {
            return CompanionSnapshot(foreignOrInvalid = true)
        }

        val entries = runCatching { Files.list(sidecarDir).use { it.toList() } }.getOrNull()
            ?: return CompanionSnapshot(foreignOrInvalid = true)
        if (entries.any { Files.isSymbolicLink(it) || !Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }) {
            return CompanionSnapshot(foreignOrInvalid = true)
        }

        val byName = entries.associateBy { it.fileName.toString() }

        var manifest: SubtitleManifest? = null
        var manifestUnreadable = false
        val manifestFile = byName["manifest.json"]
        if (manifestFile != null) {
            val text = readSmallText(manifestFile)
            val parsed = text?.let(::parseSubtitleManifestStrict)
            if (parsed == null) {
                manifestUnreadable = true
            } else {
                manifest = parsed
            }
        }

        var episodes: SavedEpisodes? = null
        var episodesUnreadable = false
        val episodesFile = byName[EpisodeListSidecar.FILE_NAME]
        if (episodesFile != null) {
            val text = readSmallText(episodesFile)
            val parsed = text?.let(EpisodeListSidecar::decode)
            if (parsed == null) {
                episodesUnreadable = true
            } else {
                episodes = parsed
            }
        }

        val listedSubtitles = manifest?.tracks?.map { it.fileName }?.toSet().orEmpty()
        var hasSkipTimes = false
        var hasArtwork = false
        var foreignEntry = false

        for ((name, file) in byName) {
            when {
                name == SidecarOwnership.MarkerName -> Unit
                name == "manifest.json" -> Unit
                name == EpisodeListSidecar.FILE_NAME -> Unit
                name == SkipTimesSidecar.FILE_NAME -> {
                    val decoded = readSmallText(file)?.let(SkipTimesSidecar::decode)
                    when (decoded) {
                        is SavedSkipTimesRead.Saved -> hasSkipTimes = true
                        is SavedSkipTimesRead.NewerVersion -> Unit
                        else -> if (!markerOwned) foreignEntry = true
                    }
                }
                name in listedSubtitles && name != "manifest.json" -> Unit
                markerOwned && SidecarOwnership.isTemporaryName(name) -> Unit
                else -> {
                    val stem = name.substringBeforeLast('.', "")
                    val ext = name.substringAfterLast('.', "")
                    val isArtworkRole = ArtworkRole.entries.any { it.fileStem == stem }
                    when {
                        isArtworkRole && ext == "none" -> {
                            val text = readSmallText(file)
                            val validMarker = text != null && (ArtworkMarker.decode(text) != null || text.trim().toLongOrNull() != null)
                            if (!validMarker && !markerOwned) foreignEntry = true
                        }
                        isArtworkRole && ext in SavedArtwork.extensions -> {
                            if (isValidImage(file)) {
                                hasArtwork = true
                            } else if (!markerOwned) {
                                foreignEntry = true
                            }
                        }
                        else -> if (!markerOwned) foreignEntry = true
                    }
                }
            }
        }

        return CompanionSnapshot(
            episodes = episodes,
            episodesPresent = episodesFile != null,
            episodesUnreadable = episodesUnreadable,
            manifest = manifest,
            manifestPresent = manifestFile != null,
            manifestUnreadable = manifestUnreadable,
            hasSkipTimes = hasSkipTimes,
            hasArtwork = hasArtwork,
            isOwnedByMarker = markerOwned,
            foreignOrInvalid = foreignEntry,
        )
    }

    private fun readSmallText(file: Path): String? = runCatching {
        if (Files.size(file) > MaxTextBytes) return null
        Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val size = channel.size().toInt()
            val buffer = ByteBuffer.allocate(size)
            while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
            String(buffer.array(), 0, buffer.position(), Charsets.UTF_8)
        }
    }.getOrNull()

    private fun isValidImage(file: Path): Boolean = runCatching {
        val size = Files.size(file)
        if (size < 12L || size > SavedArtwork.MaxBytes.toLong()) return false
        val head = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(12)
            while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
            buffer.array().copyOf(buffer.position())
        }
        SavedArtwork.extensionOf(head) != null
    }.getOrDefault(false)

    private companion object {
        const val MaxTextBytes = 1_000_000L
    }
}
