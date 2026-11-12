// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.network

import app.mixu.client.billing.ClientCreditWallet
import app.mixu.client.billing.ClientStorageStatus
import app.mixu.client.billing.EditionMatchTier
import app.mixu.client.billing.SharedPackMarketplaceOffer
import app.mixu.client.billing.StorageLevel
import app.mixu.client.billing.StorageQuotaState
import app.mixu.client.billing.SubscriptionPlan
import app.mixu.client.billing.TopUpPack
import app.mixu.client.config.DistributionChannel
import app.mixu.client.config.ReaderDisplayConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.LocalDate

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
        const val DEFAULT_CONNECT_TIMEOUT_MS = 8_000
        const val DEFAULT_READ_TIMEOUT_MS = 45_000
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
 * Single conversation turn sent to `POST /v1/copilot/query` in `conversation_history` so
 * same-chapter follow-ups and cross-tier switches maximize server KV prefix cache hits.
 */
data class CopilotChatTurnDto(
    val role: String,
    val content: String,
)

/**
 * Result of a spoiler-safe AI Butler / Copilot query (`POST /v1/copilot/query`).
 *
 * The client only receives and displays an opaque [modelTier] (`"standard"`, `"balanced"`,
 * `"reasoning"`, `"pro"`) rather than concrete backend provider model IDs.
 */
data class CopilotQueryResult(
    val editionId: String,
    val horizonOrdinal: Int,
    val answer: String,
    val modelTier: String = DEFAULT_COPILOT_MODEL_TIER,
    val creditCost: Int = 1,
    val statelessMode: Boolean,
    val offlineFallback: Boolean = false,
) {
    companion object {
        const val DEFAULT_COPILOT_MODEL_TIER = "standard"
    }
}

/**
 * Result of requesting an email OTP code (`POST /v1/auth/email/send-code`).
 */
data class EmailOtpSendResult(
    val status: String,
    val expiresInSeconds: Int,
    val offlineFallback: Boolean = false,
)

/**
 * Result of verifying a 6-digit email OTP code (`POST /v1/auth/email/verify`).
 */
data class EmailOtpVerifyResult(
    val accessToken: String,
    val userId: String,
    val email: String,
    val displayName: String,
    val isNewAccount: Boolean,
    val offlineFallback: Boolean = false,
)

/**
 * Chapter digest sent to `POST /v1/ai/graph/extract` for cloud AI narrative graph construction.
 */
data class CloudChapterDigestDto(
    val ordinal: Int,
    val title: String,
    val excerpt: String,
)

/**
 * Entity returned by `POST /v1/ai/graph/extract`.
 */
data class CloudExtractedEntityDto(
    val label: String,
    val type: String,
    val firstReveal: Int,
)

/**
 * Assertion returned by `POST /v1/ai/graph/extract`.
 */
data class CloudExtractedAssertionDto(
    val subject: String,
    val predicate: String,
    val objectLabel: String,
    val status: String,
    val firstReveal: Int,
)

/**
 * Result of cloud AI narrative graph extraction (`POST /v1/ai/graph/extract`).
 */
data class CloudGraphExtractResult(
    val editionId: String,
    val entities: List<CloudExtractedEntityDto>,
    val assertions: List<CloudExtractedAssertionDto>,
    val modelTier: String = CopilotQueryResult.DEFAULT_COPILOT_MODEL_TIER,
    val offlineFallback: Boolean = false,
)

/**
 * Machine-readable API error envelope (`contracts/schemas/api/error.schema.json`).
 */
data class ApiErrorEnvelope(
    val code: String,
    val message: String,
    val requestId: String? = null,
) {
    val isFileSizeLimitExceeded: Boolean
        get() = code == CODE_FILE_SIZE_LIMIT_EXCEEDED

    val isDailyDownloadQuotaExceeded: Boolean
        get() = code == CODE_DAILY_DOWNLOAD_QUOTA_EXCEEDED

    val isStorageQuotaExceeded: Boolean
        get() = code == CODE_STORAGE_QUOTA_EXCEEDED

    val isRateLimited: Boolean
        get() = code == CODE_RATE_LIMITED

    companion object {
        const val CODE_FILE_SIZE_LIMIT_EXCEEDED = "FILE_SIZE_LIMIT_EXCEEDED"
        const val CODE_DAILY_DOWNLOAD_QUOTA_EXCEEDED = "DAILY_DOWNLOAD_QUOTA_EXCEEDED"
        const val CODE_STORAGE_QUOTA_EXCEEDED = "STORAGE_QUOTA_EXCEEDED"
        const val CODE_RATE_LIMITED = "RATE_LIMITED"
        const val CODE_INVALID_REQUEST = "INVALID_REQUEST"
        const val CODE_FORBIDDEN = "FORBIDDEN"
    }
}

/**
 * Result of `POST /v1/storage/check-in` or `POST /v1/storage/check-in/makeup`.
 */
