package dev.placeholder.web.pages.stats

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
import com.varabyte.kobweb.navigation.UpdateHistoryMode
import dev.placeholder.shared.Languages
import dev.placeholder.shared.LinkStats
import dev.placeholder.shared.Paths
import dev.placeholder.shared.SnipDto
import dev.placeholder.shared.SnipKind
import dev.placeholder.web.Api
import dev.placeholder.web.Navigation
import dev.placeholder.web.Session
import dev.placeholder.web.components.CopyAction
import dev.placeholder.web.components.Load
import dev.placeholder.web.components.LoadedPage
import dev.placeholder.web.components.Shell
import dev.placeholder.web.components.StateScreen
import dev.placeholder.web.components.VisitsChart
import dev.placeholder.web.components.load
import dev.placeholder.web.formatDate
import dev.placeholder.web.formatLines
import dev.placeholder.web.formatPeople
import dev.placeholder.web.formatVisits
import kotlinx.browser.window
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/** A link of yours, with its visits. */
private class Owned(
    val snip: SnipDto,
    val stats: LinkStats,
)

/** How often a link of yours was opened, and by how many people: `/stats/{slug}`. */
@Page("{slug}")
@Composable
fun StatsPage() {
    val ctx = rememberPageContext()
    val slug = ctx.route.params["slug"].orEmpty()
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember(slug) { mutableStateOf<Load<Owned>>(Load.Pending) }
    val me = Session.me

    LaunchedEffect(Session.ready, me, slug, attempt) {
        if (!Session.ready) return@LaunchedEffect
        if (me == null) {
            ctx.router.navigateTo(Paths.signIn(next = Paths.stats(slug)), UpdateHistoryMode.REPLACE)
            return@LaunchedEffect
        }
        state = Load.Pending
        state =
            load {
                coroutineScope {
                    val snip = async { Api.snip(slug) }
                    val stats = async { Api.stats(slug) }
                    val found = snip.await()
                    val counts = stats.await()
                    // Someone else's link answers like a missing one: visits are only for the person who made it.
                    if (found == null || counts == null || !found.owned) null else Owned(found, counts)
                }
            }
    }

    LoadedPage(
        state = state,
        title = "Visits",
        expiredSub = "Its visits went with it.",
        expiredAction = { Anchor(Paths.LINKS, { classes("act") }) { Text("Your links") } },
        failedSub = "Your link and its visits are safe; they just didn't load.",
        onRetry = { attempt++ },
        missing = {
            Shell(title = "Not found") {
                StateScreen(
                    lead = "None of your links lives at /$slug.",
                    sub = "Visits are only shown to the person who made the link.",
                ) {
                    Anchor(Paths.LINKS, { classes("act") }) { Text("Your links") }
                }
            }
        },
    ) { owned -> StatsView(owned.snip, owned.stats) }
}

@Composable
private fun StatsView(
    snip: SnipDto,
    stats: LinkStats,
) {
    val isLink = snip.kind == SnipKind.LINK
    val subject = if (isLink) "redirects to ${snip.content.substringAfter("://")}" else formatLines(snip.lines)
    val lastDays = stats.days.sumOf { it.visits }

    Shell(title = "Visits") {
        Div({ classes("view", "stats") }) {
            if (Navigation.previous == Paths.LINKS) {
                Div({ classes("backline") }) {
                    Button({
                        classes("act")
                        attr("type", "button")
                        onClick { window.history.back() }
                    }) { Text("Back to your links") }
                }
            }
            Div({ classes("snip-bar") }) {
                Span({ classes("kind", "stats-subject") }) {
                    B { Text(if (isLink) "Link" else Languages.name(snip.language)) }
                    Text(", $subject")
                }
                Div({ classes("actions", "push") }) {
                    CopyAction("${window.location.origin}${Paths.snip(snip.slug)}")
                    // A short link opens through the server, which redirects; a paste opens in the app.
                    if (isLink) {
                        A(Paths.snip(snip.slug), { classes("act") }) { Text("Open") }
                    } else {
                        Anchor(Paths.snip(snip.slug), { classes("act") }) { Text("Open") }
                    }
                }
            }

            if (stats.visits == 0L) {
                H1({ classes("statement") }) { Text("No visits yet.") }
                P({ classes("lede") }) {
                    Text("Share the link: each visit shows up here a few seconds after someone opens it.")
                }
            } else {
                H1({ classes("statement") }) { Text(headline(lastDays, stats)) }
                P({ classes("lede") }) {
                    Text("${formatVisits(stats.visits)} in all, since ${formatDate(snip.createdAt.toDouble())}.")
                }
                VisitsChart(stats.days)
            }

            P({ classes("hint", "stats-note") }) {
                Text(
                    "Link previews and other bots aren't counted. People are estimated, to within about 2%, " +
                        "and each day runs midnight to midnight UTC.",
                )
            }
        }
    }
}

/** The last month in one sentence. Older visits are in the line under it, so a quiet month still says so plainly. */
private fun headline(
    lastDays: Long,
    stats: LinkStats,
): String {
    val period = "in the last ${stats.days.size} days"
    if (lastDays == 0L) return "No visits $period."
    return "${formatVisits(lastDays)} from ${formatPeople(stats.visitors)} $period."
}
