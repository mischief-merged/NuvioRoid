package com.nuvio.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.nuvio.app.OfflineLaunch
import com.nuvio.app.features.catalog.CatalogUiState
import com.nuvio.app.features.catalog.catalogShowsLoadMoreRetry
import com.nuvio.app.features.catalog.catalogStateAfterPageFailure
import com.nuvio.app.features.collection.CollectionBodyKind
import com.nuvio.app.features.collection.CollectionFolder
import com.nuvio.app.features.collection.FolderDetailUiState
import com.nuvio.app.features.collection.FolderTab
import com.nuvio.app.features.collection.collectionBodyKind
import com.nuvio.app.features.collection.folderBodyKind
import com.nuvio.app.features.collection.CollectionSource
import com.nuvio.app.features.collection.FolderSourceRetry
import com.nuvio.app.features.collection.collectionFailureErrors
import com.nuvio.app.features.collection.collectionFailureMessage
import com.nuvio.app.features.collection.folderRetryTargets
import com.nuvio.app.features.collection.folderRetryUsesReset
import com.nuvio.app.features.collection.folderRowsBodyKind
import com.nuvio.app.features.collection.folderSourceRetry
import com.nuvio.app.features.collection.folderTabAfterLoadFailure
import com.nuvio.app.features.details.DetailLoadOutcome
import com.nuvio.app.features.details.detailLoadKey
import com.nuvio.app.features.details.loadOrFail
import com.nuvio.app.features.details.nextDetailAttempt
import com.nuvio.app.features.home.EmptyScreenAction
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.failedEmptyActions
import com.nuvio.app.features.home.performEmptyAction
import com.nuvio.app.features.home.setupEmptyActions
import com.nuvio.app.features.home.visibleEmptyActions
import com.nuvio.app.features.player.subtitleControlsThatHideTheirValue
import com.nuvio.app.features.player.subtitleStyleRenderOmitsValue
import com.nuvio.app.features.player.unnamedPlayerControls
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.search.SearchFieldKey
import com.nuvio.app.features.search.searchFieldConsumesKey
import com.nuvio.app.features.search.searchFieldReleasesFocus
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.CustomThemeColors
import com.nuvio.app.core.ui.FailureKind
import com.nuvio.app.core.ui.FailureMessages
import com.nuvio.app.core.ui.MinimumTextContrast
import com.nuvio.app.core.ui.ThemeColors
import com.nuvio.app.core.ui.activeControlContentAlpha
import com.nuvio.app.core.ui.classifyFailure
import com.nuvio.app.core.ui.contrastRatio
import com.nuvio.app.core.ui.darkSurfaceRippleColor
import com.nuvio.app.core.ui.nextPosterAttempt
import com.nuvio.app.core.ui.posterShowsTitleOnFace
import com.nuvio.app.core.ui.toColorPalette
import com.nuvio.app.core.ui.userFacingErrorMessage
import com.nuvio.app.offlineLaunch
import com.nuvio.app.revealShellForOffline
import com.nuvio.app.startupContentAlpha
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private val failureMessages = FailureMessages(
    timeout = "timed out",
    unreachable = "unreachable",
    refused = "refused",
    insecure = "insecure",
    playbackUnavailable = "playback",
    notFound = "missing",
    rateLimited = "slow down",
    server = "server problem",
    denied = "denied",
)

private fun title(id: String = "1") = MetaPreview(id = id, type = "movie", name = "Title $id")

class UserFacingErrorTest {
    @Test
    fun technical_failures_become_plain_sentences() {
        assertEquals(FailureKind.Timeout, classifyFailure("java.net.SocketTimeoutException: timed out"))
        assertEquals("timed out", userFacingErrorMessage("SocketTimeoutException: Read timed out", "fallback", failureMessages))
        assertEquals(FailureKind.Unreachable, classifyFailure("UnknownHostException: no address associated"))
        assertEquals("unreachable", userFacingErrorMessage("Unable to resolve host", "fallback", failureMessages))
        assertEquals("refused", userFacingErrorMessage("Connection refused", "fallback", failureMessages))
        assertEquals("insecure", userFacingErrorMessage("SSL handshake failed", "fallback", failureMessages))
        assertEquals("missing", userFacingErrorMessage("HTTP status 404", "fallback", failureMessages))
        assertEquals("slow down", userFacingErrorMessage("status code 429", "fallback", failureMessages))
        assertEquals("denied", userFacingErrorMessage("403 Forbidden", "fallback", failureMessages))
        assertEquals("server problem", userFacingErrorMessage("HTTP 503", "fallback", failureMessages))
        assertEquals("playback", userFacingErrorMessage("Player engine not available. Rebuild the app.", "fallback", failureMessages))
    }

