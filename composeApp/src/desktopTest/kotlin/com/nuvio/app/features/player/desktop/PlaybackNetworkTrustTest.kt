package com.nuvio.app.features.player.desktop

import kotlin.test.*

class PlaybackNetworkTrustTest {
    @Test fun `an exception is remembered only for its router and can be removed`() {
        var saved = emptySet<String>()
        val choices = PlaybackTrustChoices({ saved }, { saved = it })
        val home = "network:192.168.1.1@00:11:22:33:44:55"
        val away = "network:192.168.1.1@00:11:22:33:44:66"
        assertFalse(choices.allows(home))
        choices.remember(home)
        assertTrue(choices.allows(home))
        assertFalse(choices.allows(away))
        assertTrue(PlaybackTrustChoices({ saved }, { saved = it }).allows(home))
        assertFalse(saved.single().contains("192.168"))
        choices.forget()
        assertFalse(choices.allows(home))
    }
    @Test fun `unknown networks and an IP without router hardware cannot persist exceptions`() {
        var saved = emptySet<String>()
        val choices = PlaybackTrustChoices({ saved }, { saved = it })
        for (network in listOf("", "session:1", "network:192.168.1.1")) {
            choices.remember(network)
            assertFalse(choices.allows(network))
        }
        assertTrue(saved.isEmpty())
    }
    @Test fun `line breaks are rejected before header whitespace is trimmed`() {
        for (bad in listOf("\r", "\n", "ok\r\nInjected: yes")) {
            assertFailsWith<IllegalArgumentException> { mapOf("X-Test" to bad).toHeaderLines() }
            assertFailsWith<IllegalArgumentException> { mapOf(bad to "ok").toHeaderLines() }
        }
        assertEquals(listOf("X-Test: good"), mapOf(" X-Test " to " good ").toHeaderLines())
    }
}
