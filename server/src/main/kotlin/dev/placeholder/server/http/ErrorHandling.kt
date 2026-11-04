package dev.placeholder.server.http

import dev.placeholder.server.AppException
import dev.placeholder.server.ConflictException
import dev.placeholder.server.ForbiddenException
import dev.placeholder.server.InvalidInputException
import dev.placeholder.server.NotFoundException
import dev.placeholder.server.PrettifyException
import dev.placeholder.server.RateLimitedException
import dev.placeholder.server.UnauthorizedException
import dev.placeholder.shared.ApiError
import dev.placeholder.shared.Messages
import dev.placeholder.shared.Rules
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.http.content.HttpStatusCodeContent
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.accept
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import org.slf4j.LoggerFactory

/** Maps every expected failure to a status and a sentence; anything unexpected is logged and hidden. */
fun Application.installErrorHandling(shell: AppShell) {
    val log = LoggerFactory.getLogger("dev.placeholder.server.errors")

    install(StatusPages) {
        exception<AppException> { call, e ->
            val status =
                when (e) {
                    is InvalidInputException -> {
                        HttpStatusCode.BadRequest
                    }

                    is PrettifyException -> {
                        HttpStatusCode.UnprocessableEntity
                    }

                    is UnauthorizedException -> {
                        HttpStatusCode.Unauthorized
                    }

                    is ForbiddenException -> {
                        HttpStatusCode.Forbidden
                    }

                    is NotFoundException -> {
                        HttpStatusCode.NotFound
                    }

                    is ConflictException -> {
                        HttpStatusCode.Conflict
                    }

                    is RateLimitedException -> {
                        call.response.header(HttpHeaders.RetryAfter, e.retryAfterSeconds.toString())
                        HttpStatusCode.TooManyRequests
                    }
                }
            call.respond(status, ApiError(e.message.orEmpty()))
        }
        exception<BadRequestException> { call, e ->
            // A body that grows past the limit while JSON is being read fails inside the converter, which reports
            // it as a bad request; the real reason is further down the chain.
            if (e.causes().any { it is PayloadTooLargeException }) {
                call.respondTooLarge()
            } else {
                call.respond(HttpStatusCode.BadRequest, ApiError("The request body isn't valid."))
            }
        }
        // The body isn't JSON at all (a form post, or no content type): the client's mistake, not a server error.
        exception<ContentTransformationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("The request body isn't valid."))
        }
        // Thrown by RequestBodyLimit (Plugins.kt) when Content-Length already says the body is too big.
        exception<PayloadTooLargeException> { call, _ -> call.respondTooLarge() }
        // Only bare 404s are filled in here (nothing matched, or a route answered with just the status). A route that
        // wrote its own 404 body already said what's missing.
        status(HttpStatusCode.NotFound) { status ->
            if (content !is HttpStatusCodeContent) return@status
            val path = call.request.path()
            val reading = call.request.httpMethod == HttpMethod.Get || call.request.httpMethod == HttpMethod.Head
            val withoutSlash = path.trimEnd('/')
            when {
                path.startsWith("/api/") -> {
                    call.respond(status, ApiError(Messages.NOT_FOUND))
                }

                // A link pasted with a trailing slash ("/abc1234/") means the link without it. Redirecting rather than
                // matching both keeps one address per page. Never to "//host": that would leave the site.
                reading && path.endsWith("/") && withoutSlash.isNotEmpty() && isLocalPath(withoutSlash) -> {
                    val query = call.request.queryString()
                    call.respondRedirect(
                        if (query.isEmpty()) withoutSlash else "$withoutSlash?$query",
                        permanent = true,
                    )
                }

                // Unknown pages still get the app, which shows its "nothing here" screen, but with an honest 404.
                reading && call.request.accept()?.contains("text/html") == true -> {
                    shell.respond(call, status, PageMeta.page("Not found", path, indexable = false))
                }
            }
        }
        exception<Throwable> { call, e ->
            log.error("Unhandled error on {}", call.request.local.uri, e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError(Messages.SERVER_ERROR),
            )
        }
    }
}

private suspend fun ApplicationCall.respondTooLarge() =
    respond(
        HttpStatusCode.PayloadTooLarge,
        ApiError("That's too big. The limit is ${Rules.MAX_CONTENT_BYTES / 1024} KB."),
    )

/** A path on this site: one leading slash, and no backslash, which browsers read as a slash. */
private fun isLocalPath(path: String): Boolean = path.startsWith("/") && !path.startsWith("//") && '\\' !in path

/** This exception and everything it was caused by, nearest first. */
private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause?.takeIf { c -> c !== it } }
