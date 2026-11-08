// SPDX-License-Identifier: MPL-2.0
@file:Suppress("ktlint:standard:function-naming")

package app.mixu.client.caseboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mixu.client.R
import app.mixu.client.eink.StrokePattern
import app.mixu.client.theme.MixuTheme
import app.mixu.graph.EntityType
import app.mixu.graph.ObjectRef
import app.mixu.graph.RevealedAssertion
import app.mixu.graph.RevealedEntity
import kotlin.math.roundToInt

/**
 * Geometric Diamond Shape for OBJECT entity rendering.
 */
val DiamondShape: Shape =
    GenericShape { size, _ ->
        moveTo(size.width / 2f, 0f)
        lineTo(size.width, size.height / 2f)
        lineTo(size.width / 2f, size.height)
        lineTo(0f, size.height / 2f)
        close()
    }

/**
 * Geometric Hexagon Shape for EVENT entity rendering.
 */
val HexagonShape: Shape =
    GenericShape { size, _ ->
        val w = size.width
        val h = size.height
        val inset = w * 0.2f
        moveTo(inset, 0f)
        lineTo(w - inset, 0f)
        lineTo(w, h / 2f)
        lineTo(w - inset, h)
        lineTo(inset, h)
        lineTo(0f, h / 2f)
        close()
    }

/**
 * Stateful entry point for the Case Board screen.
 */
@Composable
fun CaseBoardScreen(
    controller: CaseBoardController = remember { CaseBoardController() },
    onNavigateBack: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var state by remember { mutableStateOf(controller.state) }

    CaseBoardScreen(
        uiState = state,
        onScrub = { targetOrdinal ->
            controller.scrubGraphView(targetOrdinal)
            state = controller.state
        },
        onSelectEntity = { entity ->
            controller.selectEntity(entity)
            state = controller.state
        },
        onTabChange = { tab ->
            controller.setTab(tab)
            state = controller.state
        },
        onAddHypothesis = { title, desc, entityId ->
            controller.addHypothesis(title, desc, entityId)
            state = controller.state
        },
        onDeleteHypothesis = { id ->
            controller.deleteHypothesis(id)
            state = controller.state
        },
        onStepZoom = { zoomIn ->
            controller.stepZoom(zoomIn)
            state = controller.state
        },
        onStepPan = { dx, dy ->
            controller.stepPan(dx, dy)
            state = controller.state
        },
        onResetViewport = {
            controller.resetViewport()
            state = controller.state
        },
        onOpenAddHypothesisDialog = { isOpen ->
            controller.setAddHypothesisDialogOpen(isOpen)
            state = controller.state
        },
        onNavigateBack = onNavigateBack,
        onNavigateToSettings = onNavigateToSettings,
        modifier = modifier,
    )
}

