package dev.placeholder.server.infra

import dev.placeholder.server.RateLimitedException

/**
 * Fixed one-minute windows counted in Redis, so every server instance shares the same budget per client.
 * Fails open: if Redis is unavailable the request is allowed rather than taking the site down.
 */
class RateLimiter(private val redis: Redis?, private val perMinute: Long) {
    suspend fun check(bucket: String, client: String, limit: Long = perMinute, now: Long = System.currentTimeMillis()) {
        if (redis == null || limit <= 0) return
        val window = now / 60_000
        val count = redis.incrementWindow("rl:$bucket:$client:$window", 61) ?: return
        if (count > limit) throw RateLimitedException(retryAfterSeconds = 60 - (now / 1000) % 60)
    }
}
