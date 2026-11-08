// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.caseboard

import app.mixu.graph.EpistemicStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaseBoardUiStateTest {
    @Test
    fun `initial state projects graph accurately at specified horizon`() {
        val controller =
            CaseBoardController(
                initialReadingPosition = 4,
                initialHorizon = 4,
                initialViewPosition = 4,
            )

        val state = controller.state
        assertEquals(4, state.knowledgeHorizon)
        assertEquals(4, state.graphViewPosition)
        assertEquals(4, state.readingPosition)
        assertFalse(state.isRewound)

        val graphView = assertNotNull(state.revealedGraphView)
        // At ordinal 4, entities up to firstReveal 4 are present
        val entityIds = graphView.entities.map { it.entity.entityId }
        assertTrue(entityIds.contains(SyntheticMysteryData.ENTITY_ARTHUR))
        assertTrue(entityIds.contains(SyntheticMysteryData.ENTITY_VANCE))
        assertTrue(entityIds.contains(SyntheticMysteryData.ENTITY_STUDY))
        assertTrue(entityIds.contains(SyntheticMysteryData.ENTITY_BAXTER))
        assertTrue(entityIds.contains(SyntheticMysteryData.ENTITY_EVELYN))
        assertTrue(entityIds.contains(SyntheticMysteryData.ENTITY_DAGGER))
        assertTrue(entityIds.contains(SyntheticMysteryData.ENTITY_GRAY))
        assertTrue(entityIds.contains(SyntheticMysteryData.ENTITY_WILL))

        // All visible entities have stable deterministic layout positions
        for (entity in graphView.entities) {
            assertNotNull(state.layoutPositions[entity.entity.entityId.value])
        }
    }

    @Test
    fun `scrubbing reveal slider rewinds view without modifying knowledge horizon`() {
        val controller =
            CaseBoardController(
                initialReadingPosition = 4,
                initialHorizon = 4,
                initialViewPosition = 4,
            )

        // Rewind view to ordinal 0 (P-SPOILER-05)
        controller.scrubGraphView(0)
        var state = controller.state

        assertEquals(0, state.graphViewPosition)
        assertEquals(
            expected = 4,
            actual = state.knowledgeHorizon,
            message = "Horizon must remain 4 when scrubbing view backward",
        )
        assertEquals(
            expected = 4,
            actual = state.readingPosition,
            message = "Reading position must remain 4 when scrubbing view backward",
        )
        assertTrue(state.isRewound)

        val graphViewAt0 = assertNotNull(state.revealedGraphView)
        val entityIdsAt0 = graphViewAt0.entities.map { it.entity.entityId }
        assertTrue(entityIdsAt0.contains(SyntheticMysteryData.ENTITY_ARTHUR))
        assertTrue(entityIdsAt0.contains(SyntheticMysteryData.ENTITY_VANCE))
        assertTrue(entityIdsAt0.contains(SyntheticMysteryData.ENTITY_STUDY))
        // Entities disclosed in later chapters must be completely absent at r=0 (P-SPOILER-01)
        assertFalse(entityIdsAt0.contains(SyntheticMysteryData.ENTITY_BAXTER))
        assertFalse(entityIdsAt0.contains(SyntheticMysteryData.ENTITY_EVELYN))
        assertFalse(entityIdsAt0.contains(SyntheticMysteryData.ENTITY_DAGGER))
        assertFalse(entityIdsAt0.contains(SyntheticMysteryData.ENTITY_GRAY))

        val arthurPosAt0 = state.layoutPositions[SyntheticMysteryData.ENTITY_ARTHUR.value]
        assertNotNull(arthurPosAt0)

        // Scrub forward to ordinal 2
        controller.scrubGraphView(2)
        state = controller.state
        assertEquals(2, state.graphViewPosition)
        assertEquals(4, state.knowledgeHorizon)

        val graphViewAt2 = assertNotNull(state.revealedGraphView)
        val entityIdsAt2 = graphViewAt2.entities.map { it.entity.entityId }
        assertTrue(entityIdsAt2.contains(SyntheticMysteryData.ENTITY_BAXTER))
        assertTrue(entityIdsAt2.contains(SyntheticMysteryData.ENTITY_DAGGER))
        assertFalse(entityIdsAt2.contains(SyntheticMysteryData.ENTITY_WILL))

        // Deterministic layout stability: Arthur's coordinates at r=2 must match r=0
        val arthurPosAt2 = state.layoutPositions[SyntheticMysteryData.ENTITY_ARTHUR.value]
        assertEquals(
            expected = arthurPosAt0.position.x,
            actual = arthurPosAt2?.position?.x,
        )
        assertEquals(
            expected = arthurPosAt0.position.y,
            actual = arthurPosAt2?.position?.y,
        )
    }

    @Test
    fun `scrubbing clamps strictly to 0 and knowledgeHorizon`() {
        val controller =
            CaseBoardController(
                initialReadingPosition = 3,
                initialHorizon = 3,
                initialViewPosition = 3,
            )

        // Exceeding horizon clamps to knowledgeHorizon
        controller.scrubGraphView(9)
        assertEquals(3, controller.state.graphViewPosition)

        // Below 0 clamps to 0
        controller.scrubGraphView(-10)
        assertEquals(0, controller.state.graphViewPosition)
    }

    @Test
    fun `entity selection inspects aliases and claims across reveal ordinals`() {
        val controller =
            CaseBoardController(
                initialReadingPosition = 4,
                initialHorizon = 4,
                initialViewPosition = 4,
            )

        val baxterEntity =
            controller.state.revealedGraphView?.entities?.find {
                it.entity.entityId == SyntheticMysteryData.ENTITY_BAXTER
            }
        assertNotNull(baxterEntity)

        controller.selectEntity(baxterEntity)
        val selectedId =
            controller.state.selectedEntity
                ?.entity
                ?.entityId
        assertEquals(
            expected = SyntheticMysteryData.ENTITY_BAXTER,
            actual = selectedId,
        )
        val claimsAt4 = controller.state.selectedEntityAssertions
        assertTrue(claimsAt4.isNotEmpty())

        val alibiClaimAt4 =
            claimsAt4.find {
                it.assertion.assertionId == SyntheticMysteryData.ASSERTION_BAXTER_ALIBI
            }
        assertNotNull(alibiClaimAt4)
        // At ordinal 4, Baxter's alibi has transitioned to CONTRADICTED
        assertEquals(EpistemicStatus.CONTRADICTED, alibiClaimAt4.currentStatus)

        // Rewind view to ordinal 1 (P-SPOILER-02: retroactive contradiction isolation)
        controller.scrubGraphView(1)
        val claimsAt1 = controller.state.selectedEntityAssertions
        val alibiClaimAt1 =
            claimsAt1.find {
                it.assertion.assertionId == SyntheticMysteryData.ASSERTION_BAXTER_ALIBI
            }
        assertNotNull(alibiClaimAt1)
        // At ordinal 1, Baxter's alibi was newly claimed and must NOT leak the later contradiction
        assertEquals(EpistemicStatus.CLAIMED, alibiClaimAt1.currentStatus)

        // Deselection clears inspection
        controller.selectEntity(null)
        assertNull(controller.state.selectedEntity)
        assertTrue(controller.state.selectedEntityAssertions.isEmpty())
    }

    @Test
    fun `user hypotheses are tracked and segregated from canonical facts`() {
        val controller =
            CaseBoardController(
                initialReadingPosition = 2,
                initialHorizon = 2,
                initialViewPosition = 2,
            )

        assertEquals(1, controller.state.hypotheses.size)

        controller.addHypothesis(
            title = "Poison in the Tea",
            description = "Could Arthur have been poisoned before the dagger was planted?",
            relatedEntityId = SyntheticMysteryData.ENTITY_ARTHUR.value,
        )

        assertEquals(2, controller.state.hypotheses.size)
        val newHypo = controller.state.hypotheses.last()
        assertEquals("Poison in the Tea", newHypo.title)
        assertEquals(2, newHypo.createdAtOrdinal)

        // Hypotheses do not mutate the canonical graph pack assertions
        val canonicalAssertions = controller.state.revealedGraphView?.assertions ?: emptyList()
        assertFalse(canonicalAssertions.any { it.assertion.predicate.contains("Poison") })

        // Deletion
        controller.deleteHypothesis(newHypo.id)
        assertEquals(1, controller.state.hypotheses.size)
    }

    @Test
    fun `viewport zoom and pan step discretely for E-Ink compliance`() {
        val controller =
            CaseBoardController(
                initialReadingPosition = 3,
                initialHorizon = 3,
                initialViewPosition = 3,
            )

        assertEquals(1.0f, controller.state.zoomLevel)
        assertEquals(0f, controller.state.panOffsetX)
        assertEquals(0f, controller.state.panOffsetY)

        controller.stepZoom(zoomIn = true)
        assertEquals(1.25f, controller.state.zoomLevel)

        controller.stepPan(dx = 60f, dy = -40f)
        assertEquals(60f, controller.state.panOffsetX)
        assertEquals(-40f, controller.state.panOffsetY)

        controller.resetViewport()
        assertEquals(1.0f, controller.state.zoomLevel)
        assertEquals(0f, controller.state.panOffsetX)
        assertEquals(0f, controller.state.panOffsetY)
    }
}
