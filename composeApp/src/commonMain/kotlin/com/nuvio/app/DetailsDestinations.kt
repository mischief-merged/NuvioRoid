package com.nuvio.app

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.nuvio.app.features.details.EpisodeDetailsScreen
import com.nuvio.app.features.details.MetaDetailsScreen
import com.nuvio.app.features.details.PersonDetailScreen
import com.nuvio.app.features.details.TmdbEntityBrowseScreen
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tmdb.TmdbEntityKind
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.navigation.DetailRoute
import com.nuvio.app.navigation.EntityBrowseRoute
import com.nuvio.app.navigation.EpisodeDetailRoute
import com.nuvio.app.navigation.NuvioNavigator
import com.nuvio.app.navigation.PersonDetailRoute
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.person_role_creator
import nuvio.composeapp.generated.resources.person_role_director
import nuvio.composeapp.generated.resources.person_role_writer
import org.jetbrains.compose.resources.stringResource

internal typealias ContentPlayAction = (
    type: String,
    videoId: String,
    parentMetaId: String,
    parentMetaType: String,
    title: String,
    logo: String?,
    poster: String?,
    background: String?,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    episodeThumbnail: String?,
    pauseDescription: String?,
    resumePositionMs: Long?,
) -> Unit

@Composable
internal fun rememberOpenMeta(navController: NuvioNavigator): (MetaPreview) -> Unit {
    val scope = rememberCoroutineScope()
    return { preview ->
        scope.launch {
            val resolvedId = if (preview.id.startsWith("tmdb:")) {
                val tmdbId = preview.id.removePrefix("tmdb:").toIntOrNull()
                val resolved = tmdbId?.let {
                    TmdbService.tmdbToImdb(
                        tmdbId = it,
                        mediaType = preview.type,
                    )
                } ?: preview.id
                resolved
            } else {
                preview.id
            }
            navController.openPreview(preview.copy(id = resolvedId))
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun DetailsDestination(
    route: DetailRoute,
    navController: NuvioNavigator,
    onPlay: ContentPlayAction,
    onPlayManually: ContentPlayAction,
    onRequestInAppTrailer: ((contentTitle: String, trailerTitle: String, onProceed: () -> Unit) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    val onOpenMeta = rememberOpenMeta(navController)
    val directorRole = stringResource(Res.string.person_role_director)
    val writerRole = stringResource(Res.string.person_role_writer)
    val creatorRole = stringResource(Res.string.person_role_creator)
    MetaDetailsScreen(
        type = route.type,
        id = route.id,
        onBack = onBack,
        onPlay = onPlay,
        onPlayManually = onPlayManually,
        onRequestInAppTrailer = onRequestInAppTrailer,
        onOpenMeta = onOpenMeta,
        onOpenEpisode = { video ->
            navController.navigate(
                EpisodeDetailRoute(
                    type = route.type,
                    id = route.id,
                    videoId = video.id,
                    season = video.season,
                    episode = video.episode,
                    title = route.title,
                ),
            )
        },
        initialSeason = route.season,
        focusVideoId = route.focusVideoId,
        onCastClick = { person, avatarTransitionKey ->
            val tmdbId = person.tmdbId
            if (tmdbId != null && tmdbId > 0) {
                navController.navigate(
                    PersonDetailRoute(
                        personId = tmdbId,
                        personName = person.name,
                        personPhoto = person.photo,
                        castAvatarTransitionKey = avatarTransitionKey,
                        preferCrew = person.role?.let {
                            it.equals("Director", ignoreCase = true) ||
                                it.equals(directorRole, ignoreCase = true) ||
                                it.equals("Writer", ignoreCase = true) ||
                                it.equals(writerRole, ignoreCase = true) ||
                                it.equals("Creator", ignoreCase = true) ||
                                it.equals(creatorRole, ignoreCase = true)
                        } ?: false,
                    ),
                )
            }
        },
        onCompanyClick = { company, entityKind ->
            val tmdbId = company.tmdbId
            if (tmdbId != null && tmdbId > 0) {
                navController.navigate(
                    EntityBrowseRoute(
                        entityKind = entityKind,
                        entityId = tmdbId,
                        entityName = company.name,
                        sourceType = route.type,
                    ),
                )
            }
        },
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Opens the show page, dropping the episode pages of the same show above it so that Back from the
 * show does not walk through the episodes again. A show page of the same show right below is reused.
 */
internal fun NuvioNavigator.openShowPage(
    type: String,
    id: String,
    title: String?,
    season: Int? = null,
) {
    var pops = 0
    while (pops < 64 && routes.size > 1) {
        val top = currentRoute
        if (top !is EpisodeDetailRoute || top.type != type || top.id != id) break
        popBackStack()
        pops++
    }
    val top = currentRoute
    if (top is DetailRoute && top.type == type && top.id == id) {
        if (top.season == season && top.focusVideoId == null) return
        popBackStack(expectedRoute = top)
    }
    navigate(DetailRoute(type = type, id = id, title = title, season = season))
}

internal fun NuvioNavigator.openShowFromEpisode(
    route: EpisodeDetailRoute,
    showTitle: String?,
    season: Int? = null,
) = openShowPage(route.type, route.id, showTitle ?: route.title, season)

/** Opens an episode page in place of an episode page of the same show on top, or on top of anything else. */
internal fun NuvioNavigator.openEpisodePage(route: EpisodeDetailRoute) {
    val top = currentRoute
    if (top is EpisodeDetailRoute && top.type == route.type && top.id == route.id) {
        replaceWithEpisode(route)
    } else {
        navigate(route)
    }
}

/** Opens another episode page in place of the current one, so pages do not pile up. */
internal fun NuvioNavigator.replaceWithEpisode(route: EpisodeDetailRoute) {
    if (currentRoute == route) return
    navigate(route) { popUpTo<EpisodeDetailRoute> { inclusive = true } }
}

@Composable
internal fun EpisodeDestination(
    route: EpisodeDetailRoute,
    navController: NuvioNavigator,
    onPlay: ContentPlayAction,
    onPlayManually: ContentPlayAction,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    EpisodeDetailsScreen(
        type = route.type,
        id = route.id,
        videoId = route.videoId,
        season = route.season,
        episode = route.episode,
        onBack = onBack,
        onOpenShow = { navController.openShowFromEpisode(route, route.title) },
        onOpenSeason = { seasonNumber ->
            navController.openShowFromEpisode(route, route.title, season = seasonNumber)
        },
        onOpenEpisode = { video ->
            navController.replaceWithEpisode(
                EpisodeDetailRoute(
                    type = route.type,
                    id = route.id,
                    videoId = video.id,
                    season = video.season,
                    episode = video.episode,
                    title = route.title,
                ),
            )
        },
        onPlay = onPlay,
        onPlayManually = onPlayManually,
        modifier = Modifier.fillMaxSize(),
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun PersonDestination(
    route: PersonDetailRoute,
    navController: NuvioNavigator,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    PersonDetailScreen(
        personId = route.personId,
        personName = route.personName,
        initialProfilePhoto = route.personPhoto,
        avatarTransitionKey = route.castAvatarTransitionKey,
        preferCrew = route.preferCrew,
        onBack = onBack,
        onOpenMeta = rememberOpenMeta(navController),
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
internal fun EntityDestination(
    route: EntityBrowseRoute,
    navController: NuvioNavigator,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    TmdbEntityBrowseScreen(
        entityKind = TmdbEntityKind.fromRouteValue(route.entityKind),
        entityId = route.entityId,
        entityName = route.entityName,
        sourceType = route.sourceType,
        onBack = onBack,
        onOpenMeta = rememberOpenMeta(navController),
        modifier = Modifier.fillMaxSize(),
    )
}
