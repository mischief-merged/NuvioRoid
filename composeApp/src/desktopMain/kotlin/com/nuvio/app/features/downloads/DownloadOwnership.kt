package com.nuvio.app.features.downloads

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.text.Normalizer
import java.util.Locale

/** What a move needs to know of one record of one profile. */
internal data class RecordRef(
    val profileId: Int,
    val id: String,
    val completed: Boolean,
    val locationId: String?,
    val localFileUri: String?,
    val fileName: String,
) {
    val label: String get() = fileName.ifBlank { id }

    companion object {
        fun of(profileId: Int, item: DownloadItem) =
            RecordRef(profileId, item.id, item.status == DownloadStatus.Completed, item.locationId, item.localFileUri, item.fileName)
    }
}

/**
 * Which record owns which path, over the records of every profile. A finished record owns its file and
 * its sidecar folder; an unfinished one owns, in its location, its partial file, the final name it will
 * write and its sidecar folder. Paths are compared canonically: links in the folder part resolved,
 * Unicode NFC, letter case ignored (macOS and Windows volumes usually ignore it; on a volume that does
 * not, this only refuses more).
 */
internal class DownloadOwnership(
    private val locations: DownloadLocations,
    records: List<RecordRef>,
    private val pathKey: (Path) -> String = ::keyOf,
) {
    private class Claim(val owner: RecordRef, val key: String)

    private val claims: List<Claim> = records.flatMap(::claimsOf)

    /** Records whose location is no longer known: their claim cannot be placed, so it holds every path with their file name. */
    private val unplaced: List<Pair<RecordRef, String>> = records.filter { it.locationId != null && locations.location(it.locationId) == null }
        .mapNotNull { r -> (r.localFileUri?.let(DownloadLocations::fileOfUri)?.name ?: r.fileName).takeIf { it.isNotBlank() }?.let { r to nameKey(it) } }

    private fun claimsOf(record: RecordRef): List<Claim> {
        val files: List<Path> = if (record.completed) {
            // The address as stored, and where it is now if its drive came back under another mount point.
            listOfNotNull(
                record.localFileUri?.let(DownloadLocations::fileOfUri)?.toPath(),
                locations.recordedPath(record.locationId, record.localFileUri),
            ).distinct()
        } else {
            val owner = locations.locationOfRecord(record.locationId, null).path
            if (record.fileName.isBlank()) emptyList() else listOf(owner.resolve(record.fileName + ".part"), owner.resolve(record.fileName))
        }
        val videos = files.filterNot { it.fileName.toString().endsWith(".part") }
        return files.map { Claim(record, pathKey(it)) } +
            videos.map { Claim(record, pathKey(it.resolveSibling(DownloadSidecarFiles.sidecarFolderName(it.fileName.toString())))) }
    }

    /** The record, other than [profileId]/[id], that owns [path], or null. */
    fun otherOwner(profileId: Int, id: String, path: Path): RecordRef? {
        val key = pathKey(path)
        fun other(r: RecordRef) = !(r.profileId == profileId && r.id == id)
        val names = generateSequence(path) { it.parent }.mapNotNull { it.fileName?.toString() }.map(::nameKey).toSet()
        unplaced.firstOrNull { (r, name) -> other(r) && (name in names || names.any { it.startsWith("$name.subtitles") }) }?.let { return it.first }
        return claims.firstOrNull { claim ->
            !(claim.owner.profileId == profileId && claim.owner.id == id) &&
                // The same path, a path inside the claimed one, or a folder that holds the claimed one.
                (claim.key == key || key.startsWith(claim.key + "/") || claim.key.startsWith("$key/"))
        }?.owner
    }

    companion object {
        private fun nameKey(name: String) = Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)

        /** A comparable form of [path]: the links of its existing folders resolved, NFC, lower case, '/' separators. */
        fun keyOf(path: Path): String {
            val absolute = path.toAbsolutePath().normalize()
            val parent = absolute.parent
            val realParent = parent?.let { dir ->
                var existing: Path? = dir
                val rest = ArrayDeque<String>()
                while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                    existing.fileName?.let { rest.addFirst(it.toString()) }
                    existing = existing.parent
                }
                val base = existing?.let { runCatching { it.toRealPath() }.getOrDefault(it) } ?: dir
                rest.fold(base) { acc, part -> acc.resolve(part) }
            }
            val full = (realParent?.resolve(absolute.fileName?.toString() ?: "") ?: absolute).toString().replace('\\', '/')
            return Normalizer.normalize(full, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        }
    }
}

/**
 * The one-time adoption of a partial file created before downloads recorded the identity of their partial
 * file: its identity, only when ALL hold: the record has none recorded; the file is a regular file (not a
 * link) in the record's own location at exactly the record's own partial name; no other record of any
 * profile claims it; and its size is exactly the record's stored downloaded bytes. Otherwise null, and the
 * file is left as it is. Adoption only records; it deletes and cuts back nothing.
 */
internal fun legacyPartialKey(
    item: DownloadItem,
    profileId: Int,
    partial: Path,
    locations: DownloadLocations,
    records: List<RecordRef>?,
): String? {
    if (item.partialFileKey != null || item.status == DownloadStatus.Completed || item.downloadedBytes <= 0L) return null
    val own = locations.locationOfRecord(item.locationId, null).path.resolve(item.fileName + ".part").toAbsolutePath().normalize()
    if (partial.toAbsolutePath().normalize() != own) return null
    if (Files.isSymbolicLink(own) || !Files.isRegularFile(own, LinkOption.NOFOLLOW_LINKS)) return null
    val known = records ?: return null
    if (DownloadOwnership(locations, known).otherOwner(profileId, item.id, own) != null) return null
    if (runCatching { Files.size(own) }.getOrNull() != item.downloadedBytes) return null
    return DownloadMover.identityOf(own)
}
