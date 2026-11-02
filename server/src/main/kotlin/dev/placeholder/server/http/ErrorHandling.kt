package dev.placeholder.server.http

import dev.placeholder.server.AppException
import dev.placeholder.server.ConflictException
import dev.placeholder.server.ForbiddenException
import dev.placeholder.server.InvalidInputException
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
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.accept
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
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
        // Thrown by RequestBodyLimit (Plugins.kt) when Content-Length already says the body is too big.
        exception<PayloadTooLargeException> { call, _ -> call.respondTooLarge() }
        // Unknown pages still get the app, which shows its "nothing here" screen, but with an honest 404.
        status(HttpStatusCode.NotFound) { call, status ->
            val wantsPage =
                call.request.httpMethod == HttpMethod.Get &&
                    !call.request.path().startsWith("/api/") &&
                    call.request.accept()?.contains("text/html") == true
            if (wantsPage) {
                shell.respond(
                    call,
                    status,
                    PageMeta.page("Not found", call.request.path(), indexable = false),
                )
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

/** This exception and everything it was caused by, nearest first. */
private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause?.takeIf { c -> c !== it } }
