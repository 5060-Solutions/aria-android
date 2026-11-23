package com.solutions5060.aria.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import com.solutions5060.aria.ui.theme.Sizes

/** A Material Symbols Rounded icon from [com.solutions5060.aria.ui.theme.AriaIcons]. */
@Composable
fun AriaIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = Sizes.icon,
    tint: Color = LocalContentColor.current,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint,
    )
}

/** Font scale at or above which DESIGN.md's accessibility-size layouts apply. */
const val ACCESSIBILITY_FONT_SCALE = 1.5f

/** True at accessibility font sizes: drop dial letters, 2-column call grid, multi-line rows. */
@Composable
fun isAccessibilityFontScale(): Boolean = LocalDensity.current.fontScale >= ACCESSIBILITY_FONT_SCALE

/**
 * Caps the font scale for [content] whose text lives inside a fixed-size
 * shape (dial keys): the text still grows, but never past [max].
 */
@Composable
fun CappedFontScale(max: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    if (density.fontScale <= max) {
        content()
    } else {
        CompositionLocalProvider(LocalDensity provides Density(density.density, max), content = content)
    }
}
