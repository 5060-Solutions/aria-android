package com.solutions5060.aria.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.solutions5060.aria.R
import com.solutions5060.aria.ui.theme.AriaIcons
import com.solutions5060.aria.ui.theme.LocalAriaColors
import com.solutions5060.aria.ui.theme.Sizes
import com.solutions5060.aria.ui.theme.Spacing

/** Corner radius of a call button: a circle at rest, a rounded square when active or pressed. */
internal fun morphCorner(size: Dp, active: Boolean, pressed: Boolean): Dp = when {
    pressed -> size * 0.25f
    active -> size * 0.3f
    else -> size / 2
}

/**
 * One in-call control (DESIGN.md §3/§5): a 64 dp tonal circle with a 13 sp
 * label in a 96 dp cell. A toggle ([checked] non-null) morphs to a rounded
 * square and fills with primary while on, and reads "Mute, on" to TalkBack.
 */
@Composable
fun CallControlButton(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    checked: Boolean? = null,
    @DrawableRes checkedIcon: Int = icon,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val on = checked == true
    val motion = MaterialTheme.motionScheme
    val corner by animateDpAsState(
        morphCorner(Sizes.callControl, on, pressed),
        motion.fastSpatialSpec(),
        label = "controlCorner",
    )
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (on) colors.primary else colors.surfaceContainerHighest,
        motion.defaultEffectsSpec(),
        label = "controlContainer",
    )
    val content by animateColorAsState(
        if (on) colors.onPrimary else colors.onSurface,
        motion.defaultEffectsSpec(),
        label = "controlContent",
    )
    val onLabel = stringResource(R.string.ui_state_on)
    val offLabel = stringResource(R.string.ui_state_off)
    val action = if (checked != null) {
        Modifier.toggleable(
            value = on,
            enabled = enabled,
            role = Role.Switch,
            interactionSource = interaction,
            indication = null,
            onValueChange = { onClick() },
        )
    } else {
        Modifier.clickable(
            enabled = enabled,
            role = Role.Button,
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
        )
    }
    Column(
        modifier = modifier
            .width(Sizes.callControlCell)
            .then(action)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                if (checked != null) stateDescription = if (on) onLabel else offLabel
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(Sizes.callControl)
                .clip(RoundedCornerShape(corner))
                .background(if (enabled) container else container.copy(alpha = 0.38f))
                .indication(interaction, ripple(color = content)),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = if (on) checkedIcon else icon,
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.8f)) togetherWith fadeOut() },
                label = "controlIcon",
            ) { res ->
                AriaIcon(
                    res,
                    contentDescription = null,
                    size = Sizes.callControlIcon,
                    tint = if (enabled) content else content.copy(alpha = 0.38f),
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/** The fixed-colour 72 dp call actions. Never dynamic (DESIGN.md §1). */
enum class CallActionKind { Call, Answer, Decline, HangUp }

/**
 * Call / Answer / Decline / Hang-up: a 72 dp circle in the fixed answer or
 * hang-up colour with the 40 % glow (0, 6, 24), squaring off while pressed.
 * [label], when given, is shown under the button; [contentDescription]
 * defaults to it.
 */
@Composable
fun CallActionButton(
    kind: CallActionKind,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    size: Dp = Sizes.callAction,
    iconSize: Dp = Sizes.callActionIcon,
) {
    val aria = LocalAriaColors.current
    val (container, content) = when (kind) {
        CallActionKind.Call, CallActionKind.Answer -> aria.answerFill to aria.onCallFill
        CallActionKind.Decline, CallActionKind.HangUp -> aria.hangupFill to aria.onCallFill
    }
    val glyph = icon ?: when (kind) {
        CallActionKind.Call, CallActionKind.Answer -> AriaIcons.Call
        CallActionKind.Decline, CallActionKind.HangUp -> AriaIcons.HangUp
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = MaterialTheme.motionScheme
    val corner by animateDpAsState(morphCorner(size, false, pressed), motion.fastSpatialSpec(), label = "actionCorner")
    val glowAlpha by animateFloatAsState(if (enabled) 0.4f else 0f, motion.defaultEffectsSpec(), label = "glow")
    val shape = RoundedCornerShape(corner)
    Column(
        modifier = modifier.widthIn(min = Sizes.callControlCell),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .dropShadow(shape) {
                    radius = 24.dp.toPx()
                    offset = Offset(0f, 6.dp.toPx())
                    color = container.copy(alpha = glowAlpha)
                }
                .clip(shape)
                .background(if (enabled) container else container.copy(alpha = 0.38f))
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    interactionSource = interaction,
                    indication = ripple(color = content),
                    onClick = onClick,
                )
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            AriaIcon(glyph, contentDescription = null, size = iconSize, tint = content)
        }
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}
