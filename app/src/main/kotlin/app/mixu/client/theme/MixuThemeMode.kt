// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.theme

/**
 * Visual presentation themes supported across the library, reader, and case board.
 *
 * Includes classic reading palettes inspired by Kindle, Readest, and Koodo Reader.
 */
enum class MixuThemeMode {
    /** Daylight warm paper presentation with high readability. */
    LIGHT,

    /** Night presentation with low ambient glare and reduced luminance. */
    DARK,

    /** Warm parchment presentation simulating classic book stock. */
    SEPIA,

    /** Eye-care sage green presentation inspired by Koodo Reader and Readest Grass. */
    MINT,

    /** Solarized warm cream daylight palette inspired by Readest Solarized. */
    SOLARIZED,

    /** Nord slate-night palette inspired by Readest and Koodo Nord dark mode. */
    NORD_DARK,

    /** High-contrast pure monochrome presentation optimized for E-Ink electronic paper. */
    EINK,
}
