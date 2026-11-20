package com.nuvio.app.features.downloads

import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Where the automatic-download state of a profile is kept. Always [AutoDownloadStorage] in the app. */
internal interface AutoDownloadPayloads {
    fun load(profileId: Int): String?
    fun save(profileId: Int, payload: String)
    fun saveUnreadable(profileId: Int, payload: String)
}

internal object PlatformAutoDownloadPayloads : AutoDownloadPayloads {
    override fun load(profileId: Int): String? = AutoDownloadStorage.loadPayload(profileId)

    override fun save(profileId: Int, payload: String) = AutoDownloadStorage.savePayload(profileId, payload)

    override fun saveUnreadable(profileId: Int, payload: String) =
        AutoDownloadStorage.saveUnreadablePayload(profileId, payload)
}

@Serializable
internal enum class DeleteAfterWatchingMode { Off, AutomaticOnly, AllDownloads }

@Serializable
private data class StoredAutoDownloads(
    val deleteAfterWatchingMode: DeleteAfterWatchingMode = DeleteAfterWatchingMode.AutomaticOnly,
    val rules: List<AutoDownloadRule> = emptyList(),
    /** Null means no limit. */
    val storageLimitGb: Int? = AutoDownloadStore.DefaultStorageLimitGb,
    val removals: List<AutoDownloadRemoval> = emptyList(),
    /** Downloads the user chose to keep. They are never removed after watching. */
    val keptDownloadIds: Set<String> = emptySet(),
    /** Episodes removed after watching, newest last. Older than a week is dropped when one is added. */
    val recentlyDeleted: List<AutoDownloadDeleted> = emptyList(),
)

/** An episode that was removed after it was watched. Kept for [AutoDownloadStore.RecentlyDeletedMs] so that the user can see it happened. */
@Serializable
internal data class AutoDownloadDeleted(
    val title: String,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val atEpochMs: Long,
)

/** A watched download that is removed once [dueAtEpochMs] has passed. */
@Serializable
internal data class AutoDownloadRemoval(
    val downloadId: String,
    val ruleKey: String? = null,
    val dueAtEpochMs: Long,
)

/**
 * The automatic-download rules of the active profile.
 *
 * Every read first makes sure the rules in memory are those of the profile that is active now, so
 * one profile's rules are never shown or saved for another. Every change names the profile it was
 * made for and is dropped when that profile is no longer the active one.
 *
 * A stored payload that cannot be read is copied aside before anything is saved over it.
 */
