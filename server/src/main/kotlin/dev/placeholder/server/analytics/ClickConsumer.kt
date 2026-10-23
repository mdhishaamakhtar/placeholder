package dev.placeholder.server.analytics

import dev.placeholder.server.infra.Kafka
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.kafka.KafkaClientMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.apache.kafka.clients.consumer.CommitFailedException
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.errors.InterruptException
import org.apache.kafka.common.errors.RebalanceInProgressException
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

/**
 * Moves clicks from Kafka into Postgres. Every app instance runs one, all in the same consumer group, so Kafka
 * splits the topic's partitions between them, and moves a member's partitions to the others when it leaves or dies.
 *
 * Delivery is at-least-once: offsets are committed only after a batch's transaction has committed. If Postgres
 * fails, the consumer rewinds to the start of the batch and tries again later; meanwhile Kafka keeps the clicks.
 * If an instance dies between the two commits, whoever gets its partitions reads the batch again, and [ClickStore]
 * skips the ids it has already counted. At-least-once delivery plus an idempotent write is exactly-once counting.
 */
class ClickConsumer(
    private val kafka: Kafka,
    private val topic: String,
    private val store: ClickStore,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(ClickConsumer::class.java)
    private val ingested = registry.counter("clicks.ingested")
    private val duplicates = registry.counter("clicks.duplicates")
    private val unreadable = registry.counter("clicks.dropped", "reason", "unreadable")
    private val batchTimer = Timer.builder("clicks.ingest").publishPercentiles(0.5, 0.99).register(registry)

    /**
     * Starts consuming until [scope] is cancelled. A Kafka consumer must only ever be used from one thread, so it
     * gets a thread of its own; cancelling interrupts its wait for records. Until the topic exists, the consumer
     * simply finds nothing to read.
     */
    fun start(scope: CoroutineScope): Job {
        val thread = Executors.newSingleThreadExecutor { Thread(it, "click-consumer") }
        val job =
            scope.launch(thread.asCoroutineDispatcher()) {
                kafka.consumer(GROUP, BATCH_SIZE).use { consumer ->
                    KafkaClientMetrics(consumer).use { metrics ->
                        metrics.bindTo(registry)
                        consumer.subscribe(listOf(topic))
                        consume(consumer)
                    }
                }
            }
        job.invokeOnCompletion { thread.shutdown() }
        return job
    }

    private suspend fun CoroutineScope.consume(consumer: KafkaConsumer<String, String>) {
        var backoff = MIN_BACKOFF
        while (isActive) {
            val records =
                try {
                    runInterruptible { consumer.poll(POLL_TIMEOUT) }
                } catch (_: InterruptException) {
                    // Cancelled while waiting. Kafka's exception sets the thread's interrupt flag again, which would make
                    // closing the consumer throw too; clear it so the consumer can leave its group cleanly.
                    Thread.interrupted()
                    return
                }
            if (records.isEmpty) continue
            try {
                ingest(records)
            } catch (e: SQLException) {
                // Not committed, so still ours to count: go back to the start of the batch and try again later.
                records.partitions().forEach { consumer.seek(it, records.records(it).first().offset()) }
                log.warn("Database unavailable, retrying in {}: {}", backoff, e.message)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
                continue
            }
            backoff = MIN_BACKOFF
            try {
                consumer.commitSync()
            } catch (e: CommitFailedException) {
                // The partitions moved to another member mid-batch. It reads the batch again, and the ids are skipped.
                log.info(
                    "Offsets not committed after a rebalance; the batch will be recognised as counted: {}",
                    e.message,
                )
            } catch (e: RebalanceInProgressException) {
                log.info(
                    "Offsets not committed during a rebalance; the batch will be recognised as counted: {}",
                    e.message,
                )
            }
        }
    }

    private suspend fun ingest(records: ConsumerRecords<String, String>) {
        val clicks =
            records.mapNotNull { record ->
                val click = Click.fromJson(record.value())
                if (click == null) log.warn("Skipping unreadable click at {}", record.offset())
                click
            }
        unreadable.increment((records.count() - clicks.size).toDouble())
        if (clicks.isEmpty()) return
        val started = System.nanoTime()
        val fresh = store.ingest(clicks)
        batchTimer.record(Duration.ofNanos(System.nanoTime() - started))
        ingested.increment(fresh.toDouble())
        duplicates.increment((clicks.size - fresh).toDouble())
    }

    companion object {
        const val GROUP = "rollup"

        private const val BATCH_SIZE = 1_000
        private val POLL_TIMEOUT = Duration.ofSeconds(1)
        private val MIN_BACKOFF = 1.seconds
        private val MAX_BACKOFF = 30.seconds
    }
}
