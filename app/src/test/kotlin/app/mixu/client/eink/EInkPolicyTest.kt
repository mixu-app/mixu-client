// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.eink

import androidx.compose.ui.unit.dp
import app.mixu.graph.EpistemicStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EInkPolicyTest {
    @Test
    fun userOverrideTakesPrecedenceOverHardwareDetection() {
        // User explicitly disables E-Ink on a Boox device -> must stay OFF
        val policyDisabledOnBoox =
            EInkDetector.resolvePolicy(
                userMode = EInkMode.OFF,
                isHardwareDetected = true,
            )
        assertFalse(policyDisabledOnBoox.isActive)
        assertTrue(policyDisabledOnBoox.allowAnimations)
        assertFalse(policyDisabledOnBoox.useDiscreteTransitions)

        // User explicitly forces E-Ink on a generic phone -> must activate E-Ink
        val policyForcedOnPhone =
            EInkDetector.resolvePolicy(
                userMode = EInkMode.FORCE_ON,
                isHardwareDetected = false,
            )
        assertTrue(policyForcedOnPhone.isActive)
        assertFalse(policyForcedOnPhone.allowAnimations)
        assertTrue(policyForcedOnPhone.useDiscreteTransitions)
        assertEquals(PageTurnMode.INSTANT, policyForcedOnPhone.pageTurnMode)
    }

    @Test
    fun autoModeFollowsHardwareDetection() {
        val onEInk = EInkDetector.resolvePolicy(userMode = EInkMode.AUTO, isHardwareDetected = true)
        assertTrue(onEInk.isActive)

        val onLcd = EInkDetector.resolvePolicy(userMode = EInkMode.AUTO, isHardwareDetected = false)
        assertFalse(onLcd.isActive)
    }

    @Test
    fun policyInvariantsAreEnforced() {
        // Active E-Ink cannot have continuous animations
        assertFailsWith<IllegalArgumentException> {
            EInkPolicy(
                isActive = true,
                allowAnimations = true,
                useDiscreteTransitions = true,
                pageTurnMode = PageTurnMode.INSTANT,
                fullRefreshInterval = 10,
                minStrokeWidth = 2.dp,
                highContrastStrokes = true,
            )
        }

        // Active E-Ink must use discrete transitions
        assertFailsWith<IllegalArgumentException> {
            EInkPolicy(
                isActive = true,
                allowAnimations = false,
                useDiscreteTransitions = false,
                pageTurnMode = PageTurnMode.INSTANT,
                fullRefreshInterval = 10,
                minStrokeWidth = 2.dp,
                highContrastStrokes = true,
            )
        }

        // Active E-Ink must use instant page turns
        assertFailsWith<IllegalArgumentException> {
            EInkPolicy(
                isActive = true,
                allowAnimations = false,
                useDiscreteTransitions = true,
                pageTurnMode = PageTurnMode.SLIDE,
                fullRefreshInterval = 10,
                minStrokeWidth = 2.dp,
                highContrastStrokes = true,
            )
        }
    }

    @Test
    fun epistemicStrokesProvideColorIndependentDistinction() {
        val policy = EInkPolicy.EINK_ACTIVE

        val obsStroke = policy.getEpistemicStroke(EpistemicStatus.OBSERVED)
        assertEquals(StrokePattern.SOLID, obsStroke.pattern)
        assertTrue(obsStroke.strokeWidthDp >= 2.5.dp)
        assertEquals("[OBSERVED]", obsStroke.symbolBadge)

        val corrStroke = policy.getEpistemicStroke(EpistemicStatus.CORROBORATED)
        assertEquals(StrokePattern.SOLID, corrStroke.pattern)
        assertEquals("[CORROBORATED]", corrStroke.symbolBadge)

        val claimStroke = policy.getEpistemicStroke(EpistemicStatus.CLAIMED)
        assertEquals(StrokePattern.DASHED, claimStroke.pattern)
        assertEquals("[CLAIMED]", claimStroke.symbolBadge)

        val inferStroke = policy.getEpistemicStroke(EpistemicStatus.INFERRED)
        assertEquals(StrokePattern.DOTTED, inferStroke.pattern)
        assertEquals("[INFERRED]", inferStroke.symbolBadge)

        val contraStroke = policy.getEpistemicStroke(EpistemicStatus.CONTRADICTED)
        assertEquals(StrokePattern.DOUBLE, contraStroke.pattern)
        assertEquals("[CONTRADICTED]", contraStroke.symbolBadge)

        val retractStroke = policy.getEpistemicStroke(EpistemicStatus.RETRACTED)
        assertEquals(StrokePattern.DASHED, retractStroke.pattern)
        assertEquals("[RETRACTED]", retractStroke.symbolBadge)

        val unresStroke = policy.getEpistemicStroke(EpistemicStatus.UNRESOLVED)
        assertEquals(StrokePattern.DASHED, unresStroke.pattern)
        assertEquals("[UNRESOLVED]", unresStroke.symbolBadge)
    }

    @Test
    fun hardwareDetectorIdentifiesKnownVendors() {
        assertTrue(
            EInkDetector.isProbableEInkDevice(
                manufacturer = "Onyx",
                model = "Boox Page",
                brand = "Onyx",
            ),
        )
        assertTrue(
            EInkDetector.isProbableEInkDevice(
                manufacturer = "Haoqing",
                model = "Meebook M6",
                brand = "Meebook",
            ),
        )
        assertTrue(
            EInkDetector.isProbableEInkDevice(
                manufacturer = "Hisense",
                model = "A9",
                brand = "Hisense",
            ),
        )
        assertTrue(
            EInkDetector.isProbableEInkDevice(
                manufacturer = "Bigme",
                model = "B751",
                brand = "Bigme",
            ),
        )

        assertFalse(
            EInkDetector.isProbableEInkDevice(
                manufacturer = "Google",
                model = "Pixel 8",
                brand = "Google",
            ),
        )
        assertFalse(
            EInkDetector.isProbableEInkDevice(
                manufacturer = "Samsung",
                model = "SM-S918B",
                brand = "Samsung",
            ),
        )
    }
}
