package com.solutions5060.aria.ui.history

import com.solutions5060.aria.history.CallHistoryEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** History filter (DESIGN.md §5 segmented control). */
enum class HistoryFilter { All, Missed }

/** A day's section of the history list. */
data class HistoryDay(val date: LocalDate, val entries: List<CallHistoryEntry>)

/** Pure grouping and filtering for the History tab, unit-tested. */
object HistoryGrouping {

    fun isMissed(entry: CallHistoryEntry): Boolean = entry.missed

    fun filter(entries: List<CallHistoryEntry>, filter: HistoryFilter): List<CallHistoryEntry> = when (filter) {
        HistoryFilter.All -> entries
        HistoryFilter.Missed -> entries.filter(::isMissed)
    }

    /** Newest first, one section per local calendar day. */
    fun byDay(entries: List<CallHistoryEntry>, zone: ZoneId): List<HistoryDay> =
        entries.sortedByDescending { it.timestamp }
            .groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
            .map { (date, list) -> HistoryDay(date, list) }

    /** Today, Yesterday, or null when the caller should format the date itself. */
    fun relativeDay(date: LocalDate, today: LocalDate): RelativeDay? = when (date) {
        today -> RelativeDay.Today
        today.minusDays(1) -> RelativeDay.Yesterday
        else -> null
    }

    enum class RelativeDay { Today, Yesterday }

    /** Every call with the same dialable number as [entry], newest first (the info sheet). */
    fun callsWith(entry: CallHistoryEntry, entries: List<CallHistoryEntry>): List<CallHistoryEntry> =
        entries.filter { it.dialNumber == entry.dialNumber }.sortedByDescending { it.timestamp }
}
