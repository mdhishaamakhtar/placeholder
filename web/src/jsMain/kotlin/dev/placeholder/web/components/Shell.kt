package dev.placeholder.web.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.varabyte.kobweb.core.rememberPageContext
import com.varabyte.kobweb.navigation.Anchor
import dev.placeholder.shared.Paths
import dev.placeholder.shared.Theme
import dev.placeholder.web.Navigation
import dev.placeholder.web.Session
import dev.placeholder.web.ThemeSwitch
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Header
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.dom.Nav
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.time.Duration.Companion.milliseconds

/** The frame every page shares: wordmark top-left, two quiet links top-right, the page below. */
@Composable
fun Shell(title: String? = null, content: @Composable () -> Unit) {
    val path = rememberPageContext().route.path
    LaunchedEffect(title) { document.title = if (title == null) "placeholder" else "$title · placeholder" }
    LaunchedEffect(path) { Navigation.visited(path) }

    Div({ classes("frame") }) {
        Header({ classes("bar") }) {
            Anchor(Paths.HOME, { classes("mark") }) { Text("placeholder") }
            Nav({ classes("nav") }) {
                // Wait for the session check so the nav doesn't flicker between signed-out and signed-in.
                if (Session.ready) {
                    if (Session.me != null) {
                        NavLink(Paths.NEW, "New", path)
                        NavLink(Paths.LINKS, "Your links", path)
                    } else {
                        NavLink(Paths.SIGN_IN, "Sign in", path)
                    }
                }
                ThemeToggle()
            }
        }
        Main({ classes("page") }) { content() }
    }
}

/** Names the theme you'd switch to, like a light switch labelled with what it does. */
@Composable
private fun ThemeToggle() {
    var theme by remember { mutableStateOf(ThemeSwitch.current()) }
    Button({
        classes("theme-toggle")
        attr("type", "button")
        attr("aria-label", "Switch to ${theme.other.attribute} theme")
        onClick {
            theme = theme.other
            ThemeSwitch.apply(theme)
        }
    }) { Text(if (theme == Theme.DARK) "Light" else "Dark") }
}

@Composable
private fun NavLink(href: String, label: String, current: String) {
    Anchor(href, { if (current == href) attr("aria-current", "page") }) { Text(label) }
}

/** A text action whose label crossfades to a confirmation, then back. */
@Composable
fun CopyAction(value: String, label: String = "Copy link", done: String = "Copied") {
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    Button({
        classes("act")
        attr("type", "button")
        onClick {
            scope.launch {
                try {
                    window.navigator.clipboard.writeText(value).await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // The browser refused (no permission, or the page isn't focused): there's nothing to confirm.
                    return@launch
                }
                copied = true
                delay(CONFIRMATION_SHOWN)
                copied = false
            }
        }
    }) {
        Span({
            classes("swap")
            attr("data-on", copied.toString())
        }) {
            Span({ attr("aria-hidden", copied.toString()) }) { Text(label) }
            Span({
                attr("aria-hidden", (!copied).toString())
                attr("aria-live", "polite")
            }) { Text(done) }
        }
    }
}

/** How long "Copied" stays before the action's label returns: long enough to read, short enough to copy again. */
private val CONFIRMATION_SHOWN = 1400.milliseconds
