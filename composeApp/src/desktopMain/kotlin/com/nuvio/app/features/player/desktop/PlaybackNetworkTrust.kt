package com.nuvio.app.features.player.desktop

import com.nuvio.app.core.storage.DesktopStorage
import java.security.MessageDigest

/** Only a router hardware fingerprint can carry an exception across launches. */
internal class PlaybackTrustChoices(
    private val load: () -> Set<String>,
    private val save: (Set<String>) -> Unit,
) {
    fun allows(network: String): Boolean = rememberable(network) && key(network) in load()
    fun remember(network: String) {
        if (rememberable(network)) save(load() + key(network))
    }
    fun forget() = save(emptySet())
    private fun rememberable(network: String) = network.startsWith("network:") && network.contains('@')
    private fun key(network: String) = MessageDigest.getInstance("SHA-256").digest(network.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

internal object PlaybackNetworkTrust {
    private val store by lazy { DesktopStorage.store("network_trust") }
    val choices = PlaybackTrustChoices(
        load = { store.getString("playback_networks").orEmpty().lines().filter(String::isNotBlank).toSet() },
        save = { store.putString("playback_networks", it.sorted().joinToString("\n")) },
    )
}
