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
import com.varabyte.kobweb.navigation.UpdateHistoryMode
import dev.placeholder.shared.Paths
import dev.placeholder.shared.Rules
import dev.placeholder.web.Session
import dev.placeholder.web.components.Loading
import dev.placeholder.web.components.Shell
import dev.placeholder.web.editor.Composer
import dev.placeholder.web.editor.CreatedLink
import dev.placeholder.web.editor.EditorFoot
import dev.placeholder.web.editor.EditorMode
import dev.placeholder.web.editor.EditorState
import dev.placeholder.web.editor.Saved
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The editor, at /new. `?from=<slug>` starts from a copy of any link; `?edit=<slug>` edits one you own.
 * Making links needs an account, so signed-out visitors are sent to sign in and brought straight back.
 */
@Page
@Composable
fun NewPage() {
    val ctx = rememberPageContext()
    val from = ctx.route.params["from"]?.takeIf(Rules::isSlug)
    val edit = ctx.route.params["edit"]?.takeIf(Rules::isSlug)

    LaunchedEffect(Session.ready, Session.me) {
        if (Session.ready && Session.me == null) {
            val back = edit?.let(Paths::edit) ?: from?.let(Paths::copyOf) ?: Paths.NEW
            ctx.router.navigateTo(Paths.signIn(next = back), UpdateHistoryMode.REPLACE)
        }
    }

    if (!Session.ready || Session.me == null) {
        Shell(title = "New link") { Loading() }
        return
    }
    Editor(from = from, edit = edit)
}

@Composable
private fun Editor(from: String?, edit: String?) {
    val ctx = rememberPageContext()
    val scope = rememberCoroutineScope()
    val state = remember { EditorState() }
    var created by remember { mutableStateOf<Saved.Created?>(null) }

    LaunchedEffect(from, edit) {
        val source = edit ?: from ?: return@LaunchedEffect
        state.startFrom(source, wantsToEdit = edit != null)
    }

    // Detect a beat after typing stops, so a long paste doesn't stall every keystroke.
    LaunchedEffect(state.text) {
        delay(if (state.text.length > 4000) 220 else 90)
        state.redetect()
    }

    fun save() {
        scope.launch {
            when (val result = state.save()) {
                is Saved.Created -> created = result
                is Saved.Updated -> ctx.router.navigateTo(Paths.snip(result.slug))
                null -> Unit
            }
        }
    }

    Shell(title = if (state.mode is EditorMode.Editing) "Editing" else "New link") {
        val done = created
        if (done != null) {
            CreatedLink(done.link, done.summary) {
                created = null
                state.reset()
                if (from != null || edit != null) ctx.router.navigateTo(Paths.NEW, UpdateHistoryMode.REPLACE)
            }
            return@Shell
        }

        Composer(
            text = state.text,
            language = state.detection?.takeUnless { it.isLink }?.language,
            onText = state::edit,
            onSubmit = ::save,
        )
        EditorFoot(
            state = state,
            onPrettify = { language -> scope.launch { state.prettify(language) } },
            onSave = ::save,
        )
    }
}
