package com.nuvio.app.features.player.desktop

import com.nuvio.app.core.storage.DesktopStorage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Position and size are saved together, once, after the window has stayed still.
 * A drag keeps moving the quiet timer, so it does not write until the drag ends.
 */
class WindowGeometryWriteTest {
    @Test
    fun one_snapshot_is_one_write_and_the_same_snapshot_is_not_written_again() {
        withStore { store, persists ->
            val geometry = DesktopWindowGeometry(10f, 20f, 800f, 600f)

            DesktopWindowModeStorage.saveSnapshot(wasMaximized = false, geometry = geometry, target = store)

            assertEquals(1, persists())
            assertEquals(false to geometry, DesktopWindowModeStorage.readSnapshot(store))

            DesktopWindowModeStorage.saveSnapshot(wasMaximized = false, geometry = geometry, target = store)

            assertEquals(1, persists(), "an unchanged window was written again")

            DesktopWindowModeStorage.saveSnapshot(wasMaximized = null, geometry = geometry.copy(x = 11f), target = store)

            assertEquals(2, persists())
            assertEquals(false to geometry.copy(x = 11f), DesktopWindowModeStorage.readSnapshot(store))
        }
    }

    @Test
    fun a_five_second_drag_writes_once_after_the_window_stops() {
        withStore { store, persists ->
            val writes = WindowGeometryWrites { maximized, geometry ->
                DesktopWindowModeStorage.saveSnapshot(maximized, geometry, store)
            }
            var t = 0L
            while (t < 5_000) {
                writes.note(wasMaximized = false, geometry = geometry(t), now = t)
                assertFalse(writes.flushIfDue(t))
                t += 16
            }
            val end = 5_000L
            writes.note(wasMaximized = false, geometry = geometry(end), now = end)
            assertFalse(writes.flushIfDue(end))
            assertFalse(writes.flushIfDue(end + 299))

            assertEquals(0, writes.writes)
            assertEquals(0, persists())
            assertTrue(writes.flushIfDue(end + 300))

            assertEquals(1, writes.writes)
            assertEquals(1, persists())
            assertEquals(false to geometry(end), DesktopWindowModeStorage.readSnapshot(store))
            assertFalse(writes.flushIfDue(end + 10_000))
            assertEquals(1, persists())
        }
    }

    @Test
    fun closing_during_a_drag_writes_the_last_position_once() {
        withStore { store, persists ->
            val writes = WindowGeometryWrites { maximized, geometry ->
                DesktopWindowModeStorage.saveSnapshot(maximized, geometry, store)
            }
            writes.note(wasMaximized = false, geometry = geometry(0), now = 0)
            writes.note(wasMaximized = true, geometry = geometry(100), now = 100)

            assertEquals(0, persists())
            writes.flushNow()

            assertEquals(1, writes.writes)
            assertEquals(1, persists())
            assertEquals(true to geometry(100), DesktopWindowModeStorage.readSnapshot(store))
            assertFalse(writes.flushIfDue(100_000))
            assertEquals(1, persists())
        }
    }

    @Test
    fun two_separate_drags_write_twice() {
        withStore { store, persists ->
            val writes = WindowGeometryWrites { maximized, geometry ->
                DesktopWindowModeStorage.saveSnapshot(maximized, geometry, store)
            }
            writes.note(wasMaximized = false, geometry = geometry(0), now = 0)
            assertFalse(writes.flushIfDue(299))
            assertTrue(writes.flushIfDue(300))
            writes.note(wasMaximized = false, geometry = geometry(1_000), now = 10_000)
            assertFalse(writes.flushIfDue(10_299))
            assertTrue(writes.flushIfDue(10_300))

            assertEquals(2, writes.writes)
            assertEquals(2, persists())
            assertEquals(geometry(1_000), DesktopWindowModeStorage.readSnapshot(store).second)
        }
    }

    private fun geometry(x: Long) = DesktopWindowGeometry(x = x.toFloat(), y = 40f, width = 1280f, height = 720f)

    private fun withStore(test: (DesktopStorage.Store, () -> Int) -> Unit) {
        val directory = Files.createTempDirectory("window-state")
        try {
            var persists = 0
            val store = DesktopStorage.Store(directory.resolve("nuvio_window_state.properties")) { properties, output ->
                persists++
                properties.store(output, "window")
            }
            test(store) { persists }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
