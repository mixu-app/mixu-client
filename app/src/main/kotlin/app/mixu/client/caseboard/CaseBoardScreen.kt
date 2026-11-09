// SPDX-License-Identifier: MPL-2.0
@file:Suppress("ktlint:standard:function-naming")

package app.mixu.client.caseboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mixu.client.R
import app.mixu.client.billing.ClientCreditWallet
import app.mixu.client.billing.EditionMatchTier
import app.mixu.client.billing.SharedPackMarketplaceOffer
import app.mixu.client.config.GraphDimensionMode
import app.mixu.client.config.GraphPhysicsMode
import app.mixu.client.eink.StrokePattern
import app.mixu.client.theme.MixuTheme
import app.mixu.client.ui.MixuIcons
import app.mixu.graph.EntityType
import app.mixu.graph.EpistemicStatus
import app.mixu.graph.ObjectRef
import app.mixu.graph.RevealedAssertion
import app.mixu.graph.RevealedEntity
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

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
    onJumpToOrdinal: ((Int) -> Unit)? = null,
    initialGraphMode: GraphDimensionMode = GraphDimensionMode.FORCE_2D,
    modifier: Modifier = Modifier,
) {
    var state by remember(controller) { mutableStateOf(controller.state) }

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
        onJumpToOrdinal = onJumpToOrdinal,
        initialGraphMode = initialGraphMode,
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
    onJumpToOrdinal: ((Int) -> Unit)? = null,
    initialGraphMode: GraphDimensionMode = GraphDimensionMode.FORCE_2D,
    modifier: Modifier = Modifier,
) {
    val eInkPolicy = MixuTheme.einkPolicy
    val colors = MixuTheme.colors
    var showMarketplaceDialog by remember { mutableStateOf(false) }
    var marketplaceWallet by remember { mutableStateOf(ClientCreditWallet()) }
    var marketplaceOffer by remember(uiState.editionId, uiState.revealedGraphView) {
        val entitiesCount =
            uiState.revealedGraphView
                ?.entities
                ?.size
                ?.coerceAtLeast(1)
                ?: SharedPackMarketplaceOffer.DEFAULT_ENTITY_COUNT
        val assertionsCount =
            uiState.revealedGraphView
                ?.assertions
                ?.size
                ?.coerceAtLeast(1)
                ?: SharedPackMarketplaceOffer.DEFAULT_ASSERTION_COUNT
        mutableStateOf(
            SharedPackMarketplaceOffer(
                packId = "gpack-${uiState.editionId}",
                matchTier = EditionMatchTier.L1_EXACT_SHA256,
                entityCount = entitiesCount,
                assertionCount = assertionsCount,
            ),
        )
    }
    val entityDisplayNames =
        remember(uiState.revealedGraphView) {
            uiState.revealedGraphView
                ?.entities
                ?.associate {
                    it.entity.entityId.value to
                        (it.canonicalName?.displayText ?: it.entity.entityId.value)
                }.orEmpty()
        }

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
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
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
                        OutlinedButton(
                            onClick = { showMarketplaceDialog = true },
                            modifier =
                                Modifier
                                    .padding(end = 6.dp)
                                    .sizeIn(minWidth = 44.dp, minHeight = 32.dp),
                            shape = RoundedCornerShape(if (eInkPolicy.isActive) 2.dp else 8.dp),
                            border =
                                BorderStroke(
                                    if (eInkPolicy.isActive) 1.5.dp else 1.dp,
                                    colors.primary,
                                ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.marketplace_pill_label),
                                style = MixuTheme.typography.caption,
                                fontWeight = FontWeight.Bold,
                                color = colors.primary,
                            )
                        }
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
                        text = {
                            val entityCount = uiState.revealedGraphView?.entities?.size ?: 0
                            Text("${stringResource(R.string.caseboard_tab_graph)} ($entityCount)")
                        },
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

                // Compact Historical Reveal Scrubber (P-SPOILER-05)
                HistoricalRevealScrubber(
                    readingPosition = uiState.readingPosition,
                    knowledgeHorizon = uiState.knowledgeHorizon,
                    graphViewPosition = uiState.graphViewPosition,
                    isRewound = uiState.isRewound,
                    onScrub = onScrub,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
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
                        initialGraphMode = initialGraphMode,
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Entity Dossier Inspection Drawer
                    if (uiState.selectedEntity != null) {
                        EntityDossierInspectionSheet(
                            entity = uiState.selectedEntity,
                            claims = uiState.selectedEntityAssertions,
                            entityDisplayNames = entityDisplayNames,
                            onClose = { onSelectEntity(null) },
                            onJumpToOrdinal = onJumpToOrdinal,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(10.dp),
                        )
                    }
                }
                CaseBoardTab.CLAIMS -> {
                    ActiveClaimsList(
                        assertions = uiState.revealedGraphView?.assertions ?: emptyList(),
                        entityDisplayNames = entityDisplayNames,
                        onJumpToOrdinal = onJumpToOrdinal,
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

    if (showMarketplaceDialog) {
        SharedGraphPackMarketplaceDialog(
            bookTitle = uiState.bookTitle,
            offer = marketplaceOffer,
            wallet = marketplaceWallet,
            onCycleMatchTier = {
                val nextTier =
                    when (marketplaceOffer.matchTier) {
                        EditionMatchTier.L1_EXACT_SHA256 ->
                            EditionMatchTier.L2_STRUCTURE_FINGERPRINT
                        EditionMatchTier.L2_STRUCTURE_FINGERPRINT ->
                            EditionMatchTier.L3_ISBN_CHAPTER_ANCHOR
                        EditionMatchTier.L3_ISBN_CHAPTER_ANCHOR ->
                            EditionMatchTier.L1_EXACT_SHA256
                    }
                marketplaceOffer = marketplaceOffer.copy(matchTier = nextTier)
            },
            onUnlockPack = {
                if (!marketplaceOffer.unlocked &&
                    marketplaceWallet.canAfford(marketplaceOffer.downloadCostCredits)
                ) {
                    marketplaceWallet =
                        marketplaceWallet.deductCredits(marketplaceOffer.downloadCostCredits)
                    marketplaceOffer = marketplaceOffer.copy(unlocked = true)
                }
            },
            onPublishPack = {
                marketplaceOffer = marketplaceOffer.copy(publishedByCurrentUser = true)
            },
            onSimulateRoyalty = {
                marketplaceWallet =
                    marketplaceWallet.recordCreatorRoyalty(
                        royaltyCredits = marketplaceOffer.creatorRoyaltyCredits,
                        buildCostCredits = marketplaceOffer.buildCostCredits,
                    )
            },
            onDismiss = { showMarketplaceDialog = false },
        )
    }
}

@Composable
private fun SharedGraphPackMarketplaceDialog(
    bookTitle: String,
    offer: SharedPackMarketplaceOffer,
    wallet: ClientCreditWallet,
    onCycleMatchTier: () -> Unit,
    onUnlockPack: () -> Unit,
    onPublishPack: () -> Unit,
    onSimulateRoyalty: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography
    val eInkPolicy = MixuTheme.einkPolicy
    val tierLabel =
        when (offer.matchTier) {
            EditionMatchTier.L1_EXACT_SHA256 ->
                stringResource(R.string.marketplace_tier_l1_badge)
            EditionMatchTier.L2_STRUCTURE_FINGERPRINT ->
                stringResource(R.string.marketplace_tier_l2_badge)
            EditionMatchTier.L3_ISBN_CHAPTER_ANCHOR ->
                stringResource(R.string.marketplace_tier_l3_badge)
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.marketplace_dialog_title),
                    style = typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                )
                Text(
                    text = bookTitle,
                    style = typography.caption,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .border(
                                    BorderStroke(
                                        if (eInkPolicy.isActive) 1.5.dp else 1.dp,
                                        colors.primary,
                                    ),
                                    RoundedCornerShape(if (eInkPolicy.isActive) 2.dp else 6.dp),
                                ).clickable { onCycleMatchTier() }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = tierLabel,
                            style = typography.caption.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.Bold,
                            color = colors.primary,
                        )
                    }
                    Text(
                        text = stringResource(R.string.wallet_title, wallet.totalCredits),
                        style = typography.caption,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                }

                Text(
                    text =
                        stringResource(
                            R.string.marketplace_offer_summary,
                            offer.entityCount,
                            offer.assertionCount,
                            offer.downloadCostCredits,
                            offer.buildCostCredits,
                        ),
                    style = typography.caption,
                    color = colors.onSurfaceVariant,
                )

                if (offer.unlocked) {
                    Text(
                        text = stringResource(R.string.marketplace_unlocked_badge),
                        style = typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.corroborated,
                    )
                } else {
                    Button(
                        onClick = onUnlockPack,
                        enabled = wallet.canAfford(offer.downloadCostCredits),
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = colors.primary,
                                contentColor = colors.onPrimary,
                            ),
                    ) {
                        Text(
                            text =
                                stringResource(
                                    R.string.marketplace_unlock_btn,
                                    offer.downloadCostCredits,
                                ),
                            style = typography.caption,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = colors.outlineVariant.copy(alpha = 0.6f),
                )

                if (offer.publishedByCurrentUser) {
                    Text(
                        text =
                            stringResource(
                                R.string.marketplace_published_badge,
                                wallet.creatorRoyaltyEarned,
                            ),
                        style = typography.caption,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primary,
                    )
                    OutlinedButton(
                        onClick = onSimulateRoyalty,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.marketplace_simulate_royalty_btn),
                            style = typography.caption,
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onPublishPack,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.marketplace_publish_btn),
                            style = typography.caption,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_reset))
            }
        },
    )
}

