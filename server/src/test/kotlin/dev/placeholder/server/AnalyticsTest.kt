package dev.placeholder.server

import dev.placeholder.server.analytics.Click
import dev.placeholder.server.analytics.ClickConsumer
import dev.placeholder.server.analytics.ClickProducer
import dev.placeholder.server.analytics.ClickStore
import dev.placeholder.server.infra.Database
import dev.placeholder.server.infra.Kafka
import dev.placeholder.server.infra.Redis
import dev.placeholder.shared.CreateSnipRequest
import dev.placeholder.shared.CreateSnipResponse
import dev.placeholder.shared.Credentials
import dev.placeholder.shared.LinkStats
import dev.placeholder.shared.SnipPage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class AnalyticsTest {
    private fun withApp(
        kafka: Kafka = TestEnv.kafka(),
        block: suspend ApplicationTestBuilder.(newClient: suspend () -> HttpClient) -> Unit,
    ) = testApplication {
        val config = TestEnv.config()
        val components = AppComponents(config, Database(config), Redis(config.redisUrl!!), kafka)
        environment { this.config = MapApplicationConfig() }
        application {
            configure(components)
            components.clicks.start(this)
            runBlocking { components.clicks.awaitReady(15.seconds) }
        }
        val newClient: suspend () -> HttpClient = {
            createClient {
                followRedirects = false
                install(ContentNegotiation) { json() }
                install(HttpCookies)
            }.also {
                it.post("/api/account/signup") {
                    contentType(ContentType.Application.Json)
                    setBody(Credentials("clicks-${UUID.randomUUID()}@example.com", "correct horse battery"))
                }
            }
        }
        try {
            block(newClient)
        } finally {
            components.close()
        }
    }

    private suspend fun HttpClient.createLink(): String = post("/api/snips") {
        contentType(ContentType.Application.Json)
        setBody(CreateSnipRequest("https://example.com/${UUID.randomUUID()}"))
    }.body<CreateSnipResponse>().slug

    private suspend fun HttpClient.visit(slug: String, userAgent: String) =
        get("/$slug") { header(HttpHeaders.UserAgent, userAgent) }

    /** Clicks land asynchronously: read until [check] holds, or fail with the last value seen. */
    private suspend fun <T> eventually(read: suspend () -> T, check: (T) -> Boolean): T {
        val deadline = TimeSource.Monotonic.markNow() + 15.seconds
        while (true) {
            val value = read()
            if (check(value)) return value
            if (deadline.hasPassedNow()) throw AssertionError("Condition not met in time; last value: $value")
            delay(100.milliseconds)
        }
    }

    @Test
    fun visitsFlowThroughKafkaAndAreCountedOnce() = withApp { newClient ->
        val owner = newClient()
        val slug = owner.createLink()
        val visitor = newClient()
        repeat(3) { assertEquals(HttpStatusCode.Found, visitor.visit(slug, BROWSER).status) }
        repeat(2) { visitor.visit(slug, "Mozilla/5.0 (iPhone) Safari/26.0") }
        // Link previews aren't visits.
        visitor.visit(slug, "Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)")

        val stats = eventually({ owner.get("/api/snips/$slug/stats").body<LinkStats>() }) { it.visits == 5L }
        assertEquals(2L, stats.visitors)
        assertEquals(30, stats.days.size)
        assertEquals(5L, stats.days.last().visits)
        assertEquals(LocalDate.now(ZoneOffset.UTC).toString(), stats.days.last().day)

        val listed = owner.get("/api/me/snips").body<SnipPage>().items.single { it.slug == slug }
        assertEquals(5L, listed.visits)
    }

    @Test
    fun redirectsWorkWhileKafkaIsUnreachable() =
        withApp(kafka = Kafka("localhost:1", replicationFactor = 1)) { newClient ->
            val slug = newClient().createLink()
            val visitor = newClient()
            repeat(20) { assertEquals(HttpStatusCode.Found, visitor.visit(slug, BROWSER).status) }
        }

    @Test
    fun statsAreOnlyForTheOwner() = withApp { newClient ->
        val slug = newClient().createLink()
        assertEquals(HttpStatusCode.NotFound, newClient().get("/api/snips/$slug/stats").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/snips/$slug/stats").status)
    }

    @Test
    fun aRedeliveredBatchIsCountedOnce() = runBlocking {
        val config = TestEnv.config()
        Database(config).use { db ->
            val store = ClickStore(db)
            val slug = "r" + UUID.randomUUID().toString().take(6)
            val now = System.currentTimeMillis()
            val batch = (1..50).map { Click(UUID.randomUUID(), slug, now, "visitor-$it") }

            assertEquals(50, store.ingest(batch))
            // The same batch again (the consumer died between commit and acknowledgement), plus a repeat within it.
            assertEquals(0, store.ingest(batch + batch.first()))
            val today = LocalDate.now(ZoneOffset.UTC)
            assertEquals(50L, store.stats(slug, today).visits)
        }
    }

    @Test
    fun aBatchADeadConsumerNeverCommittedIsCountedByAnother() = runBlocking {
        val kafka = TestEnv.kafka()
        val topic = "clicks-${UUID.randomUUID()}"
        kafka.ensureTopic(topic, partitions = 3)
        Database(TestEnv.config()).use { db ->
            val store = ClickStore(db)
            val slug = "a" + UUID.randomUUID().toString().take(6)
            ClickProducer(kafka, topic, SimpleMeterRegistry()).use { producer ->
                producer.awaitPartitions()
                (1..3).forEach {
                    producer.record(Click(UUID.randomUUID(), slug, System.currentTimeMillis(), "visitor-$it"))
                }
            }

            // A consumer reads the clicks and dies before committing its offsets.
            kafka.consumer(ClickConsumer.GROUP, maxRecords = 100).use { dead ->
                dead.subscribe(listOf(topic))
                eventually({ dead.poll(Duration.ofMillis(200)).count() }) { it == 3 }
            }

            coroutineScope {
                val survivor = ClickConsumer(kafka, topic, store, SimpleMeterRegistry()).start(this)
                val today = LocalDate.now(ZoneOffset.UTC)
                eventually({ store.stats(slug, today).visits }) { it == 3L }
                survivor.cancelAndJoin()
            }
        }
    }

    @Test
    fun uniqueVisitorsAreEstimatedClosely() = runBlocking {
        Database(TestEnv.config()).use { db ->
            val store = ClickStore(db)
            val slug = "u" + UUID.randomUUID().toString().take(6)
            val today = LocalDate.now(ZoneOffset.UTC)
            val dayMillis = 86_400_000L
            val now = System.currentTimeMillis()
            // 20,000 visits by 8,000 people over two days; 2,000 of them come back on the second day.
            val yesterday = (0 until 5_000).map { Click(UUID.randomUUID(), slug, now - dayMillis, "v$it") }
            val todays = (3_000 until 8_000).flatMap { v -> List(3) { Click(UUID.randomUUID(), slug, now, "v$v") } }
            (yesterday + todays).chunked(1_000).forEach { store.ingest(it) }

            val stats = store.stats(slug, today)
            assertEquals(20_000L, stats.visits)
            assertWithin(8_000, stats.visitors)
            assertWithin(5_000, stats.days.last().visitors)
            assertWithin(5_000, stats.days[stats.days.size - 2].visitors)
        }
    }

    private fun assertWithin(expected: Long, actual: Long, tolerance: Double = 0.03) =
        assertTrue(abs(actual - expected) <= expected * tolerance, "Expected about $expected, got $actual")

    private companion object {
        const val BROWSER = "Mozilla/5.0 (Macintosh) Firefox/140.0"
    }
}
