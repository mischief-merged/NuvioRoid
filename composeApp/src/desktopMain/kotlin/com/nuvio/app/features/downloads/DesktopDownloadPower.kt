package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.desktop.NativePlayerBridge

internal data class DownloadPowerState(val onBattery: Boolean, val percent: Int?)

internal fun shouldHoldDownloadSleepAssertion(transfers: Int, power: DownloadPowerState?): Boolean =
    transfers > 0 && !(power?.onBattery == true && power.percent != null && power.percent < 20)

/** Providers keep the policy testable without changing the computer's power state. */
internal class DownloadSleepGuard(
    private val power: () -> DownloadPowerState?,
    private val setHeld: (Boolean) -> Unit,
) : AutoCloseable {
    private var closed = false
    private var held = false

    @Synchronized
    fun update(transfers: Int) {
        if (!closed) changeHeld(transfers > 0 && shouldHoldDownloadSleepAssertion(transfers, power()))
    }

    @Synchronized
    override fun close() {
        closed = true
        changeHeld(false)
    }

    private fun changeHeld(requested: Boolean) {
        if (held == requested) return
        setHeld(requested)
        held = requested
    }
}

/** The native monitor also releases immediately on battery changes between policy updates. */
internal object MacDownloadPowerNative {
    external fun powerState(): Int
    external fun setSleepPrevention(requested: Boolean, reason: String)

    fun guard(reason: String): DownloadSleepGuard {
        NativePlayerBridge.ensureLoaded()
        return DownloadSleepGuard(
            power = {
                when (val state = powerState()) {
                    -2 -> DownloadPowerState(onBattery = false, percent = null)
                    in 0..100 -> DownloadPowerState(onBattery = true, percent = state)
                    else -> null
                }
            },
            setHeld = { setSleepPrevention(it, reason) },
        )
    }
}
