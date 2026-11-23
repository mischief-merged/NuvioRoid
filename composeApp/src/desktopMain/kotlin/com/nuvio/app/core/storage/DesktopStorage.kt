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

    fun store(name: String, cacheEntries: Int? = null): Store = synchronized(stores) {
        stores.getOrPut(name) { Store(rootDir.resolve("$name.properties"), cacheEntries) }
    }

    fun wipe() {
        synchronized(stores) {
            stores.values.forEach(Store::clearInMemory)
            stores.clear()
        }
        if (!rootDir.exists()) return
        wipeFolder(rootDir, KeptOnWipe)
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
        private val cacheEntries: Int? = null,
        private val write: (Properties, OutputStream) -> Unit = { properties, output ->
            properties.store(output, "Nuvio desktop preferences")
        },
    ) {
        private val gate = FileGate.of(file.resolveSibling("${file.fileName}.lock"))
        private val properties = object : Properties() {
            override fun put(key: Any, value: Any): Any? {
                val old = super.put(key, value)
                cacheEntries?.let { limit ->
                    require(limit > 0)
                    while (size > limit) remove(keys.first { it != key })
                }
                return old
            }
        }
        private var loaded = false
        private var fileIsGood = false
        private val temporaryFile = file.resolveSibling("${file.fileName}.tmp")
        private val previousFile = file.resolveSibling("${file.fileName}.bak")

        /** Runs [block] holding the lock of this store file across processes. Re-entrant. */
        fun <T> exclusive(block: () -> T): T = gate.exclusive(block)

        private inline fun <T> local(block: () -> T): T = gate.local(block)

        fun contains(key: String): Boolean = local {
            ensureLoaded()
            properties.containsKey(key)
        }

        fun getString(key: String): String? = local {
            ensureLoaded()
            properties.getProperty(key)
        }

        /** The value as it is in the file now, read again under the lock. */
        fun getFreshString(key: String): String? = exclusive {
            reload()
            properties.getProperty(key)
        }

        /** Every key and value as they are in the file now, read again under the lock. */
        fun freshEntries(): Map<String, String> = exclusive {
            reload()
            properties.stringPropertyNames().associateWith { properties.getProperty(it) }
        }

        /** The value the previous copy of the store holds, for a value of the store that cannot be used. */
        fun getPreviousString(key: String): String? = local {
            Properties().takeIf { read(previousFile, it) }?.getProperty(key)
        }

        fun putString(key: String, value: String?) = change {
            if (value == null) properties.remove(key) != null else properties.setProperty(key, value) != value
        }

        /** Writes every entry in one save. A null value removes that key. Nothing is written when nothing changed. */
        fun putAll(entries: Map<String, String?>) = change {
            var changed = false
            entries.forEach { (key, value) ->
                val updated = if (value == null) {
                    properties.remove(key) != null
                } else {
                    properties.setProperty(key, value) != value
                }
                if (updated) changed = true
            }
            changed
        }

        /** Changes one value during the write's existing reload, without a second disk read. */
        fun updateString(key: String, transform: (String?) -> String?) = change {
            val previous = properties.getProperty(key)
            val next = transform(previous)
            if (next == previous) false else {
                if (next == null) properties.remove(key) else properties.setProperty(key, next)
                true
            }
        }

        /** Every key the store holds. */
        fun keys(): Set<String> = local {
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

        fun remove(key: String) = change { properties.remove(key) != null }

        fun removeAll(keys: Iterable<String>) = change {
            var changed = false
            keys.forEach { key -> if (properties.remove(key) != null) changed = true }
            changed
        }

        fun clearInMemory() = local {
            properties.clear()
            loaded = false
        }

        /** Under the lock: the file is read again, [apply] changes only its own keys, and the result is saved. */
        private fun change(apply: () -> Boolean) = exclusive {
            reload()
            if (apply()) persist()
        }

        private fun ensureLoaded() {
            if (loaded) return
            reload()
        }

        private fun reload() {
            loaded = true
            properties.clear()
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

        /**
         * The file is never written in place: the new content goes to a file beside it, which then
         * takes its place in one step, so a write that is cut short leaves the file as it was. The
         * content the file had before stays beside it as the previous copy.
         */
        private fun persist() {
            Files.createDirectories(file.parent)
            Files.newOutputStream(temporaryFile).use { output ->
                write(properties, output)
                output.flush()
            }
            if (fileIsGood) keepPreviousCopy()
            try {
                Files.move(temporaryFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING)
            }
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
            private val gates = mutableMapOf<Path, java.lang.ref.WeakReference<FileGate>>()

            fun of(lockFile: Path): FileGate = synchronized(gates) {
                gates.entries.removeAll { it.value.get() == null }
                val key = lockFile.toAbsolutePath().normalize()
                gates[key]?.get() ?: FileGate(key).also { gates[key] = java.lang.ref.WeakReference(it) }
            }
        }
    }
}
