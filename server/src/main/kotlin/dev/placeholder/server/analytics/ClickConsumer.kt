package dev.placeholder.server.analytics

import dev.placeholder.server.infra.Kafka
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.kafka.KafkaClientMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.apache.kafka.clients.consumer.CommitFailedException
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.InterruptException
import org.apache.kafka.common.errors.RebalanceInProgressException
import org.slf4j.LoggerFactory
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
 *
 * A batch that fails for a reason no retry will fix (a rollup row that can't be read, say) is taken one click at
 * a time: the clicks that fail on their own go to [deadLetterTopic] with the reason, the rest are counted, and the
 * partition moves on. Without that, one bad record would stall every click behind it for good.
 */
class ClickConsumer(
    private val kafka: Kafka,
    private val topic: String,
    private val deadLetterTopic: String,
    private val store: ClickStore,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(ClickConsumer::class.java)
    private val ingested = registry.counter("clicks.ingested")
    private val duplicates = registry.counter("clicks.duplicates")
    private val unreadable = registry.counter("clicks.dropped", "reason", "unreadable")
    private val poison = registry.counter("clicks.dropped", "reason", "poison")
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
                    kafka.producer(maxBlock = DEAD_LETTER_WAIT).use { deadLetters ->
                        KafkaClientMetrics(consumer).use { metrics ->
                            metrics.bindTo(registry)
                            consumer.subscribe(listOf(topic))
                            consume(consumer, deadLetters)
                        }
                    }
                }
            }
        job.invokeOnCompletion { thread.shutdown() }
        return job
    }

    private suspend fun CoroutineScope.consume(
        consumer: KafkaConsumer<String, String>,
        deadLetters: KafkaProducer<String, String>,
    ) {
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
            val batch = read(records)

            val failure = attempt { count(batch) }
            if (failure != null) {
                val setAside =
                    if (failure.isTransient()) {
                        failure
                    } else {
                        log.warn(
                            "A batch can't be counted as it is, taking it one click at a time: {}",
                            failure.toString(),
                        )
                        attempt { quarantine(batch, deadLetters) }
                    }
                if (setAside != null) {
                    // Not committed, so still ours to count: go back to the start of the batch and try again later.
                    records.partitions().forEach { consumer.seek(it, records.records(it).first().offset()) }
                    log.warn("Retrying the batch in {}: {}", backoff, setAside.message)
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
                    continue
                }
            }
            backoff = MIN_BACKOFF
            commit(consumer)
        }
    }

    private fun read(records: ConsumerRecords<String, String>): List<Read> {
        val readable =
            records.mapNotNull { record ->
                val click = Click.fromJson(record.value())
                if (click == null) log.warn("Skipping unreadable click at {}", record.offset())
                click?.let { Read(record, it) }
            }
        unreadable.increment((records.count() - readable.size).toDouble())
        return readable
    }

    private suspend fun count(batch: List<Read>) {
        if (batch.isEmpty()) return
        val started = System.nanoTime()
        val fresh = store.ingest(batch.map { it.click })
        batchTimer.record(Duration.ofNanos(System.nanoTime() - started))
        ingested.increment(fresh.toDouble())
        duplicates.increment((batch.size - fresh).toDouble())
    }

    /**
     * Counts the batch one click at a time. A click that fails on its own, for a reason that won't change, is
     * sent to the dead-letter topic as it arrived, with the reason in a header, and counted in `clicks.dropped`.
     * A transient failure, or the dead-letter send failing, is thrown so the whole batch is retried later.
     */
    private suspend fun quarantine(
        batch: List<Read>,
        deadLetters: KafkaProducer<String, String>,
    ) {
        for (entry in batch) {
            val failure = attempt { count(listOf(entry)) } ?: continue
            if (failure.isTransient()) throw failure
            val reason = failure.toString()
            log.warn(
                "Click {} for /{} can't be counted, moving it to {}: {}",
                entry.click.id,
                entry.click.slug,
                deadLetterTopic,
                reason,
            )
            val record = ProducerRecord(deadLetterTopic, entry.record.key(), entry.record.value())
            record.headers().add(REASON_HEADER, reason.take(REASON_CHARS).encodeToByteArray())
            runInterruptible { deadLetters.send(record).get() }
            poison.increment()
        }
    }

    private fun commit(consumer: KafkaConsumer<String, String>) {
        try {
            consumer.commitSync()
        } catch (e: CommitFailedException) {
            // The partitions moved to another member mid-batch. It reads the batch again, and the ids are skipped.
            log.info("Offsets not committed after a rebalance; the batch will be recognised as counted: {}", e.message)
        } catch (e: RebalanceInProgressException) {
            log.info("Offsets not committed during a rebalance; the batch will be recognised as counted: {}", e.message)
        }
    }

    /** Runs [block] and returns what it threw, if anything. Cancellation is never caught. */
    private inline fun attempt(block: () -> Unit): Exception? =
        try {
            block()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }

    /** A click as it came off the topic, kept with its record so it can be forwarded unchanged. */
    private class Read(
        val record: ConsumerRecord<String, String>,
        val click: Click,
    )

    companion object {
        const val GROUP = "rollup"

        /** Header on a dead-lettered click: why it couldn't be counted. */
        const val REASON_HEADER = "reason"

        private const val REASON_CHARS = 500
        private const val BATCH_SIZE = 1_000
        private val POLL_TIMEOUT = Duration.ofSeconds(1)
        private val DEAD_LETTER_WAIT = Duration.ofSeconds(10)
        private val MIN_BACKOFF = 1.seconds
        private val MAX_BACKOFF = 30.seconds
    }
}
