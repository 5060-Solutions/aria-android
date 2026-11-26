package com.solutions5060.aria.hold

import android.content.Context
import androidx.core.content.edit
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
        prefs.edit { putString(KEY, g.wire) }
    }

    /** Sign-out: the default belongs to the account. */
    fun clear() {
        prefs.edit { remove(KEY) }
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

/**
 * What a tapped (or foreground) `hold_wait` alert push says. [pushTitle] and
 * [pushBody] are the push's own notification text (the PBX's localised copy),
 * known only for a push received in the foreground; a tapped notification
 * delivers just the data.
 */
data class HoldNotice(
    val event: String,
    val remoteNumber: String,
    val remoteName: String,
    val pushTitle: String? = null,
    val pushBody: String? = null,
) {
    /** The name the banner uses: `remote_name` when there is one, else the number. */
    val title: String get() = remoteName.ifEmpty { remoteNumber }

    /**
     * The banner's headline: the push's own title when there is one, else
     * [missedTemplate] (`ui_hold_missed_title`) for a missed pickup, else the name.
     */
    fun headline(missedTemplate: String): String =
        pushTitle ?: if (event == "missed") String.format(missedTemplate, title) else title
}

object HoldNotificationJson {
    private val EVENTS = setOf("far_hung_up", "missed", "max_wait", "lost")
    private const val MAX = 64
    private const val MAX_TITLE = 160
    private const val MAX_BODY = 400

    /**
     * The alert push's `data` (FCM extras on a tap), plus its notification
     * [title] and [body] when the push arrived in the foreground; null when it
     * is not a well-formed `hold_wait`. A blank title or body counts as absent.
     */
    fun parse(extras: Map<String, String>, title: String? = null, body: String? = null): HoldNotice? {
        if (extras["type"] != "hold_wait") return null
        val event = extras["event"]?.takeIf { it in EVENTS } ?: return null
        val number = extras["remote_number"]?.trim()?.take(MAX)?.takeIf { it.isNotEmpty() } ?: return null
        val name = extras["remote_name"]?.trim()?.take(MAX).orEmpty()
        return HoldNotice(
            event,
            number,
            name,
            pushTitle = title?.trim()?.take(MAX_TITLE)?.ifEmpty { null },
            pushBody = body?.trim()?.take(MAX_BODY)?.ifEmpty { null },
        )
    }
}
