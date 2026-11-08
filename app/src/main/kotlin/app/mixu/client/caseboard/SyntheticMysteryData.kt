// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.caseboard

import app.mixu.graph.AnchorId
import app.mixu.graph.Assertion
import app.mixu.graph.AssertionId
import app.mixu.graph.AssertionTransition
import app.mixu.graph.Attribution
import app.mixu.graph.AttributionKind
import app.mixu.graph.EditionId
import app.mixu.graph.Entity
import app.mixu.graph.EntityId
import app.mixu.graph.EntityName
import app.mixu.graph.EntityType
import app.mixu.graph.EpistemicStatus
import app.mixu.graph.EvidenceAnchorId
import app.mixu.graph.GraphPack
import app.mixu.graph.GraphPackManifest
import app.mixu.graph.Locator
import app.mixu.graph.ManifestCounts
import app.mixu.graph.NameId
import app.mixu.graph.NameKind
import app.mixu.graph.ObjectRef
import app.mixu.graph.Provenance
import app.mixu.graph.RevealAnchor
import app.mixu.graph.RevealKind
import app.mixu.graph.TransitionId
import app.mixu.graph.WorkId

/**
 * Rights-cleared synthetic narrative graph pack used as the default offline mystery case.
 * Based on classic locked-room mystery tropes across 6 narrative reveal ordinals (0..5).
 */
object SyntheticMysteryData {
    val EDITION_ID = EditionId("urn:edition:blackwood-manor-v1")
    val WORK_ID = WorkId("urn:work:blackwood-mystery")

    // Entities
    val ENTITY_ARTHUR = EntityId("entity:arthur_pendelton")
    val ENTITY_VANCE = EntityId("entity:inspector_vance")
    val ENTITY_STUDY = EntityId("entity:locked_study")
    val ENTITY_BAXTER = EntityId("entity:baxter_butler")
    val ENTITY_STAIRCASE = EntityId("entity:service_staircase")
    val ENTITY_EVELYN = EntityId("entity:evelyn_reed")
    val ENTITY_DAGGER = EntityId("entity:antique_dagger")
    val ENTITY_GRAY = EntityId("entity:dr_gray")
    val ENTITY_WILL = EntityId("entity:secret_will")

    // Names
    val NAME_ARTHUR = NameId("name:arthur:canonical")
    val NAME_ARTHUR_ALIAS = NameId("name:arthur:alias")
    val NAME_VANCE = NameId("name:vance:canonical")
    val NAME_STUDY = NameId("name:study:canonical")
    val NAME_BAXTER = NameId("name:baxter:canonical")
    val NAME_BAXTER_ALIAS = NameId("name:baxter:alias")
    val NAME_STAIRCASE = NameId("name:staircase:canonical")
    val NAME_EVELYN = NameId("name:evelyn:canonical")
    val NAME_EVELYN_ALIAS = NameId("name:evelyn:alias")
    val NAME_DAGGER = NameId("name:dagger:canonical")
    val NAME_GRAY = NameId("name:gray:canonical")
    val NAME_WILL = NameId("name:will:canonical")

    // Anchors
    val ANCHOR_0 = EvidenceAnchorId("anchor:ch1_intro")
    val ANCHOR_1 = EvidenceAnchorId("anchor:ch2_discovery")
    val ANCHOR_2 = EvidenceAnchorId("anchor:ch3_clues")
    val ANCHOR_3 = EvidenceAnchorId("anchor:ch4_alibis")
    val ANCHOR_4 = EvidenceAnchorId("anchor:ch5_conflict")
    val ANCHOR_5 = EvidenceAnchorId("anchor:ch6_revelation")

    // Assertions
    val ASSERTION_STUDY_LOCKED = AssertionId("assertion:study_locked_inside")
    val ASSERTION_ARTHUR_DECEASED = AssertionId("assertion:arthur_deceased")
    val ASSERTION_BAXTER_ALIBI = AssertionId("assertion:baxter_pantry_alibi")
    val ASSERTION_EVELYN_ALIBI = AssertionId("assertion:evelyn_room_alibi")
    val ASSERTION_DAGGER_FOUND = AssertionId("assertion:dagger_found_study")
    val ASSERTION_EVELYN_DISPUTE = AssertionId("assertion:evelyn_dispute_arthur")
    val ASSERTION_GRAY_VISIT = AssertionId("assertion:gray_visited_night")
    val ASSERTION_WILL_EXISTS = AssertionId("assertion:secret_will_exists")

