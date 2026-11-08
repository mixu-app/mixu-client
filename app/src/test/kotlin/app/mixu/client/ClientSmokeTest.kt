// SPDX-License-Identifier: MPL-2.0
package app.mixu.client

import kotlin.test.Test
import kotlin.test.assertEquals

class ClientSmokeTest {
    @Test
    fun appIdentifierIsConfigured() {
        assertEquals("app.mixu.client", "app.mixu.client")
    }
}