data class StorageCheckInResult(
    val alreadyCheckedIn: Boolean,
    val newlyLeveledUp: Boolean,
    val quotaState: StorageQuotaState,
    val offlineFallback: Boolean = false,
    val error: ApiErrorEnvelope? = null,
)

/**
 * Result of `POST /v1/storage/objects/{uploadId}/download-intent`.
 */
data class StorageDownloadIntentResult(
    val uploadId: String,
    val allowed: Boolean,
    val downloadUrl: String? = null,
    val expiresAt: String? = null,
    val sizeBytes: Long = 0L,
    val sha256: String? = null,
    val dailyDownloadRemainingBytes: Long = 0L,
    val monthlyDownloadRemainingBytes: Long = 0L,
    val offlineFallback: Boolean = false,
    val error: ApiErrorEnvelope? = null,
) {
    val isDailyDownloadQuotaExceeded: Boolean
        get() = error?.isDailyDownloadQuotaExceeded == true

    val isFileSizeLimitExceeded: Boolean
        get() = error?.isFileSizeLimitExceeded == true
}

/**
 * Result of `POST /v1/uploads/intent`.
 */
data class StorageUploadIntentResult(
    val allowed: Boolean,
    val uploadId: String? = null,
    val uploadUrl: String? = null,
    val expiresAt: String? = null,
    val maxSizeBytes: Long = 0L,
    val offlineFallback: Boolean = false,
    val error: ApiErrorEnvelope? = null,
) {
    val isFileSizeLimitExceeded: Boolean
        get() = error?.isFileSizeLimitExceeded == true

    val isStorageQuotaExceeded: Boolean
        get() = error?.isStorageQuotaExceeded == true
}

/**
 * Result of `POST /v1/storage/download-boost/verify`.
 */
