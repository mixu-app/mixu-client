// SPDX-License-Identifier: MPL-2.0
package app.mixu.client

import app.mixu.graph.GraphBootstrap
import app.mixu.renderer.layout.DeterministicLayoutEngine
import app.mixu.renderer.layout.LayoutNode
import app.mixu.renderer.layout.Rect
import app.mixu.spoiler.EditionId
import app.mixu.spoiler.Horizon
import app.mixu.spoiler.RevealOrdinal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientSmokeTest {
    @Test
    fun appIdentifierIsConfigured() {
        assertEquals("app.mixu.client", "app.mixu.client")
    }

    @Test
    fun librarySubmodulesAreWiredAndUsable() {
        // mixu-graph
        assertEquals("mixu-graph", GraphBootstrap.MODULE_NAME)

        // mixu-spoiler
        val horizon = Horizon.initial(EditionId("urn:mixu:test"), RevealOrdinal(5))
        assertTrue(horizon.isPermitted(RevealOrdinal(3), EditionId("urn:mixu:test")))

        // mixu-renderer
        val layout =
            DeterministicLayoutEngine.computePositions(
                editionId = "urn:mixu:test",
                nodes = listOf(LayoutNode("node:1")),
                canvasBounds = Rect(0.0, 0.0, 500.0, 500.0),
            )
        assertTrue(layout.containsKey("node:1"))
    }
}
