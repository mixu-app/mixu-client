// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.theme

/**
 * Visual presentation themes supported across the library, reader, and case board.
 */
enum class MixuThemeMode {
    /** Daylight clean presentation with high readability. */
    LIGHT,

    /** Night presentation with low ambient glare and reduced luminance. */
    DARK,

    /** Warm paper presentation simulating book stock. */
    SEPIA,

    /** High-contrast pure monochrome presentation optimized for E-Ink electronic paper. */
    EINK,
}
