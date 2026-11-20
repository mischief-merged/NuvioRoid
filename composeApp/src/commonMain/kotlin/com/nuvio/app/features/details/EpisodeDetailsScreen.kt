package com.nuvio.app.features.details

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.ContentPlayAction
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.i18n.localizedEpisodeTitle
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.NuvioDesktopVerticalScrollbar
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.desktopPageHorizontalPaddingForWidth
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.details.components.formatRating
import com.nuvio.app.features.downloads.AvailableOfflineBadge
import com.nuvio.app.features.downloads.OfflineDownloadIndex
import com.nuvio.app.features.downloads.rememberOfflineDownloadIndex
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.formatPlaybackTime
import com.nuvio.app.features.streams.StreamAutoPlayPolicy
import com.nuvio.app.features.streams.rememberPlaybackAvailability
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watching.application.WatchingActions
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import com.nuvio.app.isDesktop
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

private val EpisodePageMaxContentWidth = 1100.dp
private val EpisodePageTwoPaneMinWidth = 840.dp
private val EpisodePageButtonMinHeight = 48.dp

/**
 * The page of one episode: the still, what it is about, and the actions that belong to it,
 * reached from the show page, the season, Downloads and Continue Watching.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EpisodeDetailsScreen(
    type: String,
    id: String,
    videoId: String,
    season: Int?,
    episode: Int?,
    onBack: () -> Unit,
    onOpenShow: () -> Unit,
    onOpenSeason: (seasonNumber: Int) -> Unit,
    onOpenEpisode: (MetaVideo) -> Unit,
    onPlay: ContentPlayAction,
    onPlayManually: ContentPlayAction,
    modifier: Modifier = Modifier,
) {
    val uiState by MetaDetailsRepository.uiState.collectAsStateWithLifecycle()
    val loadedMeta = uiState.meta?.takeIf { it.type == type && it.id == id }
        ?: MetaDetailsRepository.peek(type, id)
    val networkStatus by NetworkStatusRepository.uiState.collectAsStateWithLifecycle()
    // When the details cannot be loaded, what downloads saved stands in for them.
    val savedLookup by rememberSavedLookup(
        type, id, wanted = savedDetailsWanted(loadedMeta != null, uiState.errorMessage, networkStatus.condition),
    )
    val meta = loadedMeta ?: savedLookup.meta
    var autoLoadAttempted by remember(type, id) { mutableStateOf(false) }
    var observedOfflineState by remember(type, id) { mutableStateOf(false) }

    LaunchedEffect(type, id, loadedMeta, uiState.isLoading, autoLoadAttempted) {
        if (!autoLoadAttempted && loadedMeta == null && !uiState.isLoading) {
            autoLoadAttempted = true
            MetaDetailsRepository.load(type, id)
        }
    }

    LaunchedEffect(networkStatus.condition, loadedMeta, uiState.isLoading, type, id) {
        if (networkStatus.condition == NetworkCondition.NoInternet) {
            observedOfflineState = true
        } else if (networkStatus.hasInternet && observedOfflineState) {
            observedOfflineState = false
            if (loadedMeta == null && !uiState.isLoading) {
                MetaDetailsRepository.load(type, id)
            }
        }
    }

    val video = remember(meta, videoId, season, episode) { meta?.findEpisode(videoId, season, episode) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val pageHorizontalPadding = if (isDesktop) {
            desktopPageHorizontalPaddingForWidth(maxWidth.value)
        } else {
            16.dp
        }
        val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
            if (isDesktop) 32.dp else 8.dp

        Column(modifier = Modifier.fillMaxSize()) {
            EpisodePageHeader(
                meta = meta,
                video = video,
                horizontalPadding = pageHorizontalPadding,
                topPadding = topPadding,
                onBack = onBack,
                onOpenShow = onOpenShow,
                onOpenSeason = onOpenSeason,
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    meta == null && uiState.errorMessage != null && !uiState.isLoading && savedLookup.settled -> {
                        EpisodePageMessage(
                            title = stringResource(Res.string.details_failed_to_load),
                            message = when (networkStatus.condition) {
                                NetworkCondition.NoInternet -> stringResource(Res.string.details_check_connection)
                                NetworkCondition.ServersUnreachable -> stringResource(Res.string.details_servers_unreachable)
                                else -> uiState.errorMessage.orEmpty()
                            },
                            actionLabel = stringResource(Res.string.action_retry),
                            onAction = {
                                NetworkStatusRepository.requestRefresh(force = true)
                                MetaDetailsRepository.load(type, id, forceNetwork = true)
                            },
                        )
                    }

                    meta == null -> {
                        NuvioLoadingIndicator(modifier = Modifier.align(Alignment.Center))
                    }

                    video == null -> {
                        EpisodePageMessage(
                            title = stringResource(Res.string.episode_page_not_found_title),
                            message = stringResource(Res.string.episode_page_not_found_message),
                            actionLabel = stringResource(Res.string.episode_page_go_to_show),
                            onAction = onOpenShow,
                        )
                    }

                    else -> {
                        EpisodePageContent(
                            meta = meta,
                            video = video,
                            isOffline = networkStatus.isNoInternet,
                            horizontalPadding = pageHorizontalPadding,
                            onOpenEpisode = onOpenEpisode,
                            onPlay = onPlay,
                            onPlayManually = onPlayManually,
                        )
                    }
                }
                if (loadedMeta == null && meta != null) SavedDetailsNotice(Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun seasonLabelFor(seasonNumber: Int): String =
    if (seasonNumber == SPECIALS_SEASON_NUMBER) {
        stringResource(Res.string.episodes_specials)
    } else {
        stringResource(Res.string.episodes_season, seasonNumber)
    }

@Composable
private fun episodeLabelFor(video: MetaVideo): String =
    video.episode
        ?.let { stringResource(Res.string.episode_page_episode_label, it) }
        ?: stringResource(Res.string.episode_page_episode_unnumbered)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpisodePageHeader(
    meta: MetaDetails?,
    video: MetaVideo?,
    horizontalPadding: Dp,
    topPadding: Dp,
    onBack: () -> Unit,
    onOpenShow: () -> Unit,
    onOpenSeason: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = horizontalPadding, end = horizontalPadding, top = topPadding, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NuvioBackButton(
            onClick = onBack,
            modifier = Modifier.semantics { role = Role.Button },
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            buttonSize = 48.dp,
            iconSize = 24.dp,
        )
        if (meta != null && video != null) {
            val trail = remember(meta, video) { meta.trailFor(video) }
            val seasonLabel = seasonLabelFor(trail.seasonNumber)
            val episodeLabel = episodeLabelFor(video)
            FlowRow(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                TrailLink(
                    text = trail.showName,
                    actionLabel = stringResource(Res.string.episode_page_trail_show, trail.showName),
                    onClick = onOpenShow,
                )
                TrailSeparator()
                TrailLink(
                    text = seasonLabel,
                    actionLabel = stringResource(Res.string.episode_page_trail_season, seasonLabel),
                    onClick = { onOpenSeason(trail.seasonNumber) },
                )
                TrailSeparator()
                val currentLabel = stringResource(Res.string.episode_page_trail_current, episodeLabel)
                // Centred in the same minimum height as the links, so every part sits on one line.
                Box(
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = episodeLabel,
                        modifier = Modifier.semantics {
                            heading()
                            contentDescription = currentLabel
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.nuvio.colors.textPrimary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrailLink(
    text: String,
    actionLabel: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(MaterialTheme.nuvio.shapes.chip)
            .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.nuvio.colors.textSecondary,
            textDecoration = TextDecoration.Underline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 320.dp),
        )
    }
}

@Composable
private fun TrailSeparator() {
    // Decorative: the three items already read as show, season and episode.
    Text(
        text = "›",
        modifier = Modifier.clearAndSetSemantics { },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.nuvio.colors.textMuted,
    )
}

@Composable
private fun EpisodePageMessage(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Button(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpisodePageContent(
    meta: MetaDetails,
    video: MetaVideo,
    isOffline: Boolean,
    horizontalPadding: Dp,
    onOpenEpisode: (MetaVideo) -> Unit,
    onPlay: ContentPlayAction,
    onPlayManually: ContentPlayAction,
) {
    val offlineIndex = rememberOfflineDownloadIndex()
    val playbackAvailability = rememberPlaybackAvailability()
    val watchedUiState by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()
    val watchProgressUiState by remember {
        WatchProgressRepository.ensureLoaded()
        WatchProgressRepository.uiState
    }.collectAsStateWithLifecycle()
    val playerSettings by remember {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val metaScreenSettings by remember {
        MetaScreenSettingsRepository.ensureLoaded()
        MetaScreenSettingsRepository.uiState
    }.collectAsStateWithLifecycle()

    val progressByVideoId = remember(watchProgressUiState.entries, meta.id) {
        watchProgressUiState.byVideoIdForContent(meta.id)
    }
    val isWatched = remember(meta, video, watchedUiState.watchedKeys, progressByVideoId) {
        isEpisodeWatchedForActions(meta, video, watchedUiState.watchedKeys, progressByVideoId)
    }
    val streamVideoId = remember(meta.id, video) {
        video.id.takeIf { it.isNotBlank() } ?: buildPlaybackVideoId(
            parentMetaId = meta.id,
            seasonNumber = video.season,
            episodeNumber = video.episode,
            fallbackVideoId = video.id,
        )
    }
    val savedProgress: WatchProgressEntry? = watchProgressUiState.progressForVideo(
        videoId = streamVideoId,
        parentMetaId = meta.id,
        seasonNumber = video.season,
        episodeNumber = video.episode,
    )?.takeUnless { it.isCompleted }
    val resumeMs = savedProgress?.lastPositionMs?.takeIf { it > 0L }
    val isDownloaded = offlineIndex.hasFor(
        parentMetaId = meta.id,
        seasonNumber = video.season,
        episodeNumber = video.episode,
        videoId = video.id,
    )
    val availability = video.availability(offline = isOffline, hasLocalDownload = isDownloaded)
    val playable = availability == EpisodeAvailability.Playable
    val canStream = playbackAvailability.canStream(meta.type, streamVideoId)
    val showStreamInstead = playable && isDownloaded && canStream
    val showManualPlay = playable && !isDownloaded && canStream && StreamAutoPlayPolicy.isEffectivelyEnabled(playerSettings)

    val startPlayback: (ContentPlayAction) -> Unit = { action ->
        action(
            meta.type,
            streamVideoId,
            meta.id,
            meta.type,
            meta.name,
            meta.logo,
            meta.poster,
            meta.background,
            video.season,
            video.episode,
            video.title,
            video.thumbnail,
            video.overview,
            savedProgress?.lastPositionMs,
        )
    }

    val previous = remember(meta, video) { meta.previousEpisode(video) }
    val next = remember(meta, video) { meta.nextEpisode(video) }
    val seasonEpisodes = remember(meta, video) { meta.episodesInSeasonOf(video) }
    val blurStill = metaScreenSettings.blurUnwatchedEpisodes && !isWatched
    val scrollState = rememberScrollState()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val twoPane = maxWidth >= EpisodePageTwoPaneMinWidth
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = horizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = EpisodePageMaxContentWidth).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                val still: @Composable (Modifier) -> Unit = { stillModifier ->
                    EpisodeStill(
                        imageUrl = video.thumbnail ?: meta.background ?: meta.poster,
                        blurred = blurStill,
                        progressFraction = savedProgress
                            ?.takeIf { it.durationMs > 0L }
                            ?.progressFraction,
                        modifier = stillModifier,
                    )
                }
                val details: @Composable (Modifier) -> Unit = { detailsModifier ->
                    Column(
                        modifier = detailsModifier,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        EpisodeSummary(
                            video = video,
                            isWatched = isWatched,
                            isAvailableOffline = isDownloaded,
                            showRating = metaScreenSettings.episodeRatingsVisibility.showRating(isWatched),
                            availability = availability,
                        )
                        EpisodeActions(
                            video = video,
                            availability = availability,
                            resumeMs = resumeMs,
                            showStreamInstead = showStreamInstead,
                            showManualPlay = showManualPlay,
                            isWatched = isWatched,
                            onPlay = { startPlayback(onPlay) },
                            onPlayManually = { startPlayback(onPlayManually) },
                            onToggleWatched = {
                                WatchingActions.toggleEpisodeWatched(
                                    meta = meta,
                                    episode = video,
                                    isCurrentlyWatched = isWatched,
                                )
                            },
                        )
                        EpisodeNeighbours(
                            previous = previous,
                            next = next,
                            onOpenEpisode = onOpenEpisode,
                        )
                    }
                }
                if (twoPane) {
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        still(Modifier.weight(0.45f))
                        details(Modifier.weight(0.55f))
                    }
                } else {
                    still(Modifier.fillMaxWidth())
                    details(Modifier.fillMaxWidth())
                }

                if (seasonEpisodes.size > 1) {
                    SeasonStrip(
                        meta = meta,
                        currentVideo = video,
                        episodes = seasonEpisodes,
                        watchedKeys = watchedUiState.watchedKeys,
                        progressByVideoId = progressByVideoId,
                        blurUnwatched = metaScreenSettings.blurUnwatchedEpisodes,
                        offlineIndex = offlineIndex,
                        isOffline = isOffline,
                        onOpenEpisode = onOpenEpisode,
                    )
                }
                Spacer(modifier = Modifier.size(nuvioSafeBottomPadding(32.dp)))
            }
        }
        NuvioDesktopVerticalScrollbar(
            state = scrollState,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(vertical = 8.dp, horizontal = 4.dp),
        )
    }
}

@Composable
private fun EpisodeStill(
    imageUrl: String?,
    blurred: Boolean,
    progressFraction: Float?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(MaterialTheme.nuvio.shapes.card)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (imageUrl != null) {
            NuvioAsyncImage(
                model = imageUrl,
                // The title is written out below, so the image adds nothing to a spoken page.
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (blurred) Modifier.blur(18.dp) else Modifier),
                contentScale = ContentScale.Crop,
            )
        }
        if (progressFraction != null) {
            NuvioProgressBar(
                progress = progressFraction,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                height = 5.dp,
                trackColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.14f),
                fillColor = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun EpisodeSummary(
    video: MetaVideo,
    isWatched: Boolean,
    isAvailableOffline: Boolean,
    showRating: Boolean,
    availability: EpisodeAvailability,
) {
    val title = localizedEpisodeTitle(video.title, video.episode) ?: video.title
    val code = localizedSeasonEpisodeCode(video.season, video.episode)
    val released = video.released?.takeIf { it.isNotBlank() }?.let(::formatReleaseDateForDisplay)
    val dateText = released?.let {
        if (availability == EpisodeAvailability.NotReleased) stringResource(Res.string.cw_airs_date, it) else it
    }
    val runtimeText = video.runtime?.takeIf { it > 0 }?.let(::formatRuntimeFromMinutes)
    val ratingText = video.rating
        ?.takeIf { it > 0.0 && showRating }
        ?.let { stringResource(Res.string.episode_page_rating, formatRating(it)) }
    val watchedText = if (isWatched) stringResource(Res.string.episodes_cd_watched) else null
    val metaLine = listOfNotNull(code, dateText, runtimeText, ratingText, watchedText)
        .joinToString(separator = "  •  ")

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.nuvio.colors.textPrimary,
        )
        if (metaLine.isNotEmpty()) {
            Text(
                text = metaLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.nuvio.colors.textSecondary,
            )
        }
        if (isAvailableOffline) {
            AvailableOfflineBadge()
        }
        Text(
            text = video.overview?.takeIf { it.isNotBlank() }
                ?: stringResource(Res.string.episode_page_overview_missing),
            style = MaterialTheme.typography.bodyLarge,
            color = if (video.overview.isNullOrBlank()) {
                MaterialTheme.nuvio.colors.textMuted
            } else {
                MaterialTheme.nuvio.colors.textPrimary
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpisodeActions(
    video: MetaVideo,
    availability: EpisodeAvailability,
    resumeMs: Long?,
    showStreamInstead: Boolean,
    showManualPlay: Boolean,
    isWatched: Boolean,
    onPlay: () -> Unit,
    onPlayManually: () -> Unit,
    onToggleWatched: () -> Unit,
) {
    val playable = availability == EpisodeAvailability.Playable
    val playLabel = when {
        availability == EpisodeAvailability.NotReleased -> {
            val date = video.released?.takeIf { it.isNotBlank() }?.let(::formatReleaseDateForDisplay)
            if (date != null) {
                stringResource(Res.string.episode_page_play_not_released_date, date)
            } else {
                stringResource(Res.string.episode_page_play_not_released)
            }
        }
        availability == EpisodeAvailability.Unavailable -> stringResource(Res.string.episode_page_play_unavailable)
        resumeMs != null -> stringResource(Res.string.streams_resume_from_time, formatPlaybackTime(resumeMs))
        else -> stringResource(Res.string.action_play)
    }
    val watchedStateText = stringResource(Res.string.episodes_cd_watched)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NuvioPrimaryButton(
            text = playLabel,
            modifier = Modifier.widthIn(max = 420.dp),
            enabled = playable,
            onClick = onPlay,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showStreamInstead) {
                OutlinedButton(
                    onClick = onPlayManually,
                    modifier = Modifier.heightIn(min = EpisodePageButtonMinHeight),
                    shape = MaterialTheme.nuvio.shapes.button,
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(Res.string.stream_instead))
                }
            } else if (showManualPlay) {
                OutlinedButton(
                    onClick = onPlayManually,
                    modifier = Modifier.heightIn(min = EpisodePageButtonMinHeight),
                    shape = MaterialTheme.nuvio.shapes.button,
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(Res.string.play_manually))
                }
            }
            OutlinedButton(
                onClick = onToggleWatched,
                modifier = Modifier
                    .heightIn(min = EpisodePageButtonMinHeight)
                    .semantics { if (isWatched) stateDescription = watchedStateText },
                shape = MaterialTheme.nuvio.shapes.button,
            ) {
                Icon(
                    imageVector = if (isWatched) Icons.Default.CheckCircle else Icons.Default.CheckCircleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (isWatched) Res.string.episode_mark_unwatched else Res.string.episode_mark_watched,
                    ),
                )
            }
        }
    }
}

@Composable
private fun EpisodeNeighbours(
    previous: MetaVideo?,
    next: MetaVideo?,
    onOpenEpisode: (MetaVideo) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NeighbourButton(
            title = stringResource(Res.string.episode_page_previous),
            target = previous,
            emptyText = stringResource(Res.string.episode_page_no_previous),
            isPrevious = true,
            onClick = { previous?.let(onOpenEpisode) },
            modifier = Modifier.weight(1f),
        )
        NeighbourButton(
            title = stringResource(Res.string.episode_page_next),
            target = next,
            emptyText = stringResource(Res.string.episode_page_no_next),
            isPrevious = false,
            onClick = { next?.let(onOpenEpisode) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun NeighbourButton(
    title: String,
    target: MetaVideo?,
    emptyText: String,
    isPrevious: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The target is written in the button, so its spoken label names the episode it opens.
    val targetText = target?.let { video ->
        listOfNotNull(
            localizedSeasonEpisodeCode(video.season, video.episode),
            localizedEpisodeTitle(video.title, video.episode) ?: video.title,
        ).joinToString(separator = " • ")
    } ?: emptyText
    OutlinedButton(
        onClick = onClick,
        enabled = target != null,
        modifier = modifier.heightIn(min = 64.dp),
        shape = MaterialTheme.nuvio.shapes.button,
    ) {
        if (isPrevious) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        Column(
            modifier = Modifier.weight(1f, fill = false),
            horizontalAlignment = if (isPrevious) Alignment.Start else Alignment.End,
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                text = targetText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!isPrevious) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SeasonStrip(
    meta: MetaDetails,
    currentVideo: MetaVideo,
    episodes: List<MetaVideo>,
    watchedKeys: Set<String>,
    progressByVideoId: Map<String, WatchProgressEntry>,
    blurUnwatched: Boolean,
    offlineIndex: OfflineDownloadIndex,
    isOffline: Boolean,
    onOpenEpisode: (MetaVideo) -> Unit,
) {
    val seasonLabel = seasonLabelFor(normalizeSeasonNumber(currentVideo.season))
    val currentIndex = episodes.indexOfFirst { it.id == currentVideo.id }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(Res.string.episode_page_more_from, seasonLabel),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.nuvio.colors.textPrimary,
        )
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(episodes, key = { index, item -> "${item.id}#$index" }) { _, item ->
                val watched = isEpisodeWatchedForActions(meta, item, watchedKeys, progressByVideoId)
                val availableOffline = offlineIndex.hasFor(
                    parentMetaId = meta.id,
                    seasonNumber = item.season,
                    episodeNumber = item.episode,
                    videoId = item.id,
                )
                SeasonStripItem(
                    video = item,
                    imageUrl = item.thumbnail ?: meta.background ?: meta.poster,
                    isCurrent = item.id == currentVideo.id,
                    isWatched = watched,
                    isAvailableOffline = availableOffline,
                    isOffline = isOffline,
                    blurred = blurUnwatched && !watched,
                    onClick = { onOpenEpisode(item) },
                )
            }
        }
    }
}

@Composable
private fun SeasonStripItem(
    video: MetaVideo,
    imageUrl: String?,
    isCurrent: Boolean,
    isWatched: Boolean,
    isAvailableOffline: Boolean,
    isOffline: Boolean,
    blurred: Boolean,
    onClick: () -> Unit,
) {
    val shape = MaterialTheme.nuvio.shapes.card
    val title = localizedEpisodeTitle(video.title, video.episode) ?: video.title
    val code = localizedSeasonEpisodeCode(video.season, video.episode)
    val currentText = stringResource(Res.string.episode_page_current_marker)
    val watchedText = stringResource(Res.string.episodes_cd_watched)
    val notReleasedText = stringResource(Res.string.episode_page_play_not_released)
    val unavailableText = stringResource(Res.string.episodes_unavailable)
    val availability = video.availability(offline = isOffline, hasLocalDownload = isAvailableOffline)
    val status = listOfNotNull(
        currentText.takeIf { isCurrent },
        watchedText.takeIf { isWatched },
        notReleasedText.takeIf { availability == EpisodeAvailability.NotReleased },
        unavailableText.takeIf { availability == EpisodeAvailability.Unavailable },
    ).joinToString(separator = "  •  ")
    val openLabel = stringResource(Res.string.episode_page_open_episode, title)
    val colors = MaterialTheme.nuvio.colors
    val cardAlpha = if (availability == EpisodeAvailability.Unavailable) 0.52f else 1f

    Column(
        modifier = Modifier
            .width(240.dp)
            .graphicsLayer { alpha = cardAlpha }
            .clip(shape)
            .border(
                width = if (isCurrent) 2.dp else 1.dp,
                color = if (isCurrent) colors.borderSelected else colors.borderSubtle,
                shape = shape,
            )
            .clickable(role = Role.Button, onClickLabel = openLabel, onClick = onClick)
            .semantics { selected = isCurrent },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            if (imageUrl != null) {
                NuvioAsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (blurred) Modifier.blur(14.dp) else Modifier),
                    contentScale = ContentScale.Crop,
                )
            }
            if (isAvailableOffline) {
                AvailableOfflineBadge(
                    compact = true,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                )
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val headline = listOfNotNull(code, status.takeIf { it.isNotEmpty() }).joinToString("  •  ")
            if (headline.isNotEmpty()) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isCurrent) colors.textPrimary else colors.textSecondary,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
