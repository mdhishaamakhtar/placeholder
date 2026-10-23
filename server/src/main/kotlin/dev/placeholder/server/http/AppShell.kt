package dev.placeholder.server.http

import dev.placeholder.server.og.PreviewCards
import dev.placeholder.shared.Theme
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File

/** What link previews and search engines see for a page. Crawlers don't run the app, so this is server-rendered. */
data class PageMeta(
    val title: String,
    val description: String,
    val path: String,
    val image: String = "/og.png",
    val imageAlt: String = DEFAULT_IMAGE_ALT,
    val indexable: Boolean = true,
) {
    companion object {
        const val DEFAULT_DESCRIPTION =
            "Paste a link, code, or text and get a short link to share it. Code is highlighted and tidied; links redirect instantly."
        const val DEFAULT_IMAGE_ALT = "placeholder: short links for links, code, and text."

        fun page(title: String?, path: String, indexable: Boolean = true) = PageMeta(
            title = title?.let { "$it · placeholder" } ?: "placeholder: short links for links, code, and text",
            description = DEFAULT_DESCRIPTION,
            path = path,
            indexable = indexable,
        )
    }
}

/**
 * Serves the web app's index.html with two things filled in per request: the page's meta tags, and the visitor's
 * theme (from a cookie, so light-theme visitors never see a dark first frame while the app's script loads).
 *
 * The file Kobweb generates is parsed once into an HTML document and edited through the DOM, never by matching
 * strings, so a change in how Kobweb formats its output can't silently break what crawlers see.
 */
class AppShell(webRoot: File?, private val publicBaseUrl: String) {
    private val index = webRoot?.resolve("index.html")?.takeIf { it.isFile }

    @Volatile
    private var cached: Pair<Long, Document>? = null

    private fun template(): Document? {
        val file = index ?: return null
        val modified = file.lastModified()
        cached?.let { (at, doc) -> if (at == modified) return doc }
        val doc = Jsoup.parse(file, Charsets.UTF_8.name()).apply {
            outputSettings().prettyPrint(false)
            // Kobweb writes a site-wide title and description; each page supplies its own instead.
            head().select("title, meta[name=description], meta[name=theme-color]").remove()
        }
        cached = modified to doc
        return doc
    }

    suspend fun respond(call: ApplicationCall, status: HttpStatusCode, meta: PageMeta) {
        val template = template()
        if (template == null) {
            call.respondText("placeholder API is running. Build the web app to see the page.", status = status)
            return
        }
        val theme = Theme.from(call.request.cookies[Theme.COOKIE])
        val page = template.clone()
        page.selectFirst("html")?.attr(Theme.ATTRIBUTE, theme.attribute)
        writeMeta(page.head(), meta, theme)

        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header(HttpHeaders.Vary, "Cookie")
        call.respondText(page.outerHtml(), ContentType.Text.Html.withCharset(Charsets.UTF_8), status)
    }

    /** jsoup escapes every attribute value and text node it writes, so page content can't break out of a tag. */
    private fun writeMeta(head: Element, meta: PageMeta, theme: Theme) {
        val url = publicBaseUrl + meta.path
        val image = publicBaseUrl + meta.image
        head.appendElement("title").text(meta.title)
        head.named("description", meta.description)
        head.named("theme-color", theme.chrome)
        head.appendElement("link").attr("rel", "canonical").attr("href", url)
        if (!meta.indexable) head.named("robots", "noindex")
        head.property("og:site_name", "placeholder")
        head.property("og:type", "website")
        head.property("og:title", meta.title)
        head.property("og:description", meta.description)
        head.property("og:url", url)
        head.property("og:image", image)
        head.property("og:image:width", PreviewCards.PIXEL_WIDTH.toString())
        head.property("og:image:height", PreviewCards.PIXEL_HEIGHT.toString())
        head.property("og:image:alt", meta.imageAlt)
        head.named("twitter:card", "summary_large_image")
        head.named("twitter:title", meta.title)
        head.named("twitter:description", meta.description)
        head.named("twitter:image", image)
    }

    private fun Element.named(name: String, content: String) {
        appendElement("meta").attr("name", name).attr("content", content)
    }

    private fun Element.property(property: String, content: String) {
        appendElement("meta").attr("property", property).attr("content", content)
    }
}
