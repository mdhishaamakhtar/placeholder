package dev.placeholder.server

import dev.placeholder.server.config.AppConfig
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Settings that are fine on a laptop but must not reach production: an HTTPS site is taken to be production. */
class ConfigTest {
    private fun config(
        publicBaseUrl: String,
        visitorKey: String = "a-long-random-production-key",
        metricsToken: String? = "scrape-secret",
    ) = AppConfig(
        publicBaseUrl = publicBaseUrl,
        databaseUrl = "jdbc:postgresql://localhost/placeholder",
        databaseUser = "placeholder",
        databasePassword = "placeholder",
        databasePoolSize = 1,
        redisUrl = null,
        webRoot = null,
        createsPerMinute = 1,
        trustProxyHeaders = false,
        authPerMinute = 1,
        kafkaBootstrapServers = "localhost:9092",
        kafkaReplicationFactor = 1,
        visitorKey = visitorKey,
        metricsToken = metricsToken,
    )

    @Test
    fun theDevelopmentDefaultsAreFineOverHttp() {
        config("http://localhost:8080", visitorKey = AppConfig.DEV_VISITOR_KEY, metricsToken = null)
    }

    @Test
    fun theDevelopmentVisitorKeyIsRefusedOverHttps() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                config(
                    "https://example.com",
                    visitorKey = AppConfig.DEV_VISITOR_KEY,
                )
            }
        assertTrue("VISITOR_KEY" in e.message.orEmpty(), e.message)
    }

    @Test
    fun metricsMustBeProtectedOverHttps() {
        val e = assertFailsWith<IllegalArgumentException> { config("https://example.com", metricsToken = null) }
        assertTrue("METRICS_TOKEN" in e.message.orEmpty(), e.message)
    }
}