/**
 * Compact Historical Reveal Scrubber allowing readers to explore past narrative graph states
 * while preserving maximum vertical canvas height.
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
    val strokeWidth = if (eInkPolicy.isActive) 2.dp else 1.dp
    val cornerRadius = if (eInkPolicy.isActive) 2.dp else 8.dp
    var showDescription by remember { mutableStateOf(false) }

    Card(
        modifier = modifier,
        border =
            BorderStroke(
                strokeWidth,
                if (isRewound) colors.claimed else colors.outlineVariant,
            ),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
        shape = RoundedCornerShape(cornerRadius),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onScrub((graphViewPosition - 1).coerceAtLeast(0)) },
                    enabled = graphViewPosition > 0,
                    modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 36.dp),
                    shape = RoundedCornerShape(cornerRadius),
                    border = BorderStroke(strokeWidth, colors.outline),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        "◀",
                        style = MixuTheme.typography.caption,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Column(
                    modifier =
                        Modifier
                            .clickable { showDescription = !showDescription }
                            .padding(horizontal = 2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.caseboard_scrubber_title),
                            style =
                                MixuTheme.typography.caption.copy(
                                    fontWeight = FontWeight.Bold,
                                ),
                            color = colors.onSurface,
                        )
                        if (isRewound) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier =
                                    Modifier
                                        .background(colors.claimed, RoundedCornerShape(3.dp))
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                            ) {
                                Text(
                                    text =
                                        stringResource(
                                            R.string.caseboard_scrubber_rewound_badge,
                                        ),
                                    style =
                                        MixuTheme.typography.caption.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
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
                            ) + " · 当前第 ${readingPosition + 1} 节",
                        style =
                            MixuTheme.typography.caption.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        color = colors.onSurfaceVariant,
                    )
                }

                if (knowledgeHorizon > 0) {
                    Slider(
                        value = graphViewPosition.toFloat(),
                        onValueChange = { onScrub(it.roundToInt()) },
                        valueRange = 0f..knowledgeHorizon.toFloat(),
                        steps = if (knowledgeHorizon > 1) knowledgeHorizon - 1 else 0,
                        modifier = Modifier.weight(1f).height(32.dp),
                        colors =
                            SliderDefaults.colors(
                                thumbColor = colors.primary,
                                activeTrackColor = colors.primary,
                                inactiveTrackColor = colors.outline,
                            ),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                OutlinedButton(
                    onClick = { onScrub((graphViewPosition + 1).coerceAtMost(knowledgeHorizon)) },
                    enabled = graphViewPosition < knowledgeHorizon,
                    modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 36.dp),
                    shape = RoundedCornerShape(cornerRadius),
                    border = BorderStroke(strokeWidth, colors.outline),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        "▶",
                        style = MixuTheme.typography.caption,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            if (showDescription) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.caseboard_scrubber_desc),
                    style = MixuTheme.typography.caption,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Interactive 2D Compose Knowledge Graph Canvas supporting:
 * - **2D Planar Force Graph** ([GraphDimensionMode.FORCE_2D]): spring-embedder planar layout with
 *   curved multi-edges, directional arrowheads, predicate pills, and SVG vector entity icons.
 * - **Hierarchical Topological Graph** ([GraphDimensionMode.HIERARCHICAL]): layered hub-to-periphery
 *   Sugiyama layout with barycenter crossing minimization.
 * - **E-Ink Physics & Visual Adaptation**: pre-settled static equilibrium, expanded node repulsion
 *   and link rest length, 8px grid snapping, monochrome geometric node shapes, and discrete
 *   stepped controls.
 */
