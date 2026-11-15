// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mixu.client.eink.EInkPolicy

private const val MIN_CHROME_SCALE = 0.75f
private const val MAX_CHROME_SCALE = 1.5f
private const val MAX_CORNER_RADIUS_DP = 100f

/** Central chrome defaults, resolved through the existing theme pack and theme provider. */
data class MixuChromeAppearance(
    val typography: MixuTypography = createMixuAppTypography(),
    val cardCornerRadius: Dp = 16.dp,
    val controlCornerRadius: Dp = 12.dp,
    val pillCornerRadius: Dp = 50.dp,
    val coverCornerRadius: Dp = 8.dp,
)

/** E-Ink shapes and typography take precedence over decorative appearance overrides. */
fun resolveMixuChromeAppearance(
    pack: MixuThemePack? = null,
    isEInk: Boolean = false,
    fallbackTypography: MixuTypography = createMixuAppTypography(),
): MixuChromeAppearance {
    val defaults = MixuChromeAppearance(typography = fallbackTypography)
    if (isEInk || pack?.resolvedBaseMode() == MixuThemeMode.EINK) {
        return MixuChromeAppearance(
            typography = createMixuAppTypography(),
            cardCornerRadius = 0.dp,
            controlCornerRadius = 0.dp,
            pillCornerRadius = 0.dp,
            coverCornerRadius = 0.dp,
        )
    }
    val tokens = pack?.chrome ?: return defaults
    val family =
        when (tokens.fontFamily?.trim()?.uppercase()) {
            "SANS_SERIF", "SANS", "DEFAULT" -> FontFamily.SansSerif
            "SERIF" -> FontFamily.Serif
            "MONOSPACE" -> FontFamily.Monospace
            else -> fallbackTypography.fontFamily
        }
    val scale =
        tokens.fontScaleMultiplier
            ?.takeIf { it.isFinite() }
            ?.coerceIn(MIN_CHROME_SCALE, MAX_CHROME_SCALE) ?: fallbackTypography.scaleMultiplier
    return MixuChromeAppearance(
        typography =
            if (tokens.fontFamily != null || tokens.fontScaleMultiplier != null) {
                createMixuAppTypography(family, scale)
            } else {
                fallbackTypography
            },
        cardCornerRadius = tokens.cardCornerRadiusDp.radiusOr(defaults.cardCornerRadius),
        controlCornerRadius = tokens.controlCornerRadiusDp.radiusOr(defaults.controlCornerRadius),
        pillCornerRadius = tokens.pillCornerRadiusDp.radiusOr(defaults.pillCornerRadius),
        coverCornerRadius = tokens.coverCornerRadiusDp.radiusOr(defaults.coverCornerRadius),
    )
}

private fun Float?.radiusOr(fallback: Dp): Dp =
    this?.takeIf { it.isFinite() }?.coerceIn(0f, MAX_CORNER_RADIUS_DP)?.dp ?: fallback

/** Resolve display policy from either an explicit mode or an imported monochrome pack. */
fun resolveMixuEInkPolicy(
    mode: MixuThemeMode,
    policy: EInkPolicy,
    pack: MixuThemePack?,
): EInkPolicy =
    if (!policy.isActive &&
        (mode == MixuThemeMode.EINK || pack?.resolvedBaseMode() == MixuThemeMode.EINK)
    ) {
        EInkPolicy.EINK_ACTIVE
    } else {
        policy
    }
