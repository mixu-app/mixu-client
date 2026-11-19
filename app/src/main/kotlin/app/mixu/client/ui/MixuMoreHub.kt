// SPDX-License-Identifier: MPL-2.0
@file:Suppress("ktlint:standard:function-naming")

package app.mixu.client.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mixu.client.theme.MixuTheme

/** Reader-facing hub links only to existing native capabilities. */
@Composable
fun MixuMoreHub(
    authenticated: Boolean,
    onSelectSection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography
    val radius = MixuTheme.appearance.cardCornerRadius
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MixuBrandLogo()
            Text(
                "更多",
                style = typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth().clickable { onSelectSection("account") },
            shape = RoundedCornerShape(radius),
            colors = CardDefaults.cardColors(containerColor = colors.primary),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (authenticated) "您的阅读空间" else "离线阅读，随时开始",
                    style = typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.onPrimary,
                )
                Text(
                    if (authenticated) "管理账号、同步与云端额度 →" else "图书与笔记保存在本机 · 登录以使用云端服务 →",
                    style = typography.bodyMedium,
                    color = colors.onPrimary,
                )
            }
        }
        HubSection(
            "账号与云端",
            listOf(
                Triple("account", "账号、推理点与存储", "登录、推理点与云空间等级"),
                Triple("services", "云端服务", "连接方式与个人服务配置"),
            ),
            onSelectSection,
        )
        HubSection(
            "阅读与工具",
            listOf(
                Triple("reader", "阅读与墨水屏设置", "主题、字体、翻页与显示策略"),
            ),
            onSelectSection,
        )
        HubSection(
            "支持",
            listOf(
                Triple("help", "帮助与关于谜序", "使用说明、反馈、开源与许可证"),
            ),
            onSelectSection,
        )
    }
}

@Composable
private fun HubSection(
    title: String,
    rows: List<Triple<String, String, String>>,
    onSelect: (String) -> Unit,
) {
    val colors = MixuTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MixuTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        Card(
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            shape = RoundedCornerShape(MixuTheme.appearance.cardCornerRadius),
            border = BorderStroke(if (colors.isEInk) 2.dp else 1.dp, colors.outlineVariant),
        ) {
            rows.forEach { (key, label, description) ->
                Row(Modifier.fillMaxWidth().clickable { onSelect(key) }.padding(18.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            label,
                            style = MixuTheme.typography.titleMedium,
                            color = colors.onSurface,
                        )
                        Text(
                            description,
                            style = MixuTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Text("›", color = colors.onSurfaceVariant)
                }
            }
        }
    }
}
