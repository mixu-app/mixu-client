// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.network

import app.mixu.client.billing.ClientCreditWallet
import app.mixu.client.billing.EditionMatchTier
import app.mixu.client.billing.SharedPackMarketplaceOffer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI

/**
 * Immutable HTTP request value passed to [MixuHttpTransport].
 */
data class MixuHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

/**
 * Immutable HTTP response value returned by [MixuHttpTransport].
 */
data class MixuHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
) {
    val isSuccess: Boolean
        get() = statusCode in HTTP_OK_START..HTTP_OK_END

    companion object {
        private const val HTTP_OK_START = 200
        private const val HTTP_OK_END = 299
    }
}

/**
 * Pluggable transport abstraction so unit tests and offline mode can intercept requests
 * without opening real sockets.
 */
fun interface MixuHttpTransport {
    fun execute(request: MixuHttpRequest): MixuHttpResponse
}

/**
 * Default [HttpURLConnection]-backed transport with bounded connection and read timeouts.
 */
class JdkHttpTransport(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) : MixuHttpTransport {
    override fun execute(request: MixuHttpRequest): MixuHttpResponse {
        val connection =
            (URI.create(request.url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = request.method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                useCaches = false
                doInput = true
                for ((key, value) in request.headers) {
                    setRequestProperty(key, value)
                }
            }

        return try {
            val payload = request.body
            if (payload != null) {
                connection.doOutput = true
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payload)
                    writer.flush()
                }
            }

            val code = connection.responseCode
            val stream =
                if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream ?: connection.inputStream
                }
            val responseBody =
                stream
                    ?.let { input ->
                        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use {
                            it.readText()
                        }
                    }.orEmpty()

            val responseHeaders =
                connection.headerFields
                    .filterKeys { it != null }
                    .mapValues { (_, values) -> values.joinToString(",") }

            MixuHttpResponse(
                statusCode = code,
                body = responseBody,
                headers = responseHeaders,
            )
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 4_000
        const val DEFAULT_READ_TIMEOUT_MS = 8_000
    }
}

/**
 * Cross-device E-Ink QR ad reward challenge returned by
 * `POST /v1/billing/ad-rewards/qr-challenge` or synthesized locally when offline (ADR-030 §5).
 */
data class EInkQrChallengeDto(
    val challengeToken: String,
    val qrPayload: String,
    val expiresAtEpochSeconds: Long,
    val rewardCredits: Int = ClientCreditWallet.DEFAULT_AD_REWARD_CREDITS,
    val offlineFallback: Boolean = false,
)

/**
 * Result of publishing an offset-only GraphPack to
 * `POST /v1/catalog/packs/{packId}/publish` (ADR-040).
 */
data class SharedPackPublishResult(
    val packId: String,
    val published: Boolean,
    val royaltyCapMinor: Long,
    val offlineQueued: Boolean = false,
)

/**
 * Result of unlocking/purchasing a Shared GraphPack via
 * `POST /v1/catalog/packs/{packId}/purchase` (ADR-040).
 */
data class SharedPackPurchaseResult(
    val packId: String,
    val unlocked: Boolean,
    val matchTier: EditionMatchTier,
    val chargedCredits: Int,
    val updatedWallet: ClientCreditWallet,
    val offlineFallback: Boolean = false,
)

/**
 * Result of a spoiler-safe AI Butler / Copilot query (`POST /v1/copilot/query`).
 */
data class CopilotQueryResult(
    val editionId: String,
    val horizonOrdinal: Int,
    val answer: String,
    val statelessMode: Boolean,
    val offlineFallback: Boolean = false,
)

/**
 * End-to-end client for `mixu-server` `/v1/` endpoints with Stateless Self-Hosted Mode (ADR-037)
 * and automatic offline fallback (ADR-029) so `mixu-client` stays 100% functional without a server.
 */
