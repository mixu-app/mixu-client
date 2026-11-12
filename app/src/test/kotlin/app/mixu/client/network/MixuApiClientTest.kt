// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.network

import app.mixu.client.billing.ClientCreditWallet
import app.mixu.client.billing.ClientStorageStatus
import app.mixu.client.billing.EditionMatchTier
import app.mixu.client.billing.StorageLevel
import app.mixu.client.billing.StorageQuotaState
import app.mixu.client.billing.SubscriptionPlan
import app.mixu.client.billing.TopUpPack
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MixuApiClientTest {
    @Test
    fun `fetchWallet parses server minor and credit fields and falls back when offline`() {
        val onlineClient =
            MixuApiClient(
                baseUrl = MixuApiClient.DEFAULT_EMULATOR_LOOPBACK,
                transport = { req ->
                    assertEquals("GET", req.method)
                    assertEquals("http://10.0.2.2:8080/v1/billing/wallet", req.url)
                    assertEquals("reader-1", req.headers[MixuApiClient.HEADER_USER_ID])
                    MixuHttpResponse(
                        statusCode = 200,
                        body =
                            """
                            {
                              "promo_credits_minor": 4500,
                              "sub_credits_minor": 12000,
                              "paid_credits_minor": 30000,
                              "daily_ad_credits_claimed": 6,
                              "creator_royalty_earned": 12
                            }
                            """.trimIndent(),
                    )
                },
            )

        val wallet = onlineClient.fetchWallet("reader-1")
        assertEquals(45, wallet.promoCredits)
        assertEquals(120, wallet.subCredits)
        assertEquals(300, wallet.paidCredits)
        assertEquals(6, wallet.dailyAdCreditsClaimed)
        assertEquals(12, wallet.creatorRoyaltyEarned)

        val offlineClient =
            MixuApiClient(
                transport = { throw IOException("Network unreachable") },
            )
        val fallbackWallet = offlineClient.fetchWallet("reader-1")
        assertEquals(ClientCreditWallet.DEFAULT_PROMO_CREDITS, fallbackWallet.promoCredits)
        assertEquals(0, fallbackWallet.subCredits)
        assertEquals(0, fallbackWallet.paidCredits)
    }

    @Test
    fun `requestEInkQrAdChallenge and verifyAdReward support online and offline flows`() {
        val onlineClient =
            MixuApiClient(
                transport = { req ->
                    when (req.url.substringAfterLast('/')) {
                        "qr-challenge" ->
                            MixuHttpResponse(
                                statusCode = 200,
                                body =
                                    """
                                    {
                                      "challenge_token": "srv-token-99",
                                      "qr_payload": "mixu://ad-reward/qr?uid=u1&nonce=srv-99&reward=3",
                                      "expires_at_epoch_seconds": 1900000300,
                                      "reward_credits": 3,
                                      "offline_fallback": false
                                    }
                                    """.trimIndent(),
                            )
                        "verify" ->
                            MixuHttpResponse(
                                statusCode = 200,
                                body =
                                    """
                                    {
                                      "promo_credits": 65,
                                      "sub_credits": 0,
                                      "paid_credits": 0,
                                      "daily_ad_credits_claimed": 5,
                                      "creator_royalty_earned": 0
                                    }
                                    """.trimIndent(),
                            )
                        else -> MixuHttpResponse(statusCode = 404, body = "")
                    }
                },
            )

        val challenge = onlineClient.requestEInkQrAdChallenge("u1", "boox-leaf2")
        assertEquals("srv-token-99", challenge.challengeToken)
        assertFalse(challenge.offlineFallback)
        assertTrue(challenge.qrPayload.startsWith("mixu://ad-reward/qr"))

        val initialWallet = ClientCreditWallet(promoCredits = 60)
        val verifiedWallet =
            onlineClient.verifyAdReward(
                userId = "u1",
                rewardToken = challenge.challengeToken,
                signature = "sig-hmac-256",
                currentWallet = initialWallet,
            )
        assertEquals(65, verifiedWallet.promoCredits)
        assertEquals(5, verifiedWallet.dailyAdCreditsClaimed)

        val offlineClient =
            MixuApiClient(
                transport = { throw IOException("Offline") },
            )
        val offlineChallenge = offlineClient.requestEInkQrAdChallenge("u1", "boox-leaf2")
        assertTrue(offlineChallenge.offlineFallback)
        assertTrue(offlineChallenge.qrPayload.contains("uid=u1"))

        val offlineVerified =
            offlineClient.verifyAdReward(
                userId = "u1",
                rewardToken = offlineChallenge.challengeToken,
                signature = "offline-sig",
                currentWallet = initialWallet,
            )
        assertEquals(65, offlineVerified.promoCredits)
        assertEquals(5, offlineVerified.dailyAdCreditsClaimed)
    }

    @Test
    fun `publish and purchase shared pack handle L1 L2 L3 tiers and offline fallback`() {
        val onlineClient =
            MixuApiClient(
                transport = { req ->
                    when {
                        req.url.endsWith("/publish") ->
                            MixuHttpResponse(
                                statusCode = 200,
                                body =
                                    """
                                    {
                                      "pack_id": "pack-blackwood",
                                      "published": true,
                                      "royalty_cap_minor": 12000,
                                      "offline_queued": false
                                    }
                                    """.trimIndent(),
                            )
                        req.url.endsWith("/purchase") ->
                            MixuHttpResponse(
                                statusCode = 200,
                                body =
                                    """
                                    {
                                      "pack_id": "pack-blackwood",
                                      "unlocked": true,
                                      "match_tier": "L2_STRUCTURE_FINGERPRINT",
                                      "charged_credits": 3
                                    }
                                    """.trimIndent(),
                            )
                        else -> MixuHttpResponse(statusCode = 404, body = "")
                    }
                },
            )

        val pubRes =
            onlineClient.publishSharedGraphPack(
                userId = "creator-1",
                packId = "pack-blackwood",
                epubSha256 = "a".repeat(64),
                structureFingerprint = "spine-12-toc-12",
                isbn = "978-4-06-182001-2",
                buildCostMinor = 6000L,
            )
        assertTrue(pubRes.published)
        assertEquals(12000L, pubRes.royaltyCapMinor)
        assertFalse(pubRes.offlineQueued)

        val wallet = ClientCreditWallet(promoCredits = 2, subCredits = 5, paidCredits = 10)
        val purchaseRes =
            onlineClient.purchaseSharedGraphPack(
                userId = "buyer-1",
                packId = "pack-blackwood",
                epubSha256 = null,
                structureFingerprint = "spine-12-toc-12",
                isbn = "978-4-06-182001-2",
                currentWallet = wallet,
            )
        assertTrue(purchaseRes.unlocked)
        assertEquals(EditionMatchTier.L2_STRUCTURE_FINGERPRINT, purchaseRes.matchTier)
        assertEquals(3, purchaseRes.chargedCredits)
        // Strict deduction order: 2 from promo -> 1 from sub
        assertEquals(0, purchaseRes.updatedWallet.promoCredits)
        assertEquals(4, purchaseRes.updatedWallet.subCredits)
        assertEquals(10, purchaseRes.updatedWallet.paidCredits)

        val offlineClient = MixuApiClient(transport = { throw IOException("No connection") })
        val offlinePub =
            offlineClient.publishSharedGraphPack(
                userId = "creator-1",
                packId = "pack-offline",
                epubSha256 = "b".repeat(64),
                structureFingerprint = null,
                isbn = null,
                buildCostMinor = 6000L,
            )
        assertFalse(offlinePub.published)
        assertTrue(offlinePub.offlineQueued)
        assertEquals(12000L, offlinePub.royaltyCapMinor)

        val offlinePurchase =
            offlineClient.purchaseSharedGraphPack(
                userId = "buyer-1",
                packId = "pack-offline",
                epubSha256 = null,
                structureFingerprint = null,
                isbn = "978-4-06-182001-2",
                currentWallet = ClientCreditWallet(promoCredits = 10),
            )
        assertTrue(offlinePurchase.unlocked)
        assertTrue(offlinePurchase.offlineFallback)
        assertEquals(EditionMatchTier.L3_ISBN_CHAPTER_ANCHOR, offlinePurchase.matchTier)
        assertEquals(7, offlinePurchase.updatedWallet.promoCredits)
    }

    @Test
    fun `queryCopilot enforces non-negative horizonOrdinal and attaches stateless mode header`() {
        var capturedHeaders: Map<String, String> = emptyMap()
        var capturedBody = ""
        val statelessClient =
            MixuApiClient(
                baseUrl = "http://10.0.2.2:8080/",
                statelessSelfHostedMode = true,
                transport = { req ->
                    capturedHeaders = req.headers
                    capturedBody = req.body.orEmpty()
                    MixuHttpResponse(
                        statusCode = 200,
                        body =
                            """
                            {
                              "edition_id": "urn:edition:blackwood-manor-v1",
                              "horizon_ordinal": 2,
                              "answer": "Up to Chapter 3, Arthur was found in the locked study.",
                              "stateless_mode": true,
                              "offline_fallback": false
                            }
                            """.trimIndent(),
                    )
                },
            )

        assertFailsWith<IllegalArgumentException> {
            statelessClient.queryCopilot(
                userId = "u1",
                editionId = "urn:edition:blackwood-manor-v1",
                horizonOrdinal = -1,
                query = "Who is the culprit?",
            )
        }

        val result =
            statelessClient.queryCopilot(
                userId = "u1",
                editionId = "urn:edition:blackwood-manor-v1",
                horizonOrdinal = 2,
                query = "Summarize clues so far",
                conversationHistory =
                    listOf(
                        CopilotChatTurnDto("user", "Who entered the study first?"),
                        CopilotChatTurnDto(
                            "assistant",
                            "Butler Jenkins unlocked the door at 22:00.",
                        ),
                    ),
            )
        assertEquals("true", capturedHeaders[MixuApiClient.HEADER_STATELESS_MODE])
        assertTrue(capturedBody.contains("conversation_history"))
        assertTrue(capturedBody.contains("Who entered the study first?"))
        assertTrue(result.statelessMode)
        assertFalse(result.offlineFallback)
        assertEquals(2, result.horizonOrdinal)

        val offlineTopUp =
            MixuApiClient(transport = { throw IOException("Offline") }).purchaseTopUpPack(
                userId = "u1",
                pack = TopUpPack.PACK_18_CNY,
                idempotencyKey = "topup-offline-1",
                currentWallet = ClientCreditWallet(promoCredits = 60),
            )
        assertEquals(300, offlineTopUp.paidCredits)
    }

    @Test
    fun `sendEmailOtp verifyEmailOtp and extractBookGraph parse live responses`() {
        val client =
            MixuApiClient(
                baseUrl = MixuApiClient.DEFAULT_PRODUCTION_BASE_URL,
                transport = { req ->
                    when {
                        req.url.endsWith("/v1/auth/email/send-code") ->
                            MixuHttpResponse(
                                statusCode = 200,
                                body = """{"status":"OTP_SENT","expires_in_seconds":600}""",
                            )
                        req.url.endsWith("/v1/auth/email/verify") ->
                            MixuHttpResponse(
                                statusCode = 200,
                                body =
                                    """
                                    {
                                      "access_token": "jwt-token-01",
                                      "user": {
                                        "id": "usr_reader_01",
                                        "email": "reader@mixuread.com",
                                        "display_name": "reader"
                                      },
                                      "is_new_account": true
                                    }
                                    """.trimIndent(),
                            )
                        req.url.endsWith("/v1/ai/graph/extract") ->
                            MixuHttpResponse(
                                statusCode = 200,
                                body =
                                    """
                                    {
                                      "edition_id": "urn:mixu:edition:clock",
                                      "entities": [
                                        {"label": "江南孝明", "type": "PERSON", "first_reveal": 0},
                                        {"label": "钟表馆", "type": "LOCATION", "first_reveal": 0}
                                      ],
                                      "assertions": [
                                        {
                                          "subject": "江南孝明",
                                          "predicate": "前往",
                                          "object": "钟表馆",
                                          "status": "OBSERVED",
                                          "first_reveal": 0
                                        }
                                      ],
                                      "model_tier": "standard",
                                      "offline_fallback": false
                                    }
                                    """.trimIndent(),
                            )
                        else -> MixuHttpResponse(statusCode = 404, body = "")
                    }
                },
            )

        val otpRes = client.sendEmailOtp("reader@mixuread.com")
        assertEquals("OTP_SENT", otpRes.status)
        assertFalse(otpRes.offlineFallback)

        val verifyRes = client.verifyEmailOtp("reader@mixuread.com", "123456")
        assertEquals("usr_reader_01", verifyRes?.userId)
        assertEquals("reader@mixuread.com", verifyRes?.email)
        assertTrue(verifyRes?.isNewAccount == true)

        val graphRes =
            client.extractBookGraph(
                userId = "usr_reader_01",
                editionId = "urn:mixu:edition:clock",
                bookTitle = "钟表馆事件",
                bookAuthor = "绫辻行人",
                chapters =
                    listOf(
                        CloudChapterDigestDto(
                            ordinal = 0,
                            title = "序章",
                            excerpt = "江南孝明前往钟表馆。",
                        ),
                    ),
            )
        assertEquals(2, graphRes?.entities?.size)
        assertEquals(1, graphRes?.assertions?.size)
        assertFalse(graphRes?.offlineFallback ?: true)
    }

    @Test
    fun `storage quota check-in and makeup handle online errors and offline fallback`() {
        val onlineClient =
            MixuApiClient(
                transport = { req ->
                    when {
                        req.method == "GET" && req.url.contains("/v1/storage/quota") -> {
                            assertTrue(req.url.contains("day_key=2026-11-12"))
                            assertTrue(req.url.contains("month_key=2026-11"))
                            MixuHttpResponse(
                                statusCode = 200,
                                body =
                                    """
                                    {
                                      "user_id": "u1",
                                      "quota_limit_bytes": 5578424320,
                                      "used_bytes": 52428800,
                                      "subscription_status": "ACTIVE",
                                      "storage_level": 2,
                                      "base_quota_bytes": 209715200,
                                      "subscription_bonus_bytes": 5368709120,
                                      "max_file_size_bytes": 314572800,
                                      "daily_download_quota_bytes": 2252341248,
                                      "daily_download_used_bytes": 10485760,
                                      "daily_download_ad_boost_bytes": 52428800,
                                      "daily_ad_boosts_used": 1,
                                      "daily_ad_boosts_cap": 6,
                                      "monthly_download_used_bytes": 104857600,
                                      "monthly_download_cap_bytes": 22733127680,
                                      "checkin_days": 5,
                                      "checkin_streak": 3,
                                      "checked_in_today": true,
                                      "monthly_makeup_used": 1,
                                      "monthly_makeup_cap": 5,
                                      "reading_minutes": 95,
                                      "chapters_read": 8,
                                      "graph_downloads": 2,
                                      "ai_queries": 4,
                                      "completed_graph_builds": 0,
                                      "active_plan_id": "plus_monthly_12_cny"
                                    }
                                    """.trimIndent(),
                            )
                        }
                        req.method == "POST" && req.url.endsWith("/v1/storage/check-in") ->
                            MixuHttpResponse(
                                statusCode = 200,
                                body =
                                    """
                                    {
                                      "already_checked_in": false,
                                      "newly_leveled_up": true,
                                      "quota_summary": {
                                        "user_id": "u1",
                                        "quota_limit_bytes": 524288000,
                                        "used_bytes": 20971520,
                                        "subscription_status": "ACTIVE",
                                        "storage_level": 3,
                                        "base_quota_bytes": 524288000,
                                        "subscription_bonus_bytes": 0,
                                        "checkin_days": 14,
                                        "checkin_streak": 14,
                                        "checked_in_today": true,
                                        "active_plan_id": null
                                      }
                                    }
                                    """.trimIndent(),
                            )
                        req.method == "POST" && req.url.endsWith("/v1/storage/check-in/makeup") ->
                            MixuHttpResponse(
                                statusCode = 429,
                                body =
                                    """
                                    {
                                      "code": "RATE_LIMITED",
                                      "message": "Monthly makeup cap exceeded",
                                      "request_id": "req-makeup-429"
                                    }
                                    """.trimIndent(),
                            )
                        else -> MixuHttpResponse(statusCode = 404, body = "")
                    }
                },
            )

        val quota =
            onlineClient.fetchStorageQuota(
                userId = "u1",
                dayKey = "2026-11-12",
                monthKey = "2026-11",
            )
        assertEquals(StorageLevel.LV2, quota.storageLevel)
        assertEquals(SubscriptionPlan.PLUS_MONTHLY, quota.activePlan)
        assertEquals(50, quota.usedMb)
        assertEquals(200 + 5_120, quota.quotaMb)
        assertEquals(1, quota.dailyAdBoostsUsed)
        assertEquals(5, quota.checkinDays)
        assertTrue(quota.checkedInToday)

        val checkIn =
            onlineClient.checkInStorage(
                userId = "u1",
                dayKey = "2026-11-12",
                deviceId = "boox-tab",
            )
        assertFalse(checkIn.alreadyCheckedIn)
        assertTrue(checkIn.newlyLeveledUp)
        assertFalse(checkIn.offlineFallback)
        assertEquals(StorageLevel.LV3, checkIn.quotaState.storageLevel)
        assertEquals(null, checkIn.quotaState.activePlan)

        val rateLimitedMakeup =
            onlineClient.makeupStorageCheckIn(
                userId = "u1",
                targetDayKey = "2026-11-10",
                challengeOrAdId = "ad-1",
                signature = "sig-1",
            )
        assertEquals(ApiErrorEnvelope.CODE_RATE_LIMITED, rateLimitedMakeup.error?.code)

        val offlineClient = MixuApiClient(transport = { throw IOException("Offline") })
        val fallbackQuota = offlineClient.fetchStorageQuota("u1", fallbackState = quota)
        assertEquals(quota, fallbackQuota)

        val offlineCheckIn =
            offlineClient.checkInStorage(
                userId = "u1",
                dayKey = "2026-11-12",
                currentState = StorageQuotaState(),
            )
        assertTrue(offlineCheckIn.offlineFallback)
        assertEquals(1, offlineCheckIn.quotaState.checkinDays)

        val offlineMakeup =
            offlineClient.makeupStorageCheckIn(
                userId = "u1",
                targetDayKey = "2026-11-10",
                challengeOrAdId = "ad-offline",
                signature = "sig-offline",
                nowDayKey = "2026-11-12",
                currentState = offlineCheckIn.quotaState,
            )
        assertTrue(offlineMakeup.offlineFallback)
        assertEquals(2, offlineMakeup.quotaState.checkinDays)
        assertEquals(1, offlineMakeup.quotaState.monthlyMakeupUsed)
    }

    @Test
    fun `download intent upload intent and ad boost handle error envelopes and offline mode`() {
        val client =
            MixuApiClient(
                transport = { req ->
                    when {
                        req.url.contains("/download-intent") && req.url.contains("ok-upload") ->
                            MixuHttpResponse(
                                statusCode = 200,
                                body =
                                    """
                                    {
                                      "upload_id": "ok-upload",
                                      "download_url": "https://cdn.example.invalid/presigned",
                                      "expires_at": "2026-11-12T23:59:59Z",
                                      "size_bytes": 5242880,
                                      "sha256": "${"a".repeat(64)}",
                                      "daily_download_remaining_bytes": 47185920,
                                      "monthly_download_remaining_bytes": 623902720
                                    }
                                    """.trimIndent(),
                            )
                        req.url.contains("/download-intent") && req.url.contains("quota-hit") ->
                            MixuHttpResponse(
                                statusCode = 429,
                                body =
                                    """
                                    {
                                      "code": "DAILY_DOWNLOAD_QUOTA_EXCEEDED",
                                      "message": "Daily egress limit exceeded",
                                      "request_id": "req-dl-429"
                                    }
                                    """.trimIndent(),
                            )
                        req.url.endsWith("/v1/uploads/intent") ->
                            MixuHttpResponse(
                                statusCode = 400,
                                body =
                                    """
                                    {
                                      "code": "FILE_SIZE_LIMIT_EXCEEDED",
                                      "message": "File exceeds max_file_size_bytes",
                                      "request_id": "req-up-400"
                                    }
                                    """.trimIndent(),
                            )
                        req.url.endsWith("/v1/storage/download-boost/verify") ->
                            MixuHttpResponse(
                                statusCode = 200,
                                body =
                                    """
                                    {
                                      "challenge_or_ad_id": "boost-01",
                                      "granted_boost_bytes": 52428800,
                                      "daily_download_ad_boost_bytes": 52428800,
                                      "daily_ad_boosts_used": 1,
                                      "daily_ad_boosts_cap": 6,
                                      "quota_summary": {
                                        "user_id": "u1",
                                        "quota_limit_bytes": 104857600,
                                        "used_bytes": 18874368,
                                        "subscription_status": "ACTIVE",
                                        "storage_level": 1,
                                        "daily_download_ad_boost_bytes": 52428800,
                                        "daily_ad_boosts_used": 1,
                                        "daily_ad_boosts_cap": 6
                                      }
                                    }
                                    """.trimIndent(),
                            )
                        else -> MixuHttpResponse(statusCode = 404, body = "")
                    }
                },
            )

        val dlOk = client.requestDownloadIntent("u1", "ok-upload", dayKey = "2026-11-12")
        assertTrue(dlOk.allowed)
        assertEquals(5_242_880L, dlOk.sizeBytes)
        assertFalse(dlOk.offlineFallback)

        val dlBlocked = client.requestDownloadIntent("u1", "quota-hit")
        assertFalse(dlBlocked.allowed)
        assertTrue(dlBlocked.isDailyDownloadQuotaExceeded)
        assertEquals("req-dl-429", dlBlocked.error?.requestId)

        val upBlocked =
            client.requestUploadIntent(
                userId = "u1",
                filename = "huge.epub",
                contentType = "application/epub+zip",
                sizeBytes = 50_000_000L,
                sha256 = "b".repeat(64),
            )
        assertFalse(upBlocked.allowed)
        assertTrue(upBlocked.isFileSizeLimitExceeded)
        assertEquals(ClientStorageStatus.ACTIVE, StorageQuotaState().status)

        val boostOk =
            client.verifyDownloadAdBoost(
                userId = "u1",
                challengeOrAdId = "boost-01",
                signature = "sig-hmac",
                dayKey = "2026-11-12",
            )
        assertFalse(boostOk.offlineFallback)
        assertEquals(52_428_800L, boostOk.grantedBoostBytes)
        assertEquals(1, boostOk.dailyAdBoostsUsed)
        assertEquals(50, boostOk.quotaState.dailyDownloadAdBoostMb)

        val offlineClient = MixuApiClient(transport = { throw IOException("Offline") })
        val offlineBoost =
            offlineClient.verifyDownloadAdBoost(
                userId = "u1",
                challengeOrAdId = "boost-offline",
                signature = "sig",
                currentState = StorageQuotaState(),
            )
        assertTrue(offlineBoost.offlineFallback)
        assertEquals(52_428_800L, offlineBoost.grantedBoostBytes)
        assertEquals(1, offlineBoost.dailyAdBoostsUsed)

        val offlineUpload =
            offlineClient.requestUploadIntent(
                userId = "u1",
                filename = "oversize.epub",
                contentType = "application/epub+zip",
                sizeBytes = 20L * StorageQuotaState.BYTES_PER_MB,
                sha256 = "c".repeat(64),
                currentState = StorageQuotaState(),
            )
        assertTrue(offlineUpload.offlineFallback)
        assertTrue(offlineUpload.isFileSizeLimitExceeded)
    }
}
