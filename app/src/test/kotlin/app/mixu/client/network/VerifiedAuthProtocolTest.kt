// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.network

import app.mixu.client.auth.VerifiedCredential
import app.mixu.client.config.DistributionChannel
import app.mixu.client.config.ReaderDisplayConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerifiedAuthProtocolTest {
    private val endpoint = "https://auth.invalid"
    private val validResponse =
        """
        {"access_token":"unit-only-token","token_type":"Bearer","expires_in_seconds":3600,
        "profile":{"user_id":"reader-test","username":"reader","display_name":"Reader",
        "email_verified":true,"storage_quota_bytes":100}}
        """.trimIndent()

    @Test fun `verified profile required and incomplete OTP is local`() {
        var calls = 0
        var body = validResponse
        var status = 200
        val client =
            MixuApiClient(endpoint, transport = {
                calls++
                MixuHttpResponse(status, body)
            }, managedAuthentication = true, nowMillis = { 1000L })
        for (otp in listOf("", "123", "abcdef", "1234567")) {
            assertNull(client.verifyEmailOtp("reader@example.invalid", otp))
        }
        assertEquals(0, calls)
        val verified = assertNotNull(client.verifyEmailOtp("reader@example.invalid", "123456"))
        assertEquals("reader-test", verified.userId)
        assertEquals("Reader", verified.displayName)
        assertFalse(verified.toString().contains("unit-only-token"))
        assertEquals(3601000L, verified.expiresAtEpochMillis)
        for (invalid in listOf(
            "{}",
            validResponse.replace("unit-only-token", ""),
            validResponse.replace("Bearer", "Basic"),
            validResponse.replace("3600", "0"),
            validResponse.replace("3600", "9223372036854775807"),
            validResponse.replace("\"user_id\":\"reader-test\",", ""),
            validResponse.replace("\"email_verified\":true", "\"email_verified\":false"),
            validResponse.replace("\"profile\"", "\"user\""),
            validResponse.replace("\"reader-test\"", "123"),
            validResponse.replace("3600", "\"3600\""),
        )) {
            body = invalid
            assertNull(client.verifyEmailOtp("reader@example.invalid", "123456"))
        }
        body = validResponse
        status = 401
        assertNull(client.verifyEmailOtp("reader@example.invalid", "123456"))
    }

    @Test fun `managed bearer rejects expiry and wrong endpoint`() {
        val requests = mutableListOf<MixuHttpRequest>()
        var credential: VerifiedCredential? = null
        var clock = 1000L
        val client =
            MixuApiClient(endpoint, transport = {
                requests.add(it)
                MixuHttpResponse(200, "{}")
            }, credentialProvider = {
                credential
            }, managedAuthentication = true, nowMillis = { clock })
        client.fetchWallet("guest-test")
        assertTrue(requests.isEmpty())
        credential = VerifiedCredential("unit-only-token", endpoint, 2000L)
        client.fetchWallet("reader-test")
        assertEquals(
            "Bearer unit-only-token",
            requests.last().headers[MixuApiClient.HEADER_AUTHORIZATION],
        )
        assertFalse(requests.last().headers.containsKey(MixuApiClient.HEADER_USER_ID))
        credential = VerifiedCredential("unit-only-token", "https://other.invalid", 2000L)
        client.fetchWallet("reader-test")
        assertEquals(1, requests.size)
        credential = VerifiedCredential("unit-only-token", endpoint, 2000L)
        clock = 2000L
        client.fetchWallet("reader-test")
        assertEquals(1, requests.size)
        client.sendEmailOtp("reader@example.invalid")
        assertEquals(2, requests.size)
        assertFalse(requests.last().headers.containsKey(MixuApiClient.HEADER_AUTHORIZATION))
        assertFalse(requests.last().headers.containsKey(MixuApiClient.HEADER_USER_ID))
    }

    @Test fun `stateless self host never inherits credentials`() {
        val requests = mutableListOf<MixuHttpRequest>()
        val client =
            MixuApiClient(endpoint, statelessSelfHostedMode = true, transport = {
                requests.add(it)
                MixuHttpResponse(200, "{}")
            }, credentialProvider = {
                VerifiedCredential(
                    "unit-only-token",
                    endpoint,
                    Long.MAX_VALUE,
                )
            })
        client.fetchWallet("local-reader")
        assertFalse(requests.single().headers.containsKey(MixuApiClient.HEADER_AUTHORIZATION))
        assertEquals("true", requests.single().headers[MixuApiClient.HEADER_STATELESS_MODE])
        val custom =
            ReaderDisplayConfig.DEFAULT.copy(
                selfHostedApiEnabled = true,
                selfHostedApiUrl = "https://selfhost.invalid",
            )
        assertEquals(
            "https://selfhost.invalid",
            MixuApiClient
                .createProductionClient(
                    custom,
                    DistributionChannel.GITHUB_FOSS_APK,
                ).baseUrl,
        )
        assertEquals(
            MixuApiClient.DEFAULT_PRODUCTION_BASE_URL,
            MixuApiClient
                .createProductionClient(
                    custom,
                    DistributionChannel.CN_APP_STORE,
                ).baseUrl,
        )
    }

    @Test fun `managed unauthorized response invalidates exactly the used credential`() {
        val credential = VerifiedCredential("unit-only-token", endpoint, Long.MAX_VALUE)
        var rejected: VerifiedCredential? = null
        val client =
            MixuApiClient(
                endpoint,
                transport = { MixuHttpResponse(401, "{}") },
                credentialProvider = { credential },
                managedAuthentication = true,
                onAuthenticationRejected = { rejected = it },
            )
        client.fetchWallet("reader-test")
        assertTrue(rejected === credential)
        rejected = null
        client.verifyEmailOtp("reader@example.invalid", "123456")
        assertNull(rejected)
    }

    @Test fun `sending code reports only actual sent response`() {
        for (body in listOf("{}", """{"status":"failed","expires_in_seconds":600}""")) {
            val client = MixuApiClient(endpoint, transport = { MixuHttpResponse(200, body) })
            assertTrue(client.sendEmailOtp("reader@example.invalid").offlineFallback)
        }
        val sent =
            MixuApiClient(endpoint, transport = {
                MixuHttpResponse(200, """{"status":"sent","expires_in_seconds":600}""")
            }).sendEmailOtp("reader@example.invalid")
        assertFalse(sent.offlineFallback)
        assertEquals("sent", sent.status)
    }
}
