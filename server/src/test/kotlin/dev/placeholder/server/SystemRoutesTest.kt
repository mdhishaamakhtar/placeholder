package dev.placeholder.server

import dev.placeholder.server.config.AppConfig
import dev.placeholder.server.infra.Kafka
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class SystemRoutesTest {
    private fun withApp(
        config: AppConfig,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        val components = AppComponents(config, redis = null, kafka = Kafka("localhost:1", replicationFactor = 1))
        environment { this.config = MapApplicationConfig() }
        application { configure(components) }
        try {
            block()
        } finally {
            components.close()
        }
    }

    @Test
    fun metricsAreOpenWhenNoTokenIsConfigured() =
        withApp(TestEnv.config()) {
            assertEquals(HttpStatusCode.OK, client.get("/metrics").status)
        }

    @Test
    fun metricsNeedTheTokenWhenOneIsConfigured() =
        withApp(TestEnv.config(metricsToken = "scrape-secret")) {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/metrics").status)
            val wrong = client.get("/metrics") { header(HttpHeaders.Authorization, "Bearer nope") }
            assertEquals(HttpStatusCode.Unauthorized, wrong.status)
            val right = client.get("/metrics") { header(HttpHeaders.Authorization, "Bearer scrape-secret") }
            assertEquals(HttpStatusCode.OK, right.status)
            // Load balancers poll these without credentials.
            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            assertEquals(HttpStatusCode.OK, client.get("/ready").status)
        }
}
