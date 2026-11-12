// SPDX-License-Identifier: MPL-2.0

package app.mixu.client.billing

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Cloud storage lifecycle status enforcing the 7-Day Grace Period before cold eviction (ADR-030).
 */
enum class ClientStorageStatus {
    ACTIVE,
    GRACE_PERIOD,
    OVER_QUOTA_EVICTED,
}

/**
 * Five-level free cloud storage progression tiers earned via reading, check-ins, and AI usage (ADR-030).
 */
enum class StorageLevel(
    val level: Int,
    val baseQuotaBytes: Long,
    val maxFileSizeBytes: Long,
    val dailyDownloadQuotaBytes: Long,
    val monthlyDownloadCapBytes: Long,
) {
    LV1(
        level = 1,
        baseQuotaBytes = 104_857_600L, // 100 MB
        maxFileSizeBytes = 15_728_640L, // 15 MB
        dailyDownloadQuotaBytes = 52_428_800L, // 50 MB
        monthlyDownloadCapBytes = 629_145_600L, // 600 MB
    ),
    LV2(
        level = 2,
        baseQuotaBytes = 209_715_200L, // 200 MB
        maxFileSizeBytes = 31_457_280L, // 30 MB
        dailyDownloadQuotaBytes = 104_857_600L, // 100 MB
        monthlyDownloadCapBytes = 1_258_291_200L, // 1,200 MB
    ),
    LV3(
        level = 3,
        baseQuotaBytes = 524_288_000L, // 500 MB
        maxFileSizeBytes = 62_914_560L, // 60 MB
        dailyDownloadQuotaBytes = 209_715_200L, // 200 MB
        monthlyDownloadCapBytes = 2_621_440_000L, // 2,500 MB
    ),
    LV4(
        level = 4,
        baseQuotaBytes = 1_073_741_824L, // 1 GB
        maxFileSizeBytes = 104_857_600L, // 100 MB
        dailyDownloadQuotaBytes = 367_001_600L, // 350 MB
        monthlyDownloadCapBytes = 4_718_592_000L, // 4,500 MB
    ),
    LV5(
        level = 5,
        baseQuotaBytes = 2_147_483_648L, // 2 GB
        maxFileSizeBytes = 209_715_200L, // 200 MB
        dailyDownloadQuotaBytes = 629_145_600L, // 600 MB
        monthlyDownloadCapBytes = 8_589_934_592L, // 8 GB
    ),
    ;

    val badgeLabel: String
        get() = "Lv.$level"

    companion object {
        private const val LV2_CHECKIN_DAYS = 3
        private const val LV2_GRAPH_DOWNLOADS = 1

        private const val LV3_CHECKIN_DAYS = 14
        private const val LV3_READING_MINUTES = 180
        private const val LV3_CHAPTERS_READ = 15
        private const val LV3_GRAPH_DOWNLOADS = 3
        private const val LV3_AI_QUERIES = 5

        private const val LV4_CHECKIN_DAYS = 45
        private const val LV4_READING_MINUTES = 720
        private const val LV4_CHAPTERS_READ = 60
        private const val LV4_GRAPH_DOWNLOADS = 6
        private const val LV4_AI_QUERIES = 20
        private const val LV4_COMPLETED_BUILDS = 1

        private const val LV5_CHECKIN_DAYS = 90
        private const val LV5_READING_MINUTES = 1_800
        private const val LV5_CHAPTERS_READ = 150
        private const val LV5_AI_QUERIES = 50
        private const val LV5_COMPLETED_BUILDS = 3

        fun fromLevel(level: Int): StorageLevel =
            entries.firstOrNull { it.level == level }
                ?: if (level <= LV1.level) LV1 else LV5

        /**
         * Evaluates the highest [StorageLevel] whose unlock threshold is satisfied.
         *
         * Time complexity: O(1). Space complexity: O(1).
         */
        fun evaluate(
            checkinDays: Int,
            readingMinutes: Int,
            chaptersRead: Int,
            graphDownloads: Int,
            aiQueries: Int,
            completedGraphBuilds: Int,
        ): StorageLevel =
            when {
                checkinDays >= LV5_CHECKIN_DAYS &&
                    (readingMinutes >= LV5_READING_MINUTES || chaptersRead >= LV5_CHAPTERS_READ) &&
                    aiQueries >= LV5_AI_QUERIES &&
                    completedGraphBuilds >= LV5_COMPLETED_BUILDS -> LV5
                checkinDays >= LV4_CHECKIN_DAYS &&
                    (readingMinutes >= LV4_READING_MINUTES || chaptersRead >= LV4_CHAPTERS_READ) &&
                    graphDownloads >= LV4_GRAPH_DOWNLOADS &&
                    aiQueries >= LV4_AI_QUERIES &&
                    completedGraphBuilds >= LV4_COMPLETED_BUILDS -> LV4
                checkinDays >= LV3_CHECKIN_DAYS &&
                    (readingMinutes >= LV3_READING_MINUTES || chaptersRead >= LV3_CHAPTERS_READ) &&
                    graphDownloads >= LV3_GRAPH_DOWNLOADS &&
                    aiQueries >= LV3_AI_QUERIES -> LV3
                checkinDays >= LV2_CHECKIN_DAYS &&
                    graphDownloads >= LV2_GRAPH_DOWNLOADS -> LV2
                else -> LV1
            }
    }
}

