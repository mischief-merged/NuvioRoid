package com.nuvio.app.core.network

import kotlin.test.BeforeTest
import kotlin.test.AfterTest

/** Existing transfer fixtures deliberately serve content from this computer. */
open class TrustedLocalServerTest {
    private var added = emptySet<String>()
    @BeforeTest fun trustFixtureHosts() {
        added = setOf("127.0.0.1", "localhost", "::1") - TrustedHomeServers.hosts()
        added.forEach { TrustedHomeServers.add(if (':' in it) "http://[$it]" else it) }
    }
    @AfterTest fun restoreFixtureTrust() { added.forEach(TrustedHomeServers::remove) }
}

/** Denial tests start without fixture approvals, even when a previous test process was interrupted. */
open class UntrustedLocalServerTest {
    private var saved = emptySet<String>()
    @BeforeTest fun clearFixtureTrust() {
        val hosts = setOf("127.0.0.1", "localhost", "::1")
        saved = TrustedHomeServers.hosts().intersect(hosts)
        hosts.forEach(TrustedHomeServers::remove)
    }
    @AfterTest fun restorePreviousFixtureTrust() {
        setOf("127.0.0.1", "localhost", "::1").forEach(TrustedHomeServers::remove)
        saved.forEach { TrustedHomeServers.add(if (':' in it) "http://[$it]" else it) }
    }
}
