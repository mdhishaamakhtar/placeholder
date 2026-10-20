package dev.placeholder.web.pages

import androidx.compose.runtime.Composable
import com.varabyte.kobweb.core.Page
import com.varabyte.kobweb.navigation.Anchor
import dev.placeholder.shared.Rules
import dev.placeholder.web.Session
import dev.placeholder.web.components.RowTarget
import dev.placeholder.web.components.SnipRow
import dev.placeholder.web.components.Shell
import org.w3c.dom.HTMLElement
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Footer
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.TagElement
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul

/** Illustrative examples for the landing page. Not real links. */
private data class Example(val kind: String, val source: String, val language: String?, val slug: String)

private val EXAMPLES = listOf(
    Example("Link", "https://github.com/hishaam/placeholder/pull/42", null, "aX7kq2B"),
    Example("SQL", "select name, count(*) from orders group by 1;", "sql", "M3pZ8Lk"),
    Example("JSON", """{ "service": "billing", "retries": 3 }""", "json", "R9tA2cd"),
    Example("Text", "Notes from Tuesday: ship the beta Friday", null, "Hq4mN7e"),
)

@Page
@Composable
fun LandingPage() {
    val signedIn = Session.me != null
    val start = if (signedIn) "/new" else signinPath(create = true, next = "/new")

    Shell {
        Div({ classes("landing") }) {
            Section({ classes("hero") }) {
                Div({ classes("hero-copy") }) {
                    H1({ classes("statement") }) { Text("Short links for links, code, and text.") }
                    P({ classes("lede") }) {
                        Text(
                            "Paste a URL and it redirects. Paste code and it's highlighted and tidied, " +
                                "readable on any screen. Every link stays yours to edit, expire, and find again.",
                        )
                    }
                    Div({ classes("cta") }) {
                        Anchor(start, { classes("button") }) { Text(if (signedIn) "New link" else "Get started") }
                        if (signedIn) {
                            Anchor("/links", { classes("act") }) { Text("Your links") }
                        } else if (Session.ready) {
                            Anchor(signinPath(next = "/new"), { classes("act") }) { Text("Sign in") }
                        }
                    }
                }
                Ul({ classes("snip-rows", "rise-in"); attr("aria-label", "Examples of what placeholder makes") }) {
                    EXAMPLES.forEachIndexed { index, example ->
                        SnipRow(example.kind, example.source, example.language, example.slug, RowTarget.None, index = index)
                    }
                }
            }

            TagElement<HTMLElement>("dl", { classes("features") }) {
                Feature("Links", "A short link that redirects instantly. Long URLs from docs, PRs, and dashboards become seven characters.")
                Feature("Code", "Language detected as you paste, colored like your editor, and one click to prettify JSON, SQL, HTML, and XML.")
                Feature("Text", "Notes, logs, and Markdown, set in clean type with nothing ever run or rendered.")
                Feature("Yours", "Every link you make is saved to your account. Edit it later, or set it to expire.")
                Feature(
                    "Shared",
                    "Anyone with a link can open it, so keep passwords and keys out of your pastes. " +
                        "Each paste can be up to ${Rules.MAX_CONTENT_BYTES / 1024} KB.",
                )
            }
        }
        Footer({ classes("site-foot") }) {
            Span { Text("placeholder") }
            Span { Text("Written in Kotlin") }
        }
    }
}

@Composable
private fun Feature(term: String, description: String) {
    Div({ classes("feature") }) {
        TagElement<HTMLElement>("dt", null) { Text(term) }
        TagElement<HTMLElement>("dd", null) { Text(description) }
    }
}