class MixuApiClient(
    val baseUrl: String = DEFAULT_EMULATOR_LOOPBACK,
    val statelessSelfHostedMode: Boolean = false,
    private val transport: MixuHttpTransport = JdkHttpTransport(),
) {
    private val normalizedBaseUrl: String
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    init {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        normalizedBaseUrl = baseUrl.trim().trimEnd('/')
    }

    /**
     * Fetches the user's Three-Bucket Credit Wallet (`GET /v1/billing/wallet`).
     * Falls back to a default local [ClientCreditWallet] when offline.
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun fetchWallet(userId: String): ClientCreditWallet {
        require(userId.isNotBlank()) { "userId must not be blank" }
        val request =
            MixuHttpRequest(
                method = "GET",
                url = "$normalizedBaseUrl/v1/billing/wallet",
                headers = buildHeaders(userId = userId, hasBody = false),
            )
        val response = executeSafely(request) ?: return ClientCreditWallet()
        if (!response.isSuccess || response.body.isBlank()) {
            return ClientCreditWallet()
        }
        return runCatching {
            val obj = json.parseToJsonElement(response.body).jsonObject
            parseWalletJson(obj)
        }.getOrElse {
            ClientCreditWallet()
        }
    }

    /**
     * Requests a cross-device E-Ink QR ad challenge (`POST /v1/billing/ad-rewards/qr-challenge`).
     * Falls back to a deterministic local QR challenge payload when offline.
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun requestEInkQrAdChallenge(
        userId: String,
        deviceId: String,
    ): EInkQrChallengeDto {
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        val body =
            buildJsonObject {
                put("user_id", JsonPrimitive(userId))
                put("device_id", JsonPrimitive(deviceId))
            }.toString()
        val request =
            MixuHttpRequest(
                method = "POST",
                url = "$normalizedBaseUrl/v1/billing/ad-rewards/qr-challenge",
                headers = buildHeaders(userId = userId, hasBody = true),
                body = body,
            )
        val response = executeSafely(request)
        if (response != null && response.isSuccess && response.body.isNotBlank()) {
            runCatching {
                val obj = json.parseToJsonElement(response.body).jsonObject
                return EInkQrChallengeDto(
                    challengeToken =
                        obj.stringField("challenge_token")
                            ?: "srv-token-$deviceId",
                    qrPayload =
                        obj.stringField("qr_payload")
                            ?: ClientCreditWallet().generateEInkQrChallengePayload(userId),
                    expiresAtEpochSeconds =
                        obj.longField("expires_at_epoch_seconds")
                            ?: DEFAULT_OFFLINE_CHALLENGE_TTL_SECONDS,
                    rewardCredits =
                        obj.intField("reward_credits")
                            ?: ClientCreditWallet.DEFAULT_AD_REWARD_CREDITS,
                    offlineFallback = obj.booleanField("offline_fallback") ?: false,
                )
            }
        }
        val fallbackPayload =
            ClientCreditWallet().generateEInkQrChallengePayload(
                anonymousOrUserId = userId,
                sessionNonce = "offline-$deviceId",
            )
        return EInkQrChallengeDto(
            challengeToken = "offline-token-$deviceId",
            qrPayload = fallbackPayload,
            expiresAtEpochSeconds = DEFAULT_OFFLINE_CHALLENGE_TTL_SECONDS,
            rewardCredits = ClientCreditWallet.DEFAULT_AD_REWARD_CREDITS,
            offlineFallback = true,
        )
    }

    /**
     * Verifies a completed rewarded ad or E-Ink QR challenge (`POST /v1/billing/ad-rewards/verify`).
     * Falls back to local daily-capped reward accounting on [currentWallet] when offline.
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun verifyAdReward(
        userId: String,
        rewardToken: String,
        signature: String,
        currentWallet: ClientCreditWallet,
    ): ClientCreditWallet {
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(rewardToken.isNotBlank()) { "rewardToken must not be blank" }
        require(signature.isNotBlank()) { "signature must not be blank" }
        val body =
            buildJsonObject {
                put("user_id", JsonPrimitive(userId))
                put("reward_token", JsonPrimitive(rewardToken))
                put("signature", JsonPrimitive(signature))
            }.toString()
        val request =
            MixuHttpRequest(
                method = "POST",
                url = "$normalizedBaseUrl/v1/billing/ad-rewards/verify",
                headers = buildHeaders(userId = userId, hasBody = true),
                body = body,
            )
        val response = executeSafely(request)
        if (response != null && response.isSuccess && response.body.isNotBlank()) {
            runCatching {
                val obj = json.parseToJsonElement(response.body).jsonObject
                return parseWalletJson(obj, fallbackPromo = currentWallet.promoCredits)
            }
        }
        return currentWallet.claimRewardedAd()
    }

    /**
     * Publishes an offset-only GraphPack to the Shared GraphPack Marketplace
     * (`POST /v1/catalog/packs/{packId}/publish`) with a 200% creator royalty cap (ADR-040).
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun publishSharedGraphPack(
        userId: String,
        packId: String,
        epubSha256: String,
        structureFingerprint: String?,
        isbn: String?,
        buildCostMinor: Long = DEFAULT_BUILD_COST_MINOR,
    ): SharedPackPublishResult {
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(packId.isNotBlank()) { "packId must not be blank" }
        require(epubSha256.isNotBlank()) { "epubSha256 must not be blank" }
        require(buildCostMinor >= 0L) { "buildCostMinor must be non-negative" }

        val body =
            buildJsonObject {
                put("user_id", JsonPrimitive(userId))
                put("pack_id", JsonPrimitive(packId))
                put("epub_sha256", JsonPrimitive(epubSha256))
                structureFingerprint?.let { put("structure_fingerprint", JsonPrimitive(it)) }
                isbn?.let { put("isbn", JsonPrimitive(it)) }
                put("build_cost_minor", JsonPrimitive(buildCostMinor))
            }.toString()
        val request =
            MixuHttpRequest(
                method = "POST",
                url = "$normalizedBaseUrl/v1/catalog/packs/$packId/publish",
                headers = buildHeaders(userId = userId, hasBody = true),
                body = body,
            )
        val response = executeSafely(request)
        if (response != null && response.isSuccess && response.body.isNotBlank()) {
            runCatching {
                val obj = json.parseToJsonElement(response.body).jsonObject
                return SharedPackPublishResult(
                    packId = obj.stringField("pack_id") ?: packId,
                    published = obj.booleanField("published") ?: true,
                    royaltyCapMinor =
                        obj.longField("royalty_cap_minor")
                            ?: (buildCostMinor * ClientCreditWallet.ROYALTY_CAP_MULTIPLIER),
                    offlineQueued = obj.booleanField("offline_queued") ?: false,
                )
            }
        }
        return SharedPackPublishResult(
            packId = packId,
            published = false,
            royaltyCapMinor = buildCostMinor * ClientCreditWallet.ROYALTY_CAP_MULTIPLIER,
            offlineQueued = true,
        )
    }

    /**
     * Purchases/unlocks a Shared GraphPack (`POST /v1/catalog/packs/{packId}/purchase`),
     * resolving the Three-Tier Book Edition Fingerprint (`L1 -> L2 -> L3`) and deducting credits
     * strictly in `promo -> sub -> paid` order (ADR-030 & ADR-040).
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun purchaseSharedGraphPack(
        userId: String,
        packId: String,
        epubSha256: String?,
        structureFingerprint: String?,
        isbn: String?,
        currentWallet: ClientCreditWallet,
    ): SharedPackPurchaseResult {
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(packId.isNotBlank()) { "packId must not be blank" }
        val resolvedTier = resolveEditionMatchTier(epubSha256, structureFingerprint, isbn)
        val body =
            buildJsonObject {
                put("user_id", JsonPrimitive(userId))
                put("pack_id", JsonPrimitive(packId))
                epubSha256?.let { put("epub_sha256", JsonPrimitive(it)) }
                structureFingerprint?.let { put("structure_fingerprint", JsonPrimitive(it)) }
                isbn?.let { put("isbn", JsonPrimitive(it)) }
            }.toString()
        val request =
            MixuHttpRequest(
                method = "POST",
                url = "$normalizedBaseUrl/v1/catalog/packs/$packId/purchase",
                headers = buildHeaders(userId = userId, hasBody = true),
                body = body,
            )
        val response = executeSafely(request)
        if (response != null && response.isSuccess && response.body.isNotBlank()) {
            runCatching {
                val obj = json.parseToJsonElement(response.body).jsonObject
                val unlocked = obj.booleanField("unlocked") ?: true
                val charged =
                    obj.intField("charged_credits")
                        ?: SharedPackMarketplaceOffer.DEFAULT_DOWNLOAD_COST_CREDITS
                val tierName = obj.stringField("match_tier")
                val parsedTier =
                    tierName?.let { name ->
                        EditionMatchTier.entries.firstOrNull { it.name == name }
                    } ?: resolvedTier
                val nestedWallet =
                    obj["wallet"]?.jsonObject?.let {
                        parseWalletJson(it, fallbackPromo = currentWallet.promoCredits)
                    }
                val nextWallet =
                    nestedWallet
                        ?: if (unlocked && currentWallet.canAfford(charged)) {
                            currentWallet.deductCredits(charged)
                        } else {
                            currentWallet
                        }
                return SharedPackPurchaseResult(
                    packId = obj.stringField("pack_id") ?: packId,
                    unlocked = unlocked,
                    matchTier = parsedTier,
                    chargedCredits = charged,
                    updatedWallet = nextWallet,
                    offlineFallback = false,
                )
            }
        }

        val cost = SharedPackMarketplaceOffer.DEFAULT_DOWNLOAD_COST_CREDITS
        return if (currentWallet.canAfford(cost)) {
            SharedPackPurchaseResult(
                packId = packId,
                unlocked = true,
                matchTier = resolvedTier,
                chargedCredits = cost,
                updatedWallet = currentWallet.deductCredits(cost),
                offlineFallback = true,
            )
        } else {
            SharedPackPurchaseResult(
                packId = packId,
                unlocked = false,
                matchTier = resolvedTier,
                chargedCredits = 0,
                updatedWallet = currentWallet,
                offlineFallback = true,
            )
        }
    }

    /**
     * Executes a spoiler-safe AI Butler / Copilot query (`POST /v1/copilot/query`).
     * Enforces `horizonOrdinal >= 0` and attaches `X-Mixu-Stateless-Mode: true` when
     * [statelessSelfHostedMode] is enabled (ADR-037).
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun queryCopilot(
        userId: String,
        editionId: String,
        horizonOrdinal: Int,
        query: String,
    ): CopilotQueryResult {
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(editionId.isNotBlank()) { "editionId must not be blank" }
        require(horizonOrdinal >= 0) {
            "horizonOrdinal must be non-negative: $horizonOrdinal"
        }
        require(query.isNotBlank()) { "query must not be blank" }

        val body =
            buildJsonObject {
                put("user_id", JsonPrimitive(userId))
                put("edition_id", JsonPrimitive(editionId))
                put("horizon_ordinal", JsonPrimitive(horizonOrdinal))
                put("query", JsonPrimitive(query))
                put("stateless_mode", JsonPrimitive(statelessSelfHostedMode))
            }.toString()
        val request =
            MixuHttpRequest(
                method = "POST",
                url = "$normalizedBaseUrl/v1/copilot/query",
                headers = buildHeaders(userId = userId, hasBody = true),
                body = body,
            )
        val response = executeSafely(request)
        if (response != null && response.isSuccess && response.body.isNotBlank()) {
            runCatching {
                val obj = json.parseToJsonElement(response.body).jsonObject
                return CopilotQueryResult(
                    editionId = obj.stringField("edition_id") ?: editionId,
                    horizonOrdinal = obj.intField("horizon_ordinal") ?: horizonOrdinal,
                    answer = obj.stringField("answer").orEmpty(),
                    statelessMode =
                        obj.booleanField("stateless_mode") ?: statelessSelfHostedMode,
                    offlineFallback = obj.booleanField("offline_fallback") ?: false,
                )
            }
        }

        return CopilotQueryResult(
            editionId = editionId,
            horizonOrdinal = horizonOrdinal,
            answer =
                "[Offline Spoiler-Safe Horizon <= $horizonOrdinal] " +
                    "Verified local graph context for $editionId: $query",
            statelessMode = statelessSelfHostedMode,
            offlineFallback = true,
        )
    }

    private fun parseWalletJson(
        obj: JsonObject,
        fallbackPromo: Int = ClientCreditWallet.DEFAULT_PROMO_CREDITS,
    ): ClientCreditWallet {
        val promo =
            obj.intField("promo_credits")
                ?: obj.longField("promo_credits_minor")?.let {
                    (it / MINOR_UNITS_PER_CREDIT).toInt()
                }
                ?: fallbackPromo
        val sub =
            obj.intField("sub_credits")
                ?: obj.longField("sub_credits_minor")?.let {
                    (it / MINOR_UNITS_PER_CREDIT).toInt()
                }
                ?: 0
        val paid =
            obj.intField("paid_credits")
                ?: obj.longField("paid_credits_minor")?.let {
                    (it / MINOR_UNITS_PER_CREDIT).toInt()
                }
                ?: 0
        val dailyAd = obj.intField("daily_ad_credits_claimed") ?: 0
        val royalty = obj.intField("creator_royalty_earned") ?: 0

        return ClientCreditWallet(
            promoCredits = promo.coerceAtLeast(0),
            subCredits = sub.coerceAtLeast(0),
            paidCredits = paid.coerceAtLeast(0),
            dailyAdCreditsClaimed = dailyAd.coerceAtLeast(0),
            creatorRoyaltyEarned = royalty.coerceAtLeast(0),
        )
    }

    private fun JsonObject.stringField(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.intField(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.longField(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.booleanField(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull

    private fun resolveEditionMatchTier(
        epubSha256: String?,
        structureFingerprint: String?,
        isbn: String?,
    ): EditionMatchTier =
        when {
            !epubSha256.isNullOrBlank() -> EditionMatchTier.L1_EXACT_SHA256
            !structureFingerprint.isNullOrBlank() -> EditionMatchTier.L2_STRUCTURE_FINGERPRINT
            !isbn.isNullOrBlank() -> EditionMatchTier.L3_ISBN_CHAPTER_ANCHOR
            else -> EditionMatchTier.L1_EXACT_SHA256
        }

    private fun buildHeaders(
        userId: String,
        hasBody: Boolean,
    ): Map<String, String> =
        buildMap {
            put(HEADER_ACCEPT, CONTENT_TYPE_JSON)
            put(HEADER_USER_ID, userId)
            if (hasBody) {
                put(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
            }
            if (statelessSelfHostedMode) {
                put(HEADER_STATELESS_MODE, "true")
            }
        }

    private fun executeSafely(request: MixuHttpRequest): MixuHttpResponse? =
        runCatching {
            transport.execute(request)
        }.getOrNull()

    companion object {
        const val DEFAULT_EMULATOR_LOOPBACK = "http://10.0.2.2:8080"
        const val DEFAULT_BUILD_COST_MINOR = 6_000L
        const val DEFAULT_OFFLINE_CHALLENGE_TTL_SECONDS = 300L
        const val HEADER_USER_ID = "X-Mixu-User-Id"
        const val HEADER_STATELESS_MODE = "X-Mixu-Stateless-Mode"
        private const val MINOR_UNITS_PER_CREDIT = 100L
        private const val HEADER_ACCEPT = "Accept"
        private const val HEADER_CONTENT_TYPE = "Content-Type"
        private const val CONTENT_TYPE_JSON = "application/json"
    }
}