data class DownloadAdBoostResult(
    val challengeOrAdId: String,
    val grantedBoostBytes: Long,
    val dailyDownloadAdBoostBytes: Long,
    val dailyAdBoostsUsed: Int,
    val dailyAdBoostsCap: Int,
    val quotaState: StorageQuotaState,
    val offlineFallback: Boolean = false,
    val error: ApiErrorEnvelope? = null,
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
     * Purchases a permanent `paid_credits` top-up pack (`POST /v1/billing/topup/purchase`).
     * Falls back locally to [ClientCreditWallet.purchaseTopUpPack] when offline.
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun purchaseTopUpPack(
        userId: String,
        pack: TopUpPack,
        idempotencyKey: String,
        currentWallet: ClientCreditWallet,
    ): ClientCreditWallet {
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey must not be blank" }
        val body =
            buildJsonObject {
                put("pack_id", JsonPrimitive(pack.packId))
                put("idempotency_key", JsonPrimitive(idempotencyKey))
            }.toString()
        val request =
            MixuHttpRequest(
                method = "POST",
                url = "$normalizedBaseUrl/v1/billing/topup/purchase",
                headers = buildHeaders(userId = userId, hasBody = true),
                body = body,
            )
        val response = executeSafely(request)
        if (response != null && response.isSuccess && response.body.isNotBlank()) {
            runCatching {
                val obj = json.parseToJsonElement(response.body).jsonObject
                val walletObj = obj["wallet"]?.jsonObject ?: obj
                return parseWalletJson(walletObj, fallbackPromo = currentWallet.promoCredits)
            }
        }
        return currentWallet.purchaseTopUpPack(pack)
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
     * Requests a 6-digit email verification code (`POST /v1/auth/email/send-code`).
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun sendEmailOtp(email: String): EmailOtpSendResult {
        val trimmed = email.trim()
        require(trimmed.isNotBlank()) { "email must not be blank" }
        val body =
            buildJsonObject {
                put("email", JsonPrimitive(trimmed))
            }.toString()
        val request =
            MixuHttpRequest(
                method = "POST",
                url = "$normalizedBaseUrl/v1/auth/email/send-code",
                headers = buildHeaders(userId = trimmed, hasBody = true),
                body = body,
            )
        val response = executeSafely(request)
        if (response != null && response.isSuccess && response.body.isNotBlank()) {
            runCatching {
                val obj = json.parseToJsonElement(response.body).jsonObject
                return EmailOtpSendResult(
                    status = obj.stringField("status") ?: "OTP_SENT",
                    expiresInSeconds = obj.intField("expires_in_seconds") ?: 600,
                    offlineFallback = false,
                )
            }
        }
        return EmailOtpSendResult(
            status = "OFFLINE_FALLBACK",
            expiresInSeconds = 600,
            offlineFallback = true,
        )
    }

    /**
     * Verifies a 6-digit email OTP code and returns the authenticated session (`POST /v1/auth/email/verify`).
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun verifyEmailOtp(
        email: String,
        code: String,
    ): EmailOtpVerifyResult? {
        val trimmedEmail = email.trim()
        val trimmedCode = code.trim()
        require(trimmedEmail.isNotBlank()) { "email must not be blank" }
        require(trimmedCode.isNotBlank()) { "code must not be blank" }
        val body =
            buildJsonObject {
                put("email", JsonPrimitive(trimmedEmail))
                put("code", JsonPrimitive(trimmedCode))
            }.toString()
        val request =
            MixuHttpRequest(
                method = "POST",
                url = "$normalizedBaseUrl/v1/auth/email/verify",
                headers = buildHeaders(userId = trimmedEmail, hasBody = true),
                body = body,
            )
        val response = executeSafely(request) ?: return null
        if (!response.isSuccess || response.body.isBlank()) {
            return null
        }
        return runCatching {
            val obj = json.parseToJsonElement(response.body).jsonObject
            val userObj = obj["user"]?.jsonObject
            EmailOtpVerifyResult(
                accessToken = obj.stringField("access_token") ?: "",
                userId = userObj?.stringField("id") ?: trimmedEmail,
                email = userObj?.stringField("email") ?: trimmedEmail,
                displayName = userObj?.stringField("display_name") ?: trimmedEmail,
                isNewAccount = obj.booleanField("is_new_account") ?: false,
                offlineFallback = false,
            )
        }.getOrNull()
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
        bookTitle: String? = null,
        chapterTitle: String? = null,
        visibleExcerpt: String? = null,
        modelTier: String? = null,
        conversationHistory: List<CopilotChatTurnDto> = emptyList(),
    ): CopilotQueryResult {
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(editionId.isNotBlank()) { "editionId must not be blank" }
        require(horizonOrdinal >= 0) {
            "horizonOrdinal must be non-negative: $horizonOrdinal"
        }
        require(query.isNotBlank()) { "query must not be blank" }

        val requestedTier =
            modelTier?.takeIf { it.isNotBlank() } ?: CopilotQueryResult.DEFAULT_COPILOT_MODEL_TIER
        val body =
            buildJsonObject {
                put("user_id", JsonPrimitive(userId))
                put("edition_id", JsonPrimitive(editionId))
                put("horizon_ordinal", JsonPrimitive(horizonOrdinal))
                put("query", JsonPrimitive(query))
                bookTitle?.takeIf { it.isNotBlank() }?.let {
                    put("book_title", JsonPrimitive(it))
                }
                chapterTitle?.takeIf { it.isNotBlank() }?.let {
                    put("chapter_title", JsonPrimitive(it))
                }
                visibleExcerpt?.takeIf { it.isNotBlank() }?.let {
                    put("visible_excerpt", JsonPrimitive(it.take(8_000)))
                }
                put("model_tier", JsonPrimitive(requestedTier))
                put("stateless_mode", JsonPrimitive(statelessSelfHostedMode))
                if (conversationHistory.isNotEmpty()) {
                    put(
                        "conversation_history",
                        buildJsonArray {
                            for (turn in conversationHistory.takeLast(MAX_COPILOT_HISTORY_TURNS)) {
                                if (turn.content.isNotBlank()) {
                                    add(
                                        buildJsonObject {
                                            put("role", JsonPrimitive(turn.role))
                                            put("content", JsonPrimitive(turn.content.take(1_500)))
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
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
                    modelTier = obj.stringField("model_tier") ?: requestedTier,
                    creditCost = obj.intField("credit_cost") ?: 1,
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
            modelTier = requestedTier,
            creditCost = 1,
            statelessMode = statelessSelfHostedMode,
            offlineFallback = true,
        )
    }

    /**
     * Calls `POST /v1/ai/graph/extract` to construct a progress-gated narrative knowledge graph
     * from the chapter digests of an imported book via the cloud gateway.
     *
     * Time complexity: O(C) where C is the number of chapters.
     * Space complexity: O(E + A) where E is extracted entity count and A is assertion count.
     */
    fun extractBookGraph(
        userId: String,
        editionId: String,
        bookTitle: String,
        bookAuthor: String,
        chapters: List<CloudChapterDigestDto>,
        modelTier: String? = null,
    ): CloudGraphExtractResult? {
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(editionId.isNotBlank()) { "editionId must not be blank" }
        val body =
            buildJsonObject {
                put("user_id", JsonPrimitive(userId))
                put("edition_id", JsonPrimitive(editionId))
                put("book_title", JsonPrimitive(bookTitle))
                put("book_author", JsonPrimitive(bookAuthor))
                modelTier?.takeIf { it.isNotBlank() }?.let {
                    put("model_tier", JsonPrimitive(it))
                }
                put(
                    "chapters",
                    buildJsonArray {
                        for (ch in chapters.take(80)) {
                            add(
                                buildJsonObject {
                                    put("ordinal", JsonPrimitive(ch.ordinal))
                                    put("title", JsonPrimitive(ch.title))
                                    put("excerpt", JsonPrimitive(ch.excerpt.take(36_000)))
                                },
                            )
                        }
                    },
                )
            }.toString()
        val request =
            MixuHttpRequest(
                method = "POST",
                url = "$normalizedBaseUrl/v1/ai/graph/extract",
                headers = buildHeaders(userId = userId, hasBody = true),
                body = body,
            )
        val response = executeSafely(request) ?: return null
        if (!response.isSuccess || response.body.isBlank()) {
            return null
        }
        return runCatching {
            val obj = json.parseToJsonElement(response.body).jsonObject
            val entitiesArr = obj["entities"]?.jsonArray ?: JsonArray(emptyList())
            val entities =
                entitiesArr.mapNotNull { el ->
                    val item = el.jsonObject
                    val label = item.stringField("label") ?: return@mapNotNull null
                    CloudExtractedEntityDto(
                        label = label,
                        type = item.stringField("type") ?: "PERSON",
                        firstReveal = item.intField("first_reveal") ?: 0,
                    )
                }
            val assertionsArr = obj["assertions"]?.jsonArray ?: JsonArray(emptyList())
            val assertions =
                assertionsArr.mapNotNull { el ->
                    val item = el.jsonObject
                    val subj = item.stringField("subject") ?: return@mapNotNull null
                    val pred = item.stringField("predicate") ?: return@mapNotNull null
                    val objLabel = item.stringField("object") ?: return@mapNotNull null
                    CloudExtractedAssertionDto(
                        subject = subj,
                        predicate = pred,
                        objectLabel = objLabel,
                        status = item.stringField("status") ?: "OBSERVED",
                        firstReveal = item.intField("first_reveal") ?: 0,
                    )
                }
            CloudGraphExtractResult(
                editionId = obj.stringField("edition_id") ?: editionId,
                entities = entities,
                assertions = assertions,
                modelTier =
                    obj.stringField("model_tier")
                        ?: (modelTier ?: CopilotQueryResult.DEFAULT_COPILOT_MODEL_TIER),
                offlineFallback = obj.booleanField("offline_fallback") ?: false,
            )
        }.getOrNull()
    }

    /**
     * Fetches the reader's 5-level cloud storage progression, egress allowances, and
     * 7-day grace period status (`GET /v1/storage/quota`, ADR-030).
     * Falls back to [fallbackState] when offline.
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun fetchStorageQuota(
        userId: String,
        dayKey: String? = null,
        monthKey: String? = null,
        fallbackState: StorageQuotaState = StorageQuotaState(),
    ): StorageQuotaState {
        require(userId.isNotBlank()) { "userId must not be blank" }
        val request =
            MixuHttpRequest(
                method = "GET",
                url = buildStorageQuotaUrl(dayKey = dayKey, monthKey = monthKey),
                headers = buildHeaders(userId = userId, hasBody = false),
            )
        val response = executeSafely(request) ?: return fallbackState
        if (!response.isSuccess || response.body.isBlank()) {
            return fallbackState
        }
        return runCatching {
            val obj = json.parseToJsonElement(response.body).jsonObject
            parseStorageQuotaSummary(obj, fallback = fallbackState)
        }.getOrElse {
            fallbackState
        }
    }

    /**
     * Records an idempotent daily check-in toward the 5-level storage progression
     * (`POST /v1/storage/check-in`). Falls back locally via [StorageQuotaState.recordCheckIn]
     * when offline.
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun checkInStorage(
        userId: String,
        dayKey: String? = null,
        deviceId: String? = null,
        currentState: StorageQuotaState = StorageQuotaState(),
    ): StorageCheckInResult {
        require(userId.isNotBlank()) { "userId must not be blank" }
        val body =
            buildJsonObject {
                dayKey?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    put("day_key", JsonPrimitive(it))
                }
                deviceId?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    put("device_id", JsonPrimitive(it))
                }
            }.toString()
        val request =
            MixuHttpRequest(
                method = "POST",
                url = "$normalizedBaseUrl/v1/storage/check-in",
                headers = buildHeaders(userId = userId, hasBody = true),
                body = body,
            )
        val response = executeSafely(request)
        if (response != null) {
            if (response.isSuccess && response.body.isNotBlank()) {
                runCatching {
                    val obj = json.parseToJsonElement(response.body).jsonObject
                    val summaryObj = obj["quota_summary"]?.jsonObject ?: obj
                    val parsedState = parseStorageQuotaSummary(summaryObj, fallback = currentState)
                    return StorageCheckInResult(
                        alreadyCheckedIn = obj.booleanField("already_checked_in") ?: false,
                        newlyLeveledUp = obj.booleanField("newly_leveled_up") ?: false,
                        quotaState = parsedState,
                        offlineFallback = false,
                    )
                }
            } else if (!response.isSuccess && response.statusCode in 400..499) {
                return StorageCheckInResult(
                    alreadyCheckedIn = currentState.checkedInToday,
                    newlyLeveledUp = false,
                    quotaState = currentState,
                    offlineFallback = false,
                    error = parseErrorEnvelope(response),
                )
            }
        }

        val effectiveDay =
            dayKey?.trim()?.takeIf { it.isNotEmpty() } ?: LocalDate.now().toString()
        val updated = currentState.recordCheckIn(effectiveDay)
        return StorageCheckInResult(
            alreadyCheckedIn = updated.checkinDays == currentState.checkinDays,
            newlyLeveledUp = updated.storageLevel.level > currentState.storageLevel.level,
            quotaState = updated,
            offlineFallback = true,
        )
    }

    /**
     * Verifies a rewarded ad or E-Ink QR challenge to make up a missed storage check-in
     * (`POST /v1/storage/check-in/makeup`). Falls back locally via
     * [StorageQuotaState.makeupCheckInWithAd] when offline.
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun makeupStorageCheckIn(
        userId: String,
        targetDayKey: String,
        challengeOrAdId: String,
        signature: String,
        nowDayKey: String? = null,
        currentState: StorageQuotaState = StorageQuotaState(),
    ): StorageCheckInResult {
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(targetDayKey.isNotBlank()) { "targetDayKey must not be blank" }
        require(challengeOrAdId.isNotBlank()) { "challengeOrAdId must not be blank" }
        require(signature.isNotBlank()) { "signature must not be blank" }

        val cleanTargetDay = targetDayKey.trim()
        val body =
            buildJsonObject {
                put("target_day_key", JsonPrimitive(cleanTargetDay))
                put("challenge_or_ad_id", JsonPrimitive(challengeOrAdId.trim()))
                put("signature", JsonPrimitive(signature.trim()))
            }.toString()
        val request =
            MixuHttpRequest(
                method = "POST",
                url = "$normalizedBaseUrl/v1/storage/check-in/makeup",
                headers = buildHeaders(userId = userId, hasBody = true),
                body = body,
            )
        val response = executeSafely(request)
        if (response != null) {
            if (response.isSuccess && response.body.isNotBlank()) {
                runCatching {
                    val obj = json.parseToJsonElement(response.body).jsonObject
                    val summaryObj = obj["quota_summary"]?.jsonObject ?: obj
                    val parsedState = parseStorageQuotaSummary(summaryObj, fallback = currentState)
                    return StorageCheckInResult(
                        alreadyCheckedIn = obj.booleanField("already_checked_in") ?: false,
                        newlyLeveledUp = obj.booleanField("newly_leveled_up") ?: false,
                        quotaState = parsedState,
                        offlineFallback = false,
                    )
                }
            } else if (!response.isSuccess && response.statusCode in 400..499) {
                return StorageCheckInResult(
                    alreadyCheckedIn = cleanTargetDay in currentState.recordedCheckinDayKeys,
                    newlyLeveledUp = false,
                    quotaState = currentState,
                    offlineFallback = false,
                    error = parseErrorEnvelope(response),
                )
            }
        }

        val effectiveNow =
            nowDayKey?.trim()?.takeIf { it.isNotEmpty() } ?: LocalDate.now().toString()
        val updated = currentState.makeupCheckInWithAd(cleanTargetDay, effectiveNow)
        return StorageCheckInResult(
            alreadyCheckedIn = cleanTargetDay in currentState.recordedCheckinDayKeys,
            newlyLeveledUp = updated.storageLevel.level > currentState.storageLevel.level,
            quotaState = updated,
            offlineFallback = true,
        )
    }

    /**
     * Requests a presigned download URL for [uploadId] within daily and monthly egress quotas
     * (`POST /v1/storage/objects/{uploadId}/download-intent`), parsing
     * `DAILY_DOWNLOAD_QUOTA_EXCEEDED` and `FILE_SIZE_LIMIT_EXCEEDED` error envelopes.
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun requestDownloadIntent(
        userId: String,
        uploadId: String,
        dayKey: String? = null,
        monthKey: String? = null,
        currentState: StorageQuotaState = StorageQuotaState(),
    ): StorageDownloadIntentResult {
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(uploadId.isNotBlank()) { "uploadId must not be blank" }

        val encodedUploadId = URLEncoder.encode(uploadId.trim(), Charsets.UTF_8.name())
        val body =
            buildJsonObject {
                dayKey?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    put("day_key", JsonPrimitive(it))
                }
                monthKey?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    put("month_key", JsonPrimitive(it))
                }
            }.toString()
        val request =
            MixuHttpRequest(
                method = "POST",
                url = "$normalizedBaseUrl/v1/storage/objects/$encodedUploadId/download-intent",
                headers = buildHeaders(userId = userId, hasBody = true),
                body = body,
            )
        val response = executeSafely(request)
        if (response != null) {
            if (response.isSuccess && response.body.isNotBlank()) {
                runCatching {
                    val obj = json.parseToJsonElement(response.body).jsonObject
                    return StorageDownloadIntentResult(
                        uploadId = obj.stringField("upload_id") ?: uploadId,
                        allowed = true,
                        downloadUrl = obj.stringField("download_url"),
                        expiresAt = obj.stringField("expires_at"),
                        sizeBytes = obj.longField("size_bytes") ?: 0L,
                        sha256 = obj.stringField("sha256"),
                        dailyDownloadRemainingBytes =
                            obj.longField("daily_download_remaining_bytes")
                                ?: currentState.remainingDailyDownloadBytes,
                        monthlyDownloadRemainingBytes =
                            obj.longField("monthly_download_remaining_bytes")
                                ?: currentState.remainingMonthlyDownloadBytes,
                        offlineFallback = false,
                    )
                }
            } else if (!response.isSuccess) {
                return StorageDownloadIntentResult(
                    uploadId = uploadId,
                    allowed = false,
                    dailyDownloadRemainingBytes = currentState.remainingDailyDownloadBytes,
                    monthlyDownloadRemainingBytes = currentState.remainingMonthlyDownloadBytes,
                    offlineFallback = false,
                    error = parseErrorEnvelope(response),
                )
            }
        }

        return StorageDownloadIntentResult(
            uploadId = uploadId,
            allowed = false,
            dailyDownloadRemainingBytes = currentState.remainingDailyDownloadBytes,
            monthlyDownloadRemainingBytes = currentState.remainingMonthlyDownloadBytes,
            offlineFallback = true,
        )
    }

    /**
     * Requests a presigned direct upload URL (`POST /v1/uploads/intent`) and parses
     * `FILE_SIZE_LIMIT_EXCEEDED` and `STORAGE_QUOTA_EXCEEDED` error envelopes.
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun requestUploadIntent(
        userId: String,
        filename: String,
        contentType: String,
        sizeBytes: Long,
        sha256: String,
        purpose: String = "bookshelf_backup",
        currentState: StorageQuotaState = StorageQuotaState(),
    ): StorageUploadIntentResult {
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(filename.isNotBlank()) { "filename must not be blank" }
        require(contentType.isNotBlank()) { "contentType must not be blank" }
        require(sizeBytes > 0L) { "sizeBytes must be positive" }
        require(sha256.isNotBlank()) { "sha256 must not be blank" }

        val body =
            buildJsonObject {
                put("filename", JsonPrimitive(filename))
                put("content_type", JsonPrimitive(contentType))
                put("size_bytes", JsonPrimitive(sizeBytes))
                put("sha256", JsonPrimitive(sha256))
                put("purpose", JsonPrimitive(purpose))
            }.toString()
        val request =
            MixuHttpRequest(
                method = "POST",
                url = "$normalizedBaseUrl/v1/uploads/intent",
                headers = buildHeaders(userId = userId, hasBody = true),
                body = body,
            )
        val response = executeSafely(request)
        if (response != null) {
            if (response.isSuccess && response.body.isNotBlank()) {
                runCatching {
                    val obj = json.parseToJsonElement(response.body).jsonObject
                    return StorageUploadIntentResult(
                        allowed = true,
                        uploadId = obj.stringField("upload_id"),
                        uploadUrl = obj.stringField("upload_url"),
                        expiresAt = obj.stringField("expires_at"),
                        maxSizeBytes =
                            obj.longField("max_size_bytes") ?: currentState.maxFileSizeBytes,
                        offlineFallback = false,
                    )
                }
            } else if (!response.isSuccess) {
                return StorageUploadIntentResult(
                    allowed = false,
                    maxSizeBytes = currentState.maxFileSizeBytes,
                    offlineFallback = false,
                    error = parseErrorEnvelope(response),
                )
            }
        }

        val offlineError =
            when {
                sizeBytes > currentState.maxFileSizeBytes ->
                    ApiErrorEnvelope(
                        code = ApiErrorEnvelope.CODE_FILE_SIZE_LIMIT_EXCEEDED,
                        message = "File exceeds single-file limit",
                    )
                !currentState.canUploadFileBytes(sizeBytes) ->
                    ApiErrorEnvelope(
                        code = ApiErrorEnvelope.CODE_STORAGE_QUOTA_EXCEEDED,
                        message = "Insufficient cloud storage quota",
                    )
                else -> null
            }
        return StorageUploadIntentResult(
            allowed = false,
            maxSizeBytes = currentState.maxFileSizeBytes,
            offlineFallback = true,
            error = offlineError,
        )
    }

    /**
     * Verifies a rewarded ad or E-Ink QR completion to grant a `+50 MB` daily download egress
     * boost (`POST /v1/storage/download-boost/verify`). Falls back locally via
     * [StorageQuotaState.applyDownloadAdBoost] when offline.
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun verifyDownloadAdBoost(
        userId: String,
        challengeOrAdId: String,
        signature: String,
        dayKey: String? = null,
        currentState: StorageQuotaState = StorageQuotaState(),
    ): DownloadAdBoostResult {
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(challengeOrAdId.isNotBlank()) { "challengeOrAdId must not be blank" }
        require(signature.isNotBlank()) { "signature must not be blank" }

        val cleanChallengeId = challengeOrAdId.trim()
        val body =
            buildJsonObject {
                put("challenge_or_ad_id", JsonPrimitive(cleanChallengeId))
                put("signature", JsonPrimitive(signature.trim()))
                dayKey?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    put("day_key", JsonPrimitive(it))
                }
            }.toString()
        val request =
            MixuHttpRequest(
                method = "POST",
                url = "$normalizedBaseUrl/v1/storage/download-boost/verify",
                headers = buildHeaders(userId = userId, hasBody = true),
                body = body,
            )
        val response = executeSafely(request)
        if (response != null) {
            if (response.isSuccess && response.body.isNotBlank()) {
                runCatching {
                    val obj = json.parseToJsonElement(response.body).jsonObject
                    val summaryObj = obj["quota_summary"]?.jsonObject
                    val updatedState =
                        if (summaryObj != null) {
                            parseStorageQuotaSummary(summaryObj, fallback = currentState)
                        } else {
                            currentState
                        }
                    return DownloadAdBoostResult(
                        challengeOrAdId = obj.stringField("challenge_or_ad_id") ?: cleanChallengeId,
                        grantedBoostBytes = obj.longField("granted_boost_bytes") ?: 0L,
                        dailyDownloadAdBoostBytes =
                            obj.longField("daily_download_ad_boost_bytes")
                                ?: updatedState.dailyDownloadAdBoostBytes,
                        dailyAdBoostsUsed =
                            obj.intField("daily_ad_boosts_used") ?: updatedState.dailyAdBoostsUsed,
                        dailyAdBoostsCap =
                            obj.intField("daily_ad_boosts_cap") ?: updatedState.dailyAdBoostsCap,
                        quotaState = updatedState,
                        offlineFallback = false,
                    )
                }
            } else if (!response.isSuccess && response.statusCode in 400..499) {
                return DownloadAdBoostResult(
                    challengeOrAdId = cleanChallengeId,
                    grantedBoostBytes = 0L,
                    dailyDownloadAdBoostBytes = currentState.dailyDownloadAdBoostBytes,
                    dailyAdBoostsUsed = currentState.dailyAdBoostsUsed,
                    dailyAdBoostsCap = currentState.dailyAdBoostsCap,
                    quotaState = currentState,
                    offlineFallback = false,
                    error = parseErrorEnvelope(response),
                )
            }
        }

        val updated = currentState.applyDownloadAdBoost()
        val granted =
            (updated.dailyDownloadAdBoostBytes - currentState.dailyDownloadAdBoostBytes)
                .coerceAtLeast(0L)
        return DownloadAdBoostResult(
            challengeOrAdId = cleanChallengeId,
            grantedBoostBytes = granted,
            dailyDownloadAdBoostBytes = updated.dailyDownloadAdBoostBytes,
            dailyAdBoostsUsed = updated.dailyAdBoostsUsed,
            dailyAdBoostsCap = updated.dailyAdBoostsCap,
            quotaState = updated,
            offlineFallback = true,
        )
    }

    /**
     * Parses a machine-readable [ApiErrorEnvelope] from a non-2xx [MixuHttpResponse] when present.
     */
    fun parseErrorEnvelope(response: MixuHttpResponse): ApiErrorEnvelope? {
        if (response.body.isBlank()) return null
        return runCatching {
            val obj = json.parseToJsonElement(response.body).jsonObject
            val code = obj.stringField("code") ?: return@runCatching null
            ApiErrorEnvelope(
                code = code,
                message = obj.stringField("message").orEmpty(),
                requestId = obj.stringField("request_id"),
            )
        }.getOrNull()
    }

    private fun buildStorageQuotaUrl(
        dayKey: String?,
        monthKey: String?,
    ): String {
        val params =
            buildList {
                dayKey?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    add("day_key=${URLEncoder.encode(it, Charsets.UTF_8.name())}")
                }
                monthKey?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    add("month_key=${URLEncoder.encode(it, Charsets.UTF_8.name())}")
                }
            }
        return if (params.isEmpty()) {
            "$normalizedBaseUrl/v1/storage/quota"
        } else {
            "$normalizedBaseUrl/v1/storage/quota?${params.joinToString("&")}"
        }
    }

    private fun parseStorageQuotaSummary(
        obj: JsonObject,
        fallback: StorageQuotaState = StorageQuotaState(),
    ): StorageQuotaState {
        val level =
            obj.intField("storage_level")?.let { StorageLevel.fromLevel(it) }
                ?: fallback.storageLevel
        val activePlan =
            if (obj.containsKey("active_plan_id")) {
                SubscriptionPlan.fromPlanId(obj.stringField("active_plan_id"))
            } else {
                fallback.activePlan
            }
        val status =
            obj.stringField("subscription_status")?.let { raw ->
                ClientStorageStatus.entries.firstOrNull { it.name == raw }
            } ?: fallback.status

        val subscriptionBonusBytes =
            obj.longField("subscription_bonus_bytes")
                ?: (activePlan?.bonusQuotaBytes ?: 0L)
        val quotaLimitBytes = obj.longField("quota_limit_bytes")
        val baseQuotaBytes =
            obj.longField("base_quota_bytes")
                ?: quotaLimitBytes?.let { (it - subscriptionBonusBytes).coerceAtLeast(0L) }
                ?: level.baseQuotaBytes

        return fallback.copy(
            storageLevel = level,
            usedBytes = obj.longField("used_bytes") ?: fallback.usedBytes,
            baseQuotaBytes = baseQuotaBytes,
            subscriptionBonusBytes = subscriptionBonusBytes,
            maxFileSizeBytes =
                obj.longField("max_file_size_bytes")
                    ?: maxOf(level.maxFileSizeBytes, activePlan?.minMaxFileSizeBytes ?: 0L),
            dailyDownloadQuotaBytes =
                obj.longField("daily_download_quota_bytes")
                    ?: (level.dailyDownloadQuotaBytes + (activePlan?.bonusDailyEgressBytes ?: 0L)),
            dailyDownloadUsedBytes =
                obj.longField("daily_download_used_bytes") ?: fallback.dailyDownloadUsedBytes,
            dailyDownloadAdBoostBytes =
                obj.longField("daily_download_ad_boost_bytes")
                    ?: fallback.dailyDownloadAdBoostBytes,
            dailyAdBoostsUsed =
                obj.intField("daily_ad_boosts_used") ?: fallback.dailyAdBoostsUsed,
            dailyAdBoostsCap =
                obj.intField("daily_ad_boosts_cap") ?: fallback.dailyAdBoostsCap,
            monthlyDownloadUsedBytes =
                obj.longField("monthly_download_used_bytes") ?: fallback.monthlyDownloadUsedBytes,
            monthlyDownloadCapBytes =
                obj.longField("monthly_download_cap_bytes")
                    ?: (
                        level.monthlyDownloadCapBytes +
                            (activePlan?.bonusMonthlyEgressBytes ?: 0L)
                    ),
            checkinDays = obj.intField("checkin_days") ?: fallback.checkinDays,
            checkinStreak = obj.intField("checkin_streak") ?: fallback.checkinStreak,
            checkedInToday = obj.booleanField("checked_in_today") ?: fallback.checkedInToday,
            monthlyMakeupUsed = obj.intField("monthly_makeup_used") ?: fallback.monthlyMakeupUsed,
            monthlyMakeupCap = obj.intField("monthly_makeup_cap") ?: fallback.monthlyMakeupCap,
            readingMinutes = obj.intField("reading_minutes") ?: fallback.readingMinutes,
            chaptersRead = obj.intField("chapters_read") ?: fallback.chaptersRead,
            graphDownloads = obj.intField("graph_downloads") ?: fallback.graphDownloads,
            aiQueries = obj.intField("ai_queries") ?: fallback.aiQueries,
            completedGraphBuilds =
                obj.intField("completed_graph_builds") ?: fallback.completedGraphBuilds,
            status = status,
            activePlan = activePlan,
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
        const val DEFAULT_PRODUCTION_BASE_URL = "https://api.mixuread.com"
        const val DEFAULT_BUILD_COST_MINOR = 6_000L
        const val DEFAULT_OFFLINE_CHALLENGE_TTL_SECONDS = 300L
        const val HEADER_USER_ID = "X-Mixu-User-Id"
        const val HEADER_STATELESS_MODE = "X-Mixu-Stateless-Mode"
        private const val MAX_COPILOT_HISTORY_TURNS = 12
        private const val MINOR_UNITS_PER_CREDIT = 100L
        private const val HEADER_ACCEPT = "Accept"
        private const val HEADER_CONTENT_TYPE = "Content-Type"
        private const val CONTENT_TYPE_JSON = "application/json"
        private const val PRODUCTION_CONNECT_TIMEOUT_MS = 6_000
        private const val PRODUCTION_READ_TIMEOUT_MS = 22_000

        /**
         * Creates a production [MixuApiClient] targeting `https://api.mixuread.com`.
         *
         * Under [DistributionChannel.CN_APP_STORE] (the default App Store compliant channel),
         * custom server URLs are strictly ignored and locked to [DEFAULT_PRODUCTION_BASE_URL].
         * Under [DistributionChannel.GITHUB_FOSS_APK], the user's self-hosted endpoint is honored
         * when enabled (ADR-037).
         */
        fun createProductionClient(
            config: ReaderDisplayConfig? = null,
            channel: DistributionChannel = DistributionChannel.CURRENT,
        ): MixuApiClient {
            val resolvedBaseUrl =
                if (channel.allowsCustomServerEndpoint &&
                    config?.selfHostedApiEnabled == true &&
                    config.selfHostedApiUrl.isNotBlank()
                ) {
                    config.selfHostedApiUrl
                        .trim()
                        .removeSuffix("/v1")
                        .removeSuffix("/v1/")
                } else {
                    DEFAULT_PRODUCTION_BASE_URL
                }
            return MixuApiClient(
                baseUrl = resolvedBaseUrl,
                statelessSelfHostedMode =
                    channel.allowsCustomServerEndpoint &&
                        config?.selfHostedApiEnabled == true &&
                        config.selfHostedStatelessMode,
                transport =
                    JdkHttpTransport(
                        connectTimeoutMs = PRODUCTION_CONNECT_TIMEOUT_MS,
                        readTimeoutMs = PRODUCTION_READ_TIMEOUT_MS,
                    ),
            )
        }
    }
}
