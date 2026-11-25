package com.nuvio.app.features.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.PlatformModalBackHandler
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

internal const val ThirtyDaysMs = 30L * 24 * 60 * 60 * 1_000L

internal enum class WatchedCleanupFilter {
    AllWatched,
    OlderThan30Days,
}

internal data class CleanableWatchedDownload(
    val item: DownloadItem,
    val sizeBytes: Long,
    val watchedAtEpochMs: Long?,
)

internal fun sameNormalizedLocalPath(firstFileUri: String, secondFileUri: String): Boolean =
    firstFileUri.trim().removePrefix("file:").trimStart('/') ==
        secondFileUri.trim().removePrefix("file:").trimStart('/')

private fun sharesFileWithAnotherItem(
    item: DownloadItem,
    items: List<DownloadItem>,
    isSameFile: (String, String) -> Boolean,
): Boolean {
    val itemUri = item.localFileUri?.takeIf { it.isNotBlank() }
    val itemLoc = normalizeFloorLocationId(item.locationId)
    return items.any { other ->
        if (other.id == item.id) return@any false
        val otherUri = other.localFileUri?.takeIf { it.isNotBlank() }
        if (itemUri != null && otherUri != null) {
            isSameFile(itemUri, otherUri)
        } else {
            other.status == DownloadStatus.Completed &&
                normalizeFloorLocationId(other.locationId) == itemLoc &&
                other.fileName == item.fileName
        }
    }
}

/**
 * Completed watched downloads that can be cleaned up right now.
 * Never includes unwatched, mid-rewatch, Keep, playing, downloading, paused, failed,
 * placeholder, moving, shared-file, or unreachable items.
 */
internal fun cleanableWatchedDownloads(
    items: List<DownloadItem>,
    watchStatusOf: (DownloadItem) -> DownloadWatchStatus,
    keptDownloadIds: Set<String> = emptySet(),
    movingIds: Set<String> = emptySet(),
    excludedIds: Set<String> = emptySet(),
    isReachable: (DownloadItem) -> Boolean = { true },
    isPlaying: (DownloadItem) -> Boolean = { false },
    isSameFile: (String, String) -> Boolean = DownloadsRepository.backend::isSameFile,
): List<CleanableWatchedDownload> =
    items
        .mapNotNull { item ->
            if (item.status != DownloadStatus.Completed || item.isPlaceholder) return@mapNotNull null
            if (item.id in keptDownloadIds || item.id in movingIds || item.id in excludedIds) return@mapNotNull null
            if (!isReachable(item) || isPlaying(item)) return@mapNotNull null
            if (sharesFileWithAnotherItem(item, items, isSameFile)) return@mapNotNull null
            val status = watchStatusOf(item)
            if (!status.isWatched) return@mapNotNull null
            CleanableWatchedDownload(
                item = item,
                sizeBytes = item.sizeOnDiskBytes.coerceAtLeast(0L),
                watchedAtEpochMs = status.watchedAtEpochMs,
            )
        }
        .sortedWith(
            compareBy<CleanableWatchedDownload> { it.watchedAtEpochMs ?: Long.MAX_VALUE }
                .thenBy { it.item.updatedAtEpochMs }
                .thenBy { it.item.id },
        )

internal fun List<CleanableWatchedDownload>.matchingFilter(
    filter: WatchedCleanupFilter,
    nowEpochMs: Long,
): List<CleanableWatchedDownload> = when (filter) {
    WatchedCleanupFilter.AllWatched -> this
    WatchedCleanupFilter.OlderThan30Days -> filter { candidate ->
        val watchedAt = candidate.watchedAtEpochMs ?: return@filter false
        nowEpochMs - watchedAt >= ThirtyDaysMs
    }
}

internal fun selectedCleanupTotalBytes(
    candidates: List<CleanableWatchedDownload>,
    selectedIds: Set<String>,
): Long = candidates.filter { it.item.id in selectedIds }.sumOf { it.sizeBytes }

