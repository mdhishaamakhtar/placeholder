package dev.placeholder.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RulesTest {
    @Test
    fun singleUrlIsALink() {
        assertEquals("https://github.com/x/y", Rules.asLink("  https://github.com/x/y \n"))
    }

    @Test
    fun urlInsideTextIsNotALink() {
        assertNull(Rules.asLink("see https://github.com/x/y"))
        assertNull(Rules.asLink("https://a.com\nhttps://b.com"))
    }

    @Test
    fun nonHttpSchemesAreRejected() {
        assertNull(Rules.asLink("javascript:alert(1)"))
        assertNull(Rules.asLink("ftp://example.com/file"))
    }

    @Test
    fun slugValidation() {
        assertTrue(Rules.isSlug("x7Kq2Ab"))
        assertFalse(Rules.isSlug("x7kq2"))
        assertFalse(Rules.isSlug("x7kq2l0"))
        assertFalse(Rules.isSlug("web.js!"))
    }

    @Test
    fun lineCounting() {
        assertEquals(0, Rules.lineCount(""))
        assertEquals(1, Rules.lineCount("a"))
        assertEquals(2, Rules.lineCount("a\nb\n"))
    }
}
