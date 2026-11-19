// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.network

import app.mixu.client.auth.VerifiedCredential
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class AccountDispatchBindingTest {
    private val endpoint = MixuApiClient.DEFAULT_PRODUCTION_BASE_URL
    private val first = VerifiedCredential("first-unit-token", endpoint, Long.MAX_VALUE)
    private val next = VerifiedCredential("next-unit-token", endpoint, Long.MAX_VALUE)

    @Test fun `queued account intent cannot dispatch with replacement or logged out credential`() {
        var current: VerifiedCredential? = first
        var calls = 0
        val client =
            MixuApiClient.createProductionClient(
                credentialProvider = { current },
                transport = {
                    calls++
                    MixuHttpResponse(503, "")
                },
            )
        // A UI intent captures first, then waits for IO while another owner signs in or logs out.
        for (replacement in listOf(
            next,
            null,
            VerifiedCredential("expired-unit-token", endpoint, 1L),
            VerifiedCredential("other-endpoint-token", "https://other.invalid", Long.MAX_VALUE),
        )) {
            current = replacement
            assertNull(client.checkInAuthenticatedStorage("first-reader", first))
            assertNull(client.fetchAuthenticatedStorageQuota("first-reader", first))
            assertNull(client.fetchAuthenticatedWallet("first-reader", first))
        }
        assertEquals(0, calls)
    }

    @Test fun `single selection binds headers and rejection to initiating token`() {
        var current: VerifiedCredential? = first
        var selections = 0
        var calls = 0
        var rejected: VerifiedCredential? = null
        val client =
            MixuApiClient.createProductionClient(
                credentialProvider = {
                    selections++
                    // A replacement after selection cannot change the selected authorization header.
                    current.also { current = next }
                },
                onAuthenticationRejected = { rejected = it },
                transport = {
                    calls++
                    assertEquals(
                        "Bearer first-unit-token",
                        it.headers[MixuApiClient.HEADER_AUTHORIZATION],
                    )
                    MixuHttpResponse(401, "")
                },
            )
        assertNull(client.checkInAuthenticatedStorage("first-reader", first))
        assertEquals(1, selections)
        assertEquals(1, calls)
        assertSame(first, rejected)
        assertSame(next, current)
        assertNull(client.checkInAuthenticatedStorage("first-reader", first))
        assertEquals(1, calls)
    }
}
