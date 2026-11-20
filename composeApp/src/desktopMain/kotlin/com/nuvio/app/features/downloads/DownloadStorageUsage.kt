package com.nuvio.app.features.downloads

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** What the downloads of one location take on disk. */
internal data class LocationUsage(val usedBytes: Long, val lastKnown: Boolean)

internal class UsageResult(
    val perLocation: Map<String, LocationUsage>,
    /** What automatic downloads take on disk in the locations that are connected. */
    val autoDownloadBytes: Long,
)

/**
 * Measures what downloads really take on disk: the sizes of the files of finished and unfinished
 * downloads, partial files and companion files included, not the sizes that were announced. Only files
 * that belong to a download in the list are counted. The result is cached; [current] measures again when
 * the set of downloads or a location's state changed, while transfers run at most every [whileRunningMs],
 * and at most [maxAgeMs] apart. It must be called off the interface thread.
 */
internal class DownloadStorageUsage(
    private val locations: DownloadLocations,
    private val clock: () -> Long = System::currentTimeMillis,
    private val whileRunningMs: Long = 5_000L,
    private val maxAgeMs: Long = 60_000L,
    private val timeoutMs: Long = 5_000L,
) {
    private var cached: UsageResult? = null
    private var cachedAt = 0L
    private var fingerprint: Int? = null
    private var forced = false
    private val lastKnown = mutableMapOf<String, Long>()

    /** How many times the disk was walked. For tests. */
    var measurements = 0
        private set

    /** The next call to [current] measures again: something moved or finished. */
    fun invalidate() {
        forced = true
    }

    @Synchronized
    fun current(items: List<DownloadItem>, availability: Map<String, FolderAvailability>): UsageResult {
        val print = listOf(
            items.map { listOf(it.id, it.status, it.locationId, it.localFileUri, it.fileName) },
            availability.toSortedMap().toList(),
        ).hashCode()
        val now = clock()
        val running = items.any { it.status == DownloadStatus.Downloading }
        val due = cached == null || forced || print != fingerprint ||
            now - cachedAt >= maxAgeMs || (running && now - cachedAt >= whileRunningMs)
        if (due) {
            cached = measure(items, availability)
            cachedAt = now
            fingerprint = print
            forced = false
        }
        return cached!!
    }

    private fun measure(items: List<DownloadItem>, availability: Map<String, FolderAvailability>): UsageResult {
        measurements++
        val result = mutableMapOf<String, LocationUsage>()
        var auto = 0L
        val byLocation = items.groupBy { locations.locationOfRecord(it.locationId, it.localFileUri).id }
        locations.all().forEach { location ->
            val members = byLocation[location.id].orEmpty()
            if (availability[location.id] != FolderAvailability.Available) {
                // Away: the size it had when it was last seen, or what the records say.
                val known = lastKnown[location.id] ?: members.sumOf { it.moveBytes }
                result[location.id] = LocationUsage(known, lastKnown = true)
                return@forEach
            }
            val sizes = FsGuard.call(timeoutMs, null) { sizesIn(location.id, location.path, members) }
            if (sizes == null) {
                result[location.id] = LocationUsage(lastKnown[location.id] ?: members.sumOf { it.moveBytes }, lastKnown = true)
            } else {
                val total = sizes.values.sum()
                lastKnown[location.id] = total
                result[location.id] = LocationUsage(total, lastKnown = false)
                auto += members.filter { it.autoDownloadRuleKey != null }.sumOf { sizes[it.id] ?: 0L }
            }
        }
        return UsageResult(result, auto)
    }

    /** Size on disk of each item of [members] that lives in [root]: one listing of the folder serves all of them. */
    private fun sizesIn(locationId: String, root: Path, members: List<DownloadItem>): Map<String, Long> {
        val listing = runCatching { Files.list(root).use { it.toList() } }.getOrDefault(emptyList())
            .associateBy { it.fileName.toString() }
        return members.associate { item ->
            val video = item.localFileUri?.let(DownloadLocations::fileOfUri)?.name ?: item.fileName
            val own = if (item.status == DownloadStatus.Completed) video else item.activePartialFileName
            var total = sizeOf(listing[own])
            if (item.status == DownloadStatus.Completed) {
                total += sizeOf(listing[DownloadSidecarFiles.sidecarFolderName(video)])
            } else {
                item.supersededPartials.forEach { kept ->
                    if (kept.locationId == null || kept.locationId == locationId) {
                        total += sizeOf(listing[kept.fileName])
                    }
                }
            }
            item.id to total
        }
    }

    private fun sizeOf(path: Path?): Long = when {
        path == null -> 0L
        Files.isSymbolicLink(path) -> 0L
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> runCatching { Files.size(path) }.getOrDefault(0L)
        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> runCatching {
            Files.walk(path).use { stream ->
                stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.mapToLong { Files.size(it) }.sum()
            }
        }.getOrDefault(0L)
        else -> 0L
    }
}
