package com.solutions5060.aria.history

import android.content.Context
import android.util.Log
import com.solutions5060.aria.pbx.SelfServiceRepository
import com.solutions5060.aria.pbx.ServerCall
import java.time.OffsetDateTime
import kotlin.math.abs

/**
 * Merges the PBX's record of this extension's calls (`/extensions/{id}/cdrs`)
 * into the local history, so calls answered on another device, or placed
 * while this phone was off, show up too. Pure; unit-tested.
 */
object ServerHistory {

    /** Two records of one call start within this much of each other. */
    const val MATCH_WINDOW_MS = 90_000L

    /** Cap on the merged list. */
    const val MAX_ENTRIES = 200

    /**
     * [call] as a history entry seen from [ownExtension]: the other party,
     * and the direction from this user's side (an internal call is outgoing
     * when this extension placed it). Null for a record without a time.
     */
    fun entryFor(call: ServerCall, ownExtension: String): CallHistoryEntry? {
        val startedAt = parseMillis(call.startedAt) ?: return null
        val outbound = when (call.direction) {
            "outbound" -> true
            "inbound" -> false
            else -> numberOf(call.fromNumber, call.fromUri) == ownExtension
        }
        val number = if (outbound) numberOf(call.toNumber, call.toUri) else numberOf(call.fromNumber, call.fromUri)
        val name = if (outbound) call.toName else call.fromName
        val answered = call.disposition == "answered" || call.answeredAt != null
        return CallHistoryEntry(
            id = "pbx-${call.id}",
            remoteUri = "sip:$number",
            remoteName = name,
            direction = if (outbound) "outbound" else "inbound",
            timestamp = startedAt,
            durationSeconds = if (answered) call.durationSecs else 0,
            missed = !outbound && !answered,
        )
    }

    /**
     * Local entries plus every server call no local entry already covers,
     * newest first. A local entry covers a server call with the same other
     * party and direction that started within [MATCH_WINDOW_MS]; the local
     * one is kept (it knows about declines and transfers) and takes the
     * server's caller name if it had none. Each server call covers at most
     * one local entry.
     */
    fun merge(local: List<CallHistoryEntry>, server: List<ServerCall>, ownExtension: String): List<CallHistoryEntry> {
        val remaining = local.toMutableList()
        val result = mutableListOf<CallHistoryEntry>()
        for (call in server) {
            val entry = entryFor(call, ownExtension) ?: continue
            val match = remaining
                .filter { sameParty(it, entry) && it.direction == entry.direction && abs(it.timestamp - entry.timestamp) <= MATCH_WINDOW_MS }
                .minByOrNull { abs(it.timestamp - entry.timestamp) }
            if (match != null) {
                remaining.remove(match)
                result += if (match.remoteName.isNullOrBlank() && !entry.remoteName.isNullOrBlank()) {
                    match.copy(remoteName = entry.remoteName)
                } else {
                    match
                }
            } else {
                result += entry
            }
        }
        result += remaining
        return result.sortedByDescending { it.timestamp }.take(MAX_ENTRIES)
    }

    /** The digits/user part two records are compared on. */
    fun partyKey(uriOrNumber: String): String {
        val user = uriOrNumber.trim().removePrefix("sips:").removePrefix("sip:").removePrefix("tel:")
            .substringBefore('@').substringBefore(';')
        val digits = user.filter { it.isDigit() }
        // Compare the national part of an E.164 number with a dialled one.
        return if (digits.length >= 10) digits.takeLast(10) else user.lowercase()
    }

    private fun sameParty(a: CallHistoryEntry, b: CallHistoryEntry) = partyKey(a.remoteUri) == partyKey(b.remoteUri)

    private fun numberOf(number: String?, uri: String): String =
        number?.takeIf { it.isNotBlank() } ?: uri.removePrefix("sips:").removePrefix("sip:").substringBefore('@').substringBefore(';')

    private fun parseMillis(rfc3339: String): Long? = try {
        OffsetDateTime.parse(rfc3339).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }
}

/** Local history, merged with the server's when it can be reached. Blocks; call off the main thread. */
class HistoryRepository(private val context: Context) {
    fun merged(local: List<CallHistoryEntry> = CallHistoryStore.load(context)): List<CallHistoryEntry> = try {
        val own = context.getSharedPreferences("aria_prefs", Context.MODE_PRIVATE).getString("sip_username", "") ?: ""
        ServerHistory.merge(local, SelfServiceRepository.from(context).serverCalls(), own)
    } catch (e: Exception) {
        // Offline or no PBX: the local list is still right, just incomplete.
        Log.w("HistoryRepository", "Server history unavailable: ${e.javaClass.simpleName}")
        local
    }
}
