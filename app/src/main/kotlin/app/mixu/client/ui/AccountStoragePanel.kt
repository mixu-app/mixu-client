// SPDX-License-Identifier: MPL-2.0
@file:Suppress("ktlint:standard:function-naming")

package app.mixu.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.mixu.client.billing.SubscriptionPlan
import app.mixu.client.network.AccountStorageQuota
import app.mixu.client.theme.MixuTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private const val BYTES_PER_MEBIBYTE = 1024L * 1024L

/** Only authenticated server state is displayed; actions cannot grant local account benefits. */
@Composable
internal fun AccountStoragePanel(
    authenticated: Boolean,
    snapshot: AccountStorageQuota?,
    loading: Boolean,
    checkInBusy: Boolean,
    checkInMessage: String?,
    onRefresh: () -> Unit,
    onCheckIn: () -> Unit,
) {
    val colors = MixuTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("等级云空间", style = MixuTheme.typography.titleMedium, color = colors.onSurface)
        when {
            !authenticated -> Text("登录后查看云空间与成长等级", color = colors.onSurfaceVariant)
            loading -> Text("正在读取云空间…", color = colors.onSurfaceVariant)
            snapshot == null -> Text("云空间暂时不可用", color = colors.onSurfaceVariant)
            else -> {
                Text(snapshot.level?.let { "成长等级 Lv.$it" } ?: "等级信息暂不可用")
                Text(
                    "已使用 ${storageSize(
                        snapshot.usedBytes,
                    )} / ${storageSize(snapshot.quotaLimitBytes)}",
                )
                Text(
                    when (snapshot.status) {
                        "GRACE_PERIOD" -> "超额宽限期"
                        "OVER_QUOTA_EVICTED" -> "云空间超额受限"
                        else -> "云空间可用"
                    },
                )
                snapshot.gracePeriodEndsAt?.let {
                    val date =
                        DateTimeFormatter
                            .ofLocalizedDateTime(FormatStyle.SHORT)
                            .withLocale(Locale.getDefault())
                            .withZone(ZoneId.systemDefault())
                            .format(Instant.parse(it))
                    Text("宽限期截止：$date")
                }
                snapshot.amounts["max_file_size_bytes"]?.let { Text("单个文件上限 ${storageSize(it)}") }
                val base = snapshot.amounts["base_quota_bytes"]
                val bonus = snapshot.amounts["subscription_bonus_bytes"]
                if (base != null &&
                    bonus != null
                ) {
                    Text("等级容量 ${storageSize(base)} · 订阅容量 ${storageSize(bonus)}")
                }
                if (snapshot.activePlanKnown) {
                    Text(
                        snapshot.activePlanId?.let { id ->
                            SubscriptionPlan.fromPlanId(id)?.displayName?.let { "当前方案：$it" }
                                ?: "方案信息暂不可用"
                        } ?: "未开通订阅",
                    )
                }
                HorizontalDivider(color = colors.outlineVariant)
                Text("下载额度", style = MixuTheme.typography.titleMedium)
                val dailyUsed = snapshot.amounts["daily_download_used_bytes"]
                val dailyCap = snapshot.amounts["daily_download_quota_bytes"]
                if (dailyUsed != null && dailyCap != null) {
                    Text("今日已下载 ${storageSize(dailyUsed)} · 基础额度 ${storageSize(dailyCap)}")
                } else {
                    Text("下载用量暂不可用")
                }
                snapshot.amounts["daily_download_ad_boost_bytes"]?.let {
                    Text("今日临时下载加量 ${storageSize(it)}")
                }
                val monthlyUsed = snapshot.amounts["monthly_download_used_bytes"]
                val monthlyCap = snapshot.amounts["monthly_download_cap_bytes"]
                if (monthlyUsed != null &&
                    monthlyCap != null
                ) {
                    Text("本月下载 ${storageSize(monthlyUsed)} / ${storageSize(monthlyCap)}")
                }
                HorizontalDivider(color = colors.outlineVariant)
                Text("成长记录", style = MixuTheme.typography.titleMedium)
                val metricLabels =
                    listOf(
                        "checkin_days" to "累计签到",
                        "checkin_streak" to "连续签到",
                        "reading_minutes" to "阅读分钟",
                        "chapters_read" to "已读章节",
                        "graph_downloads" to "图谱下载",
                        "ai_queries" to "伴读提问",
                        "completed_graph_builds" to "完成图谱",
                    )
                val metrics =
                    metricLabels.mapNotNull { (key, label) ->
                        snapshot.amounts[key]?.let { "$label $it" }
                    }
                Text(if (metrics.isEmpty()) "成长数据暂不可用" else metrics.joinToString(" · "))
                snapshot.checkedInToday?.let { Text(if (it) "今日已签到" else "今日尚未签到") }
            }
        }
        if (authenticated) {
            TextButton(
                onClick = onRefresh,
                enabled = !loading && !checkInBusy,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) { Text("刷新云空间") }
        }
        if (authenticated) {
            TextButton(
                onClick = onCheckIn,
                enabled = !loading && !checkInBusy && snapshot?.checkedInToday == false,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Text(
                    when {
                        checkInBusy -> "正在签到…"
                        snapshot?.checkedInToday == true -> "今日已签到"
                        snapshot?.checkedInToday == false -> "签到"
                        else -> "签到暂不可用"
                    },
                )
            }
            checkInMessage?.let { Text(it, color = colors.onSurfaceVariant) }
        }
        Text(
            "广告奖励、补签、订阅与充值操作暂未开放。",
            style = MixuTheme.typography.caption,
            color = colors.onSurfaceVariant,
        )
    }
}

private fun storageSize(bytes: Long): String =
    String.format(Locale.getDefault(), "%.1f MB", bytes.toDouble() / BYTES_PER_MEBIBYTE)
