package dev.placeholder.web.editor

import androidx.compose.runtime.Composable
import com.varabyte.kobweb.navigation.Anchor
import dev.placeholder.shared.CreateSnipResponse
import dev.placeholder.shared.Paths
import dev.placeholder.shared.SnipKind
import dev.placeholder.web.components.CopyAction
import dev.placeholder.web.hostPrefix
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/** The moment after saving: the new short code as large as the screen allows, and what to do with it. */
@Composable
fun CreatedLink(
    created: CreateSnipResponse,
    summary: String,
    onNew: () -> Unit,
) {
    Div({ classes("created") }) {
        Div({ classes("host") }) { Text(hostPrefix(created.url)) }
        // Screen readers hear the whole link once; the per-letter spans exist only for the reveal.
        Div({
            classes("giant", "rise")
            attr("aria-label", created.url)
            attr("role", "text")
        }) {
            created.slug.forEachIndexed { index, char ->
                Span({
                    attr("aria-hidden", "true")
                    style { property("--i", index) }
                }) { Text(char.toString()) }
            }
        }
    }
    Div({ classes("foot") }) {
        Span({ classes("kind") }) { Text(summary) }
        Span({ classes("actions") }) {
            CopyAction(created.url)
            A(created.url, {
                classes("act")
                attr("target", "_blank")
                attr("rel", "noopener")
            }) {
                Text(if (created.kind == SnipKind.LINK) "Test the link" else "Open")
            }
            Anchor(Paths.LINKS, { classes("act") }) { Text("Your links") }
        }
        Button({
            classes("act", "push")
            attr("type", "button")
            onClick { onNew() }
        }) { Text("New") }
    }
}
