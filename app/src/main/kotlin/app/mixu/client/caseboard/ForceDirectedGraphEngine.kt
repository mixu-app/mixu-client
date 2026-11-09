// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.caseboard

import app.mixu.client.config.GraphDimensionMode
import app.mixu.client.config.GraphPhysicsMode
import app.mixu.graph.ObjectRef
import app.mixu.graph.RevealedAssertion
import app.mixu.graph.RevealedEntity
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 2D node coordinate and degree metadata computed by [ForceDirectedGraphEngine].
 */
data class ForceNodeLayout(
    val entityId: String,
    val x: Float,
    val y: Float,
    val degree: Int,
    val baseRadiusDp: Float,
)

/**
 * Directed assertion edge with computed quadratic Bezier curvature for multi-edge separation.
 */
data class ForceEdgeLayout(
    val assertion: RevealedAssertion,
    val sourceId: String,
    val targetId: String,
    val curvature: Float,
)

/**
 * Complete 2D layout result for a revealed narrative graph snapshot.
 */
data class ForceGraphLayoutResult(
    val nodes: Map<String, ForceNodeLayout>,
    val edges: List<ForceEdgeLayout>,
    val neighborMap: Map<String, Set<String>>,
)

/**
 * 2D screen-space projection of a [ForceNodeLayout] after applying viewport scaling,
 * zoom, pan, and optional interactive node drag offsets.
 */
data class ProjectedGraphNode(
    val entity: RevealedEntity,
    val layout: ForceNodeLayout,
    val screenX: Float,
    val screenY: Float,
    val scale: Float,
)

/**
 * Pure-Kotlin deterministic 2D Knowledge Graph layout and projection engine for Compose,
 * combining techniques from `Kuiver`, `d3-force`, and `Forcelayout` with dedicated E-Ink
 * physics tuning.
 *
 * Supports two 2D layout modes ([GraphDimensionMode]):
 * 1. [GraphDimensionMode.FORCE_2D]: 2D planar spring-embedder layout (Coulomb repulsion +
 *    Hooke spring attraction + degree-aware collision clearance).
 * 2. [GraphDimensionMode.HIERARCHICAL]: Sugiyama-inspired layered topological layout with
 *    BFS hub-to-periphery layer assignment and barycenter crossing minimization.
 *
 * E-Ink Physics Policy ([isEInk] = true):
 * - Freezes layout in a single pre-settled pass (zero continuous animation ticks).
 * - Increases inter-node repulsion (+61%), spring rest distance (+28%), and minimum collision
 *   clearance (+34%), and snaps coordinates to an 8px grid so monochrome e-paper labels stay
 *   uncluttered and ghost-free.
 *
 * Time complexity: O(I * (V^2 + E)) for force mode (I = 90), O(V log V + E) for hierarchical mode.
 * Space complexity: O(V + E).
 */
object ForceDirectedGraphEngine {
    private const val SIMULATION_ITERATIONS = 90
    private const val REPULSION_STANDARD = 26000.0
    private const val REPULSION_EINK = 42000.0
    private const val SPRING_STRENGTH_STANDARD = 0.062
    private const val SPRING_STRENGTH_EINK = 0.050
    private const val IDEAL_LINK_DISTANCE_STANDARD = 160.0
    private const val IDEAL_LINK_DISTANCE_EINK = 205.0
    private const val CENTER_GRAVITY_STANDARD = 0.028
    private const val CENTER_GRAVITY_EINK = 0.020
    private const val MIN_COLLISION_STANDARD = 76.0
    private const val MIN_COLLISION_EINK = 102.0
    private const val MAX_COORD_BOUND = 420.0
    private const val GOLDEN_ANGLE = 2.399963229728653

