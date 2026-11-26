package com.solutions5060.aria.ui.splash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashHandoffTest {
    @Test fun removeAnimationsSkipsTheExit() {
        assertEquals(ExitPlan.Skip, SplashHandoff.plan(0f, hasTarget = true))
        assertEquals(ExitPlan.Skip, SplashHandoff.plan(0f, hasTarget = false))
    }

    @Test fun glidesWhenTheHeaderIsThere() {
        assertEquals(ExitPlan.Glide, SplashHandoff.plan(1f, hasTarget = true))
    }

    @Test fun fadesWithoutAHeader() {
        assertEquals(ExitPlan.Fade, SplashHandoff.plan(1f, hasTarget = false))
    }

    @Test fun staysWithinTheBudget() {
        assertTrue(SplashHandoff.EXIT_MS <= 500)
    }

    @Test fun glideScaleRunsFromOneToTheMark() {
        assertEquals(1f, SplashHandoff.glideScale(50f, 100f, 0f)!!, 0.0001f)
        assertEquals(0.5f, SplashHandoff.glideScale(50f, 100f, 1f)!!, 0.0001f)
    }

    @Test fun anUnmeasuredIconNeverGivesANaNScale() {
        // On a Pixel the splash icon reported width 0 at exit: 0/0 crashed setScaleX.
        assertNull(SplashHandoff.glideScale(0f, 0f, 0.5f))
        assertNull(SplashHandoff.glideScale(50f, 0f, 0.5f))
        assertNull(SplashHandoff.glideScale(0f, 100f, 0.5f))
        assertNull(SplashHandoff.glideScale(Float.NaN, 100f, 0.5f))
        assertNull(SplashHandoff.glideScale(50f, Float.POSITIVE_INFINITY, 0.5f))
    }
}
