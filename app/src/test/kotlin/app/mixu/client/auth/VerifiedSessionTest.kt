// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerifiedSessionTest {
    private class SharedRecord {
        var value: String? = null
        var generation = 0L
    }

    private class MemoryStorage(
        private val record: SharedRecord = SharedRecord(),
    ) : CredentialStorage {
        private val owner =
            synchronized(record) {
                record.generation++
                record.generation
            }

        override fun isCurrentOwner(): Boolean = synchronized(record) { owner == record.generation }

        var value: String?
            get() = record.value
            set(value) {
                record.value = value
            }

        override fun read() = value

        override fun write(value: String) {
            this.value = value
        }

        override fun clear() {
            value = null
        }

        override fun compareAndSet(
            expected: String?,
            replacement: String?,
        ): Boolean =
            synchronized(this) {
                if (!isCurrentOwner() || value != expected) {
                    false
                } else {
                    value = replacement
                    true
                }
            }
    }

    @Test fun `secure restoration fails closed on expiry corruption and endpoint mismatch`() {
        val storage = MemoryStorage()
        val repository = VerifiedSessionRepository(storage)
        val endpoint = "https://auth.invalid"
        val credential = VerifiedCredential("unit-only-credential", endpoint, Long.MAX_VALUE)
        val session = UserSession.createAuthenticated("reader-test", "Reader", credential)
        repository.save(session)
        assertEquals(
            "reader-test",
            VerifiedSessionRepository(storage).restore(endpoint).anonymousId,
        )
        assertTrue(repository.restore(endpoint).isAuthenticated)
        assertFalse(repository.restore("https://another.invalid").isAuthenticated)
        assertEquals(null, storage.value)
        repository.save(session)
        assertFalse(repository.restore(endpoint, Long.MAX_VALUE).isAuthenticated)
        assertEquals(null, storage.value)
        storage.value = "corrupt"
        assertFalse(repository.restore(endpoint).isAuthenticated)
        assertEquals(null, storage.value)
        repository.save(session)
        repository.logout()
        assertFalse(VerifiedSessionRepository(storage).restore(endpoint).isAuthenticated)
    }

    @Test fun `identity maps cannot manufacture authenticated sessions or serialize credentials`() {
        val session =
            UserSession.createAuthenticated(
                "reader-test",
                "Reader",
                VerifiedCredential("unit-only-credential", "https://auth.invalid", Long.MAX_VALUE),
            )
        assertFalse(UserSession.fromMap(session.toMap()).isAuthenticated)
        assertFalse(UserSession(isGuest = false).isAuthenticated)
        assertFalse(session.toMap().toString().contains("unit-only-credential"))
        assertFalse(session.toString().contains("unit-only-credential"))
        assertFalse(
            VerifiedCredential("token\nattack", "https://auth.invalid", Long.MAX_VALUE)
                .validFor("https://auth.invalid"),
        )
        assertFalse(
            VerifiedCredential("test-token", "https://auth.invalid", Long.MAX_VALUE)
                .validFor("http://auth.invalid"),
        )
    }

    @Test fun `stale rejection cannot clear a replacement session`() {
        val storage = MemoryStorage()
        val repository = VerifiedSessionRepository(storage)
        val endpoint = "https://auth.invalid"
        val old = VerifiedCredential("old-unit-token", endpoint, Long.MAX_VALUE)
        val replacement = VerifiedCredential("new-unit-token", endpoint, Long.MAX_VALUE)
        val current = UserSession.createAuthenticated("reader-test", "Reader", replacement)
        repository.save(current)
        assertTrue(repository.reject(current, old) === current)
        assertTrue(repository.restore(endpoint).isAuthenticated)
        assertTrue(repository.expire(current, 1000L) === current)
        assertFalse(repository.expire(current, Long.MAX_VALUE).isAuthenticated)
        assertEquals(null, storage.value)
        repository.save(current)
        assertFalse(repository.reject(current, replacement).isAuthenticated)
        assertEquals(null, storage.value)
    }

    @Test fun `disposed and stale owners cannot mutate a replacement session on shared storage`() {
        val record = SharedRecord()
        val first = VerifiedSessionRepository(MemoryStorage(record))
        val oldCredential =
            VerifiedCredential("old-owner-token", "https://auth.invalid", Long.MAX_VALUE)
        val old = UserSession.createAuthenticated("reader", "Reader", oldCredential)
        first.save(old)
        val second = VerifiedSessionRepository(MemoryStorage(record))
        kotlin.test.assertFails { first.save(old) }
        first.reject(old, oldCredential)
        first.expire(old, Long.MAX_VALUE)
        first.logout()
        second.restore("https://auth.invalid")
        val fresh =
            UserSession.createAuthenticated(
                "reader",
                "Reader",
                VerifiedCredential("new-owner-token", "https://auth.invalid", Long.MAX_VALUE),
            )
        second.save(fresh)
        kotlin.test.assertFails { first.save(old) }
        // Even before disposal, compare-and-set protects the replacement from stale rejection.
        first.reject(old, oldCredential)
        assertTrue(second.restore("https://auth.invalid").isAuthenticated)
        first.close()
        kotlin.test.assertFails { first.save(old) }
        first.reject(old, oldCredential)
        first.expire(old, Long.MAX_VALUE)
        first.logout()
        assertFalse(first.restore("https://wrong.invalid").isAuthenticated)
        assertEquals(
            "new-owner-token",
            second.restore("https://auth.invalid").credential?.accessToken,
        )
        val uiOwner = RequestOwner()
        uiOwner.close()
        assertFalse(uiOwner.isActive())
    }

    @Test fun `wallet completions require the active owner and latest request generation`() {
        val oldOwner = RequestOwner()
        val oldRequest = oldOwner.beginRequest()
        val latest = oldOwner.beginRequest()
        assertFalse(oldOwner.accepts(oldRequest))
        assertTrue(oldOwner.accepts(latest))
        oldOwner.close()
        val newOwner = RequestOwner()
        val newRequest = newOwner.beginRequest()
        assertFalse(oldOwner.accepts(latest))
        assertTrue(newOwner.accepts(newRequest))
    }
}
