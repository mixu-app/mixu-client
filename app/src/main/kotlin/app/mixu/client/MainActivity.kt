// SPDX-License-Identifier: MPL-2.0
package app.mixu.client

import android.app.Activity
import android.os.Bundle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mixu.client.auth.UserSession
import app.mixu.client.caseboard.CaseBoardScreen
import app.mixu.client.config.ReaderDisplayConfig
import app.mixu.client.eink.EInkDetector
import app.mixu.client.library.LibraryScreen
import app.mixu.client.theme.MixuTheme
import app.mixu.client.theme.createMixuTypography
import app.mixu.client.ui.SettingsPreviewScreen

/**
 * Top-level destination screens in the Mixu client shell.
 */
enum class MainScreenDestination {
    LIBRARY,
    CASE_BOARD,
    SETTINGS_PREVIEW,
}

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val composeView =
            ComposeView(this).apply {
                setContent {
                    var currentDestination by remember {
                        mutableStateOf(MainScreenDestination.LIBRARY)
                    }
                    var config by remember { mutableStateOf(ReaderDisplayConfig.DEFAULT) }
                    var userSession by remember { mutableStateOf(UserSession.DEFAULT) }

                    val einkPolicy =
                        remember(config.eInkMode, config.eInkFullRefreshInterval) {
                            EInkDetector.resolvePolicy(
                                userMode = config.eInkMode,
                                fullRefreshInterval = config.eInkFullRefreshInterval,
                            )
                        }
                    val typography =
                        remember(
                            config.fontFamilyChoice,
                            config.fontScaleMultiplier,
                            config.lineHeightMultiplier,
                        ) {
                            createMixuTypography(
                                fontFamily = config.fontFamilyChoice.toComposeFontFamily(),
                                scaleMultiplier = config.fontScaleMultiplier,
                                lineHeightMultiplier = config.lineHeightMultiplier,
                            )
                        }

                    MixuTheme(
                        themeMode = config.themeMode,
                        eInkPolicy = einkPolicy,
                        typography = typography,
                    ) {
                        val colors = MixuTheme.colors
                        val strokeWidth = if (einkPolicy.isActive) 2.5.dp else 1.dp

                        Scaffold(
                            bottomBar = {
                                NavigationBar(
                                    containerColor = colors.surface,
                                    contentColor = colors.primary,
                                    modifier =
                                        Modifier.border(
                                            BorderStroke(strokeWidth, colors.outline),
                                        ),
                                ) {
                                    NavigationBarItem(
                                        selected =
                                            currentDestination == MainScreenDestination.LIBRARY,
                                        onClick = {
                                            currentDestination =
                                                MainScreenDestination.LIBRARY
                                        },
                                        icon = {
                                            Text(
                                                text =
                                                    if (currentDestination ==
                                                        MainScreenDestination.LIBRARY
                                                    ) {
                                                        "[■]"
                                                    } else {
                                                        "[□]"
                                                    },
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        },
                                        label = { Text(stringResource(R.string.nav_library)) },
                                        colors =
                                            NavigationBarItemDefaults.colors(
                                                selectedIconColor = colors.primary,
                                                selectedTextColor = colors.primary,
                                                indicatorColor = colors.primaryContainer,
                                                unselectedIconColor = colors.onSurfaceVariant,
                                                unselectedTextColor = colors.onSurfaceVariant,
                                            ),
                                    )
                                    NavigationBarItem(
                                        selected =
                                            currentDestination == MainScreenDestination.CASE_BOARD,
                                        onClick = {
                                            currentDestination =
                                                MainScreenDestination.CASE_BOARD
                                        },
                                        icon = {
                                            Text(
                                                text =
                                                    if (currentDestination ==
                                                        MainScreenDestination.CASE_BOARD
                                                    ) {
                                                        "[●]"
                                                    } else {
                                                        "[○]"
                                                    },
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        },
                                        label = { Text(stringResource(R.string.nav_case_board)) },
                                        colors =
                                            NavigationBarItemDefaults.colors(
                                                selectedIconColor = colors.primary,
                                                selectedTextColor = colors.primary,
                                                indicatorColor = colors.primaryContainer,
                                                unselectedIconColor = colors.onSurfaceVariant,
                                                unselectedTextColor = colors.onSurfaceVariant,
                                            ),
                                    )
                                    NavigationBarItem(
                                        selected =
                                            currentDestination ==
                                                MainScreenDestination.SETTINGS_PREVIEW,
                                        onClick = {
                                            currentDestination =
                                                MainScreenDestination.SETTINGS_PREVIEW
                                        },
                                        icon = {
                                            Text(
                                                text =
                                                    if (currentDestination ==
                                                        MainScreenDestination.SETTINGS_PREVIEW
                                                    ) {
                                                        "[▲]"
                                                    } else {
                                                        "[△]"
                                                    },
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        },
                                        label = { Text(stringResource(R.string.nav_settings)) },
                                        colors =
                                            NavigationBarItemDefaults.colors(
                                                selectedIconColor = colors.primary,
                                                selectedTextColor = colors.primary,
                                                indicatorColor = colors.primaryContainer,
                                                unselectedIconColor = colors.onSurfaceVariant,
                                                unselectedTextColor = colors.onSurfaceVariant,
                                            ),
                                    )
                                }
                            },
                        ) { innerPadding ->
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(innerPadding),
                            ) {
                                when (currentDestination) {
                                    MainScreenDestination.LIBRARY -> {
                                        LibraryScreen(
                                            onNavigateToSettings = {
                                                currentDestination =
                                                    MainScreenDestination.SETTINGS_PREVIEW
                                            },
                                            onBookClick = {
                                                currentDestination =
                                                    MainScreenDestination.CASE_BOARD
                                            },
                                        )
                                    }
                                    MainScreenDestination.CASE_BOARD -> {
                                        CaseBoardScreen(
                                            onNavigateBack = {
                                                currentDestination = MainScreenDestination.LIBRARY
                                            },
                                            onNavigateToSettings = {
                                                currentDestination =
                                                    MainScreenDestination.SETTINGS_PREVIEW
                                            },
                                        )
                                    }
                                    MainScreenDestination.SETTINGS_PREVIEW -> {
                                        SettingsPreviewScreen(
                                            config = config,
                                            onConfigChange = { updated -> config = updated },
                                            userSession = userSession,
                                            onNavigateBack = {
                                                currentDestination = MainScreenDestination.LIBRARY
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        setContentView(composeView)
    }
}
