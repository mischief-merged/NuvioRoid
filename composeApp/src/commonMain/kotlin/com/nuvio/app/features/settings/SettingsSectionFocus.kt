package com.nuvio.app.features.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A section the next visit to its settings page should scroll to and mark.
 * Set just before opening the page. The page consumes it once.
 */
internal object SettingsSectionFocus {
    const val PlaybackDownloads = "playback-downloads"

    var pending by mutableStateOf<String?>(null)
        private set

    fun request(section: String) {
        pending = section
    }

    /** True only the first time [section] is the one waiting. */
    fun consume(section: String): Boolean {
        if (pending != section) return false
        pending = null
        return true
    }

    fun clear() {
        pending = null
    }
}
