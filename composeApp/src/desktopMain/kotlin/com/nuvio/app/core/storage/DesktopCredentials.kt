package com.nuvio.app.core.storage

import co.touchlab.kermit.Logger
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A missing item is null; a locked or unavailable store must throw, never look like a sign-out. */
internal interface CredentialVault {
    fun read(account: String): String?
    fun write(account: String, value: String)
    fun remove(account: String)
}

internal object DesktopCredentials {
    val vault: CredentialVault? by lazy {
        if (System.getProperty("os.name").orEmpty().contains("mac", true)) MacKeychainVault() else null
    }

    val storeNames = setOf("nuvio_account_sessions", "nuvio_trakt_auth", "nuvio_mdblist_auth", "nuvio_simkl_auth",
        "nuvio_addons", "server_credentials", "nuvio_debrid_settings", "nuvio_player_settings", "nuvio_tmdb_settings",
        "nuvio_mdblist_settings", "nuvio_plugins", "nuvio_custom_poster_url")

    fun protects(store: String, key: String): Boolean = when (store) {
        "nuvio_account_sessions", "nuvio_trakt_auth", "nuvio_mdblist_auth", "nuvio_simkl_auth" -> true
        "nuvio_addons" -> true
        "server_credentials" -> key != "__keys"
        "nuvio_debrid_settings" -> "api_key" in key || "pending_device_authorization" in key
        "nuvio_player_settings" -> "api_key" in key || key.startsWith("animeskip_client_id")
        "nuvio_tmdb_settings", "nuvio_mdblist_settings" -> "api_key" in key
        "nuvio_custom_poster_url" -> key.startsWith("custom_poster_url_pattern")
        "nuvio_plugins" -> key.startsWith("settings_")
        else -> false
    }

    fun warning() {
        Logger.withTag("CredentialStorage").w { "Secure storage is unavailable; existing saved credentials have been kept." }
    }
}

/** Immutable references make an interrupted file replacement safe, including its previous copy. */
internal class CredentialCodec(
    file: Path,
    private val vault: CredentialVault,
    private val warning: () -> Unit = DesktopCredentials::warning,
) {
    private val namespace = MessageDigest.getInstance("SHA-256")
        .digest(file.toAbsolutePath().normalize().toString().toByteArray())
        .joinToString("") { "%02x".format(it) }
    private val cached = mutableMapOf<String, String>()

    private val json = Json { ignoreUnknownKeys = true }
    private fun values(): Map<String, String> = vault.read(namespace)?.let {
        json.decodeFromString<Map<String, String>>(it)
    }.orEmpty()

    private fun writeConfirmed(values: Map<String, String>) {
        val payload = json.encodeToString(values)
        vault.write(namespace, payload)
        check(vault.read(namespace) == payload)
    }

    fun encode(value: String): String {
        if (isReference(value)) return value
        val account = "$namespace:${UUID.randomUUID()}"
        return try {
            writeConfirmed(values() + (account to value))
            cached[account] = value
            prefix + account
        } catch (_: Exception) {
            warning()
            throw SecureStorageUnavailable()
        } catch (_: LinkageError) {
            warning()
            throw SecureStorageUnavailable()
        }
    }

    fun decode(value: String): String {
        if (!isReference(value)) return value
        val account = value.removePrefix(prefix)
        return try {
            val saved = values()[account] ?: throw IllegalStateException("Saved credential is unavailable")
            cached[account] = saved
            saved
        } catch (_: Exception) {
            warning()
            cached[account] ?: throw SecureStorageUnavailable()
        } catch (_: LinkageError) {
            warning()
            cached[account] ?: throw SecureStorageUnavailable()
        }
    }

    fun forget(value: String) {
        if (!isReference(value)) return
        val account = value.removePrefix(prefix)
        cached.remove(account)
        try { writeConfirmed(values() - account) } catch (_: Exception) { warning() } catch (_: LinkageError) { warning() }
    }

    fun retain(references: Set<String>) {
        try {
            val values = values()
            val accounts = references.map { it.removePrefix(prefix) }.toSet()
            val kept = values.filterKeys { it in accounts }
            if (kept != values) writeConfirmed(kept)
            cached.keys.retainAll(accounts)
        } catch (_: Exception) { warning() } catch (_: LinkageError) { warning() }
    }

    fun clear() {
        try { vault.remove(namespace); cached.clear() }
        catch (_: Exception) { warning(); throw SecureStorageUnavailable() }
        catch (_: LinkageError) { warning(); throw SecureStorageUnavailable() }
    }

    companion object {
        private const val prefix = "@keychain:v1:"
        private val reference = Regex("@keychain:v1:[0-9a-f]{64}:[0-9a-f-]{36}")
        fun isReference(value: String): Boolean = reference.matches(value)
    }
}

