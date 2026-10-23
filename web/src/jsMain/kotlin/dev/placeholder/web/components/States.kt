package dev.placeholder.web.components

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/** Empty and error screens: one plain sentence, a quieter one under it, and what you can do next. */
@Composable
fun StateScreen(lead: String, sub: String? = null, actions: @Composable () -> Unit = {}) {
    Div({ classes("state") }) {
        P({ classes("lead") }) { Text(lead) }
        if (sub != null) P({ classes("sub") }) { Text(sub) }
        Div({ classes("state-actions") }) { actions() }
    }
}

@Composable
fun Loading() {
    // Hidden for the first 300ms by CSS, so fast loads never flash a loading label.
    Div({
        classes("loading")
        attr("role", "status")
    }) { Text("Loading") }
}