    // Transitions
    val TRANSITION_BAXTER_CONTRADICTED = TransitionId("trans:baxter_contradicted")
    val TRANSITION_EVELYN_CORROBORATED = TransitionId("trans:evelyn_corroborated")

    fun createMysteryGraphPack(): GraphPack {
        val manifest =
            GraphPackManifest(
                graphSchemaVersion = 1,
                workId = WORK_ID,
                editionId = EDITION_ID,
                graphVersion = "1.0.0",
                revealAnchorMapVersion = 1,
                checksumSha256 = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
                packageFormat = "sqlite+zstd",
                publicationPolicy = "AUTHORIZED_PUBLIC",
                sourceExcerptPolicy = "LOCATOR_ONLY",
                createdAt = "2026-11-08T00:00:00Z",
                copyrightBasis = "SYNTHETIC",
                provenance = Provenance(validatorVersion = "1.0.0"),
                counts =
                    ManifestCounts(
                        entities = 9,
                        assertions = 8,
                        events = 0,
                        evidenceAnchors = 6,
                    ),
            )

        val anchors =
            listOf(
                RevealAnchor(
                    AnchorId("anchor:ord_0"),
                    EDITION_ID,
                    0,
                    Locator(
                        href = "text/ch1.xhtml",
                        type = "application/epub+zip",
                        title = "The Locked Study",
                    ),
                    RevealKind.CHAPTER_START,
                ),
                RevealAnchor(
                    AnchorId("anchor:ord_1"),
                    EDITION_ID,
                    1,
                    Locator(
                        href = "text/ch2.xhtml",
                        type = "application/epub+zip",
                        title = "The Midnight Discovery",
                    ),
                    RevealKind.CHAPTER_START,
                ),
                RevealAnchor(
                    AnchorId("anchor:ord_2"),
                    EDITION_ID,
                    2,
                    Locator(
                        href = "text/ch3.xhtml",
                        type = "application/epub+zip",
                        title = "The Clue in the Dust",
                    ),
                    RevealKind.CHAPTER_START,
                ),
                RevealAnchor(
                    AnchorId("anchor:ord_3"),
                    EDITION_ID,
                    3,
                    Locator(
                        href = "text/ch4.xhtml",
                        type = "application/epub+zip",
                        title = "The Butler's Testimony",
                    ),
                    RevealKind.CHAPTER_START,
                ),
                RevealAnchor(
                    AnchorId("anchor:ord_4"),
                    EDITION_ID,
                    4,
                    Locator(
                        href = "text/ch5.xhtml",
                        type = "application/epub+zip",
                        title = "Conflicting Timelines",
                    ),
                    RevealKind.CHAPTER_START,
                ),
                RevealAnchor(
                    AnchorId("anchor:ord_5"),
                    EDITION_ID,
                    5,
                    Locator(
                        href = "text/ch6.xhtml",
                        type = "application/epub+zip",
                        title = "The Secret Will",
                    ),
                    RevealKind.CHAPTER_START,
                ),
            )

        val entities =
            listOf(
                Entity(
                    ENTITY_ARTHUR,
                    EntityType.PERSON,
                    firstReveal = 0,
                    canonicalNameId = NAME_ARTHUR,
                ),
                Entity(
                    ENTITY_VANCE,
                    EntityType.PERSON,
                    firstReveal = 0,
                    canonicalNameId = NAME_VANCE,
                ),
                Entity(
                    ENTITY_STUDY,
                    EntityType.LOCATION,
                    firstReveal = 0,
                    canonicalNameId = NAME_STUDY,
                ),
                Entity(
                    ENTITY_BAXTER,
                    EntityType.PERSON,
                    firstReveal = 1,
                    canonicalNameId = NAME_BAXTER,
                ),
                Entity(
                    ENTITY_STAIRCASE,
                    EntityType.LOCATION,
                    firstReveal = 1,
                    canonicalNameId = NAME_STAIRCASE,
                ),
                Entity(
                    ENTITY_EVELYN,
                    EntityType.PERSON,
                    firstReveal = 1,
                    canonicalNameId = NAME_EVELYN,
                ),
                Entity(
                    ENTITY_DAGGER,
                    EntityType.OBJECT,
                    firstReveal = 2,
                    canonicalNameId = NAME_DAGGER,
                ),
                Entity(
                    ENTITY_GRAY,
                    EntityType.PERSON,
                    firstReveal = 2,
                    canonicalNameId = NAME_GRAY,
                ),
                Entity(
                    ENTITY_WILL,
                    EntityType.OBJECT,
                    firstReveal = 3,
                    canonicalNameId = NAME_WILL,
                ),
            )

        val names =
            listOf(
                EntityName(
                    NAME_ARTHUR,
                    ENTITY_ARTHUR,
                    "Arthur Pendelton",
                    firstReveal = 0,
                    NameKind.CANONICAL,
                ),
                EntityName(
                    NAME_ARTHUR_ALIAS,
                    ENTITY_ARTHUR,
                    "Lord of the Manor",
                    firstReveal = 1,
                    NameKind.TITLE,
                ),
                EntityName(
                    NAME_VANCE,
                    ENTITY_VANCE,
                    "Inspector Vance",
                    firstReveal = 0,
                    NameKind.CANONICAL,
                ),
                EntityName(
                    NAME_STUDY,
                    ENTITY_STUDY,
                    "The Study",
                    firstReveal = 0,
                    NameKind.CANONICAL,
                ),
                EntityName(
                    NAME_BAXTER,
                    ENTITY_BAXTER,
                    "Baxter",
                    firstReveal = 1,
                    NameKind.CANONICAL,
                ),
                EntityName(
                    NAME_BAXTER_ALIAS,
                    ENTITY_BAXTER,
                    "The Quiet Butler",
                    firstReveal = 2,
                    NameKind.DESCRIPTION,
                ),
                EntityName(
                    NAME_STAIRCASE,
                    ENTITY_STAIRCASE,
                    "Service Staircase",
                    firstReveal = 1,
                    NameKind.CANONICAL,
                ),
                EntityName(
                    NAME_EVELYN,
                    ENTITY_EVELYN,
                    "Evelyn Reed",
                    firstReveal = 1,
                    NameKind.CANONICAL,
                ),
                EntityName(
                    NAME_EVELYN_ALIAS,
                    ENTITY_EVELYN,
                    "The Estranged Niece",
                    firstReveal = 2,
                    NameKind.DESCRIPTION,
                ),
                EntityName(
                    NAME_DAGGER,
                    ENTITY_DAGGER,
                    "Antique Dagger",
                    firstReveal = 2,
                    NameKind.CANONICAL,
                ),
                EntityName(
                    NAME_GRAY,
                    ENTITY_GRAY,
                    "Dr. Kenneth Gray",
                    firstReveal = 2,
                    NameKind.CANONICAL,
                ),
                EntityName(
                    NAME_WILL,
                    ENTITY_WILL,
                    "Secret Will",
                    firstReveal = 3,
                    NameKind.CANONICAL,
                ),
            )

        val assertions =
            listOf(
                Assertion(
                    assertionId = ASSERTION_STUDY_LOCKED,
                    subjectId = ENTITY_STUDY,
                    predicate = "is_bolted_from",
                    objectRef = ObjectRef.Literal("inside"),
                    attribution = Attribution(AttributionKind.OBSERVATION),
                    firstReveal = 0,
                    evidenceAnchorId = ANCHOR_0,
                    epistemicStatus = EpistemicStatus.OBSERVED,
                    extractionConfidence = 1.0,
                ),
                Assertion(
                    assertionId = ASSERTION_ARTHUR_DECEASED,
                    subjectId = ENTITY_ARTHUR,
                    predicate = "located_in",
                    objectRef = ObjectRef.Entity(ENTITY_STUDY),
                    attribution = Attribution(AttributionKind.OBSERVATION),
                    firstReveal = 0,
                    evidenceAnchorId = ANCHOR_0,
                    epistemicStatus = EpistemicStatus.OBSERVED,
                    extractionConfidence = 1.0,
                ),
                Assertion(
                    assertionId = ASSERTION_BAXTER_ALIBI,
                    subjectId = ENTITY_BAXTER,
                    predicate = "claimed_location_at_midnight",
                    objectRef = ObjectRef.Literal("pantry"),
                    attribution =
                        Attribution(
                            AttributionKind.WITNESS_CLAIM,
                            speakerId = ENTITY_BAXTER,
                        ),
                    firstReveal = 1,
                    evidenceAnchorId = ANCHOR_1,
                    epistemicStatus = EpistemicStatus.CLAIMED,
                    extractionConfidence = 0.9,
                ),
                Assertion(
                    assertionId = ASSERTION_EVELYN_ALIBI,
                    subjectId = ENTITY_EVELYN,
                    predicate = "claimed_location_at_midnight",
                    objectRef = ObjectRef.Literal("guest_wing"),
                    attribution =
                        Attribution(
                            AttributionKind.WITNESS_CLAIM,
                            speakerId = ENTITY_EVELYN,
                        ),
                    firstReveal = 1,
                    evidenceAnchorId = ANCHOR_1,
                    epistemicStatus = EpistemicStatus.CLAIMED,
                    extractionConfidence = 0.85,
                ),
                Assertion(
                    assertionId = ASSERTION_DAGGER_FOUND,
                    subjectId = ENTITY_DAGGER,
                    predicate = "discovered_in",
                    objectRef = ObjectRef.Entity(ENTITY_STUDY),
                    attribution = Attribution(AttributionKind.OBSERVATION),
                    firstReveal = 2,
                    evidenceAnchorId = ANCHOR_2,
                    epistemicStatus = EpistemicStatus.OBSERVED,
                    extractionConfidence = 1.0,
                ),
                Assertion(
                    assertionId = ASSERTION_EVELYN_DISPUTE,
                    subjectId = ENTITY_EVELYN,
                    predicate = "had_heated_dispute_with",
                    objectRef = ObjectRef.Entity(ENTITY_ARTHUR),
                    attribution = Attribution(AttributionKind.READER_INFERENCE),
                    firstReveal = 2,
                    evidenceAnchorId = ANCHOR_2,
                    epistemicStatus = EpistemicStatus.INFERRED,
                    extractionConfidence = 0.75,
                ),
                Assertion(
                    assertionId = ASSERTION_GRAY_VISIT,
                    subjectId = ENTITY_GRAY,
                    predicate = "visited_before_midnight",
                    objectRef = ObjectRef.Entity(ENTITY_STUDY),
                    attribution = Attribution(AttributionKind.OBSERVATION),
                    firstReveal = 2,
                    evidenceAnchorId = ANCHOR_2,
                    epistemicStatus = EpistemicStatus.OBSERVED,
                    extractionConfidence = 0.95,
                ),
                Assertion(
                    assertionId = ASSERTION_WILL_EXISTS,
                    subjectId = ENTITY_WILL,
                    predicate = "bequeaths_estate_to",
                    objectRef = ObjectRef.Entity(ENTITY_BAXTER),
                    attribution = Attribution(AttributionKind.OBSERVATION),
                    firstReveal = 3,
                    evidenceAnchorId = ANCHOR_3,
                    epistemicStatus = EpistemicStatus.OBSERVED,
                    extractionConfidence = 0.9,
                ),
            )

        val transitions =
            listOf(
                AssertionTransition(
                    transitionId = TRANSITION_BAXTER_CONTRADICTED,
                    assertionId = ASSERTION_BAXTER_ALIBI,
                    revealOrdinal = 4,
                    status = EpistemicStatus.CONTRADICTED,
                    evidenceAnchorId = ANCHOR_4,
                    reason = "Witness placed Baxter on the service staircase at midnight",
                ),
                AssertionTransition(
                    transitionId = TRANSITION_EVELYN_CORROBORATED,
                    assertionId = ASSERTION_EVELYN_DISPUTE,
                    revealOrdinal = 4,
                    status = EpistemicStatus.CORROBORATED,
                    evidenceAnchorId = ANCHOR_4,
                    reason = "Diary entry confirmed heated argument over inheritance before dinner",
                ),
            )

        return GraphPack(
            manifest = manifest,
            entities = entities,
            names = names,
            assertions = assertions,
            transitions = transitions,
            anchors = anchors,
        )
    }
}
