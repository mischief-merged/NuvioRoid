package com.nuvio.app.core.storage

import com.nuvio.app.core.sync.SyncManager
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal actual object AccountDataOwner {
    /** Stamp used in the folder name when one account's data is set aside. Tests can replace it. */
    internal var accountDataStamp: () -> String = {
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
    }

    actual fun prepareForAccount(accountId: String): AccountDataPrep =
        prepareDesktopAccount(DesktopStorage.rootDir, accountId, accountDataStamp()) {
            SyncManager.cancelAccountSync()
            DesktopStorage.dropStoreCache()
        }
}

/**
 * Separates [root] for [accountId]. A different account moves the previous account's files
 * into a dated folder and does not delete them. The owner file changes only after that move works.
 */
internal fun prepareDesktopAccount(
    root: Path,
    accountId: String,
    stamp: String,
    beforeDifferentAccount: () -> Unit = {},
): AccountDataPrep {
    Files.createDirectories(root)
    val ownerFile = root.resolve(OWNER_FILE)
    val current = readOwner(ownerFile)
    if (current.isNullOrBlank()) {
        writeOwner(ownerFile, accountId)
        return AccountDataPrep.Adopted
    }
    if (current == accountId) return AccountDataPrep.Reused
    beforeDifferentAccount()
    val destination = uniqueDestination(root, stamp)
    Files.list(root).use { entries ->
        entries.forEach { path ->
            val name = path.fileName?.toString() ?: return@forEach
            if (keepInPlace(name)) return@forEach
            moveEntry(path, destination.resolve(name))
        }
    }
    writeOwner(ownerFile, accountId)
    return AccountDataPrep.Moved
}

private const val OWNER_FILE = "account-owner"

private fun readOwner(ownerFile: Path): String? {
    if (!Files.isRegularFile(ownerFile)) return null
    return Files.readString(ownerFile).trim().takeIf { it.isNotEmpty() }
}

private fun writeOwner(ownerFile: Path, accountId: String) {
    Files.writeString(ownerFile, accountId)
}

private fun uniqueDestination(root: Path, stamp: String): Path {
    val safe = stamp.filter { it.isLetterOrDigit() || it == '-' }.ifBlank { "unknown" }
    var candidate = root.resolve("previous-account-$safe")
    var suffix = 2
    while (Files.exists(candidate)) {
        candidate = root.resolve("previous-account-$safe-$suffix")
        suffix++
    }
    Files.createDirectories(candidate)
    return candidate
}

private fun keepInPlace(name: String): Boolean = when {
    name == OWNER_FILE || name == "credential-wipe-pending" -> true
    name == "downloads" || name == "moves" -> true
    name.startsWith("previous-account-") -> true
    name.endsWith(".lock") -> true
    name == "nuvio_account_sessions.properties" || name.startsWith("nuvio_account_sessions.properties.") -> true
    else -> false
}

/** Moves the link itself when [source] is a link. The file it points at stays where it is. */
private fun moveEntry(source: Path, target: Path) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target)
    }
}
