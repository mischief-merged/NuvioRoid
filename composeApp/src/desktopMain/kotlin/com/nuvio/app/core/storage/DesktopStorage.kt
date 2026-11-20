package com.nuvio.app.core.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.Locale
import java.util.Properties
import kotlin.io.path.exists

internal object DesktopStorage {
    private val json = Json { ignoreUnknownKeys = true }
    private val stores = mutableMapOf<String, Store>()

    val rootDir: Path by lazy {
        resolveAppDataDir().also { Files.createDirectories(it) }
    }

    val cacheDir: Path by lazy {
        resolveCacheDir().also { Files.createDirectories(it) }
    }

    fun store(name: String): Store = synchronized(stores) {
        stores.getOrPut(name) { Store(rootDir.resolve("$name.properties"), vault = if (name in DesktopCredentials.storeNames) DesktopCredentials.vault else null, protectedKey = { DesktopCredentials.protects(name, it) }, checkAccess = { if (name in DesktopCredentials.storeNames && Files.exists(wipePending)) throw SecureStorageUnavailable() }) }
    }

    fun migrateCredentials() {
        if (!Files.isDirectory(rootDir)) return
        Files.list(rootDir).use { files ->
            files.filter { it.fileName.toString().contains(".properties") }
                .map { it.fileName.toString().substringBefore(".properties") }
                .distinct().forEach { name ->
                    runCatching { store(name).freshEntries() }.onFailure { DesktopCredentials.warning() }
                }
        }
    }

    private val wipePending: Path get() = rootDir.resolve("credential-wipe-pending")

    fun prepareCredentials() {
        if (Files.exists(wipePending)) LocalAccountDataCleaner.wipe()
        DesktopCredentials.storeNames.forEach { store(it).freshEntries() }
        com.nuvio.app.core.network.DesktopSessionSettings().migrate()
    }

    fun wipe() {
        wipeCredentialsAndFiles(rootDir,
            clearLegacy = { com.nuvio.app.core.network.clearLegacyAccountSessions() },
            clearStore = { store(it).clearCredentials() },
        )
        synchronized(stores) {
            stores.values.forEach(Store::clearInMemory)
            stores.clear()
        }
    }

