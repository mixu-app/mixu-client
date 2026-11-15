// SPDX-License-Identifier: MPL-2.0
@file:Suppress("ktlint:standard:function-naming")

package app.mixu.client.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mixu.client.config.FontFamilyChoice
import app.mixu.client.config.MarginPreset
import app.mixu.client.config.PageColumnMode
import app.mixu.client.config.ReaderDisplayConfig
import app.mixu.client.config.ReaderPreset
import app.mixu.client.config.ReadingLayoutMode
import app.mixu.client.theme.MixuTheme
import app.mixu.client.theme.MixuThemeMode
import kotlin.math.roundToInt

private const val MIN_READER_FONT_SIZE = 12f
private const val MAX_READER_FONT_SIZE = 42f
private const val READER_FONT_STEP = 2f

/** Focused immediate-apply reading preferences; publication algorithms stay in their owner. */
@Composable
internal fun ReaderPreferencesDialog(
    config: ReaderDisplayConfig,
    onConfigChange: (ReaderDisplayConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MixuTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurface,
        shape = RoundedCornerShape(MixuTheme.appearance.cardCornerRadius),
        tonalElevation = 0.dp,
        title = { Text("阅读设置") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("文字", style = MixuTheme.typography.titleMedium)
                PreferenceChoice(
                    "字体",
                    config.fontFamilyChoice,
                    listOf(
                        FontFamilyChoice.SERIF to "衬线",
                        FontFamilyChoice.SANS_SERIF to "无衬线",
                        FontFamilyChoice.MONOSPACE to "等宽",
                    ),
                ) {
                    onConfigChange(config.copy(fontFamilyChoice = it))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("字号", modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            onConfigChange(
                                config.copy(
                                    fontSizeSp =
                                        (config.fontSizeSp - READER_FONT_STEP).coerceAtLeast(
                                            MIN_READER_FONT_SIZE,
                                        ),
                                ),
                            )
                        },
                        modifier =
                            Modifier.sizeIn(
                                minWidth = 48.dp,
                                minHeight = 48.dp,
                            ),
                    ) { Text("A−", modifier = Modifier.semantics { contentDescription = "缩小字号" }) }
                    Text(config.effectiveFontSizeSp().roundToInt().toString())
                    TextButton(
                        onClick = {
                            onConfigChange(
                                config.copy(
                                    fontSizeSp =
                                        (config.fontSizeSp + READER_FONT_STEP).coerceAtMost(
                                            MAX_READER_FONT_SIZE,
                                        ),
                                ),
                            )
                        },
                        modifier =
                            Modifier.sizeIn(
                                minWidth = 48.dp,
                                minHeight = 48.dp,
                            ),
                    ) { Text("A+", modifier = Modifier.semantics { contentDescription = "放大字号" }) }
                }
                PreferenceChoice(
                    "行距",
                    config.lineHeightMultiplier,
                    listOf(1.3f to "紧凑", 1.5f to "标准", 1.8f to "宽松"),
                ) {
                    onConfigChange(config.copy(lineHeightMultiplier = it))
                }
                HorizontalDivider(color = colors.outlineVariant)
                Text("外观", style = MixuTheme.typography.titleMedium)
                PreferenceChoice(
                    "主题",
                    config.themeMode,
                    listOf(
                        MixuThemeMode.LIGHT to "浅色",
                        MixuThemeMode.SEPIA to "暖纸",
                        MixuThemeMode.MINT to "浅绿",
                        MixuThemeMode.SOLARIZED to "柔光",
                        MixuThemeMode.DARK to "深色",
                        MixuThemeMode.NORD_DARK to "灰蓝",
                        MixuThemeMode.EINK to "墨水屏",
                    ),
                ) {
                    onConfigChange(config.copy(themeMode = it, customThemePackJson = null))
                }
                PreferenceChoice(
                    "阅读风格",
                    config.activePreset,
                    listOf(
                        ReaderPreset.KINDLE_CLASSIC to "经典",
                        ReaderPreset.READEST_MODERN to "清爽",
                        ReaderPreset.KOODO_EYECARE to "护眼",
                        ReaderPreset.NIGHT_DETECTIVE to "夜间",
                        ReaderPreset.EINK_CRISP to "墨水屏",
                    ),
                ) {
                    onConfigChange(config.applyPreset(it).copy(customThemePackJson = null))
                }
                HorizontalDivider(color = colors.outlineVariant)
                Text("排版", style = MixuTheme.typography.titleMedium)
                PreferenceChoice(
                    "分栏",
                    config.pageColumnMode,
                    listOf(
                        PageColumnMode.AUTO to "自动",
                        PageColumnMode.SINGLE to "单栏",
                        PageColumnMode.DOUBLE to "双栏",
                    ),
                ) {
                    onConfigChange(config.copy(pageColumnMode = it))
                }
                PreferenceChoice(
                    "阅读方式",
                    config.readingLayoutMode,
                    listOf(ReadingLayoutMode.PAGED to "翻页", ReadingLayoutMode.CONTINUOUS to "滚动"),
                ) {
                    onConfigChange(config.copy(readingLayoutMode = it))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("首行缩进", modifier = Modifier.weight(1f))
                    Switch(
                        checked = config.paragraphIndentEm > 0f,
                        onCheckedChange = {
                            onConfigChange(config.copy(paragraphIndentEm = if (it) 2f else 0f))
                        },
                        modifier =
                            Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                                contentDescription = "首行缩进"
                            },
                        colors =
                            SwitchDefaults.colors(
                                checkedThumbColor = colors.onPrimary,
                                checkedTrackColor = colors.primary,
                                uncheckedThumbColor = colors.onSurface,
                                uncheckedTrackColor = colors.surface,
                                uncheckedBorderColor = colors.outline,
                            ),
                    )
                }
                PreferenceChoice(
                    "页边距",
                    config.marginPreset,
                    listOf(
                        MarginPreset.COMPACT to "紧凑",
                        MarginPreset.NORMAL to "标准",
                        MarginPreset.WIDE to "宽裕",
                    ),
                ) {
                    onConfigChange(config.copy(marginPreset = it))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) { Text("完成") }
        },
    )
}

@Composable
private fun <T> PreferenceChoice(
    label: String,
    selected: T?,
    choices: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
) {
    val colors = MixuTheme.colors
    val isEInk = MixuTheme.einkPolicy.isActive || colors.isEInk
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = choices.firstOrNull { it.first == selected }?.second ?: "自定义"
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Box {
            TextButton(
                onClick = { expanded = true },
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Text(
                    selectedLabel,
                    modifier =
                        Modifier.semantics {
                            contentDescription =
                                "$label：$selectedLabel"
                        },
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = colors.surface,
                shape = RoundedCornerShape(MixuTheme.appearance.controlCornerRadius),
                tonalElevation = 0.dp,
                shadowElevation = if (isEInk) 0.dp else 4.dp,
                border = BorderStroke(if (isEInk) 2.dp else 1.dp, colors.outlineVariant),
            ) {
                choices.forEach { (value, name) ->
                    DropdownMenuItem(text = { Text(name) }, leadingIcon = {
                        RadioButton(selected = selected == value, onClick = null)
                    }, onClick = {
                        onSelect(value)
                        expanded = false
                    })
                }
            }
        }
    }
}
