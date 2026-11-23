package com.solutions5060.aria.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.solutions5060.aria.ui.theme.AriaShapes
import com.solutions5060.aria.ui.theme.Sizes
import com.solutions5060.aria.ui.theme.Spacing

/** Row shapes (DESIGN.md §3): content rows 16 dp, navigation/action rows a pill. */
enum class RowShape(val shape: Shape) {
    Content(AriaShapes.contentRow),
    Pill(CircleShape),
}

/**
 * The one list row. A tonal segment with the DESIGN row heights (64 with a
 * leading avatar, 52 without); rows are separated by [Spacing.rowGap].
 * Lines wrap at accessibility font sizes instead of truncating.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AriaListItem(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    supportingColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    headlineColor: Color = MaterialTheme.colorScheme.onSurface,
    emphasized: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    shape: RowShape = RowShape.Content,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    tallWithoutLeading: Boolean = false,
) {
    val large = isAccessibilityFontScale()
    val minHeight = if (leading != null || tallWithoutLeading) Sizes.listRowWithAvatar else Sizes.listRow
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape.shape,
        color = containerColor,
    ) {
        Column(
            modifier = if (onClick != null) {
                Modifier.combinedClickable(
                    onClick = onClick,
                    onClickLabel = onClickLabel,
                    onLongClick = onLongClick,
                    role = Role.Button,
                )
            } else {
                Modifier
            },
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = minHeight)
                    .padding(
                        start = if (shape == RowShape.Pill) Spacing.lg else Spacing.md,
                        end = if (trailing != null) Spacing.xs else Spacing.md,
                        top = Spacing.xs,
                        bottom = Spacing.xs,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                leading?.invoke()
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(
                        headline,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (emphasized) FontWeight.SemiBold else null,
                        color = headlineColor,
                        maxLines = if (large) 3 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (supporting != null) {
                        Text(
                            supporting,
                            style = MaterialTheme.typography.bodyMedium,
                            color = supportingColor,
                            maxLines = if (large) 3 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (trailing != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
                }
            }
            below?.invoke(this)
        }
    }
}

/** A settings row with a switch. The whole row toggles; TalkBack reads it as one switch. */
@Composable
fun AriaSwitchRow(
    headline: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AriaShapes.contentRow,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                .heightIn(min = if (leading != null) Sizes.listRowWithAvatar else Sizes.listRow)
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            leading?.invoke()
            Column(modifier = Modifier.weight(1f)) {
                Text(headline, style = MaterialTheme.typography.bodyLarge)
                if (supporting != null) {
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    }
}

/** A 40 dp tonal icon tile: the leading element of settings rows (same width as an avatar). */
@Composable
fun RowIcon(
    @androidx.annotation.DrawableRes icon: Int,
    tint: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
) {
    Surface(shape = AriaShapes.chip, color = container) {
        Box(Modifier.padding(Spacing.xs), contentAlignment = Alignment.Center) {
            AriaIcon(icon, contentDescription = null, tint = tint)
        }
    }
}

/** Section title above a group of rows (DESIGN title token, primary). */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(start = Spacing.xxs, top = Spacing.xs, bottom = Spacing.xs)
            .semantics { heading() },
    )
}

/** A group of rows with a header, rows [Spacing.rowGap] apart. */
@Composable
fun RowGroup(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.rowGap)) {
        if (title != null) SectionHeader(title)
        content()
    }
}

/** A 20 dp card on the surface container (DESIGN card radius, flat). */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentPadding: PaddingValues = PaddingValues(Spacing.md),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = modifier.fillMaxWidth(), shape = AriaShapes.card, color = containerColor) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}
