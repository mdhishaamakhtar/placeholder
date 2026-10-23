package dev.placeholder.web.components

import androidx.compose.runtime.Composable
import com.varabyte.kobweb.navigation.Anchor
import dev.placeholder.web.formatVisits
import dev.placeholder.web.highlightHtml
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Code
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/** Where a row leads: nowhere (an illustration), in-app, or through the server (short links redirect there). */
sealed interface RowTarget {
    data object None : RowTarget

    data class InApp(
        val path: String,
    ) : RowTarget

    data class Server(
        val path: String,
    ) : RowTarget
}

/**
 * One row in a list of snips: what kind it is, the start of its content (colored when it's code), optionally how
 * often it was opened and a quiet detail like a date, and the short link it lives at. The landing page and
 * "Your links" both use this, so the two lists always read the same. Put rows inside a `Ul` with class
 * `snip-rows`, which lines the columns up across rows.
 */
@Composable
fun SnipRow(
    kind: String,
    preview: String,
    language: String?,
    slug: String,
    target: RowTarget,
    visits: Long? = null,
    detail: String? = null,
    dimmed: Boolean = false,
    index: Int = 0,
) {
    Li({
        style { property("--i", index) }
        if (dimmed) classes("dimmed")
    }) {
        Span({ classes("row-kind") }) { Text(kind) }
        Code({ classes("row-src") }) {
            val html = language?.let { highlightHtml(preview, it) }
            if (html == null) Text(preview) else HtmlCode(html)
        }
        val visitsLabel = visits?.let { if (it == 0L) "No visits" else formatVisits(it) }
        if (visitsLabel != null) Span({ classes("row-visits") }) { Text(visitsLabel) }
        if (detail != null) Span({ classes("row-detail") }) { Text(detail) }
        Span({ classes("row-to") }) {
            Span({ classes("row-host") }) { Text("${window.location.host}/") }
            when (target) {
                RowTarget.None -> Text(slug)

                // The link covers the whole row (see .row-link in styles.css), not just the slug.
                is RowTarget.InApp -> Anchor(target.path, { classes("row-link") }) { Text(slug) }

                is RowTarget.Server -> A(target.path, { classes("row-link") }) { Text(slug) }
            }
        }
    }
}
