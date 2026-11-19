// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.network

import app.mixu.client.auth.RequestOwner
import app.mixu.client.auth.VerifiedCredential

/** Serializes explicit check-in adoption against earlier reads and disposed UI owners. */
internal class AccountQuotaRequests {
    private val owner = RequestOwner()
    private var checkInRequest: Long? = null

    fun beginRead(): Long? = if (checkInRequest == null) owner.beginRequest() else null

    fun beginCheckIn(
        credential: VerifiedCredential?,
        checkedInToday: Boolean?,
    ): Long? {
        if (credential == null ||
            checkedInToday != false ||
            checkInRequest != null ||
            !owner.isActive()
        ) {
            return null
        }
        return owner.beginRequest().also { checkInRequest = it }
    }

    fun accepts(
        request: Long,
        expected: VerifiedCredential?,
        current: VerifiedCredential?,
    ): Boolean = owner.accepts(request, expected, current)

    fun finishCheckIn(request: Long) {
        if (checkInRequest == request) checkInRequest = null
    }

    fun close() = owner.close()
}
