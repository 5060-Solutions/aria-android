package com.solutions5060.aria.fixtures

import android.content.Intent
import android.os.SystemClock
import com.solutions5060.aria.BuildConfig
import com.solutions5060.aria.call.AudioRouteOption
import com.solutions5060.aria.call.AudioRouteState
import com.solutions5060.aria.call.AudioRouteType
import com.solutions5060.aria.call.CallManager
import com.solutions5060.aria.call.CallPhase
import com.solutions5060.aria.call.CallUiState
import com.solutions5060.aria.history.CallHistoryEntry
import com.solutions5060.aria.ui.contacts.Contact
import com.solutions5060.aria.voicemail.VoicemailMessage
import uniffi.aria_mobile.CallDirection
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Debug-only screenshot / test mode: the app renders fixed sample data and
 * never touches the network, the engine, or the saved account.
 *
 * Start it from adb (debug builds only; release builds ignore the extras):
 *
 * ```
 * adb shell am start -S -n com.solutions5060.aria/.MainActivity \
 *     --es aria.fixture in-call --es aria.fixture.tab history
 * ```
 *
 * or from an instrumented test, before launching the activity:
 *
 * ```
 * Fixtures.install(FixtureSet.standard(FixtureScene.MAIN))
 * ```
 *
 * The fixture lives only in memory: killing the process returns the app to
 * its real state.
 */
object Fixtures {

    /** `--es aria.fixture <scene>`: see [FixtureScene.id]. */
    const val EXTRA_SCENE = "aria.fixture"

    /** `--es aria.fixture.tab <route>`: dialer, history, contacts, voicemail, settings. */
    const val EXTRA_TAB = "aria.fixture.tab"

    /** `--es aria.fixture.number <digits>`: pre-filled dialer number. */
    const val EXTRA_NUMBER = "aria.fixture.number"

    /** `--ez aria.fixture.playing true`: the first voicemail is expanded and playing. */
    const val EXTRA_PLAYING = "aria.fixture.playing"

    /** `--es aria.fixture.sheet <name>`: a sheet open on top of the scene (e.g. "connection", "keypad"). */
    const val EXTRA_SHEET = "aria.fixture.sheet"

    @Volatile
    var current: FixtureSet? = null
        private set

    /** True when sample data replaces the real app state. Always false in release builds. */
    val active: Boolean get() = BuildConfig.DEBUG && current != null

    /** Installs [set] and publishes its calls. No-op in release builds. */
    fun install(set: FixtureSet) {
        if (!BuildConfig.DEBUG) return
        current = set
        CallManager.showFixtureCalls(set.calls)
    }

    fun clear() {
        if (!BuildConfig.DEBUG) return
        current = null
        CallManager.showFixtureCalls(emptyList())
    }

    /** The gateway connection a fixture [account] shows: registered, with its gateway. */
    fun connectionState(account: FixtureAccount) = com.solutions5060.aria.service.ConnectionState(
        status = if (account.registered) {
            com.solutions5060.aria.service.ConnectionStatus.REGISTERED
        } else {
            com.solutions5060.aria.service.ConnectionStatus.ERROR
        },
        gatewayUrl = account.gatewayUrl,
        deviceId = account.deviceId,
    )

    /** Installs the fixture an intent asks for, if any. Returns whether one was installed. */
    fun installFrom(intent: Intent?): Boolean {
        if (!BuildConfig.DEBUG || intent == null) return false
        val sceneId = intent.getStringExtra(EXTRA_SCENE) ?: return false
        val scene = FixtureScene.entries.firstOrNull { it.id == sceneId } ?: FixtureScene.MAIN
        install(
            FixtureSet.standard(
                scene = scene,
                startTab = intent.getStringExtra(EXTRA_TAB),
                dialNumber = intent.getStringExtra(EXTRA_NUMBER),
                voicemailPlaying = intent.getBooleanExtra(EXTRA_PLAYING, false),
                sheet = intent.getStringExtra(EXTRA_SHEET),
            ),
        )
        return true
    }
}

/** What the fixture shows when it starts. */
enum class FixtureScene(val id: String) {
    /** Signed out: the setup / onboarding screen. */
    ONBOARDING("onboarding"),

    /** Signed in, no call: the tabs. */
    MAIN("main"),

    /** A call ringing in. */
    INCOMING("incoming"),

    /** One connected call. */
    IN_CALL("in-call"),

    /** One connected call whose media is not encrypted: the phone system declined SRTP. */
    IN_CALL_UNENCRYPTED("in-call-unencrypted"),

