// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.auth

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated encryption envelope: fixed-size nonce followed by GCM ciphertext and tag. */
internal object CredentialCipher {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun encrypt(
        value: String,
        key: SecretKey,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        require(cipher.iv.size == IV_BYTES)
        return cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    }

    fun decrypt(
        value: ByteArray,
        key: SecretKey,
    ): String {
        require(value.size > IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(TAG_BITS, value.copyOfRange(0, IV_BYTES)),
        )
        return String(cipher.doFinal(value.copyOfRange(IV_BYTES, value.size)), Charsets.UTF_8)
    }
}
