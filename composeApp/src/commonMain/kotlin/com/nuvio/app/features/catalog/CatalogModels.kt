package com.nuvio.app.features.catalog

import com.nuvio.app.features.home.MetaPreview

data class CatalogUiState(
    val items: List<MetaPreview> = emptyList(),
    val isLoading: Boolean = false,
    val nextSkip: Int? = null,
    val consecutiveDuplicatePages: Int = 0,
    val errorMessage: String? = null,
    val loadMoreFailed: Boolean = false,
) {
    val canLoadMore: Boolean
        get() = nextSkip != null && !loadMoreFailed
}

data class CatalogScrollPosition(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
)
