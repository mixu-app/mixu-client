// SPDX-License-Identifier: MPL-2.0
@file:Suppress("ktlint:standard:function-naming")

package app.mixu.client.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mixu.client.R
import app.mixu.client.theme.MixuTheme
import app.mixu.graph.EntityType

/**
 * Renders the official Mixu brand mark (an open book whose negative space forms a lowercase `e`)
 * following the canonical brand specification (`#211431` plate and `#E7DDF6` mark on standard
 * displays, or pure monochrome on E-Ink).
 */
@Composable
fun MixuBrandLogo(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    cornerRadius: Dp = 8.dp,
) {
    val colors = MixuTheme.colors
    val isEInk = MixuTheme.einkPolicy.isActive || colors.isEInk
    val plateColor = if (isEInk) colors.primary else Color(0xFF211431)
    val markTint = if (isEInk) colors.onPrimary else Color(0xFFE7DDF6)
    val shape = RoundedCornerShape(if (isEInk) 2.dp else cornerRadius)

    Box(
        modifier =
            modifier
                .size(size)
                .background(color = plateColor, shape = shape)
                .then(
                    if (isEInk) {
                        Modifier.border(
                            BorderStroke(1.5.dp, colors.outline),
                            shape = shape,
                        )
                    } else {
                        Modifier
                    },
                ).padding(size * 0.14f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_mixu_logo_mark),
            contentDescription = "Mixu Logo",
            tint = markTint,
            modifier = Modifier.size(size * 0.72f),
        )
    }
}

/**
 * Pure-Kotlin SVG vector icons ([ImageVector]) and [DrawScope] vector glyphs for Mixu UI and
 * 2D Knowledge Graph nodes.
 *
 * Designed with clean geometric strokes so icons render crisply on both high-DPI OLED/LCD screens
 * and monochrome E-Ink displays without bitmap aliasing or font-fallback dependencies.
 */
