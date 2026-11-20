package com.nuvio.app.features.search

internal enum class SearchFieldKey {
    Escape,
    Tab,
    Other,
}

/** Escape and Tab leave the search field. Other keys keep it. */
internal fun searchFieldReleasesFocus(key: SearchFieldKey): Boolean =
    key == SearchFieldKey.Escape || key == SearchFieldKey.Tab

/** Escape is handled here. Tab is left for the next control. */
internal fun searchFieldConsumesKey(key: SearchFieldKey): Boolean = key == SearchFieldKey.Escape
