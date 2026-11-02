package dev.placeholder.server.infra

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.errors.TopicExistsException
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import java.time.Duration
import java.util.concurrent.ExecutionException

/** Where Kafka is, and how this app's producers and consumers are set up to talk to it. */
class Kafka(
    private val bootstrapServers: String,
    private val replicationFactor: Short,
) {
    /**
     * A producer that, by default, never blocks its caller. Sends are appended to an in-memory buffer and shipped
     * in compressed batches by the client's own thread; if the buffer is full or the topic's partitions aren't
     * known, the send fails at once (through its callback) instead of waiting. A caller that would rather wait
     * than fail, such as the consumer setting a click aside, passes how long with [maxBlock]. Idempotence (on by
     * default) means the client's own retries can't write a message twice.
     */
    fun producer(maxBlock: Duration = Duration.ZERO): KafkaProducer<String, String> =
        KafkaProducer(
            mapOf(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                ProducerConfig.ACKS_CONFIG to "all",
                ProducerConfig.LINGER_MS_CONFIG to 20,
                ProducerConfig.BATCH_SIZE_CONFIG to 64 * 1024,
                ProducerConfig.COMPRESSION_TYPE_CONFIG to "lz4",
                ProducerConfig.MAX_BLOCK_MS_CONFIG to maxBlock.toMillis(),
                // How long a message may wait for Kafka to come back before it's given up on: the outage it rides out.
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to Duration.ofMinutes(2).toMillis().toInt(),
                // Never forget the topic's partitions: a producer that never blocks can't send without them. Kafka 4's
                // default, when no broker answers, is to discard what it knows and bootstrap again, and every click sent
                // during the outage then fails at once instead of waiting in the buffer. Found by bench/chaos.sh.
                ProducerConfig.METADATA_MAX_IDLE_CONFIG to Duration.ofDays(7).toMillis(),
                CommonClientConfigs.METADATA_RECOVERY_STRATEGY_CONFIG to "none",
            ),
            StringSerializer(),
            StringSerializer(),
        )

    /** A member of [group] that commits offsets itself, after the work they cover is done. */
    fun consumer(
        group: String,
        maxRecords: Int,
    ): KafkaConsumer<String, String> =
        KafkaConsumer(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to group,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG to maxRecords,
                // A member that stops heartbeating for this long is presumed dead, and its partitions go to the others.
                // Kafka's default is 45 s; clicks from a crashed server would wait that long to be counted.
                ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG to 10_000,
                ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG to 3_000,
            ),
            StringDeserializer(),
            StringDeserializer(),
        )

    /**
     * Creates [name] unless it exists. Topics are created on purpose, never by a typo in a producer.
     * Throws a [KafkaException] when Kafka can't be reached.
     */
    suspend fun ensureTopic(
        name: String,
        partitions: Int,
    ): Unit =
        withContext(Dispatchers.IO) {
            Admin.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers)).use { admin ->
                try {
                    admin.createTopics(listOf(NewTopic(name, partitions, replicationFactor))).all().get()
                } catch (e: ExecutionException) {
                    when (val cause = e.cause) {
                        is TopicExistsException -> Unit
                        is KafkaException -> throw cause
                        else -> throw KafkaException(cause ?: e)
                    }
                }
            }
        }
}