    fun computeLayout(
        editionId: String,
        entities: List<RevealedEntity>,
        assertions: List<RevealedAssertion>,
        isEInk: Boolean = false,
        mode: GraphDimensionMode = GraphDimensionMode.FORCE_2D,
    ): ForceGraphLayoutResult {
        if (entities.isEmpty()) {
            return ForceGraphLayoutResult(
                nodes = emptyMap(),
                edges = emptyList(),
                neighborMap = emptyMap(),
            )
        }

        val sortedEntities = entities.sortedBy { it.entity.entityId.value }
        val nodeIndexMap =
            sortedEntities
                .mapIndexed { idx, re -> re.entity.entityId.value to idx }
                .toMap()
        val n = sortedEntities.size

        // Extract valid entity-to-entity edges, literal-mention edges, and scene co-occurrence edges
        val rawEdges = mutableListOf<Triple<Int, Int, RevealedAssertion>>()
        val degrees = IntArray(n)
        val neighborSets = Array(n) { mutableSetOf<String>() }
        val anchorParticipants = mutableMapOf<String, MutableList<Pair<Int, RevealedAssertion>>>()

        for (assertion in assertions) {
            val subId = assertion.assertion.subjectId.value
            val u = nodeIndexMap[subId] ?: continue
            val anchorId = assertion.assertion.evidenceAnchorId.value
            anchorParticipants.getOrPut(anchorId) { mutableListOf() }.add(u to assertion)

            when (val obj = assertion.assertion.objectRef) {
                is ObjectRef.Entity -> {
                    val objId = obj.entityId.value
                    val v = nodeIndexMap[objId]
                    if (v != null && u != v) {
                        rawEdges.add(Triple(u, v, assertion))
                        degrees[u]++
                        degrees[v]++
                        neighborSets[u].add(objId)
                        neighborSets[v].add(subId)
                    }
                }
                is ObjectRef.Literal -> {
                    val textToSearch = "${assertion.assertion.predicate} ${obj.value}"
                    for (v in 0 until n) {
                        if (v == u) continue
                        val candidate = sortedEntities[v]
                        val candidateNames =
                            buildList {
                                candidate.canonicalName?.displayText?.let { add(it) }
                                candidate.allRevealedNames.forEach { add(it.displayText) }
                            }.filter { it.length >= 2 }
                        if (candidateNames.any { textToSearch.contains(it) }) {
                            val objId = candidate.entity.entityId.value
                            rawEdges.add(Triple(u, v, assertion))
                            degrees[u]++
                            degrees[v]++
                            neighborSets[u].add(objId)
                            neighborSets[v].add(subId)
                        }
                    }
                }
            }
        }

        // Connect co-occurring entities within the same evidence anchor scene if not already linked
        for ((_, participants) in anchorParticipants) {
            val distinctByNode = participants.distinctBy { it.first }
            if (distinctByNode.size >= 2) {
                val (hubIdx, _) = distinctByNode.first()
                val hubId = sortedEntities[hubIdx].entity.entityId.value
                for (k in 1 until distinctByNode.size) {
                    val (otherIdx, otherAssertion) = distinctByNode[k]
                    val otherId = sortedEntities[otherIdx].entity.entityId.value
                    if (!neighborSets[hubIdx].contains(otherId)) {
                        rawEdges.add(Triple(hubIdx, otherIdx, otherAssertion))
                        degrees[hubIdx]++
                        degrees[otherIdx]++
                        neighborSets[hubIdx].add(otherId)
                        neighborSets[otherIdx].add(hubId)
                    }
                }
            }
        }

        // Assign symmetric curvatures for parallel edges between the same unordered pair {u, v}
        val pairGroups =
            rawEdges.groupBy { (u, v, _) ->
                if (u < v) "$u:$v" else "$v:$u"
            }
        val edgeLayouts = mutableListOf<ForceEdgeLayout>()
        for ((_, group) in pairGroups) {
            val count = group.size
            group.forEachIndexed { idx, (u, v, assertion) ->
                val subId = sortedEntities[u].entity.entityId.value
                val objId = sortedEntities[v].entity.entityId.value
                val baseCurve =
                    if (count == 1) {
                        if (mode == GraphDimensionMode.HIERARCHICAL) 0.04f else 0.08f
                    } else {
                        val centered = idx - (count - 1) / 2.0f
                        centered * 0.26f
                    }
                val sign = if (u < v) 1f else -1f
                edgeLayouts.add(
                    ForceEdgeLayout(
                        assertion = assertion,
                        sourceId = subId,
                        targetId = objId,
                        curvature = baseCurve * sign,
                    ),
                )
            }
        }

        if (mode == GraphDimensionMode.HIERARCHICAL) {
            return computeHierarchicalLayout(
                sortedEntities = sortedEntities,
                rawEdges = rawEdges,
                degrees = degrees,
                neighborSets = neighborSets,
                edgeLayouts = edgeLayouts,
                isEInk = isEInk,
            )
        }

        val repulsionStrength = if (isEInk) REPULSION_EINK else REPULSION_STANDARD
        val springStrength = if (isEInk) SPRING_STRENGTH_EINK else SPRING_STRENGTH_STANDARD
        val idealLinkDistance =
            if (isEInk) IDEAL_LINK_DISTANCE_EINK else IDEAL_LINK_DISTANCE_STANDARD
        val centerGravity = if (isEInk) CENTER_GRAVITY_EINK else CENTER_GRAVITY_STANDARD
        val minCollisionDistance =
            if (isEInk) MIN_COLLISION_EINK else MIN_COLLISION_STANDARD

        val posX = DoubleArray(n)
        val posY = DoubleArray(n)
        val velX = DoubleArray(n)
        val velY = DoubleArray(n)

        val initialRadius = (85.0 + sqrt(n.toDouble()) * 46.0).coerceAtMost(290.0)
        for (i in 0 until n) {
            val entityId = sortedEntities[i].entity.entityId.value
            val hashJitter =
                ((fnv1a64("$editionId:$entityId") and 0xFFFFL).toDouble() / 65535.0) - 0.5
            if (n == 1) {
                posX[i] = 0.0
                posY[i] = 0.0
            } else {
                val r = initialRadius * sqrt((i + 0.5) / n.toDouble())
                val theta = GOLDEN_ANGLE * i + hashJitter * 0.25
                posX[i] = cos(theta) * r * 1.12
                posY[i] = sin(theta) * r * 0.88
            }
        }

        // Pre-settled damped 2D force simulation (no continuous runtime animation loop)
        for (iter in 0 until SIMULATION_ITERATIONS) {
            val alpha = 1.0 - (iter.toDouble() / SIMULATION_ITERATIONS.toDouble()) * 0.78
            val damping = 0.62

            val forceX = DoubleArray(n)
            val forceY = DoubleArray(n)

            // 1. Many-body Coulomb repulsion + minimum collision distance
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    val dx = posX[i] - posX[j]
                    val dy = posY[i] - posY[j]
                    val distSq = dx * dx + dy * dy + 16.0
                    val dist = sqrt(distSq)

                    val degreeBoost = 1.0 + 0.14 * (degrees[i] + degrees[j])
                    var repMag = (repulsionStrength * degreeBoost) / distSq

                    if (dist < minCollisionDistance) {
                        repMag += (minCollisionDistance - dist) * 5.2
                    }

                    val ux = if (dist > 0.001) dx / dist else 1.0
                    val uy = if (dist > 0.001) dy / dist else 0.0

                    forceX[i] += ux * repMag
                    forceY[i] += uy * repMag

                    forceX[j] -= ux * repMag
                    forceY[j] -= uy * repMag
                }
            }

