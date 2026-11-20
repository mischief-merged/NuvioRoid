package com.nuvio.app.core.storage

import java.nio.file.Path
import java.nio.file.Paths

/** Holds credentials in this process only, so a desktop test never opens the login keychain. */
internal class MemoryCredentialVault : CredentialVault {
    private val values = mutableMapOf<String, String>()
    override fun read(account: String): String? = synchronized(values) { values[account] }
    override fun write(account: String, value: String) { synchronized(values) { values[account] = value } }
    override fun remove(account: String) { synchronized(values) { values.remove(account) } }
}

/** The data folder for a desktop test. A missing folder is an error, never the app's own folder. */
internal class DesktopTestAppDataDirSource : AppDataDirSource {
    override fun path(): Path {
        val configured = System.getProperty("nuvio.storageDir")?.takeIf { it.isNotBlank() }
            ?: error("Desktop tests need their own storage folder")
        return Paths.get(configured)
    }
}

/** The credential store for a desktop test. Saved logins stay in memory. */
internal class DesktopTestCredentialVaultSource : CredentialVaultSource {
    override fun vault(): CredentialVault = MemoryCredentialVault()
}
