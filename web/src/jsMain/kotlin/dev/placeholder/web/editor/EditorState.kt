package dev.placeholder.web.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.placeholder.shared.CreateSnipResponse
import dev.placeholder.shared.Rules
import dev.placeholder.shared.SnipKind
import dev.placeholder.web.Api
import dev.placeholder.web.ApiException
import dev.placeholder.web.Detection
import dev.placeholder.web.detect
import dev.placeholder.web.formatLines

/** What the editor is doing: making a fresh link, a copy of another one, or changing one you own. */
sealed interface EditorMode {
    data object Fresh : EditorMode

    data class CopyOf(
        val slug: String,
    ) : EditorMode

    data class Editing(
        val slug: String,
    ) : EditorMode
}

/** What the editor is waiting on. While it waits, the text is read-only, so nothing typed can be overwritten. */
enum class Busy { NONE, LOADING, SAVING, PRETTIFYING }

/** The outcome of saving, for the page to act on (show the new link, or go back to the edited one). */
sealed interface Saved {
    data class Created(
        val link: CreateSnipResponse,
        val summary: String,
    ) : Saved

    data class Updated(
        val slug: String,
    ) : Saved
}

/**
 * Everything the editor knows and does, kept out of the UI: the text, what it was detected as (and any correction),
 * the mode, and the operations that talk to the server. Composables read these properties and call these methods.
 */
class EditorState {
    var text by mutableStateOf("")
        private set
    var mode by mutableStateOf<EditorMode>(EditorMode.Fresh)
        private set
    var busy by mutableStateOf(Busy.NONE)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var expiresIn by mutableStateOf<Long?>(null)

    private var detected by mutableStateOf<Detection?>(null)

    /** The text as it was last loaded or saved: anything else is unsaved work. */
    private var baseline = ""

    /** Set when the person corrects the detected language; `Pick(null)` means they chose plain text. */
    private var picked by mutableStateOf<Pick?>(null)

    /** A correction, and the guess it overrode: once the detector guesses something else, the paste has changed. */
    private data class Pick(
        val language: String?,
        val over: String?,
    )

    /** What the paste is treated as: the detector's guess, unless the person picked something else. */
    val detection: Detection?
        get() {
            val guess = detected ?: return null
            val pick = picked ?: return guess
            return if (guess.isLink) guess else Detection(pick.language, isLink = false)
        }

    val tooBig: Boolean get() = text.encodedLength() > Rules.MAX_CONTENT_BYTES
    val canSave: Boolean get() = detection != null && !tooBig && busy == Busy.NONE

    /** True when leaving now would lose something the person typed or pasted. */
    val hasUnsavedWork: Boolean get() = text.isNotBlank() && text != baseline

    fun edit(newText: String) {
        if (busy != Busy.NONE) return
        text = newText
        error = null
        if (newText.isBlank()) {
            detected = null
            picked = null
        }
    }

    fun pickLanguage(language: String?) {
        picked = Pick(language, over = detected?.language)
    }

    /** Runs detection on the current text. The caller debounces, so a long paste doesn't stall every keystroke. */
    fun redetect() {
        detected = if (text.isBlank()) null else detect(text)
        if (picked?.let { it.over != detected?.language } == true) picked = null
    }

    /** Shows a problem that isn't the result of anything the person did here, like a bad link address. */
    fun complain(message: String) {
        error = message
    }

    /** Puts back work that was set aside (see [hasUnsavedWork]), as unsaved. */
    fun restore(draft: String) {
        text = draft
        redetect()
    }

    /** Starts from an existing snip: your own to change it in place, anyone's to save as a new link. */
    suspend fun startFrom(
        slug: String,
        wantsToEdit: Boolean,
    ) {
        busy = Busy.LOADING
        try {
            val snip = Api.snip(slug)
            when {
                snip == null -> {
                    error = "There's nothing at /$slug to start from."
                }

                snip.kind == SnipKind.LINK -> {
                    error = "/$slug is a short link, so there's no content to edit."
                }

                else -> {
                    text = snip.content
                    baseline = snip.content
                    redetect()
                    val notYours = wantsToEdit && !snip.owned
                    mode = if (wantsToEdit && snip.owned) EditorMode.Editing(slug) else EditorMode.CopyOf(slug)
                    if (notYours) error = "Only the person who made /$slug can change it, so this will be a copy."
                }
            }
        } catch (e: ApiException) {
            error = e.message
        } finally {
            busy = Busy.NONE
        }
    }

    /** Returns what happened, or null if nothing was saved (nothing to save, or the server said no). */
    suspend fun save(): Saved? {
        val current = detection ?: return null
        if (!canSave) return null
        val language = if (current.isLink) null else current.language
        busy = Busy.SAVING
        error = null
        return try {
            when (val m = mode) {
                is EditorMode.Editing -> {
                    Api.update(m.slug, text, language)
                    baseline = text
                    Saved.Updated(m.slug)
                }

                else -> {
                    val created = Saved.Created(Api.create(text, language, expiresIn), summary(current))
                    baseline = text
                    created
                }
            }
        } catch (e: ApiException) {
            error = e.message
            null
        } finally {
            busy = Busy.NONE
        }
    }

    suspend fun prettify(language: String) {
        if (busy != Busy.NONE) return
        busy = Busy.PRETTIFYING
        error = null
        try {
            val result = Api.prettify(text, language)
            if (result.changed) text = result.content
        } catch (e: ApiException) {
            error = e.message
        } finally {
            busy = Busy.NONE
        }
    }

    /** Back to an empty editor, ready for the next link. */
    fun reset() {
        text = ""
        baseline = ""
        detected = null
        picked = null
        error = null
        mode = EditorMode.Fresh
    }

    private fun summary(detection: Detection): String =
        if (detection.isLink) {
            "Link, redirects to ${text.trim().substringAfter("://").take(SUMMARY_URL_CHARS)}"
        } else {
            "${detection.label}, ${linesLabel()}"
        }

    fun linesLabel(): String = formatLines(Rules.lineCount(text.trimEnd()))

    private companion object {
        /** Enough of a long URL to recognise it in the confirmation, short enough to stay on one line. */
        const val SUMMARY_URL_CHARS = 48
    }
}

/** UTF-8 size, which is what the server's limit counts, not the number of characters. */
private fun String.encodedLength(): Int = encodeToByteArray().size
