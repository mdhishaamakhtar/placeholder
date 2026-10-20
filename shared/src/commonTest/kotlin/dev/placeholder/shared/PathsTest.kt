package dev.placeholder.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PathsTest {
    @Test
    fun signInLinksCarryOnlyWhatIsNeeded() {
        assertEquals("/signin", Paths.signIn())
        assertEquals("/signin?mode=create", Paths.signIn(create = true))
        assertEquals("/signin?next=/links", Paths.signIn(next = "/links"))
        assertEquals("/signin?mode=create&next=/new?from=abc", Paths.signIn(create = true, next = "/new?from=abc"))
    }

    @Test
    fun onlySameSitePathsAreFollowedAfterSigningIn() {
        assertTrue(Paths.isSafeNext("/links"))
        assertTrue(Paths.isSafeNext("/new?edit=x7Kq2Ab"))
        assertFalse(Paths.isSafeNext("//evil.example"))
        assertFalse(Paths.isSafeNext("https://evil.example"))
        assertFalse(Paths.isSafeNext("/links?next=https://evil.example"))
    }

    @Test
    fun languagesHaveOneName() {
        assertEquals("Text", Languages.name(null))
        assertEquals("SQL", Languages.name("sql"))
        assertEquals("Elixir", Languages.name("elixir"))
    }
}
