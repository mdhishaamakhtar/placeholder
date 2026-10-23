package dev.placeholder.web.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.placeholder.shared.DayStats
import dev.placeholder.web.formatCount
import dev.placeholder.web.formatDay
import dev.placeholder.web.formatPeople
import dev.placeholder.web.formatVisits
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Visits per day as a row of bars, oldest on the left, today on the right. One day is selected at a time and read out
 * in words below the bars: today to begin with, then whichever bar the pointer is over, or the arrow keys pick.
 * The readout is a live region, so moving through the days with the keyboard reads each one aloud.
 */
@Composable
fun VisitsChart(days: List<DayStats>) {
    var selected by remember(days) { mutableIntStateOf(days.lastIndex) }
    val busiest = days.maxOf { it.visits }
    val scale = busiest.coerceAtLeast(1).toDouble()

    Div({ classes("chart") }) {
        Div({ classes("chart-head") }) {
            Span { Text("Last ${days.size} days") }
            Span { Text("Busiest day: ${formatCount(busiest)}") }
        }
        Div({
            classes("bars")
            attr("role", "group")
            attr("tabindex", "0")
            attr("aria-label", "Visits per day. Use the arrow keys to move between days.")
            attr("aria-describedby", READOUT_ID)
            onKeyDown {
                selected =
                    when (it.key) {
                        "ArrowLeft" -> (selected - 1).coerceAtLeast(0)
                        "ArrowRight" -> (selected + 1).coerceAtMost(days.lastIndex)
                        "Home" -> 0
                        "End" -> days.lastIndex
                        else -> return@onKeyDown
                    }
                it.preventDefault()
            }
            onMouseLeave { selected = days.lastIndex }
        }) {
            days.forEachIndexed { index, day ->
                Div({
                    classes("bar")
                    if (index == selected) classes("on")
                    onMouseEnter { selected = index }
                }) {
                    Span({ style { property("--share", "${day.visits / scale * 100}%") } })
                }
            }
        }
        Div({ classes("chart-axis") }) {
            Span { Text(formatDay(days.first().day, withWeekday = false)) }
            Span { Text("Today") }
        }
        P({
            classes("readout")
            attr("id", READOUT_ID)
            attr("aria-live", "polite")
        }) {
            val day = days[selected]
            Span({ classes("readout-day") }) {
                Text(if (selected == days.lastIndex) "Today, ${formatDay(day.day)}" else formatDay(day.day))
            }
            Text(" ${formatVisits(day.visits)}, ${formatPeople(day.visitors)}")
        }
    }
}

private const val READOUT_ID = "visits-readout"
