package dev.placeholder.web.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.varabyte.kobweb.core.Page
import com.varabyte.kobweb.core.rememberPageContext
import com.varabyte.kobweb.navigation.Anchor
import com.varabyte.kobweb.navigation.UpdateHistoryMode
import dev.placeholder.shared.CreateSnipResponse
import dev.placeholder.shared.Rules
import dev.placeholder.shared.SnipKind
import dev.placeholder.web.Api
import dev.placeholder.web.ApiException
import dev.placeholder.web.Detection
import dev.placeholder.web.LANGUAGE_CHOICES
import dev.placeholder.web.Session
import dev.placeholder.web.components.ChoiceMenu
import dev.placeholder.web.components.CopyAction
import dev.placeholder.web.components.HtmlCode
import dev.placeholder.web.components.Loading
import dev.placeholder.web.components.Shell
import dev.placeholder.web.detect
import dev.placeholder.web.formatLines
import dev.placeholder.web.highlightHtml
import dev.placeholder.web.hostPrefix
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Pre
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea
import org.w3c.dom.HTMLElement

private enum class Busy { NONE, SAVING, PRETTIFYING }

/** The language someone picked by hand. `null` inside means they chose plain text. */
private data class ChosenLanguage(val language: String?)

/** What the editor is doing: making a fresh link, a copy of another one, or changing one you own. */
private sealed interface Mode {
    data object Fresh : Mode
    data class CopyOf(val slug: String) : Mode
    data class Editing(val slug: String) : Mode
}

private val EXPIRY_LABELS = mapOf(
    null to "forever",
    60L * 60 to "for 1 hour",
    24L * 60 * 60 to "for 1 day",
    7L * 24 * 60 * 60 to "for 1 week",
    30L * 24 * 60 * 60 to "for 30 days",
)

/** The editor. Making links needs an account, so signed-out visitors are sent to sign in and brought back here. */
@Page
@Composable
fun NewPage() {
    val ctx = rememberPageContext()
    val from = ctx.route.params["from"]?.takeIf(Rules::isSlug)
    val edit = ctx.route.params["edit"]?.takeIf(Rules::isSlug)

    LaunchedEffect(Session.ready, Session.me) {
        if (Session.ready && Session.me == null) {
            val back = when {
                edit != null -> "/new?edit=$edit"
                from != null -> "/new?from=$from"
                else -> "/new"
            }
            ctx.router.navigateTo(signinPath(next = back), UpdateHistoryMode.REPLACE)
        }
    }

    if (!Session.ready || Session.me == null) {
        Shell(title = "New link") { Loading() }
        return
    }
    Editor(from, edit)
}

