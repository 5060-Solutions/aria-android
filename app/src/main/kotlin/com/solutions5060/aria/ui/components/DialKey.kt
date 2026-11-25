package com.solutions5060.aria.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import com.solutions5060.aria.R
import com.solutions5060.aria.ui.theme.AriaType
import com.solutions5060.aria.ui.theme.Sizes

/** The letters printed under each digit; "+" under 0 (long-press). */
val DialLetters = mapOf(
    "1" to "", "2" to "ABC", "3" to "DEF",
    "4" to "GHI", "5" to "JKL", "6" to "MNO",
    "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
    "*" to "", "0" to "+", "#" to "",
)

/** What TalkBack reads for a key: "5, J K L"; "0, plus"; "star". */
fun dialKeyDescription(digit: String, letters: String, star: String, pound: String, plus: String): String {
    val spoken = when (digit) {
        "*" -> star
        "#" -> pound
        else -> digit
    }
    return when {
        letters == "+" -> "$spoken, $plus"
        letters.isNotEmpty() -> "$spoken, ${letters.toList().joinToString(" ")}"
        else -> spoken
    }
}

private fun TextStyle.scaled(factor: Float) = copy(fontSize = fontSize * factor, lineHeight = lineHeight * factor)

/**
 * A dial key, 76 dp by default and sized by the dial pad's space. On press it morphs from a circle to a rounded square at
 * 90 % size (Expressive spatial spring). Letters drop at accessibility font
 * sizes; the digit grows with font scale up to the key's size.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialKey(
    digit: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    letters: String = DialLetters[digit].orEmpty(),
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    size: Dp = Sizes.dialKey,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = MaterialTheme.motionScheme
    val corner by animateDpAsState(
        if (pressed) size * 0.28f else size / 2,
        motion.fastSpatialSpec(),
        label = "keyCorner",
    )
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, motion.fastSpatialSpec(), label = "keyScale")
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (pressed) colors.primaryContainer else colors.surfaceContainerHigh,
        motion.fastEffectsSpec(),
        label = "keyColor",
    )
    val showLetters = !isAccessibilityFontScale()
    // The text grows with the key (in sp, so the font scale still applies on top).
    val textScale = (size.value / Sizes.dialKey.value).coerceIn(0.9f, 1.6f)
    val digitStyle = AriaType.dialDigit.scaled(textScale)
    val lettersStyle = AriaType.dialLetters.scaled(textScale)
    val description = dialKeyDescription(
        digit,
        letters,
        star = stringResource(R.string.ui_dial_star),
        pound = stringResource(R.string.ui_dial_pound),
        plus = stringResource(R.string.ui_dial_plus),
    )
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(corner))
            .background(container)
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(color = colors.primary),
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick { onClick(); true }
                if (onLongClick != null) onLongClick(label = onLongClickLabel) { onLongClick(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        CappedFontScale(max = 1.3f) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(digit, style = digitStyle, color = colors.onSurface)
                if (showLetters && letters.isNotEmpty()) {
                    Text(letters, style = lettersStyle, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}
