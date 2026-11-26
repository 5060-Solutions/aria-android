package com.solutions5060.aria.ui.splash

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.provider.Settings
import android.view.animation.PathInterpolator
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreenViewProvider

/**
 * The in-app exit transition for the system splash: the icon glides and
 * scales into the dialer's header mark while the splash background fades
 * away (400 ms, under the 500 ms ceiling). Installed after `installSplashScreen()`.
 *
 * It starts after the first frame and keeps nothing on screen waiting for the
 * app: the content is already drawn beneath. "Remove animations" skips it.
 */
fun SplashScreen.installBrandExit(activity: Activity) {
    // Hidden until the icon lands, only when the glide will actually run.
    val scale = Settings.Global.getFloat(
        activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
    )
    if (scale > 0f) SplashHandoff.markHidden = true
    setOnExitAnimationListener { provider -> exit(provider, scale) }
}

private fun exit(provider: SplashScreenViewProvider, animatorScale: Float) {
    val target = SplashHandoff.headerMark
    when (SplashHandoff.plan(animatorScale, target != null)) {
        ExitPlan.Skip -> finish(provider)
        ExitPlan.Fade, ExitPlan.Glide -> animate(provider)
    }
}

private fun animate(provider: SplashScreenViewProvider) {
    val icon = provider.iconView
    val root = provider.view
    val iconLoc = IntArray(2).also { icon.getLocationInWindow(it) }
    val startCx = iconLoc[0] + icon.width / 2f
    val startCy = iconLoc[1] + icon.height / 2f
    // The tile is 136/288 of the icon view's width (see ic_splash_logo.xml).
    val tilePx = icon.width * TILE_FRACTION
    val ease = PathInterpolator(0.05f, 0.7f, 0.1f, 1f) // emphasized decelerate

    ValueAnimator.ofFloat(0f, 1f).apply {
        duration = SplashHandoff.EXIT_MS
        interpolator = ease
        addUpdateListener { a ->
            val t = a.animatedValue as Float
            // The target is read live: the header may lay out after the first frame.
            val box = SplashHandoff.headerMark
            if (box != null) {
                val endScale = box.width / tilePx
                val s = 1f + (endScale - 1f) * t
                icon.scaleX = s
                icon.scaleY = s
                icon.translationX = (box.center.x - startCx) * t
                icon.translationY = (box.center.y - startCy) * t
                icon.alpha = if (t < 0.7f) 1f else (1f - (t - 0.7f) / 0.3f)
            } else {
                val s = 1f + 0.08f * t
                icon.scaleX = s
                icon.scaleY = s
                icon.alpha = 1f - t
            }
            root.alpha = 1f - t
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) = finish(provider)
        })
        start()
    }
}

private fun finish(provider: SplashScreenViewProvider) {
    SplashHandoff.markHidden = false
    provider.remove()
}

private const val TILE_FRACTION = 136f / 288f
