package dev.placeholder.server.infra

import dev.placeholder.server.account.AccountService
import dev.placeholder.server.snip.SnipService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Hourly housekeeping: deletes expired snips and sessions. A Redis lock means one instance does it per hour;
 * without Redis every instance runs it, which is harmless because the deletes are idempotent.
 */
class Maintenance(
    private val snips: SnipService,
    private val accounts: AccountService,
    private val redis: Redis?,
) {
    private val log = LoggerFactory.getLogger(Maintenance::class.java)

    fun start(scope: CoroutineScope): Job = scope.launch {
        delay(1.minutes)
        while (isActive) {
            runOnce()
            delay(1.hours)
        }
    }

    suspend fun runOnce() {
        // Another instance holds this hour's lock: it's doing the work. No Redis (null): run anyway, deletes are idempotent.
        if (redis?.setIfAbsent(LOCK, LOCK_SECONDS, "1") == false) return
        try {
            val removedSnips = snips.deleteExpired()
            val removedSessions = accounts.cleanUp()
            if (removedSnips + removedSessions > 0) log.info("Cleaned up {} expired snips and {} sessions", removedSnips, removedSessions)
        } catch (e: Exception) {
            log.warn("Cleanup failed, will retry next hour", e)
        }
    }

    private companion object {
        const val LOCK = "lock:maintenance"
        const val LOCK_SECONDS = 55 * 60L
    }
}
