package com.nuvio.app.core.memory

internal const val BrowseWindowSize = 2000

/** Keep recent browse pages. Stable item keys preserve the visible anchor as older pages leave. */
internal fun <T> retainBrowseWindow(items: List<T>, maximum: Int = BrowseWindowSize): List<T> {
    require(maximum > 0)
    return if (items.size <= maximum) items else items.takeLast(maximum)
}

internal fun <K, V> rememberRecentPage(pages: Map<K, V>, key: K, value: V, maximum: Int = 8): Map<K, V> {
    require(maximum > 0)
    return (pages.filterKeys { it != key } + (key to value)).entries.toList().takeLast(maximum).associate { it.toPair() }
}