    /** A connected call plus a second one on hold. */
    TWO_CALLS("two-calls"),

    /** A connected call with a second call ringing in (call waiting). */
    CALL_WAITING("call-waiting"),
}

data class FixtureAccount(
    val displayName: String,
    val extension: String,
    val domain: String,
    val server: String,
    val transport: String,
    val port: Int,
    val registered: Boolean = true,
    val deviceId: String? = null,
    val extensionId: String? = null,
    val gatewayUrl: String? = null,
)

/** Everything a fixture run shows. Screens read the parts they need. */
data class FixtureSet(
    val scene: FixtureScene,
    /** Null means signed out. */
    val account: FixtureAccount?,
    val history: List<CallHistoryEntry>,
    val contacts: List<Contact>,
    val voicemail: List<VoicemailMessage>,
    val calls: List<CallUiState>,
    val startTab: String? = null,
    val dialNumber: String? = null,
    /** Id of the voicemail shown expanded and playing, if any. */
    val voicemailPlayingId: String? = null,
    val sheet: String? = null,
) {
    companion object {
        fun standard(
            scene: FixtureScene = FixtureScene.MAIN,
            startTab: String? = null,
            dialNumber: String? = null,
            voicemailPlaying: Boolean = false,
            sheet: String? = null,
            nowMillis: Long = System.currentTimeMillis(),
            nowElapsed: Long = SystemClock.elapsedRealtime(),
        ): FixtureSet {
            val voicemail = FixtureData.voicemail(nowMillis)
            return FixtureSet(
                scene = scene,
                account = if (scene == FixtureScene.ONBOARDING) null else FixtureData.account,
                history = FixtureData.history(nowMillis),
                contacts = FixtureData.contacts,
                voicemail = voicemail,
                calls = FixtureData.calls(scene, nowElapsed),
                startTab = startTab,
                dialNumber = dialNumber,
                voicemailPlayingId = if (voicemailPlaying) voicemail.first().id else null,
                sheet = sheet,
            )
        }
    }
}

/** The sample data itself. Names and numbers are fictional (555 numbers). */
object FixtureData {

    val account = FixtureAccount(
        displayName = "Alex Rivera",
        extension = "1042",
        domain = "acme.ariaroute.com",
        server = "sip.ariaroute.com",
        transport = "tls",
        port = 5061,
        deviceId = "c0ffee42-5060-4a1e-9b7d-2f8e61d0a9b3",
        extensionId = "6f1d2c3b-aria-1042-8e7f-0a1b2c3d4e5f",
        gatewayUrl = "https://push.ariaroute.com",
    )

    val contacts: List<Contact> = listOf(
        Contact("Amelia Chen", "+1 (555) 201-4410", "Mobile", starred = true, contactId = 1),
        Contact("Amelia Chen", "+1 (555) 201-4499", "Work", starred = true, contactId = 1),
        Contact("Ben Okafor", "+1 (555) 318-2290", "Mobile", starred = true, contactId = 2),
        Contact("Carla Mendes", "+1 (555) 402-7781", "Mobile", contactId = 3),
        Contact("Daniel Weiss", "+1 (555) 610-3345", "Work", contactId = 4),
        Contact("Front Desk", "1000", "Work", contactId = 5),
        Contact("Grace Kim", "+1 (555) 742-1108", "Mobile", contactId = 6),
        Contact("Hiro Tanaka", "+1 (555) 827-5562", "Mobile", contactId = 7),
        Contact("Isabel Ortiz", "+1 (555) 913-0047", "Mobile", contactId = 8),
        Contact("Jonas Berg", "+1 (555) 284-6619", "Home", contactId = 9),
        Contact("Maya Patel", "1017", "Work", starred = true, contactId = 10),
        Contact("Noah Fischer", "+1 (555) 356-9921", "Mobile", contactId = 11),
        Contact("Priya Shah", "+1 (555) 471-2083", "Mobile", contactId = 12),
        Contact("Sam Lee", "1023", "Work", contactId = 13),
    )

    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    fun history(now: Long): List<CallHistoryEntry> = listOf(
        entry("h1", "sip:+15552014410@acme.ariaroute.com", "Amelia Chen", "inbound", now - 12 * 60_000L, 342),
        entry("h2", "sip:+15553182290@acme.ariaroute.com", "Ben Okafor", "inbound", now - 2 * HOUR, 0, missed = true),
        entry("h3", "sip:1017@acme.ariaroute.com", "Maya Patel", "outbound", now - 3 * HOUR, 95),
        entry("h4", "sip:+15554027781@acme.ariaroute.com", "Carla Mendes", "outbound", now - DAY - 2 * HOUR, 1260),
        entry("h5", "sip:+15559130047@acme.ariaroute.com", null, "inbound", now - DAY - 5 * HOUR, 0, missed = true),
        entry("h6", "sip:1000@acme.ariaroute.com", "Front Desk", "inbound", now - 2 * DAY, 48, transferred = true),
        entry("h7", "sip:+15558275562@acme.ariaroute.com", "Hiro Tanaka", "outbound", now - 3 * DAY, 0),
        entry("h8", "sip:+15557421108@acme.ariaroute.com", "Grace Kim", "inbound", now - 4 * DAY, 611),
    )

