// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.network

import app.mixu.client.auth.VerifiedCredential
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountWalletTest {
    private val endpoint = "https://account.invalid"
    private val credential = VerifiedCredential("unit-token", endpoint, Long.MAX_VALUE)
    private val body =
        """
        {"user_id":"reader","promo_credits_minor":1000,"sub_credits_minor":200,
        "paid_credits_minor":300,"available_balance_minor":1500,"reserved_balance_minor":100}
        """.trimIndent()

    @Test fun `account hydration validates numeric wallet ownership and available sum`() {
        var response = body
        var status = 200
        val client =
            MixuApiClient(
                endpoint,
                managedAuthentication = true,
                credentialProvider = { credential },
                transport = { MixuHttpResponse(status, response) },
            )
        val wallet = assertNotNull(client.fetchAuthenticatedWallet("reader", credential))
        assertEquals(10, wallet.promoCredits)
        assertEquals(2, wallet.subCredits)
        assertEquals(3, wallet.paidCredits)
        for (invalid in listOf(
            "{}",
            body.replace("reader", "other"),
            body.replace(":1000", ":-1"),
            body.replace(":1000", ":\"1000\""),
            body.replace(":1500", ":1400"),
            body.replace(":1000", ":9223372036854775807"),
        )) {
            response = invalid
            assertNull(client.fetchAuthenticatedWallet("reader", credential))
        }
        response = body
        status = 503
        assertNull(client.fetchAuthenticatedWallet("reader", credential))
    }

    @Test fun `guest hydration cannot make requests or invent a default balance`() {
        var calls = 0
        val client =
            MixuApiClient(
                endpoint,
                managedAuthentication = true,
                transport = {
                    calls++
                    MixuHttpResponse(200, body)
                },
            )
        assertNull(client.fetchAuthenticatedWallet("guest", null))
        assertEquals(0, calls)
        assertTrue(client.baseUrl == endpoint)
    }
}
