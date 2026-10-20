package com.vpnapp.data

/** Accept one copied link, including common chat/Markdown wrappers, without altering its token. */
object LinkImportInput {
    fun normalize(input: String): String {
        var value = input.trim { it.isWhitespace() || it in "\uFEFF\u200E\u200F\u202A\u202B\u202C\u2066\u2067\u2068\u2069" }
        Regex("^\\[[^\\r\\n]*]\\(([^\\s]+)\\)$").matchEntire(value)?.let { value = it.groupValues[1] }
        if (value.startsWith('<') && value.endsWith('>')) value = value.substring(1, value.length - 1)
        if (value.startsWith('`') && value.endsWith('`')) value = value.removeSurrounding("`")
        return value.trim()
    }
}
