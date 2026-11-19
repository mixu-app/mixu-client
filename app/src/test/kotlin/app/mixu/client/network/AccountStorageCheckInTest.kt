// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.network

import app.mixu.client.auth.VerifiedCredential
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountStorageCheckInTest {
    private val endpoint = "https://account.invalid"
    private val credential = VerifiedCredential("unit-token", endpoint, Long.MAX_VALUE)
    private val quota = """{"user_id":"reader","quota_limit_bytes":400,"used_bytes":1,
        "subscription_status":"ACTIVE","storage_level":2,"checked_in_today":true,
        "base_quota_bytes":400,"subscription_bonus_bytes":0,"checkin_days":8}"""
    private val ack = """{"already_checked_in":false,"newly_leveled_up":true,
        "quota_summary":$quota}"""

    @Test fun `explicit checkin sends server dated request and adopts only owned ack`() {
        val client =
            MixuApiClient(
                endpoint,
                managedAuthentication = true,
                credentialProvider = { credential },
                transport = { request ->
                    assertEquals("POST", request.method)
                    assertEquals("$endpoint/v1/storage/check-in", request.url)
                    assertEquals("{}", request.body)
                    assertEquals(
                        "Bearer unit-token",
                        request.headers[MixuApiClient.HEADER_AUTHORIZATION],
                    )
                    assertFalse(request.headers.containsKey(MixuApiClient.HEADER_USER_ID))
                    MixuHttpResponse(200, ack)
                },
            )
        val result = assertNotNull(client.checkInAuthenticatedStorage("reader", credential))
        assertFalse(result.alreadyCheckedIn)
        assertTrue(result.newlyLeveledUp)
        assertEquals(2, result.quota.level)
        assertEquals(8L, result.quota.amounts["checkin_days"])
    }

    @Test fun `failed malformed and contradictory acknowledgements grant nothing`() {
        var body = ack
        var status = 200
        val client =
            MixuApiClient(
                endpoint,
                managedAuthentication = true,
                credentialProvider = { credential },
                transport = { MixuHttpResponse(status, body) },
            )
        for (invalid in listOf(
            "{}",
            ack.replace("reader", "other"),
            ack.replace("false", "\"false\""),
            ack.replace("false", "true"),
            ack.replace("\"newly_leveled_up\":true,", ""),
            ack.replace("\"checked_in_today\":true", "\"checked_in_today\":false"),
            ack.replace("\"checked_in_today\":true,", ""),
            ack.replace("\"base_quota_bytes\":400", "\"base_quota_bytes\":399"),
        )) {
            body = invalid
            assertNull(client.checkInAuthenticatedStorage("reader", credential))
        }
        body = ack
        status = 503
        assertNull(client.checkInAuthenticatedStorage("reader", credential))
        status = 200
        body =
            ack
                .replace("\"already_checked_in\":false", "\"already_checked_in\":true")
                .replace("\"newly_leveled_up\":true", "\"newly_leveled_up\":false")
        assertTrue(
            assertNotNull(
                client.checkInAuthenticatedStorage("reader", credential),
            ).alreadyCheckedIn,
        )
    }

    @Test fun `guest and selfhost never send managed checkin`() {
        var calls = 0
        for (managed in listOf(true, false)) {
            val client =
                MixuApiClient(
                    endpoint,
                    managedAuthentication = managed,
                    transport = {
                        calls++
                        MixuHttpResponse(200, ack)
                    },
                )
            assertNull(client.checkInAuthenticatedStorage("guest", null))
        }
        assertEquals(0, calls)
    }

    @Test fun `checkin excludes duplicate and earlier reads and invalid owners`() {
        val requests = AccountQuotaRequests()
        val oldRead = assertNotNull(requests.beginRead())
        assertNull(requests.beginCheckIn(null, false))
        assertNull(requests.beginCheckIn(credential, null))
        assertNull(requests.beginCheckIn(credential, true))
        val post = assertNotNull(requests.beginCheckIn(credential, false))
        assertFalse(requests.accepts(oldRead, credential, credential))
        assertNull(requests.beginRead())
        assertNull(requests.beginCheckIn(credential, false))
        assertTrue(requests.accepts(post, credential, credential))
        val replacement = VerifiedCredential("new-token", endpoint, Long.MAX_VALUE)
        assertFalse(requests.accepts(post, credential, replacement))
        requests.finishCheckIn(post)
        val read = assertNotNull(requests.beginRead())
        assertFalse(requests.accepts(post, credential, credential))
        requests.close()
        assertFalse(requests.accepts(read, credential, credential))
        assertNull(requests.beginCheckIn(credential, false))
    }
}
