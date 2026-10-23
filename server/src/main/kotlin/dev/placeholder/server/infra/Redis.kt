package dev.placeholder.server.infra

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisException
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletionStage
import kotlin.time.Duration.Companion.milliseconds

/**
 * Thin coroutine wrapper over one shared Lettuce connection (Lettuce multiplexes, so one is enough per process).
 * Redis is an accelerator, never a dependency: Redis failures and slow calls return null so the app keeps
 * serving from Postgres. Coroutine cancellation is deliberately not caught.
 */
class Redis(url: String) : AutoCloseable {
    private val log = LoggerFactory.getLogger(Redis::class.java)
    private val client = RedisClient.create(url)
    private val connection: StatefulRedisConnection<String, String>? = try {
        client.connect()
    } catch (e: RedisException) {
        log.warn("Redis unavailable at {}, continuing without it: {}", url, e.message)
        null
    }
    private val commands: RedisAsyncCommands<String, String>? = connection?.async()
    private var pubSub: StatefulRedisPubSubConnection<String, String>? = null

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

    private class Reply<T>(val value: T?)

    suspend fun get(key: String): String? = call { it.get(key) }

    suspend fun setEx(key: String, seconds: Long, value: String) {
        call { it.set(key, value, SetArgs.Builder.ex(seconds)) }
    }

    /** Sets the key only if nobody holds it: true if we set it, false if it's taken, null if Redis is unavailable. */
    suspend fun setIfAbsent(key: String, seconds: Long, value: String): Boolean? {
        if (commands == null) return null
        val reply = runCatchingRedis { it.set(key, value, SetArgs.Builder.nx().ex(seconds)) } ?: return null
        return reply.value == "OK"
    }

    suspend fun delete(key: String) {
        call { it.del(key) }
    }

    suspend fun publish(channel: String, message: String) {
        call { it.publish(channel, message) }
    }

    /** Calls [onMessage] for every message on [channel], on a Lettuce thread. Keep the handler quick. */
    fun subscribe(channel: String, onMessage: (String) -> Unit) {
        if (connection == null) return
        try {
            val conn = client.connectPubSub()
            conn.addListener(object : RedisPubSubAdapter<String, String>() {
                override fun message(ch: String, message: String) {
                    if (ch == channel) onMessage(message)
                }
            })
            conn.sync().subscribe(channel)
            pubSub = conn
        } catch (e: RedisException) {
            log.warn("Couldn't subscribe to {}: {}", channel, e.message)
        }
    }

    /** Increments a counter and starts its expiry on first use. Returns null when Redis is unavailable. */
    suspend fun incrementWindow(key: String, windowSeconds: Long): Long? {
        val count = call { it.incr(key) } ?: return null
        if (count == 1L) call { it.expire(key, windowSeconds) }
        return count
    }

    suspend fun ping(): Boolean = call { it.ping() } == "PONG"

    override fun close() {
        pubSub?.close()
        connection?.close()
        client.shutdown()
    }

    private companion object {
        val TIMEOUT = 50.milliseconds
    }
}
