package dev.placeholder.web.editor

import androidx.compose.runtime.Composable
import com.varabyte.kobweb.navigation.Anchor
import dev.placeholder.shared.Languages
import dev.placeholder.shared.Paths
import dev.placeholder.shared.Rules
import dev.placeholder.web.components.ChoiceMenu
import dev.placeholder.web.submitShortcut
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

private val EXPIRY_NAMES = mapOf(
    null to "forever",
    60L * 60 to "for 1 hour",
    24L * 60 * 60 to "for 1 day",
    7L * 24 * 60 * 60 to "for 1 week",
    30L * 24 * 60 * 60 to "for 30 days",
)

/**
 * The row under the editor. Left: what the paste is (a menu, so a wrong guess is one click to fix), whether it's a
 * copy or an edit, and Prettify. Right: how long to keep it, then the button that saves it.
 */
@Composable
fun EditorFoot(state: EditorState, onPrettify: (String) -> Unit, onSave: () -> Unit) {
    val detection = state.detection
    val mode = state.mode
    val prettifyAs = detection?.prettifyAs(state.text)

    Div({ classes("foot") }) {
        Span({
            classes("kind")
            attr("aria-live", "polite")
        }) {
            when {
                detection == null -> Text("Nothing yet")
                detection.isLink -> {
                    B { Text("Link") }
                    Text(", will redirect")
                }
                else -> {
                    ChoiceMenu(
                        label = "Language",
                        options = Languages.CHOICES.map { it to Languages.name(it) },
                        value = detection.language,
                        onChange = state::pickLanguage,
                        id = "language",
                        alignStart = true,
                    )
                    Text(", ${state.linesLabel()}")
                }
            }
        }

        when (mode) {
            is EditorMode.CopyOf -> SourceNote("Copy of", mode.slug)
            is EditorMode.Editing -> SourceNote("Editing", mode.slug)
            EditorMode.Fresh -> Unit
        }

        if (prettifyAs != null) {
            Button({
                classes("act")
                attr("type", "button")
                if (state.busy != Busy.NONE) disabled()
                onClick { onPrettify(prettifyAs) }
            }) { Text(if (state.busy == Busy.PRETTIFYING) "Prettifying" else "Prettify") }
        }

        Span({ classes("save", "push") }) {
            if (mode !is EditorMode.Editing) {
                Span({ classes("expiry") }) {
                    Text("Keep")
                    ChoiceMenu(
                        label = "How long to keep this link",
                        options = Rules.EXPIRY_OPTIONS.map { it to EXPIRY_NAMES.getValue(it) },
                        value = state.expiresIn,
                        onChange = { state.expiresIn = it },
                        id = "expiry",
                    )
                }
            }
            SaveButton(state, editing = mode is EditorMode.Editing, onSave = onSave)
        }

        val message = state.error
            ?: "That's over ${Rules.MAX_CONTENT_BYTES / 1024} KB. Trim it down to save it.".takeIf { state.tooBig }
        if (message != null) {
            Div({
                classes("notice")
                attr("role", "alert")
            }) { Text(message) }
        }
    }
}

@Composable
private fun SourceNote(label: String, slug: String) {
    Span({ classes("copy-of") }) {
        Text("$label ")
        Anchor(Paths.snip(slug), { classes("quiet-link") }) { Text(slug) }
    }
}

@Composable
private fun SaveButton(state: EditorState, editing: Boolean, onSave: () -> Unit) {
    val saving = state.busy == Busy.SAVING
    Button({
        classes("button")
        attr("type", "button")
        attr("data-busy", saving.toString())
        attr("aria-keyshortcuts", submitShortcut.aria)
        if (state.detection == null || state.tooBig) disabled()
        onClick { onSave() }
    }) {
        Text(
            when {
                saving && editing -> "Saving"
                saving -> "Creating"
                editing -> "Save changes"
                else -> "Create link"
            },
        )
        Span({
            classes("kbd")
            attr("aria-hidden", "true")
        }) { Text(submitShortcut.label) }
    }
}