/**
 * Stateless Case Board screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaseBoardScreen(
    uiState: CaseBoardUiState,
    onScrub: (Int) -> Unit,
    onSelectEntity: (RevealedEntity?) -> Unit,
    onTabChange: (CaseBoardTab) -> Unit,
    onAddHypothesis: (String, String, String?) -> Unit,
    onDeleteHypothesis: (String) -> Unit,
    onStepZoom: (Boolean) -> Unit,
    onStepPan: (Float, Float) -> Unit,
    onResetViewport: () -> Unit,
    onOpenAddHypothesisDialog: (Boolean) -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val eInkPolicy = MixuTheme.einkPolicy
    val colors = MixuTheme.colors

    Scaffold(
        topBar = {
            Column(modifier = Modifier.fillMaxWidth().background(colors.surface)) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.caseboard_title),
                                style =
                                    MixuTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                    ),
                                color = colors.onSurface,
                            )
                            Text(
                                text = uiState.bookTitle,
                                style = MixuTheme.typography.caption,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        TextButton(onClick = onNavigateBack) {
                            Text(
                                text = "← " + stringResource(R.string.nav_history_back),
                                color = colors.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    },
                    actions = {
                        if (eInkPolicy.isActive) {
                            Box(
                                modifier =
                                    Modifier
                                        .padding(end = 8.dp)
                                        .border(
                                            BorderStroke(1.5.dp, colors.onSurface),
                                            RoundedCornerShape(2.dp),
                                        ).padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.eink_active_badge),
                                    style =
                                        MixuTheme.typography.caption.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                    color = colors.onSurface,
                                )
                            }
                        }
                        TextButton(onClick = onNavigateToSettings) {
                            Text(
                                text = stringResource(R.string.nav_settings),
                                color = colors.onSurface,
                            )
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = colors.surface,
                            titleContentColor = colors.onSurface,
                        ),
                )

                // Navigation surface tabs
                PrimaryTabRow(
                    selectedTabIndex = uiState.activeTab.ordinal,
                    containerColor = colors.surface,
                    contentColor = colors.primary,
                ) {
                    Tab(
                        selected = uiState.activeTab == CaseBoardTab.GRAPH,
                        onClick = { onTabChange(CaseBoardTab.GRAPH) },
                        text = { Text(stringResource(R.string.caseboard_tab_graph)) },
                    )
                    Tab(
                        selected = uiState.activeTab == CaseBoardTab.CLAIMS,
                        onClick = { onTabChange(CaseBoardTab.CLAIMS) },
                        text = {
                            val count = uiState.revealedGraphView?.assertions?.size ?: 0
                            Text("${stringResource(R.string.caseboard_tab_claims)} ($count)")
                        },
                    )
                    Tab(
                        selected = uiState.activeTab == CaseBoardTab.HYPOTHESES,
                        onClick = { onTabChange(CaseBoardTab.HYPOTHESES) },
                        text = {
                            Text(
                                "${stringResource(
                                    R.string.caseboard_tab_hypotheses,
                                )} (${uiState.hypotheses.size})",
                            )
                        },
                    )
                }

                // Historical Reveal Slider (P-SPOILER-05)
                HistoricalRevealScrubber(
                    readingPosition = uiState.readingPosition,
                    knowledgeHorizon = uiState.knowledgeHorizon,
                    graphViewPosition = uiState.graphViewPosition,
                    isRewound = uiState.isRewound,
                    onScrub = onScrub,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        },
        containerColor = colors.background,
        modifier = modifier.fillMaxSize(),
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            when (uiState.activeTab) {
                CaseBoardTab.GRAPH -> {
                    GraphCanvasSurface(
                        uiState = uiState,
                        onSelectEntity = onSelectEntity,
                        onStepZoom = onStepZoom,
                        onStepPan = onStepPan,
                        onResetViewport = onResetViewport,
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Entity Dossier Inspection Drawer
                    if (uiState.selectedEntity != null) {
                        EntityDossierInspectionSheet(
                            entity = uiState.selectedEntity,
                            claims = uiState.selectedEntityAssertions,
                            onClose = { onSelectEntity(null) },
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(8.dp),
                        )
                    }
                }
                CaseBoardTab.CLAIMS -> {
                    ActiveClaimsList(
                        assertions = uiState.revealedGraphView?.assertions ?: emptyList(),
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                    )
                }
                CaseBoardTab.HYPOTHESES -> {
                    HypothesesSurface(
                        hypotheses = uiState.hypotheses,
                        onAddClick = { onOpenAddHypothesisDialog(true) },
                        onDeleteClick = onDeleteHypothesis,
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                    )
                }
            }
        }
    }

    if (uiState.isAddHypothesisDialogOpen) {
        AddHypothesisDialog(
            revealedEntities = uiState.revealedGraphView?.entities ?: emptyList(),
            onSave = onAddHypothesis,
            onDismiss = { onOpenAddHypothesisDialog(false) },
        )
    }
}

/**
 * Historical Reveal Scrubber allowing readers to explore past narrative graph states.
 * Enforces Invariant P-SPOILER-05: scrubbing rewinds view only without mutating knowledge horizon.
 */
