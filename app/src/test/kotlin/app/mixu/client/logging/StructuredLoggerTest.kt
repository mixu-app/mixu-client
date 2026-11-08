// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.logging

import app.mixu.client.auth.UserSession
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StructuredLoggerTest {
    @Test
    fun userSessionInitializesInGuestModeByDefault() {
        val session = UserSession()
        assertTrue(session.isGuest, "Default session must be guest")
        assertFalse(session.isAuthenticated, "Guest session must not be authenticated")
        assertEquals(UserSession.DEFAULT_DISPLAY_NAME, session.displayName)
        assertTrue(session.anonymousId.isNotBlank(), "Guest anonymousId must be generated")

        val defaultSession = UserSession.DEFAULT
        assertTrue(defaultSession.isGuest)
        assertEquals("guest-anonymous-session", defaultSession.anonymousId)
        assertEquals("Guest", defaultSession.displayName)
    }

    @Test
    fun userSessionFactoryMethodsAndMapSerialization() {
        val guest = UserSession.createGuest(anonymousId = "local-anon-123", displayName = "Reader")
        assertTrue(guest.isGuest)
        assertEquals("local-anon-123", guest.anonymousId)
        assertEquals("Reader", guest.displayName)

        val map = guest.toMap()
        val restored = UserSession.fromMap(map)
        assertEquals(guest, restored)

        val authenticated =
            UserSession.createAuthenticated(
                userId = "usr-8899",
                displayName = "Alice",
            )
        assertFalse(authenticated.isGuest)
        assertTrue(authenticated.isAuthenticated)
        assertEquals("usr-8899", authenticated.anonymousId)
        assertEquals("Alice", authenticated.displayName)
    }

    @Test
    fun acceptanceCriteria_logOutputContainsNoTitleOrChapterText() {
        val capturedLines = mutableListOf<String>()
        val capturedRecords = mutableListOf<LogRecord>()
        val logger =
            StructuredLogger(
                tag = "TestAuditor",
                minLevel = LogLevel.DEBUG,
                sinks =
                    listOf(
                        LogSink { record, json ->
                            capturedRecords.add(record)
                            capturedLines.add(json)
                        },
                    ),
            )

        val bookTitle1 = "The Murder of Roger Ackroyd"
        val chapterTitle1 = "Chapter 1: Thoughts on Mrs Ferrars"
        val chapterPassage1 = "Mrs Ferrars died on the night of the 16th-17th September."

        val bookTitle2 = "The Mysterious Affair at Styles"
        val chapterTitle2 = "Chapter 2: The 16th and 17th of July"
        val chapterPassage2 =
            "I had been invalided home from the front and after spending some months..."

        val bookTitle3 = "The Hound of the Baskervilles"
        val chapterTitle3 = "Chapter 3: The Problem"
        val chapterPassage3 = "Mr Holmes we have in our possession the manuscript dated 1742."

        val searchQuery = "secret poisoned dagger in locked study"
        val userAnnotation = "Hypothesis: Dr Sheppard is the narrator and mastermind"

        // 1. Reading event with raw title and chapter text passed in
        logger.logReadingEvent(
            bookReference = bookTitle1,
            chapterIndex = 1,
            progressPercent = 14,
            extraAttributes =
                mapOf(
                    "title" to bookTitle1,
                    "chapter_title" to chapterTitle1,
                    "chapter_text" to chapterPassage1,
                    "annotation" to userAnnotation,
                ),
        )

        // 2. Book open event with raw title and passage
        logger.logBookOpen(
            bookReference = bookTitle2,
            extraAttributes =
                mapOf(
                    "book_title" to bookTitle2,
                    "passage" to chapterPassage2,
                ),
        )

        // 3. Search event with query
        logger.logSearchEvent(
            resultsCount = 3,
            queryLength = searchQuery.length,
            extraAttributes =
                mapOf(
                    "search_query" to searchQuery,
                ),
        )

        // 4. Error event with book title and chapter text in message, exception, and attributes
        val failureException =
            IllegalStateException("Parser failure inside $bookTitle3: $chapterTitle3")
        logger.logError(
            message = "Failed to parse passage in $bookTitle3: $chapterPassage3",
            throwable = failureException,
            bookReference = bookTitle3,
            attributes =
                mapOf(
                    "body" to chapterPassage3,
                    "note" to userAnnotation,
                ),
        )

        // Combine all output to verify acceptance criteria across every log line
        val combinedOutput = capturedLines.joinToString("\n")

        // Assert strictly that forbidden texts NEVER appear in log output
        assertFalse(
            combinedOutput.contains(bookTitle1),
            "Log output must NOT contain book title: $bookTitle1",
        )
        assertFalse(
            combinedOutput.contains(chapterTitle1),
            "Log output must NOT contain chapter title: $chapterTitle1",
        )
        assertFalse(
            combinedOutput.contains(chapterPassage1),
            "Log output must NOT contain chapter text: $chapterPassage1",
        )

        assertFalse(
            combinedOutput.contains(bookTitle2),
            "Log output must NOT contain book title: $bookTitle2",
        )
        assertFalse(
            combinedOutput.contains(chapterTitle2),
            "Log output must NOT contain chapter title: $chapterTitle2",
        )
        assertFalse(
            combinedOutput.contains(chapterPassage2),
            "Log output must NOT contain chapter text: $chapterPassage2",
        )

        assertFalse(
            combinedOutput.contains(bookTitle3),
            "Log output must NOT contain book title: $bookTitle3",
        )
        assertFalse(
            combinedOutput.contains(chapterTitle3),
            "Log output must NOT contain chapter title: $chapterTitle3",
        )
        assertFalse(
            combinedOutput.contains(chapterPassage3),
            "Log output must NOT contain chapter text: $chapterPassage3",
        )

        assertFalse(
            combinedOutput.contains(searchQuery),
            "Log output must NOT contain user search query: $searchQuery",
        )
        assertFalse(
            combinedOutput.contains(userAnnotation),
            "Log output must NOT contain user annotation: $userAnnotation",
        )

        // Verify that opaque SHA-256 book hashes ARE present instead of raw titles
        val sha1 = ContentFirewall.computeSha256(bookTitle1)
        assertTrue(
            combinedOutput.contains("sha256:$sha1"),
            "Log output should use opaque SHA-256 identifier for book 1",
        )

        val sha2 = ContentFirewall.computeSha256(bookTitle2)
        assertTrue(
            combinedOutput.contains("sha256:$sha2"),
            "Log output should use opaque SHA-256 identifier for book 2",
        )

        val sha3 = ContentFirewall.computeSha256(bookTitle3)
        assertTrue(
            combinedOutput.contains("sha256:$sha3"),
            "Log output should use opaque SHA-256 identifier for book 3",
        )
    }

    @Test
    fun opaqueIdentifiersArePreservedWithoutDoubleHashing() {
        val opaqueUrn = "urn:book:978-0-00-711931-8"
        assertEquals(opaqueUrn, ContentFirewall.anonymizeBookReference(opaqueUrn))

        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        assertEquals(uuid, ContentFirewall.anonymizeBookReference(uuid))

        val rawTitle = "Murder on the Orient Express"
        val anonymized = ContentFirewall.anonymizeBookReference(rawTitle)
        assertTrue(anonymized.startsWith("sha256:"))
        assertFalse(anonymized.contains(rawTitle))
    }

    @Test
    fun levelFilteringRespectsConfiguredThreshold() {
        val captured = mutableListOf<LogLevel>()
        val logger =
            StructuredLogger(
                tag = "FilterTest",
                minLevel = LogLevel.WARN,
                sinks = listOf(LogSink { record, _ -> captured.add(record.level) }),
            )

        logger.debug("Debug message")
        logger.info("Info message")
        assertEquals(0, captured.size, "DEBUG and INFO should be filtered out")

        logger.warn("Warning message")
        logger.error("Error message")
        assertEquals(listOf(LogLevel.WARN, LogLevel.ERROR), captured)
    }

    @Test
    fun structuredJsonFormattingMatchesContract() {
        val records = mutableListOf<LogRecord>()
        val jsonLines = mutableListOf<String>()
        val logger =
            StructuredLogger(
                tag = "FormatTest",
                minLevel = LogLevel.INFO,
                sinks =
                    listOf(
                        LogSink { record, json ->
                            records.add(record)
                            jsonLines.add(json)
                        },
                    ),
            )

        logger.info(
            message = "Config applied",
            attributes =
                mapOf(
                    "theme" to "LIGHT",
                    "font_scale" to 1.25,
                    "margin_dp" to 20,
                    "eink_active" to false,
                    "quote_test" to "Hello \"Mixu\" \\ world\nnewline",
                ),
        )

        assertEquals(1, jsonLines.size)
        val line = jsonLines.first()

        assertTrue(line.startsWith("{") && line.endsWith("}"))
        assertTrue(line.contains("\"level\":\"INFO\""))
        assertTrue(line.contains("\"tag\":\"FormatTest\""))
        assertTrue(line.contains("\"message\":\"Config applied\""))
        assertTrue(line.contains("\"theme\":\"LIGHT\""))
        assertTrue(line.contains("\"font_scale\":1.25"))
        assertTrue(line.contains("\"margin_dp\":20"))
        assertTrue(line.contains("\"eink_active\":false"))
        assertTrue(line.contains("\\\"Mixu\\\""))
        assertTrue(line.contains("\\nnewline"))

        // Ensure timestamp parses cleanly as Instant
        val parsedTime = Instant.parse(records.first().timestamp)
        assertNotNull(parsedTime)
    }
}
