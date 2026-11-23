package com.solutions5060.aria.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** DESIGN.md §3: the 4 dp grid. Never use a spacing that is not here. */
object Spacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 40.dp

    /** Screen side margins. */
    val screen = lg

    /** Between list rows. */
    val rowGap = xxs

    /** Between sections. */
    val sectionGap = xl
}

/** DESIGN.md §3 component sizes. Identical on iOS; never improvise a size. */
object Sizes {
    val dialKey = 76.dp
    val dialKeyGapH = 12.dp
    val dialKeyGapV = 14.dp

    /** Call / Answer / Hang-up. */
    val callAction = 72.dp
    val callActionIcon = 32.dp

    /** In-call control circle, its label cell and icon. */
    val callControl = 64.dp
    val callControlCell = 96.dp
    val callControlIcon = 28.dp

    /** Small icon button and the minimum hit target. */
    val iconButton = 48.dp
    val icon = 24.dp
    val iconSmall = 18.dp

    val primaryButton = 52.dp
    val secondaryButton = 44.dp

    val listRowWithAvatar = 64.dp
    val listRow = 52.dp

    val avatar = 40.dp
    val avatarLarge = 96.dp

    val statusDot = 8.dp
    val badgeDot = 8.dp

    /** Content widths on large screens. */
    val maxContentWidth = 560.dp
}

/** DESIGN.md §3 radii. */
object Radii {
    val card = 20.dp
    val sheet = 28.dp
    val contentRow = 16.dp
    val chip = 12.dp
    val textField = 14.dp
    val iconButton = 12.dp
}

object AriaShapes {
    val card = RoundedCornerShape(Radii.card)
    val sheet = RoundedCornerShape(topStart = Radii.sheet, topEnd = Radii.sheet)
    val contentRow = RoundedCornerShape(Radii.contentRow)
    val pill = CircleShape
    val chip = RoundedCornerShape(Radii.chip)
    val textField = RoundedCornerShape(Radii.textField)
}

/** Material shape slots carrying the DESIGN radii. */
val AriaMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(Radii.chip),
    medium = RoundedCornerShape(Radii.contentRow),
    large = RoundedCornerShape(Radii.card),
    extraLarge = RoundedCornerShape(Radii.sheet),
    largeIncreased = RoundedCornerShape(24.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    extraExtraLarge = RoundedCornerShape(48.dp),
)

/** DESIGN.md §6 durations, in milliseconds. */
object Durations {
    const val SHORT = 200
    const val MEDIUM = 300
    const val LONG = 450
}