/**
 * Three-Tier Book Edition Fingerprinting hierarchy for Shared GraphPack Marketplace matching (ADR-040).
 */
enum class EditionMatchTier {
    L1_EXACT_SHA256,
    L2_STRUCTURE_FINGERPRINT,
    L3_ISBN_CHAPTER_ANCHOR,
}

/**
 * Monthly subscription tiers combining additive cloud storage bonuses and monthly `sub_credits` (推理点).
 */
enum class SubscriptionPlan(
    val planId: String,
    val displayName: String,
    val monthlyPriceCny: Int,
    val monthlySubCredits: Int,
    val quotaMb: Int,
    val bonusQuotaBytes: Long,
    val minMaxFileSizeBytes: Long,
    val bonusDailyEgressBytes: Long,
    val bonusMonthlyEgressBytes: Long,
) {
    FREE(
        planId = "free",
        displayName = "免费新人档",
        monthlyPriceCny = 0,
        monthlySubCredits = 0,
        quotaMb = 100,
        bonusQuotaBytes = 0L,
        minMaxFileSizeBytes = 0L,
        bonusDailyEgressBytes = 0L,
        bonusMonthlyEgressBytes = 0L,
    ),
    PLUS_MONTHLY(
        planId = "plus_monthly_12_cny",
        displayName = "谜序 Plus 月卡",
        monthlyPriceCny = 12,
        monthlySubCredits = 150,
        quotaMb = 5_120,
        bonusQuotaBytes = 5_368_709_120L, // +5 GB
        minMaxFileSizeBytes = 314_572_800L, // >= 300 MB
        bonusDailyEgressBytes = 2_147_483_648L, // +2 GB/day
        bonusMonthlyEgressBytes = 21_474_836_480L, // +20 GB/mo
    ),
    PRO_MONTHLY(
        planId = "pro_monthly_24_cny",
        displayName = "谜序 Pro 藏书家月卡",
        monthlyPriceCny = 24,
        monthlySubCredits = 600,
        quotaMb = 20_480,
        bonusQuotaBytes = 21_474_836_480L, // +20 GB
        minMaxFileSizeBytes = 524_288_000L, // >= 500 MB
        bonusDailyEgressBytes = 5_368_709_120L, // +5 GB/day
        bonusMonthlyEgressBytes = 53_687_091_200L, // +50 GB/mo
    ),
    ;

    companion object {
        fun fromPlanId(planId: String?): SubscriptionPlan? {
            if (planId.isNullOrBlank()) return null
            return entries.firstOrNull { it.planId == planId }
        }
    }
}

/**
 * Standalone permanent 推理点 top-up packs credited to `paidCredits` (never expire, consumed last).
 */
enum class TopUpPack(
    val packId: String,
    val displayName: String,
    val priceCny: Int,
    val credits: Int,
) {
    PACK_6_CNY(
        packId = "pack_6_cny",
        displayName = "应急推理包",
        priceCny = 6,
        credits = 80,
    ),
    PACK_18_CNY(
        packId = "pack_18_cny",
        displayName = "畅读推理包",
        priceCny = 18,
        credits = 300,
    ),
    PACK_45_CNY(
        packId = "pack_45_cny",
        displayName = "藏书家囤点包",
        priceCny = 45,
        credits = 900,
    ),
}

/**
 * Represents the reader's 5-level cloud storage progression, additive subscription quota,
 * daily/monthly download egress allowance, and 7-day grace period policy state (ADR-030).
 */
