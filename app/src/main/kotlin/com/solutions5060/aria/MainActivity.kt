package com.solutions5060.aria

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.solutions5060.aria.fixtures.Fixtures
import com.solutions5060.aria.provisioning.DeepLinks
import com.solutions5060.aria.ui.AriaApp
import com.solutions5060.aria.ui.theme.AriaTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // The system splash (Theme.Aria.Starting) is the only splash; it hands
        // over to Theme.Aria as soon as the first Compose frame is drawn.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Debug builds only: `--es aria.fixture <scene>` shows sample data (see Fixtures).
        Fixtures.installFrom(intent)
        // aria://provision, tel: and sip: links; not again after a configuration change.
        // A task reopened from Recents is relaunched with its original intent:
        // that link was handled then and must not prompt again.
        val fromHistory = (intent?.flags ?: 0) and android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) {
            DeepLinks.offer(DeepLinks.parse(intent?.action, intent?.dataString))
            DeepLinks.offer(tabLink(intent))
        }

        setContent {
            AriaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AriaApp()
                }
            }
        }
    }

    /** The tab one of our own notifications asks for (not exported to other apps' data). */
    private fun tabLink(intent: android.content.Intent?): com.solutions5060.aria.provisioning.DeepLink? =
        intent?.getStringExtra(com.solutions5060.aria.voicemail.VoicemailMonitor.EXTRA_OPEN_TAB)
            ?.takeIf { it in com.solutions5060.aria.provisioning.DeepLink.OpenTab.ROUTES }
            ?.let { com.solutions5060.aria.provisioning.DeepLink.OpenTab(it) }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // A new fixture scene on a running (singleTask) activity starts over.
        if (Fixtures.installFrom(intent)) recreate()
        DeepLinks.offer(DeepLinks.parse(intent.action, intent.dataString))
        DeepLinks.offer(tabLink(intent))
    }
}
