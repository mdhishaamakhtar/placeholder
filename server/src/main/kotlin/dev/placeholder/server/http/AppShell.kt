package dev.placeholder.server.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
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
 * The web app's index.html, with two things written in per request: the visitor's theme (from a cookie, so
 * light-theme visitors never see a dark first frame) and the page's meta tags.
 */
class AppShell(webRoot: File?, private val publicBaseUrl: String) {
    private val index = webRoot?.resolve("index.html")?.takeIf { it.isFile }

    @Volatile
    private var cached: Pair<Long, String>? = null

    private fun template(): String? {
        val file = index ?: return null
        val modified = file.lastModified()
        cached?.let { (at, html) -> if (at == modified) return html }
        // Kobweb writes its own title and description; ours replace them per page.
        val html = file.readText()
            .replace(Regex("<title>.*?</title>\\s*"), "")
            .replace(Regex("<meta content=\"[^\"]*\" name=\"description\">\\s*"), "")
        cached = modified to html
        return html
    }

    suspend fun respond(call: ApplicationCall, status: HttpStatusCode, meta: PageMeta) {
        val template = template()
        if (template == null) {
            call.respondText("placeholder API is running. Build the web app to see the page.", status = status)
            return
        }
        var html = template.replaceFirst("</head>", metaTags(meta) + "</head>")
        if (call.request.cookies[THEME_COOKIE] == "light") {
            html = html
                .replaceFirst("<html lang=\"en\">", "<html lang=\"en\" data-theme=\"light\">")
                .replaceFirst("content=\"$DARK_CHROME\"", "content=\"$LIGHT_CHROME\"")
        }
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header(HttpHeaders.Vary, "Cookie")
        call.respondText(html, ContentType.Text.Html.withCharset(Charsets.UTF_8), status)
    }

    private fun metaTags(meta: PageMeta): String {
        val url = publicBaseUrl + meta.path
        val image = publicBaseUrl + meta.image
        val title = escape(meta.title)
        val description = escape(meta.description)
        return buildString {
            append("<title>$title</title>\n")
            append("""<meta name="description" content="$description">""").append('\n')
            append("""<link rel="canonical" href="${escape(url)}">""").append('\n')
            if (!meta.indexable) append("""<meta name="robots" content="noindex">""").append('\n')
            append("""<meta property="og:site_name" content="placeholder">""").append('\n')
            append("""<meta property="og:type" content="website">""").append('\n')
            append("""<meta property="og:title" content="$title">""").append('\n')
            append("""<meta property="og:description" content="$description">""").append('\n')
            append("""<meta property="og:url" content="${escape(url)}">""").append('\n')
            append("""<meta property="og:image" content="${escape(image)}">""").append('\n')
            append("""<meta property="og:image:width" content="1200">""").append('\n')
            append("""<meta property="og:image:height" content="630">""").append('\n')
            append("""<meta property="og:image:alt" content="${escape(meta.imageAlt)}">""").append('\n')
            append("""<meta name="twitter:card" content="summary_large_image">""").append('\n')
            append("""<meta name="twitter:title" content="$title">""").append('\n')
            append("""<meta name="twitter:description" content="$description">""").append('\n')
            append("""<meta name="twitter:image" content="${escape(image)}">""").append('\n')
        }
    }

    private fun escape(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    private companion object {
        const val THEME_COOKIE = "theme"
        const val DARK_CHROME = "#000000"
        const val LIGHT_CHROME = "#FFFFFF"
    }
}
