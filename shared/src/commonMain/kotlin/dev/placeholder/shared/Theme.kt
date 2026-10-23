package dev.placeholder.shared

/**
 * The site's two themes. The server reads the visitor's choice from [COOKIE] and writes it into the page, so the
 * first paint is already right; the browser switches it and saves it back to the same cookie.
 */
enum class Theme(
    /** The value of the `data-theme` attribute on `<html>`, which styles.css keys on. */
    val attribute: String,
    /** The browser chrome color for `<meta name="theme-color">`: the theme's page color. */
    val chrome: String,
) {
    DARK("dark", "#000000"),
    LIGHT("light", "#FFFFFF"),
    ;

    val other: Theme get() = if (this == DARK) LIGHT else DARK

    companion object {
        const val COOKIE = "theme"
        const val ATTRIBUTE = "data-theme"

        /** The theme a stored value names. Anything else, including nothing at all, is the default: dark. */
        fun from(value: String?): Theme = entries.firstOrNull { it.attribute == value } ?: DARK
    }
}
