// SPDX-License-Identifier: MPL-2.0
@file:Suppress("ktlint:standard:function-naming")

package app.mixu.client.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mixu.client.R
import app.mixu.client.auth.UserSession
import app.mixu.client.config.FontFamilyChoice
import app.mixu.client.config.GraphDimensionMode
import app.mixu.client.config.MarginPreset
import app.mixu.client.config.ReaderDisplayConfig
import app.mixu.client.config.ReadingLayoutMode
import app.mixu.client.eink.EInkMode
import app.mixu.client.eink.StrokePattern
import app.mixu.client.theme.MixuTheme
import app.mixu.client.theme.MixuThemeMode
import app.mixu.graph.EpistemicStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPreviewScreen(
    config: ReaderDisplayConfig,
    onConfigChange: (ReaderDisplayConfig) -> Unit,
    modifier: Modifier = Modifier,
    userSession: UserSession = UserSession.DEFAULT,
    onNavigateBack: (() -> Unit)? = null,
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography
    val einkPolicy = MixuTheme.einkPolicy
    val scrollState = rememberScrollState()

    val flingBehavior =
        if (einkPolicy.isActive) {
            remember {
                object : FlingBehavior {
                    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float =
                        0f
                }
            }
        } else {
            ScrollableDefaults.flingBehavior()
        }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (onNavigateBack != null) {
                        OutlinedButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.padding(start = 12.dp),
                            border =
                                BorderStroke(
                                    width = if (einkPolicy.isActive) 2.dp else 1.dp,
                                    color =
                                        if (einkPolicy.isActive) {
                                            colors.outline
                                        } else {
                                            colors.outlineVariant
                                        },
                                ),
                            shape =
                                RoundedCornerShape(
                                    if (einkPolicy.isActive) 0.dp else 6.dp,
                                ),
                        ) {
                            Text(
                                text = stringResource(R.string.library_switch_to_library),
                                style = typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface,
                            )
                        }
                    }
                },
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = typography.titleLarge,
                            color = colors.onSurface,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.app_tagline),
                            style = typography.caption,
                            color = colors.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    val badgeText =
                        if (einkPolicy.isActive) {
                            stringResource(R.string.eink_active_badge)
                        } else {
                            stringResource(R.string.eink_standard_badge)
                        }
                    val badgeBg =
                        if (einkPolicy.isActive) {
                            colors.surface
                        } else {
                            colors.primaryContainer
                        }
                    val badgeFg =
                        if (einkPolicy.isActive) {
                            colors.onSurface
                        } else {
                            colors.onPrimaryContainer
                        }
                    Box(
                        modifier =
                            Modifier
                                .padding(end = 12.dp)
                                .border(
                                    border =
                                        BorderStroke(
                                            width = if (einkPolicy.isActive) 2.dp else 1.dp,
                                            color = colors.primary,
                                        ),
                                    shape = RoundedCornerShape(4.dp),
                                ).background(
                                    color = badgeBg,
                                    shape = RoundedCornerShape(4.dp),
                                ).padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = badgeText,
                            style = typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = badgeFg,
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = colors.surface,
                    ),
            )
        },
        containerColor = colors.background,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(state = scrollState, flingBehavior = flingBehavior)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Section 1: Account & Privacy (Guest Mode status)
            SectionCard(title = stringResource(R.string.account_heading)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.account_guest_title),
                            style = typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface,
                        )
                        Box(
                            modifier =
                                Modifier
                                    .border(
                                        border =
                                            BorderStroke(
                                                width = if (einkPolicy.isActive) 2.dp else 1.dp,
                                                color = colors.primary,
                                            ),
                                        shape = RoundedCornerShape(4.dp),
                                    ).background(
                                        color =
                                            if (einkPolicy.isActive) {
                                                colors.surface
                                            } else {
                                                colors.primaryContainer
                                            },
                                        shape = RoundedCornerShape(4.dp),
                                    ).padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.account_guest_badge),
                                style = typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color =
                                    if (einkPolicy.isActive) {
                                        colors.onSurface
                                    } else {
                                        colors.onPrimaryContainer
                                    },
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.account_guest_desc),
                        style = typography.caption,
                        color = colors.onSurfaceVariant,
                    )

                    HorizontalDivider(color = colors.outlineVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.account_session_id_label),
                            style = typography.caption,
                            color = colors.onSurfaceVariant,
                        )
                        Text(
                            text = userSession.anonymousId,
                            style = typography.caption,
                            fontWeight = FontWeight.Medium,
                            color = colors.onSurface,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.account_cloud_sync_label),
                            style = typography.caption,
                            color = colors.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.account_cloud_sync_status),
                            style = typography.caption,
                            fontWeight = FontWeight.Medium,
                            color = colors.onSurface,
                        )
                    }
                }
            }

            // Section 2: Themes
            SectionCard(title = stringResource(R.string.theme_label)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeOptionButton(
                        label = stringResource(R.string.theme_light),
                        isSelected = config.themeMode == MixuThemeMode.LIGHT,
                        onClick = { onConfigChange(config.copy(themeMode = MixuThemeMode.LIGHT)) },
                        modifier = Modifier.weight(1f),
                    )
                    ThemeOptionButton(
                        label = stringResource(R.string.theme_dark),
                        isSelected = config.themeMode == MixuThemeMode.DARK,
                        onClick = { onConfigChange(config.copy(themeMode = MixuThemeMode.DARK)) },
                        modifier = Modifier.weight(1f),
                    )
                    ThemeOptionButton(
                        label = stringResource(R.string.theme_sepia),
                        isSelected = config.themeMode == MixuThemeMode.SEPIA,
                        onClick = { onConfigChange(config.copy(themeMode = MixuThemeMode.SEPIA)) },
                        modifier = Modifier.weight(1f),
                    )
                    ThemeOptionButton(
                        label = stringResource(R.string.theme_eink),
                        isSelected = config.themeMode == MixuThemeMode.EINK,
                        onClick = { onConfigChange(config.copy(themeMode = MixuThemeMode.EINK)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Section 3: E-Ink Display Policy
            SectionCard(title = stringResource(R.string.eink_mode_label)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ThemeOptionButton(
                            label = stringResource(R.string.eink_mode_auto),
                            isSelected = config.eInkMode == EInkMode.AUTO,
                            onClick = { onConfigChange(config.copy(eInkMode = EInkMode.AUTO)) },
                            modifier = Modifier.weight(1f),
                        )
                        ThemeOptionButton(
                            label = stringResource(R.string.eink_mode_off),
                            isSelected = config.eInkMode == EInkMode.OFF,
                            onClick = { onConfigChange(config.copy(eInkMode = EInkMode.OFF)) },
                            modifier = Modifier.weight(1f),
                        )
                        ThemeOptionButton(
                            label = stringResource(R.string.eink_mode_force_on),
                            isSelected = config.eInkMode == EInkMode.FORCE_ON,
                            onClick = {
                                onConfigChange(config.copy(eInkMode = EInkMode.FORCE_ON))
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Text(
                        text = stringResource(R.string.eink_animation_suppression_desc),
                        style = typography.caption,
                        color = colors.onSurfaceVariant,
                    )

                    HorizontalDivider(color = colors.outlineVariant)

                    // Refresh interval options
                    Text(
                        text = stringResource(R.string.eink_refresh_interval_label),
                        style = typography.labelLarge,
                        color = colors.onSurface,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(5, 10, 20, 0).forEach { interval ->
                            val text =
                                if (interval == 0) {
                                    stringResource(R.string.eink_refresh_manual_only)
                                } else {
                                    stringResource(R.string.eink_refresh_interval_pages, interval)
                                }
                            ThemeOptionButton(
                                label = text,
                                isSelected = config.eInkFullRefreshInterval == interval,
                                onClick = {
                                    onConfigChange(
                                        config.copy(eInkFullRefreshInterval = interval),
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            // Section 4: Typography and Viewport
            SectionCard(title = stringResource(R.string.typography_heading)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Font Family
                    Text(
                        text = stringResource(R.string.font_family_label),
                        style = typography.labelLarge,
                        color = colors.onSurface,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ThemeOptionButton(
                            label = stringResource(R.string.font_family_serif),
                            isSelected = config.fontFamilyChoice == FontFamilyChoice.SERIF,
                            onClick = {
                                onConfigChange(
                                    config.copy(fontFamilyChoice = FontFamilyChoice.SERIF),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                        ThemeOptionButton(
                            label = stringResource(R.string.font_family_sans),
                            isSelected = config.fontFamilyChoice == FontFamilyChoice.SANS_SERIF,
                            onClick = {
                                onConfigChange(
                                    config.copy(fontFamilyChoice = FontFamilyChoice.SANS_SERIF),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                        ThemeOptionButton(
                            label = stringResource(R.string.font_family_mono),
                            isSelected = config.fontFamilyChoice == FontFamilyChoice.MONOSPACE,
                            onClick = {
                                onConfigChange(
                                    config.copy(fontFamilyChoice = FontFamilyChoice.MONOSPACE),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // Margins
                    Text(
                        text = stringResource(R.string.margin_label),
                        style = typography.labelLarge,
                        color = colors.onSurface,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ThemeOptionButton(
                            label = stringResource(R.string.margin_compact),
                            isSelected = config.marginPreset == MarginPreset.COMPACT,
                            onClick = {
                                onConfigChange(config.copy(marginPreset = MarginPreset.COMPACT))
                            },
                            modifier = Modifier.weight(1f),
                        )
                        ThemeOptionButton(
                            label = stringResource(R.string.margin_normal),
                            isSelected = config.marginPreset == MarginPreset.NORMAL,
                            onClick = {
                                onConfigChange(config.copy(marginPreset = MarginPreset.NORMAL))
                            },
                            modifier = Modifier.weight(1f),
                        )
                        ThemeOptionButton(
                            label = stringResource(R.string.margin_wide),
                            isSelected = config.marginPreset == MarginPreset.WIDE,
                            onClick = {
                                onConfigChange(config.copy(marginPreset = MarginPreset.WIDE))
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // Font scale
                    val scaleLabel = stringResource(R.string.font_scale_label)
                    val fontSizeSummary =
                        "$scaleLabel: ${config.fontScaleMultiplier}x " +
                            "(${config.effectiveFontSizeSp()} sp)"
                    Text(
                        text = fontSizeSummary,
                        style = typography.labelLarge,
                        color = colors.onSurface,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(0.85f, 1.0f, 1.25f, 1.5f).forEach { scale ->
                            ThemeOptionButton(
                                label = "${scale}x",
                                isSelected = config.fontScaleMultiplier == scale,
                                onClick = {
                                    onConfigChange(config.copy(fontScaleMultiplier = scale))
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    // Line height multiplier
                    val lineHeightLabel = stringResource(R.string.line_height_label)
                    val lineHeightSummary =
                        "$lineHeightLabel: ${config.lineHeightMultiplier}x " +
                            "(${config.effectiveLineHeightSp().toInt()} sp)"
                    Text(
                        text = lineHeightSummary,
                        style = typography.labelLarge,
                        color = colors.onSurface,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(1.2f, 1.5f, 1.8f, 2.0f).forEach { lh ->
                            ThemeOptionButton(
                                label = "${lh}x",
                                isSelected = config.lineHeightMultiplier == lh,
                                onClick = {
                                    onConfigChange(config.copy(lineHeightMultiplier = lh))
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    // Reading Layout Mode (Paged vs Continuous Scroll)
                    Text(
                        text = stringResource(R.string.reader_layout_mode_label),
                        style = typography.labelLarge,
                        color = colors.onSurface,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ThemeOptionButton(
                            label = stringResource(R.string.reader_layout_paged),
                            isSelected = config.readingLayoutMode == ReadingLayoutMode.PAGED,
                            onClick = {
                                onConfigChange(
                                    config.copy(readingLayoutMode = ReadingLayoutMode.PAGED),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                        ThemeOptionButton(
                            label = stringResource(R.string.reader_layout_continuous),
                            isSelected =
                                config.readingLayoutMode == ReadingLayoutMode.CONTINUOUS,
                            onClick = {
                                onConfigChange(
                                    config.copy(readingLayoutMode = ReadingLayoutMode.CONTINUOUS),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // CJK First-Line Paragraph Indentation
                    Text(
                        text = stringResource(R.string.reader_indent_label),
                        style = typography.labelLarge,
                        color = colors.onSurface,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ThemeOptionButton(
                            label = stringResource(R.string.reader_indent_2em),
                            isSelected = config.paragraphIndentEm > 0f,
                            onClick = {
                                onConfigChange(config.copy(paragraphIndentEm = 2.0f))
                            },
                            modifier = Modifier.weight(1f),
                        )
                        ThemeOptionButton(
                            label = stringResource(R.string.reader_indent_none),
                            isSelected = config.paragraphIndentEm == 0f,
                            onClick = {
                                onConfigChange(config.copy(paragraphIndentEm = 0f))
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // Default Knowledge Graph Layout Mode
                    Text(
                        text = stringResource(R.string.caseboard_tab_graph),
                        style = typography.labelLarge,
                        color = colors.onSurface,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ThemeOptionButton(
                            label = stringResource(R.string.caseboard_graph_mode_2d),
                            isSelected = config.graphDimensionMode == GraphDimensionMode.FORCE_2D,
                            onClick = {
                                onConfigChange(
                                    config.copy(graphDimensionMode = GraphDimensionMode.FORCE_2D),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                        ThemeOptionButton(
                            label = stringResource(R.string.caseboard_graph_mode_hierarchical),
                            isSelected =
                                config.graphDimensionMode == GraphDimensionMode.HIERARCHICAL,
                            onClick = {
                                onConfigChange(
                                    config.copy(
                                        graphDimensionMode = GraphDimensionMode.HIERARCHICAL,
                                    ),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Section 5: Reading Behavior Defaults
            SectionCard(title = stringResource(R.string.reading_defaults_heading)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PreferenceToggleRow(
                        title = stringResource(R.string.reader_immersive_label),
                        description = stringResource(R.string.reader_immersive_desc),
                        enabled = config.immersiveReaderMode,
                        onToggle = { enabled ->
                            onConfigChange(config.copy(immersiveReaderMode = enabled))
                        },
                    )

                    HorizontalDivider(color = colors.outlineVariant)

                    PreferenceToggleRow(
                        title = stringResource(R.string.reading_keep_screen_on),
                        description = stringResource(R.string.reading_keep_screen_on_desc),
                        enabled = config.keepScreenOn,
                        onToggle = { enabled ->
                            onConfigChange(config.copy(keepScreenOn = enabled))
                        },
                    )

                    HorizontalDivider(color = colors.outlineVariant)

                    PreferenceToggleRow(
                        title = stringResource(R.string.reading_auto_bookmark),
                        description = stringResource(R.string.reading_auto_bookmark_desc),
                        enabled = config.autoBookmark,
                        onToggle = { enabled ->
                            onConfigChange(config.copy(autoBookmark = enabled))
                        },
                    )
                }
            }

            // Section 6: Book Import Defaults
            SectionCard(title = stringResource(R.string.import_defaults_heading)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PreferenceToggleRow(
                        title = stringResource(R.string.import_auto_detect_chapters),
                        description = stringResource(R.string.import_auto_detect_chapters_desc),
                        enabled = config.autoDetectChapters,
                        onToggle = { enabled ->
                            onConfigChange(config.copy(autoDetectChapters = enabled))
                        },
                    )

                    HorizontalDivider(color = colors.outlineVariant)

                    PreferenceToggleRow(
                        title = stringResource(R.string.import_auto_extract_graph),
                        description = stringResource(R.string.import_auto_extract_graph_desc),
                        enabled = config.autoExtractGraphOnImport,
                        onToggle = { enabled ->
                            onConfigChange(config.copy(autoExtractGraphOnImport = enabled))
                        },
                    )
                }
            }

            // Section 7: Epistemic Status Markers Showcase
            SectionCard(title = stringResource(R.string.epistemic_heading)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EpistemicMarkerCard(
                        status = EpistemicStatus.OBSERVED,
                        title = stringResource(R.string.epistemic_observed),
                        description = stringResource(R.string.epistemic_observed_desc),
                        color = colors.observed,
                    )
                    EpistemicMarkerCard(
                        status = EpistemicStatus.CORROBORATED,
                        title = stringResource(R.string.epistemic_corroborated),
                        description = stringResource(R.string.epistemic_corroborated_desc),
                        color = colors.corroborated,
                    )
                    EpistemicMarkerCard(
                        status = EpistemicStatus.CLAIMED,
                        title = stringResource(R.string.epistemic_claimed),
                        description = stringResource(R.string.epistemic_claimed_desc),
                        color = colors.claimed,
                    )
                    EpistemicMarkerCard(
                        status = EpistemicStatus.INFERRED,
                        title = stringResource(R.string.epistemic_inferred),
                        description = stringResource(R.string.epistemic_inferred_desc),
                        color = colors.inferred,
                    )
                    EpistemicMarkerCard(
                        status = EpistemicStatus.CONTRADICTED,
                        title = stringResource(R.string.epistemic_contradicted),
                        description = stringResource(R.string.epistemic_contradicted_desc),
                        color = colors.contradicted,
                    )
                    EpistemicMarkerCard(
                        status = EpistemicStatus.RETRACTED,
                        title = stringResource(R.string.epistemic_retracted),
                        description = stringResource(R.string.epistemic_retracted_desc),
                        color = colors.retracted,
                    )
                    EpistemicMarkerCard(
                        status = EpistemicStatus.UNRESOLVED,
                        title = stringResource(R.string.epistemic_unresolved),
                        description = stringResource(R.string.epistemic_unresolved_desc),
                        color = colors.unresolved,
                    )
                }
            }

            // Section 8: Reading Surface Live Preview
            SectionCard(title = stringResource(R.string.sample_preview_title)) {
                val horizontalMargin = config.marginPreset.horizontalDp
                val verticalMargin = config.marginPreset.verticalDp
                val effectiveFontSize = config.effectiveFontSizeSp().sp
                val effectiveLineHeight = config.effectiveLineHeightSp().sp
                val fontFamily = config.fontFamilyChoice.toComposeFontFamily()

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(if (einkPolicy.isActive) 0.dp else 8.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                    border =
                        BorderStroke(
                            width = if (einkPolicy.isActive) 2.dp else 1.dp,
                            color = colors.outline,
                        ),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = horizontalMargin,
                                    vertical = verticalMargin,
                                ),
                    ) {
                        Text(
                            text = stringResource(R.string.sample_preview_body),
                            fontSize = effectiveFontSize,
                            lineHeight = effectiveLineHeight,
                            fontFamily = fontFamily,
                            color = colors.onSurface,
                        )
                    }
                }
            }

            // Action: Reset
            OutlinedButton(
                onClick = { onConfigChange(ReaderDisplayConfig.DEFAULT) },
                modifier = Modifier.fillMaxWidth(),
                border =
                    BorderStroke(
                        width = if (einkPolicy.isActive) 2.dp else 1.dp,
                        color = colors.outline,
                    ),
            ) {
                Text(
                    text = stringResource(R.string.action_reset),
                    style = typography.labelLarge,
                    color = colors.onSurface,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PreferenceToggleRow(
    title: String,
    description: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
        ) {
            Text(
                text = title,
                style = typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Text(
                text = description,
                style = typography.caption,
                color = colors.onSurfaceVariant,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ThemeOptionButton(
                label = stringResource(R.string.setting_toggle_enabled),
                isSelected = enabled,
                onClick = { onToggle(true) },
            )
            ThemeOptionButton(
                label = stringResource(R.string.setting_toggle_disabled),
                isSelected = !enabled,
                onClick = { onToggle(false) },
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography
    val einkPolicy = MixuTheme.einkPolicy

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (einkPolicy.isActive) 0.dp else 12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
        border =
            BorderStroke(
                width = if (einkPolicy.isActive) 2.dp else 1.dp,
                color = colors.outlineVariant,
            ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun ThemeOptionButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography
    val einkPolicy = MixuTheme.einkPolicy

    val strokeWidth = if (einkPolicy.isActive) 2.dp else 1.dp
    val shape = RoundedCornerShape(if (einkPolicy.isActive) 0.dp else 8.dp)

    if (isSelected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary,
                ),
            border = BorderStroke(strokeWidth, colors.outline),
        ) {
            Text(
                text = label,
                style = typography.labelMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            border = BorderStroke(strokeWidth, colors.outlineVariant),
            colors =
                ButtonDefaults.outlinedButtonColors(
                    contentColor = colors.onSurface,
                ),
        ) {
            Text(
                text = label,
                style = typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EpistemicMarkerCard(
    status: EpistemicStatus,
    title: String,
    description: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography
    val einkPolicy = MixuTheme.einkPolicy
    val strokeStyle = remember(status, einkPolicy) { einkPolicy.getEpistemicStroke(status) }

    val effectiveColor = if (einkPolicy.isActive) colors.onSurface else color
    val borderModifier =
        Modifier.drawBehind {
            val strokePx = strokeStyle.strokeWidthDp.toPx()
            val pathEffect =
                when (strokeStyle.pattern) {
                    StrokePattern.SOLID -> null
                    StrokePattern.DASHED -> PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                    StrokePattern.DOTTED -> PathEffect.dashPathEffect(floatArrayOf(4f, 6f), 0f)
                    StrokePattern.DOUBLE -> null
                }

            if (strokeStyle.pattern == StrokePattern.DOUBLE) {
                // Outer stroke
                drawRect(
                    color = effectiveColor,
                    topLeft = Offset(0f, 0f),
                    size = size,
                    style = Stroke(width = strokePx / 2f),
                )
                // Inner stroke
                drawRect(
                    color = effectiveColor,
                    topLeft = Offset(strokePx, strokePx),
                    size =
                        size.copy(
                            width = size.width - 2 * strokePx,
                            height = size.height - 2 * strokePx,
                        ),
                    style = Stroke(width = strokePx / 2f),
                )
            } else {
                drawRect(
                    color = effectiveColor,
                    topLeft = Offset.Zero,
                    size = size,
                    style =
                        Stroke(
                            width = strokePx,
                            pathEffect = pathEffect,
                        ),
                )
            }
        }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .then(borderModifier)
                .background(colors.surface)
                .padding(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = strokeStyle.symbolBadge,
                        style = typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = effectiveColor,
                    )
                    Text(
                        text = title,
                        style = typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                }
                Text(
                    text = description,
                    style = typography.caption,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}
