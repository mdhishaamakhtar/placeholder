package dev.placeholder.server.snip

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import dev.placeholder.server.infra.InvalidationBus
import dev.placeholder.server.infra.Redis
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Duration

/**
 * Read-through cache in front of the repository: in-process Caffeine first, then Redis, then Postgres.
 * Anonymous snips never change, so they can be held as long as memory allows. Owned snips can be edited,
 * so every edit evicts them everywhere through the [InvalidationBus].
 */
class SnipCache(
    private val repository: SnipRepository,
    private val redis: Redis?,
    private val bus: InvalidationBus,
) {
    private val local: Cache<String, Snip> = Caffeine.newBuilder()
        .maximumSize(50_000)
        .expireAfterAccess(Duration.ofMinutes(10))
        .build()

    init {
        bus.listen { topic, key -> if (topic == TOPIC) local.invalidate(key) }
    }

    suspend fun find(slug: String, now: Long): Snip? {
        local.getIfPresent(slug)?.let { return it }

        when (val cached = redis?.get(key(slug))) {
            MISSING -> return null
            null -> Unit
            else -> decode(cached)?.let {
                local.put(slug, it)
                return it
            }
        }

        val snip = repository.find(slug)
        if (snip == null) {
            // Short negative cache so a flood of requests for a bad slug doesn't reach the database.
            redis?.setEx(key(slug), MISSING_TTL_SECONDS, MISSING)
        } else {
            remember(snip, now)
        }
        return snip
    }

    suspend fun insert(snip: Snip, now: Long): Boolean {
        val inserted = repository.insert(snip)
        // Clears a "missing" marker someone may have left by guessing this slug before it existed.
        if (inserted) {
            redis?.delete(key(snip.slug))
            remember(snip, now)
        }
        return inserted
    }

    /** Call after any change to a stored snip. */
    suspend fun evict(slug: String) {
        redis?.delete(key(slug))
        bus.invalidate(TOPIC, slug)
    }

    private suspend fun remember(snip: Snip, now: Long) {
        local.put(snip.slug, snip)
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
    }
}
