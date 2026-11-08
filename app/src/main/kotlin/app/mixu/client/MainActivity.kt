// SPDX-License-Identifier: MPL-2.0
package app.mixu.client

import android.app.Activity
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import app.mixu.client.config.ReaderDisplayConfig
import app.mixu.client.eink.EInkDetector
import app.mixu.client.theme.MixuTheme
import app.mixu.client.theme.createMixuTypography
import app.mixu.client.ui.SettingsPreviewScreen

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val composeView =
            ComposeView(this).apply {
                setContent {
                    var config by remember { mutableStateOf(ReaderDisplayConfig.DEFAULT) }
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
                        SettingsPreviewScreen(
                            config = config,
                            onConfigChange = { updated -> config = updated },
                        )
                    }
                }
            }
        setContentView(composeView)
    }
}
