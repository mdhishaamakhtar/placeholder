package dev.placeholder.web.components

import androidx.compose.runtime.Composable
import dev.placeholder.shared.Messages
import dev.placeholder.web.ApiException
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Text

/** Where a page that shows one thing from the API is: still asking, nothing there, gone, failed, or ready. */
sealed interface Load<out T> {
    data object Pending : Load<Nothing>

    data object Missing : Load<Nothing>

    data object Expired : Load<Nothing>

    data class Failed(
        val message: String,
    ) : Load<Nothing>

    data class Ready<T>(
        val value: T,
    ) : Load<T>
}

/** Runs [fetch] and sorts what came of it: null is [Load.Missing], a 410 is [Load.Expired], any other API failure is [Load.Failed]. */
suspend fun <T : Any> load(fetch: suspend () -> T?): Load<T> =
    try {
        fetch()?.let { Load.Ready(it) } ?: Load.Missing
    } catch (e: ApiException) {
        if (e.isExpired) Load.Expired else Load.Failed(e.message ?: Messages.SERVER_ERROR)
    }

/**
 * The page for whatever [state] is in. Waiting, expired and failed look the same on every page; what they say
 * and where they lead differs, so the page supplies the words and the way out. Nothing there, and the thing
 * itself, are the page's own.
 */
@Composable
fun <T> LoadedPage(
    state: Load<T>,
    title: String?,
    expiredSub: String,
    expiredAction: @Composable () -> Unit,
    failedSub: String,
    onRetry: () -> Unit,
    missing: @Composable () -> Unit,
    ready: @Composable (T) -> Unit,
) {
    when (state) {
        Load.Pending -> {
            Shell(title = title) { Loading() }
        }

        Load.Missing -> {
            missing()
        }

        Load.Expired -> {
            Shell(title = "Expired") {
                StateScreen(lead = Messages.EXPIRED, sub = expiredSub, actions = expiredAction)
            }
        }

        is Load.Failed -> {
            Shell(title = "Unavailable") {
                StateScreen(lead = state.message, sub = failedSub) {
                    Button({
                        classes("act")
                        attr("type", "button")
                        onClick { onRetry() }
                    }) { Text("Try again") }
                }
            }
        }

        is Load.Ready -> {
            ready(state.value)
        }
    }
}
