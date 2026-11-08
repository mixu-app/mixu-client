// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.logging

import java.security.MessageDigest
import java.time.Instant

/**
 * Severity levels supported by the structured logger.
 */
enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

/**
 * An immutable structured log event record before serialization.
 */
data class LogRecord(
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val attributes: Map<String, Any?> = emptyMap(),
) {
    /**
     * Serializes this record to a single-line JSON string.
     */
    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"timestamp\":\"").append(escapeJson(timestamp)).append("\",")
        sb.append("\"level\":\"").append(level.name).append("\",")
        sb.append("\"tag\":\"").append(escapeJson(tag)).append("\",")
        sb.append("\"message\":\"").append(escapeJson(message)).append("\",")
        sb.append("\"attributes\":").append(formatValueAsJson(attributes))
        sb.append("}")
        return sb.toString()
    }

    companion object {
        internal fun escapeJson(value: String): String {
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

        internal fun formatValueAsJson(value: Any?): String =
            when (value) {
                null -> "null"
                is Number, is Boolean -> value.toString()
                is String -> "\"${escapeJson(value)}\""
                is Map<*, *> -> {
                    val entries =
                        value.entries.map { (k, v) ->
                            "\"${escapeJson(k.toString())}\":${formatValueAsJson(v)}"
                        }
                    "{${entries.joinToString(",")}}"
                }
                is Iterable<*> -> {
                    val items = value.map { formatValueAsJson(it) }
                    "[${items.joinToString(",")}]"
                }
                is Array<*> -> {
                    val items = value.map { formatValueAsJson(it) }
                    "[${items.joinToString(",")}]"
                }
                else -> "\"${escapeJson(value.toString())}\""
            }
    }
}

/**
 * Functional interface for receiving serialized log records.
 */
fun interface LogSink {
    fun emit(
        record: LogRecord,
        formattedJson: String,
    )
}

/**
 * Privacy and Content Firewall protecting reader logs from narrative text leakage,
 * search queries, user annotations, and sensitive credentials.
 */
object ContentFirewall {
    const val REDACTED_CONTENT = "[REDACTED_CONTENT]"
    const val REDACTED_QUERY = "[REDACTED_QUERY]"
    const val REDACTED_ANNOTATION = "[REDACTED_ANNOTATION]"
    const val REDACTED_SECRET = "[REDACTED_SECRET]"

    private val SENSITIVE_KEY_PATTERNS =
        setOf(
            "title",
            "book_title",
            "booktitle",
            "chapter_title",
            "chaptertitle",
            "chapter_text",
            "chaptertext",
            "text",
            "passage",
            "content",
            "body",
            "excerpt",
            "quote",
            "raw_text",
            "query",
            "search_query",
            "searchquery",
            "filter_query",
            "annotation",
            "note",
            "user_note",
            "usernote",
            "highlight",
            "comment",
            "password",
            "secret",
            "token",
            "credential",
            "bearer",
            "email",
        )

    private val BOOK_REFERENCE_KEYS =
        setOf(
            "book_id",
            "bookid",
            "edition_id",
            "editionid",
            "work_id",
            "workid",
            "book_ref",
            "book",
        )

    private val UUID_REGEX =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val SHA256_HEX_REGEX = Regex("^[0-9a-fA-F]{64}$")

    /**
     * Determines whether a book identifier is an opaque token (URN, UUID, or hash)
     * rather than human-readable text.
     */
    fun isOpaqueIdentifier(value: String): Boolean {
        if (value.startsWith("urn:book:") ||
            value.startsWith("urn:isbn:") ||
            value.startsWith("urn:mixu:") ||
            value.startsWith("sha256:")
        ) {
            return true
        }
        if (UUID_REGEX.matches(value)) {
            return true
        }
        if (SHA256_HEX_REGEX.matches(value)) {
            return true
        }
        return false
    }

    /**
     * Converts a book reference into an opaque token.
     * Preserves pre-existing opaque URNs and hashes; converts literary titles into SHA-256 hashes.
     */
    fun anonymizeBookReference(raw: String): String {
        if (isOpaqueIdentifier(raw)) {
            return raw
        }
        return "sha256:" + computeSha256(raw)
    }

