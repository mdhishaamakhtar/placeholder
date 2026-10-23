package dev.placeholder.web.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.placeholder.web.components.HtmlCode
import dev.placeholder.web.highlightHtml
import dev.placeholder.web.isSubmitShortcut
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Pre
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea
import org.w3c.dom.HTMLElement

/** Very large pastes skip live highlighting so typing never lags; they're still highlighted once shared. */
private const val LIVE_HIGHLIGHT_LIMIT = 40_000

/**
 * The paste area. A plain textarea keeps native editing, selection, and undo; a highlighted copy of the same text
 * sits exactly behind it (same font, size, wrapping, and scroll position) so code is colored as you type.
 */
@Composable
fun Composer(text: String, language: String?, onText: (String) -> Unit, onSubmit: () -> Unit) {
    val highlighted = remember(text, language) {
        if (text.length > LIVE_HIGHLIGHT_LIMIT) null else highlightHtml(text, language)
    }
    var overlay by remember { mutableStateOf<HTMLElement?>(null) }

    Div({ classes("stage") }) {
        Div({
            classes("ghost")
            attr("data-hidden", text.isNotEmpty().toString())
        }) {
            Span({ classes("hint") }) { Text("Paste a link, code, or text.") }
        }
        if (highlighted != null) {
            Pre({
                classes("composer-highlight")
                attr("aria-hidden", "true")
                ref { element ->
                    overlay = element
                    onDispose { overlay = null }
                }
            }) {
                // A trailing newline keeps the last line's height in step with the textarea's.
                HtmlCode(highlighted + "\n")
            }
        }
        TextArea(value = text) {
            classes("composer")
            attr("data-highlighted", (highlighted != null).toString())
            attr("spellcheck", "false")
            attr("autocapitalize", "off")
            attr("autocomplete", "off")
            attr("aria-label", "Paste a link, code, or text")
            onInput { onText(it.value) }
            onScroll {
                val area = it.target as HTMLElement
                overlay?.let { pre ->
                    pre.scrollTop = area.scrollTop
                    pre.scrollLeft = area.scrollLeft
                }
            }
            onKeyDown {
                if (isSubmitShortcut(it.key, it.metaKey, it.ctrlKey)) {
                    it.preventDefault()
                    onSubmit()
                }
            }
            ref { element ->
                element.focus()
                onDispose { }
            }
        }
    }
}
