package dev.placeholder.server.infra

import dev.placeholder.server.RateLimitedException

/**
 * Fixed one-minute windows counted in Redis, so every server instance shares the same budget per client.
 * Fails open: if Redis is unavailable the request is allowed rather than taking the site down.
 */
class RateLimiter(private val redis: Redis?, private val perMinute: Long) {
    suspend fun check(bucket: String, client: String, limit: Long = perMinute, now: Long = System.currentTimeMillis()) {
        if (redis == null || limit <= 0) return
        val window = now / WINDOW_MILLIS
        // The counter outlives its window by a second, so a request at the boundary still finds it.
        val count = redis.incrementWindow("rl:$bucket:$client:$window", WINDOW_SECONDS + 1) ?: return
        val secondsLeft = WINDOW_SECONDS - (now / 1000) % WINDOW_SECONDS
        if (count > limit) throw RateLimitedException(retryAfterSeconds = secondsLeft)
    }

    private companion object {
        const val WINDOW_SECONDS = 60L
        const val WINDOW_MILLIS = WINDOW_SECONDS * 1000
    }
}