data class StorageQuotaState(
    val storageLevel: StorageLevel = StorageLevel.LV1,
    val usedBytes: Long = DEFAULT_USED_BYTES,
    val baseQuotaBytes: Long = storageLevel.baseQuotaBytes,
    val subscriptionBonusBytes: Long = 0L,
    val maxFileSizeBytes: Long = storageLevel.maxFileSizeBytes,
    val dailyDownloadQuotaBytes: Long = storageLevel.dailyDownloadQuotaBytes,
    val dailyDownloadUsedBytes: Long = 0L,
    val dailyDownloadAdBoostBytes: Long = 0L,
    val dailyAdBoostsUsed: Int = 0,
    val dailyAdBoostsCap: Int = DEFAULT_DAILY_AD_BOOSTS_CAP,
    val monthlyDownloadUsedBytes: Long = 0L,
    val monthlyDownloadCapBytes: Long = storageLevel.monthlyDownloadCapBytes,
    val checkinDays: Int = 0,
    val checkinStreak: Int = 0,
    val checkedInToday: Boolean = false,
    val monthlyMakeupUsed: Int = 0,
    val monthlyMakeupCap: Int = DEFAULT_MONTHLY_MAKEUP_CAP,
    val readingMinutes: Int = 0,
    val chaptersRead: Int = 0,
    val graphDownloads: Int = 0,
    val aiQueries: Int = 0,
    val completedGraphBuilds: Int = 0,
    val status: ClientStorageStatus = ClientStorageStatus.ACTIVE,
    val gracePeriodDaysRemaining: Int = DEFAULT_GRACE_DAYS,
    val activePlan: SubscriptionPlan? = SubscriptionPlan.FREE,
    val lastCheckinDayKey: String? = null,
    val recordedCheckinDayKeys: Set<String> = emptySet(),
    val readingSecondsCarry: Int = 0,
) {
    /**
     * Legacy MB-based constructor preserved for existing tests and callers.
     */
    constructor(
        usedMb: Int,
        quotaMb: Int,
        status: ClientStorageStatus = ClientStorageStatus.ACTIVE,
        gracePeriodDaysRemaining: Int = DEFAULT_GRACE_DAYS,
        activePlan: SubscriptionPlan? = SubscriptionPlan.FREE,
    ) : this(
        storageLevel = StorageLevel.LV1,
        usedBytes = usedMb.toLong().coerceAtLeast(0L) * BYTES_PER_MB,
        baseQuotaBytes = quotaMb.toLong().coerceAtLeast(0L) * BYTES_PER_MB,
        subscriptionBonusBytes = 0L,
        status = status,
        gracePeriodDaysRemaining = gracePeriodDaysRemaining,
        activePlan = activePlan,
    )

    init {
        require(usedBytes >= 0L) { "usedBytes must be non-negative" }
        require(baseQuotaBytes >= 0L) { "baseQuotaBytes must be non-negative" }
        require(subscriptionBonusBytes >= 0L) { "subscriptionBonusBytes must be non-negative" }
        require(maxFileSizeBytes >= 1L) { "maxFileSizeBytes must be positive" }
        require(dailyDownloadQuotaBytes >= 0L) { "dailyDownloadQuotaBytes must be non-negative" }
        require(dailyDownloadUsedBytes >= 0L) { "dailyDownloadUsedBytes must be non-negative" }
        require(
            dailyDownloadAdBoostBytes >= 0L,
        ) { "dailyDownloadAdBoostBytes must be non-negative" }
        require(dailyAdBoostsUsed >= 0) { "dailyAdBoostsUsed must be non-negative" }
        require(dailyAdBoostsCap >= 1) { "dailyAdBoostsCap must be positive" }
        require(monthlyDownloadUsedBytes >= 0L) { "monthlyDownloadUsedBytes must be non-negative" }
        require(monthlyDownloadCapBytes >= 0L) { "monthlyDownloadCapBytes must be non-negative" }
        require(checkinDays >= 0) { "checkinDays must be non-negative" }
        require(checkinStreak >= 0) { "checkinStreak must be non-negative" }
        require(monthlyMakeupUsed >= 0) { "monthlyMakeupUsed must be non-negative" }
        require(monthlyMakeupCap >= 1) { "monthlyMakeupCap must be positive" }
        require(readingMinutes >= 0) { "readingMinutes must be non-negative" }
        require(chaptersRead >= 0) { "chaptersRead must be non-negative" }
        require(graphDownloads >= 0) { "graphDownloads must be non-negative" }
        require(aiQueries >= 0) { "aiQueries must be non-negative" }
        require(completedGraphBuilds >= 0) { "completedGraphBuilds must be non-negative" }
        require(readingSecondsCarry >= 0) { "readingSecondsCarry must be non-negative" }
    }

    val quotaLimitBytes: Long
        get() = baseQuotaBytes + subscriptionBonusBytes

    val usedMb: Int
        get() = (usedBytes / BYTES_PER_MB).toInt()

    val quotaMb: Int
        get() = (quotaLimitBytes / BYTES_PER_MB).toInt()

    val baseQuotaMb: Int
        get() = (baseQuotaBytes / BYTES_PER_MB).toInt()

    val subscriptionBonusMb: Int
        get() = (subscriptionBonusBytes / BYTES_PER_MB).toInt()

    val maxFileSizeMb: Int
        get() = (maxFileSizeBytes / BYTES_PER_MB).toInt()

    val effectiveDailyDownloadQuotaBytes: Long
        get() = dailyDownloadQuotaBytes + dailyDownloadAdBoostBytes

    val remainingDailyDownloadBytes: Long
        get() = (effectiveDailyDownloadQuotaBytes - dailyDownloadUsedBytes).coerceAtLeast(0L)

    val remainingMonthlyDownloadBytes: Long
        get() = (monthlyDownloadCapBytes - monthlyDownloadUsedBytes).coerceAtLeast(0L)

    val dailyDownloadUsedMb: Int
        get() = (dailyDownloadUsedBytes / BYTES_PER_MB).toInt()

    val dailyDownloadQuotaMb: Int
        get() = (dailyDownloadQuotaBytes / BYTES_PER_MB).toInt()

    val dailyDownloadAdBoostMb: Int
        get() = (dailyDownloadAdBoostBytes / BYTES_PER_MB).toInt()

    val monthlyDownloadUsedMb: Int
        get() = (monthlyDownloadUsedBytes / BYTES_PER_MB).toInt()

    val monthlyDownloadCapMb: Int
        get() = (monthlyDownloadCapBytes / BYTES_PER_MB).toInt()

    /**
     * New EPUB uploads are permitted only when storage status is [ClientStorageStatus.ACTIVE]
     * and current usage is below the quota ceiling.
     */
    fun canUploadNewEpub(): Boolean =
        status == ClientStorageStatus.ACTIVE && usedBytes < quotaLimitBytes

    /**
     * Checks whether a single file of [fileSizeBytes] can be uploaded under the active status,
     * single-file size ceiling ([maxFileSizeBytes]), and remaining cloud storage quota.
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun canUploadFileBytes(fileSizeBytes: Long): Boolean {
        if (fileSizeBytes <= 0L) return false
        if (status != ClientStorageStatus.ACTIVE) return false
        if (fileSizeBytes > maxFileSizeBytes) return false
        return fileSizeBytes <= (quotaLimitBytes - usedBytes)
    }

    /**
     * Existing books, reading progress, and unlocked GraphPacks remain downloadable even during
     * [ClientStorageStatus.GRACE_PERIOD] so user assets are never held hostage.
     */
    fun canDownloadExistingAssets(): Boolean = true

    /**
     * Applies [plan] additively on top of the reader's earned [storageLevel].
     * Switching back to [SubscriptionPlan.FREE] removes subscription bonuses and enters
     * [ClientStorageStatus.GRACE_PERIOD] only when [usedBytes] exceeds the earned level quota.
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun applySubscriptionPlan(plan: SubscriptionPlan): StorageQuotaState =
        recomputeQuotas(nextLevel = storageLevel, nextPlan = plan)

    /**
     * Records an idempotent daily check-in for [dayKey] (`YYYY-MM-DD`) and monotonically
     * advances [storageLevel] if unlock criteria are satisfied.
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun recordCheckIn(dayKey: String): StorageQuotaState {
        val cleanDay = dayKey.trim()
        val targetDate = LocalDate.parse(cleanDay)
        if (cleanDay in recordedCheckinDayKeys ||
            (checkedInToday && cleanDay == lastCheckinDayKey)
        ) {
            return this
        }

        val lastDate = lastCheckinDayKey?.let { LocalDate.parse(it) }
        val nextStreak =
            when {
                lastDate == null -> checkinStreak + 1
                lastDate.plusDays(1L) == targetDate -> checkinStreak + 1
                targetDate.isAfter(lastDate) -> 1
                else -> checkinStreak
            }
        val nextLastDayKey =
            if (lastDate == null || targetDate.isAfter(lastDate)) {
                cleanDay
            } else {
                lastCheckinDayKey
            }

        return copy(
            checkinDays = checkinDays + 1,
            checkinStreak = nextStreak,
            checkedInToday = true,
            lastCheckinDayKey = nextLastDayKey,
            recordedCheckinDayKeys = recordedCheckinDayKeys + cleanDay,
        ).evaluateLevelProgression()
    }

    /**
     * Returns true when [targetDayKey] is eligible for a rewarded-ad makeup relative to [nowDayKey]:
     * within the past 7 days (`1..7` days prior), not already checked in, and within [monthlyMakeupCap].
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun canMakeupCheckIn(
        targetDayKey: String,
        nowDayKey: String,
    ): Boolean {
        if (monthlyMakeupUsed >= monthlyMakeupCap) return false
        val cleanTarget = targetDayKey.trim()
        val cleanNow = nowDayKey.trim()
        if (cleanTarget in recordedCheckinDayKeys || cleanTarget == lastCheckinDayKey) return false
        val targetDate = runCatching { LocalDate.parse(cleanTarget) }.getOrNull() ?: return false
        val nowDate = runCatching { LocalDate.parse(cleanNow) }.getOrNull() ?: return false
        val daysBetween = ChronoUnit.DAYS.between(targetDate, nowDate)
        return daysBetween in 1L..MAX_MAKEUP_LOOKBACK_DAYS
    }

    /**
     * Grants a retroactive check-in for [targetDayKey] via rewarded ad / E-Ink QR verification,
     * enforcing [monthlyMakeupCap] (`<= 5/month`) and the past 7-day lookback window relative to [nowDayKey].
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun makeupCheckInWithAd(
        targetDayKey: String,
        nowDayKey: String,
    ): StorageQuotaState {
        if (!canMakeupCheckIn(targetDayKey, nowDayKey)) {
            return this
        }
        val cleanTarget = targetDayKey.trim()
        val targetDate = LocalDate.parse(cleanTarget)
        val lastDate = lastCheckinDayKey?.let { LocalDate.parse(it) }
        val nextStreak =
            when {
                lastDate == null -> checkinStreak + 1
                targetDate.plusDays(1L) == lastDate -> checkinStreak + 1
                else -> maxOf(checkinStreak, 1)
            }

        return copy(
            checkinDays = checkinDays + 1,
            checkinStreak = nextStreak,
            monthlyMakeupUsed = monthlyMakeupUsed + 1,
            recordedCheckinDayKeys = recordedCheckinDayKeys + cleanTarget,
        ).evaluateLevelProgression()
    }

    /**
     * Credits offline foreground reading progress (`secondsDelta`, `chaptersDelta`), capping daily
     * credited reading time at `14_400` seconds (`240` min/day).
     *
     * Returns the updated [StorageQuotaState] paired with the updated `todayCreditedSeconds` total.
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun recordOfflineReadingProgress(
        secondsDelta: Int,
        chaptersDelta: Int,
        todayCreditedSeconds: Int = 0,
    ): Pair<StorageQuotaState, Int> {
        require(secondsDelta >= 0) { "secondsDelta must be non-negative: $secondsDelta" }
        require(chaptersDelta >= 0) { "chaptersDelta must be non-negative: $chaptersDelta" }
        require(todayCreditedSeconds >= 0) {
            "todayCreditedSeconds must be non-negative: $todayCreditedSeconds"
        }

        val remainingDailyAllowance =
            (MAX_DAILY_CREDITED_READING_SECONDS - todayCreditedSeconds).coerceAtLeast(0)
        val creditedSeconds = minOf(secondsDelta, remainingDailyAllowance)
        val totalCarrySeconds = readingSecondsCarry + creditedSeconds
        val minutesToAdd = totalCarrySeconds / SECONDS_PER_MINUTE
        val nextCarrySeconds = totalCarrySeconds % SECONDS_PER_MINUTE
        val nextTodayCreditedSeconds = todayCreditedSeconds + creditedSeconds

        val updated =
            copy(
                readingMinutes = readingMinutes + minutesToAdd,
                chaptersRead = chaptersRead + chaptersDelta,
                readingSecondsCarry = nextCarrySeconds,
            ).evaluateLevelProgression()
        return updated to nextTodayCreditedSeconds
    }

    /**
     * Increments non-reading activity counters and monotonically re-evaluates [storageLevel].
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun recordActivityCounters(
        graphDownloadsDelta: Int = 0,
        aiQueriesDelta: Int = 0,
        completedBuildsDelta: Int = 0,
    ): StorageQuotaState {
        require(graphDownloadsDelta >= 0) { "graphDownloadsDelta must be non-negative" }
        require(aiQueriesDelta >= 0) { "aiQueriesDelta must be non-negative" }
        require(completedBuildsDelta >= 0) { "completedBuildsDelta must be non-negative" }

        return copy(
            graphDownloads = graphDownloads + graphDownloadsDelta,
            aiQueries = aiQueries + aiQueriesDelta,
            completedGraphBuilds = completedGraphBuilds + completedBuildsDelta,
        ).evaluateLevelProgression()
    }

    /**
     * Checks whether downloading [bytes] stays within both the ad-boosted daily egress quota
     * and the monthly egress hard cap.
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun canDownloadBytes(bytes: Long): Boolean =
        bytes >= 0L &&
            bytes <= remainingDailyDownloadBytes &&
            bytes <= remainingMonthlyDownloadBytes

    /**
     * Consumes [bytes] from both daily and monthly download egress allowances.
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun consumeDownloadBytes(bytes: Long): StorageQuotaState {
        require(bytes >= 0L) { "bytes must be non-negative: $bytes" }
        require(canDownloadBytes(bytes)) {
            "Download exceeds remaining daily or monthly egress quota: requested=$bytes, " +
                "dailyRemaining=$remainingDailyDownloadBytes, monthlyRemaining=$remainingMonthlyDownloadBytes"
        }
        if (bytes == 0L) return this
        return copy(
            dailyDownloadUsedBytes = dailyDownloadUsedBytes + bytes,
            monthlyDownloadUsedBytes = monthlyDownloadUsedBytes + bytes,
        )
    }

    /**
     * Applies a `+50 MB` daily download egress boost from a verified rewarded ad or E-Ink QR check,
     * capped at [dailyAdBoostsCap] (`6/day`).
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun applyDownloadAdBoost(): StorageQuotaState {
        if (dailyAdBoostsUsed >= dailyAdBoostsCap) {
            return this
        }
        return copy(
            dailyDownloadAdBoostBytes = dailyDownloadAdBoostBytes + DOWNLOAD_AD_BOOST_BYTES,
            dailyAdBoostsUsed = dailyAdBoostsUsed + 1,
        )
    }

    private fun evaluateLevelProgression(): StorageQuotaState {
        val evaluated =
            StorageLevel.evaluate(
                checkinDays = checkinDays,
                readingMinutes = readingMinutes,
                chaptersRead = chaptersRead,
                graphDownloads = graphDownloads,
                aiQueries = aiQueries,
                completedGraphBuilds = completedGraphBuilds,
            )
        val monotonicLevel = if (evaluated.level > storageLevel.level) evaluated else storageLevel
        return if (monotonicLevel == storageLevel) {
            this
        } else {
            recomputeQuotas(nextLevel = monotonicLevel, nextPlan = activePlan)
        }
    }

    private fun recomputeQuotas(
        nextLevel: StorageLevel,
        nextPlan: SubscriptionPlan?,
    ): StorageQuotaState {
        val effectivePlan = nextPlan ?: SubscriptionPlan.FREE
        val nextBaseQuota = nextLevel.baseQuotaBytes
        val nextBonus = effectivePlan.bonusQuotaBytes
        val nextTotalQuota = nextBaseQuota + nextBonus
        val nextMaxFileSize = maxOf(nextLevel.maxFileSizeBytes, effectivePlan.minMaxFileSizeBytes)
        val nextDailyEgress =
            nextLevel.dailyDownloadQuotaBytes + effectivePlan.bonusDailyEgressBytes
        val nextMonthlyEgress =
            nextLevel.monthlyDownloadCapBytes + effectivePlan.bonusMonthlyEgressBytes

        val nextStatus =
            when {
                status == ClientStorageStatus.OVER_QUOTA_EVICTED && usedBytes > nextTotalQuota ->
                    ClientStorageStatus.OVER_QUOTA_EVICTED
                usedBytes > nextTotalQuota -> ClientStorageStatus.GRACE_PERIOD
                else -> ClientStorageStatus.ACTIVE
            }
        val nextGraceDays =
            if (nextStatus == ClientStorageStatus.ACTIVE) {
                DEFAULT_GRACE_DAYS
            } else {
                gracePeriodDaysRemaining
            }

        return copy(
            storageLevel = nextLevel,
            baseQuotaBytes = nextBaseQuota,
            subscriptionBonusBytes = nextBonus,
            maxFileSizeBytes = nextMaxFileSize,
            dailyDownloadQuotaBytes = nextDailyEgress,
            monthlyDownloadCapBytes = nextMonthlyEgress,
            status = nextStatus,
            gracePeriodDaysRemaining = nextGraceDays,
            activePlan = nextPlan,
        )
    }

    companion object {
        const val BYTES_PER_MB = 1_048_576L
        const val BYTES_PER_GB = 1_073_741_824L
        const val DEFAULT_USED_MB = 18
        const val DEFAULT_USED_BYTES = DEFAULT_USED_MB * BYTES_PER_MB
        const val DEFAULT_QUOTA_MB = 100
        const val PLUS_QUOTA_MB = 5_120
        const val PRO_QUOTA_MB = 20_480
        const val DEFAULT_GRACE_DAYS = 7
        const val DEFAULT_DAILY_AD_BOOSTS_CAP = 6
        const val DOWNLOAD_AD_BOOST_BYTES = 50L * BYTES_PER_MB
        const val DEFAULT_MONTHLY_MAKEUP_CAP = 5
        const val MAX_MAKEUP_LOOKBACK_DAYS = 7L
        const val MAX_DAILY_CREDITED_READING_SECONDS = 14_400
        private const val SECONDS_PER_MINUTE = 60
    }
}

/**
 * Pure, testable foreground reading metric tracker enforcing the 240 min/day (`14_400s`) cap
 * and monotonic chapter delta accumulation for offline [StorageQuotaState] progression.
 */
class OfflineReadingMetricTracker(
    initialDayKey: String? = null,
    initialTodayCreditedSeconds: Int = 0,
) {
    var currentDayKey: String? = initialDayKey
        private set

    var todayCreditedSeconds: Int = initialTodayCreditedSeconds.coerceAtLeast(0)
        private set

    private var activeSessionStartMs: Long? = null
    private var lastChapterIndex: Int? = null

    /**
     * Starts or resumes a foreground reading session at [nowMs] on calendar day [dayKey].
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun startSession(
        dayKey: String,
        nowMs: Long,
        chapterIndex: Int? = null,
    ) {
        rotateDayIfNeeded(dayKey)
        activeSessionStartMs = nowMs
        if (chapterIndex != null) {
            lastChapterIndex = chapterIndex
        }
    }

    /**
     * Flushes elapsed foreground reading seconds up to [nowMs] and optional [chaptersDelta]
     * into [state], keeping [activeSessionStartMs] anchored to [nowMs] if [keepSessionActive] is true.
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun flushSession(
        dayKey: String,
        nowMs: Long,
        state: StorageQuotaState,
        chaptersDelta: Int = 0,
        keepSessionActive: Boolean = true,
    ): StorageQuotaState {
        rotateDayIfNeeded(dayKey)
        val startMs = activeSessionStartMs
        val elapsedSeconds =
            if (startMs != null && nowMs > startMs) {
                ((nowMs - startMs) / MILLIS_PER_SECOND).toInt()
            } else {
                0
            }
        activeSessionStartMs = if (keepSessionActive) nowMs else null

        val (updatedState, nextCredited) =
            state.recordOfflineReadingProgress(
                secondsDelta = elapsedSeconds,
                chaptersDelta = chaptersDelta.coerceAtLeast(0),
                todayCreditedSeconds = todayCreditedSeconds,
            )
        todayCreditedSeconds = nextCredited
        return updatedState
    }

    /**
     * Records a forward chapter change when [chapterIndex] advances beyond [lastChapterIndex].
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun recordChapterReached(
        dayKey: String,
        chapterIndex: Int,
        state: StorageQuotaState,
    ): StorageQuotaState {
        rotateDayIfNeeded(dayKey)
        val previous = lastChapterIndex
        lastChapterIndex = if (previous == null) chapterIndex else maxOf(previous, chapterIndex)
        if (previous == null || chapterIndex <= previous) {
            return state
        }
        val (updatedState, nextCredited) =
            state.recordOfflineReadingProgress(
                secondsDelta = 0,
                chaptersDelta = 1,
                todayCreditedSeconds = todayCreditedSeconds,
            )
        todayCreditedSeconds = nextCredited
        return updatedState
    }

    private fun rotateDayIfNeeded(dayKey: String) {
        val clean = dayKey.trim()
        if (currentDayKey != clean) {
            currentDayKey = clean
            todayCreditedSeconds = 0
        }
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L
    }
}

/**
 * Represents a community-validated GraphPack offer in the Shared GraphPack Marketplace (ADR-040).
 */
data class SharedPackMarketplaceOffer(
    val packId: String,
    val matchTier: EditionMatchTier,
    val downloadCostCredits: Int = DEFAULT_DOWNLOAD_COST_CREDITS,
    val buildCostCredits: Int = DEFAULT_BUILD_COST_CREDITS,
    val creatorRoyaltyCredits: Int = DEFAULT_CREATOR_ROYALTY_CREDITS,
    val entityCount: Int = DEFAULT_ENTITY_COUNT,
    val assertionCount: Int = DEFAULT_ASSERTION_COUNT,
    val unlocked: Boolean = false,
    val publishedByCurrentUser: Boolean = false,
) {
    companion object {
        const val DEFAULT_DOWNLOAD_COST_CREDITS = 3
        const val DEFAULT_BUILD_COST_CREDITS = 20
        const val REFINED_BUILD_COST_CREDITS = 30
        const val DEFAULT_CREATOR_ROYALTY_CREDITS = 1
        const val DEFAULT_ENTITY_COUNT = 24
        const val DEFAULT_ASSERTION_COUNT = 68
    }
}

/**
 * Client-side Three-Bucket Credit Wallet (`promo_credits -> sub_credits -> paid_credits`)
 * with E-Ink Cross-Device QR Ad Reward and Creator Royalty accounting (ADR-030 & ADR-040).
 */
data class ClientCreditWallet(
    val promoCredits: Int = DEFAULT_PROMO_CREDITS,
    val subCredits: Int = 0,
    val paidCredits: Int = 0,
    val dailyAdCreditsClaimed: Int = 0,
    val creatorRoyaltyEarned: Int = 0,
) {
    init {
        require(promoCredits >= 0) { "promoCredits must be non-negative" }
        require(subCredits >= 0) { "subCredits must be non-negative" }
        require(paidCredits >= 0) { "paidCredits must be non-negative" }
        require(dailyAdCreditsClaimed >= 0) { "dailyAdCreditsClaimed must be non-negative" }
        require(creatorRoyaltyEarned >= 0) { "creatorRoyaltyEarned must be non-negative" }
    }

    val totalCredits: Int
        get() = promoCredits + subCredits + paidCredits

    fun canAfford(costCredits: Int): Boolean = costCredits >= 0 && costCredits <= totalCredits

    /**
     * Deducts [costCredits] strictly in the order:
     * `promoCredits` -> `subCredits` -> `paidCredits`.
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun deductCredits(costCredits: Int): ClientCreditWallet {
        require(costCredits >= 0) { "costCredits must be non-negative: $costCredits" }
        require(costCredits <= totalCredits) {
            "Insufficient credits: required=$costCredits, available=$totalCredits"
        }
        var remaining = costCredits

        val fromPromo = minOf(promoCredits, remaining)
        remaining -= fromPromo

        val fromSub = minOf(subCredits, remaining)
        remaining -= fromSub

        val fromPaid = minOf(paidCredits, remaining)
        remaining -= fromPaid

        return copy(
            promoCredits = promoCredits - fromPromo,
            subCredits = subCredits - fromSub,
            paidCredits = paidCredits - fromPaid,
        )
    }

    /**
     * Credits up to [rewardCredits] into [promoCredits] for a completed rewarded ad or
     * E-Ink cross-device QR verification, capped at [dailyCapCredits] per day.
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun claimRewardedAd(
        rewardCredits: Int = DEFAULT_AD_REWARD_CREDITS,
        dailyCapCredits: Int = DEFAULT_DAILY_AD_CAP_CREDITS,
    ): ClientCreditWallet {
        require(rewardCredits >= 0) { "rewardCredits must be non-negative" }
        require(dailyCapCredits >= 0) { "dailyCapCredits must be non-negative" }
        val remainingDailyAllowance = (dailyCapCredits - dailyAdCreditsClaimed).coerceAtLeast(0)
        val granted = minOf(rewardCredits, remainingDailyAllowance)
        if (granted <= 0) {
            return this
        }
        return copy(
            promoCredits = promoCredits + granted,
            dailyAdCreditsClaimed = dailyAdCreditsClaimed + granted,
        )
    }

    /**
     * Credits creator royalty into [promoCredits] when another reader unlocks the user's
     * shared GraphPack, capped at 200% (`2 * buildCostCredits`).
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun recordCreatorRoyalty(
        royaltyCredits: Int = DEFAULT_ROYALTY_CREDITS,
        buildCostCredits: Int = DEFAULT_BUILD_COST_CREDITS,
    ): ClientCreditWallet {
        require(royaltyCredits >= 0) { "royaltyCredits must be non-negative" }
        require(buildCostCredits >= 0) { "buildCostCredits must be non-negative" }
        val maxRoyaltyCap = buildCostCredits * ROYALTY_CAP_MULTIPLIER
        val remainingCap = (maxRoyaltyCap - creatorRoyaltyEarned).coerceAtLeast(0)
        val granted = minOf(royaltyCredits, remainingCap)
        if (granted <= 0) {
            return this
        }
        return copy(
            promoCredits = promoCredits + granted,
            creatorRoyaltyEarned = creatorRoyaltyEarned + granted,
        )
    }

    /**
     * Grants the monthly `subCredits` allowance when activating [plan] (`+150 推理点` for Plus
     * `¥12/月`, `+600 推理点` for Pro `¥24/月`).
     */
    fun activateSubscriptionPlan(plan: SubscriptionPlan): ClientCreditWallet =
        copy(
            subCredits = subCredits + plan.monthlySubCredits,
        )

    /**
     * Credits a standalone permanent top-up [pack] into [paidCredits] (`¥6 = 80 推理点`,
     * `¥18 = 300 推理点`, `¥45 = 900 推理点`).
     */
    fun purchaseTopUpPack(pack: TopUpPack): ClientCreditWallet =
        copy(
            paidCredits = paidCredits + pack.credits,
        )

    /**
     * Generates a deterministic E-Ink cross-device QR challenge URI so E-Ink devices can offload
     * rewarded video ads to a mobile phone without battery drain or ghosting (ADR-030 §5).
     */
    fun generateEInkQrChallengePayload(
        anonymousOrUserId: String,
        sessionNonce: String = DEFAULT_QR_SESSION_NONCE,
    ): String {
        val cleanId = anonymousOrUserId.trim().ifEmpty { "guest" }
        val cleanNonce = sessionNonce.trim().ifEmpty { DEFAULT_QR_SESSION_NONCE }
        val reward = DEFAULT_AD_REWARD_CREDITS
        return "mixu://ad-reward/qr?uid=$cleanId&nonce=$cleanNonce&reward=$reward"
    }

    /**
     * Generates a deterministic 21x21 QR-like monochrome bit matrix from [payload] with standard
     * 7x7 finder patterns at the three corners for crisp E-Ink rendering.
     *
     * Time complexity: O(size^2). Space complexity: O(size^2).
     */
    fun generateQrMatrix(
        payload: String,
        size: Int = DEFAULT_QR_MATRIX_SIZE,
    ): List<List<Boolean>> {
        val gridDim = size.coerceAtLeast(MIN_QR_MATRIX_SIZE)
        val matrix = Array(gridDim) { BooleanArray(gridDim) }

        fun placeFinderPattern(
            topRow: Int,
            leftCol: Int,
        ) {
            for (r in 0 until FINDER_SIZE) {
                for (c in 0 until FINDER_SIZE) {
                    val isBorder = r == 0 || r == FINDER_SIZE - 1 || c == 0 || c == FINDER_SIZE - 1
                    val isInnerCore = r in 2..4 && c in 2..4
                    matrix[topRow + r][leftCol + c] = isBorder || isInnerCore
                }
            }
        }

        placeFinderPattern(0, 0)
        placeFinderPattern(0, gridDim - FINDER_SIZE)
        placeFinderPattern(gridDim - FINDER_SIZE, 0)

        var hash = FNV_OFFSET_BASIS
        for (ch in payload) {
            hash = (hash xor ch.code.toLong()) * FNV_PRIME
        }

        for (r in 0 until gridDim) {
            for (c in 0 until gridDim) {
                val inTopLeft = r <= FINDER_SIZE && c <= FINDER_SIZE
                val inTopRight = r <= FINDER_SIZE && c >= gridDim - FINDER_SIZE - 1
                val inBottomLeft = r >= gridDim - FINDER_SIZE - 1 && c <= FINDER_SIZE
                if (inTopLeft || inTopRight || inBottomLeft) {
                    continue
                }
                val cellSeed = hash xor ((r * 31L + c * 17L) * FNV_PRIME)
                matrix[r][c] = (cellSeed and 1L) == 0L
            }
        }

        return matrix.map { it.toList() }
    }

    companion object {
        const val CURRENCY_NAME_ZH = "推理点"
        const val DEFAULT_PROMO_CREDITS = 60
        const val DEFAULT_AD_REWARD_CREDITS = 5
        const val DEFAULT_DAILY_AD_CAP_CREDITS = 30
        const val DEFAULT_ROYALTY_CREDITS = 1
        const val DEFAULT_BUILD_COST_CREDITS = 20
        const val REFINED_BUILD_COST_CREDITS = 30
        const val ROYALTY_CAP_MULTIPLIER = 2
        const val DEFAULT_QR_SESSION_NONCE = "eink-qr-v1"
        const val DEFAULT_QR_MATRIX_SIZE = 21
        private const val MIN_QR_MATRIX_SIZE = 21
        private const val FINDER_SIZE = 7
        private const val FNV_OFFSET_BASIS = -3750763034362895579L
        private const val FNV_PRIME = 1099511628211L
    }
}
