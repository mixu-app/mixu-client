// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.caseboard

import app.mixu.client.config.GraphDimensionMode
import app.mixu.graph.ObjectRef
import app.mixu.graph.RevealedAssertion
import app.mixu.graph.RevealedEntity
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3D/2D node coordinate and degree metadata computed by [ForceDirectedGraphEngine].
 */
data class ForceNodeLayout(
    val entityId: String,
    val x: Float,
    val y: Float,
    val z: Float,
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
 * Complete layout result for a revealed narrative graph snapshot.
 */
data class ForceGraphLayoutResult(
    val nodes: Map<String, ForceNodeLayout>,
    val edges: List<ForceEdgeLayout>,
    val neighborMap: Map<String, Set<String>>,
)

/**
 * 2D screen-space projection of a [ForceNodeLayout] after applying 3D orbit rotation,
 * perspective camera projection, zoom, and pan.
 */
data class ProjectedGraphNode(
    val entity: RevealedEntity,
    val layout: ForceNodeLayout,
    val screenX: Float,
    val screenY: Float,
    val depthZ: Float,
    val perspectiveScale: Float,
    val depthNorm: Float,
)

/**
 * Pure-Kotlin deterministic Knowledge Graph layout and perspective projection engine for Compose,
 * combining techniques from `3d-force-graph`, `d3-force`, and `Kuiver` with dedicated E-Ink
 * physics tuning.
 *
 * Supports three layout modes ([GraphDimensionMode]):
 * 1. [GraphDimensionMode.PERSPECTIVE_3D]: 3D spherical force-directed layout with camera orbit
 *    projection and Painter's Algorithm depth sorting.
 * 2. [GraphDimensionMode.FORCE_2D]: 2D planar spring-embedder layout.
 * 3. [GraphDimensionMode.HIERARCHICAL]: Sugiyama-inspired layered topological layout with
 *    barycenter crossing minimization.
 *
 * E-Ink Physics Policy ([isEInk] = true):
 * - Freezes layout in a single pre-settled pass (zero continuous animation ticks).
 * - Increases inter-node repulsion (+58%), spring rest distance (+28%), and minimum collision
 *   clearance (+40%), and snaps coordinates to an 8px grid so monochrome e-paper labels stay
 *   uncluttered and ghost-free.
 *
 * Time complexity: O(I * (V^2 + E)) for force modes (I = 80), O(V log V + E) for hierarchical mode.
 * Space complexity: O(V + E).
 */
object ForceDirectedGraphEngine {
    private const val SIMULATION_ITERATIONS = 80
    private const val REPULSION_STANDARD = 24000.0
    private const val REPULSION_EINK = 38000.0
    private const val SPRING_STRENGTH_STANDARD = 0.065
    private const val SPRING_STRENGTH_EINK = 0.052
    private const val IDEAL_LINK_DISTANCE_STANDARD = 152.0
    private const val IDEAL_LINK_DISTANCE_EINK = 195.0
    private const val CENTER_GRAVITY_STANDARD = 0.032
    private const val CENTER_GRAVITY_EINK = 0.024
    private const val MIN_COLLISION_STANDARD = 68.0
    private const val MIN_COLLISION_EINK = 96.0
    private const val MAX_COORD_BOUND = 360.0
    private const val CAMERA_DISTANCE = 920f
    private const val GOLDEN_ANGLE = 2.399963229728653

    fun computeLayout(
        editionId: String,
        entities: List<RevealedEntity>,
        assertions: List<RevealedAssertion>,
        is3D: Boolean = true,
        isEInk: Boolean = false,
        mode: GraphDimensionMode =
            if (is3D) GraphDimensionMode.PERSPECTIVE_3D else GraphDimensionMode.FORCE_2D,
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
                val (hubIdx, hubAssertion) = distinctByNode.first()
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

        val use3D = (mode == GraphDimensionMode.PERSPECTIVE_3D) && !isEInk
        val repulsionStrength = if (isEInk) REPULSION_EINK else REPULSION_STANDARD
        val springStrength = if (isEInk) SPRING_STRENGTH_EINK else SPRING_STRENGTH_STANDARD
        val idealLinkDistance =
            if (isEInk) IDEAL_LINK_DISTANCE_EINK else IDEAL_LINK_DISTANCE_STANDARD
        val centerGravity = if (isEInk) CENTER_GRAVITY_EINK else CENTER_GRAVITY_STANDARD
        val minCollisionDistance =
            if (isEInk) MIN_COLLISION_EINK else MIN_COLLISION_STANDARD

        val posX = DoubleArray(n)
        val posY = DoubleArray(n)
        val posZ = DoubleArray(n)
        val velX = DoubleArray(n)
        val velY = DoubleArray(n)
        val velZ = DoubleArray(n)

        val initialRadius = (75.0 + sqrt(n.toDouble()) * 42.0).coerceAtMost(260.0)
        for (i in 0 until n) {
            val entityId = sortedEntities[i].entity.entityId.value
            val hashJitter =
                ((fnv1a64("$editionId:$entityId") and 0xFFFFL).toDouble() / 65535.0) - 0.5
            if (n == 1) {
                posX[i] = 0.0
                posY[i] = 0.0
                posZ[i] = 0.0
            } else if (use3D) {
                val yNorm = 1.0 - (i.toDouble() / (n - 1).toDouble()) * 2.0
                val radiusAtY = sqrt(max(0.0, 1.0 - yNorm * yNorm))
                val theta = GOLDEN_ANGLE * i + hashJitter * 0.35
                posX[i] = cos(theta) * radiusAtY * initialRadius
                posY[i] = yNorm * initialRadius * 0.85
                posZ[i] = sin(theta) * radiusAtY * initialRadius
            } else {
                val r = initialRadius * sqrt((i + 0.5) / n.toDouble())
                val theta = GOLDEN_ANGLE * i + hashJitter * 0.25
                posX[i] = cos(theta) * r
                posY[i] = sin(theta) * r * 0.85
                posZ[i] = 0.0
            }
        }

        // Pre-settled damped force simulation (no continuous runtime animation loop)
        for (iter in 0 until SIMULATION_ITERATIONS) {
            val alpha = 1.0 - (iter.toDouble() / SIMULATION_ITERATIONS.toDouble()) * 0.78
            val damping = 0.62

            val forceX = DoubleArray(n)
            val forceY = DoubleArray(n)
            val forceZ = DoubleArray(n)

            // 1. Many-body Coulomb repulsion + minimum collision distance
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    val dx = posX[i] - posX[j]
                    val dy = posY[i] - posY[j]
                    val dz = if (use3D) posZ[i] - posZ[j] else 0.0
                    val distSq = dx * dx + dy * dy + dz * dz + 16.0
                    val dist = sqrt(distSq)

                    val degreeBoost = 1.0 + 0.14 * (degrees[i] + degrees[j])
                    var repMag = (repulsionStrength * degreeBoost) / distSq

                    if (dist < minCollisionDistance) {
                        repMag += (minCollisionDistance - dist) * 4.8
                    }

                    val ux = if (dist > 0.001) dx / dist else 1.0
                    val uy = if (dist > 0.001) dy / dist else 0.0
                    val uz = if (use3D && dist > 0.001) dz / dist else 0.0

                    forceX[i] += ux * repMag
                    forceY[i] += uy * repMag
                    forceZ[i] += uz * repMag

                    forceX[j] -= ux * repMag
                    forceY[j] -= uy * repMag
                    forceZ[j] -= uz * repMag
                }
            }

            // 2. Hooke spring attraction along assertion edges
            for ((u, v, _) in rawEdges) {
                val dx = posX[v] - posX[u]
                val dy = posY[v] - posY[u]
                val dz = if (use3D) posZ[v] - posZ[u] else 0.0
                val dist = sqrt(dx * dx + dy * dy + dz * dz + 1.0)
                val displacement = dist - idealLinkDistance
                val springMag = springStrength * displacement

                val ux = dx / dist
                val uy = dy / dist
                val uz = if (use3D) dz / dist else 0.0

                forceX[u] += ux * springMag
                forceY[u] += uy * springMag
                forceZ[u] += uz * springMag

                forceX[v] -= ux * springMag
                forceY[v] -= uy * springMag
                forceZ[v] -= uz * springMag
            }

            // 3. Center gravity (higher-degree hubs gravitate closer to origin)
            for (i in 0 until n) {
                val hubGravity = centerGravity * (1.0 + min(degrees[i], 8) * 0.08)
                forceX[i] -= posX[i] * hubGravity
                forceY[i] -= posY[i] * hubGravity
                if (use3D) {
                    forceZ[i] -= posZ[i] * hubGravity
                }
            }

            // 4. Integrate velocities & positions with cooling schedule
            for (i in 0 until n) {
                velX[i] = (velX[i] + forceX[i] * alpha) * damping
                velY[i] = (velY[i] + forceY[i] * alpha) * damping
                velZ[i] = if (use3D) (velZ[i] + forceZ[i] * alpha) * damping else 0.0

                posX[i] = (posX[i] + velX[i]).coerceIn(-MAX_COORD_BOUND, MAX_COORD_BOUND)
                posY[i] = (posY[i] + velY[i]).coerceIn(-MAX_COORD_BOUND, MAX_COORD_BOUND)
                posZ[i] =
                    if (use3D) {
                        (posZ[i] + velZ[i]).coerceIn(-MAX_COORD_BOUND, MAX_COORD_BOUND)
                    } else {
                        0.0
                    }
            }
        }

        val avgX = posX.average()
        val avgY = posY.average()
        val avgZ = if (use3D) posZ.average() else 0.0

        val nodeLayouts = mutableMapOf<String, ForceNodeLayout>()
        val neighborMap = mutableMapOf<String, Set<String>>()

        for (i in 0 until n) {
            val id = sortedEntities[i].entity.entityId.value
            val deg = degrees[i]
            val radiusDp = (20f + sqrt(deg.toFloat()) * 4.2f).coerceIn(20f, 36f)
            val rawX = (posX[i] - avgX).toFloat()
            val rawY = (posY[i] - avgY).toFloat()
            val rawZ = if (use3D) (posZ[i] - avgZ).toFloat() else 0f

            // Snap to 8px discrete grid when E-Ink mode is active
            val finalX = if (isEInk) (rawX / 8f).roundToInt() * 8f else rawX
            val finalY = if (isEInk) (rawY / 8f).roundToInt() * 8f else rawY

            nodeLayouts[id] =
                ForceNodeLayout(
                    entityId = id,
                    x = finalX,
                    y = finalY,
                    z = rawZ,
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
     * Sugiyama / Kuiver-inspired hierarchical layered layout with BFS layer assignment from
     * highest-degree hubs and barycenter crossing minimization.
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

        // Assign layers via BFS starting from top-degree hubs (core characters at top layer 0)
        val levels = IntArray(n) { -1 }
        val indicesByDegree = (0 until n).sortedByDescending { degrees[it] }
        val hubCount = max(1, min(3, (n + 3) / 4))
        val queue = ArrayDeque<Int>()

        for (idx in indicesByDegree.take(hubCount)) {
            if (degrees[idx] > 0) {
                levels[idx] = 0
                queue.addLast(idx)
            }
        }
        if (queue.isEmpty() && n > 0) {
            levels[0] = 0
            queue.addLast(0)
        }

        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            for (v in adj[u]) {
                if (levels[v] == -1) {
                    levels[v] = (levels[u] + 1).coerceAtMost(3)
                    queue.addLast(v)
                }
            }
        }

        val maxAssigned = levels.maxOrNull()?.coerceAtLeast(0) ?: 0
        for (i in 0 until n) {
            if (levels[i] == -1) {
                levels[i] = min(3, maxAssigned + 1)
            }
        }

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

        val levelVerticalSpacing = if (isEInk) 150f else 135f
        val nodeHorizontalSpacing = if (isEInk) 145f else 128f
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
                        22f
                    } else {
                        0f
                    }
                val id = sortedEntities[nodeIdx].entity.entityId.value
                val deg = degrees[nodeIdx]
                val radiusDp = (20f + sqrt(deg.toFloat()) * 4.2f).coerceIn(20f, 36f)
                nodeLayouts[id] =
                    ForceNodeLayout(
                        entityId = id,
                        x = x,
                        y = y + staggerY,
                        z = 0f,
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
     * Projects 3D/2D [ForceNodeLayout] coordinates into 2D viewport pixel coordinates,
     * sorted back-to-front by camera depth `depthZ` (Painter's Algorithm).
     */
    fun projectNodes(
        entities: List<RevealedEntity>,
        layoutResult: ForceGraphLayoutResult,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        zoom: Float,
        panX: Float,
        panY: Float,
        orbitYawRad: Float,
        orbitPitchRad: Float,
        is3D: Boolean,
    ): List<ProjectedGraphNode> {
        if (entities.isEmpty()) return emptyList()

        val centerX = viewportWidthPx / 2f
        val centerY = viewportHeightPx / 2f
        val minDim = min(viewportWidthPx, viewportHeightPx).coerceAtLeast(320f)
        val viewportFitScale = (minDim / 560f).coerceIn(0.55f, 2.2f)

        val cosYaw = if (is3D) cos(orbitYawRad) else 1f
        val sinYaw = if (is3D) sin(orbitYawRad) else 0f
        val cosPitch = if (is3D) cos(orbitPitchRad) else 1f
        val sinPitch = if (is3D) sin(orbitPitchRad) else 0f

        val rawProjected =
            entities.mapNotNull { revealed ->
                val node =
                    layoutResult.nodes[revealed.entity.entityId.value] ?: return@mapNotNull null

                val rx = node.x * cosYaw + node.z * sinYaw
                val rz1 = -node.x * sinYaw + node.z * cosYaw
                val ry = node.y * cosPitch - rz1 * sinPitch
                val rz = node.y * sinPitch + rz1 * cosPitch

                val perspectiveScale =
                    if (is3D) {
                        (CAMERA_DISTANCE / (CAMERA_DISTANCE - rz).coerceAtLeast(260f))
                            .coerceIn(0.68f, 1.48f)
                    } else {
                        1.0f
                    }

                val worldScale = viewportFitScale * zoom * perspectiveScale
                val screenX = centerX + (rx * viewportFitScale * perspectiveScale + panX) * zoom
                val screenY = centerY + (ry * viewportFitScale * perspectiveScale + panY) * zoom

                ProjectedGraphNode(
                    entity = revealed,
                    layout = node,
                    screenX = screenX,
                    screenY = screenY,
                    depthZ = rz,
                    perspectiveScale = worldScale / viewportFitScale,
                    depthNorm = ((rz + 300f) / 600f).coerceIn(0f, 1f),
                )
            }

        return rawProjected.sortedBy { it.depthZ }
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

    val DEFAULT_YAW_RAD = (PI / 7.5).toFloat()
    val DEFAULT_PITCH_RAD = (-PI / 11.0).toFloat()
}
