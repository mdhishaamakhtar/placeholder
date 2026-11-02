package dev.placeholder.server

import dev.placeholder.server.config.AppConfig
import dev.placeholder.server.infra.Kafka
import org.testcontainers.containers.GenericContainer
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.io.path.createTempDirectory

/** One Postgres, one Redis and one Kafka for the whole test run, started on first use. */
object TestEnv {
    private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }
    private val redis = GenericContainer("redis:8-alpine").withExposedPorts(6379).apply { start() }
    private val kafka = KafkaContainer("apache/kafka:4.4.0").apply { start() }

    private val webRoot =
        createTempDirectory("web").toFile().apply {
            resolve("index.html").writeText(
                "<!DOCTYPE html><html lang=\"en\"><head><meta name=\"theme-color\" content=\"#000000\"></head><body></body></html>",
            )
        }

    const val BASE_URL = "http://test.local"

    fun config(
        createsPerMinute: Long = 1000,
        publicBaseUrl: String = BASE_URL,
        metricsToken: String? = null,
    ) = AppConfig(
        publicBaseUrl = publicBaseUrl,
        databaseUrl = postgres.jdbcUrl,
        databaseUser = postgres.username,
        databasePassword = postgres.password,
        databasePoolSize = 4,
        redisUrl = redisUrl,
        webRoot = webRoot,
        createsPerMinute = createsPerMinute,
        trustProxyHeaders = false,
        authPerMinute = 10_000,
        kafkaBootstrapServers = kafka.bootstrapServers,
        kafkaReplicationFactor = 1,
        visitorKey = "test-visitor-key",
        metricsToken = metricsToken,
    )

    private val redisUrl: String get() = "redis://${redis.host}:${redis.getMappedPort(6379)}"

    fun kafka() = Kafka(kafka.bootstrapServers, replicationFactor = 1)
}
