package com.solutions5060.aria

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.solutions5060.aria.call.CallManager
import com.solutions5060.aria.fixtures.Fixtures
import com.solutions5060.aria.pbx.ForwardType
import com.solutions5060.aria.pbx.SelfServiceState
import com.solutions5060.aria.provisioning.DeepLinks
import com.solutions5060.aria.ui.forwarding.ForwardingTags
import com.solutions5060.aria.ui.settings.CallingSectionTags
import com.solutions5060.aria.ui.setup.ManualSetupTags
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * End-to-end UI checks on the real activity in debug fixture mode (sample
 * data, no network, no engine). Run on the aria_pixel_b AVD:
 * `ANDROID_SERIAL=emulator-5556 ./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class AriaUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun str(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    @Before
    fun reset() {
        SelfServiceState.clear()
        DeepLinks.pending.value?.let(DeepLinks::consume)
    }

    @After
    fun tearDown() {
        scenario?.close()
        Fixtures.clear()
    }

    /** Launches a fixture scene and checks that no runtime permission dialog came up with it. */
    private fun launch(scene: String, tab: String? = null) {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(Fixtures.EXTRA_SCENE, scene)
        tab?.let { intent.putExtra(Fixtures.EXTRA_TAB, it) }
        scenario = ActivityScenario.launch(intent)
        compose.waitForIdle()
        assertNoPermissionDialog()
    }

    /** No system permission prompt (contacts, microphone, camera, notifications) is on screen. */
    private fun assertNoPermissionDialog() {
        device.waitForIdle()
        val dialog = device.findObject(By.pkg(Pattern.compile(".*permissioncontroller.*")))
        assertNull("A runtime permission dialog appeared at launch", dialog)
    }

    private fun tab(@StringRes label: Int) = compose.onNode(hasText(str(label)) and hasClickAction())

    // ── Onboarding and manual login ────────────────────────────────────

    @Test
    fun manualLoginValidatesEveryField() {
        launch("onboarding")
        // Onboarding: hero, then the setup method, then the permission primer.
        compose.onNodeWithText(str(R.string.ui_onboarding_get_started)).performClick()
        compose.onNodeWithText(str(R.string.ui_onboarding_manual)).performClick()
        compose.onNodeWithText(str(R.string.ui_onboarding_continue)).performClick()

        compose.onNodeWithTag(ManualSetupTags.SIGN_IN).performClick()
        compose.onNodeWithText(str(R.string.manual_setup_error_server_required)).assertExists()
        compose.onNodeWithText(str(R.string.manual_setup_error_extension_required)).assertExists()
        compose.onNodeWithText(str(R.string.manual_setup_error_password_required)).assertExists()

        compose.onNodeWithTag(ManualSetupTags.SERVER).performTextInput("not a host")
        compose.onNodeWithTag(ManualSetupTags.EXTENSION).performTextInput("101")
        compose.onNodeWithTag(ManualSetupTags.PASSWORD).performTextInput("secret")
        compose.onNodeWithTag(ManualSetupTags.API_URL).performTextInput("http://api.example.com")
        compose.onNodeWithTag(ManualSetupTags.SIGN_IN).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.manual_setup_error_server_invalid)).assertExists()
        // HTTPS only: a cleartext API URL is refused, never upgraded or used.
        compose.onNodeWithText(str(R.string.manual_setup_error_api_not_https)).assertExists()
        compose.onNodeWithText(str(R.string.manual_setup_error_extension_required)).assertDoesNotExist()
    }

    // ── Tabs ──────────────────────────────────────────────────────────

    @Test
    fun everyTabOpens() {
        launch("main")
        for (label in listOf(
            R.string.ui_tab_history,
            R.string.ui_tab_contacts,
            R.string.ui_tab_voicemail,
            R.string.ui_tab_settings,
            R.string.ui_tab_dialer,
        )) {
            tab(label).performClick()
            compose.waitForIdle()
            tab(label).assertIsSelected()
            assertNoPermissionDialog()
        }
        tab(R.string.ui_tab_settings).performClick()
        compose.onNodeWithTag(CallingSectionTags.DND).assertExists()
    }

    // ── Dialer and emergency hand-off ────────────────────────────────

    /** A dial key: TalkBack reads "5, J K L", or just "1". */
    private fun key(digit: String) = compose.onNode(
        SemanticsMatcher("dial key $digit") { node ->
            val cd = node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) { null }?.firstOrNull()
            cd == digit || cd?.startsWith("$digit,") == true
        } and hasClickAction(),
    )

    @Test
    fun dialerTakesDigitsAndBackspace() {
        launch("main", tab = "dialer")
        key("5").performClick()
        key("5").performClick()
        key("1").performClick()
        compose.onNode(hasContentDescription("${str(R.string.ui_dialer_number)}, 5 5 1")).assertExists()
        compose.onNodeWithContentDescription(str(R.string.ui_dialer_delete)).performClick()
        compose.onNode(hasContentDescription("${str(R.string.ui_dialer_number)}, 5 5")).assertExists()
    }

    @Test
    fun anEmergencyNumberGoesToTheSystemDialerAndNeverOverVoip() {
        Intents.init()
        try {
            // Answer the hand-off ourselves so no real dialer opens on the AVD.
            intending(hasAction(Intent.ACTION_DIAL)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            launch("main", tab = "dialer")
            key("9").performClick()
            key("1").performClick()
            key("1").performClick()
            compose.onNodeWithContentDescription(str(R.string.ui_dialer_call)).performClick()
            compose.waitForIdle()

            intended(allOf(hasAction(Intent.ACTION_DIAL), hasData(Uri.parse("tel:911"))))
            // No VoIP call was started: no call record, nothing on the call screen.
            assertTrue(CallManager.calls.value.isEmpty())
            assertNull(CallManager.state.value)
        } finally {
            Intents.release()
        }
    }

    // ── Do Not Disturb and forwarding ─────────────────────────────────

    @Test
    fun dndToggleAndForwardingSave() {
        launch("main", tab = "settings")
        compose.onNodeWithTag(CallingSectionTags.DND).performScrollTo()
        compose.waitUntil(5_000) { SelfServiceState.state.value.doNotDisturb != null }
        // The whole row is the switch (one toggleable node inside the tagged row).
        val dnd = compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag(CallingSectionTags.DND)))
        dnd.assertIsOff()
        dnd.performClick()
        compose.waitUntil(5_000) { SelfServiceState.state.value.doNotDisturb == true }
        dnd.assertIsOn()

        compose.onNodeWithTag(CallingSectionTags.FORWARDING).performScrollTo().performClick()
        val type = ForwardType.UNCONDITIONAL
        compose.onNodeWithTag(ForwardingTags.destination(type)).performTextInput("2001")
        compose.onNodeWithTag(ForwardingTags.save(type)).performClick()
        compose.waitUntil(5_000) { SelfServiceState.state.value.forward(type)?.destination == "2001" }
        compose.onNodeWithTag(ForwardingTags.enabled(type)).assertIsOn()
        assertTrue(SelfServiceState.state.value.forwardingOn)
    }

    // ── Merge and split on the two-call fixture ──────────────────────

    @Test
    fun mergeThenSplitTheTwoCalls() {
        launch("two-calls")
        assertEquals(2, CallManager.calls.value.size)

        // The other-call row offers Merge next to Swap and End.
        compose.onNode(hasContentDescription(str(R.string.ui_call_merge)) and hasClickAction()).performClick()
        compose.waitUntil(5_000) { CallManager.calls.value.all { it.inConference } }

        // Split lives in the overflow menu while conferenced.
        compose.onNodeWithContentDescription(str(R.string.ui_call_more)).performClick()
        compose.onNode(hasText(str(R.string.ui_call_split)) and hasClickAction()).performClick()
        compose.waitUntil(5_000) { CallManager.calls.value.none { it.inConference } }
        val (older, newer) = CallManager.calls.value
        assertTrue(older.onHold)
        assertTrue(!newer.onHold)
    }

    // ── Launch permissions ───────────────────────────────────────────

    @Test
    fun noSceneRaisesAPermissionDialogAtLaunch() {
        for (scene in listOf("onboarding", "main", "incoming", "in-call", "two-calls", "call-waiting")) {
            launch(scene)
            scenario?.close()
        }
        for (t in listOf("contacts", "voicemail", "history", "settings")) {
            launch("main", tab = t)
            scenario?.close()
        }
    }
}
