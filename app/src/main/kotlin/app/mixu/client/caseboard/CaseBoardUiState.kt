// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.caseboard

import app.mixu.graph.GraphPack
import app.mixu.graph.ObjectRef
import app.mixu.graph.RevealedAssertion
import app.mixu.graph.RevealedEntity
import app.mixu.graph.RevealedGraphView
import app.mixu.graph.TemporalProjectionEngine
import app.mixu.renderer.layout.DeterministicLayoutEngine
import app.mixu.renderer.layout.LayoutNode
import app.mixu.renderer.layout.NodePosition
import app.mixu.renderer.layout.Point
import app.mixu.renderer.layout.Rect
import app.mixu.renderer.layout.Size

/**
 * Display tabs available within the Case Board surface.
 */
enum class CaseBoardTab {
    GRAPH,
    CLAIMS,
    HYPOTHESES,
}

/**
 * User-created speculative hypothesis or detective note.
 * Visually distinguished with dashed borders and dedicated badges
 * to ensure user speculation is never confused with canonical narrative facts.
 */
data class UserHypothesis(
    val id: String,
    val title: String,
    val description: String,
    val relatedEntityId: String? = null,
    val createdAtOrdinal: Int = 0,
) {
    init {
        require(title.isNotBlank()) { "title cannot be blank" }
    }
}

/**
 * Complete UI state for the Case Board screen.
 */
data class CaseBoardUiState(
    val bookTitle: String = "Blackwood Manor Mystery",
    val editionId: String = SyntheticMysteryData.EDITION_ID.value,
    val readingPosition: Int = 4,
    val knowledgeHorizon: Int = 4,
    val graphViewPosition: Int = 4,
    val revealedGraphView: RevealedGraphView? = null,
    val layoutPositions: Map<String, NodePosition> = emptyMap(),
    val selectedEntity: RevealedEntity? = null,
    val selectedEntityAssertions: List<RevealedAssertion> = emptyList(),
    val hypotheses: List<UserHypothesis> = emptyList(),
    val activeTab: CaseBoardTab = CaseBoardTab.GRAPH,
    val zoomLevel: Float = 1.0f,
    val panOffsetX: Float = 0f,
    val panOffsetY: Float = 0f,
    val isAddHypothesisDialogOpen: Boolean = false,
) {
    val maxReveal: Int
        get() = knowledgeHorizon

    val isRewound: Boolean
        get() = graphViewPosition < knowledgeHorizon
}

/**
 * Controller providing state management, temporal graph projection,
 * deterministic layout anchoring, and hypothesis tracking for the Case Board.
 */
