package dev.placeholder.server

import dev.placeholder.server.http.readableRoute
import kotlin.test.Test
import kotlin.test.assertEquals

class MetricsTest {
    @Test
    fun regexRoutesGetReadableLabels() {
        assertEquals("/{slug}", readableRoute("/Regex((?<slug>[abcXYZ23]{7}))"))
        assertEquals("/{slug}/og.png", readableRoute("/Regex((?<slug>[abcXYZ23]{7})/og.png)"))
        assertEquals("/api/snips/{slug}/stats", readableRoute("/api/snips/{slug}/stats"))
    }
}
