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
import dev.placeholder.shared.Languages
import dev.placeholder.shared.Paths
import dev.placeholder.shared.SnipKind
import dev.placeholder.shared.SnipSummary
import dev.placeholder.web.Api
import dev.placeholder.web.ApiException
import dev.placeholder.web.Session
import dev.placeholder.web.components.Loading
import dev.placeholder.web.components.RowTarget
import dev.placeholder.web.components.Shell
import dev.placeholder.web.components.SnipRow
import dev.placeholder.web.components.StateScreen
import dev.placeholder.web.formatDate
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import kotlin.js.Date

@Page
@Composable
fun LinksPage() {
    val ctx = rememberPageContext()
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<SnipSummary>?>(null) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val me = Session.me

    LaunchedEffect(Session.ready, me) {
        if (!Session.ready) return@LaunchedEffect
        if (me == null) {
            ctx.router.navigateTo(Paths.signIn(next = Paths.LINKS), UpdateHistoryMode.REPLACE)
            return@LaunchedEffect
        }
        try {
            val page = Api.mySnips(null)
            items = page.items
            cursor = page.nextCursor
        } catch (e: ApiException) {
            error = e.message
        }
    }

    fun loadMore() {
        val from = cursor ?: return
        loadingMore = true
        scope.launch {
            try {
                val page = Api.mySnips(from)
                items = items.orEmpty() + page.items
                cursor = page.nextCursor
            } catch (e: ApiException) {
                error = e.message
            } finally {
                loadingMore = false
            }
        }
    }

    Shell(title = "Your links") {
        val list = items
        when {
            list == null && error != null -> {
                StateScreen(error.orEmpty())
            }

            list == null -> {
                Loading()
            }

            list.isEmpty() -> {
                StateScreen(
                    lead = "You haven't made any links yet.",
                    sub = "Links you make while signed in show up here, on every device.",
                ) {
                    Anchor(Paths.NEW, { classes("act") }) { Text("Make one") }
                }
            }

            else -> {
                Div({ classes("link-list") }) {
                    Ul({ classes("snip-rows") }) {
                        list.forEach { item -> LinkRow(item) }
                    }
                    if (cursor != null) {
                        Div({ classes("more") }) {
                            Button({
                                classes("act")
                                attr("type", "button")
                                if (loadingMore) disabled()
                                onClick { loadMore() }
                            }) { Text(if (loadingMore) "Loading" else "Show older links") }
                        }
                    }
                }
            }
        }
        Div({ classes("foot") }) {
            Span({ classes("kind") }) { Text(me?.let { "Signed in as ${it.email}" } ?: "") }
            Button({
                classes("act", "push")
                attr("type", "button")
                onClick {
                    scope.launch {
                        Session.signOut()
                        ctx.router.navigateTo(Paths.HOME)
                    }
                }
            }) { Text("Sign out") }
        }
    }
}

@Composable
private fun LinkRow(item: SnipSummary) {
    val kind = if (item.kind == SnipKind.LINK) "Link" else Languages.name(item.language)
    val expiresAt = item.expiresAt
    val expired = expiresAt != null && expiresAt <= Date.now()
    val path = Paths.snip(item.slug)
    // Short links go through the server so the redirect happens there; everything else stays in the app.
    val target = if (item.kind == SnipKind.LINK) RowTarget.Server(path) else RowTarget.InApp(path)
    SnipRow(
        kind = kind,
        preview = item.preview,
        language = item.language.takeIf { item.kind == SnipKind.CODE },
        slug = item.slug,
        target = target,
        visits = item.visits,
        visitsPath = Paths.stats(item.slug),
        detail =
            when {
                expired -> "Expired"
                expiresAt != null -> "Until ${formatDate(expiresAt.toDouble())}"
                else -> formatDate(item.createdAt.toDouble())
            },
        dimmed = expired,
    )
}
