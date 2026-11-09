// SPDX-License-Identifier: MPL-2.0
@file:Suppress("ktlint:standard:function-naming")

package app.mixu.client.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import app.mixu.client.eink.EInkPolicy

val LocalMixuColors = staticCompositionLocalOf { LightMixuColors }
val LocalMixuTypography = staticCompositionLocalOf { createMixuTypography() }
val LocalMixuEInkPolicy = staticCompositionLocalOf { EInkPolicy.STANDARD }

object MixuTheme {
    val colors: MixuColors
        @Composable
        @ReadOnlyComposable
        get() = LocalMixuColors.current

    val typography: MixuTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalMixuTypography.current

    val einkPolicy: EInkPolicy
        @Composable
        @ReadOnlyComposable
        get() = LocalMixuEInkPolicy.current
}

/**
 * Root design system theme provider unifying Mixu semantic tokens with Compose Material3.
 */
@Composable
fun MixuTheme(
    themeMode: MixuThemeMode = MixuThemeMode.LIGHT,
    eInkPolicy: EInkPolicy = EInkPolicy.STANDARD,
    typography: MixuTypography = createMixuTypography(),
    content: @Composable () -> Unit,
) {
    val resolvedColors =
        if (eInkPolicy.isActive || themeMode == MixuThemeMode.EINK) {
            EInkMixuColors
        } else {
            when (themeMode) {
                MixuThemeMode.LIGHT -> LightMixuColors
                MixuThemeMode.DARK -> DarkMixuColors
                MixuThemeMode.SEPIA -> SepiaMixuColors
                MixuThemeMode.MINT -> MintMixuColors
                MixuThemeMode.SOLARIZED -> SolarizedMixuColors
                MixuThemeMode.NORD_DARK -> NordDarkMixuColors
                MixuThemeMode.EINK -> EInkMixuColors
            }
        }

    val isDarkPalette =
        (themeMode == MixuThemeMode.DARK || themeMode == MixuThemeMode.NORD_DARK) &&
            !resolvedColors.isEInk

    val materialColorScheme: ColorScheme =
        if (isDarkPalette) {
            darkColorScheme(
                primary = resolvedColors.primary,
                onPrimary = resolvedColors.onPrimary,
                primaryContainer = resolvedColors.primaryContainer,
                onPrimaryContainer = resolvedColors.onPrimaryContainer,
                background = resolvedColors.background,
                onBackground = resolvedColors.onBackground,
                surface = resolvedColors.surface,
                onSurface = resolvedColors.onSurface,
                surfaceVariant = resolvedColors.surfaceVariant,
                onSurfaceVariant = resolvedColors.onSurfaceVariant,
                outline = resolvedColors.outline,
                outlineVariant = resolvedColors.outlineVariant,
            )
        } else {
            lightColorScheme(
                primary = resolvedColors.primary,
                onPrimary = resolvedColors.onPrimary,
                primaryContainer = resolvedColors.primaryContainer,
                onPrimaryContainer = resolvedColors.onPrimaryContainer,
                background = resolvedColors.background,
                onBackground = resolvedColors.onBackground,
                surface = resolvedColors.surface,
                onSurface = resolvedColors.onSurface,
                surfaceVariant = resolvedColors.surfaceVariant,
                onSurfaceVariant = resolvedColors.onSurfaceVariant,
                outline = resolvedColors.outline,
                outlineVariant = resolvedColors.outlineVariant,
            )
        }

    val resolvedEInkPolicy =
        if (themeMode == MixuThemeMode.EINK && !eInkPolicy.isActive) {
            EInkPolicy.EINK_ACTIVE
        } else {
            eInkPolicy
        }

    CompositionLocalProvider(
        LocalMixuColors provides resolvedColors,
        LocalMixuTypography provides typography,
        LocalMixuEInkPolicy provides resolvedEInkPolicy,
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            content = content,
        )
    }
}
