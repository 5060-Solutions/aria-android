package com.solutions5060.aria.ui.splash

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect

/** How the system splash leaves. */
enum class ExitPlan {
    /** Animations are off ("remove animations"): the splash goes at once. */
    Skip,

    /** No header mark to land on: a short fade and a slight scale-up. */
    Fade,

    /** The mark glides and scales into the dialer's header mark. */
    Glide,
}

/**
 * The hand-off between the system splash and the dialer's header mark.
 *
 * The exit animation is cosmetic and runs after the first frame, so it never
 * delays the launch; with animations off it does not run at all.
 */
object SplashHandoff {
    /** Spec ceiling for the in-app exit transition. */
    const val EXIT_MS = 400L

    /** Where the header mark is, in window coordinates; null until it is laid out. */
    var headerMark: Rect? by mutableStateOf(null)

    /** The header mark waits, invisible, while the splash icon travels to it. */
    var markHidden by mutableStateOf(false)

    fun plan(animatorDurationScale: Float, hasTarget: Boolean): ExitPlan = when {
        animatorDurationScale <= 0f -> ExitPlan.Skip
        hasTarget -> ExitPlan.Glide
        else -> ExitPlan.Fade
    }

    /**
     * The icon's scale at [t] of the glide into a header mark [markWidth] wide,
     * from a splash tile [tilePx] wide; null when either size is not known yet
     * (zero, or not finite), and the exit fades instead. A view's scale must
     * never be NaN or infinite: Android throws.
     */
    fun glideScale(markWidth: Float, tilePx: Float, t: Float): Float? {
        if (!(tilePx > 0f) || !(markWidth > 0f) || !tilePx.isFinite() || !markWidth.isFinite()) return null
        val s = 1f + (markWidth / tilePx - 1f) * t
        return s.takeIf { it.isFinite() }
    }
}
