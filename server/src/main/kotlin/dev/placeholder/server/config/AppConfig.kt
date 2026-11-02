package dev.placeholder.server.config

import io.ktor.server.config.ApplicationConfig
import java.io.File

/** Typed view of the `placeholder:` section of application.yaml, read and validated once at startup. */
data class AppConfig(
    val publicBaseUrl: String,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val databasePoolSize: Int,
    val redisUrl: String?,
    val webRoot: File?,
    val createsPerMinute: Long,
    val trustProxyHeaders: Boolean,
    /** Sign-ups and sign-ins per minute, per client. Argon2 is costly by design, so this stays tight. */
    val authPerMinute: Long,
    /** Kafka brokers to bootstrap from, comma-separated. Clicks travel through Kafka on their way to Postgres. */
    val kafkaBootstrapServers: String,
    /** Copies of each click partition Kafka keeps: 1 on a laptop, 3 in production. */
    val kafkaReplicationFactor: Short,
    /** Key for the HMAC that turns an IP and user agent into an anonymous visitor id. Shared by every instance. */
    val visitorKey: String,
    /** Bearer token Prometheus must present to read /metrics. Null leaves them open, which only a laptop should. */
    val metricsToken: String?,
) {
    /**
     * An HTTPS address means the site is public: session cookies are marked Secure, HSTS is sent, and the
     * settings that are only fine on a laptop are refused.
     */
    val servedOverHttps: Boolean get() = publicBaseUrl.startsWith("https://")

    init {
        require(!servedOverHttps || visitorKey != DEV_VISITOR_KEY) {
            "VISITOR_KEY must be set to a long random value when the site is served over HTTPS"
        }
        require(!servedOverHttps || metricsToken != null) {
            "METRICS_TOKEN must be set when the site is served over HTTPS, or /metrics is open to everyone"
        }
    }

    companion object {
        /** Stands in for VISITOR_KEY on a laptop. Visitor ids made with it are only as anonymous as this string. */
        const val DEV_VISITOR_KEY = "local-development-only"

        fun from(config: ApplicationConfig): AppConfig {
            val app = config.config("placeholder")

            fun value(path: String): String? = app.propertyOrNull(path)?.getString()?.takeIf { it.isNotBlank() }

            fun required(path: String): String = value(path) ?: error("Missing config value placeholder.$path")

            return AppConfig(
                publicBaseUrl = required("publicBaseUrl").trimEnd('/'),
                databaseUrl = required("database.url"),
                databaseUser = required("database.user"),
                databasePassword = required("database.password"),
                databasePoolSize = required("database.poolSize").toInt(),
                redisUrl = value("redis.url"),
                webRoot = value("webRoot")?.let(::File)?.takeIf { it.isDirectory },
                createsPerMinute = required("rateLimit.createsPerMinute").toLong(),
                trustProxyHeaders = value("trustProxyHeaders").toBoolean(),
                authPerMinute = required("rateLimit.authPerMinute").toLong(),
                kafkaBootstrapServers = required("kafka.bootstrapServers"),
                kafkaReplicationFactor = required("kafka.replicationFactor").toShort(),
                visitorKey = value("analytics.visitorKey") ?: DEV_VISITOR_KEY,
                metricsToken = value("metrics.token"),
            )
        }
    }
}
