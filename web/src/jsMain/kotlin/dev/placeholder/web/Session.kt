package dev.placeholder.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.placeholder.shared.Me

/** Who's signed in, shared by every page. Loaded once when the app starts. */
object Session {
    var me: Me? by mutableStateOf(null)
    var ready: Boolean by mutableStateOf(false)
        private set

    suspend fun load() {
        me = try {
            Api.me()
        } catch (_: ApiException) {
            null
        }
        ready = true
    }

    suspend fun signOut() {
        try {
            Api.signOut()
        } finally {
            me = null
        }
    }
}
