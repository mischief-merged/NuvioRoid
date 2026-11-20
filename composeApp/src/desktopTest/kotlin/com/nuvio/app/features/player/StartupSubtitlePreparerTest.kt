package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StartupSubtitlePreparerTest {

    private companion object {
        val fullSubtitleStyle = SubtitleStyleState.DEFAULT.copy(useForcedSubtitles = false)
        const val VALID_EN_SRT = "1\n00:00:01,000 --> 00:00:03,000\nHello world\n"
        const val VALID_ES_SRT = "1\n00:00:01,000 --> 00:00:03,000\nHola mundo\n"
        const val VALID_PT_BR_VTT = "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nOi tudo bem\n"
        const val VALID_PT_PT_VTT = "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nOla tudo bem\n"
        const val VALID_ASS =
            "[Script Info]\nScriptType: v4.00+\n[Events]\n" +
                "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
                "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Buenos dias\n"
    }

    private fun TestScope.newPreparer(
        cacheDir: File,
        addons: suspend (StartupSubtitleRequest, (List<AddonSubtitle>) -> Unit) -> List<AddonSubtitle> = { _, _ -> emptyList() },
        remoteTexts: suspend (url: String, headers: Map<String, String>, languageHint: String?) -> String? = { _, _, _ -> null },
        localTexts: suspend (String) -> String? = { uri ->
            File(java.net.URI(uri)).takeIf { it.isFile }?.readText(Charsets.UTF_8)
        },
        validator: suspend (String, String?) -> Boolean = { text, sourceUrl ->
            isValidSubtitleContent(text, sourceUrl)
        },
        deviceLanguages: () -> List<String> = { listOf("en-US") },
        persistedPreference: (String) -> PersistedPlayerTrackPreference? = { null },
        startupTimeoutMs: Long = 3_000L,
        perFileTimeoutMs: Long = 2_000L,
        localValidationTimeoutMs: Long = 1_500L,
    ): StartupSubtitlePreparer = StartupSubtitlePreparer(
        StartupSubtitleDependencies(
            fetchAddonSubtitlesStreaming = addons,
            fetchSubtitleText = remoteTexts,
            readLocalSubtitleText = localTexts,
            writeCachedSubtitleText = { fileName, content ->
                val out = File(cacheDir, fileName)
                out.writeText(content, Charsets.UTF_8)
                out.toURI().toString()
            },
            validateSubtitleContent = validator,
            deviceLanguages = deviceLanguages,
            loadPersistedTrackPreference = persistedPreference,
            nowMs = { testScheduler.currentTime },
            startupTimeoutMs = startupTimeoutMs,
            perFileTimeoutMs = perFileTimeoutMs,
            localValidationTimeoutMs = localValidationTimeoutMs,
        ),
    )

    @Test
    fun downloadSelectionUsesSavedPreferredLanguageFileNextToVideo() = runTest {
        val dir = Files.createTempDirectory("nuvio-sub-dl").toFile()
        try {
            val enFile = File(dir, "en_English.srt").apply { writeText(VALID_EN_SRT) }
            val esFile = File(dir, "es_Spanish.srt").apply { writeText(VALID_ES_SRT) }
            val preparer = newPreparer(dir)

            val prepared = preparer.prepareDownloadedSubtitles(
                localSubtitles = listOf(
                    StreamSubtitle(url = enFile.toURI().toString(), language = "en", name = "English"),
                    StreamSubtitle(url = esFile.toURI().toString(), language = "es", name = "Spanish"),
                ),
                settings = PlayerSettingsUiState(subtitleStyle = fullSubtitleStyle, preferredSubtitleLanguage = "es"),
                parentMetaId = "tt100",
            )

            assertEquals(2, prepared.subtitles.size)
            assertEquals(esFile.toURI().toString(), prepared.selection.selectedUrl)
            assertFalse(prepared.selection.disableAutoSelect)
            assertFalse(prepared.selection.exhaustedOrTimedOut)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun downloadSelectionPrefersRegionalVariantAndDropsCorruptFiles() = runTest {
        val dir = Files.createTempDirectory("nuvio-sub-var").toFile()
        try {
            val corruptPtBr = File(dir, "ptbr_corrupt.srt").apply { writeText("<html>502 Bad Gateway</html>") }
            val validPtBr = File(dir, "ptbr_valid.vtt").apply { writeText(VALID_PT_BR_VTT) }
            val validPtPt = File(dir, "ptpt_valid.vtt").apply { writeText(VALID_PT_PT_VTT) }
            val preparer = newPreparer(dir)

            val prepared = preparer.prepareDownloadedSubtitles(
                localSubtitles = listOf(
                    StreamSubtitle(url = validPtPt.toURI().toString(), language = "pt", name = "Portuguese"),
                    StreamSubtitle(url = corruptPtBr.toURI().toString(), language = "pt-br", name = "Portuguese (Brazil)"),
                    StreamSubtitle(url = validPtBr.toURI().toString(), language = "pt-br", name = "Portuguese (Brazil)"),
                ),
                settings = PlayerSettingsUiState(subtitleStyle = fullSubtitleStyle, preferredSubtitleLanguage = "pt-br"),
                parentMetaId = "tt101",
            )

            assertEquals(2, prepared.subtitles.size)
            assertEquals(validPtBr.toURI().toString(), prepared.selection.selectedUrl)
            assertTrue(corruptPtBr.toURI().toString() in prepared.selection.failedUrls)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun downloadBadFilesFallBackToNoneCleanly() = runTest {
        val dir = Files.createTempDirectory("nuvio-sub-bad-dl").toFile()
        try {
            val htmlFile = File(dir, "bad.srt").apply { writeText("<!DOCTYPE html><html>not Found</html>") }
            val emptyFile = File(dir, "empty.srt").apply { writeText("   \n\n ") }
            val missingFile = File(dir, "missing.srt")
            val preparer = newPreparer(dir)

            val prepared = preparer.prepareDownloadedSubtitles(
                localSubtitles = listOf(
                    StreamSubtitle(url = htmlFile.toURI().toString(), language = "en", name = "English"),
                    StreamSubtitle(url = emptyFile.toURI().toString(), language = "en", name = "English 2"),
                    StreamSubtitle(url = missingFile.toURI().toString(), language = "en", name = "English 3"),
                ),
                settings = PlayerSettingsUiState(subtitleStyle = fullSubtitleStyle, preferredSubtitleLanguage = "en"),
                parentMetaId = "tt102",
            )

            assertTrue(prepared.subtitles.isEmpty())
            assertNull(prepared.selection.selectedUrl)
            assertTrue(prepared.selection.exhaustedOrTimedOut)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun disabledPreferenceKeepsLocalOptionsButMarksAutoSelectDisabled() = runTest {
        val dir = Files.createTempDirectory("nuvio-sub-off").toFile()
        try {
            val enFile = File(dir, "en.srt").apply { writeText(VALID_EN_SRT) }
            var networkCalls = 0
            val preparer = newPreparer(
                dir,
                addons = { _, _ ->
                    networkCalls++
                    emptyList()
                },
            )

            val dlPrepared = preparer.prepareDownloadedSubtitles(
                localSubtitles = listOf(StreamSubtitle(url = enFile.toURI().toString(), language = "en", name = "English")),
                settings = PlayerSettingsUiState(subtitleStyle = fullSubtitleStyle, preferredSubtitleLanguage = SubtitleLanguageOption.NONE),
                parentMetaId = "tt103",
            )
            assertEquals(1, dlPrepared.subtitles.size)
            assertNull(dlPrepared.selection.selectedUrl)
            assertTrue(dlPrepared.selection.disableAutoSelect)

            val streamPrepared = preparer.prepareStreamSubtitles(
                StartupSubtitleRequest(
                    type = "movie",
                    videoId = "tt103",
                    parentMetaId = "tt103",
                    settings = PlayerSettingsUiState(subtitleStyle = fullSubtitleStyle, preferredSubtitleLanguage = SubtitleLanguageOption.NONE),
                ),
            )
            assertTrue(streamPrepared.subtitles.isEmpty())
            assertTrue(streamPrepared.selection.disableAutoSelect)
            assertEquals(0, networkCalls)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun persistedShowAddonPreferenceOverridesGlobalDefault() = runTest {
        val dir = Files.createTempDirectory("nuvio-sub-persisted").toFile()
        try {
            val enFile = File(dir, "en.srt").apply { writeText(VALID_EN_SRT) }
            val assFile = File(dir, "es.ass").apply { writeText(VALID_ASS) }
            val preparer = newPreparer(
                dir,
                persistedPreference = {
                    PersistedPlayerTrackPreference(
                        subtitleType = PersistedSubtitleSelectionType.ADDON,
                        subtitleLanguage = "es",
                        addonSubtitleId = "download_1",
                    )
                },
            )

            val prepared = preparer.prepareDownloadedSubtitles(
                localSubtitles = listOf(
                    StreamSubtitle(url = enFile.toURI().toString(), language = "en", name = "English"),
                    StreamSubtitle(url = assFile.toURI().toString(), language = "es", name = "Spanish"),
                ),
                settings = PlayerSettingsUiState(subtitleStyle = fullSubtitleStyle, preferredSubtitleLanguage = "en"),
                parentMetaId = "tt-show",
            )

            assertEquals(assFile.toURI().toString(), prepared.selection.selectedUrl)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun deviceAndForcedPreferencesSelectExpectedSubtitle() = runTest {
        val dir = Files.createTempDirectory("nuvio-sub-forced").toFile()
        try {
            val fullEs = File(dir, "es_full.srt").apply { writeText(VALID_ES_SRT) }
            val forcedEs = File(dir, "es_forced.srt").apply { writeText(VALID_ES_SRT) }
            val preparer = newPreparer(dir, deviceLanguages = { listOf("es") })

            val devicePrepared = preparer.prepareDownloadedSubtitles(
                localSubtitles = listOf(
                    StreamSubtitle(url = forcedEs.toURI().toString(), language = "es", name = "Spanish (Forced)"),
                    StreamSubtitle(url = fullEs.toURI().toString(), language = "es", name = "Spanish"),
                ),
                settings = PlayerSettingsUiState(subtitleStyle = fullSubtitleStyle, preferredSubtitleLanguage = SubtitleLanguageOption.DEVICE),
                parentMetaId = "tt-device",
            )
            assertEquals(fullEs.toURI().toString(), devicePrepared.selection.selectedUrl)

            val forcedPrepared = preparer.prepareDownloadedSubtitles(
                localSubtitles = listOf(
                    StreamSubtitle(url = fullEs.toURI().toString(), language = "es", name = "Spanish"),
                    StreamSubtitle(url = forcedEs.toURI().toString(), language = "es", name = "Spanish (Forced)"),
                ),
                settings = PlayerSettingsUiState(subtitleStyle = fullSubtitleStyle, preferredAudioLanguage = "es",
                    preferredSubtitleLanguage = SubtitleLanguageOption.FORCED,
                ),
                parentMetaId = "tt-forced",
                audioLanguageHint = "es",
            )
            assertEquals(forcedEs.toURI().toString(), forcedPrepared.selection.selectedUrl)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun streamFetchesPreferredSubtitleInParallelWithSourceResolutionAndWritesLocalFile() = runTest {
        val cacheDir = Files.createTempDirectory("nuvio-sub-parallel").toFile()
        try {
            val subtitleStartedDuringSource = CompletableDeferred<Unit>()
            val subtitleUrl = "https://subs.example.invalid/es_track.srt"
            val preparer = newPreparer(
                cacheDir,
                addons = { _, onBatch ->
                    subtitleStartedDuringSource.complete(Unit)
                    val batch = listOf(
                        AddonSubtitle(
                            id = "sub-1",
                            url = subtitleUrl,
                            language = "es",
                            display = "Spanish",
                            addonName = "OpenSubtitles",
                        ),
                    )
                    onBatch(batch)
                    batch
                },
                remoteTexts = { url, _, _ ->
                    if (url == subtitleUrl) VALID_ES_SRT else null
                },
            )

            val (resolvedUrl, prepared) = preparer.prepareStreamInParallelWithSource(
                request = StartupSubtitleRequest(
                    type = "series",
                    videoId = "tt999:1:1",
                    parentMetaId = "tt999",
                    settings = PlayerSettingsUiState(subtitleStyle = fullSubtitleStyle, preferredSubtitleLanguage = "es"),
                ),
                resolveSource = {
                    // Proves subtitle discovery actually started concurrently BEFORE source finished.
                    subtitleStartedDuringSource.await()
                    delay(100L)
                    "https://cdn.example.invalid/episode1.mkv"
                },
            )

            assertEquals("https://cdn.example.invalid/episode1.mkv", resolvedUrl)
            val selectedUrl = prepared.selection.selectedUrl
            assertNotNull(selectedUrl)
            assertTrue(selectedUrl.startsWith("file:"), "Expected local file URI, got $selectedUrl")
            assertEquals(1, prepared.subtitles.size)
            assertEquals(selectedUrl, prepared.subtitles.single().url)
            assertEquals(selectedUrl, prepared.selection.localByRemoteUrl[subtitleUrl])
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun slowTrailingAddonDoesNotBlockFastPreferredMatch() = runTest {
        val cacheDir = Files.createTempDirectory("nuvio-sub-fast-addon").toFile()
        try {
            val enUrl = "https://subs.example.invalid/fast_en.vtt"
            val preparer = newPreparer(
                cacheDir,
                startupTimeoutMs = 3_000L,
                addons = { _, onBatch ->
                    onBatch(
                        listOf(
                            AddonSubtitle(
                                id = "fast-en",
                                url = enUrl,
                                language = "en",
                                display = "English",
                            ),
                        ),
                    )
                    awaitCancellation()
                },
                remoteTexts = { url, _, _ ->
                    if (url == enUrl) VALID_PT_BR_VTT else null
                },
            )

            val (_, prepared) = preparer.prepareStreamInParallelWithSource(
                request = StartupSubtitleRequest(
                    type = "movie",
                    videoId = "tt200",
                    parentMetaId = "tt200",
                    settings = PlayerSettingsUiState(subtitleStyle = fullSubtitleStyle, preferredSubtitleLanguage = "en"),
                ),
                resolveSource = { "https://cdn.example.invalid/movie.mp4" },
            )

            assertNotNull(prepared.selection.selectedUrl)
            assertTrue(prepared.selection.selectedUrl!!.startsWith("file:"))
            assertTrue(testScheduler.currentTime < 500L, "Should finish well before timeout")
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun hangingRemoteSubtitleTimesOutAndFallsBackToNone() = runTest {
        val cacheDir = Files.createTempDirectory("nuvio-sub-timeout").toFile()
        try {
            val hangingUrl = "https://tarpit.example.invalid/slow.srt"
            val preparer = newPreparer(
                cacheDir,
                startupTimeoutMs = 2_000L,
                perFileTimeoutMs = 1_500L,
                addons = { _, onBatch ->
                    val subs = listOf(AddonSubtitle(id = "slow", url = hangingUrl, language = "en", display = "English"))
                    onBatch(subs)
                    subs
                },
                remoteTexts = { _, _, _ ->
                    delay(60_000L)
                    VALID_EN_SRT
                },
            )

            val (_, prepared) = preparer.prepareStreamInParallelWithSource(
                request = StartupSubtitleRequest(
                    type = "movie",
                    videoId = "tt300",
                    parentMetaId = "tt300",
                    settings = PlayerSettingsUiState(subtitleStyle = fullSubtitleStyle, preferredSubtitleLanguage = "en"),
                ),
                resolveSource = {
                    delay(100L)
                    "https://cdn.example.invalid/video.mp4"
                },
            )

            assertNull(prepared.selection.selectedUrl)
            assertTrue(prepared.subtitles.isEmpty())
            assertTrue(prepared.selection.exhaustedOrTimedOut)
            assertTrue(hangingUrl in prepared.selection.failedUrls)
            assertTrue(testScheduler.currentTime <= 2_000L)
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun corruptPrimaryFallsBackToSecondaryOrNoneCleanly() = runTest {
        val cacheDir = Files.createTempDirectory("nuvio-sub-fallback").toFile()
        try {
            val badEnUrl = "https://subs.example.invalid/bad_en.srt"
            val goodEsUrl = "https://subs.example.invalid/good_es.srt"
            val preparer = newPreparer(
                cacheDir,
                addons = { _, onBatch ->
                    val batch = listOf(
                        AddonSubtitle(id = "bad-en", url = badEnUrl, language = "en", display = "English"),
                        AddonSubtitle(id = "good-es", url = goodEsUrl, language = "es", display = "Spanish"),
                    )
                    onBatch(batch)
                    batch
                },
                remoteTexts = { url, _, _ ->
                    when (url) {
                        badEnUrl -> "{\"error\":\"not found\"}"
                        goodEsUrl -> VALID_ES_SRT
                        else -> null
                    }
                },
            )

            val preparedWithSecondary = preparer.prepareStreamSubtitles(
                StartupSubtitleRequest(
                    type = "movie",
                    videoId = "tt400",
                    parentMetaId = "tt400",
                    settings = PlayerSettingsUiState(subtitleStyle = fullSubtitleStyle, preferredSubtitleLanguage = "en",
                        secondaryPreferredSubtitleLanguage = "es",
                    ),
                ),
            )
            assertNotNull(preparedWithSecondary.selection.selectedUrl)
            assertEquals("es", preparedWithSecondary.subtitles.single().language)
            assertTrue(badEnUrl in preparedWithSecondary.selection.failedUrls)

            val preparedAllBad = preparer.prepareStreamSubtitles(
                StartupSubtitleRequest(
                    type = "movie",
                    videoId = "tt401",
                    parentMetaId = "tt401",
                    settings = PlayerSettingsUiState(subtitleStyle = fullSubtitleStyle, preferredSubtitleLanguage = "en"),
                ),
            )
            assertNull(preparedAllBad.selection.selectedUrl)
            assertTrue(preparedAllBad.subtitles.isEmpty())
            assertTrue(preparedAllBad.selection.exhaustedOrTimedOut)
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun hangingLocalDownloadSidecarTimesOutAndFallsBackToNone() = runTest {
        val cacheDir = Files.createTempDirectory("nuvio-sub-dl-timeout").toFile()
        try {
            val slowPath = File(cacheDir, "slow_local.srt").toURI().toString()
            val preparer = newPreparer(
                cacheDir,
                localValidationTimeoutMs = 500L,
                localTexts = {
                    delay(10_000L)
                    VALID_EN_SRT
                },
            )

            val prepared = preparer.prepareDownloadedSubtitles(
                localSubtitles = listOf(StreamSubtitle(url = slowPath, language = "en", name = "English")),
                settings = PlayerSettingsUiState(subtitleStyle = fullSubtitleStyle, preferredSubtitleLanguage = "en"),
                parentMetaId = "tt500",
            )

            assertNull(prepared.selection.selectedUrl)
            assertTrue(prepared.subtitles.isEmpty())
            assertTrue(prepared.selection.exhaustedOrTimedOut)
            assertEquals(500L, testScheduler.currentTime)
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun onDemandResolveCachesValidLocalFileAndTimesOutBadRemote() = runTest {
        val cacheDir = Files.createTempDirectory("nuvio-sub-ondemand").toFile()
        try {
            val goodUrl = "https://subs.example.invalid/on_demand.srt"
            val slowUrl = "https://tarpit.example.invalid/hang.srt"
            var goodDownloads = 0
            val preparer = newPreparer(
                cacheDir,
                perFileTimeoutMs = 800L,
                remoteTexts = { url, _, _ ->
                    when (url) {
                        goodUrl -> {
                            goodDownloads++
                            VALID_EN_SRT
                        }
                        slowUrl -> {
                            delay(20_000L)
                            VALID_EN_SRT
                        }
                        else -> null
                    }
                },
            )

            val firstLocal = preparer.resolvePlayableLocalSubtitle(goodUrl)
            val secondLocal = preparer.resolvePlayableLocalSubtitle(goodUrl)
            assertNotNull(firstLocal)
            assertTrue(firstLocal.startsWith("file:"))
            assertEquals(firstLocal, secondLocal)
            assertEquals(1, goodDownloads)

            val timedOutLocal = preparer.resolvePlayableLocalSubtitle(slowUrl)
            assertNull(timedOutLocal)
            assertTrue(preparer.isRecentlyFailed(slowUrl))
        } finally {
            cacheDir.deleteRecursively()
        }
    }
    @Test
    fun forcedStylePreferenceSelectsForcedEnglishAttachment() = runTest {
        val dir = Files.createTempDirectory("nuvio-sub-forced-style").toFile()
        try {
            val full = "https://subs.example.invalid/full.srt"
            val forced = "https://subs.example.invalid/forced.srt"
            val preparer = newPreparer(dir, remoteTexts = { _, _, _ -> VALID_EN_SRT })
            val prepared = preparer.prepareStreamSubtitles(
                StartupSubtitleRequest(
                    type = "movie", videoId = "tt-forced-style", parentMetaId = "tt-forced-style",
                    settings = PlayerSettingsUiState(
                        preferredAudioLanguage = "en", preferredSubtitleLanguage = "en",
                        subtitleStyle = SubtitleStyleState.DEFAULT.copy(useForcedSubtitles = true),
                    ),
                    audioLanguageHint = "en",
                    streamExternalSubtitles = listOf(
                        StreamSubtitle(full, "en", "English"),
                        StreamSubtitle(forced, "en", "English (Forced)"),
                    ),
                ),
            )
            assertEquals(setOf(forced), prepared.selection.localByRemoteUrl.keys)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun successfulInFlightPrefetchSurvivesSlowSourceResolution() = runTest {
        val dir = Files.createTempDirectory("nuvio-sub-slow-source").toFile()
        try {
            val request = StartupSubtitleRequest(
                type = "movie", videoId = "tt-prefetch", parentMetaId = "tt-prefetch",
                settings = PlayerSettingsUiState(subtitleStyle = fullSubtitleStyle, preferredSubtitleLanguage = "en"),
            )
            val preparer = newPreparer(
                dir,
                addons = { _, onBatch ->
                    delay(100L)
                    listOf(AddonSubtitle("prefetch", "https://subs.example.invalid/en.srt", "en", "English"))
                        .also(onBatch)
                },
                remoteTexts = { _, _, _ -> VALID_EN_SRT },
            )
            val prefetch = preparer.prefetchForStream(this, request)
            runCurrent()
            val (_, prepared) = preparer.prepareStreamInParallelWithSource(
                request,
                resolveSource = { delay(5_000L); "source" },
            )
            assertNotNull(prepared.selection.selectedUrl)
            assertEquals(prefetch.await(), prepared)
            assertFalse(prepared.selection.exhaustedOrTimedOut)
            assertEquals(5_000L, testScheduler.currentTime)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun resolvedSourceSubtitleDoesNotWaitForEmptySlowPrefetch() = runTest {
        val dir = Files.createTempDirectory("nuvio-sub-prefetch-source").toFile()
        try {
            var discoveryCalls = 0
            val subtitle = StreamSubtitle("https://subs.example.invalid/resolved.srt", "en", "English")
            val request = StartupSubtitleRequest(
                type = "movie", videoId = "tt-resolved", parentMetaId = "tt-resolved",
                settings = PlayerSettingsUiState(subtitleStyle = fullSubtitleStyle, preferredSubtitleLanguage = "en"),
            )
            val preparer = newPreparer(
                dir,
                addons = { _, _ -> discoveryCalls++; awaitCancellation() },
                remoteTexts = { _, _, _ -> delay(500L); VALID_EN_SRT },
            )
            val prefetch = preparer.prefetchForStream(this, request)
            runCurrent()
            advanceTimeBy(100L)
            val (source, prepared) = preparer.prepareStreamInParallelWithSource(
                request,
                resolveSource = { delay(100L); listOf(subtitle) },
                extractStreamSubtitles = { it },
            )
            assertEquals(listOf(subtitle), source)
            assertNotNull(prepared.selection.selectedUrl)
            assertEquals(setOf(subtitle.url), prepared.selection.localByRemoteUrl.keys)
            assertEquals(700L, testScheduler.currentTime)
            assertEquals(1, discoveryCalls)
            assertFalse(prefetch.isCompleted)
            prefetch.cancel()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun failedResolvedSourceSubtitleStillUsesSuccessfulPrefetch() = runTest {
        val dir = Files.createTempDirectory("nuvio-sub-prefetch-fallback").toFile()
        try {
            val attachedUrl = "https://subs.example.invalid/bad.srt"
            val addonUrl = "https://subs.example.invalid/addon.srt"
            val request = StartupSubtitleRequest(
                type = "movie", videoId = "tt-source-fallback", parentMetaId = "tt-source-fallback",
                settings = PlayerSettingsUiState(subtitleStyle = fullSubtitleStyle, preferredSubtitleLanguage = "en"),
            )
            val preparer = newPreparer(
                dir,
                addons = { _, onBatch ->
                    delay(800L)
                    listOf(AddonSubtitle("prefetch", addonUrl, "en", "English")).also(onBatch)
                },
                remoteTexts = { url, _, _ -> if (url == addonUrl) VALID_EN_SRT else null },
            )
            val prefetch = preparer.prefetchForStream(this, request)
            runCurrent()
            val (_, prepared) = preparer.prepareStreamInParallelWithSource(
                request,
                resolveSource = { listOf(StreamSubtitle(attachedUrl, "en", "English")) },
                extractStreamSubtitles = { it },
            )
            assertNotNull(prepared.selection.selectedUrl)
            assertEquals(setOf(addonUrl), prepared.selection.localByRemoteUrl.keys)
            assertTrue(attachedUrl in prepared.selection.failedUrls)
            assertEquals(800L, testScheduler.currentTime)
            assertEquals(prefetch.await().selection.selectedUrl, prepared.selection.selectedUrl)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun resolvedSecondarySubtitleDoesNotDisplacePendingPrimaryPrefetch() = runTest {
        val dir = Files.createTempDirectory("nuvio-sub-prefetch-primary").toFile()
        try {
            val addonUrl = "https://subs.example.invalid/primary.srt"
            val downloadedUrls = mutableListOf<String>()
            val request = StartupSubtitleRequest(
                type = "movie", videoId = "tt-primary", parentMetaId = "tt-primary",
                settings = PlayerSettingsUiState(
                    subtitleStyle = fullSubtitleStyle, preferredSubtitleLanguage = "en",
                    secondaryPreferredSubtitleLanguage = "es",
                ),
            )
            val preparer = newPreparer(
                dir,
                addons = { _, onBatch ->
                    delay(800L)
                    listOf(AddonSubtitle("prefetch", addonUrl, "en", "English")).also(onBatch)
                },
                remoteTexts = { url, _, _ -> downloadedUrls.add(url); VALID_EN_SRT },
            )
            preparer.prefetchForStream(this, request)
            runCurrent()
            val (_, prepared) = preparer.prepareStreamInParallelWithSource(
                request,
                resolveSource = { listOf(StreamSubtitle("https://subs.example.invalid/secondary.srt", "es", "Spanish")) },
                extractStreamSubtitles = { it },
            )
            assertEquals(setOf(addonUrl), prepared.selection.localByRemoteUrl.keys)
            assertEquals(listOf(addonUrl), downloadedUrls)
            assertEquals(800L, testScheduler.currentTime)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun hangingResolvedSubtitleKeepsOriginalStartupDeadline() = runTest {
        val dir = Files.createTempDirectory("nuvio-sub-prefetch-deadline").toFile()
        try {
            var sourceDownloadStarted = false
            var sourceDownloadCancelled = false
            val request = StartupSubtitleRequest(
                type = "movie", videoId = "tt-source-timeout", parentMetaId = "tt-source-timeout",
                settings = PlayerSettingsUiState(subtitleStyle = fullSubtitleStyle, preferredSubtitleLanguage = "en"),
            )
            val preparer = newPreparer(
                dir,
                perFileTimeoutMs = 10_000L,
                addons = { _, _ -> awaitCancellation() },
                remoteTexts = { _, _, _ ->
                    sourceDownloadStarted = true
                    try { awaitCancellation() } finally { sourceDownloadCancelled = true }
                },
            )
            preparer.prefetchForStream(this, request)
            runCurrent()
            advanceTimeBy(100L)
            val (_, prepared) = preparer.prepareStreamInParallelWithSource(
                request,
                resolveSource = { listOf(StreamSubtitle("https://subs.example.invalid/hanging.srt", "en", "English")) },
                extractStreamSubtitles = { it },
            )
            assertNull(prepared.selection.selectedUrl)
            assertTrue(prepared.selection.exhaustedOrTimedOut)
            assertTrue(sourceDownloadStarted)
            assertTrue(sourceDownloadCancelled)
            assertEquals(3_100L, testScheduler.currentTime)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readyPrefetchInterruptsHangingResolvedSubtitle() = runTest {
        val dir = Files.createTempDirectory("nuvio-sub-prefetch-interrupt").toFile()
        try {
            val addonUrl = "https://subs.example.invalid/ready.srt"
            var attachmentCancelled = false
            val request = StartupSubtitleRequest(
                type = "movie", videoId = "tt-ready-prefetch", parentMetaId = "tt-ready-prefetch",
                settings = PlayerSettingsUiState(subtitleStyle = fullSubtitleStyle, preferredSubtitleLanguage = "en"),
            )
            val preparer = newPreparer(
                dir,
                perFileTimeoutMs = 10_000L,
                addons = { _, onBatch ->
                    delay(800L)
                    listOf(AddonSubtitle("prefetch", addonUrl, "en", "English")).also(onBatch)
                },
                remoteTexts = { url, _, _ ->
                    if (url == addonUrl) VALID_EN_SRT else {
                        try { awaitCancellation() } finally { attachmentCancelled = true }
                    }
                },
            )
            preparer.prefetchForStream(this, request)
            runCurrent()
            val (_, prepared) = preparer.prepareStreamInParallelWithSource(
                request,
                resolveSource = { listOf(StreamSubtitle("https://subs.example.invalid/hanging.srt", "en", "English")) },
                extractStreamSubtitles = { it },
            )
            assertEquals(setOf(addonUrl), prepared.selection.localByRemoteUrl.keys)
            assertTrue(attachmentCancelled)
            assertEquals(800L, testScheduler.currentTime)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun captionsOffPreservesInitialAndResolvedStreamAttachments() = runTest {
        val dir = Files.createTempDirectory("nuvio-sub-off-attachments").toFile()
        try {
            var networkCalls = 0
            val preparer = newPreparer(dir, addons = { _, _ -> networkCalls++; emptyList() })
            val initial = StreamSubtitle("https://subs.example.invalid/en.srt", "en", "English")
            val resolved = StreamSubtitle("https://subs.example.invalid/es.srt", "es", "Spanish")
            val request = StartupSubtitleRequest(
                type = "movie", videoId = "tt-off", parentMetaId = "tt-off",
                settings = PlayerSettingsUiState(subtitleStyle = fullSubtitleStyle, preferredSubtitleLanguage = SubtitleLanguageOption.NONE),
                streamExternalSubtitles = listOf(initial),
            )
            val direct = preparer.prepareStreamSubtitles(request)
            assertEquals(listOf(initial), direct.subtitles)
            assertTrue(direct.selection.disableAutoSelect)
            assertNull(direct.selection.selectedUrl)
            val (_, parallel) = preparer.prepareStreamInParallelWithSource(
                request, resolveSource = { listOf(resolved) }, extractStreamSubtitles = { it },
            )
            assertEquals(listOf(initial, resolved), parallel.subtitles)
            assertTrue(parallel.selection.disableAutoSelect)
            assertNull(parallel.selection.selectedUrl)
            assertEquals(0, networkCalls)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun localValidationTimeoutIncludesParsing() = runTest {
        val dir = Files.createTempDirectory("nuvio-sub-parse-timeout").toFile()
        try {
            var parsingStarted = false
            val preparer = newPreparer(
                dir,
                localTexts = { VALID_EN_SRT },
                validator = { _, _ ->
                    parsingStarted = true
                    delay(10_000L)
                    true
                },
                localValidationTimeoutMs = 500L,
            )
            val prepared = preparer.prepareDownloadedSubtitles(
                listOf(StreamSubtitle("file:///tmp/slow-parse.srt", "en", "English")),
                PlayerSettingsUiState(subtitleStyle = fullSubtitleStyle, preferredSubtitleLanguage = "en"),
                "tt-slow-parse",
            )
            assertTrue(parsingStarted)
            assertNull(prepared.selection.selectedUrl)
            assertTrue(prepared.selection.exhaustedOrTimedOut)
            assertEquals(500L, testScheduler.currentTime)
        } finally {
            dir.deleteRecursively()
        }
    }

}