    @Test
    fun raw_exceptions_and_regex_use_the_fallback_and_plain_sentences_stay() {
        assertEquals("fallback", userFacingErrorMessage("java.lang.NullPointerException", "fallback", failureMessages))
        assertEquals("fallback", userFacingErrorMessage("IllegalStateException: bad state", "fallback", failureMessages))
        assertEquals("fallback", userFacingErrorMessage("io.ktor.client.plugins.ClientRequestException", "fallback", failureMessages))
        assertEquals("fallback", userFacingErrorMessage("Unexpected JSON parse error", "fallback", failureMessages))
        assertEquals("fallback", userFacingErrorMessage("That regex is invalid", "fallback", failureMessages))
        assertEquals("fallback", userFacingErrorMessage("", "fallback", failureMessages))
        assertEquals("fallback", userFacingErrorMessage(null, "fallback", failureMessages))
        assertEquals("Check the title and try again.", userFacingErrorMessage("Check the title and try again.", "fallback", failureMessages))
    }
}

class CatalogPageFailureTest {
    @Test
    fun a_later_page_keeps_the_cursor_and_offers_retry() {
        val before = CatalogUiState(items = listOf(title()), nextSkip = 50)
        val after = catalogStateAfterPageFailure(before, reset = false, message = "Couldn't load these titles. Try again.")
        assertEquals(before.items, after.items)
        assertEquals(50, after.nextSkip)
        assertTrue(after.loadMoreFailed)
        assertFalse(after.canLoadMore)
        assertTrue(catalogShowsLoadMoreRetry(after))
        assertEquals("Couldn't load these titles. Try again.", after.errorMessage)
    }

    @Test
    fun the_first_page_does_not_pretend_there_is_another_page() {
        val before = CatalogUiState(items = listOf(title()), nextSkip = 50)
        val after = catalogStateAfterPageFailure(before, reset = true, message = "Couldn't load these titles. Try again.")
        assertTrue(after.items.isEmpty())
        assertEquals(null, after.nextSkip)
        assertFalse(after.loadMoreFailed)
        assertFalse(catalogShowsLoadMoreRetry(after))
    }
}

class CollectionAvailabilityTest {
    @Test
    fun empty_failed_and_partial_lists_are_different() {
        assertEquals(
            CollectionBodyKind.Loading,
            collectionBodyKind(loading = true, sourceCount = 0, sourcesWithItems = 0, failedSources = 0),
        )
        assertEquals(
            CollectionBodyKind.Empty,
            collectionBodyKind(loading = false, sourceCount = 1, sourcesWithItems = 0, failedSources = 0),
        )
        assertEquals(
            CollectionBodyKind.Loading,
            collectionBodyKind(loading = true, sourceCount = 2, sourcesWithItems = 0, failedSources = 1),
        )
        assertEquals(
            CollectionBodyKind.Failed,
            collectionBodyKind(loading = false, sourceCount = 2, sourcesWithItems = 0, failedSources = 2),
        )
        assertEquals(
            CollectionBodyKind.Partial,
            collectionBodyKind(loading = false, sourceCount = 2, sourcesWithItems = 1, failedSources = 1),
        )
        assertEquals(
            CollectionBodyKind.Ready,
            collectionBodyKind(loading = false, sourceCount = 1, sourcesWithItems = 1, failedSources = 0),
        )
    }

    @Test
    fun a_failed_later_page_keeps_titles_and_retries_only_that_source() {
        val tab = FolderTab(label = "Movies", items = listOf(title()), nextSkip = 20, supportsPagination = true)
        val failed = folderTabAfterLoadFailure(tab, reset = false, message = "Check your connection and try again.")
        assertEquals(tab.items, failed.items)
        assertEquals(20, failed.nextSkip)
        assertEquals("Check your connection and try again.", failed.error)
        assertFalse(failed.canLoadMore)
        assertFalse(folderRetryUsesReset(failed))

        val other = FolderTab(label = "Shows", error = "down")
        val all = FolderTab(label = "All", isAllTab = true)
        assertEquals(listOf(0), folderRetryTargets(listOf(failed, other, all), selectedIndex = 0))
        assertEquals(listOf(0, 1), folderRetryTargets(listOf(failed, other, all), selectedIndex = 2))
        assertTrue(folderRetryUsesReset(FolderTab(label = "Empty", error = "down")))
    }

