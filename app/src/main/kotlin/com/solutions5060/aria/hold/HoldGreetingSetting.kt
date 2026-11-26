package com.solutions5060.aria.hold

import android.content.Context
import com.solutions5060.aria.service.EngineManager
import kotlinx.coroutines.CancellationException

/** Where the last known default greeting is kept on this phone. */
interface GreetingMirror {
    fun get(): HoldGreeting?
    fun set(g: HoldGreeting)
}

/** The mirror in the app's own preferences (`hold_for_me_greeting`). */
class PrefsGreetingMirror(context: Context) : GreetingMirror {
    private val prefs = context.applicationContext.getSharedPreferences(EngineManager.PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(): HoldGreeting? = prefs.getString(KEY, null)?.let(HoldGreeting::fromWire)

    override fun set(g: HoldGreeting) {
        prefs.edit().putString(KEY, g.wire).apply()
    }

    companion object {
        const val KEY = "hold_for_me_greeting"
    }
}

/**
 * The default greeting in Settings. Like the call hand-off screen it keeps a
 * local mirror so the row answers at once, and tells the PBX in the
 * background; a refusal puts the previous value back.
 */
class HoldGreetingSetting(private val api: HoldApi, private val mirror: GreetingMirror) {

    /** What the row shows before the PBX answers. */
    fun current(): HoldGreeting = mirror.get() ?: HoldGreeting.LINE

    /** Reads the PBX's settings and mirrors the greeting; when it cannot be read, the mirror and no assistant. */
    suspend fun load(): HoldSettings = try {
        api.settings().also { mirror.set(it.greeting) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        HoldSettings.Fallback.copy(greeting = current())
    }

    /** Saves [g]; false (and the previous greeting restored) when the PBX did not accept it. */
    suspend fun pick(g: HoldGreeting): Boolean {
        val previous = current()
        mirror.set(g)
        return try {
            mirror.set(api.setGreeting(g).greeting)
            true
        } catch (e: CancellationException) {
            mirror.set(previous)
            throw e
        } catch (_: Exception) {
            mirror.set(previous)
            false
        }
    }
}

/** What a tapped (or foreground) `hold_wait` alert push says. */
data class HoldNotice(val event: String, val remoteNumber: String, val remoteName: String) {
    /** The name the banner uses: `remote_name` when there is one, else the number. */
    val title: String get() = remoteName.ifEmpty { remoteNumber }
}

object HoldNotificationJson {
    private val EVENTS = setOf("far_hung_up", "missed", "max_wait", "lost")
    private const val MAX = 64

    /** The alert push's `data` (FCM extras on a tap); null when it is not a well-formed `hold_wait`. */
    fun parse(extras: Map<String, String>): HoldNotice? {
        if (extras["type"] != "hold_wait") return null
        val event = extras["event"]?.takeIf { it in EVENTS } ?: return null
        val number = extras["remote_number"]?.trim()?.take(MAX)?.takeIf { it.isNotEmpty() } ?: return null
        val name = extras["remote_name"]?.trim()?.take(MAX).orEmpty()
        return HoldNotice(event, number, name)
    }
}
