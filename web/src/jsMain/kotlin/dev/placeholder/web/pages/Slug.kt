package dev.placeholder.web.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.varabyte.kobweb.core.Page
import com.varabyte.kobweb.core.rememberPageContext
import com.varabyte.kobweb.navigation.Anchor
import dev.placeholder.shared.SnipDto
import dev.placeholder.shared.Languages
import dev.placeholder.shared.Paths
import dev.placeholder.shared.SnipKind
import dev.placeholder.web.Api
import dev.placeholder.web.ApiException
import dev.placeholder.web.escapeHtml
import dev.placeholder.web.Navigation
import dev.placeholder.web.components.CopyAction
import dev.placeholder.web.components.HtmlCode
import dev.placeholder.web.components.Loading
import dev.placeholder.web.components.Shell
import dev.placeholder.web.components.StateScreen
import dev.placeholder.web.formatDate
import dev.placeholder.web.formatLines
import dev.placeholder.web.highlightHtml
import dev.placeholder.web.highlightLines
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Code
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Pre
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

private sealed interface Load {
    data object Pending : Load
    data object Missing : Load
    data object Expired : Load
    data class Failed(val message: String) : Load
    data class Ready(val snip: SnipDto) : Load
}

@Page("{slug}")
@Composable
fun SnipPage() {
    val slug = rememberPageContext().route.params["slug"].orEmpty()
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember(slug) { mutableStateOf<Load>(Load.Pending) }

    LaunchedEffect(slug, attempt) {
        state = Load.Pending
        state = try {
            Api.snip(slug)?.let { Load.Ready(it) } ?: Load.Missing
        } catch (e: ApiException) {
            if (e.status == 410) Load.Expired else Load.Failed(e.message ?: "Something went wrong.")
        }
    }

    when (val current = state) {
        Load.Pending -> Shell { Loading() }
        Load.Missing -> MissingScreen("/$slug")
        Load.Expired -> Shell(title = "Expired") {
            StateScreen(
                lead = "This link has expired.",
                sub = "Whoever made it chose for it to stop working after a while.",
            ) {
                Anchor(Paths.HOME, { classes("act") }) { Text("What is placeholder?") }
            }
        }
        is Load.Failed -> Shell(title = "Unavailable") {
            StateScreen(
                lead = current.message,
                sub = "Your link is safe. This is a problem loading it, not with the link itself.",
            ) {
                Button({ classes("act"); attr("type", "button"); onClick { attempt++ } }) { Text("Try again") }
            }
        }
        is Load.Ready -> SnipView(current.snip)
    }
}

@Composable
fun MissingScreen(path: String) {
    Shell(title = "Not found") {
        StateScreen(
            lead = "Nothing lives at $path.",
            sub = "The link may be mistyped, or it was never made.",
        ) {
            Anchor(Paths.HOME, { classes("act") }) { Text("Go to placeholder") }
        }
    }
}

@Composable
private fun SnipView(snip: SnipDto) {
    if (snip.kind == SnipKind.LINK) {
        // Reached through in-app navigation; the server would normally redirect before the page loads.
        LaunchedEffect(snip.slug) { window.location.replace(snip.content) }
        Shell { Loading() }
        return
    }

    val label = Languages.name(snip.language)
    val url = "${window.location.origin}/${snip.slug}"

    val details = buildList {
        add(formatLines(snip.lines))
        add(formatDate(snip.createdAt.toDouble()))
        snip.updatedAt?.let { add("edited ${formatDate(it.toDouble())}") }
        snip.expiresAt?.let { add("expires ${formatDate(it.toDouble())}") }
    }.joinToString(", ")

    Shell(title = label) {
        Div({ classes("view") }) {
            // Came here from your list: offer the way back, through history so the list keeps its place.
            if (Navigation.previous == Paths.LINKS) {
                Div({ classes("backline") }) {
                    Button({
                        classes("act")
                        attr("type", "button")
                        onClick { window.history.back() }
                    }) { Text("Back to your links") }
                }
            }
            // What it is and what you can do with it, pinned above the content so it's never a scroll away.
            Div({ classes("snip-bar") }) {
                Span({ classes("kind") }) {
                    B { Text(label) }
                    Text(", $details")
                }
                Div({ classes("actions", "push") }) {
                    CopyAction(snip.content, label = "Copy", done = "Copied")
                    CopyAction(url)
                    A(Paths.raw(snip.slug), { classes("act") }) { Text("Raw") }
                    if (snip.owned) {
                        Anchor(Paths.edit(snip.slug), { classes("act") }) { Text("Edit") }
                    } else {
                        Anchor(Paths.copyOf(snip.slug), { classes("act") }) { Text("Edit a copy") }
                    }
                }
            }
            if (snip.kind == SnipKind.CODE) CodeSheet(snip) else ProseSheet(snip)
        }
    }
}

@Composable
private fun CodeSheet(snip: SnipDto) {
    // One row per source line: long lines wrap on their own row, and their number stays beside their start.
    val lines = remember(snip.content, snip.language) {
        highlightLines(snip.content, snip.language) ?: snip.content.trimEnd('\n').lines().map(::escapeHtml)
    }
    Pre({ classes("sheet") }) {
        lines.forEachIndexed { index, html ->
            Span({ classes("line") }) {
                Span({ classes("no"); attr("aria-hidden", "true") }) { Text((index + 1).toString()) }
                Code({ classes("src") }) { HtmlCode(html.ifEmpty { " " }) }
            }
        }
    }
}


@Composable
private fun ProseSheet(snip: SnipDto) {
    Pre({ classes("prose") }) {
        HighlightedCode(snip.content, snip.language)
    }
}

@Composable
private fun HighlightedCode(content: String, language: String?) {
    val html = remember(content, language) { highlightHtml(content, language) }
    if (html == null) Code { Text(content) } else HtmlCode(html)
}
