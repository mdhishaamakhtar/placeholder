package dev.placeholder.web

import kotlinx.browser.window
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.w3c.dom.Storage

/**
 * Unsaved editor work, set aside for this tab when the editor goes away before it was saved: a trip to another page,
 * or a session that ran out and needs a sign-in first. The editor takes it back when it opens at the same place.
 */
object Drafts {
    @Serializable
    private data class Draft(
        val place: String,
        val text: String,
    )

    /** Keeps [text] for the editor at [place] (its path and query), replacing whatever was kept before. */
    fun keep(
        place: String,
        text: String,
    ) {
        storage { it.setItem(KEY, Json.encodeToString(Draft.serializer(), Draft(place, text))) }
    }

    /** The text kept for [place], if any. Taking it removes it, so it comes back once. */
    fun take(place: String): String? {
        val raw = storage { it.getItem(KEY) } ?: return null
        val draft =
            try {
                Json.decodeFromString(Draft.serializer(), raw)
            } catch (_: SerializationException) {
                null
            }
        if (draft == null || draft.place == place) storage { it.removeItem(KEY) }
        return draft?.takeIf { it.place == place }?.text
    }

    /**
     * Session storage can be missing or refuse a write (private browsing, storage turned off, over quota). A draft is
     * a courtesy, so when it can't be kept the editor simply carries on without one.
     */
    private fun <T> storage(block: (Storage) -> T): T? =
        try {
            block(window.sessionStorage)
        } catch (_: Throwable) {
            null
        }

    private const val KEY = "placeholder.draft"
}
