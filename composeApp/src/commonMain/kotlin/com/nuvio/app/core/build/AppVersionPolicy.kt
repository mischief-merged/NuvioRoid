package com.nuvio.app.core.build

expect object AppVersionPolicy {
    /** The unmodified version used for packaging and existing service fields. */
    val packageVersionName: String
    val displayVersionName: String
    val displayVersionCode: Int
    val basedOnVersionName: String?
    val userAgentAppName: String
}
