package com.solutions5060.aria.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solutions5060.aria.R
import com.solutions5060.aria.ui.theme.AriaExtendedColors
import com.solutions5060.aria.ui.theme.AriaIcons
import com.solutions5060.aria.ui.theme.AriaShapes
import com.solutions5060.aria.ui.theme.Durations
import com.solutions5060.aria.ui.theme.LocalAriaColors
import com.solutions5060.aria.ui.theme.LocalReducedMotion
import com.solutions5060.aria.ui.theme.Sizes
import com.solutions5060.aria.ui.theme.Spacing

/** Registration as the status chip shows it (DESIGN.md §1 status colours). */
enum class RegistrationStatus(@StringRes val label: Int) {
    Registered(R.string.ui_status_registered),
    Connecting(R.string.ui_status_connecting),
    /** Connecting again after a network change while calls carry on (amber, like connecting). */
    Reconnecting(R.string.ui_status_reconnecting),
    Error(R.string.ui_status_error),
    Offline(R.string.ui_status_offline),
    SignedOut(R.string.ui_status_signed_out);

    fun color(colors: AriaExtendedColors): Color = when (this) {
        Registered -> colors.statusRegistered
        Connecting, Reconnecting -> colors.statusConnecting
        Error -> colors.statusError
        Offline, SignedOut -> colors.statusOffline
    }
}

/** The coloured registration dot; pulses while connecting unless motion is reduced. */
@Composable
fun StatusDot(status: RegistrationStatus, modifier: Modifier = Modifier) {
    val color = status.color(LocalAriaColors.current)
    val pulse = (status == RegistrationStatus.Connecting || status == RegistrationStatus.Reconnecting) &&
        !LocalReducedMotion.current
    val alpha = if (pulse) {
        val transition = rememberInfiniteTransition(label = "statusPulse")
        val a by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(Durations.LONG * 2), RepeatMode.Reverse),
            label = "statusAlpha",
        )
        a
    } else {
        1f
    }
    Box(
        modifier
            .size(Sizes.statusDot)
            .alpha(alpha)
            .background(color, CircleShape),
    )
}

/**
 * The desktop StatusBar equivalent at the top of the dialer: account name,
 * registration dot + label (never colour alone), a DND moon and a
 * forwarding arrow when those are on. Tapping opens the connection sheet.
 */
@Composable
fun StatusChip(
    accountName: String,
    status: RegistrationStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dnd: Boolean = false,
    forwarding: Boolean = false,
) {
    val statusLabel = stringResource(status.label)
    val dndLabel = stringResource(R.string.ui_dnd_on)
    val fwdLabel = stringResource(R.string.ui_forwarding_on)
    val description = buildString {
        append(accountName.ifBlank { statusLabel })
        if (accountName.isNotBlank()) append(", ").append(statusLabel)
        if (dnd) append(", ").append(dndLabel)
        if (forwarding) append(", ").append(fwdLabel)
    }
    AssistChip(
        onClick = onClick,
        modifier = modifier
            .widthIn(max = 360.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        shape = AriaShapes.chip,
        border = null,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            labelColor = MaterialTheme.colorScheme.onSurface,
            leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            trailingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        leadingIcon = { StatusDot(status) },
        label = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (accountName.isNotBlank()) {
                    Text(
                        accountName,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text("·", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
                }
                Text(
                    statusLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
        trailingIcon = if (dnd || forwarding) {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    if (dnd) AriaIcon(AriaIcons.Dnd, null, size = Sizes.iconSmall, tint = MaterialTheme.colorScheme.error)
                    if (forwarding) AriaIcon(AriaIcons.Forwarding, null, size = Sizes.iconSmall)
                }
            }
        } else {
            null
        },
    )
}