internal fun executeWatchedCleanup(
    profileId: Int,
    downloadIds: Collection<String>,
    store: AutoDownloadStore,
    downloads: () -> List<DownloadItem> = { DownloadsRepository.uiState.value.items },
    isWatched: (DownloadItem) -> Boolean,
    isPlaying: (DownloadItem) -> Boolean = AutoDownloads::isPlaying,
    nowEpochMs: () -> Long = { DownloadsClock.nowEpochMs() },
    removeDownload: (
        downloadId: String,
        stillWanted: () -> Boolean,
        onCommitted: (DownloadItem, TrashedWatchedFile?) -> Boolean,
    ) -> DownloadsRepository.RemovedWatchedDownload = { id, stillWanted, onCommitted ->
        DownloadsRepository.cleanUpWatchedDownload(
            downloadId = id,
            allowHoldingWhenNoSystemTrash = true,
            expectedProfileId = profileId,
            stillWanted = stillWanted,
            onCommitted = onCommitted,
        )
    },
): Int {
    var moved = 0
    for (downloadId in downloadIds.distinct()) {
        if (store.profileId() != profileId) break
        val candidate = downloads().firstOrNull { it.id == downloadId } ?: continue
        if (store.isKept(profileId, downloadId) || store.hasPendingKeep(profileId, downloadId)) continue
        if (isPlaying(candidate) || !isWatched(candidate)) continue
        val stillWanted = {
            store.profileId() == profileId &&
                !isPlaying(candidate) &&
                !store.hasPendingKeep(profileId, downloadId) &&
                !store.isKept(profileId, downloadId) &&
                isWatched(candidate)
        }
        val commitUndo: (DownloadItem, TrashedWatchedFile?) -> Boolean = { item, trashed ->
            if (trashed == null || !trashed.canRestore) {
                false
            } else {
                val ruleKey = item.autoDownloadRuleKey
                val season = item.seasonNumber
                val episode = item.episodeNumber
                store.commitWatchedRemoval(
                    profileId = profileId,
                    deleted = AutoDownloadDeleted(
                        title = item.title,
                        seasonNumber = season,
                        episodeNumber = episode,
                        atEpochMs = nowEpochMs(),
                        downloadId = item.id,
                        reason = DeletedDownloadReason.CleanedUp,
                        restorableItem = item,
                        trashed = trashed,
                    ),
                    ruleKey = ruleKey,
                    ownedEpisodeKey = if (ruleKey != null && season != null && episode != null) {
                        autoDownloadEpisodeKey(season, episode)
                    } else {
                        null
                    },
                    droppedDownloadId = item.id,
                )
            }
        }
        val removed = removeDownload(downloadId, stillWanted, commitUndo)
        if (removed.outcome.result == AutoDownloadRemovalResult.Removed) {
            moved++
        }
    }
    return moved
}

internal fun undoDeletedWatchedDownload(
    profileId: Int,
    entry: AutoDownloadDeleted,
    store: AutoDownloadStore,
    restoreDownload: (
        profileId: Int,
        item: DownloadItem,
        trashed: TrashedWatchedFile,
        onRollbackMoved: (TrashedWatchedFile) -> Boolean,
        onRestoredCommitted: (UndoRestoreOutcome) -> Boolean,
    ) -> UndoRestoreOutcome = DownloadsRepository::restoreTrashedDownloadWithOutcome,
): UndoTrashResult {
    val item = entry.restorableItem ?: return UndoTrashResult.NotRestored
    val trashed = entry.trashed?.takeIf { it.canRestore } ?: return UndoTrashResult.NotRestored
    var rollbackSaved = false
    var restoredSaved = false
    val commitRollback: (TrashedWatchedFile) -> Boolean = { replacement ->
        val saved = store.updateDeletedUndo(profileId, entry, replacement)
        if (saved) rollbackSaved = true
        saved
    }
    val commitRestored: (UndoRestoreOutcome) -> Boolean = { restoredOutcome ->
        val saved = when (restoredOutcome.result) {
            UndoTrashResult.Restored ->
                store.commitUndoRestore(
                    profileId = profileId,
                    downloadId = item.id,
                    entry = entry,
                )
            UndoTrashResult.PartiallyRestored -> {
                val remaining = restoredOutcome.updatedTrashed
                if (remaining != null && remaining.canRestore) {
                    store.commitUndoRestore(
                        profileId = profileId,
                        downloadId = item.id,
                        entry = entry,
                        remainingTrashed = remaining,
                    )
                } else {
                    store.commitUndoRestore(
                        profileId = profileId,
                        downloadId = item.id,
                        entry = entry,
                        clearUndoOnly = true,
                    )
                }
            }
            else -> false
        }
        if (saved) restoredSaved = true
        saved
    }
    val outcome = restoreDownload(profileId, item, trashed, commitRollback, commitRestored)
    when (outcome.result) {
        UndoTrashResult.Restored, UndoTrashResult.PartiallyRestored -> {
            if (!restoredSaved && !commitRestored(outcome)) {
                return UndoTrashResult.NotRestored
            }
        }
        UndoTrashResult.FileGone -> {
            runCatching { store.clearDeletedUndo(profileId, entry) }
        }
        UndoTrashResult.TargetTaken, UndoTrashResult.NotReachable, UndoTrashResult.NotRestored -> {
            val updated = outcome.updatedTrashed
            if (!rollbackSaved && updated != null && updated != trashed) {
                store.updateDeletedUndo(profileId, entry, updated)
            }
        }
    }
    return outcome.result
}

