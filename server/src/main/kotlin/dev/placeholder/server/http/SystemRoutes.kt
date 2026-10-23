package dev.placeholder.server.http

import dev.placeholder.server.infra.Database
import dev.placeholder.server.infra.Metrics
import dev.placeholder.server.infra.Redis
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Liveness, readiness and metrics, for load balancers and Prometheus. */
fun Route.systemRoutes(
    database: Database,
    redis: Redis?,
    metrics: Metrics,
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

    get("/metrics") { call.respondText(metrics.scrape()) }
}
