package dev.placeholder.server.analytics

import dev.placeholder.server.infra.Kafka
import dev.placeholder.shared.LinkStats
import dev.placeholder.shared.SnipPage
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.kafka.common.KafkaException
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Visit counting, end to end. A visit is recorded on the request ([record]), carried through Kafka
 * ([ClickProducer], [ClickConsumer]) and rolled up by day in Postgres ([ClickStore]), where the owner reads it.
 */
class Clicks(
    private val kafka: Kafka,
    private val store: ClickStore,
    private val visitors: Visitors,
    registry: MeterRegistry,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(Clicks::class.java)
    private val producer = ClickProducer(kafka, TOPIC, registry)
    private val consumer = ClickConsumer(kafka, TOPIC, store, registry)
    private val ready = CompletableDeferred<Unit>()

    /** Counts a visit to [slug], unless it came from a bot. Returns at once; the count lands a moment later. */
    fun record(slug: String, ip: String, userAgent: String?, now: Long = System.currentTimeMillis()) {
        if (userAgent == null || Bots.isBot(userAgent)) return
        producer.record(Click(UUID.randomUUID(), slug, now, visitors.id(ip, userAgent)))
    }

    /**
     * Starts the background work, which stops when [scope] does: getting the topic and the producer ready, and
     * consuming. The job completes once it has all wound down.
     */
    fun start(scope: CoroutineScope): Job = scope.launch {
        launch {
            ensureTopic()
            producer.awaitPartitions()
            ready.complete(Unit)
        }
        consumer.start(this)
    }

    /**
     * Waits up to [timeout] for the producer to be able to send, so a server that has just started doesn't drop the
     * first visits it sees. False if Kafka didn't answer in time: the server starts anyway, since redirects don't
     * need Kafka, and clicks are counted from the moment it does answer.
     */
    suspend fun awaitReady(timeout: Duration): Boolean = withTimeoutOrNull(timeout) { ready.await() } != null

    private suspend fun ensureTopic() {
        var backoff = MIN_BACKOFF
        while (true) {
            try {
                kafka.ensureTopic(TOPIC, PARTITIONS)
                return
            } catch (e: KafkaException) {
                log.warn("Kafka unavailable, retrying in {}: {}", backoff, e.message)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
            }
        }
    }

    suspend fun withVisits(page: SnipPage): SnipPage {
        val visits = store.visits(page.items.map { it.slug })
        return page.copy(items = page.items.map { it.copy(visits = visits[it.slug] ?: 0L) })
    }

    suspend fun stats(slug: String, now: Long = System.currentTimeMillis()): LinkStats =
        store.stats(slug, today = LocalDate.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC))

    /** Sends the clicks the producer still holds. */
    override fun close() = producer.close()

    private companion object {
        const val TOPIC = "clicks"

        /** Enough to spread across a dozen consumers; partitions can be added later, but not removed. */
        const val PARTITIONS = 12
        val MIN_BACKOFF = 1.seconds
        val MAX_BACKOFF = 30.seconds
    }
}
