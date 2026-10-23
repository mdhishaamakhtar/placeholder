package dev.placeholder.web.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import org.w3c.dom.HTMLElement
import org.w3c.dom.NEAREST
import org.w3c.dom.Node
import org.w3c.dom.ScrollIntoViewOptions
import org.w3c.dom.ScrollLogicalPosition
import org.w3c.dom.events.Event

/**
 * A themed single-choice menu: an underlined text trigger that opens a small list upward from where it sits.
 * Replaces the browser's own <select>, which can't be styled to match.
 *
 * Keyboard focus never leaves the trigger (the ARIA combobox pattern): arrows move the highlight,
 * Enter or Space chooses, Escape or Tab closes. Moving real focus into the list was fragile, because
 * re-rendering the list's attributes can briefly make it unfocusable and drop focus to the page.
 */
@Composable
fun <T> ChoiceMenu(
    label: String,
    options: List<Pair<T, String>>,
    value: T,
    onChange: (T) -> Unit,
    id: String,
    alignStart: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    var active by remember { mutableIntStateOf(0) }
    var root by remember { mutableStateOf<HTMLElement?>(null) }
    val selectedIndex = options.indexOfFirst { it.first == value }.coerceAtLeast(0)

    fun openMenu() {
        active = selectedIndex
        open = true
    }

    fun choose(index: Int) {
        onChange(options[index].first)
        open = false
    }

    // Long lists scroll: keep the highlighted option in view as the arrows move it.
    LaunchedEffect(open, active) {
        if (open) {
            document.getElementById("$id-option-$active")
                ?.scrollIntoView(ScrollIntoViewOptions(block = ScrollLogicalPosition.NEAREST))
        }
    }

    // Clicking anywhere outside closes it, like any menu.
    DisposableEffect(open) {
        val listener: (Event) -> Unit = { event ->
            val target = event.target as? Node
            if (open && target != null && root?.contains(target) == false) open = false
        }
        document.addEventListener("pointerdown", listener)
        onDispose { document.removeEventListener("pointerdown", listener) }
    }

    Span({
        classes("menu")
        ref { element ->
            root = element
            onDispose { root = null }
        }
    }) {
        Button({
            classes("act", "menu-trigger")
            attr("type", "button")
            attr("role", "combobox")
            attr("aria-haspopup", "listbox")
            attr("aria-controls", "$id-list")
            attr("aria-expanded", open.toString())
            attr("aria-label", label)
            if (open) attr("aria-activedescendant", "$id-option-$active")
            onClick { if (open) open = false else openMenu() }
            onKeyDown {
                if (!open) {
                    if (it.key == "ArrowDown" || it.key == "ArrowUp") {
                        it.preventDefault()
                        openMenu()
                    }
                    return@onKeyDown
                }
                when (it.key) {
                    "ArrowDown" -> active = (active + 1).coerceAtMost(options.lastIndex)
                    "ArrowUp" -> active = (active - 1).coerceAtLeast(0)
                    "Home" -> active = 0
                    "End" -> active = options.lastIndex
                    "Enter", " " -> choose(active)
                    "Escape" -> open = false
                    "Tab" -> {
                        open = false
                        return@onKeyDown
                    }
                    else -> return@onKeyDown
                }
                it.preventDefault()
            }
        }) { Text(options[selectedIndex].second) }

        Ul({
            id("$id-list")
            classes("menu-list")
            if (alignStart) classes("start")
            attr("role", "listbox")
            attr("aria-label", label)
            attr("data-open", open.toString())
        }) {
            options.forEachIndexed { index, (_, text) ->
                Li({
                    id("$id-option-$index")
                    attr("role", "option")
                    attr("aria-selected", (index == selectedIndex).toString())
                    if (index == active) classes("active")
                    onMouseMove { active = index }
                    // Keep focus on the trigger when choosing with the mouse.
                    onMouseDown { it.preventDefault() }
                    onClick { choose(index) }
                }) { Text(text) }
            }
        }
    }
}
