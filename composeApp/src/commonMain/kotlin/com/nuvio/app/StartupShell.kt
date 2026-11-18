package com.nuvio.app

internal enum class OfflineLaunch {
    Wait,
    StayHere,
    OpenDownloads,
}

/**
 * The window shows a shell as soon as the tabs exist. Waiting for the first catalog
 * used to hide that shell, including when the Mac is already offline.
 */
internal fun startupContentAlpha(catalogReady: Boolean): Float = if (catalogReady) 1f else 1f

internal fun offlineLaunch(
    alreadyHandled: Boolean,
    networkKnown: Boolean,
    offline: Boolean,
    downloadsEnabled: Boolean,
    hasPlayableDownload: Boolean,
    downloadsListReady: Boolean = true,
): OfflineLaunch {
    if (alreadyHandled || !networkKnown) return OfflineLaunch.Wait
    if (!offline) return OfflineLaunch.StayHere
    // Offline with downloads on: the list is read off the interface thread, so wait for it.
    if (downloadsEnabled && !downloadsListReady) return OfflineLaunch.Wait
    return if (downloadsEnabled && hasPlayableDownload) {
        OfflineLaunch.OpenDownloads
    } else {
        OfflineLaunch.StayHere
    }
}

/** Offline launch should reveal the shell immediately instead of waiting for a catalog. */
internal fun revealShellForOffline(offline: Boolean): Boolean = offline
