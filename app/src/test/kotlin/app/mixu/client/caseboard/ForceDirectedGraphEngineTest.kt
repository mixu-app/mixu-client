// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.caseboard

import app.mixu.client.config.GraphDimensionMode
import app.mixu.graph.TemporalProjectionEngine
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ForceDirectedGraphEngineTest {
    @Test
    fun `computeLayout produces deterministic 3D, 2D, and Hierarchical coordinates`() {
        val pack = SyntheticMysteryData.createMysteryGraphPack()
        val projection = TemporalProjectionEngine(pack).project(4)

        val layout3dFirst =
            ForceDirectedGraphEngine.computeLayout(
                editionId = pack.manifest.editionId.value,
                entities = projection.entities,
                assertions = projection.assertions,
                is3D = true,
                mode = GraphDimensionMode.PERSPECTIVE_3D,
            )
        val layout3dSecond =
            ForceDirectedGraphEngine.computeLayout(
                editionId = pack.manifest.editionId.value,
                entities = projection.entities,
                assertions = projection.assertions,
                is3D = true,
                mode = GraphDimensionMode.PERSPECTIVE_3D,
            )

        assertEquals(projection.entities.size, layout3dFirst.nodes.size)
        assertEquals(layout3dFirst.nodes, layout3dSecond.nodes)
        assertTrue(layout3dFirst.edges.isNotEmpty())

        // Verify 3D layout utilizes the Z axis while 2D layout keeps Z == 0f
        assertTrue(layout3dFirst.nodes.values.any { abs(it.z) > 1.0f })

        val layout2d =
            ForceDirectedGraphEngine.computeLayout(
                editionId = pack.manifest.editionId.value,
                entities = projection.entities,
                assertions = projection.assertions,
                is3D = false,
                mode = GraphDimensionMode.FORCE_2D,
            )
        assertTrue(layout2d.nodes.values.all { it.z == 0f })

        // Verify Hierarchical layout assigns distinct vertical layers and keeps Z == 0f
        val layoutHierarchical =
            ForceDirectedGraphEngine.computeLayout(
                editionId = pack.manifest.editionId.value,
                entities = projection.entities,
                assertions = projection.assertions,
                is3D = false,
                mode = GraphDimensionMode.HIERARCHICAL,
            )
        assertEquals(projection.entities.size, layoutHierarchical.nodes.size)
        assertTrue(layoutHierarchical.nodes.values.all { it.z == 0f })
        assertTrue(
            layoutHierarchical.nodes.values
                .map { it.y }
                .distinct()
                .size >= 2,
        )

        // Verify E-Ink mode snaps coordinates to 8px grid and enforces 2D planar Z == 0f
        val layoutEInk =
            ForceDirectedGraphEngine.computeLayout(
                editionId = pack.manifest.editionId.value,
                entities = projection.entities,
                assertions = projection.assertions,
                is3D = true,
                isEInk = true,
                mode = GraphDimensionMode.PERSPECTIVE_3D,
            )
        assertTrue(layoutEInk.nodes.values.all { it.z == 0f })
        assertTrue(layoutEInk.nodes.values.all { it.x % 8f == 0f && it.y % 8f == 0f })
    }

    @Test
    fun `projectNodes applies 3D orbit rotation and sorts back-to-front by depthZ`() {
        val pack = SyntheticMysteryData.createMysteryGraphPack()
        val projection = TemporalProjectionEngine(pack).project(4)
        val layout3d =
            ForceDirectedGraphEngine.computeLayout(
                editionId = pack.manifest.editionId.value,
                entities = projection.entities,
                assertions = projection.assertions,
                is3D = true,
                mode = GraphDimensionMode.PERSPECTIVE_3D,
            )

        val projectedAtDefaultOrbit =
            ForceDirectedGraphEngine.projectNodes(
                entities = projection.entities,
                layoutResult = layout3d,
                viewportWidthPx = 1200f,
                viewportHeightPx = 900f,
                zoom = 1.0f,
                panX = 0f,
                panY = 0f,
                orbitYawRad = ForceDirectedGraphEngine.DEFAULT_YAW_RAD,
                orbitPitchRad = ForceDirectedGraphEngine.DEFAULT_PITCH_RAD,
                is3D = true,
            )

        assertEquals(projection.entities.size, projectedAtDefaultOrbit.size)

        // Verify back-to-front depth sorting (Painter's algorithm)
        for (i in 0 until projectedAtDefaultOrbit.lastIndex) {
            assertTrue(
                projectedAtDefaultOrbit[i].depthZ <= projectedAtDefaultOrbit[i + 1].depthZ,
                "Expected depthZ ascending order at index $i",
            )
        }

        // Orbiting camera yaw changes projected 2D screen positions
        val projectedRotated =
            ForceDirectedGraphEngine.projectNodes(
                entities = projection.entities,
                layoutResult = layout3d,
                viewportWidthPx = 1200f,
                viewportHeightPx = 900f,
                zoom = 1.0f,
                panX = 0f,
                panY = 0f,
                orbitYawRad = ForceDirectedGraphEngine.DEFAULT_YAW_RAD + 0.85f,
                orbitPitchRad = ForceDirectedGraphEngine.DEFAULT_PITCH_RAD + 0.25f,
                is3D = true,
            )
        assertNotEquals(
            projectedAtDefaultOrbit.map { it.screenX to it.screenY },
            projectedRotated.map { it.screenX to it.screenY },
        )
    }
}