    /**
     * Computes a SHA-256 hex digest for arbitrary input text.
     */
    fun computeSha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Sanitizes an attribute map by redacting sensitive keys and anonymizing book references.
     * Returns the sanitized map together with the set of sensitive literal values found.
     */
    fun sanitizeAttributes(attributes: Map<String, Any?>): Pair<Map<String, Any?>, Set<String>> {
        val sanitized = mutableMapOf<String, Any?>()
        val sensitiveValuesFound = mutableSetOf<String>()

        for ((key, value) in attributes) {
            val lowerKey = key.lowercase()
            when {
                BOOK_REFERENCE_KEYS.contains(lowerKey) -> {
                    if (value is String) {
                        if (!isOpaqueIdentifier(value)) {
                            sensitiveValuesFound.add(value)
                            sanitized[key] = anonymizeBookReference(value)
                        } else {
                            sanitized[key] = value
                        }
                    } else {
                        sanitized[key] = value
                    }
                }

                SENSITIVE_KEY_PATTERNS.contains(lowerKey) -> {
                    if (value is String && value.isNotBlank()) {
                        sensitiveValuesFound.add(value)
                    }
                    sanitized[key] =
                        when {
                            lowerKey.contains("query") -> REDACTED_QUERY
                            lowerKey.contains("note") || lowerKey.contains("annotation") ->
                                REDACTED_ANNOTATION
                            lowerKey.contains("token") ||
                                lowerKey.contains("secret") ||
                                lowerKey.contains("password") ||
                                lowerKey.contains("credential") -> REDACTED_SECRET
                            else -> REDACTED_CONTENT
                        }
                }

                value is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                        val (nestedSanitized, nestedSensitive) =
                        sanitizeAttributes(value as Map<String, Any?>)
                    sensitiveValuesFound.addAll(nestedSensitive)
                    sanitized[key] = nestedSanitized
                }

                else -> {
                    sanitized[key] = value
                }
            }
        }
        return Pair(sanitized, sensitiveValuesFound)
    }

    /**
     * Redacts known sensitive strings and common literary patterns from arbitrary message text.
     */
    fun sanitizeMessage(
        message: String,
        sensitiveLiterals: Set<String> = emptySet(),
    ): String {
        var sanitized = message
        for (literal in sensitiveLiterals) {
            if (literal.isNotBlank()) {
                sanitized = sanitized.replace(literal, REDACTED_CONTENT)
            }
        }
        // Redact patterns like query=..., chapter: ..., title: ...
        sanitized =
            sanitized.replace(
                Regex("(?i)query\\s*=\\s*['\"]?[^'\",;\\n]+['\"]?"),
                "query=$REDACTED_QUERY",
            )
        sanitized =
            sanitized.replace(
                Regex("(?i)\\bchapter\\s+\\d+[^'\",;\\n]*"),
                "chapter:$REDACTED_CONTENT",
            )
        sanitized =
            sanitized.replace(
                Regex("(?i)\\bchapter\\s*:\\s*['\"]?[^'\",;\\n]+['\"]?"),
                "chapter:$REDACTED_CONTENT",
            )
        sanitized =
            sanitized.replace(
                Regex("(?i)title\\s*:\\s*['\"]?[^'\",;\\n]+['\"]?"),
                "title:$REDACTED_CONTENT",
            )
        return sanitized
    }
}

/**
 * Structured logger enforcing PII sanitization and zero-leakage content firewall.
 */
