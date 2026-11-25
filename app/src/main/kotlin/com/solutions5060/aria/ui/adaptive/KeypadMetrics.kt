package com.solutions5060.aria.ui.adaptive

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.solutions5060.aria.ui.theme.Sizes

/**
 * Sizes for the dial pad, derived from the space it gets rather than fixed:
 * the key is the largest circle that fits both the width (three keys and two
 * gaps) and the height (four key rows, the call row and the gaps between),
 * kept between [MinKey] and [MaxKey] so small phones still fit and a tablet
 * does not get comically large keys. Gaps keep the 12 : 14 : 76 proportions of
 * DESIGN.md, and [scale] is the factor every text size and the call button
 * grow by (digits 34 sp, letters 10 sp at 1.0), still in sp so the user's
 * font scale applies on top.
 */
data class KeypadMetrics(val key: Dp, val gapH: Dp, val gapV: Dp) {
    /** The key relative to the 76 dp design size. */
    val scale: Float get() = key.value / Sizes.dialKey.value

    /** The text scale: follows the key, but a huge key does not get huge letters. */
    val textScale: Float get() = scale.coerceIn(0.9f, 1.6f)

    /** The call button: 72 of 76, as designed. */
    val callAction: Dp get() = key * (Sizes.callAction.value / Sizes.dialKey.value)

    /** The backspace hit target. */
    val backspace: Dp get() = Sizes.iconButton * scale.coerceIn(1f, 1.4f)

    companion object {
        val MinKey = 56.dp
        val MaxKey = 120.dp

        private const val GAP_H = 12f / 76f
        private const val GAP_V = 14f / 76f

        /**
         * @param width room for the three columns
         * @param height room for the four key rows, the call row and the [rowSpacing] between
         * @param callRow false when the call row is placed elsewhere (landscape phones)
         */
        fun forSpace(width: Dp, height: Dp, rowSpacing: Dp, callRow: Boolean = true): KeypadMetrics {
            val byWidth = width.value / (3f + 2f * GAP_H)
            val rows = if (callRow) 5f else 4f
            val gaps = 3f * GAP_V
            val usable = height.value - if (callRow) rowSpacing.value else 0f
            val byHeight = usable / (rows + gaps)
            val key = minOf(byWidth, byHeight).dp.coerceIn(MinKey, MaxKey)
            return KeypadMetrics(key, key * GAP_H, key * GAP_V)
        }
    }
}
