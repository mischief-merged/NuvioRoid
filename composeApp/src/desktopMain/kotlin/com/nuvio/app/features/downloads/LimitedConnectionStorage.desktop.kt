package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage

internal actual object LimitedConnectionStorage {
    private val store by lazy { DesktopStorage.store("nuvio_limited_connections") }

    actual fun loadTravel(): String? = store.getString("travel")
    actual fun saveTravel(value: String) { store.putString("travel", value) }

    actual fun loadPauseOnLimited(): Boolean? =
        store.getString("pause_on_limited")?.toBooleanStrictOrNull()

    actual fun savePauseOnLimited(value: Boolean) {
        store.putString("pause_on_limited", value.toString())
    }

    actual fun loadPolite(): Boolean? = store.getString("let_others_first")?.toBooleanStrictOrNull()

    actual fun savePolite(value: Boolean) {
        store.putString("let_others_first", value.toString())
    }
}
