// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.network

import app.mixu.client.billing.ClientCreditWallet
import app.mixu.client.billing.EditionMatchTier
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
                                      "promo_credits": 63,
                                      "sub_credits": 0,
                                      "paid_credits": 0,
                                      "daily_ad_credits_claimed": 3,
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
        assertEquals(63, verifiedWallet.promoCredits)
        assertEquals(3, verifiedWallet.dailyAdCreditsClaimed)

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
        assertEquals(63, offlineVerified.promoCredits)
        assertEquals(3, offlineVerified.dailyAdCreditsClaimed)
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
        val statelessClient =
            MixuApiClient(
                baseUrl = "http://10.0.2.2:8080/",
                statelessSelfHostedMode = true,
                transport = { req ->
                    capturedHeaders = req.headers
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
            )
        assertEquals("true", capturedHeaders[MixuApiClient.HEADER_STATELESS_MODE])
        assertTrue(result.statelessMode)
        assertFalse(result.offlineFallback)
        assertEquals(2, result.horizonOrdinal)
    }
}
