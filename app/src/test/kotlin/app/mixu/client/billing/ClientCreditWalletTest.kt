// SPDX-License-Identifier: MPL-2.0

package app.mixu.client.billing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientCreditWalletTest {
    @Test
    fun `deductCredits strictly follows promo then sub then paid bucket order`() {
        val initial =
            ClientCreditWallet(
                promoCredits = 10,
                subCredits = 20,
                paidCredits = 30,
            )
        assertEquals(60, initial.totalCredits)
        assertTrue(initial.canAfford(60))
        assertFalse(initial.canAfford(61))

        // Deduct 6 credits -> only promoCredits reduced
        val step1 = initial.deductCredits(6)
        assertEquals(4, step1.promoCredits)
        assertEquals(20, step1.subCredits)
        assertEquals(30, step1.paidCredits)

        // Deduct 14 credits -> 4 from promo, 10 from sub
        val step2 = step1.deductCredits(14)
        assertEquals(0, step2.promoCredits)
        assertEquals(10, step2.subCredits)
        assertEquals(30, step2.paidCredits)

        // Deduct 25 credits -> 10 from sub, 15 from paid
        val step3 = step2.deductCredits(25)
        assertEquals(0, step3.promoCredits)
        assertEquals(0, step3.subCredits)
        assertEquals(15, step3.paidCredits)
        assertEquals(15, step3.totalCredits)
    }

    @Test
    fun `deductCredits throws when cost exceeds totalCredits`() {
        val wallet = ClientCreditWallet(promoCredits = 5, subCredits = 0, paidCredits = 0)
        assertFailsWith<IllegalArgumentException> {
            wallet.deductCredits(6)
        }
    }

    @Test
    fun `claimRewardedAd grants 5 promo credits per ad up to 6 per day (30 credits cap)`() {
        var wallet = ClientCreditWallet(promoCredits = 60, dailyAdCreditsClaimed = 0)

        repeat(6) {
            wallet = wallet.claimRewardedAd()
        }
        assertEquals(90, wallet.promoCredits)
        assertEquals(30, wallet.dailyAdCreditsClaimed)

        // 7th claim on the same day must be capped at 0 additional credits
        val capped = wallet.claimRewardedAd()
        assertEquals(90, capped.promoCredits)
        assertEquals(30, capped.dailyAdCreditsClaimed)
    }

    @Test
    fun `recordCreatorRoyalty accumulates 1 promo credit and caps at 200 percent`() {
        var wallet = ClientCreditWallet(promoCredits = 0, creatorRoyaltyEarned = 119)

        // 120th credit (200% of 60 build cost) succeeds
        wallet = wallet.recordCreatorRoyalty(royaltyCredits = 1, buildCostCredits = 60)
        assertEquals(1, wallet.promoCredits)
        assertEquals(120, wallet.creatorRoyaltyEarned)

        // 121st credit is capped
        val afterCap = wallet.recordCreatorRoyalty(royaltyCredits = 1, buildCostCredits = 60)
        assertEquals(1, afterCap.promoCredits)
        assertEquals(120, afterCap.creatorRoyaltyEarned)
    }

    @Test
    fun `StorageQuotaState enforces ACTIVE vs GRACE_PERIOD upload and download permissions`() {
        val activeState =
            StorageQuotaState(
                usedMb = 18,
                quotaMb = 100,
                status = ClientStorageStatus.ACTIVE,
            )
        assertTrue(activeState.canUploadNewEpub())
        assertTrue(activeState.canDownloadExistingAssets())

        val graceState =
            activeState.copy(
                status = ClientStorageStatus.GRACE_PERIOD,
                gracePeriodDaysRemaining = 7,
            )
        assertFalse(graceState.canUploadNewEpub())
        assertTrue(graceState.canDownloadExistingAssets())
    }

    @Test
    fun `generateEInkQrChallengePayload and generateQrMatrix are deterministic`() {
        val wallet = ClientCreditWallet()
        val payload1 = wallet.generateEInkQrChallengePayload("anon-1234", "eink-qr-v1")
        val payload2 = wallet.generateEInkQrChallengePayload("anon-1234", "eink-qr-v1")
        assertEquals("mixu://ad-reward/qr?uid=anon-1234&nonce=eink-qr-v1&reward=5", payload1)
        assertEquals(payload1, payload2)

        val matrix1 = wallet.generateQrMatrix(payload1)
        val matrix2 = wallet.generateQrMatrix(payload2)
        assertEquals(21, matrix1.size)
        assertEquals(21, matrix1.first().size)
        assertEquals(matrix1, matrix2)
        // Top-left finder corner (0,0) and center (3,3) must be true
        assertTrue(matrix1[0][0])
        assertTrue(matrix1[3][3])
    }

    @Test
    fun `SubscriptionPlan Plus and Pro grant subCredits and additive storage quota`() {
        assertEquals("推理点", ClientCreditWallet.CURRENCY_NAME_ZH)
        assertEquals(20, ClientCreditWallet.DEFAULT_BUILD_COST_CREDITS)
        assertEquals(30, ClientCreditWallet.REFINED_BUILD_COST_CREDITS)

        val wallet = ClientCreditWallet(promoCredits = 60, subCredits = 0, paidCredits = 0)
        val storage = StorageQuotaState()
        assertEquals(100, storage.quotaMb)
        assertEquals(SubscriptionPlan.FREE, storage.activePlan)

        val plusWallet = wallet.activateSubscriptionPlan(SubscriptionPlan.PLUS_MONTHLY)
        val plusStorage = storage.applySubscriptionPlan(SubscriptionPlan.PLUS_MONTHLY)
        assertEquals(12, SubscriptionPlan.PLUS_MONTHLY.monthlyPriceCny)
        assertEquals(150, plusWallet.subCredits)
        assertEquals(210, plusWallet.totalCredits)
        // Additive: 100 MB (LV1) + 5 GB (5,120 MB) = 5,220 MB
        assertEquals(5_220, plusStorage.quotaMb)
        assertEquals(300, plusStorage.maxFileSizeMb)
        assertEquals(50L * 1_048_576L + 2L * 1_073_741_824L, plusStorage.dailyDownloadQuotaBytes)
        assertEquals(600L * 1_048_576L + 20L * 1_073_741_824L, plusStorage.monthlyDownloadCapBytes)
        assertEquals(SubscriptionPlan.PLUS_MONTHLY, plusStorage.activePlan)

        val proWallet = plusWallet.activateSubscriptionPlan(SubscriptionPlan.PRO_MONTHLY)
        val proStorage = plusStorage.applySubscriptionPlan(SubscriptionPlan.PRO_MONTHLY)
        assertEquals(24, SubscriptionPlan.PRO_MONTHLY.monthlyPriceCny)
        assertEquals(750, proWallet.subCredits)
        assertEquals(810, proWallet.totalCredits)
        // Additive: 100 MB (LV1) + 20 GB (20,480 MB) = 20,580 MB
        assertEquals(20_580, proStorage.quotaMb)
        assertEquals(500, proStorage.maxFileSizeMb)
        assertEquals(50L * 1_048_576L + 5L * 1_073_741_824L, proStorage.dailyDownloadQuotaBytes)
        assertEquals(600L * 1_048_576L + 50L * 1_073_741_824L, proStorage.monthlyDownloadCapBytes)
        assertEquals(SubscriptionPlan.PRO_MONTHLY, proStorage.activePlan)
    }

    @Test
    fun `TopUpPack ¥6 ¥18 and ¥45 packs credit permanent paidCredits`() {
        var wallet = ClientCreditWallet(promoCredits = 10, subCredits = 0, paidCredits = 0)
        wallet = wallet.purchaseTopUpPack(TopUpPack.PACK_6_CNY)
        assertEquals(80, wallet.paidCredits)
        wallet = wallet.purchaseTopUpPack(TopUpPack.PACK_18_CNY)
        assertEquals(380, wallet.paidCredits)
        wallet = wallet.purchaseTopUpPack(TopUpPack.PACK_45_CNY)
        assertEquals(1_280, wallet.paidCredits)
        assertEquals(1_290, wallet.totalCredits)
    }

    @Test
    fun `StorageLevel LV1 through LV5 exact quotas and monotonic progression thresholds`() {
        assertEquals(104_857_600L, StorageLevel.LV1.baseQuotaBytes)
        assertEquals(15_728_640L, StorageLevel.LV1.maxFileSizeBytes)
        assertEquals(52_428_800L, StorageLevel.LV1.dailyDownloadQuotaBytes)
        assertEquals(629_145_600L, StorageLevel.LV1.monthlyDownloadCapBytes)

        assertEquals(209_715_200L, StorageLevel.LV2.baseQuotaBytes)
        assertEquals(31_457_280L, StorageLevel.LV2.maxFileSizeBytes)
        assertEquals(104_857_600L, StorageLevel.LV2.dailyDownloadQuotaBytes)
        assertEquals(1_258_291_200L, StorageLevel.LV2.monthlyDownloadCapBytes)

        assertEquals(524_288_000L, StorageLevel.LV3.baseQuotaBytes)
        assertEquals(62_914_560L, StorageLevel.LV3.maxFileSizeBytes)
        assertEquals(209_715_200L, StorageLevel.LV3.dailyDownloadQuotaBytes)
        assertEquals(2_621_440_000L, StorageLevel.LV3.monthlyDownloadCapBytes)

        assertEquals(1_073_741_824L, StorageLevel.LV4.baseQuotaBytes)
        assertEquals(104_857_600L, StorageLevel.LV4.maxFileSizeBytes)
        assertEquals(367_001_600L, StorageLevel.LV4.dailyDownloadQuotaBytes)
        assertEquals(4_718_592_000L, StorageLevel.LV4.monthlyDownloadCapBytes)

        assertEquals(2_147_483_648L, StorageLevel.LV5.baseQuotaBytes)
        assertEquals(209_715_200L, StorageLevel.LV5.maxFileSizeBytes)
        assertEquals(629_145_600L, StorageLevel.LV5.dailyDownloadQuotaBytes)
        assertEquals(8_589_934_592L, StorageLevel.LV5.monthlyDownloadCapBytes)

        // LV1 -> LV2 requires checkinDays >= 3 && graphDownloads >= 1
        var state =
            StorageQuotaState()
                .recordCheckIn("2026-11-01")
                .recordCheckIn("2026-11-02")
                .recordCheckIn("2026-11-03")
        assertEquals(StorageLevel.LV1, state.storageLevel)
        state = state.recordActivityCounters(graphDownloadsDelta = 1)
        assertEquals(StorageLevel.LV2, state.storageLevel)
        assertEquals(200, state.baseQuotaMb)

        // LV3 via chaptersRead >= 15 (even with readingMinutes < 180) + checkinDays >= 14 + graphDownloads >= 3 + aiQueries >= 5
        state =
            state.copy(
                checkinDays = 14,
                chaptersRead = 15,
                graphDownloads = 3,
                aiQueries = 4,
            )
        assertEquals(StorageLevel.LV2, state.storageLevel)
        state = state.recordActivityCounters(aiQueriesDelta = 1)
        assertEquals(StorageLevel.LV3, state.storageLevel)
        assertEquals(500, state.baseQuotaMb)

        // LV4 requires checkinDays >= 45 && (readingMinutes >= 720 || chaptersRead >= 60) && graphDownloads >= 6 && aiQueries >= 20 && completedGraphBuilds >= 1
        state =
            state
                .copy(checkinDays = 45, readingMinutes = 720, graphDownloads = 6, aiQueries = 20)
                .recordActivityCounters(completedBuildsDelta = 1)
        assertEquals(StorageLevel.LV4, state.storageLevel)
        assertEquals(1_024, state.baseQuotaMb)

        // LV5 requires checkinDays >= 90 && (readingMinutes >= 1800 || chaptersRead >= 150) && aiQueries >= 50 && completedGraphBuilds >= 3
        state =
            state
                .copy(checkinDays = 90, chaptersRead = 150, aiQueries = 50)
                .recordActivityCounters(completedBuildsDelta = 2)
        assertEquals(StorageLevel.LV5, state.storageLevel)
        assertEquals(2_048, state.baseQuotaMb)

        // Monotonic: even if counters were lower in a synthetic copy, level never downgrades
        val monotonic = state.copy(checkinDays = 1).recordActivityCounters(aiQueriesDelta = 1)
        assertEquals(StorageLevel.LV5, monotonic.storageLevel)
    }

    @Test
    fun `FREE fallback enters GRACE_PERIOD only when usedBytes exceeds level quota`() {
        // LV3 user has 500 MB base quota; using 400 MB stays ACTIVE when downgrading from PLUS to FREE
        val lv3PlusWithinBase =
            StorageQuotaState(
                storageLevel = StorageLevel.LV3,
                usedBytes = 400L * StorageQuotaState.BYTES_PER_MB,
            ).applySubscriptionPlan(SubscriptionPlan.PLUS_MONTHLY)
        assertEquals(500 + 5_120, lv3PlusWithinBase.quotaMb)
        assertEquals(ClientStorageStatus.ACTIVE, lv3PlusWithinBase.status)

        val downgradedActive = lv3PlusWithinBase.applySubscriptionPlan(SubscriptionPlan.FREE)
        assertEquals(500, downgradedActive.quotaMb)
        assertEquals(0L, downgradedActive.subscriptionBonusBytes)
        assertEquals(ClientStorageStatus.ACTIVE, downgradedActive.status)
        assertTrue(downgradedActive.canUploadFileBytes(50L * StorageQuotaState.BYTES_PER_MB))

        // Using 600 MB (> 500 MB LV3 base) enters GRACE_PERIOD when downgrading to FREE
        val lv3PlusOverBase =
            lv3PlusWithinBase.copy(usedBytes = 600L * StorageQuotaState.BYTES_PER_MB)
        val downgradedGrace = lv3PlusOverBase.applySubscriptionPlan(SubscriptionPlan.FREE)
        assertEquals(ClientStorageStatus.GRACE_PERIOD, downgradedGrace.status)
        assertFalse(downgradedGrace.canUploadFileBytes(1_048_576L))
        assertTrue(downgradedGrace.canDownloadExistingAssets())
    }

    @Test
    fun `canUploadFileBytes enforces status, maxFileSizeBytes, and remaining quota`() {
        val state =
            StorageQuotaState(
                storageLevel = StorageLevel.LV1,
                usedBytes = 90L * StorageQuotaState.BYTES_PER_MB,
            )
        // 10 MB remaining, maxFile = 15 MB
        assertTrue(state.canUploadFileBytes(10L * StorageQuotaState.BYTES_PER_MB))
        // Exceeds remaining 10 MB quota
        assertFalse(state.canUploadFileBytes(11L * StorageQuotaState.BYTES_PER_MB))
        // Exceeds 15 MB single-file cap even when quota is free
        val emptyLv1 = state.copy(usedBytes = 0L)
        assertTrue(emptyLv1.canUploadFileBytes(15L * StorageQuotaState.BYTES_PER_MB))
        assertFalse(emptyLv1.canUploadFileBytes(15L * StorageQuotaState.BYTES_PER_MB + 1L))
        assertFalse(emptyLv1.canUploadFileBytes(0L))
    }

    @Test
    fun `check-in idempotency and rewarded-ad makeup 5 per month and 7-day window`() {
        var state = StorageQuotaState()
        state = state.recordCheckIn("2026-11-10")
        assertEquals(1, state.checkinDays)
        assertEquals(1, state.checkinStreak)
        assertTrue(state.checkedInToday)

        // Same day again is idempotent
        state = state.recordCheckIn("2026-11-10")
        assertEquals(1, state.checkinDays)
        assertEquals(1, state.checkinStreak)

        // Next consecutive day increments streak
        state = state.recordCheckIn("2026-11-11")
        assertEquals(2, state.checkinDays)
        assertEquals(2, state.checkinStreak)

        // Gap resets streak to 1
        state = state.recordCheckIn("2026-11-13")
        assertEquals(3, state.checkinDays)
        assertEquals(1, state.checkinStreak)

        // Outside 7-day window (> 7 days before 2026-11-13 -> 2026-11-05 is 8 days ago) or today is rejected
        assertFalse(state.canMakeupCheckIn("2026-11-05", "2026-11-13"))
        assertFalse(state.canMakeupCheckIn("2026-11-13", "2026-11-13"))
        assertFalse(state.canMakeupCheckIn("2026-11-14", "2026-11-13"))
        val rejected = state.makeupCheckInWithAd("2026-11-05", "2026-11-13")
        assertEquals(3, rejected.checkinDays)
        assertEquals(0, rejected.monthlyMakeupUsed)

        // Up to 5 valid makeups within past 7 days (days 6, 7, 8, 9, 12)
        val validMissedDays =
            listOf("2026-11-12", "2026-11-09", "2026-11-08", "2026-11-07", "2026-11-06")
        for (missedDay in validMissedDays) {
            assertTrue(state.canMakeupCheckIn(missedDay, "2026-11-13"))
            state = state.makeupCheckInWithAd(missedDay, "2026-11-13")
        }
        assertEquals(8, state.checkinDays)
        assertEquals(5, state.monthlyMakeupUsed)

        // 6th makeup in same month is blocked by monthly cap = 5
        val stateWithAnotherDay =
            state.copy(
                recordedCheckinDayKeys =
                    state.recordedCheckinDayKeys - "2026-11-12",
            )
        assertFalse(stateWithAnotherDay.canMakeupCheckIn("2026-11-12", "2026-11-13"))
        val blocked = stateWithAnotherDay.makeupCheckInWithAd("2026-11-12", "2026-11-13")
        assertEquals(8, blocked.checkinDays)
        assertEquals(5, blocked.monthlyMakeupUsed)
    }

    @Test
    fun `offline reading progress caps at 14400s per day and tracker rotates across days`() {
        val (s1, credited1) =
            StorageQuotaState().recordOfflineReadingProgress(
                secondsDelta = 10_000,
                chaptersDelta = 2,
                todayCreditedSeconds = 0,
            )
        assertEquals(10_000, credited1)
        assertEquals(166, s1.readingMinutes) // 10000 / 60 = 166 min, 40s carry
        assertEquals(2, s1.chaptersRead)

        // Additional 8_000s only credits remaining 4_400s up to 14_400s (total 240 min)
        val (s2, credited2) =
            s1.recordOfflineReadingProgress(
                secondsDelta = 8_000,
                chaptersDelta = 1,
                todayCreditedSeconds = credited1,
            )
        assertEquals(14_400, credited2)
        assertEquals(240, s2.readingMinutes)
        assertEquals(3, s2.chaptersRead)

        // Further reading on same day adds 0 minutes
        val (s3, credited3) =
            s2.recordOfflineReadingProgress(
                secondsDelta = 1_200,
                chaptersDelta = 0,
                todayCreditedSeconds = credited2,
            )
        assertEquals(14_400, credited3)
        assertEquals(240, s3.readingMinutes)

        // OfflineReadingMetricTracker integrates session time and day rotation
        val tracker = OfflineReadingMetricTracker()
        tracker.startSession(dayKey = "2026-11-12", nowMs = 1_000L, chapterIndex = 0)
        var tracked =
            tracker.flushSession(
                dayKey = "2026-11-12",
                nowMs = 1_000L + 20_000_000L,
                state = StorageQuotaState(),
            )
        assertEquals(240, tracked.readingMinutes)
        assertEquals(14_400, tracker.todayCreditedSeconds)

        tracked =
            tracker.recordChapterReached(dayKey = "2026-11-12", chapterIndex = 1, state = tracked)
        assertEquals(1, tracked.chaptersRead)

        // Next day resets daily cap
        tracker.startSession(dayKey = "2026-11-13", nowMs = 50_000_000L, chapterIndex = 1)
        tracked =
            tracker.flushSession(
                dayKey = "2026-11-13",
                nowMs = 50_000_000L + 3_600_000L,
                state = tracked,
            )
        assertEquals(300, tracked.readingMinutes)
        assertEquals(3_600, tracker.todayCreditedSeconds)
    }

    @Test
    fun `download egress consumption and ad boost +50 MB up to 6 per day`() {
        var state = StorageQuotaState(storageLevel = StorageLevel.LV1)
        val mb = StorageQuotaState.BYTES_PER_MB

        assertTrue(state.canDownloadBytes(50L * mb))
        assertFalse(state.canDownloadBytes(51L * mb))
        state = state.consumeDownloadBytes(45L * mb)
        assertEquals(5L * mb, state.remainingDailyDownloadBytes)
        assertEquals(555L * mb, state.remainingMonthlyDownloadBytes)

        assertFailsWith<IllegalArgumentException> {
            state.consumeDownloadBytes(10L * mb)
        }

        // Apply 6 ad boosts (+300 MB) -> 7th is capped
        repeat(6) {
            state = state.applyDownloadAdBoost()
        }
        assertEquals(6, state.dailyAdBoostsUsed)
        assertEquals(300L * mb, state.dailyDownloadAdBoostBytes)
        assertEquals(305L * mb, state.remainingDailyDownloadBytes)

        val capped = state.applyDownloadAdBoost()
        assertEquals(6, capped.dailyAdBoostsUsed)
        assertEquals(300L * mb, capped.dailyDownloadAdBoostBytes)

        state = state.consumeDownloadBytes(100L * mb)
        assertEquals(145L * mb, state.dailyDownloadUsedBytes)
        assertEquals(145L * mb, state.monthlyDownloadUsedBytes)
    }
}
