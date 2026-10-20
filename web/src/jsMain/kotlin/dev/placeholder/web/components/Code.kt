package dev.placeholder.web.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import org.jetbrains.compose.web.dom.Code

/** Renders highlight.js output. highlight.js escapes the source before adding its spans, so this is safe as innerHTML. */
@Composable
fun HtmlCode(html: String) {
    key(html) {
        Code({
            ref { element ->
                element.innerHTML = html
                onDispose { }
            }
        })
    }
}