@Composable
private fun GraphCanvasSurface(
    uiState: CaseBoardUiState,
    onSelectEntity: (RevealedEntity?) -> Unit,
    onStepZoom: (Boolean) -> Unit,
    onStepPan: (Float, Float) -> Unit,
    onResetViewport: () -> Unit,
    initialGraphMode: GraphDimensionMode = GraphDimensionMode.FORCE_2D,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val eInkPolicy = MixuTheme.einkPolicy
    val graphView = uiState.revealedGraphView
    val entities = graphView?.entities ?: emptyList()
    val assertions = graphView?.assertions ?: emptyList()

    var graphMode by remember(initialGraphMode) {
        mutableStateOf(initialGraphMode)
    }
    var physicsMode by remember {
        mutableStateOf(GraphPhysicsMode.LIVE_SPRING)
    }
    var entityTypeFilter by remember {
        mutableStateOf<EntityType?>(null)
    }
    var showEdgeLabels by remember { mutableStateOf(true) }
    var interactivePanX by remember { mutableFloatStateOf(0f) }
    var interactivePanY by remember { mutableFloatStateOf(0f) }
    var interactiveZoom by remember { mutableFloatStateOf(1.0f) }
    var nodeOffsets by remember(
        uiState.editionId,
        graphMode,
        uiState.graphViewPosition,
        entityTypeFilter,
    ) {
        mutableStateOf<Map<String, Pair<Float, Float>>>(emptyMap())
    }
    var pinnedNodeIds by remember(
        uiState.editionId,
        graphMode,
        uiState.graphViewPosition,
        entityTypeFilter,
    ) {
        mutableStateOf<Set<String>>(emptySet())
    }
    var activeDraggedEntityId by remember { mutableStateOf<String?>(null) }

    val filteredEntities =
        remember(entities, entityTypeFilter) {
            when (entityTypeFilter) {
                null -> entities
                EntityType.PERSON ->
                    entities.filter {
                        it.entity.entityType == EntityType.PERSON ||
                            it.entity.entityType == EntityType.PERSONA ||
                            it.entity.entityType == EntityType.ORGANIZATION
                    }
                else -> entities.filter { it.entity.entityType == entityTypeFilter }
            }.ifEmpty { entities }
        }

    val effectiveZoom = (uiState.zoomLevel * interactiveZoom).coerceIn(0.5f, 2.6f)
    val effectivePanX = uiState.panOffsetX + interactivePanX
    val effectivePanY = uiState.panOffsetY + interactivePanY

    val layoutResult =
        remember(
            uiState.editionId,
            filteredEntities,
            assertions,
            graphMode,
            eInkPolicy.isActive,
        ) {
            ForceDirectedGraphEngine.computeLayout(
                editionId = uiState.editionId,
                entities = filteredEntities,
                assertions = assertions,
                isEInk = eInkPolicy.isActive,
                mode = graphMode,
            )
        }

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier =
            modifier
                .background(colors.background)
                .clipToBounds(),
    ) {
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
            val viewportWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(320f)
            val viewportHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(320f)

            val projectedNodes =
                remember(
                    filteredEntities,
                    layoutResult,
                    viewportWidthPx,
                    viewportHeightPx,
                    effectiveZoom,
                    effectivePanX,
                    effectivePanY,
                    nodeOffsets,
                ) {
                    ForceDirectedGraphEngine.projectNodes(
                        entities = filteredEntities,
                        layoutResult = layoutResult,
                        viewportWidthPx = viewportWidthPx,
                        viewportHeightPx = viewportHeightPx,
                        zoom = effectiveZoom,
                        panX = effectivePanX,
                        panY = effectivePanY,
                        nodeOffsets = nodeOffsets,
                    )
                }

            val projectedById =
                remember(projectedNodes) {
                    projectedNodes.associateBy { it.entity.entity.entityId.value }
                }

            val selectedEntityId =
                uiState.selectedEntity
                    ?.entity
                    ?.entityId
                    ?.value
            val selectedNeighbors =
                remember(selectedEntityId, layoutResult) {
                    if (selectedEntityId != null) {
                        layoutResult.neighborMap[selectedEntityId].orEmpty() + selectedEntityId
                    } else {
                        emptySet()
                    }
                }

            val latestProjectedNodes by rememberUpdatedState(projectedNodes)
            val latestSelectedEntityId by rememberUpdatedState(selectedEntityId)
            val latestNodeOffsets by rememberUpdatedState(nodeOffsets)
            val latestPinnedNodeIds by rememberUpdatedState(pinnedNodeIds)
            val latestPhysicsMode by rememberUpdatedState(physicsMode)
            val latestEffectiveZoom by rememberUpdatedState(effectiveZoom)

            val nodeLabelStyle =
                remember(colors.onSurface) {
                    TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                    )
                }
            val edgeLabelStyle =
                remember(colors.onSurfaceVariant) {
                    TextStyle(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.onSurfaceVariant,
                    )
                }

            Canvas(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .pointerInput(layoutResult) {
                            detectTapGestures { tapOffset ->
                                val hitRadiusPx = 46.dp.toPx()
                                val hitNode =
                                    latestProjectedNodes.asReversed().firstOrNull { node ->
                                        hypot(
                                            tapOffset.x - node.screenX,
                                            tapOffset.y - node.screenY,
                                        ) <= hitRadiusPx
                                    }
                                if (hitNode != null) {
                                    val clickedId = hitNode.entity.entity.entityId.value
                                    if (clickedId == latestSelectedEntityId) {
                                        onSelectEntity(null)
                                    } else {
                                        onSelectEntity(hitNode.entity)
                                    }
                                } else {
                                    onSelectEntity(null)
                                }
                            }
                        }.pointerInput(
                            layoutResult,
                            eInkPolicy.isActive,
                            viewportWidthPx,
                            viewportHeightPx,
                        ) {
                            detectDragGestures(
                                onDragStart = { startOffset ->
                                    val hitRadiusPx = 48.dp.toPx()
                                    val hitNode =
                                        latestProjectedNodes.asReversed().firstOrNull { node ->
                                            hypot(
                                                startOffset.x - node.screenX,
                                                startOffset.y - node.screenY,
                                            ) <= hitRadiusPx
                                        }
                                    if (hitNode != null) {
                                        val draggedId = hitNode.entity.entity.entityId.value
                                        activeDraggedEntityId = draggedId
                                        onSelectEntity(hitNode.entity)
                                    } else {
                                        activeDraggedEntityId = null
                                    }
                                },
                                onDragEnd = {
                                    activeDraggedEntityId = null
                                },
                                onDragCancel = {
                                    activeDraggedEntityId = null
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val draggedId = activeDraggedEntityId
                                    val curZoom = latestEffectiveZoom
                                    if (draggedId != null) {
                                        val nextPinned = latestPinnedNodeIds + draggedId
                                        pinnedNodeIds = nextPinned
                                        nodeOffsets =
                                            ForceDirectedGraphEngine.applyNodeDragStep(
                                                layoutResult = layoutResult,
                                                currentOffsets = latestNodeOffsets,
                                                draggedEntityId = draggedId,
                                                screenDx = dragAmount.x,
                                                screenDy = dragAmount.y,
                                                viewportWidthPx = viewportWidthPx,
                                                viewportHeightPx = viewportHeightPx,
                                                zoom = curZoom,
                                                physicsMode = latestPhysicsMode,
                                                pinnedEntityIds = nextPinned,
                                                isEInk = eInkPolicy.isActive,
                                            )
                                    } else {
                                        interactivePanX =
                                            (interactivePanX + dragAmount.x / curZoom)
                                                .coerceIn(-650f, 650f)
                                        interactivePanY =
                                            (interactivePanY + dragAmount.y / curZoom)
                                                .coerceIn(-650f, 650f)
                                    }
                                },
                            )
                        },
            ) {
                // 1. Subtle 2D Coordinate Grid Backdrop (non-E-Ink)
                if (!eInkPolicy.isActive) {
                    drawGraphBackdrop(
                        centerX = size.width / 2f + effectivePanX * effectiveZoom * 0.25f,
                        centerY = size.height / 2f + effectivePanY * effectiveZoom * 0.25f,
                        minDim = minOf(size.width, size.height),
                        zoom = effectiveZoom,
                        outlineColor = colors.outlineVariant,
                        primaryColor = colors.primary,
                    )
                }

                // 2. Draw Directed Curved Edges
                for (edge in layoutResult.edges) {
                    val src = projectedById[edge.sourceId] ?: continue
                    val dst = projectedById[edge.targetId] ?: continue

                    val isIncidentToSelection =
                        selectedEntityId != null &&
                            (
                                edge.sourceId == selectedEntityId ||
                                    edge.targetId == selectedEntityId
                            )
                    val isDimmed = selectedEntityId != null && !isIncidentToSelection

                    val baseAlpha =
                        when {
                            eInkPolicy.isActive -> if (isDimmed) 0.35f else 1.0f
                            isIncidentToSelection -> 0.98f
                            isDimmed -> 0.16f
                            else -> 0.78f
                        }

                    val status = edge.assertion.currentStatus
                    val baseEdgeColor =
                        if (eInkPolicy.isActive) {
                            colors.onSurface
                        } else if (isIncidentToSelection) {
                            colors.primary
                        } else {
                            when (status) {
                                EpistemicStatus.OBSERVED -> colors.observed
                                EpistemicStatus.CORROBORATED -> colors.corroborated
                                EpistemicStatus.CLAIMED -> colors.claimed
                                EpistemicStatus.INFERRED -> colors.inferred
                                EpistemicStatus.CONTRADICTED -> colors.contradicted
                                EpistemicStatus.RETRACTED -> colors.retracted
                                EpistemicStatus.UNRESOLVED -> colors.unresolved
                            }
                        }
                    val edgeColor = baseEdgeColor.copy(alpha = baseAlpha)

                    val epistemicStyle = eInkPolicy.getEpistemicStroke(status)
                    val pathEffect =
                        when (epistemicStyle.pattern) {
                            StrokePattern.DASHED ->
                                PathEffect.dashPathEffect(floatArrayOf(14f, 8f), 0f)
                            StrokePattern.DOTTED ->
                                PathEffect.dashPathEffect(floatArrayOf(5f, 6f), 0f)
                            else -> null
                        }

                    val strokePx =
                        if (isIncidentToSelection) {
                            (epistemicStyle.strokeWidthDp + 1.dp).toPx()
                        } else {
                            epistemicStyle.strokeWidthDp.toPx()
                        }

                    val dx = dst.screenX - src.screenX
                    val dy = dst.screenY - src.screenY
                    val dist = hypot(dx, dy).coerceAtLeast(1f)
                    val nx = -dy / dist
                    val ny = dx / dist
                    val isStraightEdge = kotlin.math.abs(edge.curvature) < 0.005f
                    val curveOffset = if (isStraightEdge) 0f else dist * edge.curvature
                    val ctrlX = (src.screenX + dst.screenX) * 0.5f + nx * curveOffset
                    val ctrlY = (src.screenY + dst.screenY) * 0.5f + ny * curveOffset

                    if (isStraightEdge) {
                        drawLine(
                            color = edgeColor,
                            start = Offset(src.screenX, src.screenY),
                            end = Offset(dst.screenX, dst.screenY),
                            strokeWidth = strokePx,
                            cap = StrokeCap.Round,
                            pathEffect = pathEffect,
                        )
                    } else {
                        val path =
                            Path().apply {
                                moveTo(src.screenX, src.screenY)
                                quadraticTo(ctrlX, ctrlY, dst.screenX, dst.screenY)
                            }

                        drawPath(
                            path = path,
                            color = edgeColor,
                            style =
                                Stroke(
                                    width = strokePx,
                                    cap = StrokeCap.Round,
                                    pathEffect = pathEffect,
                                ),
                        )
                    }

                    // Draw directional arrowhead near the target node boundary
                    val targetRadiusPx =
                        (
                            dst.layout.baseRadiusDp.dp
                                .toPx() * dst.scale
                        ).coerceIn(16.dp.toPx(), 42.dp.toPx())
                    if (dist > targetRadiusPx * 1.8f) {
                        val tArrow =
                            (1f - (targetRadiusPx + 6.dp.toPx()) / dist).coerceIn(
                                0.55f,
                                0.92f,
                            )
                        val oneMinusT = 1f - tArrow
                        val tipX =
                            oneMinusT * oneMinusT * src.screenX +
                                2f * oneMinusT * tArrow * ctrlX +
                                tArrow * tArrow * dst.screenX
                        val tipY =
                            oneMinusT * oneMinusT * src.screenY +
                                2f * oneMinusT * tArrow * ctrlY +
                                tArrow * tArrow * dst.screenY
                        val tanX =
                            2f * oneMinusT * (ctrlX - src.screenX) +
                                2f * tArrow * (dst.screenX - ctrlX)
                        val tanY =
                            2f * oneMinusT * (ctrlY - src.screenY) +
                                2f * tArrow * (dst.screenY - ctrlY)
                        val angle = atan2(tanY, tanX)
                        val arrowLen = if (isIncidentToSelection) 10.dp.toPx() else 8.dp.toPx()
                        val spread = 0.46f

                        val arrowPath =
                            Path().apply {
                                moveTo(tipX, tipY)
                                lineTo(
                                    tipX - arrowLen * cos(angle - spread),
                                    tipY - arrowLen * sin(angle - spread),
                                )
                                lineTo(
                                    tipX - arrowLen * cos(angle + spread),
                                    tipY - arrowLen * sin(angle + spread),
                                )
                                close()
                            }
                        drawPath(path = arrowPath, color = edgeColor)
                    }

                    // Draw predicate pill label along the curve midpoint
                    val minLabelDistPx =
                        when {
                            isIncidentToSelection -> 54.dp.toPx()
                            entities.size <= 14 || effectiveZoom >= 1.25f -> 76.dp.toPx()
                            else -> 135.dp.toPx()
                        }
                    if ((showEdgeLabels && (!isDimmed || entities.size <= 14)) ||
                        isIncidentToSelection
                    ) {
                        val midX = 0.25f * src.screenX + 0.5f * ctrlX + 0.25f * dst.screenX
                        val midY = 0.25f * src.screenY + 0.5f * ctrlY + 0.25f * dst.screenY
                        val rawPred =
                            edge.assertion.assertion.predicate
                                .trim()
                        val predLabel =
                            if (rawPred.length > 14) rawPred.take(13) + "…" else rawPred
                        if (predLabel.isNotEmpty() && dist > minLabelDistPx) {
                            val measured =
                                textMeasurer.measure(
                                    text = AnnotatedString(predLabel),
                                    style =
                                        edgeLabelStyle.copy(
                                            color =
                                                if (isIncidentToSelection) {
                                                    colors.primary
                                                } else {
                                                    colors.onSurfaceVariant.copy(alpha = baseAlpha)
                                                },
                                        ),
                                )
                            val padX = 5.dp.toPx()
                            val padY = 2.dp.toPx()
                            val boxW = measured.size.width + padX * 2
                            val boxH = measured.size.height + padY * 2
                            val topLeft = Offset(midX - boxW / 2f, midY - boxH / 2f)

                            drawRoundRect(
                                color =
                                    colors.surface.copy(
                                        alpha = if (eInkPolicy.isActive) 1f else 0.88f * baseAlpha,
                                    ),
                                topLeft = topLeft,
                                size = Size(boxW, boxH),
                                cornerRadius = CornerRadius(4.dp.toPx()),
                            )
                            if (isIncidentToSelection || eInkPolicy.isActive) {
                                drawRoundRect(
                                    color = edgeColor,
                                    topLeft = topLeft,
                                    size = Size(boxW, boxH),
                                    cornerRadius = CornerRadius(4.dp.toPx()),
                                    style = Stroke(width = 1.dp.toPx()),
                                )
                            }
                            drawText(
                                textLayoutResult = measured,
                                topLeft = Offset(topLeft.x + padX, topLeft.y + padY),
                            )
                        }
                    }
                }

                // 3. Draw 2D Nodes with Crisp SVG Vector Icons Inside
                for (proj in projectedNodes) {
                    val entityId = proj.entity.entity.entityId.value
                    val isSelected = entityId == selectedEntityId
                    val isNeighbor = selectedNeighbors.contains(entityId)
                    val isDimmed = selectedEntityId != null && !isNeighbor

                    val nodeAlpha =
                        when {
                            eInkPolicy.isActive -> if (isDimmed) 0.4f else 1.0f
                            isSelected || isNeighbor -> 1.0f
                            isDimmed -> 0.22f
                            else -> 0.96f
                        }

                    val radiusPx =
                        (
                            proj.layout.baseRadiusDp.dp
                                .toPx() * proj.scale
                        ).coerceIn(16.dp.toPx(), 42.dp.toPx())
                    val center = Offset(proj.screenX, proj.screenY)

                    val baseNodeColor =
                        when (proj.entity.entity.entityType) {
                            EntityType.PERSON, EntityType.PERSONA -> colors.primary
                            EntityType.LOCATION -> colors.corroborated
                            EntityType.OBJECT -> colors.claimed
                            EntityType.ORGANIZATION -> colors.inferred
                        }

                    // Outer selection / hub ring
                    if (isSelected) {
                        if (!eInkPolicy.isActive) {
                            drawCircle(
                                color = baseNodeColor.copy(alpha = 0.20f),
                                radius = radiusPx + 8.dp.toPx(),
                                center = center,
                            )
                        }
                        drawCircle(
                            color = if (eInkPolicy.isActive) colors.onSurface else baseNodeColor,
                            radius = radiusPx + 5.dp.toPx(),
                            center = center,
                            style =
                                Stroke(
                                    width = if (eInkPolicy.isActive) 3.dp.toPx() else 2.2.dp.toPx(),
                                ),
                        )
                    } else if (proj.layout.degree >= 4 && !isDimmed && !eInkPolicy.isActive) {
                        drawCircle(
                            color = baseNodeColor.copy(alpha = 0.24f * nodeAlpha),
                            radius = radiusPx + 4.dp.toPx(),
                            center = center,
                            style = Stroke(width = 1.2.dp.toPx()),
                        )
                    }

                    // 2D Node Body: flat semantic circle in Standard mode, geometric shape in E-Ink mode
                    if (eInkPolicy.isActive) {
                        drawEInkGeometricNode(
                            entityType = proj.entity.entity.entityType,
                            center = center,
                            radiusPx = radiusPx,
                            fillColor = if (isSelected) colors.primaryContainer else colors.surface,
                            strokeColor = colors.onSurface.copy(alpha = nodeAlpha),
                            strokeWidthPx = if (isSelected) 3.dp.toPx() else 2.dp.toPx(),
                        )
                    } else {
                        drawCircle(
                            color = baseNodeColor.copy(alpha = nodeAlpha),
                            radius = radiusPx,
                            center = center,
                        )
                        drawCircle(
                            color = colors.surface.copy(alpha = 0.90f * nodeAlpha),
                            radius = radiusPx,
                            center = center,
                            style = Stroke(width = 1.8.dp.toPx()),
                        )
                    }

                    // Crisp SVG vector icon inside node
                    val iconColor =
                        if (eInkPolicy.isActive) {
                            colors.onSurface.copy(alpha = nodeAlpha)
                        } else {
                            colors.onPrimary.copy(alpha = nodeAlpha)
                        }
                    with(MixuIcons) {
                        drawEntityVectorIcon(
                            entityType = proj.entity.entity.entityType,
                            center = center,
                            iconRadiusPx = radiusPx * 0.56f,
                            color = iconColor,
                            strokeWidthPx = 1.8.dp.toPx(),
                        )
                    }

                    // Floating entity name pill underneath node
                    val displayName =
                        proj.entity.canonicalName?.displayText
                            ?: proj.entity.entity.entityId.value
                    val truncatedName =
                        if (displayName.length > 10) {
                            displayName.take(9) + "…"
                        } else {
                            displayName
                        }
                    val labelText =
                        if (proj.layout.degree >= 2) {
                            "$truncatedName · ${proj.layout.degree}"
                        } else {
                            truncatedName
                        }
                    val labelMeasured =
                        textMeasurer.measure(
                            text = AnnotatedString(labelText),
                            style =
                                nodeLabelStyle.copy(
                                    color =
                                        if (isSelected) {
                                            colors.primary
                                        } else {
                                            colors.onSurface.copy(alpha = nodeAlpha)
                                        },
                                ),
                        )
                    val lPadX = 7.dp.toPx()
                    val lPadY = 3.dp.toPx()
                    val pillW = labelMeasured.size.width + lPadX * 2
                    val pillH = labelMeasured.size.height + lPadY * 2
                    val pillTopLeft =
                        Offset(
                            center.x - pillW / 2f,
                            center.y + radiusPx + 5.dp.toPx(),
                        )

                    drawRoundRect(
                        color =
                            if (isSelected) {
                                colors.primaryContainer
                            } else {
                                colors.surface.copy(
                                    alpha = if (eInkPolicy.isActive) 1f else 0.92f * nodeAlpha,
                                )
                            },
                        topLeft = pillTopLeft,
                        size = Size(pillW, pillH),
                        cornerRadius =
                            CornerRadius(if (eInkPolicy.isActive) 2.dp.toPx() else 6.dp.toPx()),
                    )
                    drawRoundRect(
                        color =
                            if (isSelected) {
                                colors.primary
                            } else {
                                colors.outlineVariant.copy(alpha = nodeAlpha)
                            },
                        topLeft = pillTopLeft,
                        size = Size(pillW, pillH),
                        cornerRadius =
                            CornerRadius(if (eInkPolicy.isActive) 2.dp.toPx() else 6.dp.toPx()),
                        style =
                            Stroke(
                                width = if (isSelected) 1.5.dp.toPx() else 1.dp.toPx(),
                            ),
                    )
                    drawText(
                        textLayoutResult = labelMeasured,
                        topLeft = Offset(pillTopLeft.x + lPadX, pillTopLeft.y + lPadY),
                    )
                }
            }
        }

        // Top-Left Mode Switcher & Graph Stats Bar
        GraphModeToolbar(
            graphMode = graphMode,
            physicsMode = physicsMode,
            entityTypeFilter = entityTypeFilter,
            showEdgeLabels = showEdgeLabels,
            entityCount = filteredEntities.size,
            edgeCount = layoutResult.edges.size,
            onModeChange = { newMode -> graphMode = newMode },
            onCyclePhysicsMode = {
                physicsMode =
                    when (physicsMode) {
                        GraphPhysicsMode.LIVE_SPRING -> GraphPhysicsMode.STRONG_GRAVITY
                        GraphPhysicsMode.STRONG_GRAVITY -> GraphPhysicsMode.PIN_NODE
                        GraphPhysicsMode.PIN_NODE -> GraphPhysicsMode.LIVE_SPRING
                    }
            },
            onEntityTypeFilterChange = { filter -> entityTypeFilter = filter },
            onToggleLabels = { showEdgeLabels = !showEdgeLabels },
            onResetView = {
                interactivePanX = 0f
                interactivePanY = 0f
                interactiveZoom = 1.0f
                nodeOffsets = emptyMap()
                pinnedNodeIds = emptySet()
                entityTypeFilter = null
                onResetViewport()
            },
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
        )

        // Discrete Stepped Pan & Zoom Controls (E-Ink compliant)
        DiscreteViewportControls(
            zoomLevel = effectiveZoom,
            onStepZoom = onStepZoom,
            onStepPan = onStepPan,
            onReset = {
                interactivePanX = 0f
                interactivePanY = 0f
                interactiveZoom = 1.0f
                nodeOffsets = emptyMap()
                pinnedNodeIds = emptySet()
                onResetViewport()
            },
            modifier =
                Modifier
                    .align(if (maxWidth < 560.dp) Alignment.BottomEnd else Alignment.TopEnd)
                    .padding(10.dp),
        )
    }
}

