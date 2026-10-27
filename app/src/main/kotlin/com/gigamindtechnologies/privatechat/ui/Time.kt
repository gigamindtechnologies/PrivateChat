package com.gigamindtechnologies.privatechat.ui

import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

private fun dayOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

private fun timeOf(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

private fun dateOf(day: LocalDate): String {
    val today = LocalDate.now()
    return if (day.year == today.year) {
        DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(day)
    } else {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).format(day)
    }
}

/** For the chat list: "10:42", "Yesterday", "Monday", "12 Oct". */
fun chatListTime(millis: Long): String {
    val day = dayOf(millis)
    val today = LocalDate.now()
    return when {
        day == today -> timeOf(millis)
        day == today.minusDays(1) -> "Yesterday"
        day.isAfter(today.minusDays(7)) -> day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        else -> dateOf(day)
    }
}

/** "last seen today at 10:42", "last seen yesterday at 22:10", "last seen 12 Oct at 09:15". */
fun lastSeenText(millis: Long): String {
    val day = dayOf(millis)
    val today = LocalDate.now()
    val whenDay = when (day) {
        today -> "today"
        today.minusDays(1) -> "yesterday"
        else -> dateOf(day)
    }
    return "last seen $whenDay at ${timeOf(millis)}"
}