class CaseBoardController(
    val graphPack: GraphPack = SyntheticMysteryData.createMysteryGraphPack(),
    initialReadingPosition: Int = 4,
    initialHorizon: Int = 4,
    initialViewPosition: Int = 4,
    initialBookTitle: String = "Blackwood Manor Mystery",
) {
    private val projectionEngine = TemporalProjectionEngine(graphPack)
    private val previousPositions = mutableMapOf<String, Point>()

    var state: CaseBoardUiState =
        CaseBoardUiState(
            bookTitle = initialBookTitle,
            editionId = graphPack.manifest.editionId.value,
            readingPosition = initialReadingPosition,
            knowledgeHorizon = initialHorizon,
            graphViewPosition = initialViewPosition.coerceIn(0, initialHorizon),
            hypotheses =
                listOf(
                    UserHypothesis(
                        id = "hypo-1",
                        title = "Secret Passage Speculation",
                        description =
                            "Could the killer have used the service staircase to enter the study?",
                        relatedEntityId = SyntheticMysteryData.ENTITY_STAIRCASE.value,
                        createdAtOrdinal = 1,
                    ),
                ),
        )
        private set

    init {
        reproject(state.graphViewPosition)
    }

    /**
     * Scrubs the historical reveal slider to [targetOrdinal].
     * Enforces P-SPOILER-05: bounded to [0, [knowledgeHorizon]], does not alter horizon.
     */
    fun scrubGraphView(targetOrdinal: Int) {
        val clamped = targetOrdinal.coerceIn(0, state.knowledgeHorizon)
        if (clamped == state.graphViewPosition && state.revealedGraphView != null) {
            return
        }
        state = state.copy(graphViewPosition = clamped)
        reproject(clamped)
    }

    /**
     * Sets the active surface tab.
     */
    fun setTab(tab: CaseBoardTab) {
        state = state.copy(activeTab = tab)
    }

    /**
     * Selects an entity for inspection in the dossier drawer.
     */
    fun selectEntity(entity: RevealedEntity?) {
        if (entity == null) {
            state = state.copy(selectedEntity = null, selectedEntityAssertions = emptyList())
            return
        }
        val currentAssertions = state.revealedGraphView?.assertions ?: emptyList()
        val relevantAssertions =
            currentAssertions.filter { assertion ->
                val obj = assertion.assertion.objectRef
                assertion.assertion.subjectId == entity.entity.entityId ||
                    (obj is ObjectRef.Entity && obj.entityId == entity.entity.entityId)
            }
        state =
            state.copy(
                selectedEntity = entity,
                selectedEntityAssertions = relevantAssertions,
            )
    }

    /**
     * Adds a user-authored hypothesis note.
     */
    fun addHypothesis(
        title: String,
        description: String,
        relatedEntityId: String? = null,
    ) {
        require(title.isNotBlank()) { "title cannot be blank" }
        val newHypo =
            UserHypothesis(
                id = "hypo-${System.currentTimeMillis()}",
                title = title.trim(),
                description = description.trim(),
                relatedEntityId = relatedEntityId,
                createdAtOrdinal = state.graphViewPosition,
            )
        state =
            state.copy(
                hypotheses = state.hypotheses + newHypo,
                isAddHypothesisDialogOpen = false,
            )
    }

    /**
     * Deletes a user hypothesis.
     */
    fun deleteHypothesis(id: String) {
        state = state.copy(hypotheses = state.hypotheses.filterNot { it.id == id })
    }

    fun setAddHypothesisDialogOpen(isOpen: Boolean) {
        state = state.copy(isAddHypothesisDialogOpen = isOpen)
    }

    /**
     * Discrete stepped zoom adhering to E-Ink discrete viewport transition policy.
     */
    fun stepZoom(zoomIn: Boolean) {
        val zoomLevels = listOf(0.75f, 1.0f, 1.25f, 1.5f)
        val currentIndex = zoomLevels.indexOf(state.zoomLevel).let { if (it == -1) 1 else it }
        val nextIndex =
            if (zoomIn) {
                (currentIndex + 1).coerceAtMost(zoomLevels.lastIndex)
            } else {
                (
                    currentIndex -
                        1
                ).coerceAtLeast(0)
            }
        state = state.copy(zoomLevel = zoomLevels[nextIndex])
    }

    /**
     * Discrete stepped pan adhering to E-Ink discrete viewport transition policy.
     */
    fun stepPan(
        dx: Float,
        dy: Float,
    ) {
        state =
            state.copy(
                panOffsetX = (state.panOffsetX + dx).coerceIn(-400f, 400f),
                panOffsetY = (state.panOffsetY + dy).coerceIn(-400f, 400f),
            )
    }

    /**
     * Resets viewport pan and zoom to default.
     */
    fun resetViewport() {
        state = state.copy(zoomLevel = 1.0f, panOffsetX = 0f, panOffsetY = 0f)
    }

    private fun reproject(ordinal: Int) {
        val projection = projectionEngine.project(ordinal)
        val canvasBounds = Rect(0.0, 0.0, 800.0, 600.0)
        val layoutNodes =
            projection.entities.map {
                LayoutNode(
                    entityId = it.entity.entityId.value,
                    size = Size(140.0, 70.0),
                )
            }

        val positions =
            DeterministicLayoutEngine.computePositions(
                editionId = state.editionId,
                nodes = layoutNodes,
                canvasBounds = canvasBounds,
                previousPositions = previousPositions,
            )

        // Cache positions for stability as new nodes are revealed
        for ((k, v) in positions) {
            previousPositions[k] = v.position
        }

        // Re-evaluate selected entity under new projection
        val updatedSelectedEntity =
            if (state.selectedEntity != null) {
                projection.entities.find {
                    it.entity.entityId ==
                        state.selectedEntity?.entity?.entityId
                }
            } else {
                null
            }

        val updatedSelectedAssertions =
            if (updatedSelectedEntity != null) {
                projection.assertions.filter { assertion ->
                    val obj = assertion.assertion.objectRef
                    assertion.assertion.subjectId == updatedSelectedEntity.entity.entityId ||
                        (
                            obj is ObjectRef.Entity &&
                                obj.entityId == updatedSelectedEntity.entity.entityId
                        )
                }
            } else {
                emptyList()
            }

        state =
            state.copy(
                revealedGraphView = projection,
                layoutPositions = positions,
                selectedEntity = updatedSelectedEntity,
                selectedEntityAssertions = updatedSelectedAssertions,
            )
    }
}
