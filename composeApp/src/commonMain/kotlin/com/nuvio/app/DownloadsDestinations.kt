package com.nuvio.app

import androidx.compose.runtime.Composable
import com.nuvio.app.features.downloads.DownloadEpisodeLinks
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadsScreen
import com.nuvio.app.navigation.DetailRoute
import com.nuvio.app.navigation.DownloadShowRoute
import com.nuvio.app.navigation.DownloadsRoute
import com.nuvio.app.navigation.EpisodeDetailRoute
import com.nuvio.app.navigation.NuvioNavigator

@Composable
internal fun DownloadsDestination(
    route: DownloadsRoute,
    navController: NuvioNavigator,
    useNativeNavigation: Boolean,
    onOpenDownload: (DownloadItem) -> Unit,
    onChooseAnotherSource: ((DownloadItem) -> Unit)? = null,
    onBrowse: (() -> Unit)? = null,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    DownloadsScreen(
        onBack = onBack,
        onOpenDownload = onOpenDownload,
        onChooseAnotherSource = onChooseAnotherSource,
        onBrowse = onBrowse,
        onOpenShowDetails = { type, id, title -> navController.navigate(DetailRoute(type = type, id = id, title = title)) },
        episodeLinks = if (isDesktop) downloadEpisodeLinks(navController) else null,
        onNavigateToShow = if (useNativeNavigation) {
            { showId, title -> navController.navigate(DownloadShowRoute(showId, title)) }
        } else {
            null
        },
    )
}

@Composable
internal fun DownloadShowDestination(
    route: DownloadShowRoute,
    navController: NuvioNavigator,
    onOpenDownload: (DownloadItem) -> Unit,
    onChooseAnotherSource: ((DownloadItem) -> Unit)? = null,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    DownloadsScreen(
        onBack = onBack,
        onOpenDownload = onOpenDownload,
        onChooseAnotherSource = onChooseAnotherSource,
        initialShowId = route.showId,
        onBackFromShow = onBack,
    )
}

private fun downloadEpisodeLinks(navController: NuvioNavigator) = DownloadEpisodeLinks(
    onEpisodeDetails = { item ->
        navController.navigate(
            EpisodeDetailRoute(
                type = item.parentMetaType,
                id = item.parentMetaId,
                videoId = item.videoId,
                season = item.seasonNumber,
                episode = item.episodeNumber,
                title = item.title,
            ),
        )
    },
    onGoToSeason = { item ->
        navController.navigate(
            DetailRoute(
                type = item.parentMetaType,
                id = item.parentMetaId,
                title = item.title,
                season = item.seasonNumber,
            ),
        )
    },
    onGoToShow = { item ->
        navController.navigate(
            DetailRoute(type = item.parentMetaType, id = item.parentMetaId, title = item.title),
        )
    },
)
