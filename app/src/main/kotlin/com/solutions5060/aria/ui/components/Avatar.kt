package com.solutions5060.aria.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import com.solutions5060.aria.ui.theme.AriaIcons
import com.solutions5060.aria.ui.theme.Sizes

/** Up to two initials from a name; null for a number, URI or blank (show the person glyph). */
fun initialsOf(name: String?): String? {
    val words = name?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
    val letters = words.mapNotNull { w -> w.firstOrNull { it.isLetter() }?.takeIf { w.first().isLetter() } }
    if (letters.isEmpty()) return null
    val picked = if (letters.size == 1) listOf(letters.first()) else listOf(letters.first(), letters.last())
    return picked.joinToString("") { it.uppercase() }
}

/** A stable palette slot (0..2) for [key], so a person keeps their colour across screens. */
fun avatarSlot(key: String): Int = Math.floorMod(key.lowercase().hashCode(), 3)

/**
 * Initials (or a person glyph) on a tonal container: 40 dp in lists, 96 on
 * the call screen. Decorative: the row or screen announces the name.
 */
@Composable
fun Avatar(
    name: String?,
    modifier: Modifier = Modifier,
    key: String = name.orEmpty(),
    size: Dp = Sizes.avatar,
    shape: Shape = CircleShape,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (avatarSlot(key)) {
        0 -> colors.primaryContainer to colors.onPrimaryContainer
        1 -> colors.secondaryContainer to colors.onSecondaryContainer
        else -> colors.tertiaryContainer to colors.onTertiaryContainer
    }
    AvatarContent(name, modifier, size, shape, container, content)
}

@Composable
internal fun AvatarContent(
    name: String?,
    modifier: Modifier,
    size: Dp,
    shape: Shape,
    container: Color,
    content: Color,
) {
    val initials = initialsOf(name)
    val large = size >= Sizes.avatarLarge
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(container)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        CappedFontScale(max = 1f) {
            if (initials != null) {
                val style: TextStyle = if (large) MaterialTheme.typography.displayMedium else MaterialTheme.typography.titleMedium
                Text(initials, style = style, color = content)
            } else {
                AriaIcon(AriaIcons.Person, contentDescription = null, size = size * 0.5f, tint = content)
            }
        }
    }
}