/** Calls Security.framework directly: credentials never appear in process arguments or a shell. */
internal class MacKeychainVault(private val service: String = "com.nuvio.desktop.credentials.v1") : CredentialVault {
    private interface Security : Library {
        fun SecKeychainFindGenericPassword(keychain: Pointer?, serviceLength: Int, service: ByteArray,
            accountLength: Int, account: ByteArray, length: IntByReference?, data: PointerByReference?, item: PointerByReference?): Int
        fun SecKeychainAddGenericPassword(keychain: Pointer?, serviceLength: Int, service: ByteArray,
            accountLength: Int, account: ByteArray, length: Int, data: ByteArray, item: PointerByReference?): Int
        fun SecKeychainItemModifyAttributesAndData(item: Pointer, attributes: Pointer?, length: Int, data: ByteArray): Int
        fun SecKeychainItemFreeContent(attributes: Pointer?, data: Pointer): Int
        fun SecKeychainItemDelete(item: Pointer): Int
    }
    private interface Core : Library { fun CFRelease(value: Pointer) }
    private val security by lazy { Native.load("/System/Library/Frameworks/Security.framework/Security", Security::class.java) }
    private val core by lazy { Native.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", Core::class.java) }
    private val serviceBytes = service.toByteArray(Charsets.UTF_8)

    override fun read(account: String): String? {
        val name = account.toByteArray(Charsets.UTF_8)
        val size = IntByReference()
        val data = PointerByReference()
        val status = security.SecKeychainFindGenericPassword(null, serviceBytes.size, serviceBytes, name.size, name, size, data, null)
        if (status == -25300) return null
        checkStatus(status)
        val pointer = data.value ?: return ""
        try {
            val bytes = pointer.getByteArray(0, size.value)
            return try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
        } finally { security.SecKeychainItemFreeContent(null, pointer) }
    }

    override fun write(account: String, value: String) {
        val name = account.toByteArray(Charsets.UTF_8)
        val bytes = value.toByteArray(Charsets.UTF_8)
        val item = PointerByReference()
        try {
            val status = security.SecKeychainFindGenericPassword(null, serviceBytes.size, serviceBytes, name.size, name, null, null, item)
            if (status == -25300) {
                checkStatus(security.SecKeychainAddGenericPassword(null, serviceBytes.size, serviceBytes, name.size, name, bytes.size, bytes, null))
            } else {
                checkStatus(status)
                checkStatus(security.SecKeychainItemModifyAttributesAndData(item.value, null, bytes.size, bytes))
            }
        } finally {
            bytes.fill(0)
            item.value?.let(core::CFRelease)
        }
    }

    override fun remove(account: String) {
        val name = account.toByteArray(Charsets.UTF_8)
        val item = PointerByReference()
        val status = security.SecKeychainFindGenericPassword(null, serviceBytes.size, serviceBytes, name.size, name, null, null, item)
        if (status == -25300) return
        checkStatus(status)
        try { checkStatus(security.SecKeychainItemDelete(item.value)) } finally { core.CFRelease(item.value) }
    }

    private fun checkStatus(status: Int) { check(status == 0) { "Secure storage operation failed ($status)" } }
}
