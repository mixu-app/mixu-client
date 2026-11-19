// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.network

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant

private const val MIN_STORAGE_LEVEL = 1L
private const val MAX_STORAGE_LEVEL = 5L

/** A server snapshot with no locally inferred levels, caps or grants. */
data class AccountStorageQuota(
    val quotaLimitBytes: Long,
    val usedBytes: Long,
    val status: String,
    val level: Int?,
    val amounts: Map<String, Long>,
    val checkedInToday: Boolean?,
    val gracePeriodEndsAt: String?,
    val activePlanKnown: Boolean,
    val activePlanId: String?,
)

internal fun parseAccountStorageQuota(
    obj: JsonObject,
    userId: String,
): AccountStorageQuota? =
    runCatching {
        fun string(key: String): String? =
            (obj[key] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.contentOrNull

        fun number(
            key: String,
            required: Boolean = false,
            positive: Boolean = false,
        ): Long? {
            if (key !in obj && !required) return null
            val value =
                (obj[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                    ?: error("Invalid quota amount")
            require(value >= if (positive) 1L else 0L)
            return value
        }
        require(string("user_id") == userId)
        val limit = requireNotNull(number("quota_limit_bytes", required = true))
        val used = requireNotNull(number("used_bytes", required = true))
        val status = requireNotNull(string("subscription_status"))
        require(status in setOf("ACTIVE", "GRACE_PERIOD", "OVER_QUOTA_EVICTED"))
        val level =
            number("storage_level")
                ?.also {
                    require(
                        it in MIN_STORAGE_LEVEL..MAX_STORAGE_LEVEL,
                    )
                }?.toInt()
        val longFields =
            listOf(
                "base_quota_bytes",
                "subscription_bonus_bytes",
                "max_file_size_bytes",
                "daily_download_quota_bytes",
                "daily_download_used_bytes",
                "daily_download_ad_boost_bytes",
                "monthly_download_used_bytes",
                "monthly_download_cap_bytes",
            )
        val counterFields =
            listOf(
                "daily_ad_boosts_used",
                "daily_ad_boosts_cap",
                "checkin_days",
                "checkin_streak",
                "monthly_makeup_used",
                "monthly_makeup_cap",
                "reading_minutes",
                "chapters_read",
                "graph_downloads",
                "ai_queries",
                "completed_graph_builds",
            )
        val positiveFields =
            setOf("max_file_size_bytes", "daily_ad_boosts_cap", "monthly_makeup_cap")
        val amounts =
            buildMap {
                (longFields + counterFields).forEach { key ->
                    number(key, positive = key in positiveFields)?.let { value ->
                        if (key in counterFields) require(value <= Int.MAX_VALUE)
                        put(key, value)
                    }
                }
            }
        val base = amounts["base_quota_bytes"]
        val bonus = amounts["subscription_bonus_bytes"]
        if (base != null && bonus != null) require(Math.addExact(base, bonus) == limit)
        val checked =
            if ("checked_in_today" in obj) {
                (obj["checked_in_today"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                    ?: error("Invalid check-in state")
            } else {
                null
            }
        val grace =
            if (obj["grace_period_ends_at"] ==
                JsonNull
            ) {
                null
            } else {
                string("grace_period_ends_at")
            }
        if ("grace_period_ends_at" in obj && obj["grace_period_ends_at"] != JsonNull) {
            require(grace != null)
            Instant.parse(grace)
        }
        val plan = if (obj["active_plan_id"] == JsonNull) null else string("active_plan_id")
        if ("active_plan_id" in obj &&
            obj["active_plan_id"] != JsonNull
        ) {
            require(!plan.isNullOrBlank())
        }
        AccountStorageQuota(
            limit,
            used,
            status,
            level,
            amounts,
            checked,
            grace,
            "active_plan_id" in obj,
            plan,
        )
    }.getOrNull()

/** A check-in result is adopted only after validating both flags and its owned quota snapshot. */
data class AccountStorageCheckIn(
    val alreadyCheckedIn: Boolean,
    val newlyLeveledUp: Boolean,
    val quota: AccountStorageQuota,
)

internal fun parseAccountStorageCheckIn(
    obj: JsonObject,
    userId: String,
): AccountStorageCheckIn? =
    runCatching {
        fun flag(key: String): Boolean =
            (obj[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                ?: error("Invalid check-in result")
        val already = flag("already_checked_in")
        val leveled = flag("newly_leveled_up")
        require(!already || !leveled)
        val quota =
            requireNotNull(parseAccountStorageQuota(obj["quota_summary"] as JsonObject, userId))
        require(quota.checkedInToday == true)
        AccountStorageCheckIn(already, leveled, quota)
    }.getOrNull()
