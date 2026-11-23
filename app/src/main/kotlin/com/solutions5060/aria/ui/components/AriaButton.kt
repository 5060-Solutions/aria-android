package com.solutions5060.aria.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.solutions5060.aria.ui.theme.Sizes
import com.solutions5060.aria.ui.theme.Spacing

/** DESIGN.md §3 button hierarchy. Use exactly these four everywhere. */
enum class AriaButtonStyle {
    /** Filled primary: the one main action on a screen. */
    Prominent,

    /** Filled tonal: secondary actions. */
    Standard,

    /** Text: low-emphasis actions. */
    Plain,

    /** Filled error: sign out, delete. */
    Destructive,
}

/** Primary 52 dp (forms, main actions); secondary 44 dp. */
enum class AriaButtonSize(val height: Dp) {
    Primary(Sizes.primaryButton),
    Secondary(Sizes.secondaryButton),
}

/**
 * The app's one button. Expressive shapes: a pill that squares off while
 * pressed. Height is a minimum, so large font scales grow it rather than clip.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AriaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: AriaButtonStyle = AriaButtonStyle.Prominent,
    size: AriaButtonSize = if (style == AriaButtonStyle.Prominent || style == AriaButtonStyle.Destructive) {
        AriaButtonSize.Primary
    } else {
        AriaButtonSize.Secondary
    },
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val sized = modifier.heightIn(min = size.height)
    val padding = PaddingValues(horizontal = Spacing.xl, vertical = Spacing.xs)
    val content: @Composable RowScope.() -> Unit = {
        ButtonContent(text = text, icon = icon, loading = loading)
    }
    val clickable = enabled && !loading
    when (style) {
        AriaButtonStyle.Prominent -> Button(
            onClick = onClick,
            modifier = sized,
            enabled = clickable,
            shapes = ButtonDefaults.shapes(),
            contentPadding = padding,
            content = content,
        )
        AriaButtonStyle.Destructive -> Button(
            onClick = onClick,
            modifier = sized,
            enabled = clickable,
            shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            contentPadding = padding,
            content = content,
        )
        AriaButtonStyle.Standard -> FilledTonalButton(
            onClick = onClick,
            modifier = sized,
            enabled = clickable,
            shapes = ButtonDefaults.shapes(),
            contentPadding = padding,
            content = content,
        )
        AriaButtonStyle.Plain -> TextButton(
            onClick = onClick,
            modifier = sized,
            enabled = clickable,
            shapes = ButtonDefaults.shapes(),
            contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.xs),
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RowScope.ButtonContent(text: String, @DrawableRes icon: Int?, loading: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            loading -> LoadingIndicator(
                modifier = Modifier.size(Sizes.icon),
                color = LocalContentColor.current,
            )
            icon != null -> AriaIcon(icon, contentDescription = null, size = Sizes.iconSmall)
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            color = Color.Unspecified,
        )
    }
}
