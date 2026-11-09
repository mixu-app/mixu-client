// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.caseboard

import app.mixu.graph.AnchorId
import app.mixu.graph.Assertion
import app.mixu.graph.AssertionId
import app.mixu.graph.Attribution
import app.mixu.graph.AttributionKind
import app.mixu.graph.EpistemicStatus
import app.mixu.graph.EvidenceAnchor
import app.mixu.graph.EvidenceAnchorId
import app.mixu.graph.ExcerptPolicy
import app.mixu.graph.Locator
import app.mixu.graph.ObjectRef
import app.mixu.graph.RevealAnchor
import app.mixu.graph.RevealKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OffsetOnlySharedPackCodecTest {
    @Test
    fun `validateForMarketplacePublish checks entity and assertion thresholds`() {
        val basePack = SyntheticMysteryData.createMysteryGraphPack()
        // basePack has 9 entities and 8 assertions; default minAssertions is 10
        val defaultCheck = OffsetOnlySharedPackCodec.validateForMarketplacePublish(basePack)
        assertFalse(defaultCheck.isValid)
        assertTrue(defaultCheck.violations.any { it.startsWith("assertion_count_below_minimum") })

        val extraAssertions =
            basePack.assertions +
                listOf(
                    Assertion(
                        assertionId = AssertionId("assertion:extra_9"),
                        subjectId = SyntheticMysteryData.ENTITY_VANCE,
                        predicate = "inspected",
                        objectRef = ObjectRef.Entity(SyntheticMysteryData.ENTITY_DAGGER),
                        attribution = Attribution(AttributionKind.OBSERVATION),
                        firstReveal = 2,
                        evidenceAnchorId = SyntheticMysteryData.ANCHOR_2,
                        epistemicStatus = EpistemicStatus.OBSERVED,
                        extractionConfidence = 0.92,
                    ),
                    Assertion(
                        assertionId = AssertionId("assertion:extra_10"),
                        subjectId = SyntheticMysteryData.ENTITY_VANCE,
                        predicate = "questioned",
                        objectRef = ObjectRef.Entity(SyntheticMysteryData.ENTITY_BAXTER),
                        attribution = Attribution(AttributionKind.OBSERVATION),
                        firstReveal = 3,
                        evidenceAnchorId = SyntheticMysteryData.ANCHOR_3,
                        epistemicStatus = EpistemicStatus.OBSERVED,
                        extractionConfidence = 0.94,
                    ),
                )
        val publishablePack = basePack.copy(assertions = extraAssertions)
        val validCheck = OffsetOnlySharedPackCodec.validateForMarketplacePublish(publishablePack)
        assertTrue(validCheck.isValid)
        assertEquals(9, validCheck.entityCount)
        assertEquals(10, validCheck.assertionCount)
        assertTrue(validCheck.violations.isEmpty())
    }

    @Test
    fun `stripProseForSharedCatalog removes quotes and rehydrates from local chapters`() {
        val ch1Quote = "The heavy oak door of the study was bolted from the inside."
        val ch2Quote = "A silver-hilted antique dagger lay beside the fireplace."
        val ch1FullText = "Chapter 1. $ch1Quote Inspector Vance frowned at the brass latch."
        val ch2FullText = "At midnight the alarm rang. $ch2Quote Nobody had entered the hall."

        val basePack = SyntheticMysteryData.createMysteryGraphPack()
        val anchorsWithProse =
            basePack.anchors.map { anchor ->
                when (anchor.ordinal) {
                    0 ->
                        anchor.copy(
                            locator =
                                anchor.locator.copy(
                                    textBefore = ch1Quote,
                                    textAfter = "Inspector Vance frowned",
                                ),
                        )
                    1 ->
                        anchor.copy(
                            locator =
                                anchor.locator.copy(
                                    textBefore = ch2Quote,
                                ),
                        )
                    else -> anchor
                }
            }
        val entitiesWithNotes =
            basePack.entities.mapIndexed { idx, ent ->
                if (idx == 0) ent.copy(notes = "Private copyrighted note excerpt") else ent
            }
        val packWithProse =
            basePack.copy(
                entities = entitiesWithNotes,
                anchors = anchorsWithProse,
            )

        val evidenceAnchors =
            listOf(
                EvidenceAnchor(
                    evidenceId = EvidenceAnchorId("ev:ch1_bolt"),
                    revealOrdinal = 0,
                    locator =
                        Locator(
                            href = "text/ch1.xhtml",
                            type = "application/epub+zip",
                            textBefore = ch1Quote,
                        ),
                    excerptPolicy = ExcerptPolicy.BOUNDED_QUOTE,
                    excerpt = ch1Quote,
                ),
            )

        val chapterMap =
            mapOf(
                "text/ch1.xhtml" to ch1FullText,
                "text/ch2.xhtml" to ch2FullText,
            )

        val envelope =
            OffsetOnlySharedPackCodec.stripProseForSharedCatalog(
                pack = packWithProse,
                evidenceAnchors = evidenceAnchors,
                chapterTextByHref = chapterMap,
            )

        // Verify zero copyrighted prose remains in the sanitized pack or serialized JSON
        val wireJson = envelope.toJsonString()
        assertFalse(wireJson.contains(ch1Quote))
        assertFalse(wireJson.contains(ch2Quote))
        assertFalse(wireJson.contains("Private copyrighted note excerpt"))
        assertFalse(wireJson.contains("Witness placed Baxter on the service staircase"))

        for (anchor in envelope.sanitizedPack.anchors) {
            assertNull(anchor.locator.textBefore)
            assertNull(anchor.locator.textAfter)
        }
        for (entity in envelope.sanitizedPack.entities) {
            assertNull(entity.notes)
        }
        for (transition in envelope.sanitizedPack.transitions) {
            assertNull(transition.reason)
        }
        for (ev in envelope.sanitizedEvidenceAnchors) {
            assertEquals(ExcerptPolicy.LOCATOR_ONLY, ev.excerptPolicy)
            assertNull(ev.excerpt)
            assertEquals(OffsetOnlySharedPackCodec.sha256Hex(ch1Quote), ev.sourceHash)
        }

        // Rehydrate from the local EPUB chapters and verify exact reconstruction + SHA-256 check
        val rehydratedPack =
            OffsetOnlySharedPackCodec.rehydrateFromLocalChapters(
                envelope = envelope,
                chapterTextByHref = chapterMap,
            )
        val ord0Anchor = rehydratedPack.anchors.first { it.ordinal == 0 }
        val ord1Anchor = rehydratedPack.anchors.first { it.ordinal == 1 }
        assertEquals(ch1Quote, ord0Anchor.locator.textBefore)
        assertEquals(OffsetOnlySharedPackCodec.sha256Hex(ch1Quote), ord0Anchor.textHash)
        assertEquals(ch2Quote, ord1Anchor.locator.textBefore)
        assertEquals(OffsetOnlySharedPackCodec.sha256Hex(ch2Quote), ord1Anchor.textHash)

        val rehydratedEvidence =
            OffsetOnlySharedPackCodec.rehydrateEvidenceAnchors(
                envelope = envelope,
                chapterTextByHref = chapterMap,
            )
        assertEquals(1, rehydratedEvidence.size)
        assertEquals(ExcerptPolicy.BOUNDED_QUOTE, rehydratedEvidence.first().excerptPolicy)
        assertEquals(ch1Quote, rehydratedEvidence.first().excerpt)
    }

    @Test
    fun `rehydrateFromLocalChapters rejects tampered chapter failing sha256 verification`() {
        val quote = "The clock struck twelve when the gunshot echoed."
        val basePack = SyntheticMysteryData.createMysteryGraphPack()
        val packWithQuote =
            basePack.copy(
                anchors =
                    listOf(
                        RevealAnchor(
                            anchorId = AnchorId("anchor:ord_0"),
                            editionId = SyntheticMysteryData.EDITION_ID,
                            ordinal = 0,
                            locator =
                                Locator(
                                    href = "text/ch1.xhtml",
                                    type = "application/epub+zip",
                                    textBefore = quote,
                                ),
                            revealKind = RevealKind.PARAGRAPH,
                        ),
                    ) + basePack.anchors.drop(1),
            )

        val envelope = OffsetOnlySharedPackCodec.stripProseForSharedCatalog(packWithQuote)
        val tamperedText =
            "The clock struck ONE when the gunshot echoed. Completely different text."
        val tamperedChapters =
            mapOf(
                "text/ch1.xhtml" to tamperedText,
            )

        assertFailsWith<IllegalArgumentException> {
            OffsetOnlySharedPackCodec.rehydrateFromLocalChapters(
                envelope = envelope,
                chapterTextByHref = tamperedChapters,
            )
        }
    }
}
