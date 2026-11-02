package dev.placeholder.web.editor

import dev.placeholder.shared.Rules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The editor's state holder, without a server: what a paste is taken for, and when it may be saved. */
class EditorStateTest {
    private fun editor(text: String): EditorState =
        EditorState().apply {
            edit(text)
            redetect()
        }

    @Test
    fun anEmptyEditorHasNothingToSave() {
        val state = EditorState()
        assertNull(state.detection)
        assertFalse(state.canSave)
    }

    @Test
    fun aUrlOnItsOwnIsALink() {
        val state = editor("  https://example.com/path?q=1  ")
        assertTrue(state.detection?.isLink == true)
        assertEquals("Link", state.detection?.label)
        assertTrue(state.canSave)
    }

    @Test
    fun codeIsDetectedByLanguage() {
        val state = editor("select id, name from users where id = 1")
        assertEquals("sql", state.detection?.language)
        assertFalse(state.detection?.isLink == true)
    }

    @Test
    fun aPickedLanguageOverridesTheGuess() {
        val state = editor("select id from users")
        state.pickLanguage("kotlin")
        assertEquals("kotlin", state.detection?.language)
    }

    @Test
    fun aPickCannotTurnALinkIntoCode() {
        val state = editor("https://example.com")
        state.pickLanguage("kotlin")
        assertTrue(state.detection?.isLink == true)
    }

    @Test
    fun clearingTheTextForgetsThePick() {
        val state = editor("select id from users")
        state.pickLanguage("kotlin")
        state.edit("")
        assertNull(state.detection)
        state.edit("select id from users")
        state.redetect()
        assertEquals("sql", state.detection?.language)
    }

    @Test
    fun theSizeLimitCountsBytesNotCharacters() {
        // Two bytes each in UTF-8: fewer characters than the limit, more bytes.
        val state = editor("é".repeat(Rules.MAX_CONTENT_BYTES / 2 + 1))
        assertTrue(state.text.length < Rules.MAX_CONTENT_BYTES)
        assertTrue(state.tooBig)
        assertFalse(state.canSave)
    }

    @Test
    fun resetStartsOver() {
        val state = editor("select id from users")
        state.pickLanguage("kotlin")
        state.reset()
        assertEquals("", state.text)
        assertNull(state.detection)
        assertEquals(EditorMode.Fresh, state.mode)
        assertNull(state.error)
    }
}
