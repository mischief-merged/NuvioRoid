package com.nuvio.app.core.build

/** Updated when syncing a new upstream desktop release, not when bumping a fork release. */
internal const val UPSTREAM_DESKTOP_VERSION = "0.1.38"

/** Decodes major.minor.(patch * 100 + release); unknown formats stay readable as supplied. */
internal fun decodeForkVersion(packageVersion: String): String {
    val parts = packageVersion.split('.')
    if (parts.size != 3 || parts.any { part -> part.isEmpty() || part.any { it !in '0'..'9' } }) {
        return packageVersion
    }
    val numbers = parts.map { it.toIntOrNull() ?: return packageVersion }
    val release = numbers[2] % 100
    if (numbers[0] < 1 || release == 0) return packageVersion
    return "${numbers[0]}.${numbers[1]}.${numbers[2] / 100}.$release"
}
