package dev.placeholder.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Remembers the previous in-app page, so a page can offer a way back to where you came from. */
object Navigation {
    var previous: String? by mutableStateOf(null)
        private set
    private var current: String? = null

    fun visited(path: String) {
        if (path == current) return
        previous = current
        current = path
    }
}
