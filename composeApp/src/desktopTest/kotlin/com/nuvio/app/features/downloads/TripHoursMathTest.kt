package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TripHoursMathTest {

    @Test
    fun `isVerifiedForTrip requires completed non-flagged non-suspect reachable verified copy`() {
        val verifiedHash = completedEpisode("ep-1", season = 1, episode = 1, verifiedBy = DownloadVerification.Hash)
        val verifiedDigest = completedEpisode("ep-2", season = 1, episode = 2, verifiedBy = DownloadVerification.Digest)
        val verifiedSize = completedEpisode("ep-3", season = 1, episode = 3, verifiedBy = DownloadVerification.Size)
        val passedFrames = completedEpisode(
            "ep-4",
            season = 1,
            episode = 4,
            verifiedBy = DownloadVerification.None,
            framesCheck = DownloadFramesCheck.Passed,
        )
        val unverified = completedEpisode(
            "ep-5",
            season = 1,
            episode = 5,
            verifiedBy = DownloadVerification.None,
            framesCheck = DownloadFramesCheck.NotChecked,
        )
        val legacyUnchecked = completedEpisode(
            "ep-6",
            season = 1,
            episode = 6,
            verifiedBy = null,
            framesCheck = DownloadFramesCheck.NotChecked,
        )
        val suspect = completedEpisode(
            "ep-7",
            season = 1,
            episode = 7,
            verifiedBy = DownloadVerification.Size,
            framesCheck = DownloadFramesCheck.Suspect,
        )
        val placeholder = completedEpisode(
            "ep-8",
            season = 1,
            episode = 8,
            verifiedBy = DownloadVerification.Size,
            framesCheck = DownloadFramesCheck.NotTheRealVideo,
        )
        val missingAddress = completedEpisode(
            "ep-9",
            season = 1,
            episode = 9,
            verifiedBy = DownloadVerification.Hash,
            localFileUri = " ",
        )
        val stillDownloading = completedEpisode(
            "ep-10",
            season = 1,
            episode = 10,
            verifiedBy = DownloadVerification.Hash,
            status = DownloadStatus.Downloading,
        )

        assertTrue(verifiedHash.isVerifiedForTrip())
        assertTrue(verifiedDigest.isVerifiedForTrip())
        assertTrue(verifiedSize.isVerifiedForTrip())
        assertTrue(passedFrames.isVerifiedForTrip())
        assertFalse(unverified.isVerifiedForTrip())
        assertFalse(legacyUnchecked.isVerifiedForTrip())
        assertFalse(suspect.isVerifiedForTrip())
        assertFalse(placeholder.isVerifiedForTrip())
        assertFalse(missingAddress.isVerifiedForTrip())
        assertFalse(stillDownloading.isVerifiedForTrip())
    }

    @Test
    fun `resolveItemDurationMs follows container then metadata then sidecar then progress priority`() {
        assertEquals(
            2_700_000L,
            resolveItemDurationMs(
                fileDurationMs = 2_700_000L,
                runtimeMinutes = 50,
                sidecarRuntimeMinutes = 42,
                watchProgressDurationMs = 2_500_000L,
            ),
        )
        assertEquals(
            3_000_000L,
            resolveItemDurationMs(
                fileDurationMs = 0L,
                runtimeMinutes = 50,
                sidecarRuntimeMinutes = 42,
                watchProgressDurationMs = 2_500_000L,
            ),
        )
        assertEquals(
            2_520_000L,
            resolveItemDurationMs(
                fileDurationMs = null,
                runtimeMinutes = null,
                sidecarRuntimeMinutes = 42,
                watchProgressDurationMs = 2_500_000L,
            ),
        )
        assertEquals(
            2_500_000L,
            resolveItemDurationMs(
                fileDurationMs = null,
                runtimeMinutes = 0,
                sidecarRuntimeMinutes = -5,
                watchProgressDurationMs = 2_500_000L,
            ),
        )
        assertNull(
            resolveItemDurationMs(
                fileDurationMs = null,
                runtimeMinutes = null,
                sidecarRuntimeMinutes = null,
                watchProgressDurationMs = 0L,
            ),
        )
    }

    @Test
    fun `rate tracker computes sliding window speed and ETA across transfers`() {
        val tracker = DownloadRateTracker(windowMs = 10_000L, minSpanMs = 1_000L, stallTimeoutMs = 20_000L)
        tracker.recordStart("d1", initialDownloadedBytes = 0L, nowEpochMs = 1_000L)
        assertNull(tracker.bytesPerSecond("d1", 1_500L))

        tracker.recordProgress("d1", downloadedBytes = 10_000_000L, nowEpochMs = 6_000L)
        // 10 MB over 5s = 2 MB/s
        assertEquals(2_000_000L, tracker.bytesPerSecond("d1", 6_500L))

        // Samples older than the 10s window are evicted while keeping >= 1s span.
        tracker.recordProgress("d1", downloadedBytes = 30_000_000L, nowEpochMs = 16_000L)
        assertEquals(2_000_000L, tracker.bytesPerSecond("d1", 16_000L))
        tracker.recordProgress("d1", downloadedBytes = 34_000_000L, nowEpochMs = 17_000L)
        // Sample at t=6_000 is now older than cutoff (7_000), leaving (16_000, 30M) -> (17_000, 34M) = 4 MB/s.
        val rateAt17s = tracker.bytesPerSecond("d1", 17_000L)
        assertEquals(4_000_000L, rateAt17s)
        assertNull(tracker.bytesPerSecond("d1", 40_000L)) // Beyond 20s stall timeout

        val downloading = DownloadItem(
            id = "d1",
            contentType = "series",
            parentMetaId = "show-1",
            parentMetaType = "series",
            videoId = "show-1:1:1",
            title = "Andor",
            seasonNumber = 1,
            episodeNumber = 1,
            streamTitle = "ep1",
            providerName = "Addon",
            sourceUrl = "https://example.test/ep1.mkv",
            fileName = "ep1.mkv",
            status = DownloadStatus.Downloading,
            downloadedBytes = 34_000_000L,
            totalBytes = 134_000_000L,
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 17_000L,
        )
        // 100_000_000 bytes left at 2_000_000 B/s = 50s
        assertEquals(50L, estimateDownloadEtaSeconds(downloading, bytesPerSecond = 2_000_000L, nowEpochMs = 18_000L, offline = false))
        assertNull(estimateDownloadEtaSeconds(downloading, bytesPerSecond = 2_000_000L, nowEpochMs = 18_000L, offline = true))
        assertNull(estimateDownloadEtaSeconds(downloading, bytesPerSecond = 2_000_000L, nowEpochMs = 50_000L, offline = false))

        // Concurrent total ETA takes max when all known-total active transfers have an ETA, or null if one lacks speed or size.
        val entryFast = TripDownloadingEntry(downloading, DownloadPhase.Downloading, 0.25f, 34L, 134L, 2_000_000L, etaSeconds = 50L)
        val entrySlower = TripDownloadingEntry(downloading.copy(id = "d2"), DownloadPhase.Downloading, 0.1f, 10L, 100L, 500_000L, etaSeconds = 180L)
        val entryNoEta = TripDownloadingEntry(downloading.copy(id = "d3"), DownloadPhase.Downloading, 0f, 0L, 100L, null, etaSeconds = null)
        val entryUnknownSize = TripDownloadingEntry(downloading.copy(id = "d4"), DownloadPhase.Downloading, 0f, 50L, null, 1_000_000L, etaSeconds = null)
        assertEquals(180L, estimateTotalDownloadingEtaSeconds(listOf(entryFast, entrySlower)))
        assertNull(estimateTotalDownloadingEtaSeconds(listOf(entryFast, entryNoEta)))
        assertNull(estimateTotalDownloadingEtaSeconds(listOf(entryFast, entryUnknownSize)))
    }

    @Test
    fun `deleted completed files are excluded from playable offline hours and surface in attention`() = kotlinx.coroutines.runBlocking {
        val localDrive = DownloadLocationInfo(
            id = "local",
            path = "/downloads",
            name = "Downloads",
            isDefault = true,
            isAppFolder = true,
            availability = FolderAvailability.Available,
            itemCount = 1,
            totalBytes = 1_000_000_000L,
            freeBytes = 50_000_000_000L,
        )
        val folderState = DownloadFolderState(locations = listOf(localDrive))
        val deletedEp = completedEpisode(
            id = "deleted-ep",
            title = "Andor",
            season = 1,
            episode = 1,
            verifiedBy = DownloadVerification.Hash,
            localFileUri = "file:///downloads/deleted-ep.mkv",
        )
        val diskInfo = loadAppTripDiskInfo(
            items = listOf(deletedEp),
            nowEpochMs = 10_000L,
            folderState = folderState,
            locationIdOf = { "local" },
            resolveUri = { null },
            inspect = { null },
        )
        val summary = buildTripReadySummary(
            items = listOf(deletedEp),
            folderState = folderState,
            locationIdOf = { "local" },
            addressRequired = true,
            diskInfoByDownloadId = diskInfo,
            durationMsOf = { 45 * 60_000L },
        )
        assertEquals(0L, summary.totalPlayableOfflineMs)
        assertEquals(0, summary.totalPlayableCount)
        assertEquals(listOf("deleted-ep"), summary.attentionItems.map { it.item.id })
        assertTrue(summary.attentionItems.single().isFileMissing)
    }

    @Test
    fun `computeTripDurationComponents converts millis cleanly`() {
        assertEquals(0 to 0, computeTripDurationComponents(0L))
        assertEquals(0 to 45, computeTripDurationComponents(45 * 60_000L))
        assertEquals(1 to 0, computeTripDurationComponents(60 * 60_000L))
        assertEquals(2 to 15, computeTripDurationComponents(135 * 60_000L))
        assertEquals(0 to 2, computeTripDurationComponents(61_000L, roundUpMinutes = true))
    }

    @Test
    fun `buildTripReadySummary totals verified hours deduplicates copies and surfaces issues and unplugged drives`() {
        val localDrive = DownloadLocationInfo(
            id = "local",
            path = "/downloads",
            name = "Downloads",
            isDefault = true,
            isAppFolder = true,
            availability = FolderAvailability.Available,
            itemCount = 10,
            totalBytes = 10_000_000_000L,
            freeBytes = 50_000_000_000L,
        )
        val usbDrive = DownloadLocationInfo(
            id = "usb-1",
            path = "/Volumes/TripUSB",
            name = "TripUSB",
            isDefault = false,
            isAppFolder = false,
            availability = FolderAvailability.NotConnected,
            itemCount = 2,
            totalBytes = 4_000_000_000L,
            freeBytes = 0L,
        )
        val folderState = DownloadFolderState(
            locations = listOf(localDrive, usbDrive),
            movingIds = setOf("moving-ep"),
        )

        val durations = mapOf(
            "s1-e1" to 45 * 60_000L,
            "s1-e1-dup" to 45 * 60_000L,
            "s1-e2" to 60 * 60_000L,
            "movie-1" to 120 * 60_000L,
            "usb-movie" to 95 * 60_000L,
            "suspect-ep" to 50 * 60_000L,
            "flagged-ep" to 45 * 60_000L,
            "unverified-ep" to 40 * 60_000L,
            "moving-ep" to 40 * 60_000L,
        )

        val items = listOf(
            // Playable verified episodes (two copies of S01E01 should count only once!)
            completedEpisode("s1-e1", title = "Andor", season = 1, episode = 1, verifiedBy = DownloadVerification.Hash, downloadedBytes = 800_000_000L),
            completedEpisode("s1-e1-dup", title = "Andor", season = 1, episode = 1, verifiedBy = DownloadVerification.Size, downloadedBytes = 500_000_000L),
            completedEpisode("s1-e2", title = "Andor", season = 1, episode = 2, verifiedBy = DownloadVerification.Digest, downloadedBytes = 900_000_000L),
            // Verified episode with unknown duration (contributes to unknownDurationCount)
            completedEpisode("s1-e3", title = "Andor", season = 1, episode = 3, verifiedBy = DownloadVerification.Hash, downloadedBytes = 700_000_000L),
            // Playable verified movie
            completedMovie("movie-1", title = "Arrival", verifiedBy = DownloadVerification.Size, downloadedBytes = 3_000_000_000L),
            // Moving file (excluded from verified offline now)
            completedEpisode("moving-ep", title = "Andor", season = 1, episode = 4, verifiedBy = DownloadVerification.Hash),
            // Flagged placeholder (excluded from verified offline, included in attention)
            completedEpisode(
                "flagged-ep",
                title = "Severance",
                season = 1,
                episode = 1,
                verifiedBy = DownloadVerification.Size,
                framesCheck = DownloadFramesCheck.NotTheRealVideo,
            ),
            // Suspect file (excluded from verified offline, included in attention)
            completedEpisode(
                "suspect-ep",
                title = "Severance",
                season = 1,
                episode = 2,
                verifiedBy = DownloadVerification.Size,
                framesCheck = DownloadFramesCheck.Suspect,
            ),
            // Unverified file (excluded from verified offline, included in attention)
            completedEpisode(
                "unverified-ep",
                title = "Severance",
                season = 1,
                episode = 3,
                verifiedBy = null,
                framesCheck = DownloadFramesCheck.NotChecked,
            ),
            // Unplugged drive completed + waiting items
            completedMovie("usb-movie", title = "Dune", verifiedBy = DownloadVerification.Hash, locationId = "usb-1"),
            completedEpisode(
                "usb-wait",
                title = "Severance",
                season = 1,
                episode = 4,
                status = DownloadStatus.Paused,
                pausedByFolder = true,
                locationId = "usb-1",
            ),
            // Active downloading item
            completedEpisode(
                "active-dl",
                title = "Andor",
                season = 1,
                episode = 5,
                status = DownloadStatus.Downloading,
                downloadedBytes = 100_000_000L,
                totalBytes = 400_000_000L,
                updatedAtEpochMs = 10_000L,
            ),
            // Locally paused item
            completedEpisode(
                "paused-dl",
                title = "Severance",
                season = 1,
                episode = 6,
                status = DownloadStatus.Paused,
                pausedByConnection = true,
            ),
        )

        val diskInfo = mapOf(
            "s1-e1" to TripItemDiskInfo(subtitleCount = 2, skipTimesNote = SkipTimesNote.Saved),
            "s1-e1-dup" to TripItemDiskInfo(subtitleCount = 1, skipTimesNote = SkipTimesNote.Saved),
            "s1-e2" to TripItemDiskInfo(subtitleCount = 0, skipTimesNote = null), // Missing subtitles & skip times
            "s1-e3" to TripItemDiskInfo(subtitleCount = 1, skipTimesNote = SkipTimesNote.Saved),
            "movie-1" to TripItemDiskInfo(subtitleCount = 0, skipTimesNote = SkipTimesNote.Saved, sidecarRefused = true),
            "suspect-ep" to TripItemDiskInfo(subtitleCount = 1, skipTimesNote = SkipTimesNote.NoneFound),
            "unverified-ep" to TripItemDiskInfo(subtitleCount = 1, skipTimesNote = SkipTimesNote.Saved),
        )

        val summary = buildTripReadySummary(
            items = items,
            folderState = folderState,
            locationIdOf = { it.locationId ?: "local" },
            nowEpochMs = 12_000L,
            limitedBy = LimitedConnectionReason.Hotspot,
            diskInfoByDownloadId = diskInfo,
            durationMsOf = { item -> durations[item.id] },
            bytesPerSecondOf = { item -> if (item.id == "active-dl") 3_000_000L else null },
        )

        // Verified offline total = s1-e1 (45m, counted once!) + s1-e2 (60m) + movie-1 (120m) = 225 min = 3h 45m
        assertEquals(225 * 60_000L, summary.totalPlayableOfflineMs)
        assertEquals(4, summary.totalPlayableCount) // s1-e1, s1-e2, s1-e3, movie-1
        assertEquals(3, summary.totalPlayableEpisodeCount)
        assertEquals(1, summary.totalPlayableMovieCount)
        assertEquals(1, summary.totalPlayableUnknownDurationCount) // s1-e3

        assertEquals(2, summary.titles.size)
        val andor = summary.titles[0]
        val arrival = summary.titles[1]
        assertEquals("Andor", andor.title)
        assertEquals(3, andor.episodeCount)
        assertEquals(105 * 60_000L, andor.totalKnownDurationMs)
        assertEquals(1, andor.unknownDurationCount)
        assertEquals("Arrival", arrival.title)
        assertFalse(arrival.isEpisode)
        assertEquals(120 * 60_000L, arrival.totalKnownDurationMs)

        // Downloading with ETA: 300MB left at 3 MB/s = 100s
        assertEquals(1, summary.downloading.size)
        assertEquals(100L, summary.downloading[0].etaSeconds)
        assertEquals(100L, summary.totalDownloadingEtaSeconds)

        // Paused excludes unplugged usb-wait
        assertEquals(listOf("paused-dl"), summary.pausedItems.map { it.first.id })

        // Unplugged drives group has usb-1 with Dune + Severance S01E04
        assertEquals(1, summary.unpluggedDrives.size)
        assertEquals(2, summary.unpluggedTotalCount)
        val usbGroup = summary.unpluggedDrives[0]
        assertEquals("TripUSB", usbGroup.location.name)
        assertEquals(1, usbGroup.completedCount)
        assertEquals(1, usbGroup.waitingCount)
        assertEquals(95 * 60_000L, usbGroup.knownDurationMs)

        // Attention items capture flagged placeholder, suspect+NoneFound, unverified, missing subs/skip, sidecar refused
        val attentionById = summary.attentionItems.associateBy { it.item.id }
        assertTrue(attentionById.getValue("flagged-ep").isFlaggedPlaceholder)
        assertTrue(attentionById.getValue("suspect-ep").isSuspect)
        assertEquals(TripSkipTimesStatus.NoneFound, attentionById.getValue("suspect-ep").skipTimesStatus)
        assertTrue(attentionById.getValue("unverified-ep").isUnverified)
        assertTrue(attentionById.getValue("s1-e2").missingSubtitles)
        assertEquals(TripSkipTimesStatus.NotSaved, attentionById.getValue("s1-e2").skipTimesStatus)
        assertTrue(attentionById.getValue("movie-1").sidecarRefused)
    }

    private fun completedEpisode(
        id: String,
        title: String = "Andor",
        season: Int,
        episode: Int,
        verifiedBy: DownloadVerification? = DownloadVerification.Hash,
        framesCheck: DownloadFramesCheck = DownloadFramesCheck.NotChecked,
        status: DownloadStatus = DownloadStatus.Completed,
        localFileUri: String? = "file:///downloads/$id.mkv",
        downloadedBytes: Long = 500_000_000L,
        totalBytes: Long? = downloadedBytes,
        locationId: String? = "local",
        pausedByFolder: Boolean = false,
        pausedByConnection: Boolean = false,
        updatedAtEpochMs: Long = 10_000L,
    ): DownloadItem = DownloadItem(
        id = id,
        contentType = "series",
        parentMetaId = "show:${title.lowercase()}",
        parentMetaType = "series",
        videoId = "show:${title.lowercase()}:$season:$episode",
        title = title,
        seasonNumber = season,
        episodeNumber = episode,
        streamTitle = "$title S${season}E$episode",
        providerName = "Addon",
        sourceUrl = "https://example.test/$id.mkv",
        localFileUri = localFileUri,
        fileName = "$id.mkv",
        status = status,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = updatedAtEpochMs,
        verifiedBy = verifiedBy,
        framesCheck = framesCheck,
        locationId = locationId,
        pausedByFolder = pausedByFolder,
        pausedByConnection = pausedByConnection,
    )

    private fun completedMovie(
        id: String,
        title: String,
        verifiedBy: DownloadVerification? = DownloadVerification.Size,
        downloadedBytes: Long = 2_000_000_000L,
        locationId: String? = "local",
    ): DownloadItem = DownloadItem(
        id = id,
        contentType = "movie",
        parentMetaId = "movie:${title.lowercase()}",
        parentMetaType = "movie",
        videoId = "movie:${title.lowercase()}",
        title = title,
        streamTitle = title,
        providerName = "Addon",
        sourceUrl = "https://example.test/$id.mkv",
        localFileUri = "file:///downloads/$id.mkv",
        fileName = "$id.mkv",
        status = DownloadStatus.Completed,
        downloadedBytes = downloadedBytes,
        totalBytes = downloadedBytes,
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 5_000L,
        verifiedBy = verifiedBy,
        locationId = locationId,
    )
}