private fun DrawScope.drawGraphBackdrop(
    centerX: Float,
    centerY: Float,
    minDim: Float,
    zoom: Float,
    outlineColor: Color,
    primaryColor: Color,
) {
    val center = Offset(centerX, centerY)
    val baseRadius = (minDim * 0.38f * zoom).coerceIn(110f, minDim * 0.65f)

    // Subtle radial ambient glow
    drawCircle(
        brush =
            Brush.radialGradient(
                colors =
                    listOf(
                        primaryColor.copy(alpha = 0.05f),
                        Color.Transparent,
                    ),
                center = center,
                radius = baseRadius * 1.45f,
            ),
        radius = baseRadius * 1.45f,
        center = center,
    )
    drawCircle(
        color = outlineColor.copy(alpha = 0.16f),
        radius = baseRadius,
        center = center,
        style =
            Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 10f), 0f),
            ),
    )
}

private fun DrawScope.drawEInkGeometricNode(
    entityType: EntityType,
    center: Offset,
    radiusPx: Float,
    fillColor: Color,
    strokeColor: Color,
    strokeWidthPx: Float,
) {
    when (entityType) {
        EntityType.PERSON, EntityType.PERSONA -> {
            drawCircle(color = fillColor, radius = radiusPx, center = center)
            drawCircle(
                color = strokeColor,
                radius = radiusPx,
                center = center,
                style = Stroke(width = strokeWidthPx),
            )
        }
        EntityType.LOCATION -> {
            val topLeft = Offset(center.x - radiusPx, center.y - radiusPx)
            val boxSize = Size(radiusPx * 2f, radiusPx * 2f)
            drawRect(color = fillColor, topLeft = topLeft, size = boxSize)
            drawRect(
                color = strokeColor,
                topLeft = topLeft,
                size = boxSize,
                style = Stroke(width = strokeWidthPx),
            )
        }
        EntityType.OBJECT -> {
            val path =
                Path().apply {
                    moveTo(center.x, center.y - radiusPx * 1.15f)
                    lineTo(center.x + radiusPx * 1.15f, center.y)
                    lineTo(center.x, center.y + radiusPx * 1.15f)
                    lineTo(center.x - radiusPx * 1.15f, center.y)
                    close()
                }
            drawPath(path = path, color = fillColor)
            drawPath(path = path, color = strokeColor, style = Stroke(width = strokeWidthPx))
        }
        EntityType.ORGANIZATION -> {
            val topLeft = Offset(center.x - radiusPx * 1.2f, center.y - radiusPx * 0.85f)
            val boxSize = Size(radiusPx * 2.4f, radiusPx * 1.7f)
            drawRoundRect(
                color = fillColor,
                topLeft = topLeft,
                size = boxSize,
                cornerRadius = CornerRadius(radiusPx * 0.6f),
            )
            drawRoundRect(
                color = strokeColor,
                topLeft = topLeft,
                size = boxSize,
                cornerRadius = CornerRadius(radiusPx * 0.6f),
                style = Stroke(width = strokeWidthPx),
            )
        }
    }
}

