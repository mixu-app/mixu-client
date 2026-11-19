// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.auth

import java.util.UUID

/**
 * Represents the active user identity and session state within the Mixu client shell.
 *
 * Mixu adopts a strict offline-first, local-first paradigm: cold start initiates
 * immediately into Guest Mode with zero authentication challenges, zero account prompts,
 * and zero outbound telemetry or credential requirements.
 *
 * @property isGuest Indicates whether the active session operates in guest mode without an account.
 * @property anonymousId Stable client-local pseudonymous identifier.
 * @property displayName User-facing name shown across the UI.
 */
data class UserSession(
    val isGuest: Boolean = true,
    val anonymousId: String = UUID.randomUUID().toString(),
    val displayName: String = DEFAULT_DISPLAY_NAME,
    val credential: VerifiedCredential? = null,
) {
    val isAuthenticated: Boolean
        get() = !isGuest && credential?.validFor(credential.endpoint) == true

    fun toMap(): Map<String, String> =
        mapOf(
            KEY_IS_GUEST to isGuest.toString(),
            KEY_ANONYMOUS_ID to anonymousId,
            KEY_DISPLAY_NAME to displayName,
        )

    companion object {
        const val DEFAULT_DISPLAY_NAME = "Guest"
        const val KEY_IS_GUEST = "is_guest"
        const val KEY_ANONYMOUS_ID = "anonymous_id"
        const val KEY_DISPLAY_NAME = "display_name"

        val DEFAULT: UserSession =
            UserSession(
                isGuest = true,
                anonymousId = "guest-anonymous-session",
                displayName = DEFAULT_DISPLAY_NAME,
            )

        fun createGuest(
            anonymousId: String = UUID.randomUUID().toString(),
            displayName: String = DEFAULT_DISPLAY_NAME,
        ): UserSession =
            UserSession(
                isGuest = true,
                anonymousId = anonymousId,
                displayName = displayName,
            )

        fun createAuthenticated(
            userId: String,
            displayName: String,
            credential: VerifiedCredential,
        ): UserSession =
            UserSession(
                isGuest = false,
                anonymousId = userId,
                displayName = displayName,
                credential = credential,
            )

        fun fromMap(map: Map<String, String>): UserSession {
            // Identity maps never restore authentication without secure credentials.
            val isGuest = true
            val anonymousId =
                map[KEY_ANONYMOUS_ID]?.takeIf { it.isNotBlank() } ?: DEFAULT.anonymousId
            val displayName =
                map[KEY_DISPLAY_NAME]?.takeIf { it.isNotBlank() } ?: DEFAULT.displayName
            return UserSession(
                isGuest = isGuest,
                anonymousId = anonymousId,
                displayName = displayName,
            )
        }
    }
}
