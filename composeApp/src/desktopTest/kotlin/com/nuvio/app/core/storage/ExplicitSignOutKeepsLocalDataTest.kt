package com.nuvio.app.core.storage

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExplicitSignOutKeepsLocalDataTest {
    @Test
    fun explicit_sign_out_removes_secrets_and_keeps_local_files() {
        val root = Files.createTempDirectory("explicit-sign-out")
        try {
            Files.writeString(root.resolve("nuvio_library.properties"), "library=kept")
            Files.writeString(root.resolve("nuvio_downloads.properties"), "downloads=kept")
            Files.writeString(root.resolve("nuvio_watch_progress.properties"), "progress=kept")
            Files.writeString(root.resolve("nuvio_player_settings.properties"), "animeskip_client_id=player-secret\nvolume=3")
            Files.writeString(
                root.resolve("nuvio_debrid_settings.properties"),
                "debrid_torbox_api_key=secret-debrid\ndebrid_enabled=true\n",
            )
            Files.writeString(
                root.resolve("server_credentials.properties"),
                "credential.abc=secret-server\nprofile.1.connections=servers\n",
            )
            Files.writeString(root.resolve("nuvio_trakt_auth.properties"), "trakt_auth_payload_1=secret-trakt\n")
            Files.writeString(root.resolve("nuvio_addons.properties"), "installed_addon_urls_1=secret-addon\n")
            Files.writeString(root.resolve("nuvio_account_sessions.properties"), "sb-example-session=secret-session\n")
            val video = root.resolve("downloads/Film.mkv")
            Files.createDirectories(video.parent)
            Files.writeString(video, "the film")

            DesktopStorage.wipeCredentialsAndFiles(root, {}, { name ->
                val store = DesktopStorage.Store(
                    root.resolve("$name.properties"),
                    protectedKey = { key -> DesktopCredentials.protects(name, key) },
                )
                DesktopStorage.clearExplicitSecrets(store, name)
            })

            assertEquals("library=kept", Files.readString(root.resolve("nuvio_library.properties")))
            assertEquals("downloads=kept", Files.readString(root.resolve("nuvio_downloads.properties")))
            assertEquals("progress=kept", Files.readString(root.resolve("nuvio_watch_progress.properties")))
            assertTrue(Files.readString(root.resolve("nuvio_player_settings.properties")).contains("player-secret"))
            assertEquals("the film", Files.readString(video))
            assertFalse(Files.exists(root.resolve("nuvio_account_sessions.properties")))
            assertFalse(Files.exists(root.resolve("credential-wipe-pending")))
            assertFalse(read(root, "nuvio_trakt_auth.properties").contains("secret-trakt"))
            assertFalse(read(root, "nuvio_addons.properties").contains("secret-addon"))
            val debrid = read(root, "nuvio_debrid_settings.properties")
            assertFalse(debrid.contains("secret-debrid"))
            assertTrue(debrid.contains("debrid_enabled"))
            val servers = read(root, "server_credentials.properties")
            assertFalse(servers.contains("secret-server"))
            assertTrue(servers.contains("profile.1.connections"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun read(root: java.nio.file.Path, name: String): String =
        root.resolve(name).let { path -> if (Files.exists(path)) Files.readString(path) else "" }
}
