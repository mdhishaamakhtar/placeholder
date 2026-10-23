package dev.placeholder.server.infra

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tells every server instance to drop an in-memory cache entry. Each instance keeps its own Caffeine cache,
 * so after an edit or sign-out on one instance, the others must hear about it or they'd keep serving the
 * old value. Messages are "<topic>:<key>", e.g. "snip:x7Kq2Ab" or "session:<hash>".
 */
class InvalidationBus(
    private val redis: Redis?,
) {
    private val listeners = CopyOnWriteArrayList<(topic: String, key: String) -> Unit>()

    init {
        redis?.subscribe(CHANNEL) { message -> dispatch(message) }
    }

    fun listen(listener: (topic: String, key: String) -> Unit) {
        listeners += listener
    }

    /** Evicts locally right away (Redis may be down), then tells the other instances. */
    suspend fun invalidate(
        topic: String,
        key: String,
    ) {
        dispatch("$topic:$key")
        redis?.publish(CHANNEL, "$topic:$key")
    }

    private fun dispatch(message: String) {
        val topic = message.substringBefore(':')
        val key = message.substringAfter(':')
        listeners.forEach { it(topic, key) }
    }

    private companion object {
        const val CHANNEL = "placeholder:invalidate"
    }
}
