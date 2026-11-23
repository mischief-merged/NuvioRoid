package com.nuvio.app.features.downloads

/**
 * False when the host of this URL is this computer or an address on a private or link-local network,
 * or when the URL has no host. Only the text of the URL is looked at: a name is not resolved here,
 * and a redirect the server answers with is not seen.
 */
internal fun String.hasPublicDownloadHost(): Boolean {
    val host = downloadUrlHost(this) ?: return false
    if (isTrustedHomeDownloadHost(host)) return true
    return when {
        host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") -> false
        ':' in host -> !isPrivateIpv6Literal(host)
        else -> parseIpv4Literal(host)?.let { !isPrivateIpv4(it) } ?: true
    }
}

private fun downloadUrlHost(url: String): String? {
    val authority = url.trim()
        .substringAfter("://", missingDelimiterValue = "")
        .takeWhile { it != '/' && it != '?' && it != '#' }
        .substringAfterLast('@')
    val host = if (authority.startsWith("[")) {
        authority.substringAfter('[').substringBefore(']')
    } else {
        authority.substringBeforeLast(':', missingDelimiterValue = authority)
    }
    return host.trim().trimEnd('.').lowercase().takeIf { it.isNotEmpty() }
}

private fun parseIpv4Literal(host: String): List<Int>? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    return parts.map { part ->
        part.takeIf { it.length in 1..3 && it.all(Char::isDigit) }?.toInt()?.takeIf { it <= 255 } ?: return null
    }
}

private fun isPrivateIpv4(octets: List<Int>): Boolean {
    val (first, second) = octets
    return first == 0 ||
        first == 10 ||
        first == 127 ||
        (first == 100 && second in 64..127) ||
        (first == 169 && second == 254) ||
        (first == 172 && second in 16..31) ||
        (first == 192 && second == 168)
}

private fun isPrivateIpv6Literal(host: String): Boolean {
    val address = host.substringBefore('%')
    if (address == "::" || address == "::1") return true
    // An IPv4 address written inside an IPv6 one, as in ::ffff:10.0.0.1.
    val embedded = address.substringAfterLast(':')
    if ('.' in embedded) return parseIpv4Literal(embedded)?.let(::isPrivateIpv4) ?: true
    val firstGroup = address.substringBefore(':').padStart(4, '0')
    return firstGroup.startsWith("fc") || firstGroup.startsWith("fd") ||
        firstGroup.startsWith("fe8") || firstGroup.startsWith("fe9") ||
        firstGroup.startsWith("fea") || firstGroup.startsWith("feb")
}

internal expect fun isTrustedHomeDownloadHost(host: String): Boolean