    @Test
    fun the_open_list_and_the_row_layout_use_the_right_sources() {
        val folder = CollectionFolder(id = "f", title = "Folder")
        val failed = FolderTab(label = "Movies", error = "down", isLoading = false)
        val ready = FolderTab(label = "Shows", items = listOf(title()), isLoading = false)
        val all = FolderTab(label = "All", isAllTab = true, items = ready.items, isLoading = false)
        val state = FolderDetailUiState(
            folder = folder,
            tabs = listOf(all, failed, ready),
            selectedTabIndex = 0,
            isLoading = false,
        )
        assertEquals(CollectionBodyKind.Partial, folderBodyKind(state))
        assertEquals(CollectionBodyKind.Partial, folderRowsBodyKind(state))
        assertEquals(CollectionBodyKind.Failed, folderBodyKind(state.copy(selectedTabIndex = 1)))
    }

    @Test
    fun a_missing_source_is_asked_for_again_and_keeps_its_own_message() {
        val source = CollectionSource(
            provider = "addon",
            addonId = "comet",
            type = "movie",
            catalogId = "top",
        )
        val missing = FolderTab(
            label = "Movies",
            source = source,
            manifestUrl = null,
            error = "Addon not found: comet",
            isLoading = false,
        )
        val connection = "Check your connection and try again."
        assertEquals(
            FolderSourceRetry.StillMissing("Addon not found: comet"),
            folderSourceRetry(missing, resolvedManifestUrl = null, resolvedSupportsPagination = false, missingMessage = "Addon not found: comet"),
        )
        assertEquals(
            FolderSourceRetry.AttachThenLoad(manifestUrl = "https://comet.test", supportsPagination = true),
            folderSourceRetry(missing, resolvedManifestUrl = "https://comet.test", resolvedSupportsPagination = true, missingMessage = "Addon not found: comet"),
        )
        assertEquals(
            FolderSourceRetry.LoadPage,
            folderSourceRetry(
                missing.copy(manifestUrl = "https://comet.test"),
                resolvedManifestUrl = null,
                resolvedSupportsPagination = false,
                missingMessage = "Addon not found: comet",
            ),
        )
        assertEquals(
            FolderSourceRetry.LoadPage,
            folderSourceRetry(
                FolderTab(label = "TMDB", source = CollectionSource(provider = "tmdb"), manifestUrl = null),
                resolvedManifestUrl = null,
                resolvedSupportsPagination = false,
                missingMessage = "Addon not found: comet",
            ),
        )
        assertEquals(listOf("Addon not found: comet"), collectionFailureErrors(listOf(missing), selectedIndex = 0, rows = false))
        assertEquals("Addon not found: comet", collectionFailureMessage(listOf("Addon not found: comet"), connection))
        assertEquals(connection, collectionFailureMessage(listOf(connection), connection))
        assertEquals(connection, collectionFailureMessage(emptyList(), connection))
        assertEquals(
            "Addon not found: comet",
            collectionFailureMessage(listOf(connection, "Addon not found: comet"), connection),
        )
    }
}

class DetailLoadTest {
    @Test
    fun retry_changes_the_load_key_and_a_null_result_is_a_failure() = runBlocking {
        assertEquals("person:4#0", detailLoadKey("person:4", 0))
        assertEquals("person:4#1", detailLoadKey("person:4", nextDetailAttempt(0)))
        val ready = loadOrFail(0, "Couldn't load this person.") { "Ada" }
        assertEquals(DetailLoadOutcome.Ready("Ada"), ready)
        val failed = loadOrFail(1, "Couldn't load this person.") { null }
        assertEquals(DetailLoadOutcome.Failed("Couldn't load this person."), failed)
    }
}

class SearchFocusTest {
    @Test
    fun escape_and_tab_leave_the_field_and_only_escape_is_consumed() {
        assertTrue(searchFieldReleasesFocus(SearchFieldKey.Escape))
        assertTrue(searchFieldReleasesFocus(SearchFieldKey.Tab))
        assertFalse(searchFieldReleasesFocus(SearchFieldKey.Other))
        assertTrue(searchFieldConsumesKey(SearchFieldKey.Escape))
        assertFalse(searchFieldConsumesKey(SearchFieldKey.Tab))
    }
}

