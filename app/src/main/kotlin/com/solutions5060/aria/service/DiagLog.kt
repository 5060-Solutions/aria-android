package com.solutions5060.aria.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * A short, in-memory log of connection and call events for the diagnostics
 * screen's copyable log. Never holds secrets or personal data: no tokens,
 * passwords, phone numbers or caller names, only states, hosts and error
 * kinds. Cleared when the process ends.
 */
object DiagLog {

    const val MAX_LINES = 300
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    @Volatile
    internal var clock: () -> LocalTime = { LocalTime.now() }

    /**
     * Debug builds append every line to a file as well (see [AriaApplication]):
     * some devices drop an app's own logcat output, and the file can be read
     * with `adb shell run-as <package> cat files/diag.log`.
     */
    @Volatile
    var fileSink: ((String) -> Unit)? = null

    fun log(area: String, message: String) {
        val line = "${clock().format(time)} $area: $message"
        try {
            android.util.Log.i("AriaDiag", line)
            fileSink?.invoke(line)
        } catch (_: Exception) {
            // Unit tests (no Android) and a full disk: the in-memory log is the record.
        }
        synchronized(this) {
            val next = _lines.value + line
            _lines.value = if (next.size > MAX_LINES) next.takeLast(MAX_LINES) else next
        }
    }

    fun text(): String = _lines.value.joinToString("\n")

    fun clear() {
        _lines.value = emptyList()
    }
}
