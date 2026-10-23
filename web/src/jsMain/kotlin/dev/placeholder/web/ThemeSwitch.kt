package dev.placeholder.web

import dev.placeholder.shared.Theme
import kotlinx.browser.document
import kotlinx.browser.window

/** Reads and switches the [Theme] in the browser. */
object ThemeSwitch {
    /** The server already wrote the saved theme into <html>, so the page is the source of truth. */
    fun current(): Theme = Theme.from(document.documentElement?.getAttribute(Theme.ATTRIBUTE))

    fun apply(theme: Theme) {
        // A cookie (not localStorage) so the server can render the right theme on the very first byte.
        document.cookie = "${Theme.COOKIE}=${theme.attribute}; path=/; max-age=$ONE_YEAR_SECONDS; samesite=lax"

        // An instant, clean swap. Every element's own color transitions are paused for this one frame;
        // otherwise borders and underlines fade at their own speeds and the page looks half-switched.
        val root = document.documentElement ?: return
        root.classList.add("theme-switching")
        root.setAttribute(Theme.ATTRIBUTE, theme.attribute)
        document.querySelector("meta[name=theme-color]")?.setAttribute("content", theme.chrome)
        window.requestAnimationFrame {
            window.requestAnimationFrame { root.classList.remove("theme-switching") }
        }
    }

    private const val ONE_YEAR_SECONDS = 365 * 24 * 60 * 60
}