    private fun entry(
        id: String,
        uri: String,
        name: String?,
        direction: String,
        timestamp: Long,
        duration: Int,
        missed: Boolean = false,
        transferred: Boolean = false,
    ) = CallHistoryEntry(
        id = id,
        remoteUri = uri,
        remoteName = name,
        direction = direction,
        timestamp = timestamp,
        durationSeconds = duration,
        missed = missed,
        transferred = transferred,
    )

    fun voicemail(now: Long): List<VoicemailMessage> {
        fun at(offset: Long) = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(now - offset), ZoneOffset.UTC).toString()
        return listOf(
            VoicemailMessage(
                id = "vm1",
                boxId = "box",
                callerId = "+15553182290",
                durationSecs = 38,
                read = false,
                createdAt = at(2 * HOUR),
                transcription = "Hi Alex, it's Ben from Okafor Logistics. The shipment is confirmed for Thursday " +
                    "morning. Give me a call back when you get a chance so we can lock in the delivery window. Thanks!",
            ),
            VoicemailMessage(
                id = "vm2",
                boxId = "box",
                callerId = "+15559130047",
                durationSecs = 72,
                read = false,
                createdAt = at(DAY + 5 * HOUR),
                transcription = "Hello, this is Isabel Ortiz calling about the quote you sent over last week. " +
                    "I have a couple of questions about the support plan.",
            ),
            VoicemailMessage(
                id = "vm3",
                boxId = "box",
                callerId = "sip:1017@acme.ariaroute.com",
                durationSecs = 15,
                read = true,
                createdAt = at(3 * DAY),
                transcription = "Hey, it's Maya. Running five minutes late to the stand-up.",
            ),
        )
    }

    private val audio = AudioRouteState(
        options = listOf(
            AudioRouteOption(id = "earpiece", label = "Phone", type = AudioRouteType.EARPIECE),
            AudioRouteOption(id = "speaker", label = "Speaker", type = AudioRouteType.SPEAKER),
            AudioRouteOption(id = "bt", label = "Pixel Buds", type = AudioRouteType.BLUETOOTH),
        ),
        currentId = "earpiece",
    )

    fun calls(scene: FixtureScene, nowElapsed: Long): List<CallUiState> {
        val amelia = CallUiState(
            key = "fixture-a",
            callId = "fixture-call-a",
            remoteUri = "sip:+15552014410@acme.ariaroute.com",
            remoteName = "Amelia Chen",
            direction = CallDirection.OUTBOUND,
            phase = CallPhase.ACTIVE,
            connectedAtElapsed = nowElapsed - 754_000L,
            audio = audio,
            encrypted = true,
            // As the core reports it: the SDP name.
            codec = "opus",
        )
        val ben = CallUiState(
            key = "fixture-b",
            callId = "fixture-call-b",
            remoteUri = "sip:+15553182290@acme.ariaroute.com",
            remoteName = "Ben Okafor",
            direction = CallDirection.INBOUND,
            phase = CallPhase.INCOMING,
            audio = audio,
        )
        return when (scene) {
            FixtureScene.ONBOARDING, FixtureScene.MAIN -> emptyList()
            FixtureScene.INCOMING -> listOf(ben)
            FixtureScene.IN_CALL -> listOf(amelia)
            FixtureScene.IN_CALL_UNENCRYPTED -> listOf(amelia.copy(encrypted = false))
            FixtureScene.TWO_CALLS -> listOf(
                amelia.copy(phase = CallPhase.HELD, onHold = true),
                ben.copy(phase = CallPhase.ACTIVE, connectedAtElapsed = nowElapsed - 83_000L, encrypted = true, codec = "PCMU"),
            )
            FixtureScene.CALL_WAITING -> listOf(amelia, ben)
        }
    }
}
