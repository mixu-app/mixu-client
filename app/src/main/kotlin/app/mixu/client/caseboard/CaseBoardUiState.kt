// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.caseboard

import app.mixu.graph.AnchorId
import app.mixu.graph.Assertion
import app.mixu.graph.AssertionId
import app.mixu.graph.Attribution
import app.mixu.graph.AttributionKind
import app.mixu.graph.Entity
import app.mixu.graph.EntityId
import app.mixu.graph.EntityName
import app.mixu.graph.EntityType
import app.mixu.graph.EpistemicStatus
import app.mixu.graph.EvidenceAnchorId
import app.mixu.graph.GraphPack
import app.mixu.graph.Locator
import app.mixu.graph.ManifestCounts
import app.mixu.graph.NameId
import app.mixu.graph.NameKind
import app.mixu.graph.ObjectRef
import app.mixu.graph.RevealAnchor
import app.mixu.graph.RevealKind
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
    val isAddEntityDialogOpen: Boolean = false,
    val editingEntity: RevealedEntity? = null,
    val isAddAssertionDialogOpen: Boolean = false,
) {
    val maxReveal: Int
        get() = knowledgeHorizon

    val isRewound: Boolean
        get() = graphViewPosition < knowledgeHorizon
}

/**
 * Controller providing state management, temporal graph projection,
 * deterministic layout anchoring, user graph editing, and hypothesis tracking for the Case Board.
 */