@Composable
private fun HistoricalRevealScrubber(
    readingPosition: Int,
    knowledgeHorizon: Int,
    graphViewPosition: Int,
    isRewound: Boolean,
    onScrub: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val eInkPolicy = MixuTheme.einkPolicy
    val strokeWidth = if (eInkPolicy.isActive) 2.5.dp else 1.5.dp

    Card(
        modifier = modifier,
        border = BorderStroke(strokeWidth, if (isRewound) colors.claimed else colors.outline),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
        shape = RoundedCornerShape(4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.caseboard_scrubber_title),
                        style = MixuTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = colors.onSurface,
                    )
                    if (isRewound) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier =
                                Modifier
                                    .background(colors.claimed, RoundedCornerShape(2.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.caseboard_scrubber_rewound_badge),
                                style =
                                    MixuTheme.typography.caption.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                color = colors.onPrimary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Text(
                    text =
                        stringResource(
                            R.string.caseboard_scrubber_label,
                            graphViewPosition,
                            knowledgeHorizon,
                        ),
                    style =
                        MixuTheme.typography.caption.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    color = colors.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.caseboard_scrubber_desc),
                style = MixuTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { onScrub((graphViewPosition - 1).coerceAtLeast(0)) },
                    enabled = graphViewPosition > 0,
                    modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 48.dp),
                    shape = RoundedCornerShape(2.dp),
                    border = BorderStroke(strokeWidth, colors.outline),
                ) {
                    Text(
                        "◀ " + stringResource(R.string.caseboard_scrubber_prev),
                        style = MixuTheme.typography.caption,
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                if (knowledgeHorizon > 0) {
                    Slider(
                        value = graphViewPosition.toFloat(),
                        onValueChange = { onScrub(it.roundToInt()) },
                        valueRange = 0f..knowledgeHorizon.toFloat(),
                        steps = if (knowledgeHorizon > 1) knowledgeHorizon - 1 else 0,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        colors =
                            SliderDefaults.colors(
                                thumbColor = colors.primary,
                                activeTrackColor = colors.primary,
                                inactiveTrackColor = colors.outline,
                            ),
                    )
                } else {
                    Box(modifier = Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedButton(
                    onClick = { onScrub((graphViewPosition + 1).coerceAtMost(knowledgeHorizon)) },
                    enabled = graphViewPosition < knowledgeHorizon,
                    modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 48.dp),
                    shape = RoundedCornerShape(2.dp),
                    border = BorderStroke(strokeWidth, colors.outline),
                ) {
                    Text(
                        stringResource(R.string.caseboard_scrubber_next) + " ▶",
                        style = MixuTheme.typography.caption,
                    )
                }
            }
        }
    }
}

/**
 * Compose Graph Canvas displaying deterministic layout nodes with non-color geometric shapes,
 * stroke styles, and 48dp minimum touch targets complying with EInkPolicy.
 */
