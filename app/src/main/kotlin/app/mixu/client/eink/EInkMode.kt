// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.eink

/**
 * User-configurable electronic paper (E-Ink) display modes.
 */
enum class EInkMode {
    /**
     * Automatically suggest or activate E-Ink optimizations when running on recognized
     * electronic paper hardware, while preserving explicit manual override.
     */
    AUTO,

    /**
     * Standard emissive display mode (LCD / OLED): full animation and subtle color gradients enabled.
     */
    OFF,

    /**
     * Forced electronic paper mode: animations disabled, discrete step navigation, pure contrast,
     * thick stroke widths and no reliance on color alone.
     */
    FORCE_ON,
}
