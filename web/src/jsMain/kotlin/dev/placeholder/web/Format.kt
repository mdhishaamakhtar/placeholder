package dev.placeholder.web

import kotlin.js.Date

fun formatBytes(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes / 102.4).toInt() / 10.0} KB"
    else -> "${(bytes / (1024 * 102.4)).toInt() / 10.0} MB"
}

fun formatLines(lines: Int): String = if (lines == 1) "1 line" else "$lines lines"

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

fun formatDate(epochMillis: Double): String {
    val date = Date(epochMillis)
    return "${date.getDate()} ${MONTHS[date.getMonth()]} ${date.getFullYear()}"
}

/** "https://snip.example/abc1234" → "snip.example/" so the slug can be set apart from its host. */
fun hostPrefix(url: String): String = url.substringAfter("://").substringBeforeLast('/') + "/"

/** For text shown through innerHTML when there's no highlighter output to use instead. */
fun escapeHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
