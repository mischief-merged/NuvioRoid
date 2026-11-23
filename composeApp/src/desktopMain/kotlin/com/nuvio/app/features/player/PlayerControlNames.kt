package com.nuvio.app.features.player

/**
 * A player button needs a name a screen reader can say.
 * An empty aria-label replaces the visible words, so it counts as unnamed.
 * A button hidden from assistive tech is skipped.
 */
internal fun unnamedPlayerControls(html: String): List<String> {
    val problems = mutableListOf<String>()
    var index = 0
    while (true) {
        val start = html.indexOf("<button", index, ignoreCase = true)
        if (start < 0) break
        val tagEnd = html.indexOf('>', start)
        if (tagEnd < 0) break
        val openTag = html.substring(start, tagEnd + 1)
        val close = html.indexOf("</button>", tagEnd, ignoreCase = true)
        if (close < 0) break
        if (!openTag.contains("aria-hidden=\"true\"", ignoreCase = true)) {
            val label = htmlAttribute(openTag, "aria-label")
            val visible = visibleButtonText(html.substring(tagEnd + 1, close))
            val named = if (label != null) {
                label.isNotBlank()
            } else {
                visible.length >= 2 && visible.any { it.isLetter() }
            }
            if (!named) {
                problems += htmlAttribute(openTag, "id")?.takeIf { it.isNotBlank() }
                    ?: visible.ifBlank { "button@$start" }
            }
        }
        index = close + "</button>".length
    }
    return problems
}

private fun htmlAttribute(tag: String, name: String): String? {
    val match = Regex(
        """\b${Regex.escape(name)}\s*=\s*"([^"]*)"""",
        RegexOption.IGNORE_CASE,
    ).find(tag)
    return match?.groupValues?.get(1)
}

/**
 * Bold, outline, shadow and the font row must say their current value.
 * A fixed name that leaves the value out hides it from a screen reader.
 */
internal fun subtitleControlsThatHideTheirValue(html: String): List<String> {
    val problems = mutableListOf<String>()
    listOf("boldToggle", "outlineToggle", "shadowToggle", "subtitleFontRow").forEach { id ->
        val idAt = html.indexOf("id=\"$id\"")
        if (idAt < 0) {
            problems += id
            return@forEach
        }
        val openStart = html.lastIndexOf("<button", idAt)
        val openEnd = html.indexOf('>', idAt)
        val close = html.indexOf("</button>", openEnd)
        if (openStart < 0 || openEnd < 0 || close < 0) {
            problems += id
            return@forEach
        }
        val openTag = html.substring(openStart, openEnd + 1)
        val value = visibleButtonText(html.substring(openEnd + 1, close))
            .replace("›", "")
            .trim()
        val label = htmlAttribute(openTag, "aria-label").orEmpty()
        if (value.isNotEmpty() && !label.contains(value)) problems += id
        if (id != "subtitleFontRow" && htmlAttribute(openTag, "aria-pressed") == null) {
            problems += "$id-pressed"
        }
    }
    return problems
}

/** The style panel must write the pressed state and the current name when it draws. */
internal fun subtitleStyleRenderOmitsValue(script: String): List<String> {
    val start = script.indexOf("const renderSubtitleStylePanel")
    if (start < 0) return listOf("renderSubtitleStylePanel")
    val next = script.indexOf("const isBuiltInFont", start)
    val panel = script.substring(start, if (next < 0) script.length else next)
    val missing = mutableListOf<String>()
    listOf("boldToggle", "outlineToggle", "shadowToggle").forEach { id ->
        if (!panel.contains("$id.setAttribute(\"aria-pressed\"")) missing += id
        if (!panel.contains("$id.setAttribute(\"aria-label\"")) missing += "$id-label"
    }
    if (!panel.contains("subtitleFontRow.setAttribute(\"aria-label\"")) missing += "subtitleFontRow"
    return missing
}

private fun visibleButtonText(inner: String): String =
    inner
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&")
        .replace(Regex("\\s+"), " ")
        .trim()
