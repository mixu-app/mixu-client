// SPDX-License-Identifier: MPL-2.0

package app.mixu.client.billing

/**
 * Cloud storage lifecycle status enforcing the 7-Day Grace Period before cold eviction (ADR-030).
 */
enum class ClientStorageStatus {
    ACTIVE,
    GRACE_PERIOD,
    OVER_QUOTA_EVICTED,
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
 * Represents the reader's cloud storage quota and 7-day grace period policy state (ADR-030).
 */
data class StorageQuotaState(
    val usedMb: Int = DEFAULT_USED_MB,
    val quotaMb: Int = DEFAULT_QUOTA_MB,
    val status: ClientStorageStatus = ClientStorageStatus.ACTIVE,
    val gracePeriodDaysRemaining: Int = DEFAULT_GRACE_DAYS,
) {
    /**
     * New EPUB uploads are permitted only when storage status is [ClientStorageStatus.ACTIVE]
     * and current usage is below the quota ceiling.
     */
    fun canUploadNewEpub(): Boolean = status == ClientStorageStatus.ACTIVE && usedMb < quotaMb

    /**
     * Existing books, reading progress, and unlocked GraphPacks remain downloadable even during
     * [ClientStorageStatus.GRACE_PERIOD] so user assets are never held hostage.
     */
    fun canDownloadExistingAssets(): Boolean = true

    companion object {
        const val DEFAULT_USED_MB = 18
        const val DEFAULT_QUOTA_MB = 100
        const val DEFAULT_GRACE_DAYS = 7
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
        const val DEFAULT_BUILD_COST_CREDITS = 60
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
        const val DEFAULT_PROMO_CREDITS = 60
        const val DEFAULT_AD_REWARD_CREDITS = 3
        const val DEFAULT_DAILY_AD_CAP_CREDITS = 30
        const val DEFAULT_ROYALTY_CREDITS = 1
        const val DEFAULT_BUILD_COST_CREDITS = 60
        const val ROYALTY_CAP_MULTIPLIER = 2
        const val DEFAULT_QR_SESSION_NONCE = "eink-qr-v1"
        const val DEFAULT_QR_MATRIX_SIZE = 21
        private const val MIN_QR_MATRIX_SIZE = 21
        private const val FINDER_SIZE = 7
        private const val FNV_OFFSET_BASIS = -3750763034362895579L
        private const val FNV_PRIME = 1099511628211L
    }
}
