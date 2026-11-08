// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.db

import app.mixu.graph.Locator

/**
 * Deterministic JSON serializer and parser for [Locator] instances stored in SQLite.
 */
object LocatorJsonCodec {
    fun encode(locator: Locator): String {
        val sb = StringBuilder(128)
        sb.append("{")
        sb.append("\"href\":\"").append(escapeJsonString(locator.href)).append("\",")
        sb.append("\"type\":\"").append(escapeJsonString(locator.type)).append("\"")
        if (locator.title != null) {
            sb.append(",\"title\":\"").append(escapeJsonString(locator.title!!)).append("\"")
        }
        if (locator.progression != null) {
            sb.append(",\"progression\":").append(locator.progression)
        }
        if (locator.totalProgression != null) {
            sb.append(",\"total_progression\":").append(locator.totalProgression)
        }
        if (locator.textBefore != null) {
            sb
                .append(",\"text_before\":\"")
                .append(escapeJsonString(locator.textBefore!!))
                .append("\"")
        }
        if (locator.textAfter != null) {
            sb
                .append(",\"text_after\":\"")
                .append(escapeJsonString(locator.textAfter!!))
                .append("\"")
        }
        sb.append("}")
        return sb.toString()
    }

    fun decode(json: String): Locator {
        val fields = parseFlatJsonObject(json)
        val href =
            fields["href"]
                ?: throw IllegalArgumentException("Missing required 'href' in Locator JSON")
        val type =
            fields["type"]
                ?: throw IllegalArgumentException("Missing required 'type' in Locator JSON")
        val title = fields["title"]
        val progression = fields["progression"]?.toDoubleOrNull()
        val totalProgression =
            (fields["total_progression"] ?: fields["totalProgression"])?.toDoubleOrNull()
        val textBefore = fields["text_before"] ?: fields["textBefore"]
        val textAfter = fields["text_after"] ?: fields["textAfter"]

        return Locator(
            href = href,
            type = type,
            title = title,
            progression = progression,
            totalProgression = totalProgression,
            textBefore = textBefore,
            textAfter = textAfter,
        )
    }

    internal fun escapeJsonString(value: String): String {
        val sb = StringBuilder(value.length + 16)
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append(String.format("\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        return sb.toString()
    }

    internal fun parseFlatJsonObject(json: String): Map<String, String?> {
        val trimmed = json.trim()
        require(trimmed.startsWith("{") && trimmed.endsWith("}")) {
            "Invalid JSON object wrapper"
        }
        val result = linkedMapOf<String, String?>()
        var idx = 1
        val end = trimmed.length - 1

        fun skipWhitespace() {
            while (idx < end && trimmed[idx].isWhitespace()) {
                idx++
            }
        }

        fun readQuotedString(): String {
            require(idx < end && trimmed[idx] == '"') {
                "Expected '\"' at index $idx"
            }
            idx++ // skip opening quote
            val sb = StringBuilder()
            while (idx < end) {
                val ch = trimmed[idx++]
                if (ch == '"') {
                    return sb.toString()
                }
                if (ch == '\\') {
                    require(idx < end) { "Unexpected end of escape sequence" }
                    when (val esc = trimmed[idx++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            require(idx + 4 <= end) { "Incomplete unicode escape" }
                            val hex = trimmed.substring(idx, idx + 4)
                            sb.append(hex.toInt(16).toChar())
                            idx += 4
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(ch)
                }
            }
            throw IllegalArgumentException("Unterminated JSON string literal")
        }

        skipWhitespace()
        while (idx < end) {
            skipWhitespace()
            if (idx >= end) break
            val key = readQuotedString()
            skipWhitespace()
            require(idx < end && trimmed[idx] == ':') {
                "Expected ':' after key '$key'"
            }
            idx++ // skip ':'
            skipWhitespace()
            require(idx < end) { "Expected value for key '$key'" }

            val value: String? =
                if (trimmed[idx] == '"') {
                    readQuotedString()
                } else {
                    val tokenStart = idx
                    while (idx < end && trimmed[idx] != ',' && !trimmed[idx].isWhitespace()) {
                        idx++
                    }
                    val rawToken = trimmed.substring(tokenStart, idx)
                    if (rawToken == "null") null else rawToken
                }

            result[key] = value
            skipWhitespace()
            if (idx < end && trimmed[idx] == ',') {
                idx++
            }
        }
        return result
    }
}