@Composable
private fun GraphModeToolbar(
    graphMode: GraphDimensionMode,
    physicsMode: GraphPhysicsMode,
    entityTypeFilter: EntityType?,
    showEdgeLabels: Boolean,
    entityCount: Int,
    edgeCount: Int,
    onModeChange: (GraphDimensionMode) -> Unit,
    onCyclePhysicsMode: () -> Unit,
    onEntityTypeFilterChange: (EntityType?) -> Unit,
    onToggleLabels: () -> Unit,
    onResetView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val eInkPolicy = MixuTheme.einkPolicy
    val strokeWidth = if (eInkPolicy.isActive) 2.dp else 1.dp
    val cornerRadius = if (eInkPolicy.isActive) 2.dp else 10.dp

    val physicsLabel =
        when (physicsMode) {
            GraphPhysicsMode.LIVE_SPRING -> stringResource(R.string.caseboard_physics_spring)
            GraphPhysicsMode.STRONG_GRAVITY -> stringResource(R.string.caseboard_physics_gravity)
            GraphPhysicsMode.PIN_NODE -> stringResource(R.string.caseboard_physics_pin)
        }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        color = colors.surface.copy(alpha = if (eInkPolicy.isActive) 1f else 0.92f),
        border = BorderStroke(strokeWidth, colors.outlineVariant),
        shadowElevation = if (eInkPolicy.isActive) 0.dp else 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GraphPillButton(
                    label = stringResource(R.string.caseboard_graph_mode_2d),
                    selected = graphMode == GraphDimensionMode.FORCE_2D,
                    onClick = { onModeChange(GraphDimensionMode.FORCE_2D) },
                    icon = MixuIcons.ForceGraph,
                )
                GraphPillButton(
                    label = stringResource(R.string.caseboard_graph_mode_hierarchical),
                    selected = graphMode == GraphDimensionMode.HIERARCHICAL,
                    onClick = { onModeChange(GraphDimensionMode.HIERARCHICAL) },
                    icon = MixuIcons.HierarchicalGraph,
                )
                GraphPillButton(
                    label = physicsLabel,
                    selected = physicsMode != GraphPhysicsMode.PIN_NODE,
                    onClick = onCyclePhysicsMode,
                )
            }

            // Canonical Mixu Narrative Dimensions: Characters / Locations / Clues & Objects
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GraphPillButton(
                    label = "全部",
                    selected = entityTypeFilter == null,
                    onClick = { onEntityTypeFilterChange(null) },
                )
                GraphPillButton(
                    label = "👤 人物",
                    selected = entityTypeFilter == EntityType.PERSON,
                    onClick = {
                        onEntityTypeFilterChange(
                            if (entityTypeFilter == EntityType.PERSON) null else EntityType.PERSON,
                        )
                    },
                )
                GraphPillButton(
                    label = "📍 地点",
                    selected = entityTypeFilter == EntityType.LOCATION,
                    onClick = {
                        onEntityTypeFilterChange(
                            if (entityTypeFilter ==
                                EntityType.LOCATION
                            ) {
                                null
                            } else {
                                EntityType.LOCATION
                            },
                        )
                    },
                )
                GraphPillButton(
                    label = "🔑 证物",
                    selected = entityTypeFilter == EntityType.OBJECT,
                    onClick = {
                        onEntityTypeFilterChange(
                            if (entityTypeFilter == EntityType.OBJECT) null else EntityType.OBJECT,
                        )
                    },
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GraphPillButton(
                    label = stringResource(R.string.caseboard_graph_labels_toggle),
                    selected = showEdgeLabels,
                    onClick = onToggleLabels,
                )
                GraphPillButton(
                    label = stringResource(R.string.caseboard_graph_recenter),
                    selected = false,
                    onClick = onResetView,
                )
                Text(
                    text =
                        stringResource(
                            R.string.caseboard_graph_stats,
                            entityCount,
                            edgeCount,
                        ),
                    style =
                        MixuTheme.typography.caption.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun GraphPillButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val colors = MixuTheme.colors
    val eInkPolicy = MixuTheme.einkPolicy
    val shape = RoundedCornerShape(if (eInkPolicy.isActive) 2.dp else 6.dp)
    val strokeWidth = if (eInkPolicy.isActive) 2.dp else 1.dp
    val contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface

    Surface(
        modifier =
            modifier
                .height(30.dp)
                .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) colors.primaryContainer else colors.surfaceVariant.copy(alpha = 0.6f),
        border =
            BorderStroke(
                strokeWidth,
                if (selected) colors.primary else colors.outlineVariant,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.width(14.dp).height(14.dp),
                )
            }
            Text(
                text = label,
                style = MixuTheme.typography.caption,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
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
    val strokeWidth = if (eInkPolicy.isActive) 2.dp else 1.dp
    val cornerRadius = if (eInkPolicy.isActive) 2.dp else 10.dp

    Card(
        border = BorderStroke(strokeWidth, colors.outlineVariant),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    colors.surface.copy(
                        alpha = if (eInkPolicy.isActive) 1f else 0.92f,
                    ),
            ),
        shape = RoundedCornerShape(cornerRadius),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = { onStepZoom(false) },
                    modifier = Modifier.sizeIn(minWidth = 40.dp, minHeight = 36.dp),
                    shape = RoundedCornerShape(if (eInkPolicy.isActive) 2.dp else 6.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("－", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Box(
                    modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 36.dp),
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
                    modifier = Modifier.sizeIn(minWidth = 40.dp, minHeight = 36.dp),
                    shape = RoundedCornerShape(if (eInkPolicy.isActive) 2.dp else 6.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("＋", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = { onStepPan(-60f, 0f) },
                    modifier = Modifier.sizeIn(minWidth = 40.dp, minHeight = 36.dp),
                    shape = RoundedCornerShape(if (eInkPolicy.isActive) 2.dp else 6.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("◀", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 36.dp),
                    shape = RoundedCornerShape(if (eInkPolicy.isActive) 2.dp else 6.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("●", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = { onStepPan(60f, 0f) },
                    modifier = Modifier.sizeIn(minWidth = 40.dp, minHeight = 36.dp),
                    shape = RoundedCornerShape(if (eInkPolicy.isActive) 2.dp else 6.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("▶", fontSize = 11.sp)
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
    entityDisplayNames: Map<String, String>,
    onClose: () -> Unit,
    onJumpToOrdinal: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val eInkPolicy = MixuTheme.einkPolicy
    val strokeWidth = if (eInkPolicy.isActive) 2.5.dp else 1.5.dp
    val displayName = entity.canonicalName?.displayText ?: entity.entity.entityId.value

    Card(
        border = BorderStroke(strokeWidth, colors.primary),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        shape = RoundedCornerShape(if (eInkPolicy.isActive) 2.dp else 12.dp),
        modifier = modifier.heightIn(max = 260.dp),
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
                    modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 40.dp),
                    shape = RoundedCornerShape(if (eInkPolicy.isActive) 2.dp else 8.dp),
                ) {
                    Text(
                        stringResource(R.string.caseboard_entity_close),
                        style = MixuTheme.typography.caption,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            val firstRevealMod =
                if (onJumpToOrdinal != null) {
                    Modifier.clickable { onJumpToOrdinal(entity.entity.firstReveal) }
                } else {
                    Modifier
                }
            Text(
                text =
                    stringResource(
                        R.string.caseboard_entity_first_reveal,
                        entity.entity.firstReveal + 1,
                    ),
                style = MixuTheme.typography.caption,
                color = if (onJumpToOrdinal != null) colors.primary else colors.onSurfaceVariant,
                modifier = firstRevealMod,
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
                        AssertionItemRow(
                            assertion = assertion,
                            entityDisplayNames = entityDisplayNames,
                            onJumpToOrdinal = onJumpToOrdinal,
                        )
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
private fun AssertionItemRow(
    assertion: RevealedAssertion,
    entityDisplayNames: Map<String, String> = emptyMap(),
    onJumpToOrdinal: ((Int) -> Unit)? = null,
) {
    val colors = MixuTheme.colors
    val eInkPolicy = MixuTheme.einkPolicy
    val epistemicStyle = eInkPolicy.getEpistemicStroke(assertion.currentStatus)

    val objectDesc =
        when (val obj = assertion.assertion.objectRef) {
            is ObjectRef.Entity ->
                entityDisplayNames[obj.entityId.value] ?: obj.entityId.value
            is ObjectRef.Literal -> "${obj.value} ${obj.unit ?: ""}".trim()
        }

    val rowMod =
        if (onJumpToOrdinal != null) {
            Modifier
                .fillMaxWidth()
                .clickable { onJumpToOrdinal(assertion.assertion.firstReveal) }
                .padding(vertical = 4.dp)
        } else {
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        }

    Row(
        modifier = rowMod,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val rawSub = assertion.assertion.subjectId.value
            val sub = entityDisplayNames[rawSub] ?: rawSub
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
                color = if (onJumpToOrdinal != null) colors.primary else colors.onSurfaceVariant,
            )
        }

        Box(
            modifier =
                Modifier
                    .border(BorderStroke(1.dp, colors.outline), RoundedCornerShape(4.dp))
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
    entityDisplayNames: Map<String, String> = emptyMap(),
    onJumpToOrdinal: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val eInkPolicy = MixuTheme.einkPolicy

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
                    shape = RoundedCornerShape(if (eInkPolicy.isActive) 2.dp else 8.dp),
                    border = BorderStroke(1.dp, colors.outlineVariant),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        AssertionItemRow(
                            assertion = assertion,
                            entityDisplayNames = entityDisplayNames,
                            onJumpToOrdinal = onJumpToOrdinal,
                        )
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
            shape = RoundedCornerShape(if (eInkPolicy.isActive) 2.dp else 8.dp),
            border = BorderStroke(strokeWidth, colors.outlineVariant),
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
                modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 44.dp),
                shape = RoundedCornerShape(if (eInkPolicy.isActive) 2.dp else 8.dp),
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
                        cornerRadius = CornerRadius(4.dp.toPx()),
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
                    modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 40.dp),
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
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 44.dp),
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
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 44.dp),
            ) {
                Text(stringResource(R.string.caseboard_hypothesis_cancel))
            }
        },
    )
}
