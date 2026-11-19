// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.network

import app.mixu.client.auth.RequestOwner
import app.mixu.client.auth.VerifiedCredential
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountStorageQuotaTest {
    private val endpoint = "https://account.invalid"
    private val credential = VerifiedCredential("unit-token", endpoint, Long.MAX_VALUE)
    private val minimum =
        """
        {"user_id":"reader","quota_limit_bytes":555,"used_bytes":800,
        "subscription_status":"GRACE_PERIOD"}
        """.trimIndent()
    private val full =
        minimum.dropLast(1) +
            """
            ,
            "storage_level":4,"base_quota_bytes":400,"subscription_bonus_bytes":155,
            "max_file_size_bytes":19,"daily_download_quota_bytes":7,"daily_download_used_bytes":2,
            "daily_download_ad_boost_bytes":3,"daily_ad_boosts_used":1,"daily_ad_boosts_cap":6,
            "monthly_download_used_bytes":8,"monthly_download_cap_bytes":22,
            "checkin_days":12,"checkin_streak":2,"checked_in_today":true,
            "monthly_makeup_used":0,"monthly_makeup_cap":5,"reading_minutes":25,
            "chapters_read":3,"graph_downloads":1,"ai_queries":2,"completed_graph_builds":1,
            "active_plan_id":null,"grace_period_ends_at":"2030-01-01T00:00:00Z"}
            """.trimIndent()

    @Test fun `server snapshot preserves level caps and separate boost`() {
        val client =
            MixuApiClient(
                endpoint,
                managedAuthentication = true,
                credentialProvider = { credential },
                transport = { request ->
                    assertEquals("GET", request.method)
                    assertEquals("$endpoint/v1/storage/quota", request.url)
                    assertEquals(
                        "Bearer unit-token",
                        request.headers[MixuApiClient.HEADER_AUTHORIZATION],
                    )
                    assertFalse(request.headers.containsKey(MixuApiClient.HEADER_USER_ID))
                    MixuHttpResponse(200, full)
                },
            )
        val state = assertNotNull(client.fetchAuthenticatedStorageQuota("reader", credential))
        assertEquals(4, state.level)
        assertEquals(555L, state.quotaLimitBytes)
        assertEquals(800L, state.usedBytes)
        assertEquals(19L, state.amounts["max_file_size_bytes"])
        assertEquals(7L, state.amounts["daily_download_quota_bytes"])
        assertEquals(3L, state.amounts["daily_download_ad_boost_bytes"])
        assertEquals(12L, state.amounts["checkin_days"])
        assertEquals(true, state.checkedInToday)
        assertTrue(state.activePlanKnown)
        assertNull(state.activePlanId)
    }

    @Test fun `absent modern fields never invent level one defaults`() {
        val client =
            MixuApiClient(
                endpoint,
                managedAuthentication = true,
                credentialProvider = { credential },
                transport = { MixuHttpResponse(200, minimum) },
            )
        val state = assertNotNull(client.fetchAuthenticatedStorageQuota("reader", credential))
        assertNull(state.level)
        assertTrue(state.amounts.isEmpty())
        assertNull(state.checkedInToday)
        assertFalse(state.activePlanKnown)
    }

    @Test fun `malformed or unavailable quota never becomes a local default`() {
        var body = full
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
            full.replace("reader", "other"),
            full.replace(":555", ":554"),
            full.replace(":400", ":9223372036854775807"),
            full.replace(":19", ":0"),
            full.replace(":800", ":-1"),
            full.replace(":4,", ":6,"),
            full.replace(":555", ":\"555\""),
            full.replace(":true", ":\"true\""),
            full.replace("GRACE_PERIOD", "UNKNOWN"),
        )) {
            body = invalid
            assertNull(client.fetchAuthenticatedStorageQuota("reader", credential))
        }
        body = full
        status = 503
        assertNull(client.fetchAuthenticatedStorageQuota("reader", credential))
    }

    @Test fun `guest and stale owners cannot hydrate quota`() {
        var calls = 0
        val client =
            MixuApiClient(
                endpoint,
                managedAuthentication = true,
                transport = {
                    calls++
                    MixuHttpResponse(200, full)
                },
            )
        assertNull(client.fetchAuthenticatedStorageQuota("guest", null))
        assertEquals(0, calls)
        val owner = RequestOwner()
        val first = owner.beginRequest()
        assertTrue(owner.accepts(first, credential, credential))
        val replacement = VerifiedCredential("replacement-token", endpoint, Long.MAX_VALUE)
        assertFalse(owner.accepts(first, credential, replacement))
        val next = owner.beginRequest()
        assertFalse(owner.accepts(first, credential, credential))
        owner.close()
        assertFalse(owner.accepts(next, credential, credential))
        val newOwner = RequestOwner()
        assertTrue(newOwner.accepts(newOwner.beginRequest(), replacement, replacement))
    }
}