@Composable
private fun Editor(from: String?, edit: String?) {
    val ctx = rememberPageContext()
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var detected by remember { mutableStateOf<Detection?>(null) }
    // Set when the person corrects the detected language; cleared when they clear the editor.
    var chosenLanguage by remember { mutableStateOf<ChosenLanguage?>(null) }
    var busy by remember { mutableStateOf(Busy.NONE) }
    var error by remember { mutableStateOf<String?>(null) }
    var created by remember { mutableStateOf<CreateSnipResponse?>(null) }
    var createdLabel by remember { mutableStateOf("") }
    var expiresIn by remember { mutableStateOf<Long?>(null) }
    var mode by remember { mutableStateOf<Mode>(Mode.Fresh) }

    // Start from an existing snip: your own to change it in place, anyone's to save a copy as a new link.
    LaunchedEffect(from, edit) {
        val source = edit ?: from ?: return@LaunchedEffect
        try {
            val snip = Api.snip(source)
            when {
                snip == null -> error = "There's nothing at /$source to start from."
                snip.kind == SnipKind.LINK -> error = "/$source is a short link, so there's no content to edit."
                else -> {
                    text = snip.content
                    mode = if (edit != null && snip.owned) Mode.Editing(source) else Mode.CopyOf(source)
                    if (edit != null && !snip.owned) error = "Only the person who made /$source can change it, so this will be a copy."
                }
            }
        } catch (e: ApiException) {
            error = e.message
        }
    }

    // Detection runs a beat after typing stops, so a long paste doesn't stall every keystroke.
    LaunchedEffect(text) {
        if (text.isBlank()) {
            detected = null
            chosenLanguage = null
            return@LaunchedEffect
        }
        delay(if (text.length > 4000) 220 else 90)
        detected = detect(text)
    }
    // What the paste is treated as: the detector's guess, unless the person picked something else.
    val detection = detected?.let { d ->
        val chosen = chosenLanguage
        if (d.isLink || chosen == null) d else Detection(chosen.language, isLink = false)
    }

    fun save() {
        val current = detection ?: return
        if (busy != Busy.NONE) return
        val language = if (current.isLink) null else current.language
        busy = Busy.SAVING
        error = null
        scope.launch {
            try {
                when (val m = mode) {
                    is Mode.Editing -> {
                        Api.update(m.slug, text, language)
                        ctx.router.navigateTo("/${m.slug}")
                    }
                    else -> {
                        createdLabel = kindLabel(current, text)
                        created = Api.create(text, language, expiresIn)
                    }
                }
            } catch (e: ApiException) {
                error = e.message
            } finally {
                busy = Busy.NONE
            }
        }
    }

    fun prettify(language: String) {
        if (busy != Busy.NONE) return
        busy = Busy.PRETTIFYING
        error = null
        scope.launch {
            try {
                val result = Api.prettify(text, language)
                if (result.changed) text = result.content
            } catch (e: ApiException) {
                error = e.message
            } finally {
                busy = Busy.NONE
            }
        }
    }

    Shell(title = if (mode is Mode.Editing) "Editing" else "New link") {
        val done = created
        if (done != null) {
            CreatedView(done, createdLabel) {
                created = null
                text = ""
                error = null
                mode = Mode.Fresh
                if (from != null || edit != null) ctx.router.navigateTo("/new", UpdateHistoryMode.REPLACE)
            }
            return@Shell
        }

        Composer(
            text = text,
            language = detection?.takeUnless { it.isLink }?.language,
            onText = {
                text = it
                error = null
            },
            onSubmit = ::save,
        )

        val prettifyAs = detection?.prettifyAs(text)
        val tooBig = text.length > Rules.MAX_CONTENT_BYTES
        val m = mode
        Div({ classes("foot") }) {
            Span({ classes("kind"); attr("aria-live", "polite") }) {
                val d = detection
                when {
                    d == null -> Text("Nothing yet")
                    d.isLink -> {
                        B { Text("Link") }
                        Text(", will redirect")
                    }
                    else -> {
                        // The guess is a menu, so a wrong one (prose read as Rust, say) is one click to fix.
                        ChoiceMenu(
                            label = "Language",
                            options = LANGUAGE_CHOICES,
                            value = d.language,
                            onChange = { chosenLanguage = ChosenLanguage(it) },
                            id = "language",
                            alignStart = true,
                        )
                        Text(", ${formatLines(Rules.lineCount(text.trimEnd()))}")
                    }
                }
            }
            when (m) {
                is Mode.CopyOf -> Span({ classes("copy-of") }) {
                    Text("Copy of ")
                    Anchor("/${m.slug}", { classes("quiet-link") }) { Text(m.slug) }
                }
                is Mode.Editing -> Span({ classes("copy-of") }) {
                    Text("Editing ")
                    Anchor("/${m.slug}", { classes("quiet-link") }) { Text(m.slug) }
                }
                Mode.Fresh -> Unit
            }
            if (prettifyAs != null) {
                Button({
                    classes("act")
                    attr("type", "button")
                    if (busy != Busy.NONE) disabled()
                    onClick { prettify(prettifyAs) }
                }) { Text(if (busy == Busy.PRETTIFYING) "Prettifying" else "Prettify") }
            }
            // Everything about saving sits together on the right: how long to keep it, then the button.
            Span({ classes("save", "push") }) {
                if (m !is Mode.Editing) ExpiryPicker(expiresIn) { expiresIn = it }
                Button({
                    classes("button")
                    attr("type", "button")
                    attr("data-busy", (busy == Busy.SAVING).toString())
                    attr("aria-keyshortcuts", if (isMac()) "Meta+Enter" else "Control+Enter")
                    if (detection == null || tooBig) disabled()
                    onClick { save() }
                }) {
                    Text(
                        when {
                            busy == Busy.SAVING && m is Mode.Editing -> "Saving"
                            busy == Busy.SAVING -> "Creating"
                            m is Mode.Editing -> "Save changes"
                            else -> "Create link"
                        },
                    )
                    Span({ classes("kbd"); attr("aria-hidden", "true") }) { Text(if (isMac()) "⌘ Enter" else "Ctrl Enter") }
                }
            }

            val message = error ?: if (tooBig) "That's over ${Rules.MAX_CONTENT_BYTES / 1024} KB. Trim it down to save it." else null
            if (message != null) Div({ classes("notice"); attr("role", "alert") }) { Text(message) }
        }
    }
}