@Composable
private fun GraphCanvasSurface(
    uiState: CaseBoardUiState,
    onSelectEntity: (RevealedEntity?) -> Unit,
    onStepZoom: (Boolean) -> Unit,
    onStepPan: (Float, Float) -> Unit,
    onResetViewport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val eInkPolicy = MixuTheme.einkPolicy
    val graphView = uiState.revealedGraphView
    val entities = graphView?.entities ?: emptyList()
    val assertions = graphView?.assertions ?: emptyList()

    Box(modifier = modifier.background(colors.background)) {
        if (entities.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.caseboard_empty_graph),
                    style = MixuTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                )
            }
        } else {
            // Edge canvas rendering connections with epistemic status patterns
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .drawBehind {
                            val zoom = uiState.zoomLevel
                            val panX = uiState.panOffsetX
                            val panY = uiState.panOffsetY

                            for (assertion in assertions) {
                                val obj = assertion.assertion.objectRef
                                if (obj is ObjectRef.Entity) {
                                    val subId = assertion.assertion.subjectId.value
                                    val sourcePos = uiState.layoutPositions[subId]
                                    val targetPos = uiState.layoutPositions[obj.entityId.value]
                                    if (sourcePos != null && targetPos != null) {
                                        val startX =
                                            (
                                                sourcePos.bounds.center.x
                                                    .toFloat() + panX
                                            ) * zoom
                                        val startY =
                                            (
                                                sourcePos.bounds.center.y
                                                    .toFloat() + panY
                                            ) * zoom
                                        val endX =
                                            (
                                                targetPos.bounds.center.x
                                                    .toFloat() + panX
                                            ) * zoom
                                        val endY =
                                            (
                                                targetPos.bounds.center.y
                                                    .toFloat() + panY
                                            ) * zoom

                                        val epistemicStyle =
                                            eInkPolicy.getEpistemicStroke(
                                                assertion.currentStatus,
                                            )
                                        val pathEffect =
                                            when (epistemicStyle.pattern) {
                                                StrokePattern.DASHED ->
                                                    PathEffect.dashPathEffect(
                                                        floatArrayOf(12f, 8f),
                                                        0f,
                                                    )
                                                StrokePattern.DOTTED ->
                                                    PathEffect.dashPathEffect(
                                                        floatArrayOf(4f, 4f),
                                                        0f,
                                                    )
                                                else -> null
                                            }

                                        drawLine(
                                            color = colors.outline,
                                            start = Offset(startX, startY),
                                            end = Offset(endX, endY),
                                            strokeWidth = epistemicStyle.strokeWidthDp.toPx(),
                                            pathEffect = pathEffect,
                                        )
                                    }
                                }
                            }
                        },
            ) {
                // Nodes
                for (revealedEntity in entities) {
                    val pos = uiState.layoutPositions[revealedEntity.entity.entityId.value]
                    if (pos != null) {
                        val zoom = uiState.zoomLevel
                        val panX = uiState.panOffsetX
                        val panY = uiState.panOffsetY

                        val nodeX = ((pos.position.x.toFloat() + panX) * zoom).roundToInt()
                        val nodeY = ((pos.position.y.toFloat() + panY) * zoom).roundToInt()

                        EntityNodeCard(
                            entity = revealedEntity,
                            isSelected =
                                uiState.selectedEntity?.entity?.entityId ==
                                    revealedEntity.entity.entityId,
                            onClick = { onSelectEntity(revealedEntity) },
                            modifier =
                                Modifier
                                    .offset { IntOffset(nodeX, nodeY) }
                                    .sizeIn(minWidth = 56.dp, minHeight = 48.dp),
                        )
                    }
                }
            }
        }

        // Discrete stepped pan & zoom control overlay for E-Ink compliance
        DiscreteViewportControls(
            zoomLevel = uiState.zoomLevel,
            onStepZoom = onStepZoom,
            onStepPan = onStepPan,
            onReset = onResetViewport,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
        )
    }
}

/**
 * Geometric entity node with non-color shapes and distinct iconography.
 * Min touch target: 48dp.
 */
