package dev.placeholder.server.infra

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisException
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Thin coroutine wrapper over one shared Lettuce connection (Lettuce multiplexes, so one is enough per process).
 * Redis is an accelerator, never a dependency: Redis failures and slow calls return null so the app keeps
 * serving from Postgres. Coroutine cancellation is deliberately not caught.
 *
 * If Redis isn't up when the app starts (both are often started together), the app starts anyway and keeps trying
 * in the background. Once connected, Lettuce reconnects on its own after any later drop, and resubscribes.
 */
class Redis(
    private val url: String,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(Redis::class.java)
    private val client = RedisClient.create(url)
    private val connector = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var connection: StatefulRedisConnection<String, String>? = null

    @Volatile private var commands: RedisAsyncCommands<String, String>? = null

    @Volatile private var pubSub: StatefulRedisPubSubConnection<String, String>? = null

    /** Channels and their handlers, kept so they can be subscribed whenever the connection arrives. */
    private val subscriptions = ConcurrentHashMap<String, (String) -> Unit>()

    init {
        if (!connect()) {
            log.warn("Redis unavailable at {}, continuing without it and retrying every {}", url, RETRY_EVERY)
            connector.launch {
                while (isActive && !connect()) delay(RETRY_EVERY)
            }
        }
    }

    /** One attempt to connect. On success, everything already asked for ([subscribe]) is set up too. */
    @Synchronized
    private fun connect(): Boolean {
        val conn =
            try {
                client.connect()
            } catch (e: RedisException) {
                log.debug("Redis connection failed: {}", e.message)
                return false
            }
        connection = conn
        commands = conn.async()
        if (subscriptions.isNotEmpty()) openPubSub()
        log.info("Connected to Redis")
        return true
    }

    private suspend fun <T> call(block: (RedisAsyncCommands<String, String>) -> CompletionStage<T>): T? {
        val cmd = commands ?: return null
        return try {
            withTimeoutOrNull(TIMEOUT) { block(cmd).await() }
        } catch (e: RedisException) {
            log.debug("Redis call failed: {}", e.message)
            null
        }
    }

    /** Like [call], but tells "Redis answered nothing" (a missing key, a refused NX) apart from "Redis didn't answer". */
    private suspend fun <T> runCatchingRedis(
        block: (RedisAsyncCommands<String, String>) -> CompletionStage<T>,
    ): Reply<T>? {
        val cmd = commands ?: return null
        return try {
            withTimeoutOrNull(TIMEOUT) { Reply(block(cmd).await()) }
        } catch (e: RedisException) {
            log.debug("Redis call failed: {}", e.message)
            null
        }
    }

    private class Reply<T>(
        val value: T?,
    )

    suspend fun get(key: String): String? = call { it.get(key) }

    suspend fun setEx(
        key: String,
        seconds: Long,
        value: String,
    ) {
        call { it.set(key, value, SetArgs.Builder.ex(seconds)) }
    }

    /**
     * Sets [key] to [value], a JSON object with a `version`, unless Redis already holds a newer version of it. An
     * older copy read before an edit can then never replace the edited one.
     */
    suspend fun setExIfNotOlder(
        key: String,
        seconds: Long,
        version: Int,
        value: String,
    ) {
        call {
            it.eval<Long>(
                SET_IF_NOT_OLDER,
                ScriptOutputType.INTEGER,
                arrayOf(key),
                version.toString(),
                value,
                seconds.toString(),
            )
        }
    }

    /** Sets the key only if nobody holds it: true if we set it, false if it's taken, null if Redis is unavailable. */
    suspend fun setIfAbsent(
        key: String,
        seconds: Long,
        value: String,
    ): Boolean? {
        if (commands == null) return null
        val reply = runCatchingRedis { it.set(key, value, SetArgs.Builder.nx().ex(seconds)) } ?: return null
        return reply.value == "OK"
    }

    suspend fun delete(key: String) {
        call { it.del(key) }
    }

    suspend fun publish(
        channel: String,
        message: String,
    ) {
        call { it.publish(channel, message) }
    }

    /**
     * Calls [onMessage] for every message on [channel], on a Lettuce thread. Keep the handler quick. If Redis isn't
     * connected yet, the subscription starts as soon as it is.
     */
    @Synchronized
    fun subscribe(
        channel: String,
        onMessage: (String) -> Unit,
    ) {
        subscriptions[channel] = onMessage
        if (connection == null) return
        val conn = pubSub ?: return openPubSub()
        try {
            conn.sync().subscribe(channel)
        } catch (e: RedisException) {
            log.warn("Couldn't subscribe to {}: {}", channel, e.message)
        }
    }

    /** Opens the one pub/sub connection and subscribes to every channel asked for so far. Called under the lock. */
    private fun openPubSub() {
        try {
            val conn = client.connectPubSub()
            conn.addListener(
                object : RedisPubSubAdapter<String, String>() {
                    override fun message(
                        channel: String,
                        message: String,
                    ) {
                        subscriptions[channel]?.invoke(message)
                    }
                },
            )
            conn.sync().subscribe(*subscriptions.keys.toTypedArray())
            pubSub = conn
        } catch (e: RedisException) {
            log.warn("Couldn't subscribe to {}: {}", subscriptions.keys, e.message)
        }
    }

    /**
     * Increments a counter, starting its expiry on first use, in one step: the counter can never be left behind
     * without an expiry. Returns null when Redis is unavailable.
     */
    suspend fun incrementWindow(
        key: String,
        windowSeconds: Long,
    ): Long? =
        call { it.eval<Long>(INCREMENT_WINDOW, ScriptOutputType.INTEGER, arrayOf(key), windowSeconds.toString()) }

    suspend fun ping(): Boolean = call { it.ping() } == "PONG"

    override fun close() {
        connector.cancel()
        pubSub?.close()
        connection?.close()
        client.shutdown()
    }

    private companion object {
        val TIMEOUT = 50.milliseconds
        val RETRY_EVERY = 5.seconds

        const val INCREMENT_WINDOW =
            """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return count
            """

        // What's held may be the "missing" marker rather than JSON, so decoding is allowed to fail.
        const val SET_IF_NOT_OLDER =
            """
            local held = redis.call('GET', KEYS[1])
            if held then
              local ok, decoded = pcall(cjson.decode, held)
              if ok and type(decoded) == 'table' and tonumber(decoded.version or 1) > tonumber(ARGV[1]) then
                return 0
              end
            end
            redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
            return 1
            """
    }
}
