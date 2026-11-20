package com.nuvio.app.features.servers

import kotlin.test.*

class ServerCredentialRecoveryTest {
    private val connection = ServerConnection("one", "emby", "Media", "https://media.example", "server", "remote", "local", "first")

    @Test fun unreadableConnectionsBlockChangesAndCanBeLoadedAfterRetry() {
        val values = mutableMapOf<String, String>()
        var locked = false
        val repository = ServerConnectionRepository(
            { key -> check(!locked); values[key] },
            { key, value -> if (value == null) values.remove(key) else values[key] = value },
            { 1 },
        )
        repository.store(connection, "saved-token")
        val original = values.toMap()
        locked = true
        assertFailsWith<IllegalStateException> { repository.onProfileChanged() }
        assertFailsWith<IllegalStateException> { repository.store(connection.copy(id = "two", credentialRef = "second"), "new-token") }
        assertEquals(original, values)
        locked = false
        repository.onProfileChanged()
        assertEquals(listOf(connection), repository.uiState.value.connections)
        assertEquals("saved-token", repository.session("one")!!.token)
    }

    @Test fun failedConnectionSaveDoesNotPublishOrDeleteSavedCredentials() {
        val values = mutableMapOf<String, String>()
        var denied = false
        val repository = ServerConnectionRepository(values::get, { key, value ->
            if (denied && key.endsWith(".connections")) error("unavailable")
            if (value == null) values.remove(key) else values[key] = value
        }, { 1 })
        repository.store(connection, "saved-token")
        denied = true
        assertFailsWith<IllegalStateException> { repository.remove("one") }
        assertEquals(listOf(connection), repository.uiState.value.connections)
        assertEquals("saved-token", values["credential.first"])
    }

    @Test fun missingCredentialCannotBecomeAnEmptySyncSnapshot() {
        val values = mutableMapOf<String, String>()
        val repository = ServerConnectionRepository(values::get, { key, value ->
            if (value == null) values.remove(key) else values[key] = value
        }, { 1 })
        repository.store(connection, "saved-token")
        values.remove("credential.first")
        repository.onProfileChanged()
        assertFailsWith<IllegalStateException> { repository.syncSnapshot(1) }
    }
}
