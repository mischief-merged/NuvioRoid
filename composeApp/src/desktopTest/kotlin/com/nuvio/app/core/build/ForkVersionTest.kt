package com.nuvio.app.core.build

import kotlin.test.Test
import kotlin.test.assertEquals

class ForkVersionTest {
    @Test
    fun decodesTheCurrentRelease() {
        assertEquals("1.1.38.1", decodeForkVersion("1.1.3801"))
    }

    @Test
    fun followsForkReleaseAndUpstreamBumps() {
        assertEquals("1.1.38.2", decodeForkVersion("1.1.3802"))
        assertEquals("1.1.38.99", decodeForkVersion("1.1.3899"))
        assertEquals("1.1.39.1", decodeForkVersion("1.1.3901"))
        assertEquals("2.3.0.1", decodeForkVersion("2.3.1"))
    }

    @Test
    fun preservesSuffixesWithoutGuessingTheirMeaning() {
        listOf("1.1.3801-alpha", "1.1.3801+local", "0.1.38-alpha").forEach {
            assertEquals(it, decodeForkVersion(it))
        }
    }

    @Test
    fun preservesIncompleteAndAlreadyDecodedVersions() {
        listOf("1", "1.1", "1.1.38.1", "1.1.", ".1.3801").forEach {
            assertEquals(it, decodeForkVersion(it))
        }
    }

    @Test
    fun preservesReleaseZeroAndUnclampedMajor() {
        listOf("1.1.3800", "1.1.0", "0.1.3801").forEach {
            assertEquals(it, decodeForkVersion(it))
        }
    }

    @Test
    fun preservesNonNumericAndOutOfRangeInput() {
        listOf("", "dev", "x.1.3801", "1.x.3801", "1.1.x", "1.1.-1", "1.1.+3801",
            " 1.1.3801 ", "1.1.2147483648", "2147483648.1.3801", "1.2147483648.3801").forEach {
            assertEquals(it, decodeForkVersion(it))
        }
        assertEquals("1.1.21474836.47", decodeForkVersion("1.1.2147483647"))
    }

    @Test
    fun displayPolicyKeepsThePackageVersionForServices() {
        assertEquals(AppVersionConfig.DESKTOP_VERSION_NAME, AppVersionPolicy.packageVersionName)
        assertEquals(decodeForkVersion(AppVersionConfig.DESKTOP_VERSION_NAME), AppVersionPolicy.displayVersionName)
        assertEquals(AppVersionConfig.DESKTOP_VERSION_CODE, AppVersionPolicy.displayVersionCode)
        assertEquals(
            AppVersionConfig.VERSION_NAME.takeIf { it != AppVersionConfig.DESKTOP_VERSION_NAME },
            AppVersionPolicy.basedOnVersionName,
        )
    }
}