class CaseBoardController(
    val graphPack: GraphPack = SyntheticMysteryData.createMysteryGraphPack(),
    initialReadingPosition: Int = 4,
    initialHorizon: Int = 4,
    initialViewPosition: Int = 4,
    initialBookTitle: String = "Blackwood Manor Mystery",
    private val onGraphPackMutated: ((GraphPack) -> Unit)? = null,
) {
    var activeGraphPack: GraphPack = graphPack
        private set
    private var projectionEngine = TemporalProjectionEngine(activeGraphPack)
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

    fun setAddEntityDialogOpen(isOpen: Boolean) {
        state = state.copy(isAddEntityDialogOpen = isOpen)
    }

    fun setEditingEntity(entity: RevealedEntity?) {
        state = state.copy(editingEntity = entity)
    }

    fun setAddAssertionDialogOpen(isOpen: Boolean) {
        state = state.copy(isAddAssertionDialogOpen = isOpen)
    }

    /**
     * Adds a user-created entity node to the knowledge graph at [firstReveal] and persists the pack.
     *
     * Time complexity: O(V + E) where V is entity count and E is assertion count.
     * Space complexity: O(V + E).
     */
    fun addEntity(
        label: String,
        type: EntityType = EntityType.PERSON,
        firstReveal: Int = state.graphViewPosition,
    ) {
        val cleanLabel = label.trim()
        require(cleanLabel.isNotBlank()) { "Entity label cannot be blank" }
        val revealOrd = firstReveal.coerceIn(0, state.knowledgeHorizon)
        val suffix = System.currentTimeMillis()
        val entId = EntityId("ent:user_$suffix")
        val nameId = NameId("name:user_$suffix:canonical")
        val anchorId = ensureAnchorForOrdinal(revealOrd)

        val newEntity =
            Entity(
                entityId = entId,
                entityType = type,
                firstReveal = revealOrd,
                canonicalNameId = nameId,
            )
        val newName =
            EntityName(
                nameId = nameId,
                entityId = entId,
                displayText = cleanLabel,
                firstReveal = revealOrd,
                nameKind = NameKind.CANONICAL,
                evidenceAnchorId = EvidenceAnchorId(anchorId.value),
            )

        updateGraphPack(
            entities = activeGraphPack.entities + newEntity,
            names = activeGraphPack.names + newName,
            assertions = activeGraphPack.assertions,
        )
        state = state.copy(isAddEntityDialogOpen = false)
    }

    /**
     * Renames an existing entity node and/or updates its [EntityType].
     *
     * Time complexity: O(V + E).
     * Space complexity: O(V + E).
     */
    fun updateEntity(
        entityId: String,
        newLabel: String,
        newType: EntityType,
    ) {
        val cleanLabel = newLabel.trim()
        require(cleanLabel.isNotBlank()) { "Entity label cannot be blank" }
        val targetId = EntityId(entityId)
        val targetEntity = activeGraphPack.entities.find { it.entityId == targetId } ?: return

        val updatedEntities =
            activeGraphPack.entities.map { ent ->
                if (ent.entityId == targetId) {
                    ent.copy(entityType = newType)
                } else {
                    ent
                }
            }
        val updatedNames =
            activeGraphPack.names.map { nm ->
                if (nm.nameId == targetEntity.canonicalNameId ||
                    (nm.entityId == targetId && nm.nameKind == NameKind.CANONICAL)
                ) {
                    nm.copy(displayText = cleanLabel)
                } else {
                    nm
                }
            }

        updateGraphPack(
            entities = updatedEntities,
            names = updatedNames,
            assertions = activeGraphPack.assertions,
        )
        state = state.copy(editingEntity = null)
    }

    /**
     * Deletes an entity node and all incident assertions from the knowledge graph.
     *
     * Time complexity: O(V + E).
     * Space complexity: O(V + E).
     */
    fun deleteEntity(entityId: String) {
        val targetId = EntityId(entityId)
        val remainingEntities = activeGraphPack.entities.filterNot { it.entityId == targetId }
        if (remainingEntities.isEmpty()) {
            // Keep at least one node in the graph pack so canonical validation succeeds
            return
        }
        val remainingNames = activeGraphPack.names.filterNot { it.entityId == targetId }
        val remainingAssertions =
            activeGraphPack.assertions.filterNot { assertion ->
                val obj = assertion.objectRef
                assertion.subjectId == targetId ||
                    (obj is ObjectRef.Entity && obj.entityId == targetId)
            }
        previousPositions.remove(entityId)

        updateGraphPack(
            entities = remainingEntities,
            names = remainingNames,
            assertions = remainingAssertions,
        )
        if (state.selectedEntity?.entity?.entityId == targetId) {
            state =
                state.copy(
                    selectedEntity = null,
                    selectedEntityAssertions = emptyList(),
                    editingEntity = null,
                )
        }
    }

    /**
     * Adds a user-authored relational assertion edge between [subjectId] and [objectId].
     *
     * Time complexity: O(V + E).
     * Space complexity: O(V + E).
     */
    fun addAssertion(
        subjectId: String,
        predicate: String,
        objectId: String,
        status: EpistemicStatus = EpistemicStatus.OBSERVED,
        firstReveal: Int = state.graphViewPosition,
    ) {
        val cleanPredicate = predicate.trim()
        require(cleanPredicate.isNotBlank()) { "Predicate cannot be blank" }
        require(subjectId != objectId) { "Self-loop assertions are not allowed" }

        val subjEnt =
            activeGraphPack.entities.find { it.entityId.value == subjectId } ?: return
        val objEnt =
            activeGraphPack.entities.find { it.entityId.value == objectId } ?: return

        val revealOrd =
            maxOf(
                firstReveal.coerceIn(0, state.knowledgeHorizon),
                subjEnt.firstReveal,
                objEnt.firstReveal,
            ).coerceIn(0, state.knowledgeHorizon)
        val anchorId = ensureAnchorForOrdinal(revealOrd)
        val newAssertion =
            Assertion(
                assertionId = AssertionId("assertion:user_${System.currentTimeMillis()}"),
                subjectId = subjEnt.entityId,
                predicate = cleanPredicate,
                objectRef = ObjectRef.Entity(objEnt.entityId),
                attribution = Attribution(AttributionKind.READER_INFERENCE),
                firstReveal = revealOrd,
                evidenceAnchorId = EvidenceAnchorId(anchorId.value),
                epistemicStatus = status,
                extractionConfidence = 1.0,
            )

        updateGraphPack(
            entities = activeGraphPack.entities,
            names = activeGraphPack.names,
            assertions = activeGraphPack.assertions + newAssertion,
        )
        state = state.copy(isAddAssertionDialogOpen = false)
    }

    /**
     * Cycles the epistemic status of an assertion (`OBSERVED -> CLAIMED -> CORROBORATED -> INFERRED -> CONTRADICTED -> UNRESOLVED`).
     */
    fun cycleAssertionStatus(assertionId: String) {
        val targetId = AssertionId(assertionId)
        val order =
            listOf(
                EpistemicStatus.OBSERVED,
                EpistemicStatus.CLAIMED,
                EpistemicStatus.CORROBORATED,
                EpistemicStatus.INFERRED,
                EpistemicStatus.CONTRADICTED,
                EpistemicStatus.UNRESOLVED,
            )
        val updatedAssertions =
            activeGraphPack.assertions.map { assertion ->
                if (assertion.assertionId == targetId) {
                    val idx = order.indexOf(assertion.epistemicStatus)
                    val nextStatus = order[(idx + 1).mod(order.size)]
                    assertion.copy(epistemicStatus = nextStatus)
                } else {
                    assertion
                }
            }
        updateGraphPack(
            entities = activeGraphPack.entities,
            names = activeGraphPack.names,
            assertions = updatedAssertions,
        )
    }

    /**
     * Deletes an assertion edge by [assertionId].
     */
    fun deleteAssertion(assertionId: String) {
        val targetId = AssertionId(assertionId)
        val updatedAssertions =
            activeGraphPack.assertions.filterNot { it.assertionId == targetId }
        updateGraphPack(
            entities = activeGraphPack.entities,
            names = activeGraphPack.names,
            assertions = updatedAssertions,
        )
    }

    private fun ensureAnchorForOrdinal(ordinal: Int): AnchorId {
        val existing = activeGraphPack.anchors.find { it.ordinal == ordinal }
        if (existing != null) {
            return existing.anchorId
        }
        val fallbackAnchor = activeGraphPack.anchors.firstOrNull()
        val newAnchor =
            RevealAnchor(
                anchorId = AnchorId("anchor:ord_$ordinal"),
                editionId = activeGraphPack.manifest.editionId,
                ordinal = ordinal,
                locator =
                    fallbackAnchor?.locator ?: Locator(
                        href = "text/ch$ordinal.xhtml",
                        type = "application/xhtml+xml",
                        title = "Chapter ${ordinal + 1}",
                    ),
                revealKind = RevealKind.CHAPTER_START,
            )
        val updatedAnchors =
            (activeGraphPack.anchors + newAnchor).sortedBy { it.ordinal }
        activeGraphPack =
            GraphPack.canonical(
                manifest =
                    activeGraphPack.manifest.copy(
                        counts =
                            activeGraphPack.manifest.counts?.copy(
                                evidenceAnchors = updatedAnchors.size,
                            ),
                    ),
                entities = activeGraphPack.entities,
                names = activeGraphPack.names,
                assertions = activeGraphPack.assertions,
                transitions = activeGraphPack.transitions,
                anchors = updatedAnchors,
            )
        return newAnchor.anchorId
    }

    private fun updateGraphPack(
        entities: List<Entity>,
        names: List<EntityName>,
        assertions: List<Assertion>,
    ) {
        val validAssertionIds = assertions.map { it.assertionId }.toSet()
        val filteredTransitions =
            activeGraphPack.transitions.filter { it.assertionId in validAssertionIds }
        val updatedManifest =
            activeGraphPack.manifest.copy(
                counts =
                    ManifestCounts(
                        entities = entities.size,
                        assertions = assertions.size,
                        events = activeGraphPack.manifest.counts?.events ?: 0,
                        evidenceAnchors = activeGraphPack.anchors.size,
                    ),
            )
        activeGraphPack =
            GraphPack.canonical(
                manifest = updatedManifest,
                entities = entities,
                names = names,
                assertions = assertions,
                transitions = filteredTransitions,
                anchors = activeGraphPack.anchors,
            )
        projectionEngine = TemporalProjectionEngine(activeGraphPack)
        reproject(state.graphViewPosition)
        onGraphPackMutated?.invoke(activeGraphPack)
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
