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

/**
 * Decides what an offline or online launch does about Downloads.
 *
 * [hasPlayableDownload] looks at files and can wait on the downloads list, so it is only
 * called once every other condition says the answer matters: the network is known, the
 * device is offline, downloads are on, and the list has been read. It is never called
 * while the list is still loading.
 */
internal fun offlineLaunch(
    alreadyHandled: Boolean,
    networkKnown: Boolean,
    offline: Boolean,
    downloadsEnabled: Boolean,
    hasPlayableDownload: () -> Boolean,
    downloadsListReady: Boolean = true,
): OfflineLaunch {
    if (alreadyHandled || !networkKnown) return OfflineLaunch.Wait
    if (!offline) return OfflineLaunch.StayHere
    if (!downloadsEnabled) return OfflineLaunch.StayHere
    // The list is read off the interface thread, so wait for it before looking at any file.
    if (!downloadsListReady) return OfflineLaunch.Wait
    return if (hasPlayableDownload()) OfflineLaunch.OpenDownloads else OfflineLaunch.StayHere
}

/** Offline launch should reveal the shell immediately instead of waiting for a catalog. */
internal fun revealShellForOffline(offline: Boolean): Boolean = offline
