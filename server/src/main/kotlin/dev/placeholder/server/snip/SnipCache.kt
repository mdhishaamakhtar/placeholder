package dev.placeholder.server.snip

import com.github.benmanes.caffeine.cache.AsyncCache
import com.github.benmanes.caffeine.cache.Caffeine
import dev.placeholder.server.infra.InvalidationBus
import dev.placeholder.server.infra.Redis
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Read-through cache in front of the repository: in-process Caffeine first, then Redis, then Postgres.
 * Anonymous snips never change, so they can be held as long as memory allows. Owned snips can be edited,
 * so every edit evicts them everywhere through the [InvalidationBus].
 *
 * The in-process layer is an async cache, which coalesces concurrent misses: when a link goes viral before it's
 * cached, the first request starts the lookup and every request behind it awaits the same lookup instead of
 * asking Redis and Postgres itself. Lookups run in [scope], not in the request that started them, so one
 * client going away doesn't fail the others. Where each answer came from is counted in `snip.lookups`.
 */
class SnipCache(
    private val repository: SnipRepository,
    private val redis: Redis?,
    private val bus: InvalidationBus,
    registry: MeterRegistry,
    private val scope: CoroutineScope,
) {
    private val fromLocal = lookups(registry, "local")
    private val fromRedis = lookups(registry, "redis")
    private val knownMissing = lookups(registry, "redis_missing")
    private val fromDatabase = lookups(registry, "database")
    private val coalesced = lookups(registry, "coalesced")

    /** A lookup that finds nothing completes with null, which Caffeine doesn't keep: misses go to Redis next time. */
    private val local: AsyncCache<String, Snip?> =
        Caffeine
            .newBuilder()
            .maximumSize(50_000)
            .expireAfterAccess(Duration.ofMinutes(10))
            .buildAsync()

    init {
        bus.listen { topic, key -> if (topic == TOPIC) local.synchronous().invalidate(key) }
    }

    suspend fun find(
        slug: String,
        now: Long,
    ): Snip? {
        var started = false
        val lookup =
            local.get(slug) { key, _ ->
                started = true
                scope.future { load(key, now) }
            }
        if (!started) (if (lookup.isDone) fromLocal else coalesced).increment()
        // Await a copy: cancelling this request cancels what it awaits, and the shared lookup must outlive it.
        return lookup.copy().await()
    }

    private suspend fun load(
        slug: String,
        now: Long,
    ): Snip? {
        when (val cached = redis?.get(key(slug))) {
            MISSING -> {
                knownMissing.increment()
                return null
            }

            null -> {
                Unit
            }

            else -> {
                decode(cached)?.let {
                    fromRedis.increment()
                    return it
                }
            }
        }

        fromDatabase.increment()
        val snip = repository.find(slug)
        if (snip == null) {
            // Short negative cache so a flood of requests for a bad slug doesn't reach the database.
            redis?.setEx(key(slug), MISSING_TTL_SECONDS, MISSING)
        } else {
            share(snip, now)
        }
        return snip
    }

    suspend fun insert(
        snip: Snip,
        now: Long,
    ): Boolean {
        val inserted = repository.insert(snip)
        // Clears a "missing" marker someone may have left by guessing this slug before it existed.
        if (inserted) {
            redis?.delete(key(snip.slug))
            local.put(snip.slug, CompletableFuture.completedFuture(snip))
            share(snip, now)
        }
        return inserted
    }

    /** Call after any change to a stored snip. */
    suspend fun evict(slug: String) {
        redis?.delete(key(slug))
        bus.invalidate(TOPIC, slug)
    }

    /** Puts [snip] in Redis for the other instances, until it expires or for a day, whichever is sooner. */
    private suspend fun share(
        snip: Snip,
        now: Long,
    ) {
        val untilExpiry = snip.expiresAt?.let { (it - now) / 1000 }
        val ttl = minOf(HIT_TTL_SECONDS, untilExpiry ?: HIT_TTL_SECONDS)
        if (ttl > 0) redis?.setEx(key(snip.slug), ttl, Json.encodeToString(Snip.serializer(), snip))
    }

    private fun decode(value: String): Snip? =
        try {
            Json.decodeFromString(Snip.serializer(), value)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun key(slug: String) = "snip:v2:$slug"

    private companion object {
        const val TOPIC = "snip"
        const val MISSING = "\u0000"
        const val MISSING_TTL_SECONDS = 60L
        const val HIT_TTL_SECONDS = 24 * 60 * 60L

        fun lookups(
            registry: MeterRegistry,
            layer: String,
        ): Counter = Counter.builder("snip.lookups").tag("layer", layer).register(registry)
    }
}
