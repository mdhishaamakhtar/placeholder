package dev.placeholder.server.http

import dev.placeholder.server.config.AppConfig
import dev.placeholder.server.infra.Metrics
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import kotlinx.serialization.json.Json

fun Application.installPlugins(config: AppConfig, metrics: Metrics, shell: AppShell) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("Referrer-Policy", "strict-origin-when-cross-origin")
        header("X-Frame-Options", "DENY")
    }
    install(Compression) {
        gzip { matchContentType(ContentType.Application.Json, ContentType.Text.Any, ContentType.Application.JavaScript) }
        deflate { matchContentType(ContentType.Application.Json, ContentType.Text.Any) }
    }
    // Static files carry ETags, so revalidating browsers get a cheap 304 when nothing changed.
    install(ConditionalHeaders)
    if (config.trustProxyHeaders) install(XForwardedHeaders)
    install(MicrometerMetrics) {
        registry = metrics.registry
    }
    installErrorHandling(shell)
}
