package dev.placeholder.web

import kotlinx.browser.window

/** A keyboard shortcut, named the way the visitor's platform names it, and the way assistive tech expects it. */
data class Shortcut(val label: String, val aria: String)

private val isApple: Boolean = Regex("Mac|iPhone|iPad").containsMatchIn(window.navigator.userAgent)

/** Saving: Command-Enter on Apple devices, Control-Enter elsewhere. */
val submitShortcut: Shortcut = if (isApple) Shortcut("⌘ Enter", "Meta+Enter") else Shortcut("Ctrl Enter", "Control+Enter")

fun isSubmitShortcut(key: String, meta: Boolean, ctrl: Boolean): Boolean = key == "Enter" && (meta || ctrl)
