// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.reader

private val GENERATED_IMAGE_DESCRIPTION = Regex("^书中插图 / 现场平面图 #\\d+$")

/** Sanitize legacy generated captions only for presentation, leaving publication bytes unchanged. */
internal fun readerImageDescription(originalAlt: String): String {
    val description = originalAlt.trim()
    return if (description.isEmpty() || GENERATED_IMAGE_DESCRIPTION.matches(description)) {
        "图片"
    } else {
        description
    }
}
