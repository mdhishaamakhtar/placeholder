package dev.placeholder.server.http

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.accept
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect

/**
 * The answer for a page address with nothing behind it: the address it was meant to be, or the app's own "nothing
 * here" screen with an honest 404.
 *
 * Routes call this themselves instead of answering with a bare 404 for a status page to fill in. Ktor's status pages
 * copy the response's headers before deciding, so a 404 a route already wrote (with its own body, possibly
 * compressed) would go out with doubled Content-Length or Content-Encoding headers and an unreadable body.
 */
suspend fun ApplicationCall.respondNotFound(shell: AppShell) {
    val path = request.path()
    val reading = request.httpMethod == HttpMethod.Get || request.httpMethod == HttpMethod.Head
    val withoutSlash = path.trimEnd('/')
    when {
        // A link pasted with a trailing slash ("/abc1234/") means the link without it. Redirecting rather than
        // matching both keeps one address per page. Never to "//host": that would leave the site.
        reading && path.endsWith("/") && withoutSlash.isNotEmpty() && isLocalPath(withoutSlash) -> {
            val query = request.queryString()
            respondRedirect(if (query.isEmpty()) withoutSlash else "$withoutSlash?$query", permanent = true)
        }

        reading && request.accept()?.contains("text/html") == true -> {
            shell.respond(this, HttpStatusCode.NotFound, PageMeta.page("Not found", path, indexable = false))
        }

        else -> {
            respond(HttpStatusCode.NotFound)
        }
    }
}

/** A path on this site: one leading slash, and no backslash, which browsers read as a slash. */
private fun isLocalPath(path: String): Boolean = path.startsWith("/") && !path.startsWith("//") && '\\' !in path
