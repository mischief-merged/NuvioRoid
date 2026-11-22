package com.nuvio.app.core.storage

import android.content.Context

internal actual object PlatformLocalAccountDataCleaner {
    private val wholeSecretStores = listOf(
        "nuvio_trakt_auth",
        "nuvio_simkl_auth",
        "nuvio_mdblist_auth",
        "nuvio_addons",
    )

    private var appContext: Context? = null
    private var memoryOwner: String? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    actual fun wipe() {
        val context = appContext ?: return
        wholeSecretStores.forEach { name ->
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            if (!editor.commit()) error("Unable to clear saved secrets")
        }
        removeMatching("nuvio_debrid_settings") { key ->
            "api_key" in key || "pending_device_authorization" in key
        }
        removeMatching("nuvio_servers") { key -> key.startsWith("credential.") }
    }

    /** Records the owner. Does not move or delete files. A phone build has no dated folder. */
    fun prepareAccount(accountId: String): AccountDataPrep {
        val preferences = appContext?.getSharedPreferences("nuvio_account_owner", Context.MODE_PRIVATE)
        val recorded = preferences?.getString(OWNER_KEY, null)?.takeIf { it.isNotBlank() } ?: memoryOwner
        val result = when {
            recorded.isNullOrBlank() -> AccountDataPrep.Adopted
            recorded == accountId -> AccountDataPrep.Reused
            else -> AccountDataPrep.Moved
        }
        memoryOwner = accountId
        preferences?.edit()?.putString(OWNER_KEY, accountId)?.commit()
        return result
    }

    private fun removeMatching(name: String, predicate: (String) -> Boolean) {
        val context = appContext ?: return
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val editor = preferences.edit()
        preferences.all.keys.filter(predicate).forEach(editor::remove)
        if (!editor.commit()) error("Unable to clear saved secrets")
    }

    private const val OWNER_KEY = "account_id"
}

internal fun prepareAndroidAccountOwner(accountId: String): AccountDataPrep =
    PlatformLocalAccountDataCleaner.prepareAccount(accountId)
