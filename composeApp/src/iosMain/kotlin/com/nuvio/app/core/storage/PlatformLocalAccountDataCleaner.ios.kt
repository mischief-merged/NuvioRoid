package com.nuvio.app.core.storage

import com.nuvio.app.features.mdblist.PlatformMdbListAuthPersistence
import platform.Foundation.NSUserDefaults

internal actual object PlatformLocalAccountDataCleaner {
    actual fun wipe() {
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.dictionaryRepresentation().keys.forEach { key ->
            val name = key as? String ?: return@forEach
            if (isLinkedSecret(name)) defaults.removeObjectForKey(name)
        }
        // Auth for this service is the account secret. The server list is a different service and stays.
        runCatching { PlatformMdbListAuthPersistence.clear() }
    }

    private fun isLinkedSecret(key: String): Boolean = when {
        "trakt_auth_payload" in key -> true
        "simkl_auth_metadata" in key -> true
        "mdblist_api_key" in key -> true
        key.startsWith("installed_manifest_") -> true
        "debrid" in key && ("api_key" in key || "pending_device_authorization" in key) -> true
        else -> false
    }
}