@Composable
internal fun WatchedCleanupDialog(
    candidates: List<CleanableWatchedDownload>,
    nowEpochMs: Long,
    usesSystemTrash: Boolean,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(WatchedCleanupFilter.AllWatched) }
    val visible = remember(candidates, filter, nowEpochMs) {
        candidates.matchingFilter(filter, nowEpochMs)
    }
    // Initialized once when the dialog opens so explicit user unchecks stay unchecked across list or filter updates,
    // and newly completed downloads arriving while the dialog is open do not silently select themselves.
    var selectedIds by remember {
        mutableStateOf<Set<String>>(candidates.mapTo(linkedSetOf()) { it.item.id })
    }
    val visibleIds: Set<String> = remember(visible) { visible.mapTo(linkedSetOf()) { it.item.id } }
    val activeSelected = remember(selectedIds, visibleIds) { selectedIds.intersect(visibleIds) }
    val selectedBytes = remember(visible, activeSelected) {
        selectedCleanupTotalBytes(visible, activeSelected)
    }

    PlatformModalBackHandler(onDismiss = onDismiss)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.downloads_cleanup_watched_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = filter == WatchedCleanupFilter.AllWatched,
                        onClick = { filter = WatchedCleanupFilter.AllWatched },
                        label = { Text(stringResource(Res.string.downloads_cleanup_filter_all)) },
                    )
                    FilterChip(
                        selected = filter == WatchedCleanupFilter.OlderThan30Days,
                        onClick = { filter = WatchedCleanupFilter.OlderThan30Days },
                        label = { Text(stringResource(Res.string.downloads_cleanup_filter_30_days)) },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            Res.string.downloads_cleanup_total_selected,
                            activeSelected.size,
                            formatBytes(selectedBytes),
                        ),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (visible.isNotEmpty()) {
                        val allChosen = activeSelected.size == visible.size
                        TextButton(
                            onClick = {
                                selectedIds = if (allChosen) selectedIds - visibleIds else selectedIds + visibleIds
                            },
                        ) {
                            Text(
                                text = stringResource(
                                    if (allChosen) Res.string.downloads_cleanup_clear_selection else Res.string.downloads_cleanup_select_all,
                                ),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
                if (!usesSystemTrash) {
                    Text(
                        text = stringResource(Res.string.downloads_delete_message_held),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (visible.isEmpty()) {
                    Text(
                        text = stringResource(
                            if (filter == WatchedCleanupFilter.OlderThan30Days) {
                                Res.string.downloads_cleanup_empty_30_days
                            } else {
                                Res.string.downloads_cleanup_empty_all
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(visible, key = { it.item.id }) { entry ->
                            val checked = entry.item.id in activeSelected
                            val label = entry.item.nameForLabels()
                            val selectLabel = stringResource(Res.string.downloads_select_description, label)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .toggleable(value = checked, role = Role.Checkbox) {
                                        selectedIds = if (checked) selectedIds - entry.item.id else selectedIds + entry.item.id
                                    }
                                    .semantics { contentDescription = selectLabel }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Text(
                                    text = label,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = formatBytes(entry.sizeBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = activeSelected.isNotEmpty(),
                onClick = { onConfirm(activeSelected) },
            ) {
                Text(
                    stringResource(
                        if (usesSystemTrash) Res.string.downloads_delete_confirm_trash else Res.string.downloads_cleanup_confirm_archive,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}
