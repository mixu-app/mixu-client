// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.caseboard

import app.mixu.graph.EvidenceAnchor
import app.mixu.graph.ExcerptPolicy
import app.mixu.graph.GraphJson
import app.mixu.graph.GraphPack
import app.mixu.graph.Locator
import app.mixu.graph.RevealAnchor
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.security.MessageDigest

/**
 * Result of validating a [GraphPack] before marketplace publication (ADR-040).
 */
data class MarketplaceValidationResult(
    val isValid: Boolean,
    val entityCount: Int,
    val assertionCount: Int,
    val violations: List<String> = emptyList(),
)

/**
 * Copyright-safe offset descriptor for an anchor quote or excerpt (ADR-040).
 *
 * Stores only character offsets `[startOffset, endOffset)`, byte/character length, and a
 * 64-character lowercase SHA-256 digest of the excerpt so zero copyrighted prose is stored
 * or transmitted in the Shared GraphPack Marketplace.
 */
data class OffsetOnlyAnchorRecord(
    val anchorId: String,
    val locator: Locator,
    val startOffset: Int = 0,
    val endOffset: Int = 0,
    val quoteLength: Int = (endOffset - startOffset).coerceAtLeast(0),
    val quoteSha256: String,
    val revealOrdinal: Int = 0,
) {
    init {
        require(anchorId.isNotBlank()) { "anchorId cannot be blank" }
        require(startOffset >= 0) { "startOffset must be non-negative" }
        require(endOffset >= startOffset) {
            "endOffset ($endOffset) cannot precede startOffset ($startOffset)"
        }
        require(quoteLength >= 0) { "quoteLength must be non-negative" }
        require(quoteSha256.matches(SHA256_HEX_REGEX)) {
            "quoteSha256 must be a 64-char lowercase hex string"
        }
        require(revealOrdinal >= 0) { "revealOrdinal must be non-negative" }
    }

    companion object {
        private val SHA256_HEX_REGEX = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Wire envelope for a copyright-stripped Shared GraphPack (ADR-040).
 */
data class OffsetOnlyGraphPackEnvelope(
    val sanitizedPack: GraphPack,
    val anchorRecords: List<OffsetOnlyAnchorRecord> = emptyList(),
    val sanitizedEvidenceAnchors: List<EvidenceAnchor> = emptyList(),
) {
    /**
     * Serializes this envelope into compact JSON for marketplace upload/download.
     */
    fun toJsonString(): String =
        buildJsonObject {
            put(
                "sanitized_pack",
                GraphJson.encodeToJsonElement(GraphPack.serializer(), sanitizedPack),
            )
            put(
                "anchor_records",
                buildJsonArray {
                    for (record in anchorRecords) {
                        add(
                            buildJsonObject {
                                put("anchor_id", JsonPrimitive(record.anchorId))
                                put(
                                    "locator",
                                    GraphJson.encodeToJsonElement(
                                        Locator.serializer(),
                                        record.locator,
                                    ),
                                )
                                put("start_offset", JsonPrimitive(record.startOffset))
                                put("end_offset", JsonPrimitive(record.endOffset))
                                put("quote_length", JsonPrimitive(record.quoteLength))
                                put("quote_sha256", JsonPrimitive(record.quoteSha256))
                                put("reveal_ordinal", JsonPrimitive(record.revealOrdinal))
                            },
                        )
                    }
                },
            )
            put(
                "sanitized_evidence_anchors",
                buildJsonArray {
                    for (ev in sanitizedEvidenceAnchors) {
                        add(GraphJson.encodeToJsonElement(EvidenceAnchor.serializer(), ev))
                    }
                },
            )
        }.toString()
}

/**
 * Enforces copyright-safe Offset-Only Shared GraphPack encoding and local EPUB rehydration
 * (ADR-040).
 *
 * All verbatim book quotes (`Locator.textBefore`, `Locator.textAfter`, `EvidenceAnchor.excerpt`,
 * `Entity.notes`, and `AssertionTransition.reason`) are stripped prior to marketplace upload and
 * replaced with deterministic character offsets and SHA-256 digests. When another reader unlocks
 * the pack on a device holding the matching EPUB, quotes are reconstructed locally from the
 * reader's own EPUB chapters and verified against `quoteSha256`.
 */
object OffsetOnlySharedPackCodec {
    const val DEFAULT_MIN_ENTITIES = 6
    const val DEFAULT_MIN_ASSERTIONS = 10
    const val PUBLICATION_POLICY_OFFSET_ONLY = "SHARED_OFFSET_ONLY"
    const val EXCERPT_POLICY_LOCATOR_ONLY = "LOCATOR_ONLY"

    /**
     * Validates that [pack] meets minimum structural quality and spoiler-horizon invariants
     * for publication to the Shared GraphPack Marketplace.
     *
     * Time complexity: O(E + N + A + T + R) where E, N, A, T, R are element counts in [pack].
     * Space complexity: O(V) where V is the number of detected violations.
     */
    fun validateForMarketplacePublish(
        pack: GraphPack,
        minEntities: Int = DEFAULT_MIN_ENTITIES,
        minAssertions: Int = DEFAULT_MIN_ASSERTIONS,
    ): MarketplaceValidationResult {
        require(minEntities >= 0) { "minEntities must be non-negative" }
        require(minAssertions >= 0) { "minAssertions must be non-negative" }

        val violations = mutableListOf<String>()

        if (pack.entities.size < minEntities) {
            violations.add("entity_count_below_minimum: ${pack.entities.size} < $minEntities")
        }
        if (pack.assertions.size < minAssertions) {
            violations.add(
                "assertion_count_below_minimum: ${pack.assertions.size} < $minAssertions",
            )
        }
        if (pack.anchors.isEmpty()) {
            violations.add("anchors_empty: at least one RevealAnchor is required")
        }
        if (pack.entities.any { it.firstReveal < 0 }) {
            violations.add("negative_entity_reveal_ordinal")
        }
        if (pack.names.any { it.firstReveal < 0 }) {
            violations.add("negative_name_reveal_ordinal")
        }
        if (pack.assertions.any { it.firstReveal < 0 }) {
            violations.add("negative_assertion_reveal_ordinal")
        }
        if (pack.transitions.any { it.revealOrdinal < 0 }) {
            violations.add("negative_transition_reveal_ordinal")
        }
        if (pack.anchors.any { it.ordinal < 0 }) {
            violations.add("negative_anchor_ordinal")
        }

        return MarketplaceValidationResult(
            isValid = violations.isEmpty(),
            entityCount = pack.entities.size,
            assertionCount = pack.assertions.size,
            violations = violations,
        )
    }

    /**
     * Strips all verbatim book quotes/excerpts from [pack] (and optional [evidenceAnchors]),
     * recording `(anchorId, locator, startOffset, endOffset, quoteLength, quoteSha256)` in
     * [OffsetOnlyAnchorRecord]s so zero copyrighted prose leaves the device.
     *
     * Time complexity: O(R * L + E + T) where R is anchor count and L is chapter length.
     * Space complexity: O(E + A + R).
     */
    fun stripProseForSharedCatalog(
        pack: GraphPack,
        evidenceAnchors: List<EvidenceAnchor> = emptyList(),
        chapterTextByHref: Map<String, String> = emptyMap(),
    ): OffsetOnlyGraphPackEnvelope {
        val records = mutableListOf<OffsetOnlyAnchorRecord>()

        val sanitizedRevealAnchors =
            pack.anchors.map { anchor ->
                val verbatimQuote =
                    anchor.locator.textBefore
                        ?: anchor.locator.textAfter
                        ?: ""
                val cleanLocator =
                    anchor.locator.copy(
                        textBefore = null,
                        textAfter = null,
                    )

                if (verbatimQuote.isNotEmpty() || anchor.textHash != null) {
                    val digest =
                        if (verbatimQuote.isNotEmpty()) {
                            sha256Hex(verbatimQuote)
                        } else {
                            requireNotNull(anchor.textHash)
                        }
                    val (startOffset, endOffset) =
                        resolveOffsets(
                            href = anchor.locator.href,
                            quote = verbatimQuote,
                            progression = anchor.locator.progression,
                            chapterTextByHref = chapterTextByHref,
                        )
                    records.add(
                        OffsetOnlyAnchorRecord(
                            anchorId = anchor.anchorId.value,
                            locator = cleanLocator,
                            startOffset = startOffset,
                            endOffset = endOffset,
                            quoteLength = verbatimQuote.length,
                            quoteSha256 = digest,
                            revealOrdinal = anchor.ordinal,
                        ),
                    )
                    anchor.copy(
                        locator = cleanLocator,
                        textHash = digest,
                    )
                } else {
                    anchor.copy(locator = cleanLocator)
                }
            }

        val sanitizedEvidence =
            evidenceAnchors.map { evidence ->
                val excerptText = evidence.excerpt.orEmpty()
                val digest =
                    if (excerptText.isNotEmpty()) {
                        sha256Hex(excerptText)
                    } else {
                        evidence.sourceHash ?: sha256Hex("")
                    }
                val cleanLocator =
                    evidence.locator.copy(
                        textBefore = null,
                        textAfter = null,
                    )
                val (startOffset, endOffset) =
                    resolveOffsets(
                        href = evidence.locator.href,
                        quote = excerptText,
                        progression = evidence.locator.progression,
                        chapterTextByHref = chapterTextByHref,
                    )
                if (records.none { it.anchorId == evidence.evidenceId.value }) {
                    records.add(
                        OffsetOnlyAnchorRecord(
                            anchorId = evidence.evidenceId.value,
                            locator = cleanLocator,
                            startOffset = startOffset,
                            endOffset = endOffset,
                            quoteLength = excerptText.length,
                            quoteSha256 = digest,
                            revealOrdinal = evidence.revealOrdinal,
                        ),
                    )
                }
                evidence.copy(
                    locator = cleanLocator,
                    sourceHash = digest,
                    excerptPolicy = ExcerptPolicy.LOCATOR_ONLY,
                    excerpt = null,
                )
            }

        val sanitizedEntities =
            pack.entities.map { entity ->
                entity.copy(notes = null)
            }

        val sanitizedTransitions =
            pack.transitions.map { transition ->
                transition.copy(reason = null)
            }

        val sanitizedManifest =
            pack.manifest.copy(
                publicationPolicy = PUBLICATION_POLICY_OFFSET_ONLY,
                sourceExcerptPolicy = EXCERPT_POLICY_LOCATOR_ONLY,
            )

        val sanitizedPack =
            GraphPack(
                manifest = sanitizedManifest,
                entities = sanitizedEntities,
                names = pack.names,
                assertions = pack.assertions,
                transitions = sanitizedTransitions,
                anchors = sanitizedRevealAnchors,
            )

        return OffsetOnlyGraphPackEnvelope(
            sanitizedPack = sanitizedPack,
            anchorRecords = records,
            sanitizedEvidenceAnchors = sanitizedEvidence,
        )
    }

    /**
     * Reconstructs verbatim quotes in [GraphPack.anchors] from the reader's local EPUB chapters
     * ([chapterTextByHref]) using each [OffsetOnlyAnchorRecord]'s `[startOffset, endOffset)`
     * and verifies the SHA-256 digest against `quoteSha256`.
     *
     * Throws [IllegalArgumentException] if a required chapter slice is out of bounds or fails
     * SHA-256 integrity verification.
     *
     * Time complexity: O(R * L) in the fallback scan case, O(R * Q) when offsets are exact.
     * Space complexity: O(R * Q) where R is anchor count and Q is quote length.
     */
    fun rehydrateFromLocalChapters(
        envelope: OffsetOnlyGraphPackEnvelope,
        chapterTextByHref: Map<String, String>,
    ): GraphPack {
        val recordsByAnchorId = envelope.anchorRecords.associateBy { it.anchorId }

        val rehydratedAnchors: List<RevealAnchor> =
            envelope.sanitizedPack.anchors.map { anchor ->
                val record = recordsByAnchorId[anchor.anchorId.value] ?: return@map anchor
                val chapterText =
                    chapterTextByHref[record.locator.href]
                        ?: chapterTextByHref[anchor.locator.href]
                        ?: throw IllegalArgumentException(
                            "Missing local chapter text for href '${record.locator.href}' " +
                                "while rehydrating anchor '${record.anchorId}'",
                        )

                val slice = extractVerifiedQuote(record, chapterText)
                anchor.copy(
                    locator = anchor.locator.copy(textBefore = slice),
                    textHash = record.quoteSha256,
                )
            }

        return envelope.sanitizedPack.copy(anchors = rehydratedAnchors)
    }

    /**
     * Reconstructs [EvidenceAnchor.excerpt] values from local EPUB chapter text and verifies
     * each reconstructed quote's SHA-256 checksum against [OffsetOnlyAnchorRecord.quoteSha256].
     *
     * Time complexity: O(R * Q). Space complexity: O(R * Q).
     */
    fun rehydrateEvidenceAnchors(
        envelope: OffsetOnlyGraphPackEnvelope,
        chapterTextByHref: Map<String, String>,
    ): List<EvidenceAnchor> {
        val recordsByAnchorId = envelope.anchorRecords.associateBy { it.anchorId }

        return envelope.sanitizedEvidenceAnchors.map { evidence ->
            val record =
                recordsByAnchorId[evidence.evidenceId.value]
                    ?: return@map evidence
            val chapterText =
                chapterTextByHref[record.locator.href]
                    ?: chapterTextByHref[evidence.locator.href]
                    ?: throw IllegalArgumentException(
                        "Missing local chapter text for href '${record.locator.href}' " +
                            "while rehydrating evidence '${record.anchorId}'",
                    )

            val slice = extractVerifiedQuote(record, chapterText)
            evidence.copy(
                sourceHash = record.quoteSha256,
                excerptPolicy = ExcerptPolicy.BOUNDED_QUOTE,
                excerpt = slice.take(MAX_EVIDENCE_EXCERPT_LENGTH),
            )
        }
    }

    /**
     * Computes a 64-character lowercase hexadecimal SHA-256 digest of [text].
     */
    fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val v = byte.toInt() and 0xFF
            sb.append(HEX_CHARS[v ushr 4])
            sb.append(HEX_CHARS[v and 0x0F])
        }
        return sb.toString()
    }

    private fun resolveOffsets(
        href: String,
        quote: String,
        progression: Double?,
        chapterTextByHref: Map<String, String>,
    ): Pair<Int, Int> {
        if (quote.isEmpty()) {
            return 0 to 0
        }
        val chapterText = chapterTextByHref[href]
        if (chapterText != null) {
            val foundIndex = chapterText.indexOf(quote)
            if (foundIndex >= 0) {
                return foundIndex to (foundIndex + quote.length)
            }
        }
        if (progression != null && chapterText != null && chapterText.length >= quote.length) {
            val approxStart =
                ((chapterText.length - quote.length) * progression)
                    .toInt()
                    .coerceIn(0, chapterText.length - quote.length)
            return approxStart to (approxStart + quote.length)
        }
        return 0 to quote.length
    }

    private fun extractVerifiedQuote(
        record: OffsetOnlyAnchorRecord,
        chapterText: String,
    ): String {
        if (record.quoteLength == 0 && record.startOffset == record.endOffset) {
            val emptyHash = sha256Hex("")
            require(record.quoteSha256 == emptyHash) {
                "SHA-256 mismatch for empty anchor '${record.anchorId}'"
            }
            return ""
        }

        require(record.startOffset >= 0 && record.endOffset <= chapterText.length) {
            "Offset range [${record.startOffset}, ${record.endOffset}) out of bounds " +
                "for chapter '${record.locator.href}' (length=${chapterText.length})"
        }

        val directSlice = chapterText.substring(record.startOffset, record.endOffset)
        if (sha256Hex(directSlice) == record.quoteSha256) {
            return directSlice
        }

        // If offsets were initialized at [0, quoteLength) without chapter text at strip time,
        // scan the local chapter for a substring of length quoteLength matching quoteSha256.
        if (
            record.startOffset == 0 &&
            record.quoteLength > 0 &&
            chapterText.length >= record.quoteLength
        ) {
            val maxStart = chapterText.length - record.quoteLength
            for (start in 0..maxStart) {
                val candidate = chapterText.substring(start, start + record.quoteLength)
                if (sha256Hex(candidate) == record.quoteSha256) {
                    return candidate
                }
            }
        }

        throw IllegalArgumentException(
            "SHA-256 integrity mismatch for anchor '${record.anchorId}' " +
                "in '${record.locator.href}': " +
                "expected ${record.quoteSha256} but got ${sha256Hex(directSlice)}",
        )
    }

    private const val MAX_EVIDENCE_EXCERPT_LENGTH = 600
    private val HEX_CHARS = "0123456789abcdef".toCharArray()
}
