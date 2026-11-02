package dev.placeholder.server.http

import dev.placeholder.server.infra.Database
import dev.placeholder.server.infra.Metrics
import dev.placeholder.server.infra.Redis
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.authorization
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.security.MessageDigest

/**
 * Liveness, readiness and metrics. The first two are for load balancers and stay open; they say almost nothing.
 * Metrics describe every route's traffic and latency, so when a [metricsToken] is set, Prometheus must present it.
 */
fun Route.systemRoutes(
    database: Database,
    redis: Redis?,
    metrics: Metrics,
    metricsToken: String?,
) {
    get("/health") { call.respondText("ok") }

    get("/ready") {
        val db = database.isHealthy()
        val cache = redis?.ping() ?: false
        call.respondText(
            """{"database":$db,"redis":$cache}""",
            ContentType.Application.Json,
            if (db) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
        )
    }

    get("/metrics") {
        if (metricsToken != null && !call.presents(metricsToken)) {
            call.respond(HttpStatusCode.Unauthorized)
            return@get
        }
        call.respondText(metrics.scrape())
    }
}

/** Compared in constant time: a scraper's token shouldn't be guessable a byte at a time. */
private fun io.ktor.server.application.ApplicationCall.presents(token: String): Boolean {
    val expected = "Bearer $token".encodeToByteArray()
    val given = request.authorization()?.encodeToByteArray() ?: return false
    return MessageDigest.isEqual(expected, given)
}
