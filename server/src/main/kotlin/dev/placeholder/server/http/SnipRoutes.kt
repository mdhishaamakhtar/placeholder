package dev.placeholder.server.http

import dev.placeholder.server.PayloadTooLargeException
import dev.placeholder.server.analytics.Clicks
import dev.placeholder.server.infra.RateLimiter
import dev.placeholder.server.prettify.Prettifier
import dev.placeholder.server.snip.Lookup
import dev.placeholder.server.snip.Snip
import dev.placeholder.server.snip.SnipService
import dev.placeholder.shared.ApiError
import dev.placeholder.shared.CreateSnipRequest
import dev.placeholder.shared.CreateSnipResponse
import dev.placeholder.shared.Messages
import dev.placeholder.shared.PrettifyRequest
import dev.placeholder.shared.PrettifyResponse
import dev.placeholder.shared.Rules
import dev.placeholder.shared.UpdateSnipRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The JSON API the web app talks to. */
fun Route.snipRoutes(snips: SnipService, clicks: Clicks, limiter: RateLimiter, publicBaseUrl: String) {
    route("/api") {
        post("/snips") {
            call.rejectOversizedBody()
            // Making links needs an account: every new link has an owner who can find, edit, and expire it.
            val user = call.requireUser()
            limiter.check("create", call.clientId())
            val snip = snips.create(call.receive<CreateSnipRequest>(), user)
            call.respond(
                HttpStatusCode.Created,
                CreateSnipResponse(snip.slug, snip.kind, "$publicBaseUrl/${snip.slug}"),
            )
        }

        get("/snips/{slug}") {
            when (val found = snips.lookup(call.parameters["slug"].orEmpty())) {
                Lookup.Missing -> {
                    call.response.header(HttpHeaders.CacheControl, CachePolicy.SHORT)
                    call.respond(HttpStatusCode.NotFound, ApiError(Messages.NOT_FOUND))
                }
                Lookup.Expired -> {
                    call.response.header(HttpHeaders.CacheControl, CachePolicy.SHORT)
                    call.respond(HttpStatusCode.Gone, ApiError(Messages.EXPIRED))
                }
                is Lookup.Found -> {
                    val snip = found.snip
                    if (call.notModified(snip)) return@get
                    call.response.header(HttpHeaders.CacheControl, CachePolicy.forSnip(snip))
                    call.respond(snip.toDto(call.user?.id))
                }
            }
        }

        put("/snips/{slug}") {
            call.rejectOversizedBody()
            val user = call.requireUser()
            val snip = snips.update(call.parameters["slug"].orEmpty(), call.receive<UpdateSnipRequest>(), user)
            call.respond(snip.toDto(user.id))
        }

        get("/me/snips") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(clicks.withVisits(snips.mine(call.requireUser(), call.request.queryParameters["before"])))
        }

        get("/snips/{slug}/stats") {
            val user = call.requireUser()
            call.response.header(HttpHeaders.CacheControl, "no-store")
            // Someone else's link answers exactly like a missing one: stats don't reveal what exists.
            val snip = (snips.lookup(call.parameters["slug"].orEmpty()) as? Lookup.Found)?.snip
            if (snip == null || !snip.isOwnedBy(user.id)) {
                call.respond(HttpStatusCode.NotFound, ApiError(Messages.NOT_FOUND))
                return@get
            }
            call.respond(clicks.stats(snip.slug))
        }

        post("/prettify") {
            call.rejectOversizedBody()
            call.requireUser()
            limiter.check("prettify", call.clientId())
            val request = call.receive<PrettifyRequest>()
            val formatted = withContext(Dispatchers.Default) { Prettifier.format(request.content, request.language) }
            call.respond(PrettifyResponse(formatted, formatted != request.content))
        }
    }
}

/**
 * Anonymous snips never change: cache them forever (or until they expire). Owned snips can be edited, and the
 * response says whether *you* own it, so they stay private and are revalidated by version.
 */
object CachePolicy {
    const val IMMUTABLE = "public, max-age=31536000, immutable"
    const val SHORT = "public, max-age=60"

    fun forSnip(snip: Snip, now: Long = System.currentTimeMillis()): String {
        if (!snip.isImmutable) return "private, no-cache"
        val expiresAt = snip.expiresAt ?: return IMMUTABLE
        return "public, max-age=${((expiresAt - now) / 1000).coerceAtLeast(0)}"
    }

    fun forRedirect(snip: Snip, now: Long = System.currentTimeMillis()): String {
        if (!snip.isImmutable) return "private, no-cache"
        val day = 86_400L
        val left = snip.expiresAt?.let { ((it - now) / 1000).coerceAtLeast(0) } ?: day
        return "public, max-age=${minOf(day, left)}"
    }
}

/** Editable snips carry their version as an ETag, so an unchanged one revalidates as a tiny 304. */
private suspend fun ApplicationCall.notModified(snip: Snip): Boolean {
    if (snip.isImmutable) return false
    val etag = "\"v${snip.version}-${if (snip.isOwnedBy(user?.id)) "o" else "p"}\""
    response.header(HttpHeaders.ETag, etag)
    if (request.headers[HttpHeaders.IfNoneMatch] != etag) return false
    respond(HttpStatusCode.NotModified)
    return true
}

private fun ApplicationCall.clientId(): String = request.origin.remoteHost

private fun ApplicationCall.rejectOversizedBody() {
    val length = request.contentLength() ?: return
    // JSON escaping can roughly double the size of the raw content, so allow headroom over the content limit.
    if (length > Rules.MAX_CONTENT_BYTES * 2L + 4096) {
        throw PayloadTooLargeException("That's too big. The limit is ${Rules.MAX_CONTENT_BYTES / 1024} KB.")
    }
}
