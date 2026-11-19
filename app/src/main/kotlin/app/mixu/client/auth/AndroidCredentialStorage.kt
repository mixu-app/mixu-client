// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** AES-GCM ciphertext is excluded from Android backups; the key stays in AndroidKeyStore. */
class AndroidCredentialStorage(
    context: Context,
) : CredentialStorage {
    private val file = AtomicFile(File(context.noBackupFilesDir, "verified-session.enc"))
    private val owner =
        synchronized(LOCK) {
            generation++
            generation
        }

    override fun isCurrentOwner(): Boolean = synchronized(LOCK) { owner == generation }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec
                        .Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }.generateKey()
    }

    override fun read(): String? =
        synchronized(LOCK) {
            if (!isCurrentOwner()) return@synchronized null
            if (!file.baseFile.exists()) return@synchronized null
            try {
                CredentialCipher.decrypt(file.readFully(), key())
            } catch (_: Exception) {
                clear()
                null
            }
        }

    override fun write(value: String): Unit =
        synchronized(LOCK) {
            val encrypted = CredentialCipher.encrypt(value, key())
            val stream = file.startWrite()
            try {
                stream.write(encrypted)
                file.finishWrite(stream)
            } catch (failure: Exception) {
                file.failWrite(stream)
                throw failure
            }
        }

    override fun clear(): Unit =
        synchronized(LOCK) {
            file.delete()
        }

    override fun compareAndSet(
        expected: String?,
        replacement: String?,
    ): Boolean =
        synchronized(LOCK) {
            if (!isCurrentOwner() || read() != expected) return@synchronized false
            if (replacement == null) clear() else write(replacement)
            true
        }

    private companion object {
        val LOCK = Any()
        var generation = 0L
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "mixu.verified-session.v1"
    }
}
