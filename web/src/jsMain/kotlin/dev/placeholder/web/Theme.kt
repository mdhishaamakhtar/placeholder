package dev.placeholder.web

import kotlinx.browser.document
import kotlinx.browser.window

enum class Theme(val attribute: String, val chrome: String) {
    DARK("dark", "#000000"),
    LIGHT("light", "#FFFFFF");

    val other: Theme get() = if (this == DARK) LIGHT else DARK

    companion object {
        /** The server already wrote the saved theme into <html>, so the page is the source of truth. */
        fun current(): Theme =
            if (document.documentElement?.getAttribute("data-theme") == LIGHT.attribute) LIGHT else DARK

        fun apply(theme: Theme) {
            // A cookie (not localStorage) so the server can render the right theme on the very first byte.
            document.cookie = "theme=${theme.attribute}; path=/; max-age=31536000; samesite=lax"

            // An instant, clean swap. Every element's own color transitions are paused for this one frame;
            // otherwise borders and underlines fade at their own speeds and the page looks half-switched.
            val root = document.documentElement ?: return
            root.classList.add("theme-switching")
            root.setAttribute("data-theme", theme.attribute)
            document.querySelector("meta[name=theme-color]")?.setAttribute("content", theme.chrome)
            window.requestAnimationFrame {
                window.requestAnimationFrame { root.classList.remove("theme-switching") }
            }
        }
    }
}