@Composable
private fun ExpiryPicker(value: Long?, onChange: (Long?) -> Unit) {
    Span({ classes("expiry") }) {
        Text("Keep")
        ChoiceMenu(
            label = "How long to keep this link",
            options = Rules.EXPIRY_OPTIONS.map { it to EXPIRY_LABELS.getValue(it) },
            value = value,
            onChange = onChange,
            id = "expiry",
        )
    }
}

@Composable
private fun Composer(text: String, language: String?, onText: (String) -> Unit, onSubmit: () -> Unit) {
    // Very large pastes skip live highlighting so typing never lags; they're still highlighted once shared.
    val highlighted = remember(text, language) {
        if (text.length > LIVE_HIGHLIGHT_LIMIT) null else highlightHtml(text, language)
    }
    var overlay by remember { mutableStateOf<HTMLElement?>(null) }

    Div({ classes("stage") }) {
        Div({ classes("ghost"); attr("data-hidden", text.isNotEmpty().toString()) }) {
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
                // A trailing newline keeps the last line's height in step with the textarea.
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
                if (it.key == "Enter" && (it.metaKey || it.ctrlKey)) {
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

private const val LIVE_HIGHLIGHT_LIMIT = 40_000

@Composable
private fun CreatedView(created: CreateSnipResponse, label: String, onNew: () -> Unit) {
    Div({ classes("created") }) {
        Div({ classes("host") }) { Text(hostPrefix(created.url)) }
        Div({ classes("giant", "rise"); attr("aria-label", created.url); attr("role", "text") }) {
            created.slug.forEachIndexed { index, char ->
                Span({
                    attr("aria-hidden", "true")
                    style { property("--i", index) }
                }) { Text(char.toString()) }
            }
        }
    }
    Div({ classes("foot") }) {
        Span({ classes("kind") }) { Text(label) }
        CopyAction(created.url)
        A(created.url, { classes("act"); attr("target", "_blank"); attr("rel", "noopener") }) {
            Text(if (created.kind == SnipKind.LINK) "Test the link" else "Open")
        }
        Anchor("/links", { classes("act") }) { Text("Your links") }
        Button({
            classes("act", "push")
            attr("type", "button")
            onClick { onNew() }
        }) { Text("New") }
    }
}

private fun kindLabel(detection: Detection, text: String): String =
    if (detection.isLink) "Link, redirects to ${text.trim().substringAfter("://").take(48)}"
    else "${detection.label}, ${formatLines(Rules.lineCount(text.trimEnd()))}"

private fun isMac(): Boolean = window.navigator.platform.contains("Mac", ignoreCase = true)
