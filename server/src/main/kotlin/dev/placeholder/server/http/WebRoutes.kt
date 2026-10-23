package dev.placeholder.server.http

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import dev.placeholder.server.analytics.Clicks
import dev.placeholder.server.og.PreviewCards
import dev.placeholder.server.snip.Lookup
import dev.placeholder.server.snip.Snip
import dev.placeholder.server.snip.SnipService
import dev.placeholder.shared.Languages
import dev.placeholder.shared.Messages
import dev.placeholder.shared.Paths
import dev.placeholder.shared.Rules
import dev.placeholder.shared.SnipKind
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.EntityTagVersion
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.origin
import io.ktor.server.request.userAgent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Short links, raw views, preview images, and the web app itself. Regex routes match without the leading slash. */
fun Route.webRoutes(snips: SnipService, clicks: Clicks, webRoot: File?, shell: AppShell, cards: PreviewCards) {
    val slug = "(?<slug>[${Rules.SLUG_ALPHABET}]{${Rules.SLUG_LENGTH}})"
    // Rendering a card takes a few milliseconds; a crawler storm for one popular link shouldn't redo it each time.
    val renderedCards: Cache<String, ByteArray> = Caffeine.newBuilder().maximumSize(2_000).build()

    get("/og.png") {
        call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
        call.respondBytes(cards.site, ContentType.Image.PNG)
    }

    get(Regex("$slug/og.png")) {
        val snip = (snips.lookup(call.parameters["slug"].orEmpty()) as? Lookup.Found)?.snip
        if (snip == null || snip.kind == SnipKind.LINK) {
            call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
            call.respondBytes(cards.site, ContentType.Image.PNG)
            return@get
        }
        val png = renderedCards.getIfPresent("${snip.slug}:${snip.version}")
            ?: withContext(Dispatchers.Default) {
                cards.snip(snip)
            }.also { renderedCards.put("${snip.slug}:${snip.version}", it) }
        // Short-lived even for owned snips: the card shows content, which an owner can edit.
        call.response.header(
            HttpHeaders.CacheControl,
            if (snip.isImmutable) CachePolicy.forSnip(snip) else "public, max-age=300",
        )
        call.respondBytes(png, ContentType.Image.PNG)
    }

    get(Regex("$slug/raw")) {
        when (val found = snips.lookup(call.parameters["slug"].orEmpty())) {
            Lookup.Missing -> call.respondText("${Messages.NOT_FOUND}\n", status = HttpStatusCode.NotFound)
            Lookup.Expired -> call.respondText("${Messages.EXPIRED}\n", status = HttpStatusCode.Gone)
            is Lookup.Found -> {
                call.response.header(HttpHeaders.CacheControl, CachePolicy.forSnip(found.snip))
                call.respondText(found.snip.content, ContentType.Text.Plain.withParameter("charset", "utf-8"))
            }
        }
    }

    get(Regex(slug)) {
        val path = "/${call.parameters["slug"]}"
        when (val found = snips.lookup(call.parameters["slug"].orEmpty())) {
            // The 404 handler in ErrorHandling.kt serves the app, which shows the "nothing here" screen.
            Lookup.Missing -> call.respond(HttpStatusCode.NotFound)
            Lookup.Expired -> shell.respond(
                call,
                HttpStatusCode.Gone,
                PageMeta.page("Expired", path, indexable = false),
            )
            is Lookup.Found -> {
                val snip = found.snip
                clicks.record(snip.slug, call.request.origin.remoteHost, call.request.userAgent())
                if (snip.kind == SnipKind.LINK) {
                    call.response.header(HttpHeaders.CacheControl, CachePolicy.forRedirect(snip))
                    call.respondRedirect(snip.content, permanent = false)
                } else {
                    shell.respond(call, HttpStatusCode.OK, snipMeta(snip))
                }
            }
        }
    }

    // The app's own pages. Any other path falls through to the 404 handler, which still renders the app.
    Paths.PAGES.forEach { path ->
        require(path in PAGE_TITLES) { "No page title for $path" }
        val meta = PageMeta.page(PAGE_TITLES[path], path, indexable = path in INDEXABLE_PAGES)
        get(path) { shell.respond(call, HttpStatusCode.OK, meta) }
    }

    if (webRoot != null) {
        // File names aren't content-hashed, so browsers must revalidate every time or they'd keep stale code after
        // a deploy. ETags (ConditionalHeaders) make an unchanged file a cheap 304.
        staticFiles("/", webRoot) {
            etag { resource -> (resource as File).let { EntityTagVersion("${it.lastModified()}-${it.length()}") } }
            cacheControl { listOf(CacheControl.NoCache(null)) }
        }
    }
}

/** A shared snippet previews as itself: its kind and size in the title, its opening line as the description. */
private fun snipMeta(snip: Snip): PageMeta {
    val kind = Languages.name(snip.language)
    val size = if (snip.lines == 1) "1 line" else "${snip.lines} lines"
    val opening = snip.content.take(400).replace(Regex("\\s+"), " ").trim().take(160)
    return PageMeta(
        title = "$kind, $size · placeholder",
        description = opening.ifEmpty { "Shared with placeholder." },
        path = "/${snip.slug}",
        image = "/${snip.slug}/og.png",
        imageAlt = "$kind snippet shared with placeholder",
        // Pastes are shared with a link, not published: keep them out of search results.
        indexable = false,
    )
}

/** Page titles for the fixed pages. Every path in [Paths.PAGES] must have an entry (checked at startup). */
private val PAGE_TITLES: Map<String, String?> = mapOf(
    Paths.HOME to null,
    Paths.NEW to "New link",
    Paths.SIGN_IN to "Sign in",
    Paths.LINKS to "Your links",
)

/** Only the landing page belongs in search results; the rest is personal or transient. */
private val INDEXABLE_PAGES = setOf(Paths.HOME)