class PosterArtworkTest {
    @Test
    fun a_missing_or_failed_image_shows_the_title_and_can_be_tried_again() {
        assertTrue(posterShowsTitleOnFace(null, imageFailed = false))
        assertTrue(posterShowsTitleOnFace("  ", imageFailed = false))
        assertTrue(posterShowsTitleOnFace("https://example.test/poster.jpg", imageFailed = true))
        assertFalse(posterShowsTitleOnFace("https://example.test/poster.jpg", imageFailed = false))
        assertEquals(2, nextPosterAttempt(1))
    }
}

class EmptyScreenActionsTest {
    @Test
    fun empty_and_failed_screens_offer_the_next_step() {
        assertEquals(
            listOf(EmptyScreenAction.AddSource, EmptyScreenAction.ConnectServer),
            setupEmptyActions(offline = false),
        )
        assertEquals(
            listOf(EmptyScreenAction.AddSource, EmptyScreenAction.ConnectServer, EmptyScreenAction.OpenDownloads),
            setupEmptyActions(offline = true),
        )
        assertEquals(
            listOf(EmptyScreenAction.Retry, EmptyScreenAction.OpenDownloads),
            failedEmptyActions(offline = true),
        )
        assertEquals(
            listOf(EmptyScreenAction.Retry),
            visibleEmptyActions(failedEmptyActions(offline = true), downloadsEnabled = false),
        )
        val called = mutableListOf<String>()
        performEmptyAction(
            EmptyScreenAction.ConnectServer,
            onAddSource = { called += "add" },
            onConnectServer = { called += "server" },
            onOpenDownloads = { called += "downloads" },
            onRetry = { called += "retry" },
        )
        assertEquals(listOf("server"), called)
    }
}

class StartupShellTest {
    @Test
    fun the_shell_is_visible_before_the_catalog_and_offline_can_open_downloads() {
        assertEquals(1f, startupContentAlpha(catalogReady = false))
        assertEquals(1f, startupContentAlpha(catalogReady = true))
        assertTrue(revealShellForOffline(offline = true))
        assertFalse(revealShellForOffline(offline = false))
        assertEquals(
            OfflineLaunch.Wait,
            offlineLaunch(false, networkKnown = false, offline = true, downloadsEnabled = true, hasPlayableDownload = true),
        )
        assertEquals(
            OfflineLaunch.OpenDownloads,
            offlineLaunch(false, networkKnown = true, offline = true, downloadsEnabled = true, hasPlayableDownload = true),
        )
        assertEquals(
            OfflineLaunch.StayHere,
            offlineLaunch(false, networkKnown = true, offline = true, downloadsEnabled = true, hasPlayableDownload = false),
        )
        assertEquals(
            OfflineLaunch.StayHere,
            offlineLaunch(false, networkKnown = true, offline = false, downloadsEnabled = true, hasPlayableDownload = true),
        )
        assertEquals(
            OfflineLaunch.Wait,
            offlineLaunch(true, networkKnown = true, offline = true, downloadsEnabled = true, hasPlayableDownload = true),
        )
    }
}

class ContrastThemeTest {
    @Test
    fun built_in_button_text_meets_the_contrast_minimum() {
        AppTheme.entries.filter { it != AppTheme.CUSTOM }.forEach { theme ->
            val palette = ThemeColors.getColorPalette(theme)
            val ratio = contrastRatio(palette.onSecondary, palette.secondary)
            assertTrue(ratio >= MinimumTextContrast, "${theme.name} contrast $ratio")
        }
    }

    @Test
    fun custom_button_text_follows_the_accent_and_the_dark_ripple_is_light() {
        val bright = CustomThemeColors(0x111111, 0xFB8C00, 0x111111).toColorPalette()
        val dark = CustomThemeColors(0x111111, 0x1A237E, 0x111111).toColorPalette()
        assertTrue(contrastRatio(bright.onSecondary, bright.secondary) >= MinimumTextContrast)
        assertTrue(contrastRatio(dark.onSecondary, dark.secondary) >= MinimumTextContrast)
        assertEquals(Color.Black, bright.onSecondary)
        assertEquals(Color.White, dark.onSecondary)
        val ripple = darkSurfaceRippleColor()
        assertEquals(Color.White, ripple)
        assertTrue(ripple.luminance() > 0.5f)
        assertTrue(ripple != Color.Black)
        assertEquals(1f, activeControlContentAlpha())
        val surface = Color(0xFF1A1A1A)
        val muted = Color(0xFF969CA3)
        val blended = Color(
            red = surface.red + (muted.red - surface.red) * 0.52f,
            green = surface.green + (muted.green - surface.green) * 0.52f,
            blue = surface.blue + (muted.blue - surface.blue) * 0.52f,
        )
        assertTrue(contrastRatio(blended, surface) < 3f)
        assertTrue(contrastRatio(Color(0xFFF5F7F8), surface) >= MinimumTextContrast)
    }
}

