// SPDX-License-Identifier: MPL-2.0
@file:Suppress("ktlint:standard:function-naming")

package app.mixu.client.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout

/** Keeps tab-owned drafts and in-flight operations alive without placing inactive pages. */
@Composable
fun RetainedCompanionPage(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (visible) placeables.forEach { it.placeRelative(0, 0) }
        }
    }
}
