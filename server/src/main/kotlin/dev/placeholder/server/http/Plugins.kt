package dev.placeholder.server.http

import dev.placeholder.server.config.AppConfig
import dev.placeholder.server.infra.Metrics
import dev.placeholder.shared.Rules
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.routing.HttpMethodRouteSelector
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import kotlinx.serialization.json.Json
import java.time.Duration

fun Application.installPlugins(
    config: AppConfig,
    metrics: Metrics,
    shell: AppShell,
) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    // Checked against Content-Length up front, and as the body streams in when there is none (chunked requests),
    // so no request can make the server buffer more than this before the content limit is even looked at.
    install(RequestBodyLimit) {
        bodyLimit { BODY_LIMIT_BYTES }
    }
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("Referrer-Policy", "strict-origin-when-cross-origin")
        header("X-Frame-Options", "DENY")
        header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
        if (config.servedOverHttps) header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
    }
    install(Compression) {
        gzip {
            matchContentType(ContentType.Application.Json, ContentType.Text.Any, ContentType.Application.JavaScript)
        }
        deflate { matchContentType(ContentType.Application.Json, ContentType.Text.Any) }
    }
    // Static files carry ETags, so revalidating browsers get a cheap 304 when nothing changed.
    install(ConditionalHeaders)
    if (config.trustProxyHeaders) install(XForwardedHeaders)
    install(MicrometerMetrics) {
        registry = metrics.registry
        // Histogram buckets, so Prometheus can compute p99 across all instances (per-instance percentiles can't be
        // combined). Bounded to the range a request here can take, which keeps the bucket count small.
        distributionStatisticConfig =
            DistributionStatisticConfig
                .Builder()
                .percentilesHistogram(true)
                .minimumExpectedValue(Duration.ofMillis(1).toNanos().toDouble())
                .maximumExpectedValue(Duration.ofSeconds(10).toNanos().toDouble())
                .build()
        // Like Ktor's default, label a route by its path, not by the method selector at its end.
        transformRoute { node ->
            readableRoute((if (node.selector is HttpMethodRouteSelector) node.parent ?: node else node).toString())
        }
    }
    installErrorHandling(shell)
}

/** JSON escaping can roughly double the size of the raw content, so allow headroom over the content limit. */
private const val BODY_LIMIT_BYTES = Rules.MAX_CONTENT_BYTES * 2L + 4096

/**
 * The page renders pasted text, so the browser is told exactly what it may run: only this site's own script, and
 * nothing inline. Styles allow inline attributes because Compose HTML sets element styles that way. Nothing may
 * frame the page, and a `<base>` or `<object>` injected into it would be ignored.
 */
private const val CONTENT_SECURITY_POLICY =
    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'; font-src 'self'; " +
        "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'"

/**
 * Regex routes print as their pattern, e.g. `/Regex((?<slug>[abc…]{7})/raw)`, which makes an unreadable metrics label.
 * Each named group becomes its name in braces, so that one reads `/{slug}/raw`.
 */
internal fun readableRoute(route: String): String =
    route.replace(REGEX_ROUTE) { it.groupValues[1].replace(NAMED_GROUP) { group -> "{${group.groupValues[1]}}" } }

private val REGEX_ROUTE = Regex("""Regex\((.*)\)""")
private val NAMED_GROUP = Regex("""\(\?<(\w+)>[^)]*\)""")
