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
    fun `SubscriptionPlan Plus 12 CNY and Pro 24 CNY grant subCredits and storage quota`() {
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
        assertEquals(5_120, plusStorage.quotaMb)
        assertEquals(SubscriptionPlan.PLUS_MONTHLY, plusStorage.activePlan)

        val proWallet = plusWallet.activateSubscriptionPlan(SubscriptionPlan.PRO_MONTHLY)
        val proStorage = plusStorage.applySubscriptionPlan(SubscriptionPlan.PRO_MONTHLY)
        assertEquals(24, SubscriptionPlan.PRO_MONTHLY.monthlyPriceCny)
        assertEquals(750, proWallet.subCredits)
        assertEquals(810, proWallet.totalCredits)
        assertEquals(20_480, proStorage.quotaMb)
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
}