@Composable
private fun EntityNodeCard(
    entity: RevealedEntity,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val eInkPolicy = MixuTheme.einkPolicy
    val strokeWidth =
        if (isSelected) {
            3.5.dp
        } else if (eInkPolicy.isActive) {
            2.5.dp
        } else {
            1.5.dp
        }

    val (shape, iconSymbol, typeLabelRes) =
        when (entity.entity.entityType) {
            EntityType.PERSON, EntityType.PERSONA ->
                Triple(
                    CircleShape,
                    "[●]",
                    R.string.caseboard_type_person,
                )
            EntityType.LOCATION ->
                Triple(
                    RoundedCornerShape(2.dp),
                    "[■]",
                    R.string.caseboard_type_location,
                )
            EntityType.OBJECT -> Triple(DiamondShape, "[◆]", R.string.caseboard_type_object)
            EntityType.ORGANIZATION ->
                Triple(
                    RoundedCornerShape(8.dp),
                    "[⬭]",
                    R.string.caseboard_type_organization,
                )
        }

    val displayName = entity.canonicalName?.displayText ?: entity.entity.entityId.value

    Card(
        shape = shape,
        border = BorderStroke(strokeWidth, if (isSelected) colors.primary else colors.outline),
        colors =
            CardDefaults.cardColors(
                containerColor = if (isSelected) colors.primaryContainer else colors.surface,
            ),
        modifier =
            modifier
                .clickable(onClick = onClick)
                .sizeIn(minWidth = 56.dp, minHeight = 48.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = iconSymbol,
                    style = MixuTheme.typography.caption.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) colors.onPrimaryContainer else colors.primary,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(typeLabelRes),
                    style = MixuTheme.typography.caption,
                    color = colors.onSurfaceVariant,
                )
            }
            Text(
                text = displayName,
                style = MixuTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = if (isSelected) colors.onPrimaryContainer else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Viewport controls adhering strictly to E-Ink discrete navigation policy.
 */
@Composable
private fun DiscreteViewportControls(
    zoomLevel: Float,
    onStepZoom: (Boolean) -> Unit,
    onStepPan: (Float, Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val eInkPolicy = MixuTheme.einkPolicy
    val strokeWidth = if (eInkPolicy.isActive) 2.5.dp else 1.5.dp

    Card(
        border = BorderStroke(strokeWidth, colors.outline),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        shape = RoundedCornerShape(4.dp),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = { onStepZoom(false) },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    shape = RoundedCornerShape(2.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("－", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Box(
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${(zoomLevel * 100).roundToInt()}%",
                        style =
                            MixuTheme.typography.caption.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        fontWeight = FontWeight.Bold,
                    )
                }
                OutlinedButton(
                    onClick = { onStepZoom(true) },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    shape = RoundedCornerShape(2.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("＋", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = { onStepPan(-60f, 0f) },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    shape = RoundedCornerShape(2.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("◀", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    shape = RoundedCornerShape(2.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("●", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { onStepPan(60f, 0f) },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    shape = RoundedCornerShape(2.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("▶", fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * Inspection sheet displaying entity dossier, aliases at r, and active epistemic claims.
 */
@Composable
private fun EntityDossierInspectionSheet(
    entity: RevealedEntity,
    claims: List<RevealedAssertion>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val eInkPolicy = MixuTheme.einkPolicy
    val strokeWidth = if (eInkPolicy.isActive) 2.5.dp else 1.5.dp
    val displayName = entity.canonicalName?.displayText ?: entity.entity.entityId.value

    Card(
        border = BorderStroke(strokeWidth, colors.primary),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        shape = RoundedCornerShape(4.dp),
        modifier = modifier.heightIn(max = 280.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.caseboard_entity_inspect_title),
                        style = MixuTheme.typography.caption.copy(fontWeight = FontWeight.Bold),
                        color = colors.primary,
                    )
                    Text(
                        text = displayName,
                        style = MixuTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = colors.onSurface,
                    )
                }
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 48.dp),
                    shape = RoundedCornerShape(2.dp),
                ) {
                    Text(
                        stringResource(R.string.caseboard_entity_close),
                        style = MixuTheme.typography.caption,
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text =
                    stringResource(
                        R.string.caseboard_entity_first_reveal,
                        entity.entity.firstReveal,
                    ),
                style = MixuTheme.typography.caption,
                color = colors.onSurfaceVariant,
            )

            // Revealed aliases
            if (entity.allRevealedNames.size > 1) {
                Spacer(modifier = Modifier.height(4.dp))
                val aliases =
                    entity.allRevealedNames
                        .filter { it.nameId != entity.canonicalName?.nameId }
                        .joinToString(", ") { "${it.displayText} (${it.nameKind})" }
                Text(
                    text = "${stringResource(R.string.caseboard_entity_aliases)}: $aliases",
                    style = MixuTheme.typography.caption,
                    color = colors.onSurfaceVariant,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = colors.outline)

            Text(
                text = stringResource(R.string.caseboard_entity_claims, claims.size),
                style = MixuTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = colors.onSurface,
            )

            Spacer(modifier = Modifier.height(4.dp))

            if (claims.isEmpty()) {
                Text(
                    text = stringResource(R.string.caseboard_no_claims),
                    style = MixuTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(claims) { assertion ->
                        AssertionItemRow(assertion = assertion)
                    }
                }
            }
        }
    }
}

/**
 * Single claim row displaying subject, predicate, object, epistemic status badge, and evidence anchor.
 */
@Composable
private fun AssertionItemRow(assertion: RevealedAssertion) {
    val colors = MixuTheme.colors
    val eInkPolicy = MixuTheme.einkPolicy
    val epistemicStyle = eInkPolicy.getEpistemicStroke(assertion.currentStatus)

    val objectDesc =
        when (val obj = assertion.assertion.objectRef) {
            is ObjectRef.Entity -> obj.entityId.value
            is ObjectRef.Literal -> "${obj.value} ${obj.unit ?: ""}".trim()
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val sub = assertion.assertion.subjectId.value
            val pred = assertion.assertion.predicate
            Text(
                text = "$sub → $pred → $objectDesc",
                style = MixuTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = colors.onSurface,
            )
            Text(
                text =
                    stringResource(
                        R.string.caseboard_evidence_anchor,
                        assertion.assertion.evidenceAnchorId.value,
                    ),
                style = MixuTheme.typography.caption.copy(fontFamily = FontFamily.Monospace),
                color = colors.onSurfaceVariant,
            )
        }

        Box(
            modifier =
                Modifier
                    .border(BorderStroke(1.dp, colors.outline), RoundedCornerShape(2.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = epistemicStyle.symbolBadge,
                style = MixuTheme.typography.caption.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
        }
    }
}

/**
 * Tab displaying all active claims in the narrative graph at the scrubbed reveal ordinal.
 */
@Composable
private fun ActiveClaimsList(
    assertions: List<RevealedAssertion>,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors

    if (assertions.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.caseboard_no_claims),
                style = MixuTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(assertions) { assertion ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(2.dp),
                    border = BorderStroke(1.dp, colors.outline),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        AssertionItemRow(assertion = assertion)
                        val transitionReason = assertion.activeTransition?.reason
                        if (transitionReason != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Reason: $transitionReason",
                                style = MixuTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tab displaying user hypotheses, distinctly rendered with dashed borders and dedicated badges
 * to strictly segregate personal speculation from canonical narrative facts.
 */
@Composable
private fun HypothesesSurface(
    hypotheses: List<UserHypothesis>,
    onAddClick: () -> Unit,
    onDeleteClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val eInkPolicy = MixuTheme.einkPolicy
    val strokeWidth = if (eInkPolicy.isActive) 2.5.dp else 1.5.dp

    Column(modifier = modifier) {
        // Disclaimer Banner
        Card(
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(strokeWidth, colors.outline),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "[?] " + stringResource(R.string.caseboard_hypothesis_badge),
                        style =
                            MixuTheme.typography.caption.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.caseboard_hypothesis_disclaimer),
                    style = MixuTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${stringResource(R.string.caseboard_tab_hypotheses)} (${hypotheses.size})",
                style = MixuTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = colors.onSurface,
            )
            Button(
                onClick = onAddClick,
                modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 48.dp),
                shape = RoundedCornerShape(2.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
            ) {
                Text(
                    "+ " + stringResource(R.string.caseboard_add_hypothesis),
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (hypotheses.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.caseboard_empty_hypotheses),
                    style = MixuTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(hypotheses) { hypothesis ->
                    HypothesisItemCard(
                        hypothesis = hypothesis,
                        onDelete = { onDeleteClick(hypothesis.id) },
                    )
                }
            }
        }
    }
}

/**
 * Hypothesis Card with dashed border effect distinguishing speculation from fact.
 */
@Composable
private fun HypothesisItemCard(
    hypothesis: UserHypothesis,
    onDelete: () -> Unit,
) {
    val colors = MixuTheme.colors
    val eInkPolicy = MixuTheme.einkPolicy
    val strokeWidthPx = if (eInkPolicy.isActive) 2.5f * 2.5f else 1.5f * 2.5f

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRoundRect(
                        color = colors.outline,
                        size = size,
                        cornerRadius =
                            androidx.compose.ui.geometry
                                .CornerRadius(4.dp.toPx()),
                        style =
                            Stroke(
                                width = strokeWidthPx,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f),
                            ),
                    )
                }.background(colors.surface, RoundedCornerShape(4.dp))
                .padding(14.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier =
                            Modifier
                                .background(colors.surfaceVariant, RoundedCornerShape(2.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "[?] " + stringResource(R.string.caseboard_hypothesis_badge),
                            style =
                                MixuTheme.typography.caption.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface,
                        )
                    }
                    if (hypothesis.relatedEntityId != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Target: ${hypothesis.relatedEntityId}",
                            style = MixuTheme.typography.caption,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }

                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 48.dp),
                    shape = RoundedCornerShape(2.dp),
                ) {
                    Text(
                        stringResource(R.string.caseboard_hypothesis_delete),
                        color = colors.contradicted,
                        style = MixuTheme.typography.caption,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = hypothesis.title,
                style = MixuTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = colors.onSurface,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = hypothesis.description,
                style = MixuTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * Dialog for authoring user candidate hypotheses.
 */
@Composable
private fun AddHypothesisDialog(
    revealedEntities: List<RevealedEntity>,
    onSave: (title: String, description: String, relatedEntityId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selectedEntityId by remember { mutableStateOf<String?>(null) }
    val colors = MixuTheme.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.caseboard_add_hypothesis),
                style = MixuTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.caseboard_hypothesis_title_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.primary,
                            unfocusedBorderColor = colors.outline,
                        ),
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.caseboard_hypothesis_desc_hint)) },
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.primary,
                            unfocusedBorderColor = colors.outline,
                        ),
                )

                if (revealedEntities.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.caseboard_hypothesis_related_entity_label),
                        style = MixuTheme.typography.caption,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyColumn(modifier = Modifier.height(80.dp)) {
                        items(revealedEntities) { entity ->
                            val entityName =
                                entity.canonicalName?.displayText ?: entity.entity.entityId.value
                            val isSelected = selectedEntityId == entity.entity.entityId.value
                            val prefix = if (isSelected) "▶ " else "  "
                            val weight =
                                if (isSelected) FontWeight.Bold else FontWeight.Normal
                            Text(
                                text = "$prefix$entityName",
                                style = MixuTheme.typography.bodyMedium.copy(fontWeight = weight),
                                color = if (isSelected) colors.primary else colors.onSurface,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedEntityId =
                                                if (isSelected) {
                                                    null
                                                } else {
                                                    entity.entity.entityId.value
                                                }
                                        }.padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        onSave(title, description, selectedEntityId)
                    }
                },
                enabled = title.isNotBlank(),
                shape = RoundedCornerShape(2.dp),
                modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 48.dp),
            ) {
                Text(
                    stringResource(R.string.caseboard_hypothesis_save),
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(2.dp),
                modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 48.dp),
            ) {
                Text(stringResource(R.string.caseboard_hypothesis_cancel))
            }
        },
    )
}
