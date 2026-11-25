package com.nuvio.app.features.downloads

internal actual object LimitedConnectionStorage {
    actual fun loadTravel(): String? = null
    actual fun saveTravel(value: String) = Unit

    actual fun loadPauseOnLimited(): Boolean? = null
    actual fun savePauseOnLimited(value: Boolean) = Unit
    actual fun loadPolite(): Boolean? = null
    actual fun savePolite(value: Boolean) = Unit
    actual fun loadFreeSpaceFloorGb(profileId: Int): Int? = null
    actual fun saveFreeSpaceFloorGb(profileId: Int, value: Int?) = Unit
}
