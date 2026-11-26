package com.solutions5060.aria.ui.splash

import org.junit.Assert.assertEquals
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
}
