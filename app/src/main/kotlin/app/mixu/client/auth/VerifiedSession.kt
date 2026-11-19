// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI

/** Credential values never participate in printable identity maps or generated data-class strings. */
class VerifiedCredential(
    val accessToken: String,
    val endpoint: String,
    val expiresAtEpochMillis: Long,
) {
    fun validFor(
        baseUrl: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean =
        accessToken.isNotBlank() &&
            accessToken.none { it.isWhitespace() || it.isISOControl() } &&
            expiresAtEpochMillis > nowMillis &&
            canonicalAuthEndpoint(endpoint)?.let { it == canonicalAuthEndpoint(baseUrl) } == true

    override fun toString(): String = "VerifiedCredential([redacted])"
}

internal fun canonicalAuthEndpoint(value: String): String? =
    runCatching {
        val uri = URI(value.trim().trimEnd('/'))
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank())
        require(uri.userInfo == null && uri.query == null && uri.fragment == null)
        val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
        "https://${uri.host.lowercase()}$port${uri.path.orEmpty().trimEnd('/')}"
    }.getOrNull()

/** Storage implementations must encrypt at rest. Null clears the current credential. */
interface CredentialStorage {
    fun read(): String?

    fun write(value: String)

    fun clear()

    /** New owners invalidate callbacks from previous owners of the same store. */
    fun isCurrentOwner(): Boolean

    /** Atomically replace the encrypted record only if it still matches this owner snapshot. */
    fun compareAndSet(
        expected: String?,
        replacement: String?,
    ): Boolean
}

/** Fail-closed restoration and logout, independent of Android for meaningful storage tests. */
class VerifiedSessionRepository(
    private val storage: CredentialStorage,
) {
    private var active = true
    private var snapshot: String? = storage.read()

    @Synchronized fun save(session: UserSession) {
        check(active && storage.isCurrentOwner()) { "Session owner disposed or replaced" }
        val credential = requireNotNull(session.credential)
        require(session.isAuthenticated && credential.validFor(credential.endpoint))
        val encoded =
            buildJsonObject {
                put(
                    "record_id",
                    JsonPrimitive(
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                    ),
                )
                put("access_token", JsonPrimitive(credential.accessToken))
                put("endpoint", JsonPrimitive(credential.endpoint))
                put("expires_at", JsonPrimitive(credential.expiresAtEpochMillis))
                put("user_id", JsonPrimitive(session.anonymousId))
                put("display_name", JsonPrimitive(session.displayName))
            }.toString()
        check(storage.compareAndSet(snapshot, encoded)) { "Session replaced by another owner" }
        snapshot = encoded
    }

    @Synchronized fun restore(
        endpoint: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): UserSession {
        if (!active || !storage.isCurrentOwner()) return UserSession.DEFAULT
        snapshot = storage.read()
        val session =
            runCatching {
                val obj =
                    Json
                        .parseToJsonElement(
                            snapshot ?: return UserSession.DEFAULT,
                        ).jsonObject

                fun field(key: String) = obj[key]?.jsonPrimitive?.contentOrNull.orEmpty()
                val credential =
                    VerifiedCredential(
                        field("access_token"),
                        field("endpoint"),
                        obj["expires_at"]?.jsonPrimitive?.longOrNull ?: 0L,
                    )
                require(credential.validFor(endpoint, nowMillis))
                require(field("user_id").isNotBlank() && field("display_name").isNotBlank())
                UserSession.createAuthenticated(field("user_id"), field("display_name"), credential)
            }.getOrNull()
        if (session == null) {
            storage.compareAndSet(snapshot, null)
            snapshot = null
        }
        return session ?: UserSession.DEFAULT
    }

    @Synchronized fun reject(
        current: UserSession,
        rejected: VerifiedCredential,
    ): UserSession {
        if (!active || !storage.isCurrentOwner() || current.credential !== rejected) return current
        logout()
        return UserSession.DEFAULT
    }

    @Synchronized fun expire(
        current: UserSession,
        nowMillis: Long = System.currentTimeMillis(),
    ): UserSession {
        if (!active || !storage.isCurrentOwner()) return current
        val credential = current.credential ?: return current
        return if (credential.validFor(
                credential.endpoint,
                nowMillis,
            )
        ) {
            current
        } else {
            reject(current, credential)
        }
    }

    @Synchronized fun logout() {
        if (!active || !storage.isCurrentOwner()) return
        storage.compareAndSet(snapshot, null)
        snapshot = null
    }

    /** Disposed owners must never adopt a verification or mutate a replacement owner's record. */
    @Synchronized fun close() {
        active = false
    }
}

/** Local UI work is discarded after its composing owner leaves, even if a worker finishes later. */
class RequestOwner {
    @Volatile private var active = true
    private var generation = 0L

    fun isActive(): Boolean = active

    @Synchronized fun beginRequest(): Long {
        generation++
        return generation
    }

    @Synchronized fun accepts(request: Long): Boolean = active && generation == request

    fun accepts(
        request: Long,
        expected: VerifiedCredential?,
        current: VerifiedCredential?,
    ): Boolean = expected != null && expected === current && accepts(request)

    @Synchronized fun close() {
        active = false
        generation++
    }
}
