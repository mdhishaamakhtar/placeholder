package dev.placeholder.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.placeholder.shared.Me
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Who's signed in, shared by every page. It belongs to the whole app, so it runs in the app's own scope rather than
 * any one component's: no recomposition or page change can cancel the first check halfway.
 */
object Session {
    private val scope = MainScope()

    var me: Me? by mutableStateOf(null)
        private set

    /** False until the first "who am I?" has answered. Pages wait on this instead of guessing. */
    var ready: Boolean by mutableStateOf(false)
        private set

    /** Asks the server who's signed in. Called once at startup; calling it again starts a fresh check. */
    fun start() {
        scope.launch {
            try {
                me = Api.me()
            } catch (_: ApiException) {
                // Unreachable server: treat as signed out; pages show their own errors when they call the API.
                me = null
            } finally {
                ready = true
            }
        }
    }

    suspend fun signIn(email: String, password: String) {
        me = Api.signIn(email, password)
    }

    suspend fun signUp(email: String, password: String) {
        me = Api.signUp(email, password)
    }

    suspend fun signOut() {
        try {
            Api.signOut()
        } finally {
            me = null
        }
    }
}
