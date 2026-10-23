package dev.placeholder.web

import kotlin.js.Date

fun formatBytes(bytes: Int): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes / 102.4).toInt() / 10.0} KB"
        else -> "${(bytes / (1024 * 102.4)).toInt() / 10.0} MB"
    }

fun formatLines(lines: Int): String = if (lines == 1) "1 line" else "$lines lines"

/** "1,204": grouped the way the reader's locale groups digits. */
fun formatCount(count: Long): String = GROUPED.format(count.toDouble())

/** "1 visit", "1,204 visits". */
fun formatVisits(visits: Long): String = if (visits == 1L) "1 visit" else "${formatCount(visits)} visits"

/** "1 person", "412 people". */
fun formatPeople(people: Long): String = if (people == 1L) "1 person" else "${formatCount(people)} people"

private val GROUPED = Intl.NumberFormat()

/** The browser's own number formatting, which knows every locale's digit grouping. */
private external object Intl {
    class NumberFormat {
        fun format(value: Double): String
    }
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

fun formatDate(epochMillis: Double): String {
    val date = Date(epochMillis)
    return "${date.getDate()} ${MONTHS[date.getMonth()]} ${date.getFullYear()}"
}

private val WEEKDAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

/**
 * "Thu 23 Oct" for an ISO date like "2026-10-23". Stats days are UTC days, so this reads the date as written rather
 * than converting it through the browser's time zone, which would show yesterday's date west of Greenwich.
 */
fun formatDay(
    isoDate: String,
    withWeekday: Boolean = true,
): String {
    val (year, month, day) = isoDate.split('-').map(String::toInt)
    val label = "$day ${MONTHS[month - 1]}"
    if (!withWeekday) return label
    val weekday = Date(Date.UTC(year, month - 1, day)).getUTCDay()
    return "${WEEKDAYS[weekday]} $label"
}

/** "https://snip.example/abc1234" → "snip.example/" so the slug can be set apart from its host. */
fun hostPrefix(url: String): String = url.substringAfter("://").substringBeforeLast('/') + "/"

/** For text shown through innerHTML when there's no highlighter output to use instead. */
fun escapeHtml(text: String): String =
    text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
