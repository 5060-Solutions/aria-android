package com.solutions5060.aria.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import com.solutions5060.aria.ui.theme.Sizes

/** One choice in a [ConnectedToggleGroup]. */
data class ToggleOption(val label: String, @DrawableRes val icon: Int? = null)

/**
 * Material 3 Expressive connected button group used as a single-choice
 * segmented control: History All/Missed, voicemail speed, theme. Each
 * option reads as a radio button, with the group as a selectable group.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectedToggleGroup(
    options: List<ToggleOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
) {
    Row(
        modifier = modifier.semantics { selectableGroup() },
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            val shapes = when (index) {
                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }
            ToggleButton(
                checked = index == selectedIndex,
                onCheckedChange = { onSelect(index) },
                shapes = shapes,
                colors = ToggleButtonDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                modifier = (if (fill) Modifier.weight(1f) else Modifier)
                    .heightIn(min = Sizes.iconButton)
                    .semantics { role = Role.RadioButton },
                icon = option.icon?.let { res ->
                    { AriaIcon(res, contentDescription = null, size = Sizes.iconSmall) }
                },
            ) {
                Text(option.label, maxLines = 1)
            }
        }
    }
}
