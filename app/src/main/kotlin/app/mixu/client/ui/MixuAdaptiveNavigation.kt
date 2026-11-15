// SPDX-License-Identifier: MPL-2.0
@file:Suppress("ktlint:standard:function-naming")

package app.mixu.client.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mixu.client.MainScreenDestination
import app.mixu.client.library.BookCoverThumbnail
import app.mixu.client.library.BookItem
import app.mixu.client.theme.MixuTheme

/** Native adaptive navigation; no synthetic books or prototype-only actions. */
@Composable
fun MixuAdaptiveNavigation(
    current: MainScreenDestination,
    onSelect: (MainScreenDestination) -> Unit,
    lastReadBook: BookItem?,
    onContinue: (BookItem) -> Unit,
    vertical: Boolean = false,
    compact: Boolean = false,
) {
    val colors = MixuTheme.colors
    val destinations =
        listOf(
            Triple(MainScreenDestination.HOME, "主页", MixuIcons.Reader),
            Triple(MainScreenDestination.LIBRARY, "图书馆", MixuIcons.Library),
            Triple(MainScreenDestination.SETTINGS_PREVIEW, "更多", MixuIcons.Settings),
        )
    Surface(
        color = colors.surface,
        border = BorderStroke(if (compact) 2.dp else 1.dp, colors.outlineVariant),
    ) {
        if (vertical) {
            Column(
                Modifier.width(224.dp).fillMaxHeight().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MixuBrandLogo()
                    Text(
                        "谜序",
                        style = MixuTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (lastReadBook != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                colors.surfaceVariant,
                                RoundedCornerShape(MixuTheme.appearance.controlCornerRadius),
                            ).clickable { onContinue(lastReadBook) }
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        BookCoverThumbnail(
                            lastReadBook,
                            isGrid = true,
                            showBadge = false,
                            modifier = Modifier.width(36.dp).height(52.dp),
                        )
                        Column {
                            Text(
                                "继续阅读 · ${lastReadBook.progressPercent}%",
                                style = MixuTheme.typography.caption,
                                color = colors.primary,
                            )
                            Text(
                                lastReadBook.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MixuTheme.typography.labelMedium,
                            )
                        }
                    }
                }
                HorizontalDivider(color = colors.outlineVariant)
                destinations.forEach { (destination, label, icon) ->
                    val selected = current == destination
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (selected) colors.primary else colors.surface,
                                RoundedCornerShape(MixuTheme.appearance.controlCornerRadius),
                            ).selectable(
                                selected = selected,
                                role = Role.Tab,
                                onClick = { onSelect(destination) },
                            ).padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (selected) colors.onPrimary else colors.onSurface,
                        )
                        Text(
                            label,
                            color = if (selected) colors.onPrimary else colors.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                HorizontalDivider(color = colors.outlineVariant)
                Text(
                    "本地书库 · 离线阅读",
                    style = MixuTheme.typography.caption,
                    color = colors.onSurfaceVariant,
                )
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (compact) Modifier else Modifier.navigationBarsPadding())
                    .padding(
                        horizontal = 12.dp,
                        vertical = 8.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (lastReadBook != null && !compact) {
                    BookCoverThumbnail(
                        lastReadBook,
                        isGrid = true,
                        showBadge = false,
                        modifier =
                            Modifier
                                .width(
                                    34.dp,
                                ).height(48.dp)
                                .clickable { onContinue(lastReadBook) },
                    )
                    Spacer(Modifier.width(12.dp))
                }
                destinations.forEach { (destination, label, icon) ->
                    val selected = current == destination
                    Column(
                        Modifier
                            .weight(1f)
                            .sizeIn(minHeight = 48.dp)
                            .background(
                                if (selected) colors.primaryContainer else colors.surface,
                                RoundedCornerShape(MixuTheme.appearance.controlCornerRadius),
                            ).selectable(
                                selected = selected,
                                role = Role.Tab,
                                onClick = { onSelect(destination) },
                            ).padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        if (!compact) {
                            Icon(
                                icon,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = if (selected) colors.primary else colors.onSurfaceVariant,
                            )
                        }
                        Text(
                            label,
                            style = MixuTheme.typography.labelMedium,
                            color =
                                if (selected) {
                                    colors.onPrimaryContainer
                                } else {
                                    colors.onSurfaceVariant
                                },
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
