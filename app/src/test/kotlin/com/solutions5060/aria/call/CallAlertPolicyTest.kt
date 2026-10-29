package com.solutions5060.aria.call

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallAlertPolicyTest {

    @Test
    fun notificationsOffMeansTheAppRings() {
        assertTrue(IncomingAlertPolicy.ringInApp(false, NotificationManager.IMPORTANCE_HIGH, true))
    }

    @Test
    fun aSoundingChannelRingsSoTheAppDoesNot() {
        assertFalse(IncomingAlertPolicy.ringInApp(true, NotificationManager.IMPORTANCE_HIGH, true))
        assertFalse(IncomingAlertPolicy.ringInApp(true, NotificationManager.IMPORTANCE_DEFAULT, true))
    }

    @Test
    fun aQuietOrMissingChannelMeansTheAppRings() {
        assertTrue(IncomingAlertPolicy.ringInApp(true, NotificationManager.IMPORTANCE_LOW, true))
        assertTrue(IncomingAlertPolicy.ringInApp(true, NotificationManager.IMPORTANCE_NONE, true))
        assertTrue(IncomingAlertPolicy.ringInApp(true, NotificationManager.IMPORTANCE_HIGH, false))
        assertTrue(IncomingAlertPolicy.ringInApp(true, null, true))
    }

    @Test
    fun setupProblemsAreReportedMostSeriousFirst() {
        assertEquals(
            listOf(CallAlertSetup.Problem.NOTIFICATIONS_OFF),
            CallAlertSetup.problems(notificationsEnabled = false, canUseFullScreenIntent = false),
        )
        assertEquals(
            listOf(CallAlertSetup.Problem.FULL_SCREEN_DENIED),
            CallAlertSetup.problems(notificationsEnabled = true, canUseFullScreenIntent = false),
        )
        assertEquals(emptyList<CallAlertSetup.Problem>(), CallAlertSetup.problems(true, true))
    }
}
