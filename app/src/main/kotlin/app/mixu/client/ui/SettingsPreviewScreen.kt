// SPDX-License-Identifier: MPL-2.0
@file:Suppress("ktlint:standard:function-naming")

package app.mixu.client.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mixu.client.R
import app.mixu.client.auth.UserSession
import app.mixu.client.billing.ClientCreditWallet
import app.mixu.client.billing.ClientStorageStatus
import app.mixu.client.billing.StorageQuotaState
import app.mixu.client.config.DistributionChannel
import app.mixu.client.config.FontFamilyChoice
import app.mixu.client.config.GraphPhysicsMode
import app.mixu.client.config.LookupProviderMode
import app.mixu.client.config.MarginPreset
import app.mixu.client.config.PageColumnMode
import app.mixu.client.config.ReaderDisplayConfig
import app.mixu.client.config.ReaderPreset
import app.mixu.client.config.ReadingLayoutMode
import app.mixu.client.eink.EInkMode
import app.mixu.client.network.MixuApiClient
import app.mixu.client.theme.MixuTheme
import app.mixu.client.theme.MixuThemeMode
import app.mixu.graph.EpistemicStatus
import kotlin.math.roundToInt

private const val REPO_URL_MIXU = "https://github.com/mixu-app/mixu"
private const val REPO_URL_CLIENT = "https://github.com/mixu-app/mixu-client"
private const val REPO_URL_ENGINE = "https://github.com/mixu-app/mixu-graph"
private const val REPO_URL_RESEARCH = "https://github.com/mixu-app/mixu-research"
private const val REPO_URL_ISSUES = "https://github.com/mixu-app/mixu-client/issues"
private const val APP_VERSION_NAME = "v0.0.1 (KMP · Compose)"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPreviewScreen(
    config: ReaderDisplayConfig,
    onConfigChange: (ReaderDisplayConfig) -> Unit,
    modifier: Modifier = Modifier,
    userSession: UserSession = UserSession.DEFAULT,
    onSessionChange: (UserSession) -> Unit = {},
    apiClient: MixuApiClient =
        remember(config.selfHostedApiEnabled, config.selfHostedApiUrl) {
            MixuApiClient.createProductionClient(config)
        },
    onNavigateBack: (() -> Unit)? = null,
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography
    val einkPolicy = MixuTheme.einkPolicy
    val uriHandler = LocalUriHandler.current
    val scrollState = rememberScrollState()

    var showEpistemicLegend by remember { mutableStateOf(false) }
    var showLicenseDialog by remember { mutableStateOf(false) }
    var showSignInDialog by remember { mutableStateOf(false) }
    var signedInAccount by remember(userSession) {
        mutableStateOf(if (userSession.isAuthenticated) userSession.displayName else null)
    }
    var accountDraft by remember { mutableStateOf("reader@mixuread.com") }
    var otpDraft by remember { mutableStateOf("") }
    var authStatusMessage by remember { mutableStateOf<String?>(null) }
    var isSendingOtp by remember { mutableStateOf(false) }
    var wallet by remember { mutableStateOf(ClientCreditWallet()) }
    var storageState by remember { mutableStateOf(StorageQuotaState()) }
    var showEInkQrAdDialog by remember { mutableStateOf(false) }
    val isEInkActive = einkPolicy.isActive || config.eInkMode == EInkMode.FORCE_ON

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
                        TextButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.padding(start = 4.dp),
                        ) {
                            Text(
                                text = "← " + stringResource(R.string.library_switch_to_library),
                                style = typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.primary,
                            )
                        }
                    }
                },
                title = {
                    Text(
                        text =
                            stringResource(R.string.nav_more) +
                                " · " +
                                stringResource(R.string.nav_settings),
                        style = typography.titleLarge,
                        color = colors.onSurface,
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    TextButton(
                        onClick = { onConfigChange(ReaderDisplayConfig.DEFAULT) },
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.action_reset),
                            style = typography.labelMedium,
                            color = colors.onSurfaceVariant,
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
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = 760.dp)
                        .fillMaxWidth()
                        .verticalScroll(state = scrollState, flingBehavior = flingBehavior)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // =========================================================================
                // Group 0: Account, Sign-In & Cloud Sync (账号、登录与云端同步)
                // =========================================================================
                SettingsGroupCard(title = stringResource(R.string.account_heading)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).padding(end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text =
                                    signedInAccount
                                        ?: stringResource(R.string.account_guest_title),
                                style = typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface,
                            )
                            Text(
                                text =
                                    if (signedInAccount != null) {
                                        stringResource(R.string.more_account_signed_in_badge) +
                                            " · " +
                                            stringResource(R.string.library_tags_sync_badge)
                                    } else {
                                        stringResource(R.string.account_guest_badge) +
                                            " · " +
                                            userSession.anonymousId
                                    },
                                style = typography.caption,
                                color = colors.primary,
                            )
                        }
                        CompactSegmentPill(
                            label = stringResource(R.string.more_account_signin_btn),
                            isSelected = signedInAccount != null,
                            onClick = { showSignInDialog = true },
                        )
                    }
                    Text(
                        text = stringResource(R.string.account_guest_desc),
                        style = typography.caption,
                        color = colors.onSurfaceVariant,
                    )

                    SettingsDivider()

                    // Three-Bucket Credit Wallet & E-Ink Cross-Device QR Ad Reward (ADR-030)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).padding(end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.wallet_title, wallet.totalCredits),
                                style = typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface,
                            )
                            Text(
                                text =
                                    stringResource(
                                        R.string.wallet_buckets_summary,
                                        wallet.promoCredits,
                                        wallet.subCredits,
                                        wallet.paidCredits,
                                    ),
                                style = typography.caption,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        CompactSegmentPill(
                            label =
                                if (isEInkActive) {
                                    stringResource(R.string.wallet_ad_reward_eink_btn)
                                } else {
                                    stringResource(R.string.wallet_ad_reward_btn)
                                },
                            isSelected = false,
                            onClick = {
                                if (isEInkActive) {
                                    showEInkQrAdDialog = true
                                } else {
                                    wallet = wallet.claimRewardedAd()
                                }
                            },
                        )
                    }

                    SettingsDivider()

                    // Cloud Storage Quota & 7-Day Grace Period (ADR-030)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).padding(end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text =
                                    stringResource(
                                        R.string.storage_quota_label,
                                        storageState.usedMb,
                                        storageState.quotaMb,
                                    ),
                                style = typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface,
                            )
                            Text(
                                text =
                                    if (storageState.status == ClientStorageStatus.GRACE_PERIOD) {
                                        stringResource(R.string.storage_status_grace_desc)
                                    } else {
                                        stringResource(R.string.storage_status_active_desc)
                                    },
                                style = typography.caption,
                                color =
                                    if (storageState.status == ClientStorageStatus.GRACE_PERIOD) {
                                        colors.claimed
                                    } else {
                                        colors.onSurfaceVariant
                                    },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CompactSegmentPill(
                                label = stringResource(R.string.storage_status_toggle_active),
                                isSelected = storageState.status == ClientStorageStatus.ACTIVE,
                                onClick = {
                                    storageState =
                                        storageState.copy(status = ClientStorageStatus.ACTIVE)
                                },
                            )
                            CompactSegmentPill(
                                label = stringResource(R.string.storage_status_toggle_grace),
                                isSelected =
                                    storageState.status == ClientStorageStatus.GRACE_PERIOD,
                                onClick = {
                                    storageState =
                                        storageState.copy(
                                            status = ClientStorageStatus.GRACE_PERIOD,
                                        )
                                },
                            )
                        }
                    }
                }

                // =========================================================================
                // Group 1: Appearance & Reading (外观与排版)
                // =========================================================================
                SettingsGroupCard(title = stringResource(R.string.settings_group_appearance)) {
                    // 1. Curated Presets Row
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.preset_heading),
                            style = typography.labelMedium,
                            color = colors.onSurfaceVariant,
                        )
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CompactSegmentPill(
                                label = stringResource(R.string.preset_kindle),
                                isSelected = config.activePreset == ReaderPreset.KINDLE_CLASSIC,
                                onClick = {
                                    onConfigChange(config.applyPreset(ReaderPreset.KINDLE_CLASSIC))
                                },
                            )
                            CompactSegmentPill(
                                label = stringResource(R.string.preset_readest),
                                isSelected = config.activePreset == ReaderPreset.READEST_MODERN,
                                onClick = {
                                    onConfigChange(config.applyPreset(ReaderPreset.READEST_MODERN))
                                },
                            )
                            CompactSegmentPill(
                                label = stringResource(R.string.preset_koodo),
                                isSelected = config.activePreset == ReaderPreset.KOODO_EYECARE,
                                onClick = {
                                    onConfigChange(config.applyPreset(ReaderPreset.KOODO_EYECARE))
                                },
                            )
                            CompactSegmentPill(
                                label = stringResource(R.string.preset_night),
                                isSelected = config.activePreset == ReaderPreset.NIGHT_DETECTIVE,
                                onClick = {
                                    onConfigChange(config.applyPreset(ReaderPreset.NIGHT_DETECTIVE))
                                },
                            )
                            CompactSegmentPill(
                                label = stringResource(R.string.preset_eink),
                                isSelected = config.activePreset == ReaderPreset.EINK_CRISP,
                                onClick = {
                                    onConfigChange(config.applyPreset(ReaderPreset.EINK_CRISP))
                                },
                            )
                        }
                    }

                    SettingsDivider()

                    // 2. Color Theme Swatches
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.theme_label),
                            style = typography.labelMedium,
                            color = colors.onSurfaceVariant,
                        )
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ThemeSwatchChip(
                                label = stringResource(R.string.theme_light),
                                bg = Color(0xFFFAF8F5),
                                fg = Color(0xFF1A1917),
                                isSelected = config.themeMode == MixuThemeMode.LIGHT,
                                onClick = {
                                    onConfigChange(config.copy(themeMode = MixuThemeMode.LIGHT))
                                },
                            )
                            ThemeSwatchChip(
                                label = stringResource(R.string.theme_sepia),
                                bg = Color(0xFFF4ECD8),
                                fg = Color(0xFF3B3228),
                                isSelected = config.themeMode == MixuThemeMode.SEPIA,
                                onClick = {
                                    onConfigChange(config.copy(themeMode = MixuThemeMode.SEPIA))
                                },
                            )
                            ThemeSwatchChip(
                                label = stringResource(R.string.theme_mint),
                                bg = Color(0xFFE4F0E6),
                                fg = Color(0xFF1E2D22),
                                isSelected = config.themeMode == MixuThemeMode.MINT,
                                onClick = {
                                    onConfigChange(config.copy(themeMode = MixuThemeMode.MINT))
                                },
                            )
                            ThemeSwatchChip(
                                label = stringResource(R.string.theme_solarized),
                                bg = Color(0xFFFDF6E3),
                                fg = Color(0xFF334248),
                                isSelected = config.themeMode == MixuThemeMode.SOLARIZED,
                                onClick = {
                                    onConfigChange(config.copy(themeMode = MixuThemeMode.SOLARIZED))
                                },
                            )
                            ThemeSwatchChip(
                                label = stringResource(R.string.theme_dark),
                                bg = Color(0xFF161618),
                                fg = Color(0xFFE6E1E5),
                                isSelected = config.themeMode == MixuThemeMode.DARK,
                                onClick = {
                                    onConfigChange(config.copy(themeMode = MixuThemeMode.DARK))
                                },
                            )
                            ThemeSwatchChip(
                                label = stringResource(R.string.theme_nord),
                                bg = Color(0xFF1E2430),
                                fg = Color(0xFFD8DEE9),
                                isSelected = config.themeMode == MixuThemeMode.NORD_DARK,
                                onClick = {
                                    onConfigChange(config.copy(themeMode = MixuThemeMode.NORD_DARK))
                                },
                            )
                            ThemeSwatchChip(
                                label = stringResource(R.string.theme_eink),
                                bg = Color(0xFFFFFFFF),
                                fg = Color(0xFF000000),
                                isSelected = config.themeMode == MixuThemeMode.EINK,
                                onClick = {
                                    onConfigChange(config.copy(themeMode = MixuThemeMode.EINK))
                                },
                            )
                        }
                    }

                    SettingsDivider()

                    // 3. Font Size Stepper Row
                    SettingsInlineRow(label = stringResource(R.string.font_size_label)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CompactSegmentPill(
                                label = stringResource(R.string.reader_font_smaller),
                                isSelected = false,
                                onClick = {
                                    val next = (config.fontSizeSp - 1f).coerceIn(12f, 36f)
                                    onConfigChange(
                                        config.copy(
                                            fontSizeSp = next,
                                            fontScaleMultiplier = 1.0f,
                                        ),
                                    )
                                },
                            )
                            Text(
                                text = "${config.effectiveFontSizeSp().roundToInt()} sp",
                                style = typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface,
                            )
                            CompactSegmentPill(
                                label = stringResource(R.string.reader_font_larger),
                                isSelected = false,
                                onClick = {
                                    val next = (config.fontSizeSp + 1f).coerceIn(12f, 36f)
                                    onConfigChange(
                                        config.copy(
                                            fontSizeSp = next,
                                            fontScaleMultiplier = 1.0f,
                                        ),
                                    )
                                },
                            )
                        }
                    }

                    SettingsDivider()

                    // 4. Line Height Inline Selector
                    SettingsInlineRow(label = stringResource(R.string.line_height_label)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(1.3f, 1.5f, 1.7f, 1.9f).forEach { lh ->
                                val selected =
                                    kotlin.math.abs(config.lineHeightMultiplier - lh) < 0.09f
                                CompactSegmentPill(
                                    label = "${lh}x",
                                    isSelected = selected,
                                    onClick = {
                                        onConfigChange(config.copy(lineHeightMultiplier = lh))
                                    },
                                )
                            }
                        }
                    }

                    SettingsDivider()

                    // 5. Font Family Inline Selector
                    SettingsInlineRow(label = stringResource(R.string.font_family_label)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CompactSegmentPill(
                                label = stringResource(R.string.font_family_serif),
                                isSelected = config.fontFamilyChoice == FontFamilyChoice.SERIF,
                                onClick = {
                                    onConfigChange(
                                        config.copy(fontFamilyChoice = FontFamilyChoice.SERIF),
                                    )
                                },
                            )
                            CompactSegmentPill(
                                label = stringResource(R.string.font_family_sans),
                                isSelected = config.fontFamilyChoice == FontFamilyChoice.SANS_SERIF,
                                onClick = {
                                    onConfigChange(
                                        config.copy(fontFamilyChoice = FontFamilyChoice.SANS_SERIF),
                                    )
                                },
                            )
                            CompactSegmentPill(
                                label = stringResource(R.string.font_family_mono),
                                isSelected = config.fontFamilyChoice == FontFamilyChoice.MONOSPACE,
                                onClick = {
                                    onConfigChange(
                                        config.copy(fontFamilyChoice = FontFamilyChoice.MONOSPACE),
                                    )
                                },
                            )
                        }
                    }

                    SettingsDivider()

                    // 6. Multi-Column Spread Inline Selector
                    SettingsInlineRow(label = stringResource(R.string.column_mode_label)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CompactSegmentPill(
                                label = stringResource(R.string.column_mode_auto),
                                isSelected = config.pageColumnMode == PageColumnMode.AUTO,
                                onClick = {
                                    onConfigChange(
                                        config.copy(pageColumnMode = PageColumnMode.AUTO),
                                    )
                                },
                            )
                            CompactSegmentPill(
                                label = stringResource(R.string.column_mode_single),
                                isSelected = config.pageColumnMode == PageColumnMode.SINGLE,
                                onClick = {
                                    onConfigChange(
                                        config.copy(pageColumnMode = PageColumnMode.SINGLE),
                                    )
                                },
                            )
                            CompactSegmentPill(
                                label = stringResource(R.string.column_mode_double),
                                isSelected = config.pageColumnMode == PageColumnMode.DOUBLE,
                                onClick = {
                                    onConfigChange(
                                        config.copy(pageColumnMode = PageColumnMode.DOUBLE),
                                    )
                                },
                            )
                        }
                    }

                    SettingsDivider()

                    // 7. Page Margins Inline Selector
                    SettingsInlineRow(label = stringResource(R.string.margin_label)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CompactSegmentPill(
                                label = stringResource(R.string.margin_compact),
                                isSelected = config.marginPreset == MarginPreset.COMPACT,
                                onClick = {
                                    onConfigChange(config.copy(marginPreset = MarginPreset.COMPACT))
                                },
                            )
                            CompactSegmentPill(
                                label = stringResource(R.string.margin_normal),
                                isSelected = config.marginPreset == MarginPreset.NORMAL,
                                onClick = {
                                    onConfigChange(config.copy(marginPreset = MarginPreset.NORMAL))
                                },
                            )
                            CompactSegmentPill(
                                label = stringResource(R.string.margin_wide),
                                isSelected = config.marginPreset == MarginPreset.WIDE,
                                onClick = {
                                    onConfigChange(config.copy(marginPreset = MarginPreset.WIDE))
                                },
                            )
                        }
                    }

                    SettingsDivider()

                    // 8. Reading Mode (Paged vs Continuous)
                    SettingsInlineRow(label = stringResource(R.string.reader_layout_mode_label)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CompactSegmentPill(
                                label = stringResource(R.string.reader_layout_paged),
                                isSelected = config.readingLayoutMode == ReadingLayoutMode.PAGED,
                                onClick = {
                                    onConfigChange(
                                        config.copy(readingLayoutMode = ReadingLayoutMode.PAGED),
                                    )
                                },
                            )
                            CompactSegmentPill(
                                label = stringResource(R.string.reader_layout_continuous),
                                isSelected =
                                    config.readingLayoutMode == ReadingLayoutMode.CONTINUOUS,
                                onClick = {
                                    onConfigChange(
                                        config.copy(
                                            readingLayoutMode = ReadingLayoutMode.CONTINUOUS,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }

                // =========================================================================
                // Group 2: Reading Interaction & Device (阅读交互与设备)
                // =========================================================================
                SettingsGroupCard(title = stringResource(R.string.settings_group_interaction)) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.reader_immersive_label),
                        description = stringResource(R.string.reader_immersive_desc),
                        checked = config.immersiveReaderMode,
                        onCheckedChange = {
                            onConfigChange(config.copy(immersiveReaderMode = it))
                        },
                    )

                    SettingsDivider()

                    SettingsSwitchRow(
                        title = stringResource(R.string.reading_volume_key_turn),
                        description = stringResource(R.string.reading_volume_key_turn_desc),
                        checked = config.volumeKeyPageTurn,
                        onCheckedChange = {
                            onConfigChange(config.copy(volumeKeyPageTurn = it))
                        },
                    )

                    SettingsDivider()

                    SettingsSwitchRow(
                        title = stringResource(R.string.reading_keyboard_turn),
                        description = stringResource(R.string.reading_keyboard_turn_desc),
                        checked = config.keyboardPageTurn,
                        onCheckedChange = {
                            onConfigChange(config.copy(keyboardPageTurn = it))
                        },
                    )

                    SettingsDivider()

                    SettingsSwitchRow(
                        title = stringResource(R.string.reading_keep_screen_on),
                        description = stringResource(R.string.reading_keep_screen_on_desc),
                        checked = config.keepScreenOn,
                        onCheckedChange = {
                            onConfigChange(config.copy(keepScreenOn = it))
                        },
                    )

                    SettingsDivider()

                    SettingsSwitchRow(
                        title = stringResource(R.string.reading_auto_bookmark),
                        description = stringResource(R.string.reading_auto_bookmark_desc),
                        checked = config.autoBookmark,
                        onCheckedChange = {
                            onConfigChange(config.copy(autoBookmark = it))
                        },
                    )

                    SettingsDivider()

                    SettingsInlineRow(label = stringResource(R.string.eink_mode_label)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CompactSegmentPill(
                                label = stringResource(R.string.eink_mode_auto),
                                isSelected = config.eInkMode == EInkMode.AUTO,
                                onClick = {
                                    onConfigChange(config.copy(eInkMode = EInkMode.AUTO))
                                },
                            )
                            CompactSegmentPill(
                                label = stringResource(R.string.eink_mode_off),
                                isSelected = config.eInkMode == EInkMode.OFF,
                                onClick = {
                                    onConfigChange(config.copy(eInkMode = EInkMode.OFF))
                                },
                            )
                            CompactSegmentPill(
                                label = stringResource(R.string.eink_mode_force_on),
                                isSelected = config.eInkMode == EInkMode.FORCE_ON,
                                onClick = {
                                    onConfigChange(config.copy(eInkMode = EInkMode.FORCE_ON))
                                },
                            )
                        }
                    }

                    if (einkPolicy.isActive || config.eInkMode == EInkMode.FORCE_ON) {
                        SettingsDivider()
                        SettingsInlineRow(
                            label = stringResource(R.string.eink_refresh_interval_label),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(5, 10, 20, 0).forEach { interval ->
                                    val text =
                                        if (interval == 0) {
                                            stringResource(R.string.eink_refresh_manual_only)
                                        } else {
                                            stringResource(
                                                R.string.eink_refresh_interval_pages,
                                                interval,
                                            )
                                        }
                                    CompactSegmentPill(
                                        label = text,
                                        isSelected = config.eInkFullRefreshInterval == interval,
                                        onClick = {
                                            onConfigChange(
                                                config.copy(eInkFullRefreshInterval = interval),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }

                    SettingsDivider()

                    SettingsInlineRow(label = stringResource(R.string.caseboard_physics_label)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CompactSegmentPill(
                                label = stringResource(R.string.caseboard_physics_spring),
                                isSelected =
                                    config.graphPhysicsMode == GraphPhysicsMode.LIVE_SPRING,
                                onClick = {
                                    onConfigChange(
                                        config.copy(
                                            graphPhysicsMode = GraphPhysicsMode.LIVE_SPRING,
                                        ),
                                    )
                                },
                            )
                            CompactSegmentPill(
                                label = stringResource(R.string.caseboard_physics_gravity),
                                isSelected =
                                    config.graphPhysicsMode == GraphPhysicsMode.STRONG_GRAVITY,
                                onClick = {
                                    onConfigChange(
                                        config.copy(
                                            graphPhysicsMode = GraphPhysicsMode.STRONG_GRAVITY,
                                        ),
                                    )
                                },
                            )
                            CompactSegmentPill(
                                label = stringResource(R.string.caseboard_physics_pin),
                                isSelected = config.graphPhysicsMode == GraphPhysicsMode.PIN_NODE,
                                onClick = {
                                    onConfigChange(
                                        config.copy(graphPhysicsMode = GraphPhysicsMode.PIN_NODE),
                                    )
                                },
                            )
                        }
                    }
                }

                // =========================================================================
                // Group 3: Dictionary, Translation & Custom Server (查词翻译与自建服务)
                // =========================================================================
                SettingsGroupCard(title = stringResource(R.string.settings_group_services)) {
                    SettingsInlineRow(label = stringResource(R.string.reader_lookup_button)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CompactSegmentPill(
                                label = stringResource(R.string.lookup_mode_hybrid),
                                isSelected =
                                    config.lookupProviderMode == LookupProviderMode.HYBRID_AUTO,
                                onClick = {
                                    onConfigChange(
                                        config.copy(
                                            lookupProviderMode = LookupProviderMode.HYBRID_AUTO,
                                        ),
                                    )
                                },
                            )
                            CompactSegmentPill(
                                label = stringResource(R.string.lookup_mode_offline),
                                isSelected =
                                    config.lookupProviderMode == LookupProviderMode.OFFLINE_LOCAL,
                                onClick = {
                                    onConfigChange(
                                        config.copy(
                                            lookupProviderMode = LookupProviderMode.OFFLINE_LOCAL,
                                        ),
                                    )
                                },
                            )
                            CompactSegmentPill(
                                label = stringResource(R.string.lookup_mode_online),
                                isSelected =
                                    config.lookupProviderMode == LookupProviderMode.ONLINE_API,
                                onClick = {
                                    onConfigChange(
                                        config.copy(
                                            lookupProviderMode = LookupProviderMode.ONLINE_API,
                                        ),
                                    )
                                },
                            )
                        }
                    }

                    if (DistributionChannel.CURRENT.allowsCustomServerEndpoint) {
                        SettingsDivider()

                        SettingsSwitchRow(
                            title = stringResource(R.string.self_hosted_enable_label),
                            description = stringResource(R.string.self_hosted_enable_desc),
                            checked = config.selfHostedApiEnabled,
                            onCheckedChange = { enabled ->
                                onConfigChange(config.copy(selfHostedApiEnabled = enabled))
                            },
                        )

                        if (config.selfHostedApiEnabled) {
                            SettingsDivider()

                            SettingsSwitchRow(
                                title = stringResource(R.string.self_hosted_stateless_label),
                                description = stringResource(R.string.self_hosted_stateless_desc),
                                checked = config.selfHostedStatelessMode,
                                onCheckedChange = { stateless ->
                                    onConfigChange(config.copy(selfHostedStatelessMode = stateless))
                                },
                            )

                            OutlinedTextField(
                                value = config.selfHostedApiUrl,
                                onValueChange = { url ->
                                    onConfigChange(config.copy(selfHostedApiUrl = url))
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                singleLine = true,
                                label = {
                                    Text(
                                        text = stringResource(R.string.self_hosted_url_label),
                                        style = typography.caption,
                                    )
                                },
                            )
                        }
                    }
                }

                // =========================================================================
                // Group 4: Help & Quick Guide (使用帮助与指南)
                // =========================================================================
                SettingsGroupCard(title = stringResource(R.string.settings_group_help)) {
                    HelpGuideItem(
                        title = stringResource(R.string.help_guide_reader_title),
                        description = stringResource(R.string.help_guide_reader_desc),
                    )

                    SettingsDivider()

                    HelpGuideItem(
                        title = stringResource(R.string.help_guide_spoiler_title),
                        description = stringResource(R.string.help_guide_spoiler_desc),
                    )

                    SettingsDivider()

                    HelpGuideItem(
                        title = stringResource(R.string.help_guide_graph_title),
                        description = stringResource(R.string.help_guide_graph_desc),
                    )

                    SettingsDivider()

                    // Collapsible Epistemic Status Legend (collapsed by default)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { showEpistemicLegend = !showEpistemicLegend }
                                .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.help_epistemic_toggle),
                            style = typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = colors.onSurface,
                        )
                        Text(
                            text = if (showEpistemicLegend) "▴" else "▾",
                            style = typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }

                    if (showEpistemicLegend) {
                        Column(
                            modifier = Modifier.padding(top = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CompactEpistemicLegendRow(
                                status = EpistemicStatus.OBSERVED,
                                title = stringResource(R.string.epistemic_observed),
                                desc = stringResource(R.string.epistemic_observed_desc),
                                color = colors.observed,
                            )
                            CompactEpistemicLegendRow(
                                status = EpistemicStatus.CORROBORATED,
                                title = stringResource(R.string.epistemic_corroborated),
                                desc = stringResource(R.string.epistemic_corroborated_desc),
                                color = colors.corroborated,
                            )
                            CompactEpistemicLegendRow(
                                status = EpistemicStatus.CLAIMED,
                                title = stringResource(R.string.epistemic_claimed),
                                desc = stringResource(R.string.epistemic_claimed_desc),
                                color = colors.claimed,
                            )
                            CompactEpistemicLegendRow(
                                status = EpistemicStatus.INFERRED,
                                title = stringResource(R.string.epistemic_inferred),
                                desc = stringResource(R.string.epistemic_inferred_desc),
                                color = colors.inferred,
                            )
                            CompactEpistemicLegendRow(
                                status = EpistemicStatus.CONTRADICTED,
                                title = stringResource(R.string.epistemic_contradicted),
                                desc = stringResource(R.string.epistemic_contradicted_desc),
                                color = colors.contradicted,
                            )
                            CompactEpistemicLegendRow(
                                status = EpistemicStatus.RETRACTED,
                                title = stringResource(R.string.epistemic_retracted),
                                desc = stringResource(R.string.epistemic_retracted_desc),
                                color = colors.retracted,
                            )
                            CompactEpistemicLegendRow(
                                status = EpistemicStatus.UNRESOLVED,
                                title = stringResource(R.string.epistemic_unresolved),
                                desc = stringResource(R.string.epistemic_unresolved_desc),
                                color = colors.unresolved,
                            )
                        }
                    }
                }

                // =========================================================================
                // Group 5: About Mixu & Source Code Repositories (关于 谜序 Mixu)
                // =========================================================================
                SettingsGroupCard(title = stringResource(R.string.settings_group_about)) {
                    // Brand Identity Header
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.app_name) + " · Mixu",
                                style = typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface,
                            )
                            Text(
                                text = APP_VERSION_NAME,
                                style = typography.caption,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = stringResource(R.string.app_tagline),
                            style = typography.caption,
                            color = colors.onSurfaceVariant,
                        )
                        Text(
                            text =
                                stringResource(R.string.account_guest_title) +
                                    " · " +
                                    userSession.anonymousId,
                            style = typography.caption,
                            color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                        )
                    }

                    SettingsDivider()

                    // Source Code: Public Entry Point (mixu)
                    AboutLinkRow(
                        title = stringResource(R.string.about_source_mixu_title),
                        subtitle = stringResource(R.string.about_source_mixu_desc),
                        trailingSymbol = "↗",
                        onClick = { runCatching { uriHandler.openUri(REPO_URL_MIXU) } },
                    )

                    SettingsDivider()

                    // Source Code: Client Repository (mixu-client)
                    AboutLinkRow(
                        title = stringResource(R.string.about_source_client_title),
                        subtitle = stringResource(R.string.about_source_client_desc),
                        trailingSymbol = "↗",
                        onClick = { runCatching { uriHandler.openUri(REPO_URL_CLIENT) } },
                    )

                    SettingsDivider()

                    // Source Code: Core Graph & Spoiler Engine
                    AboutLinkRow(
                        title = stringResource(R.string.about_source_engine_title),
                        subtitle = stringResource(R.string.about_source_engine_desc),
                        trailingSymbol = "↗",
                        onClick = { runCatching { uriHandler.openUri(REPO_URL_ENGINE) } },
                    )

                    SettingsDivider()

                    // Source Code: Research & Synthetic Corpus
                    AboutLinkRow(
                        title = stringResource(R.string.about_source_research_title),
                        subtitle = stringResource(R.string.about_source_research_desc),
                        trailingSymbol = "↗",
                        onClick = { runCatching { uriHandler.openUri(REPO_URL_RESEARCH) } },
                    )

                    SettingsDivider()

                    // Issue Tracker & Feedback
                    AboutLinkRow(
                        title = stringResource(R.string.about_issue_tracker_title),
                        subtitle = stringResource(R.string.about_issue_tracker_desc),
                        trailingSymbol = "↗",
                        onClick = { runCatching { uriHandler.openUri(REPO_URL_ISSUES) } },
                    )

                    SettingsDivider()

                    // Open-Source Licenses & Trademark Policy Dialog
                    AboutLinkRow(
                        title = stringResource(R.string.about_license_title),
                        subtitle = stringResource(R.string.about_license_desc),
                        trailingSymbol = "›",
                        onClick = { showLicenseDialog = true },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showLicenseDialog) {
        AlertDialog(
            onDismissRequest = { showLicenseDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.about_license_title),
                    style = typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.about_license_dialog_body),
                    style = typography.bodyMedium,
                    color = colors.onSurface,
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicenseDialog = false }) {
                    Text(stringResource(R.string.settings_reset))
                }
            },
        )
    }

    if (showSignInDialog) {
        AlertDialog(
            onDismissRequest = { showSignInDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.more_account_signin_dialog_title),
                    style = typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.library_collections_sync_hint),
                        style = typography.caption,
                        color = colors.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = accountDraft,
                        onValueChange = { accountDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = {
                            Text(
                                text = stringResource(R.string.more_account_email_label),
                                style = typography.caption,
                            )
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = otpDraft,
                            onValueChange = { otpDraft = it.filter { ch -> ch.isDigit() }.take(6) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = {
                                Text(
                                    text = "6 位邮箱验证码 (OTP)",
                                    style = typography.caption,
                                )
                            },
                        )
                        OutlinedButton(
                            onClick = {
                                val targetEmail = accountDraft.trim()
                                if (targetEmail.isNotBlank() && !isSendingOtp) {
                                    isSendingOtp = true
                                    authStatusMessage = "正在请求云端发送验证码…"
                                    Thread {
                                        val res = apiClient.sendEmailOtp(targetEmail)
                                        Handler(Looper.getMainLooper()).post {
                                            isSendingOtp = false
                                            authStatusMessage =
                                                if (!res.offlineFallback) {
                                                    "✓ 验证码已发送至 $targetEmail（发件人 no-reply@mail.mixuread.com）"
                                                } else {
                                                    "当前处于离线模式，可直接点击下方确认切换本地账号"
                                                }
                                        }
                                    }.start()
                                }
                            },
                        ) {
                            Text(
                                text = if (isSendingOtp) "发送中…" else "发送验证码",
                                style = typography.caption,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    if (!authStatusMessage.isNullOrBlank()) {
                        Text(
                            text = authStatusMessage.orEmpty(),
                            style = typography.caption,
                            color = colors.primary,
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        signedInAccount = null
                        onSessionChange(UserSession.DEFAULT)
                        showSignInDialog = false
                    },
                ) {
                    Text(stringResource(R.string.more_account_switch_guest))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmedEmail = accountDraft.trim()
                        val trimmedOtp = otpDraft.trim()
                        if (trimmedEmail.isNotEmpty() && trimmedOtp.length == 6) {
                            authStatusMessage = "正在校验邮箱验证码…"
                            Thread {
                                val verified = apiClient.verifyEmailOtp(trimmedEmail, trimmedOtp)
                                val fetchedWallet =
                                    if (verified != null) {
                                        apiClient.fetchWallet(verified.userId)
                                    } else {
                                        null
                                    }
                                Handler(Looper.getMainLooper()).post {
                                    if (verified != null) {
                                        signedInAccount = verified.email
                                        onSessionChange(
                                            UserSession.createAuthenticated(
                                                userId = verified.userId,
                                                displayName = verified.email,
                                            ),
                                        )
                                        if (fetchedWallet != null) {
                                            wallet = fetchedWallet
                                        }
                                        authStatusMessage = null
                                        showSignInDialog = false
                                    } else {
                                        authStatusMessage = "验证码无效或已过期，请重新获取"
                                    }
                                }
                            }.start()
                        } else if (trimmedEmail.isNotEmpty()) {
                            signedInAccount = trimmedEmail
                            onSessionChange(
                                UserSession.createAuthenticated(
                                    userId = trimmedEmail,
                                    displayName = trimmedEmail,
                                ),
                            )
                            showSignInDialog = false
                        } else {
                            showSignInDialog = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.more_account_signin_confirm))
                }
            },
        )
    }

    if (showEInkQrAdDialog) {
        val qrPayload =
            remember(userSession.anonymousId, signedInAccount) {
                wallet.generateEInkQrChallengePayload(
                    anonymousOrUserId = signedInAccount ?: userSession.anonymousId,
                )
            }
        val qrMatrix = remember(qrPayload) { wallet.generateQrMatrix(qrPayload) }

        AlertDialog(
            onDismissRequest = { showEInkQrAdDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.wallet_eink_qr_dialog_title),
                    style = typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(168.dp)
                                .background(Color.White)
                                .border(2.dp, Color.Black)
                                .padding(10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val dim = qrMatrix.size.coerceAtLeast(1)
                            val cellW = size.width / dim
                            val cellH = size.height / dim
                            for (r in 0 until dim) {
                                for (c in 0 until dim) {
                                    if (qrMatrix[r][c]) {
                                        drawRect(
                                            color = Color.Black,
                                            topLeft = Offset(c * cellW, r * cellH),
                                            size = Size(cellW + 0.5f, cellH + 0.5f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        text = qrPayload,
                        style = typography.caption.copy(fontFamily = FontFamily.Monospace),
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text =
                            stringResource(
                                R.string.wallet_eink_qr_dialog_desc,
                                wallet.dailyAdCreditsClaimed,
                            ),
                        style = typography.caption,
                        color = colors.onSurface,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showEInkQrAdDialog = false }) {
                    Text(stringResource(R.string.caseboard_hypothesis_cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        wallet = wallet.claimRewardedAd()
                        showEInkQrAdDialog = false
                    },
                ) {
                    Text(stringResource(R.string.wallet_eink_qr_confirm_btn))
                }
            },
        )
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography
    val einkPolicy = MixuTheme.einkPolicy

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(if (einkPolicy.isActive) 0.dp else 12.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            border =
                BorderStroke(
                    width = if (einkPolicy.isActive) 1.5.dp else 1.dp,
                    color =
                        colors.outlineVariant.copy(
                            alpha = if (einkPolicy.isActive) 1f else 0.6f,
                        ),
                ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsDivider() {
    val colors = MixuTheme.colors
    HorizontalDivider(
        thickness = 0.5.dp,
        color = colors.outlineVariant.copy(alpha = 0.5f),
    )
}

@Composable
private fun SettingsInlineRow(
    label: String,
    modifier: Modifier = Modifier,
    control: @Composable () -> Unit,
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography

    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colors.onSurface,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        control()
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(vertical = 2.dp),
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
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
            )
            Text(
                text = description,
                style = typography.caption,
                color = colors.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = colors.onPrimary,
                    checkedTrackColor = colors.primary,
                    uncheckedThumbColor = colors.onSurfaceVariant,
                    uncheckedTrackColor = colors.surfaceVariant,
                ),
        )
    }
}

@Composable
private fun CompactSegmentPill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography
    val einkPolicy = MixuTheme.einkPolicy
    val shape = RoundedCornerShape(if (einkPolicy.isActive) 0.dp else 8.dp)

    OutlinedButton(
        onClick = onClick,
        modifier = modifier.sizeIn(minWidth = 44.dp, minHeight = 32.dp),
        shape = shape,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        border =
            BorderStroke(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) colors.primary else colors.outlineVariant,
            ),
        colors =
            ButtonDefaults.outlinedButtonColors(
                containerColor =
                    if (isSelected) {
                        colors.primaryContainer
                    } else {
                        Color.Transparent
                    },
                contentColor =
                    if (isSelected) {
                        colors.onPrimaryContainer
                    } else {
                        colors.onSurface
                    },
            ),
    ) {
        Text(
            text = label,
            style = typography.caption,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun ThemeSwatchChip(
    label: String,
    bg: Color,
    fg: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography
    val einkPolicy = MixuTheme.einkPolicy
    val shape = RoundedCornerShape(if (einkPolicy.isActive) 0.dp else 18.dp)

    Row(
        modifier =
            Modifier
                .clip(shape)
                .background(bg, shape)
                .border(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) colors.primary else fg.copy(alpha = 0.28f),
                    shape = shape,
                ).clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(8.dp)
                    .background(
                        color = if (isSelected) colors.primary else fg.copy(alpha = 0.5f),
                        shape = CircleShape,
                    ),
        )
        Text(
            text = label,
            style = typography.caption,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
        )
    }
}

@Composable
private fun HelpGuideItem(
    title: String,
    description: String,
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colors.onSurface,
        )
        Text(
            text = description,
            style = typography.caption,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun CompactEpistemicLegendRow(
    status: EpistemicStatus,
    title: String,
    desc: String,
    color: Color,
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography
    val einkPolicy = MixuTheme.einkPolicy
    val strokeStyle = remember(status, einkPolicy) { einkPolicy.getEpistemicStroke(status) }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    color = colors.surfaceVariant.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(6.dp),
                ).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = strokeStyle.symbolBadge,
            style = typography.caption,
            fontWeight = FontWeight.Bold,
            color = if (einkPolicy.isActive) colors.onSurface else color,
        )
        Text(
            text = title,
            style = typography.caption,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
        )
        Text(
            text = "· $desc",
            style = typography.caption,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AboutLinkRow(
    title: String,
    subtitle: String,
    trailingSymbol: String,
    onClick: () -> Unit,
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
            )
            Text(
                text = subtitle,
                style = typography.caption,
                color = colors.onSurfaceVariant,
            )
        }
        Text(
            text = trailingSymbol,
            style = typography.titleMedium,
            color = colors.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
