package com.nuvio.app.features.downloads

internal actual object LimitedConnectionStorage {
    actual fun loadPauseOnLimited(): Boolean? = null
    actual fun savePauseOnLimited(value: Boolean) = Unit
    actual fun loadPolite(): Boolean? = null
    actual fun savePolite(value: Boolean) = Unit
}
