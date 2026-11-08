// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Scalable typography definitions adapted for high legibility on mobile and E-Ink screens.
 */
data class MixuTypography(
    val display: TextStyle,
    val titleLarge: TextStyle,
    val titleMedium: TextStyle,
    val bodyLarge: TextStyle,
    val bodyMedium: TextStyle,
    val labelLarge: TextStyle,
    val labelMedium: TextStyle,
    val caption: TextStyle,
    val fontFamily: FontFamily = FontFamily.Default,
    val scaleMultiplier: Float = 1.0f,
    val lineHeightMultiplier: Float = 1.5f,
)

fun createMixuTypography(
    fontFamily: FontFamily = FontFamily.Default,
    scaleMultiplier: Float = 1.0f,
    lineHeightMultiplier: Float = 1.5f,
): MixuTypography {
    val scale = scaleMultiplier.coerceIn(0.75f, 2.5f)
    val lh = lineHeightMultiplier.coerceIn(1.1f, 2.5f)

    return MixuTypography(
        display =
            TextStyle(
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = (28f * scale).sp,
                lineHeight = (28f * scale * lh).sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = fontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = (22f * scale).sp,
                lineHeight = (22f * scale * lh).sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = fontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = (18f * scale).sp,
                lineHeight = (18f * scale * lh).sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = fontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = (16f * scale).sp,
                lineHeight = (16f * scale * lh).sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = fontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = (14f * scale).sp,
                lineHeight = (14f * scale * lh).sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = fontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = (14f * scale).sp,
                lineHeight = (14f * scale * 1.2f).sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = fontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = (12f * scale).sp,
                lineHeight = (12f * scale * 1.2f).sp,
            ),
        caption =
            TextStyle(
                fontFamily = fontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = (11f * scale).sp,
                lineHeight = (11f * scale * 1.2f).sp,
            ),
        fontFamily = fontFamily,
        scaleMultiplier = scale,
        lineHeightMultiplier = lh,
    )
}