    internal fun wipeCredentialsAndFiles(root: Path, clearLegacy: () -> Unit, clearStore: (String) -> Unit) {
        Files.createDirectories(root)
        val pending = root.resolve("credential-wipe-pending")
        Files.writeString(pending, "pending")
        java.nio.channels.FileChannel.open(pending, java.nio.file.StandardOpenOption.WRITE).use { it.force(true) }
        java.nio.channels.FileChannel.open(root, java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        var failure: Throwable? = null
        runCatching(clearLegacy).onFailure { failure = it }
        DesktopCredentials.storeNames.forEach { name ->
            runCatching { clearStore(name) }.onFailure { if (failure == null) failure = it }
        }
        if (failure != null) throw SecureStorageUnavailable()
        wipeFolder(root, KeptOnWipe + pending.fileName.toString())
        val remaining = Files.list(root).use { files ->
            files.anyMatch { it != pending && it.fileName.toString() !in KeptOnWipe }
        }
        if (remaining) throw SecureStorageUnavailable()
        Files.delete(pending)
        java.nio.channels.FileChannel.open(root, java.nio.file.StandardOpenOption.READ).use { it.force(true) }
    }

    /**
     * What a wipe of the app's data (sign-out, a lost session, account deletion) never touches: the app's own
     * downloads folder, which holds the user's downloaded videos, and the move journals that may still name
     * copies of them. Settings and lists go; the files stay where they are.
     */
    internal val KeptOnWipe = setOf("downloads", "moves")

    /** Deletes everything under [root] except the top-level entries named in [keep] and what is inside them. Never follows a link. */
    internal fun wipeFolder(root: Path, keep: Set<String>) {
        if (!Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        val kept = keep.map { root.resolve(it) }
        Files.walk(root).use { stream ->
            stream
                .sorted(Comparator.reverseOrder())
                .filter { it != root && kept.none { k -> it.startsWith(k) } }
                .forEach { path -> runCatching { Files.deleteIfExists(path) } }
        }
    }

    private fun resolveAppDataDir(): Path {
        val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val userHome = Paths.get(System.getProperty("user.home").orEmpty())
        return when {
            osName.contains("mac") -> userHome.resolve("Library/Application Support/Nuvio")
            osName.contains("win") -> {
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                (appData?.let(Paths::get) ?: userHome.resolve("AppData/Roaming")).resolve("Nuvio")
            }
            else -> {
                val xdgConfig = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                (xdgConfig?.let(Paths::get) ?: userHome.resolve(".config")).resolve("nuvio")
            }
        }
    }

    private fun resolveCacheDir(): Path {
        val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val userHome = Paths.get(System.getProperty("user.home").orEmpty())
        return when {
            osName.contains("mac") -> userHome.resolve("Library/Caches/Nuvio")
            osName.contains("win") -> {
                val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                (localAppData?.let(Paths::get) ?: userHome.resolve("AppData/Local")).resolve("Nuvio/Cache")
            }
            else -> {
                val xdgCache = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
                (xdgCache?.let(Paths::get) ?: userHome.resolve(".cache")).resolve("nuvio")
            }
        }
    }

    /**
     * One store file shared by every copy of the app. Every change is made under [exclusive]: a lock held
     * across processes on `<file>.lock`, inside which the file is read again and only the changed key is
     * written over what is on disk, so a copy whose memory is stale never overwrites another copy's keys.
     */
    internal class Store(
        private val file: Path,
        vault: CredentialVault? = null,
        private val protectedKey: (String) -> Boolean = { false },
        private val warning: () -> Unit = DesktopCredentials::warning,
        private val checkAccess: () -> Unit = {},
        private val write: (Properties, OutputStream) -> Unit = { properties, output ->
            properties.store(output, "Nuvio desktop preferences")
        },
    ) {
        private val gate = FileGate.of(file.resolveSibling("${file.fileName}.lock"))
        private val properties = Properties()
        private val credentials = vault?.let { CredentialCodec(file, it, warning) }
        private var loaded = false
        private var fileIsGood = false
        private val temporaryFile = file.resolveSibling("${file.fileName}.tmp")
        private val previousFile = file.resolveSibling("${file.fileName}.bak")

        /** Runs [block] holding the lock of this store file across processes. Re-entrant. */
        fun <T> exclusive(block: () -> T): T = gate.exclusive(block)

        private inline fun <T> local(block: () -> T): T = gate.local(block)

        fun isSecured(key: String): Boolean = exclusive {
            reload()
            properties.getProperty(key)?.let { CredentialCodec.isReference(it) } == true
        }

        fun contains(key: String): Boolean = exclusive {
            ensureLoaded()
            properties.containsKey(key)
        }

        fun getString(key: String): String? = exclusive {
            ensureLoaded()
            properties.getProperty(key)?.let { decode(key, it) }
        }

        /** The value as it is in the file now, read again under the lock. */
        fun getFreshString(key: String): String? = exclusive {
            reload()
            properties.getProperty(key)?.let { decode(key, it) }
        }

        /** Every key and value as they are in the file now, read again under the lock. */
        fun freshEntries(): Map<String, String> = exclusive {
            reload()
            properties.stringPropertyNames().associateWith { decode(it, properties.getProperty(it)) }
        }

        /** The value the previous copy of the store holds, for a value of the store that cannot be used. */
        fun getPreviousString(key: String): String? = local {
            Properties().takeIf { read(previousFile, it) }?.getProperty(key)?.let { decode(key, it) }
        }

        fun putString(key: String, value: String?) {
            if (value == null) remove(key) else change { properties.setProperty(key, value) != value }
        }

        /** Every key the store holds. */
        fun keys(): Set<String> = exclusive {
            ensureLoaded()
            properties.stringPropertyNames().toSet()
        }

        /**
         * Flushes the saved file and then its folder to disk. For the few writes that must survive a power
         * cut. True only when both flushes succeeded; a folder that cannot be flushed here is a failure.
         */
        fun sync(): Boolean = exclusive {
            ensureLoaded()
            runCatching {
                java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.WRITE).use { it.force(true) }
                java.nio.channels.FileChannel.open(file.parent, java.nio.file.StandardOpenOption.READ).use { it.force(true) }
            }.isSuccess
        }

        fun getBoolean(key: String): Boolean? =
            getString(key)?.toBooleanStrictOrNull()

        fun putBoolean(key: String, value: Boolean) {
            putString(key, value.toString())
        }

        fun getInt(key: String): Int? =
            getString(key)?.toIntOrNull()

        fun putInt(key: String, value: Int) {
            putString(key, value.toString())
        }

        fun getFloat(key: String): Float? =
            getString(key)?.toFloatOrNull()

        fun putFloat(key: String, value: Float) {
            putString(key, value.toString())
        }

        fun getStringSet(key: String): Set<String>? =
            getString(key)?.let { payload ->
                runCatching { json.decodeFromString<List<String>>(payload).toSet() }.getOrNull()
            }

        fun putStringSet(key: String, values: Set<String>) {
            putString(key, json.encodeToString(values.toList()))
        }

        fun remove(key: String) = removeAll(listOf(key))

        fun removeAll(keys: Iterable<String>) = exclusive {
            val removed = keys.toSet()
            reload()
            val references = mutableSetOf<String>()
            removed.forEach { key -> properties.getProperty(key)?.let(references::add) }
            val changed = removed.fold(false) { changed, key -> (properties.remove(key) != null) || changed }
            if (changed) persist()
            // A removed credential must not be restored from a previous copy.
            val recovery = listOf(file, previousFile, temporaryFile).map { it.resolveSibling("${it.fileName}.secure-tmp") }
            (listOf(previousFile, temporaryFile) + recovery).forEach { source ->
                val copy = Properties()
                if (read(source, copy)) {
                    var dirty = false
                    removed.filter(protectedKey).forEach { key ->
                        copy.remove(key)?.let { references.add(it.toString()); dirty = true }
                    }
                    if (dirty) replaceCopy(source, copy)
                }
            }
            references.filter(CredentialCodec::isReference).forEach { credentials?.forget(it) }
        }

        /** Replaces obsolete verifiers in recovery copies after the new value is durable. */
        fun replacePreviousValue(key: String, value: String) = exclusive {
            listOf(previousFile, temporaryFile).forEach { source ->
                val copy = Properties()
                if (read(source, copy) && copy.containsKey(key) && copy.getProperty(key) != value) {
                    copy.setProperty(key, value)
                    replaceCopy(source, encoded(copy))
                }
            }
        }

        fun clearInMemory() = local {
            properties.clear()
            loaded = false
        }

        /** Under the lock: the file is read again, [apply] changes only its own keys, and the result is saved. */
        private fun change(apply: () -> Boolean) = exclusive {
            reload()
            try {
                if (apply()) persist()
            } catch (error: Throwable) {
                properties.clear()
                loaded = false
                throw error
            }
        }

        private fun ensureLoaded() {
            checkAccess()
            if (loaded) return
            reload()
        }

        private fun reload() {
            checkAccess()
            loaded = true
            properties.clear()
            fileIsGood = false
            migrateCredentials()
            if (!file.exists()) return
            fileIsGood = read(file, properties)
            if (!fileIsGood) {
                properties.clear()
                read(previousFile, properties)
            }
        }

        private fun read(source: Path, into: Properties): Boolean =
            runCatching {
                if (!Files.isRegularFile(source) || Files.size(source) == 0L) return false
                Files.newInputStream(source).use(into::load)
                true
            }.getOrDefault(false)

        private fun decode(key: String, value: String): String =
            (if (protectedKey(key)) credentials?.decode(value) ?: value else value).also {
                if (protectedKey(key)) com.nuvio.app.core.diagnostics.CredentialRedactor.remember(it)
            }

        private fun encoded(source: Properties): Properties = Properties().also { copy ->
            source.stringPropertyNames().forEach { key ->
                val value = source.getProperty(key)
                if (protectedKey(key) && !CredentialCodec.isReference(value)) com.nuvio.app.core.diagnostics.CredentialRedactor.remember(value)
                copy.setProperty(key, if (protectedKey(key)) credentials?.encode(value) ?: value else value)
            }
        }

        /** Each file changes only after every replacement in that file has been read back from the vault. */
        private fun migrateCredentials() {
            if (credentials == null) return
            val copies = listOf(file, previousFile, temporaryFile).let { originals ->
                originals + originals.map { it.resolveSibling("${it.fileName}.secure-tmp") }
            }
            copies.forEach { source ->
                try {
                    val original = Properties()
                    if (!read(source, original)) return@forEach
                    val replacement = encoded(original)
                    val unconfirmed = replacement.stringPropertyNames().any {
                        protectedKey(it) && !CredentialCodec.isReference(replacement.getProperty(it))
                    }
                    if (!unconfirmed && replacement != original) replaceCopy(source, replacement)
                } catch (_: Exception) { warning() }
            }
            val references = mutableSetOf<String>()
            for (source in copies) {
                if (!Files.exists(source)) continue
                val copy = Properties()
                if (!read(source, copy)) return
                copy.stringPropertyNames().filter(protectedKey).forEach { references.add(copy.getProperty(it)) }
            }
            credentials.retain(references)
        }

        fun clearCredentials() = exclusive { credentials?.clear() }

        private fun replaceCopy(target: Path, values: Properties) {
            val pending = target.resolveSibling("${target.fileName}.secure-tmp")
            Files.newOutputStream(pending).use { output -> write(values, output); output.flush() }
            java.nio.channels.FileChannel.open(pending, java.nio.file.StandardOpenOption.WRITE).use { it.force(true) }
            try {
                Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING)
            }
            forceDirectory()
        }

        private fun forceDirectory() {
            java.nio.channels.FileChannel.open(file.parent, java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        }

        /**
         * The file is never written in place: the new content goes to a file beside it, which then
         * takes its place in one step, so a write that is cut short leaves the file as it was. The
         * content the file had before stays beside it as the previous copy.
         */
        private fun persist() {
            Files.createDirectories(file.parent)
            val protected = encoded(properties)
            Files.newOutputStream(temporaryFile).use { output ->
                write(protected, output)
                output.flush()
            }
            if (fileIsGood) keepPreviousCopy()
            try {
                Files.move(temporaryFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING)
            }
            if (credentials != null) {
                java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.WRITE).use { it.force(true) }
                forceDirectory()
            }
            properties.clear()
            properties.putAll(protected)
            fileIsGood = true
        }

        private fun keepPreviousCopy() {
            runCatching {
                Files.deleteIfExists(previousFile)
                try {
                    Files.createLink(previousFile, file)
                } catch (_: Exception) {
                    Files.copy(file, previousFile, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    /**
     * The lock of one store file: a lock inside this process (shared by every Store object of that file)
     * and, while it is held at the outermost level, an OS lock on the lock file that other processes see.
     */
    /** The lock of a store file could not be taken: nothing was read or written under it. */
    internal class StoreLockUnavailable(lockFile: Path, cause: Throwable) : java.io.IOException("Cannot lock $lockFile", cause)

    internal class FileGate private constructor(private val lockFile: Path) {
        private val inProcess = java.util.concurrent.locks.ReentrantLock()
        private var depth = 0
        private var channel: java.nio.channels.FileChannel? = null
        private var osLock: java.nio.channels.FileLock? = null

        inline fun <T> local(block: () -> T): T {
            lockLocal()
            try {
                return block()
            } finally {
                unlockLocal()
            }
        }

        fun lockLocal() = inProcess.lock()
        fun unlockLocal() = inProcess.unlock()

        /** Runs [block] holding the lock across processes. When that lock cannot be had, [block] does not run and this throws. */
        fun <T> exclusive(block: () -> T): T = local {
            if (depth == 0) acquireOs()
            depth++
            try {
                block()
            } finally {
                if (--depth == 0) releaseOs()
            }
        }

        private fun acquireOs() {
            try {
                Files.createDirectories(lockFile.parent)
                val opened = java.nio.channels.FileChannel.open(lockFile, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)
                channel = opened
                // Blocks until another copy of the app lets go.
                osLock = opened.lock()
            } catch (error: Exception) {
                releaseOs()
                throw StoreLockUnavailable(lockFile, error)
            }
        }

        private fun releaseOs() {
            runCatching { osLock?.release() }
            runCatching { channel?.close() }
            osLock = null
            channel = null
        }

        companion object {
            private val gates = java.util.concurrent.ConcurrentHashMap<Path, FileGate>()

            fun of(lockFile: Path): FileGate = gates.computeIfAbsent(lockFile.toAbsolutePath().normalize()) { FileGate(it) }
        }
    }
}