class StructuredLogger(
    val tag: String = "MixuClient",
    var minLevel: LogLevel = LogLevel.INFO,
    sinks: List<LogSink> = emptyList(),
) {
    private val sinks: MutableList<LogSink> = sinks.toMutableList()

    fun addSink(sink: LogSink) {
        sinks.add(sink)
    }

    fun clearSinks() {
        sinks.clear()
    }

    /**
     * Emits a structured log event, applying content firewall redaction.
     */
    fun log(
        level: LogLevel,
        message: String,
        attributes: Map<String, Any?> = emptyMap(),
        throwable: Throwable? = null,
        extraSensitiveLiterals: Set<String> = emptySet(),
    ) {
        if (level.ordinal < minLevel.ordinal) {
            return
        }

        val (sanitizedAttrs, sensitiveValues) = ContentFirewall.sanitizeAttributes(attributes)
        val combinedSensitive = sensitiveValues + extraSensitiveLiterals
        val finalAttrs = sanitizedAttrs.toMutableMap()

        if (throwable != null) {
            finalAttrs["error_class"] = throwable.javaClass.simpleName
            val rawErrorMessage = throwable.message ?: "Unknown error"
            finalAttrs["error_message"] =
                ContentFirewall.sanitizeMessage(rawErrorMessage, combinedSensitive)
        }

        val sanitizedMessage = ContentFirewall.sanitizeMessage(message, combinedSensitive)

        val record =
            LogRecord(
                timestamp = Instant.now().toString(),
                level = level,
                tag = tag,
                message = sanitizedMessage,
                attributes = finalAttrs,
            )

        val json = record.toJson()
        if (sinks.isEmpty()) {
            println(json)
        } else {
            for (sink in sinks) {
                sink.emit(record, json)
            }
        }
    }

    fun debug(
        message: String,
        attributes: Map<String, Any?> = emptyMap(),
    ) {
        log(LogLevel.DEBUG, message, attributes)
    }

    fun info(
        message: String,
        attributes: Map<String, Any?> = emptyMap(),
    ) {
        log(LogLevel.INFO, message, attributes)
    }

    fun warn(
        message: String,
        attributes: Map<String, Any?> = emptyMap(),
        throwable: Throwable? = null,
    ) {
        log(LogLevel.WARN, message, attributes, throwable)
    }

    fun error(
        message: String,
        attributes: Map<String, Any?> = emptyMap(),
        throwable: Throwable? = null,
    ) {
        log(LogLevel.ERROR, message, attributes, throwable)
    }

    /**
     * Logs a reading progress event with strict opaque book identifier enforcement.
     */
    fun logReadingEvent(
        bookReference: String,
        chapterIndex: Int,
        progressPercent: Int,
        extraAttributes: Map<String, Any?> = emptyMap(),
    ) {
        val attrs = extraAttributes.toMutableMap()
        attrs["book_id"] = bookReference
        attrs["chapter_index"] = chapterIndex
        attrs["progress_percent"] = progressPercent
        val sensitive =
            if (!ContentFirewall.isOpaqueIdentifier(bookReference)) {
                setOf(bookReference)
            } else {
                emptySet()
            }
        log(
            level = LogLevel.INFO,
            message = "Reading progress updated",
            attributes = attrs,
            extraSensitiveLiterals = sensitive,
        )
    }

    /**
     * Logs a book open event with strict opaque book identifier enforcement.
     */
    fun logBookOpen(
        bookReference: String,
        extraAttributes: Map<String, Any?> = emptyMap(),
    ) {
        val attrs = extraAttributes.toMutableMap()
        attrs["book_id"] = bookReference
        val sensitive =
            if (!ContentFirewall.isOpaqueIdentifier(bookReference)) {
                setOf(bookReference)
            } else {
                emptySet()
            }
        log(
            level = LogLevel.INFO,
            message = "Book opened",
            attributes = attrs,
            extraSensitiveLiterals = sensitive,
        )
    }

    /**
     * Logs a search event recording metadata metrics without user query text.
     */
    fun logSearchEvent(
        resultsCount: Int,
        queryLength: Int,
        extraAttributes: Map<String, Any?> = emptyMap(),
    ) {
        val attrs = extraAttributes.toMutableMap()
        attrs["results_count"] = resultsCount
        attrs["query_length"] = queryLength
        info(message = "Library search performed", attributes = attrs)
    }

    /**
     * Logs an error event with automated book reference sanitization and sensitive text redaction.
     */
    fun logError(
        message: String,
        throwable: Throwable? = null,
        bookReference: String? = null,
        attributes: Map<String, Any?> = emptyMap(),
    ) {
        val attrs = attributes.toMutableMap()
        val sensitive = mutableSetOf<String>()
        if (bookReference != null) {
            attrs["book_id"] = bookReference
            if (!ContentFirewall.isOpaqueIdentifier(bookReference)) {
                sensitive.add(bookReference)
            }
        }
        log(
            level = LogLevel.ERROR,
            message = message,
            attributes = attrs,
            throwable = throwable,
            extraSensitiveLiterals = sensitive,
        )
    }

    companion object {
        val default: StructuredLogger = StructuredLogger()

        fun create(
            tag: String,
            minLevel: LogLevel = LogLevel.INFO,
        ): StructuredLogger = StructuredLogger(tag = tag, minLevel = minLevel)
    }
}
