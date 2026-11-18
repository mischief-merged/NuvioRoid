package com.nuvio.app.features.catalog

/**
 * A later page that fails keeps the cursor and the titles already on screen.
 * Clearing the cursor makes the catalog look finished.
 */
internal fun catalogStateAfterPageFailure(
    beforeRequest: CatalogUiState,
    reset: Boolean,
    message: String,
): CatalogUiState {
    val keepPage = !reset && beforeRequest.items.isNotEmpty() && beforeRequest.nextSkip != null
    return if (keepPage) {
        beforeRequest.copy(
            isLoading = false,
            loadMoreFailed = true,
            errorMessage = message,
        )
    } else {
        beforeRequest.copy(
            items = if (reset) emptyList() else beforeRequest.items,
            isLoading = false,
            nextSkip = null,
            loadMoreFailed = false,
            errorMessage = message,
        )
    }
}

internal fun catalogShowsLoadMoreRetry(state: CatalogUiState): Boolean =
    state.items.isNotEmpty() && state.loadMoreFailed && state.nextSkip != null && !state.isLoading
