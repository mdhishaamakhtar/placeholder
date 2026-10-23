package dev.placeholder.server.analytics

import dev.placeholder.server.infra.Kafka
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.kafka.KafkaClientMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.KafkaException
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The request side of click counting. [record] hands the click to Kafka's producer and returns: the client batches,
 * compresses and sends on its own thread, so a slow or missing Kafka can't add a millisecond to a redirect.
 *
 * Clicks are keyed by slug, so all of a link's clicks land on one partition and are counted by one consumer.
 * While Kafka is unreachable the producer holds clicks in memory (up to 32 MB) for up to two minutes; past that,
 * or if the buffer fills, clicks are dropped and counted in `clicks.dropped`. Losing some visits is acceptable,
 * slowing down or failing the redirect is not.
 */
class ClickProducer(kafka: Kafka, private val topic: String, registry: MeterRegistry) : AutoCloseable {
    private val log = LoggerFactory.getLogger(ClickProducer::class.java)
    private val producer = kafka.producer()
    private val metrics = KafkaClientMetrics(producer).apply { bindTo(registry) }
    private val recorded = registry.counter("clicks.recorded")
    private val notAccepted = dropped(registry, "not_accepted")
    private val undelivered = dropped(registry, "undelivered")

    fun record(click: Click) {
        try {
            producer.send(ProducerRecord(topic, click.slug, click.toJson())) { _, error ->
                // Not sent: the buffer was full, Kafka's partitions weren't known, or Kafka stayed down for 2 minutes.
                if (error != null) {
                    undelivered.increment()
                    log.debug("Click {} not delivered: {}", click.id, error.message)
                }
            }
            recorded.increment()
        } catch (e: KafkaException) {
            // Rare: most failures (a full buffer, unknown partitions) arrive through the callback instead. This is the
            // producer refusing outright, e.g. while it's closing at shutdown.
            notAccepted.increment()
            log.debug("Click {} not accepted: {}", click.id, e.message)
        }
    }

    /**
     * Waits until the producer knows the topic's partitions. It's set never to wait for them on a send, so until it
     * knows them every send fails; this asks until Kafka answers.
     */
    suspend fun awaitPartitions() = withContext(Dispatchers.IO) {
        while (true) {
            try {
                producer.partitionsFor(topic)
                return@withContext
            } catch (_: KafkaException) {
                delay(RETRY)
            }
        }
    }

    /** Sends what's still buffered (waiting a few seconds at most), then closes. */
    override fun close() {
        producer.close(Duration.ofSeconds(5))
        metrics.close()
    }

    private companion object {
        val RETRY = 500.milliseconds

        fun dropped(registry: MeterRegistry, reason: String): Counter =
            Counter.builder("clicks.dropped").tag("reason", reason).register(registry)
    }
}