class PlayerControlNamesTest {
    @Test
    fun empty_labels_and_bare_symbols_are_unnamed_and_the_player_file_is_not() {
        val sample = """
            <button id="ok" aria-label="Play"></button>
            <button id="blank" aria-label=""></button>
            <button id="fit"><span>Fit</span></button>
            <button id="minus">-</button>
            <button id="hidden" aria-hidden="true"></button>
        """.trimIndent()
        assertEquals(listOf("blank", "minus"), unnamedPlayerControls(sample))
        assertEquals(emptyList(), unnamedPlayerControls(readProjectFile("src/desktopMain/resources/player-ui/controls.html")))
        val script = readProjectFile("src/desktopMain/resources/player-ui/controls.js")
        assertTrue(script.contains("setAttribute(\"aria-label\""))
        assertTrue(script.contains("Subtitle text color"))
    }

    @Test
    fun subtitle_style_controls_say_whether_they_are_on_and_which_font_is_chosen() {
        val html = readProjectFile("src/desktopMain/resources/player-ui/controls.html")
        val script = readProjectFile("src/desktopMain/resources/player-ui/controls.js")
        assertEquals(emptyList(), subtitleControlsThatHideTheirValue(html))
        assertEquals(emptyList(), subtitleStyleRenderOmitsValue(script))
    }
}

class MatchingSourceCopyTest {
    @Test
    fun the_pattern_copy_says_that_special_characters_change_which_source_plays() {
        val xml = readProjectFile("src/commonMain/composeResources/values/strings.xml")
        val label = xmlString(xml, "settings_playback_regex_pattern")
        val help = xmlString(xml, "settings_playback_regex_matches_against")
        val placeholder = xmlString(xml, "settings_playback_regex_placeholder")
        val mode = xmlString(xml, "settings_playback_stream_selection_mode_regex_description")
        assertFalse(label.contains("word", ignoreCase = true))
        assertFalse(help.contains("word", ignoreCase = true))
        assertFalse(placeholder.contains("word", ignoreCase = true))
        assertFalse(mode.contains("word", ignoreCase = true))
        assertTrue(help.contains("+"))
        assertTrue(mode.contains("+"))

        val plain = StreamItem(
            name = "Movie HDR",
            url = "https://example.test/hdr.mp4",
            addonName = "Addon",
            addonId = "addon:1",
        )
        val plus = plain.copy(name = "Movie HDR+", url = "https://example.test/hdr-plus.mp4")
        assertEquals(plain, pickMatchingSource("HDR+", listOf(plain)))
        assertNull(pickMatchingSource("HDR\\+", listOf(plain)))
        assertEquals(plus, pickMatchingSource("HDR\\+", listOf(plain, plus)))
    }
}

private fun pickMatchingSource(pattern: String, streams: List<StreamItem>) =
    StreamAutoPlaySelector.evaluateAutoPlayStream(
        streams = streams,
        mode = StreamAutoPlayMode.REGEX_MATCH,
        regexPattern = pattern,
        source = StreamAutoPlaySource.ALL_SOURCES,
        installedAddonNames = setOf("Addon"),
        selectedAddons = emptySet(),
        selectedPlugins = emptySet(),
    ).stream

private fun xmlString(xml: String, name: String): String {
    val match = Regex("""<string name="$name">([^<]*)</string>""").find(xml)
    return match?.groupValues?.get(1) ?: error("Missing string $name")
}

private fun readProjectFile(relativeToComposeApp: String): String {
    val candidates = listOf(
        File(relativeToComposeApp),
        File("composeApp/$relativeToComposeApp"),
    )
    candidates.firstOrNull { it.isFile }?.let { return it.readText() }
    val resource = relativeToComposeApp.substringAfter("resources/")
    val stream = PlayerControlNamesTest::class.java.classLoader.getResourceAsStream(resource)
    return stream?.bufferedReader()?.use { it.readText() }
        ?: error("Missing $relativeToComposeApp from ${File(".").absolutePath}")
}
