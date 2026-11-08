// SPDX-License-Identifier: MPL-2.0
package app.mixu.client

import android.app.Activity
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import app.mixu.client.auth.UserSession
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
                        when (currentDestination) {
                            MainScreenDestination.LIBRARY -> {
                                LibraryScreen(
                                    onNavigateToSettings = {
                                        currentDestination = MainScreenDestination.SETTINGS_PREVIEW
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
        setContentView(composeView)
    }
}
