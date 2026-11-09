// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.caseboard

import app.mixu.client.config.GraphDimensionMode
import app.mixu.client.config.GraphPhysicsMode
import app.mixu.graph.TemporalProjectionEngine
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ForceDirectedGraphEngineTest {
    @Test
    fun `computeLayout produces deterministic 2D Force and Hierarchical coordinates`() {
        val pack = SyntheticMysteryData.createMysteryGraphPack()
        val projection = TemporalProjectionEngine(pack).project(4)

        val layoutFirst =
            ForceDirectedGraphEngine.computeLayout(
                editionId = pack.manifest.editionId.value,
                entities = projection.entities,
                assertions = projection.assertions,
                mode = GraphDimensionMode.FORCE_2D,
            )
        val layoutSecond =
            ForceDirectedGraphEngine.computeLayout(
                editionId = pack.manifest.editionId.value,
                entities = projection.entities,
                assertions = projection.assertions,
                mode = GraphDimensionMode.FORCE_2D,
            )

        assertEquals(projection.entities.size, layoutFirst.nodes.size)
        assertEquals(layoutFirst.nodes, layoutSecond.nodes)
        assertTrue(layoutFirst.edges.isNotEmpty())

        // Verify Hierarchical layout assigns distinct vertical layers
        val layoutHierarchical =
            ForceDirectedGraphEngine.computeLayout(
                editionId = pack.manifest.editionId.value,
                entities = projection.entities,
                assertions = projection.assertions,
                mode = GraphDimensionMode.HIERARCHICAL,
            )
        assertEquals(projection.entities.size, layoutHierarchical.nodes.size)
        assertTrue(
            layoutHierarchical.nodes.values
                .map { it.y }
                .distinct()
                .size >= 2,
        )

        // Verify E-Ink mode snaps coordinates to 8px grid and enforces wider inter-node separation
        val layoutEInk =
            ForceDirectedGraphEngine.computeLayout(
                editionId = pack.manifest.editionId.value,
                entities = projection.entities,
                assertions = projection.assertions,
                isEInk = true,
                mode = GraphDimensionMode.FORCE_2D,
            )
        assertTrue(layoutEInk.nodes.values.all { it.x % 8f == 0f && it.y % 8f == 0f })

        val standardNodes = layoutFirst.nodes.values.toList()
        val einkNodes = layoutEInk.nodes.values.toList()
        var minStandardDist = Float.MAX_VALUE
        var minEInkDist = Float.MAX_VALUE
        for (i in standardNodes.indices) {
            for (j in i + 1 until standardNodes.size) {
                val dStd =
                    hypot(
                        standardNodes[i].x - standardNodes[j].x,
                        standardNodes[i].y - standardNodes[j].y,
                    )
                val dEInk =
                    hypot(
                        einkNodes[i].x - einkNodes[j].x,
                        einkNodes[i].y - einkNodes[j].y,
                    )
                if (dStd < minStandardDist) minStandardDist = dStd
                if (dEInk < minEInkDist) minEInkDist = dEInk
            }
        }
        assertTrue(
            minEInkDist > minStandardDist,
            "Expected E-Ink minimum node separation ($minEInkDist) > standard ($minStandardDist)",
        )
    }

    @Test
    fun `projectNodes maps 2D coordinates to viewport and applies pan, zoom, and drag offsets`() {
        val pack = SyntheticMysteryData.createMysteryGraphPack()
        val projection = TemporalProjectionEngine(pack).project(4)
        val layout2d =
            ForceDirectedGraphEngine.computeLayout(
                editionId = pack.manifest.editionId.value,
                entities = projection.entities,
                assertions = projection.assertions,
                mode = GraphDimensionMode.FORCE_2D,
            )

        val projectedDefault =
            ForceDirectedGraphEngine.projectNodes(
                entities = projection.entities,
                layoutResult = layout2d,
                viewportWidthPx = 1200f,
                viewportHeightPx = 900f,
                zoom = 1.0f,
                panX = 0f,
                panY = 0f,
            )

        assertEquals(projection.entities.size, projectedDefault.size)

        // Panning or dragging a node shifts projected 2D screen positions
        val firstEntityId =
            projection.entities
                .first()
                .entity.entityId.value
        val projectedWithDrag =
            ForceDirectedGraphEngine.projectNodes(
                entities = projection.entities,
                layoutResult = layout2d,
                viewportWidthPx = 1200f,
                viewportHeightPx = 900f,
                zoom = 1.0f,
                panX = 40f,
                panY = -25f,
                nodeOffsets = mapOf(firstEntityId to (60f to 45f)),
            )
        assertNotEquals(
            projectedDefault.map { it.screenX to it.screenY },
            projectedWithDrag.map { it.screenX to it.screenY },
        )
    }

    @Test
    fun `applyNodeDragStep moves dragged node and couples neighbors under spring physics`() {
        val pack = SyntheticMysteryData.createMysteryGraphPack()
        val projection = TemporalProjectionEngine(pack).project(4)
        val layout =
            ForceDirectedGraphEngine.computeLayout(
                editionId = pack.manifest.editionId.value,
                entities = projection.entities,
                assertions = projection.assertions,
                mode = GraphDimensionMode.FORCE_2D,
            )

        val draggedId =
            layout.neighborMap.entries
                .first { it.value.isNotEmpty() }
                .key
        val neighborId = layout.neighborMap.getValue(draggedId).first()

        // 1. PIN_NODE moves only the dragged node
        val pinOffsets =
            ForceDirectedGraphEngine.applyNodeDragStep(
                layoutResult = layout,
                currentOffsets = emptyMap(),
                draggedEntityId = draggedId,
                screenDx = 80f,
                screenDy = -50f,
                viewportWidthPx = 1200f,
                viewportHeightPx = 900f,
                zoom = 1.0f,
                physicsMode = GraphPhysicsMode.PIN_NODE,
                pinnedEntityIds = setOf(draggedId),
            )
        assertTrue(pinOffsets.containsKey(draggedId))
        assertEquals(null, pinOffsets[neighborId])

        // 2. LIVE_SPRING pulls unpinned connected neighbors via Hooke spring attraction
        val springOffsets =
            ForceDirectedGraphEngine.applyNodeDragStep(
                layoutResult = layout,
                currentOffsets = emptyMap(),
                draggedEntityId = draggedId,
                screenDx = 120f,
                screenDy = 75f,
                viewportWidthPx = 1200f,
                viewportHeightPx = 900f,
                zoom = 1.0f,
                physicsMode = GraphPhysicsMode.LIVE_SPRING,
                pinnedEntityIds = setOf(draggedId),
            )
        assertTrue(springOffsets.containsKey(draggedId))
        val neighborOffset = springOffsets[neighborId]
        assertTrue(
            neighborOffset != null && (neighborOffset.first != 0f || neighborOffset.second != 0f),
            "Expected connected neighbor $neighborId to experience spring pull when $draggedId is dragged",
        )
    }
}
