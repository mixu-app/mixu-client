// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.network

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AuthRedirectTest {
    @Test fun `HTTP transport disables redirects before applying credential headers`() {
        val connection =
            object : HttpURLConnection(URI("https://auth.invalid").toURL()) {
                override fun connect() = Unit

                override fun disconnect() = Unit

                override fun usingProxy() = false

                override fun getResponseCode() = 302

                override fun getInputStream() = ByteArrayInputStream(byteArrayOf())

                override fun getHeaderFields(): Map<String, List<String>> =
                    mapOf("Location" to listOf("https://other.invalid"))
            }
        var opens = 0
        val transport =
            JdkHttpTransport(connectionFactory = {
                opens++
                connection
            })
        val response =
            transport.execute(
                MixuHttpRequest(
                    "GET",
                    "https://auth.invalid/v1/me",
                    mapOf("Authorization" to "Bearer unit-only-token"),
                ),
            )
        assertEquals(302, response.statusCode)
        assertEquals(1, opens)
        assertFalse(connection.instanceFollowRedirects)
    }
}