object MixuIcons {
    val Library: ImageVector by lazy {
        ImageVector
            .Builder(
                name = "MixuLibrary",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    // Left book spine
                    moveTo(4f, 19f)
                    lineTo(4f, 5f)
                    lineTo(8f, 5f)
                    lineTo(8f, 19f)
                    close()
                    // Middle book spine
                    moveTo(8f, 19f)
                    lineTo(8f, 4f)
                    lineTo(13f, 4f)
                    lineTo(13f, 19f)
                    close()
                    // Tilted right book
                    moveTo(13f, 19f)
                    lineTo(15.5f, 5.5f)
                    lineTo(19.8f, 6.5f)
                    lineTo(17.3f, 19f)
                    close()
                    // Shelf baseline
                    moveTo(2.5f, 19f)
                    lineTo(21.5f, 19f)
                }
            }.build()
    }

    val Reader: ImageVector by lazy {
        ImageVector
            .Builder(
                name = "MixuReader",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    // Open book left page
                    moveTo(12f, 6.5f)
                    quadTo(8f, 4.5f, 3f, 5.5f)
                    lineTo(3f, 18.5f)
                    quadTo(8f, 17.5f, 12f, 19.5f)
                    // Open book right page
                    moveTo(12f, 6.5f)
                    quadTo(16f, 4.5f, 21f, 5.5f)
                    lineTo(21f, 18.5f)
                    quadTo(16f, 17.5f, 12f, 19.5f)
                    // Center spine
                    moveTo(12f, 6.5f)
                    lineTo(12f, 19.5f)
                }
            }.build()
    }

    val CaseBoard: ImageVector by lazy {
        ImageVector
            .Builder(
                name = "MixuCaseBoard",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    // Connecting edges between 3 graph nodes
                    moveTo(9.2f, 7.6f)
                    lineTo(15.2f, 10.4f)
                    moveTo(8.5f, 9.2f)
                    lineTo(10.5f, 15.2f)
                    moveTo(15.5f, 13.5f)
                    lineTo(12.8f, 16.2f)
                    // Node 1 (Top-Left Hub)
                    moveTo(9.5f, 6.5f)
                    arcTo(3f, 3f, 0f, true, true, 3.5f, 6.5f)
                    arcTo(3f, 3f, 0f, true, true, 9.5f, 6.5f)
                    // Node 2 (Right)
                    moveTo(20.5f, 11.5f)
                    arcTo(2.8f, 2.8f, 0f, true, true, 14.9f, 11.5f)
                    arcTo(2.8f, 2.8f, 0f, true, true, 20.5f, 11.5f)
                    // Node 3 (Bottom)
                    moveTo(14f, 18f)
                    arcTo(2.6f, 2.6f, 0f, true, true, 8.8f, 18f)
                    arcTo(2.6f, 2.6f, 0f, true, true, 14f, 18f)
                }
            }.build()
    }

    val Settings: ImageVector by lazy {
        ImageVector
            .Builder(
                name = "MixuSettings",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    // Top slider track + knob
                    moveTo(4f, 7f)
                    lineTo(12f, 7f)
                    moveTo(16f, 7f)
                    lineTo(20f, 7f)
                    moveTo(16f, 7f)
                    arcTo(2f, 2f, 0f, true, true, 12f, 7f)
                    arcTo(2f, 2f, 0f, true, true, 16f, 7f)
                    // Middle slider track + knob
                    moveTo(4f, 12f)
                    lineTo(7f, 12f)
                    moveTo(11f, 12f)
                    lineTo(20f, 12f)
                    moveTo(11f, 12f)
                    arcTo(2f, 2f, 0f, true, true, 7f, 12f)
                    arcTo(2f, 2f, 0f, true, true, 11f, 12f)
                    // Bottom slider track + knob
                    moveTo(4f, 17f)
                    lineTo(14f, 17f)
                    moveTo(18f, 17f)
                    lineTo(20f, 17f)
                    moveTo(18f, 17f)
                    arcTo(2f, 2f, 0f, true, true, 14f, 17f)
                    arcTo(2f, 2f, 0f, true, true, 18f, 17f)
                }
            }.build()
    }

    val ForceGraph: ImageVector by lazy {
        ImageVector
            .Builder(
                name = "MixuForceGraph",
                defaultWidth = 18.dp,
                defaultHeight = 18.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(12f, 12f)
                    lineTo(6f, 6f)
                    moveTo(12f, 12f)
                    lineTo(19f, 7f)
                    moveTo(12f, 12f)
                    lineTo(8f, 19f)
                    moveTo(12f, 12f)
                    lineTo(18f, 18f)
                    // Center hub
                    moveTo(15f, 12f)
                    arcTo(3f, 3f, 0f, true, true, 9f, 12f)
                    arcTo(3f, 3f, 0f, true, true, 15f, 12f)
                }
            }.build()
    }

    val HierarchicalGraph: ImageVector by lazy {
        ImageVector
            .Builder(
                name = "MixuHierarchicalGraph",
                defaultWidth = 18.dp,
                defaultHeight = 18.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    // Top root box
                    moveTo(9f, 3f)
                    lineTo(15f, 3f)
                    lineTo(15f, 7f)
                    lineTo(9f, 7f)
                    close()
                    // Connector tree lines
                    moveTo(12f, 7f)
                    lineTo(12f, 12f)
                    moveTo(6f, 12f)
                    lineTo(18f, 12f)
                    moveTo(6f, 12f)
                    lineTo(6f, 16f)
                    moveTo(18f, 12f)
                    lineTo(18f, 16f)
                    // Bottom-left leaf box
                    moveTo(3f, 16f)
                    lineTo(9f, 16f)
                    lineTo(9f, 20f)
                    lineTo(3f, 20f)
                    close()
                    // Bottom-right leaf box
                    moveTo(15f, 16f)
                    lineTo(21f, 16f)
                    lineTo(21f, 20f)
                    lineTo(15f, 20f)
                    close()
                }
            }.build()
    }

    val StarOutline: ImageVector by lazy {
        ImageVector
            .Builder(
                name = "MixuStarOutline",
                defaultWidth = 18.dp,
                defaultHeight = 18.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(12f, 2.5f)
                    lineTo(14.9f, 8.4f)
                    lineTo(21.4f, 9.3f)
                    lineTo(16.7f, 13.9f)
                    lineTo(17.8f, 20.4f)
                    lineTo(12f, 17.3f)
                    lineTo(6.2f, 20.4f)
                    lineTo(7.3f, 13.9f)
                    lineTo(2.6f, 9.3f)
                    lineTo(9.1f, 8.4f)
                    close()
                }
            }.build()
    }

    val StarFilled: ImageVector by lazy {
        ImageVector
            .Builder(
                name = "MixuStarFilled",
                defaultWidth = 18.dp,
                defaultHeight = 18.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Black),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(12f, 2.5f)
                    lineTo(14.9f, 8.4f)
                    lineTo(21.4f, 9.3f)
                    lineTo(16.7f, 13.9f)
                    lineTo(17.8f, 20.4f)
                    lineTo(12f, 17.3f)
                    lineTo(6.2f, 20.4f)
                    lineTo(7.3f, 13.9f)
                    lineTo(2.6f, 9.3f)
                    lineTo(9.1f, 8.4f)
                    close()
                }
            }.build()
    }

    val FolderCollection: ImageVector by lazy {
        ImageVector
            .Builder(
                name = "MixuFolderCollection",
                defaultWidth = 18.dp,
                defaultHeight = 18.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    moveTo(3f, 6f)
                    lineTo(9.5f, 6f)
                    lineTo(11.5f, 8.5f)
                    lineTo(21f, 8.5f)
                    lineTo(21f, 19f)
                    lineTo(3f, 19f)
                    close()
                }
            }.build()
    }

    val TranslateLookup: ImageVector by lazy {
        ImageVector
            .Builder(
                name = "MixuTranslateLookup",
                defaultWidth = 18.dp,
                defaultHeight = 18.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    // Magnifying glass / dictionary lens
                    moveTo(15f, 10.5f)
                    arcTo(5.5f, 5.5f, 0f, true, true, 4f, 10.5f)
                    arcTo(5.5f, 5.5f, 0f, true, true, 15f, 10.5f)
                    moveTo(13.8f, 14.5f)
                    lineTo(20f, 20.5f)
                    // "A" glyph inside lens
                    moveTo(7.5f, 13f)
                    lineTo(9.5f, 7.5f)
                    lineTo(11.5f, 13f)
                    moveTo(8.1f, 11.5f)
                    lineTo(10.9f, 11.5f)
                }
            }.build()
    }

    /**
     * Draws a crisp SVG-style vector glyph for [entityType] centered at [center] with half-size [iconRadiusPx].
     */
    fun DrawScope.drawEntityVectorIcon(
        entityType: EntityType,
        center: Offset,
        iconRadiusPx: Float,
        color: Color,
        strokeWidthPx: Float,
    ) {
        val r = iconRadiusPx
        val stroke =
            Stroke(
                width = strokeWidthPx,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )

        when (entityType) {
            EntityType.PERSON, EntityType.PERSONA -> {
                // Head circle + shoulders arc
                val headRadius = r * 0.36f
                drawCircle(
                    color = color,
                    radius = headRadius,
                    center = Offset(center.x, center.y - r * 0.26f),
                    style = stroke,
                )
                val shouldersPath =
                    Path().apply {
                        moveTo(center.x - r * 0.68f, center.y + r * 0.65f)
                        quadraticTo(
                            center.x - r * 0.62f,
                            center.y + r * 0.12f,
                            center.x,
                            center.y + r * 0.12f,
                        )
                        quadraticTo(
                            center.x + r * 0.62f,
                            center.y + r * 0.12f,
                            center.x + r * 0.68f,
                            center.y + r * 0.65f,
                        )
                    }
                drawPath(path = shouldersPath, color = color, style = stroke)
            }

            EntityType.LOCATION -> {
                // Map pin / classical roof landmark
                val pinPath =
                    Path().apply {
                        moveTo(center.x, center.y + r * 0.78f)
                        quadraticTo(
                            center.x - r * 0.65f,
                            center.y + r * 0.10f,
                            center.x - r * 0.65f,
                            center.y - r * 0.22f,
                        )
                        arcTo(
                            rect =
                                Rect(
                                    left = center.x - r * 0.65f,
                                    top = center.y - r * 0.82f,
                                    right = center.x + r * 0.65f,
                                    bottom = center.y + r * 0.38f,
                                ),
                            startAngleDegrees = 180f,
                            sweepAngleDegrees = 180f,
                            forceMoveTo = false,
                        )
                        quadraticTo(
                            center.x + r * 0.65f,
                            center.y + r * 0.10f,
                            center.x,
                            center.y + r * 0.78f,
                        )
                        close()
                    }
                drawPath(path = pinPath, color = color, style = stroke)
                drawCircle(
                    color = color,
                    radius = r * 0.22f,
                    center = Offset(center.x, center.y - r * 0.22f),
                    style = stroke,
                )
            }

            EntityType.OBJECT -> {
                // Evidence key / magnifying glass icon
                val ringCenter = Offset(center.x - r * 0.24f, center.y - r * 0.24f)
                drawCircle(
                    color = color,
                    radius = r * 0.38f,
                    center = ringCenter,
                    style = stroke,
                )
                val shaftPath =
                    Path().apply {
                        moveTo(center.x + r * 0.04f, center.y + r * 0.04f)
                        lineTo(center.x + r * 0.68f, center.y + r * 0.68f)
                        moveTo(center.x + r * 0.42f, center.y + r * 0.42f)
                        lineTo(center.x + r * 0.22f, center.y + r * 0.62f)
                        moveTo(center.x + r * 0.62f, center.y + r * 0.62f)
                        lineTo(center.x + r * 0.42f, center.y + r * 0.82f)
                    }
                drawPath(path = shaftPath, color = color, style = stroke)
            }

            EntityType.ORGANIZATION -> {
                // Classical pediment + pillars icon
                val orgPath =
                    Path().apply {
                        // Pediment triangle
                        moveTo(center.x - r * 0.75f, center.y - r * 0.22f)
                        lineTo(center.x, center.y - r * 0.72f)
                        lineTo(center.x + r * 0.75f, center.y - r * 0.22f)
                        close()
                        // Three vertical columns
                        moveTo(center.x - r * 0.48f, center.y - r * 0.20f)
                        lineTo(center.x - r * 0.48f, center.y + r * 0.55f)
                        moveTo(center.x, center.y - r * 0.20f)
                        lineTo(center.x, center.y + r * 0.55f)
                        moveTo(center.x + r * 0.48f, center.y - r * 0.20f)
                        lineTo(center.x + r * 0.48f, center.y + r * 0.55f)
                        // Base step
                        moveTo(center.x - r * 0.75f, center.y + r * 0.58f)
                        lineTo(center.x + r * 0.75f, center.y + r * 0.58f)
                    }
                drawPath(path = orgPath, color = color, style = stroke)
            }
        }
    }

    /**
     * Returns a clean Unicode geometric/semantic glyph for [EntityType] in text chips and cards.
     */
    fun entityUnicodeGlyph(type: EntityType): String =
        when (type) {
            EntityType.PERSON -> "♟"
            EntityType.PERSONA -> "◈"
            EntityType.LOCATION -> "⌖"
            EntityType.OBJECT -> "❖"
            EntityType.ORGANIZATION -> "🏛"
        }
}
