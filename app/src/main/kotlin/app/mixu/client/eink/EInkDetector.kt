// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.eink

import android.os.Build

/**
 * Electronic paper hardware detection and policy resolution.
 *
 * In accordance with UX specifications and ADR-004, heuristic detection provides a helpful
 * initial suggestion or automatic default, but explicit user preferences always take precedence.
 */
object EInkDetector {
    private val KNOWN_EINK_BRANDS =
        setOf(
            "onyx",
            "boox",
            "meebook",
            "boyue",
            "hisense",
            "bigme",
            "moaan",
            "dasung",
            "supernote",
            "kobo",
            "kindle",
            "tolino",
            "pocketbook",
            "yotaphone",
        )

    private fun safeGet(prop: () -> String): String =
        runCatching(prop).getOrNull().orEmpty().lowercase()

    /**
     * Inspects Android hardware properties against known e-paper manufacturer identities.
     * Safely handles null values when running inside JVM unit tests.
     */
    fun isProbableEInkDevice(
        manufacturer: String? = null,
        brand: String? = null,
        model: String? = null,
        product: String? = null,
    ): Boolean {
        val m = manufacturer?.lowercase() ?: safeGet { Build.MANUFACTURER }
        val b = brand?.lowercase() ?: safeGet { Build.BRAND }
        val d = model?.lowercase() ?: safeGet { Build.MODEL }
        val p = product?.lowercase() ?: safeGet { Build.PRODUCT }

        return KNOWN_EINK_BRANDS.any { brandKey ->
            m.contains(brandKey) ||
                b.contains(brandKey) ||
                d.contains(brandKey) ||
                p.contains(brandKey)
        }
    }

    /**
     * Resolves the authoritative display policy combining user override and hardware detection.
     *
     * @param userMode User-configured E-Ink toggle (AUTO, OFF, or FORCE_ON).
     * @param isHardwareDetected Result of hardware signature check.
     * @param fullRefreshInterval Page-turn interval between full screen flushes.
     */
    fun resolvePolicy(
        userMode: EInkMode,
        isHardwareDetected: Boolean = isProbableEInkDevice(),
        fullRefreshInterval: Int = 10,
    ): EInkPolicy {
        val active =
            when (userMode) {
                EInkMode.FORCE_ON -> true
                EInkMode.OFF -> false
                EInkMode.AUTO -> isHardwareDetected
            }

        return if (active) {
            EInkPolicy.EINK_ACTIVE.copy(
                fullRefreshInterval = fullRefreshInterval.coerceAtLeast(0),
            )
        } else {
            EInkPolicy.STANDARD
        }
    }
}
