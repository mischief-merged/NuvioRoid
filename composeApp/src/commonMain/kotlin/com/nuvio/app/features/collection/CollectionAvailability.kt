package com.nuvio.app.features.collection

internal enum class CollectionBodyKind {
    Loading,
    Empty,
    Failed,
    Partial,
    Ready,
}

internal fun collectionBodyKind(
    loading: Boolean,
    sourceCount: Int,
    sourcesWithItems: Int,
    failedSources: Int,
): CollectionBodyKind {
    if (sourceCount == 0) {
        return if (loading) CollectionBodyKind.Loading else CollectionBodyKind.Empty
    }
    if (loading && sourcesWithItems == 0) return CollectionBodyKind.Loading
    if (sourcesWithItems == 0 && failedSources > 0) return CollectionBodyKind.Failed
    if (sourcesWithItems == 0) return CollectionBodyKind.Empty
    if (failedSources > 0) return CollectionBodyKind.Partial
    return CollectionBodyKind.Ready
}

internal fun folderTabAfterLoadFailure(
    tab: FolderTab,
    reset: Boolean,
    message: String,
): FolderTab = tab.copy(
    isLoading = false,
    isLoadingMore = false,
    items = if (reset) emptyList() else tab.items,
    nextSkip = if (reset) null else tab.nextSkip,
    error = message,
)

internal fun folderBodyKind(state: FolderDetailUiState): CollectionBodyKind {
    if (state.folder == null) {
        return if (state.isLoading) CollectionBodyKind.Loading else CollectionBodyKind.Empty
    }
    val tab = state.selectedTab
        ?: return if (state.isLoading) CollectionBodyKind.Loading else CollectionBodyKind.Empty
    if (tab.isAllTab) return folderRowsBodyKind(state)
    return collectionBodyKind(
        loading = tab.isLoading,
        sourceCount = 1,
        sourcesWithItems = if (tab.items.isNotEmpty()) 1 else 0,
        failedSources = if (tab.error != null) 1 else 0,
    )
}

/** Row layout shows every source at once, so the body follows all of them. */
internal fun folderRowsBodyKind(state: FolderDetailUiState): CollectionBodyKind {
    val sources = state.tabs.filter { !it.isAllTab }
    return collectionBodyKind(
        loading = state.isLoading || sources.any { it.isLoading },
        sourceCount = sources.size,
        sourcesWithItems = sources.count { it.items.isNotEmpty() },
        failedSources = sources.count { it.error != null },
    )
}

/** Retry the open source when it failed. Otherwise retry every source that failed. */
internal fun folderRetryTargets(tabs: List<FolderTab>, selectedIndex: Int): List<Int> {
    val selected = tabs.getOrNull(selectedIndex)
    if (selected != null && !selected.isAllTab && selected.error != null) return listOf(selectedIndex)
    return tabs.mapIndexedNotNull { index, tab ->
        index.takeIf { !tab.isAllTab && tab.error != null }
    }
}

/** A later page keeps its cursor. A first page, or a page with nowhere to resume, starts over. */
internal fun folderRetryUsesReset(tab: FolderTab): Boolean = tab.items.isEmpty() || tab.nextSkip == null

internal sealed class FolderSourceRetry {
    data object LoadPage : FolderSourceRetry()
    data class AttachThenLoad(
        val manifestUrl: String,
        val supportsPagination: Boolean,
    ) : FolderSourceRetry()
    data class StillMissing(val message: String) : FolderSourceRetry()
}

/**
 * A catalog source with no address is looked up again. If it is still missing, retry
 * keeps that message. A direct list, or a source that already has an address, loads the page.
 */
internal fun folderSourceRetry(
    tab: FolderTab,
    resolvedManifestUrl: String?,
    resolvedSupportsPagination: Boolean,
    missingMessage: String,
): FolderSourceRetry {
    val direct = tab.source?.isTmdb == true || tab.source?.isTrakt == true
    if (direct || !tab.manifestUrl.isNullOrBlank()) return FolderSourceRetry.LoadPage
    val manifest = resolvedManifestUrl?.trim()?.takeIf { it.isNotEmpty() }
    if (manifest != null) return FolderSourceRetry.AttachThenLoad(manifest, resolvedSupportsPagination)
    val message = missingMessage.trim().ifEmpty { tab.error?.trim().orEmpty() }
    return FolderSourceRetry.StillMissing(message)
}

/** The open list uses its own message. The combined list and the row layout use every source. */
internal fun collectionFailureErrors(
    tabs: List<FolderTab>,
    selectedIndex: Int,
    rows: Boolean,
): List<String?> {
    val selected = tabs.getOrNull(selectedIndex)
    if (!rows && selected != null && !selected.isAllTab) return listOf(selected.error)
    return tabs.filter { !it.isAllTab }.map { it.error }
}

/** A missing-source sentence stays. A connection sentence is the fallback when nothing more specific was stored. */
internal fun collectionFailureMessage(errors: List<String?>, connectionFallback: String): String {
    val present = errors.mapNotNull { it?.trim()?.takeIf { text -> text.isNotEmpty() } }
    return present.firstOrNull { it != connectionFallback } ?: present.firstOrNull() ?: connectionFallback
}
