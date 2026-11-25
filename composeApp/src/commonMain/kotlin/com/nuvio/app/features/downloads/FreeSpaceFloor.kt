package com.nuvio.app.features.downloads

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun normalizeFloorLocationId(locationId: String?): String =
    locationId?.trim()?.takeIf { it.isNotEmpty() } ?: DefaultFloorLocationId

internal const val DefaultFloorLocationId = "app"

internal data class FreeSpaceFloorState(
    val floorGb: Int? = null,
    val locationFreeBytes: Map<String, Long?> = emptyMap(),
) {
    val floorBytes: Long?
        get() = floorGb?.takeIf { it > 0 }?.times(BytesPerGigabyte)

    fun isLocationBelowFloor(locationId: String?): Boolean {
        val floor = floorBytes ?: return false
        val free = locationFreeBytes[normalizeFloorLocationId(locationId)] ?: return false
        return free < floor
    }

    /**
     * True when a location held by the free-space floor may resume: either the floor is off,
     * or the location's free space is known and at least [floorBytes].
     * Unknown free space (such as an unplugged drive) never clears a free-space hold.
     */
    fun canResumeLocation(locationId: String?): Boolean {
        val floor = floorBytes ?: return true
        val free = locationFreeBytes[normalizeFloorLocationId(locationId)] ?: return false
        return free >= floor
    }
}

/**
 * Per-location free-space floor. Measured off-lock by the folder monitor and read in memory
 * by [DownloadsRepository], so holding or resuming transfers never touches the disk under locks.
 */
internal class FreeSpaceHold(
    private val loadFloorGb: () -> Int? = { null },
    private val saveFloorGb: (Int?) -> Unit = {},
) {
    private val lock = SynchronizedObject()
    private var loaded = false
    private var floorGb: Int? = null
    private var locationFreeBytes: Map<String, Long?> = emptyMap()
    private var listener: (() -> Unit)? = null

    private val _state = MutableStateFlow(FreeSpaceFloorState())
    val state: StateFlow<FreeSpaceFloorState>
        get() {
            ensureLoaded()
            return _state.asStateFlow()
        }

    val floorBytes: Long?
        get() {
            ensureLoaded()
            return _state.value.floorBytes
        }

    fun isLocationBelowFloor(locationId: String?): Boolean {
        ensureLoaded()
        return _state.value.isLocationBelowFloor(locationId)
    }

    fun canResumeLocation(locationId: String?): Boolean {
        ensureLoaded()
        return _state.value.canResumeLocation(locationId)
    }

    fun listen(onChanged: () -> Unit) {
        synchronized(lock) {
            ensureLoadedLocked()
            listener = onChanged
        }
    }

    fun setFloorGb(value: Int?, notifyInBackground: Boolean = false) {
        val normalized = value?.takeIf { it in optionsGb }
        val work = synchronized(lock) {
            ensureLoadedLocked()
            val before = _state.value
            val changedSetting = floorGb != normalized
            floorGb = normalized
            val next = FreeSpaceFloorState(floorGb = floorGb, locationFreeBytes = locationFreeBytes)
            _state.value = next
            val notify = listener.takeIf { next != before }
            val runEffects: () -> Unit = {
                if (changedSetting) runCatching { saveFloorGb(normalized) }
                notify?.invoke()
            }
            runEffects
        }
        if (notifyInBackground) ConnectionUpdates.submit(work) else work()
    }

    fun reportLocationFreeBytes(freeByLocation: Map<String, Long?>, notifyInBackground: Boolean = false) {
        val normalizedMap = freeByLocation.entries.associate { (k, v) -> normalizeFloorLocationId(k) to v }
        val notify = synchronized(lock) {
            ensureLoadedLocked()
            val before = _state.value
            locationFreeBytes = normalizedMap
            val next = FreeSpaceFloorState(floorGb = floorGb, locationFreeBytes = locationFreeBytes)
            _state.value = next
            listener.takeIf { next != before }
        } ?: return
        if (notifyInBackground) ConnectionUpdates.submit(notify) else notify()
    }

    private fun ensureLoaded() {
        synchronized(lock) { ensureLoadedLocked() }
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val stored = runCatching { loadFloorGb() }.getOrNull()
        floorGb = stored?.takeIf { it in optionsGb }
        _state.value = FreeSpaceFloorState(floorGb = floorGb, locationFreeBytes = locationFreeBytes)
    }

    companion object {
        val optionsGb: List<Int?> = listOf(null, 5, 10, 20, 50, 100)
    }
}

internal object FreeSpaceFloors {
    val hold: FreeSpaceHold = FreeSpaceHold(
        loadFloorGb = LimitedConnectionStorage::loadFreeSpaceFloorGb,
        saveFloorGb = LimitedConnectionStorage::saveFreeSpaceFloorGb,
    )
}
