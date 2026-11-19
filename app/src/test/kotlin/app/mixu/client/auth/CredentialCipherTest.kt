// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.auth

import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse

class CredentialCipherTest {
    @Test fun `encryption randomizes nonce and rejects tampering`() {
        val key = KeyGenerator.getInstance("AES").generateKey()
        val value = "unit-only-secret-token"
        val first = CredentialCipher.encrypt(value, key)
        val second = CredentialCipher.encrypt(value, key)
        assertFalse(first.contentEquals(second))
        assertFalse(first.toString(Charsets.ISO_8859_1).contains(value))
        assertEquals(value, CredentialCipher.decrypt(first, key))
        val wrongKey = KeyGenerator.getInstance("AES").generateKey()
        assertFails { CredentialCipher.decrypt(first, wrongKey) }
        first[first.lastIndex] = (first.last().toInt() xor 1).toByte()
        assertFails { CredentialCipher.decrypt(first, key) }
        assertFails { CredentialCipher.decrypt(byteArrayOf(), key) }
    }
}