internal class AutoDownloadStore(
    private val payloads: AutoDownloadPayloads,
    private val activeProfileId: () -> Int,
) {
    private val lock = WatchedRemovalGuard.lock
    // Every field is written, also one that has its default value, so that a rule keeps what it
    // was saved with when a default changes later.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private var loadedProfileId: Int? = null
    private var state = StoredAutoDownloads()
    private val _rules = MutableStateFlow<List<AutoDownloadRule>>(emptyList())

    private val _storageLimitGb = MutableStateFlow<Int?>(DefaultStorageLimitGb)

    private val _deleteAfterWatchingMode = MutableStateFlow(DeleteAfterWatchingMode.AutomaticOnly)
    private val _keptDownloadIds = MutableStateFlow<Set<String>>(emptySet())

    val deleteAfterWatchingMode: StateFlow<DeleteAfterWatchingMode> = _deleteAfterWatchingMode.asStateFlow()
    val keptDownloadIds: StateFlow<Set<String>> = _keptDownloadIds.asStateFlow()

    fun setDeleteAfterWatchingMode(profileId: Int, mode: DeleteAfterWatchingMode): Boolean = WatchedRemovalGuard.change {
        if (ensureLoaded() != profileId) return@change false
        save(profileId, state.copy(deleteAfterWatchingMode = mode))
        true
    }

    fun allowsRemoval(profileId: Int, ruleKey: String?): Boolean = synchronized(lock) {
        ensureLoaded() == profileId && state.allowsRemoval(ruleKey)
    }

    private fun StoredAutoDownloads.allowsRemoval(ruleKey: String?): Boolean =
        deleteAfterWatchingMode != DeleteAfterWatchingMode.Off &&
            if (ruleKey == null) deleteAfterWatchingMode == DeleteAfterWatchingMode.AllDownloads
            else rules.any { it.key == ruleKey && it.deleteAfterWatching }

    private val _removals = MutableStateFlow<List<AutoDownloadRemoval>>(emptyList())

    private val _recentlyDeleted = MutableStateFlow<List<AutoDownloadDeleted>>(emptyList())

    /** The rules of the profile that was active at the last read or change. */
    val rules: StateFlow<List<AutoDownloadRule>> = _rules.asStateFlow()

    /** The removals scheduled for that profile. */
    val removals: StateFlow<List<AutoDownloadRemoval>> = _removals.asStateFlow()

    /** The episodes of that profile that were removed after watching, oldest first. */
    val recentlyDeleted: StateFlow<List<AutoDownloadDeleted>> = _recentlyDeleted.asStateFlow()

    /** Notes that [entry] was removed. Entries older than a week are dropped. False when [profileId] is not the active profile. */
    fun recordDeleted(profileId: Int, entry: AutoDownloadDeleted): Boolean = synchronized(lock) {
        if (ensureLoaded() != profileId) return false
        val cutoff = entry.atEpochMs - RecentlyDeletedMs
        save(profileId, state.copy(recentlyDeleted = state.recentlyDeleted.filter { it.atEpochMs >= cutoff } + entry))
        true
    }

    /** The removals scheduled for [profileId]. Empty when it is not the active profile. */
    fun removals(profileId: Int): List<AutoDownloadRemoval> = synchronized(lock) {
        if (ensureLoaded() != profileId) emptyList() else state.removals
    }

    fun isKept(profileId: Int, downloadId: String): Boolean = synchronized(lock) {
        ensureLoaded() == profileId && downloadId in state.keptDownloadIds
    }

    /** Schedules [removal], replacing an earlier one of the same download. Refused for a kept download. */
    fun scheduleRemoval(profileId: Int, removal: AutoDownloadRemoval): Boolean = synchronized(lock) {
        if (ensureLoaded() != profileId || removal.downloadId in state.keptDownloadIds || !state.allowsRemoval(removal.ruleKey)) return false
        save(profileId, state.copy(removals = state.removals.filterNot { it.downloadId == removal.downloadId } + removal))
        true
    }

    /** Drops [removal] if it is still the one scheduled for its download. */
    fun dropRemoval(profileId: Int, removal: AutoDownloadRemoval): Boolean = synchronized(lock) {
        if (ensureLoaded() != profileId) return false
        save(profileId, state.copy(removals = state.removals - removal))
        true
    }

    /** Keeps a download for good: its scheduled removal is dropped and none is scheduled again. */
    fun keep(profileId: Int, downloadId: String): Boolean = WatchedRemovalGuard.change {
        if (ensureLoaded() != profileId) return@change false
        save(
            profileId,
            state.copy(
                removals = state.removals.filterNot { it.downloadId == downloadId },
                keptDownloadIds = state.keptDownloadIds + downloadId,
            ),
        )
        true
    }

    /** Undoing Keep permits a new schedule the next time the download is finished. */
    fun undoKeep(profileId: Int, downloadId: String): Boolean = synchronized(lock) {
        if (ensureLoaded() != profileId) return false
        save(profileId, state.copy(keptDownloadIds = state.keptDownloadIds - downloadId))
        true
    }

    /** The storage limit for automatic downloads of that profile, in gigabytes. Null means no limit. */
    val storageLimitGb: StateFlow<Int?> = _storageLimitGb.asStateFlow()

    fun storageLimitBytes(): Long? = synchronized(lock) {
        ensureLoaded()
        state.storageLimitGb?.let { it * BytesPerGigabyte }
    }

    /** False when [profileId] is not the active profile, in which case nothing is changed. */
    fun setStorageLimit(profileId: Int, limitGb: Int?): Boolean = synchronized(lock) {
        if (ensureLoaded() != profileId) return false
        save(profileId, state.copy(storageLimitGb = limitGb).normalized())
        true
    }

    /** The active profile, with its rules loaded. */
    fun profileId(): Int = synchronized(lock) { ensureLoaded() }

    fun currentRules(): List<AutoDownloadRule> = synchronized(lock) {
        ensureLoaded()
        state.rules
    }

    fun rule(profileId: Int, ruleKey: String): AutoDownloadRule? = synchronized(lock) {
        if (ensureLoaded() != profileId) return null
        state.rules.firstOrNull { it.key == ruleKey }
    }

    /** Adds [rule] or replaces the rule of the same show. What the earlier rule downloaded is kept. */
    fun upsert(profileId: Int, rule: AutoDownloadRule): Boolean {
        val saveRule = {
            change(profileId) { rules ->
                val existing = rules.firstOrNull { it.key == rule.key }
                val saved = rule.normalized().copy(ownedEpisodeKeys = rule.ownedEpisodeKeys + existing?.ownedEpisodeKeys.orEmpty())
                if (existing == null) rules + saved else rules.map { if (it.key == saved.key) saved else it }
            }
        }
        // Signal before reading the existing rule, since that read also waits for the removal lock.
        return if (!rule.deleteAfterWatching) WatchedRemovalGuard.change(saveRule) else saveRule()
    }

    /** Removes a rule and the removals that were scheduled because of it. */
    fun remove(profileId: Int, ruleKey: String): Boolean = WatchedRemovalGuard.change {
        if (ensureLoaded() != profileId) return@change false
        save(
            profileId,
            state.copy(
                rules = state.rules.filterNot { it.key == ruleKey },
                removals = state.removals.filterNot { it.ruleKey == ruleKey },
            ),
        )
        true
    }

    fun update(profileId: Int, ruleKey: String, transform: (AutoDownloadRule) -> AutoDownloadRule): Boolean =
        change(profileId) { rules -> rules.map { if (it.key == ruleKey) transform(it) else it } }

    /** False when [profileId] is not the active profile, in which case nothing is changed. */
    private fun change(profileId: Int, transform: (List<AutoDownloadRule>) -> List<AutoDownloadRule>): Boolean =
        synchronized(lock) {
            if (ensureLoaded() != profileId) return false
            save(profileId, state.copy(rules = transform(state.rules)))
            true
        }

    private fun save(profileId: Int, changed: StoredAutoDownloads) {
        val updated = changed.normalized()
        if (updated == state) return
        // Written first: when that fails, nothing in memory has changed, so what is shown is what is stored.
        payloads.save(profileId, json.encodeToString(updated))
        state = updated
        _deleteAfterWatchingMode.value = updated.deleteAfterWatchingMode
        _keptDownloadIds.value = updated.keptDownloadIds
        _rules.value = updated.rules
        _storageLimitGb.value = updated.storageLimitGb
        _removals.value = updated.removals
        _recentlyDeleted.value = updated.recentlyDeleted
    }

    private fun ensureLoaded(): Int {
        val active = activeProfileId()
        if (loadedProfileId != active) {
            state = read(active)
            loadedProfileId = active
            _deleteAfterWatchingMode.value = state.deleteAfterWatchingMode
            _keptDownloadIds.value = state.keptDownloadIds
            _rules.value = state.rules
            _storageLimitGb.value = state.storageLimitGb
            _removals.value = state.removals
            _recentlyDeleted.value = state.recentlyDeleted
        }
        return active
    }

    private fun read(profileId: Int): StoredAutoDownloads {
        val payload = payloads.load(profileId)?.takeIf { it.isNotBlank() } ?: return StoredAutoDownloads()
        return runCatching {
            json.decodeFromString<StoredAutoDownloads>(payload).normalized()
        }.getOrElse {
            payloads.saveUnreadable(profileId, payload)
            StoredAutoDownloads()
        }
    }

    private fun StoredAutoDownloads.normalized(): StoredAutoDownloads = copy(
        rules = rules.map(AutoDownloadRule::normalized).distinctBy { it.key },
        recentlyDeleted = recentlyDeleted.takeLast(MaxRecentlyDeleted),
        storageLimitGb = if (storageLimitGb in storageLimitOptionsGb) storageLimitGb else DefaultStorageLimitGb,
        // Changing the setting or a rule drops removals immediately, including before they are due.
        removals = removals
            .distinctBy { it.downloadId }
            .filter { it.downloadId !in keptDownloadIds && allowsRemoval(it.ruleKey) },
    )

    companion object {
        const val DefaultStorageLimitGb = 50
        const val RecentlyDeletedMs = 7L * 24 * 60 * 60 * 1000
        const val MaxRecentlyDeleted = 20
        val storageLimitOptionsGb: List<Int?> = listOf(null, 10, 25, 50, 100, 250)
    }
}