            // 2. Hooke spring attraction along directed/undirected edges
            for ((u, v, _) in rawEdges) {
                val dx = posX[v] - posX[u]
                val dy = posY[v] - posY[u]
                val dist = hypot(dx, dy).coerceAtLeast(1.0)
                val displacement = dist - idealLinkDistance
                val springMag = springStrength * displacement

                val ux = dx / dist
                val uy = dy / dist

                forceX[u] += ux * springMag
                forceY[u] += uy * springMag

                forceX[v] -= ux * springMag
                forceY[v] -= uy * springMag
            }

            // 3. Central gravity (pulls high-degree hubs gently toward center)
            for (i in 0 until n) {
                val hubWeight = 1.0 + degrees[i] * 0.12
                forceX[i] -= posX[i] * centerGravity * hubWeight
                forceY[i] -= posY[i] * centerGravity * hubWeight
            }

            // 4. Integrate velocities & positions with bounding box clamp
            for (i in 0 until n) {
                velX[i] = (velX[i] + forceX[i] * alpha) * damping
                velY[i] = (velY[i] + forceY[i] * alpha) * damping

                posX[i] = (posX[i] + velX[i]).coerceIn(-MAX_COORD_BOUND, MAX_COORD_BOUND)
                posY[i] = (posY[i] + velY[i]).coerceIn(-MAX_COORD_BOUND, MAX_COORD_BOUND)
            }
        }

        // Re-center centroid at (0, 0)
        val meanX = posX.average()
        val meanY = posY.average()

        val nodeLayouts = mutableMapOf<String, ForceNodeLayout>()
        val neighborMap = mutableMapOf<String, Set<String>>()

        for (i in 0 until n) {
            val id = sortedEntities[i].entity.entityId.value
            val deg = degrees[i]
            val radiusDp = (21f + sqrt(deg.toFloat()) * 4.5f).coerceIn(21f, 38f)
            val rawX = (posX[i] - meanX).toFloat()
            val rawY = (posY[i] - meanY).toFloat()
            val finalX = if (isEInk) (rawX / 8f).roundToInt() * 8f else rawX
            val finalY = if (isEInk) (rawY / 8f).roundToInt() * 8f else rawY
            nodeLayouts[id] =
                ForceNodeLayout(
                    entityId = id,
                    x = finalX,
                    y = finalY,
                    degree = deg,
                    baseRadiusDp = radiusDp,
                )
            neighborMap[id] = neighborSets[i]
        }

        return ForceGraphLayoutResult(
            nodes = nodeLayouts,
            edges = edgeLayouts,
            neighborMap = neighborMap,
        )
    }

    /**
     * Computes a Sugiyama / Kuiver-inspired hierarchical layered layout:
     * - Assigns nodes to vertical layers (0..3) based on degree centrality and BFS distance from
     *   the primary protagonist/hubs.
     * - Runs 6 downward/upward barycenter sweeps to minimize edge crossings between adjacent layers.
     */
    private fun computeHierarchicalLayout(
        sortedEntities: List<RevealedEntity>,
        rawEdges: List<Triple<Int, Int, RevealedAssertion>>,
        degrees: IntArray,
        neighborSets: Array<MutableSet<String>>,
        edgeLayouts: List<ForceEdgeLayout>,
        isEInk: Boolean,
    ): ForceGraphLayoutResult {
        val n = sortedEntities.size
        val adj = Array(n) { mutableSetOf<Int>() }
        for ((u, v, _) in rawEdges) {
            adj[u].add(v)
            adj[v].add(u)
        }

        // Identify top hubs (up to 2 highest-degree nodes) as layer 0 roots
        val indicesByDegree = (0 until n).sortedByDescending { degrees[it] }
        val levels = IntArray(n) { -1 }
        val queue = ArrayDeque<Int>()
        val rootCount = if (n >= 5) 2 else 1
        for (k in 0 until min(rootCount, n)) {
            val rootIdx = indicesByDegree[k]
            if (degrees[rootIdx] > 0 || k == 0) {
                levels[rootIdx] = 0
                queue.addLast(rootIdx)
            }
        }

        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            val nextLevel = (levels[u] + 1).coerceAtMost(3)
            for (v in adj[u]) {
                if (levels[v] == -1) {
                    levels[v] = nextLevel
                    queue.addLast(v)
                }
            }
        }

        // Disconnected or peripheral nodes go to the bottom layer
        for (i in 0 until n) {
            if (levels[i] == -1) {
                levels[i] = if (n <= 4) 1 else 3
            }
        }

        // Group nodes by layer
        val layers =
            (0..3)
                .map { lvl ->
                    (0 until n).filter { levels[it] == lvl }.toMutableList()
                }.filter { it.isNotEmpty() }
                .toMutableList()

        // Barycenter crossing minimization (downward + upward sweeps)
        repeat(6) {
            for (l in 1 until layers.size) {
                val prevPositions =
                    layers[l - 1].withIndex().associate { (pos, nodeIdx) -> nodeIdx to pos }
                layers[l].sortBy { nodeIdx ->
                    val neighborPos = adj[nodeIdx].mapNotNull { prevPositions[it] }
                    if (neighborPos.isNotEmpty()) neighborPos.average() else nodeIdx.toDouble()
                }
            }
            for (l in layers.size - 2 downTo 0) {
                val nextPositions =
                    layers[l + 1].withIndex().associate { (pos, nodeIdx) -> nodeIdx to pos }
                layers[l].sortBy { nodeIdx ->
                    val neighborPos = adj[nodeIdx].mapNotNull { nextPositions[it] }
                    if (neighborPos.isNotEmpty()) neighborPos.average() else nodeIdx.toDouble()
                }
            }
        }

        val levelVerticalSpacing = if (isEInk) 160f else 140f
        val nodeHorizontalSpacing = if (isEInk) 152f else 136f
        val totalHeight = (layers.size - 1).coerceAtLeast(0) * levelVerticalSpacing

        val nodeLayouts = mutableMapOf<String, ForceNodeLayout>()
        val neighborMap = mutableMapOf<String, Set<String>>()

        layers.forEachIndexed { layerIdx, layerNodes ->
            val y = -totalHeight / 2f + layerIdx * levelVerticalSpacing
            val rowWidth = (layerNodes.size - 1).coerceAtLeast(0) * nodeHorizontalSpacing
            layerNodes.forEachIndexed { posIdx, nodeIdx ->
                val x = -rowWidth / 2f + posIdx * nodeHorizontalSpacing
                val staggerY =
                    if (layerNodes.size >= 5 && posIdx % 2 == 1) {
                        24f
                    } else {
                        0f
                    }
                val id = sortedEntities[nodeIdx].entity.entityId.value
                val deg = degrees[nodeIdx]
                val radiusDp = (21f + sqrt(deg.toFloat()) * 4.2f).coerceIn(21f, 36f)
                val finalX = if (isEInk) (x / 8f).roundToInt() * 8f else x
                val finalY =
                    if (isEInk) ((y + staggerY) / 8f).roundToInt() * 8f else (y + staggerY)
                nodeLayouts[id] =
                    ForceNodeLayout(
                        entityId = id,
                        x = finalX,
                        y = finalY,
                        degree = deg,
                        baseRadiusDp = radiusDp,
                    )
                neighborMap[id] = neighborSets[nodeIdx]
            }
        }

        return ForceGraphLayoutResult(
            nodes = nodeLayouts,
            edges = edgeLayouts,
            neighborMap = neighborMap,
        )
    }

    /**
     * Projects 2D [ForceNodeLayout] coordinates into 2D viewport pixel coordinates,
     * fitting the layout naturally to the viewport aspect ratio while applying zoom, pan,
     * and optional interactive per-node drag offsets.
     */
    fun projectNodes(
        entities: List<RevealedEntity>,
        layoutResult: ForceGraphLayoutResult,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        zoom: Float,
        panX: Float,
        panY: Float,
        nodeOffsets: Map<String, Pair<Float, Float>> = emptyMap(),
    ): List<ProjectedGraphNode> {
        if (entities.isEmpty()) return emptyList()

        val centerX = viewportWidthPx / 2f
        val centerY = viewportHeightPx / 2f

        val layouts = entities.mapNotNull { layoutResult.nodes[it.entity.entityId.value] }
        if (layouts.isEmpty()) return emptyList()

        val maxAbsX = layouts.maxOf { kotlin.math.abs(it.x) }.coerceAtLeast(90f)
        val maxAbsY = layouts.maxOf { kotlin.math.abs(it.y) }.coerceAtLeast(90f)

        // Fit smoothly into 70% of the available 2D viewport width and height
        // while keeping scale within comfortable bounds so small graphs aren't over-stretched
        // and E-Ink wider node coordinates preserve their wider spacing.
        val targetHalfWidth = (viewportWidthPx * 0.36f).coerceAtLeast(130f)
        val targetHalfHeight = (viewportHeightPx * 0.35f).coerceAtLeast(120f)
        val fitScaleX = (targetHalfWidth / maxAbsX).coerceIn(0.75f, 3.0f)
        val fitScaleY = (targetHalfHeight / maxAbsY).coerceIn(0.75f, 2.5f)
        val baseFitScale = min(fitScaleX, fitScaleY)
        // Expand comfortably across wide tablet or tall phone viewports
        val scaleX = (baseFitScale * 0.35f + fitScaleX * 0.65f)
        val scaleY = (baseFitScale * 0.55f + fitScaleY * 0.45f)

        val projected =
            entities.mapNotNull { revealed ->
                val id = revealed.entity.entityId.value
                val node = layoutResult.nodes[id] ?: return@mapNotNull null
                val (dragDx, dragDy) = nodeOffsets[id] ?: (0f to 0f)

                val screenX = centerX + (node.x * scaleX + panX + dragDx) * zoom
                val screenY = centerY + (node.y * scaleY + panY + dragDy) * zoom

                ProjectedGraphNode(
                    entity = revealed,
                    layout = node,
                    screenX = screenX,
                    screenY = screenY,
                    scale = zoom.coerceIn(0.65f, 2.2f),
                )
            }

        // Draw lower-degree nodes first and high-degree hubs on top
        return projected.sortedBy { it.layout.degree }
    }

    /**
     * Applies an interactive drag delta ([screenDx], [screenDy]) to [draggedEntityId] and runs
     * coupled 2D Hooke-spring attraction, Coulomb repulsion, and central gravity relaxation across
     * the graph according to [physicsMode].
     *
     * Time complexity: O(S * (V^2 + E)) where S <= 6 is the relaxation sub-step count.
     * Space complexity: O(V).
     */
    fun applyNodeDragStep(
        layoutResult: ForceGraphLayoutResult,
        currentOffsets: Map<String, Pair<Float, Float>>,
        draggedEntityId: String,
        screenDx: Float,
        screenDy: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        zoom: Float,
        physicsMode: GraphPhysicsMode = GraphPhysicsMode.LIVE_SPRING,
        pinnedEntityIds: Set<String> = emptySet(),
        isEInk: Boolean = false,
    ): Map<String, Pair<Float, Float>> {
        val nodes = layoutResult.nodes
        if (nodes.isEmpty() || !nodes.containsKey(draggedEntityId)) {
            return currentOffsets
        }

        val safeZoom = zoom.coerceAtLeast(0.4f)
        val updated = currentOffsets.toMutableMap()
        val (prevDx, prevDy) = updated[draggedEntityId] ?: (0f to 0f)
        val rawDraggedDx = (prevDx + screenDx / safeZoom).coerceIn(-720f, 720f)
        val rawDraggedDy = (prevDy + screenDy / safeZoom).coerceIn(-720f, 720f)
        updated[draggedEntityId] =
            if (isEInk) {
                ((rawDraggedDx / 4f).roundToInt() * 4f) to ((rawDraggedDy / 4f).roundToInt() * 4f)
            } else {
                rawDraggedDx to rawDraggedDy
            }

        if (physicsMode == GraphPhysicsMode.PIN_NODE || nodes.size <= 1) {
            return updated
        }

        val layouts = nodes.values.toList()
        val maxAbsX = layouts.maxOf { kotlin.math.abs(it.x) }.coerceAtLeast(90f)
        val maxAbsY = layouts.maxOf { kotlin.math.abs(it.y) }.coerceAtLeast(90f)
        val targetHalfWidth = (viewportWidthPx * 0.36f).coerceAtLeast(130f)
        val targetHalfHeight = (viewportHeightPx * 0.35f).coerceAtLeast(120f)
        val fitScaleX = (targetHalfWidth / maxAbsX).coerceIn(0.75f, 3.0f)
        val fitScaleY = (targetHalfHeight / maxAbsY).coerceIn(0.75f, 2.5f)
        val baseFitScale = min(fitScaleX, fitScaleY)
        val scaleX = (baseFitScale * 0.35f + fitScaleX * 0.65f).coerceAtLeast(0.5f)
        val scaleY = (baseFitScale * 0.55f + fitScaleY * 0.45f).coerceAtLeast(0.5f)

        val orderedIds = layouts.map { it.entityId }
        val idToIndex = orderedIds.withIndex().associate { (i, id) -> id to i }
        val n = orderedIds.size

        val posX = DoubleArray(n)
        val posY = DoubleArray(n)
        for (i in 0 until n) {
            val id = orderedIds[i]
            val baseNode = nodes.getValue(id)
            val (offX, offY) = updated[id] ?: (0f to 0f)
            posX[i] = (baseNode.x + offX / scaleX).toDouble()
            posY[i] = (baseNode.y + offY / scaleY).toDouble()
        }

        val springStrength =
            when (physicsMode) {
                GraphPhysicsMode.STRONG_GRAVITY -> 0.165
                GraphPhysicsMode.LIVE_SPRING -> 0.115
                GraphPhysicsMode.PIN_NODE -> 0.0
            }
        val gravityStrength =
            when (physicsMode) {
                GraphPhysicsMode.STRONG_GRAVITY -> 0.052
                GraphPhysicsMode.LIVE_SPRING -> 0.022
                GraphPhysicsMode.PIN_NODE -> 0.0
            }
        val idealDist = if (isEInk) 185.0 else 148.0
        val repulsion = if (isEInk) 28000.0 else 19000.0
        val minCollision = if (isEInk) 92.0 else 70.0
        val subSteps = if (isEInk) 3 else 5

        repeat(subSteps) {
            val forceX = DoubleArray(n)
            val forceY = DoubleArray(n)

            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    val dx = posX[i] - posX[j]
                    val dy = posY[i] - posY[j]
                    val distSq = dx * dx + dy * dy + 25.0
                    val dist = sqrt(distSq)
                    var repMag = repulsion / distSq
                    if (dist < minCollision) {
                        repMag += (minCollision - dist) * 3.8
                    }
                    val ux = dx / dist
                    val uy = dy / dist
                    forceX[i] += ux * repMag
                    forceY[i] += uy * repMag
                    forceX[j] -= ux * repMag
                    forceY[j] -= uy * repMag
                }
            }

            for (edge in layoutResult.edges) {
                val u = idToIndex[edge.sourceId] ?: continue
                val v = idToIndex[edge.targetId] ?: continue
                if (u == v) continue
                val dx = posX[v] - posX[u]
                val dy = posY[v] - posY[u]
                val dist = hypot(dx, dy).coerceAtLeast(1.0)
                val displacement = dist - idealDist
                val boost =
                    if (edge.sourceId == draggedEntityId || edge.targetId == draggedEntityId) {
                        1.45
                    } else {
                        1.0
                    }
                val springMag = springStrength * boost * displacement
                val ux = dx / dist
                val uy = dy / dist
                forceX[u] += ux * springMag
                forceY[u] += uy * springMag
                forceX[v] -= ux * springMag
                forceY[v] -= uy * springMag
            }

            for (i in 0 until n) {
                val id = orderedIds[i]
                if (id == draggedEntityId || pinnedEntityIds.contains(id)) continue
                forceX[i] -= posX[i] * gravityStrength
                forceY[i] -= posY[i] * gravityStrength
                val stepX = (forceX[i] * 0.28).coerceIn(-18.0, 18.0)
                val stepY = (forceY[i] * 0.28).coerceIn(-18.0, 18.0)
                posX[i] = (posX[i] + stepX).coerceIn(-MAX_COORD_BOUND * 1.3, MAX_COORD_BOUND * 1.3)
                posY[i] = (posY[i] + stepY).coerceIn(-MAX_COORD_BOUND * 1.3, MAX_COORD_BOUND * 1.3)
            }
        }

        for (i in 0 until n) {
            val id = orderedIds[i]
            if (id == draggedEntityId) continue
            val baseNode = nodes.getValue(id)
            val newOffX = ((posX[i] - baseNode.x) * scaleX).toFloat().coerceIn(-720f, 720f)
            val newOffY = ((posY[i] - baseNode.y) * scaleY).toFloat().coerceIn(-720f, 720f)
            updated[id] =
                if (isEInk) {
                    ((newOffX / 4f).roundToInt() * 4f) to ((newOffY / 4f).roundToInt() * 4f)
                } else {
                    newOffX to newOffY
                }
        }

        return updated
    }

    private fun fnv1a64(input: String): Long {
        var hash = -0x340d631b7bdddcdbL
        val prime = 0x100000001b3L
        for (ch in input) {
            hash = hash xor ch.code.toLong()
            hash *= prime
        }
        return hash
    }
}
