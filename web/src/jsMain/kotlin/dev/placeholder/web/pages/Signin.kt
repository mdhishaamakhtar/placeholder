package dev.placeholder.web.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.varabyte.kobweb.core.Page
import com.varabyte.kobweb.core.rememberPageContext
import com.varabyte.kobweb.navigation.Anchor
import dev.placeholder.shared.Rules
import dev.placeholder.web.Api
import dev.placeholder.web.ApiException
import dev.placeholder.web.Session
import dev.placeholder.web.components.Shell
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

@Page
@Composable
fun SigninPage() {
    val ctx = rememberPageContext()
    val creating = ctx.route.params["mode"] == "create"
    // Only follow plain same-site paths after signing in, never a full URL someone put in the link.
    val next = ctx.route.params["next"]?.takeIf { SAFE_NEXT.matches(it) } ?: DEFAULT_NEXT
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember(creating) { mutableStateOf<String?>(null) }

    fun submit() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                Session.me = if (creating) Api.signUp(email, password) else Api.signIn(email, password)
                ctx.router.navigateTo(next)
            } catch (e: ApiException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    Shell(title = if (creating) "Create an account" else "Sign in") {
        Div({ classes("state") }) {
            Form(attrs = {
                classes("auth")
                addEventListener("submit") {
                    it.preventDefault()
                    submit()
                }
            }) {
                P({ classes("lead") }) {
                    Text(
                        if (creating) "Create an account to keep your links on every device and edit them later."
                        else "Sign in to see your links on every device.",
                    )
                }
                Label(attrs = { classes("field") }) {
                    Span { Text("Email") }
                    Input(InputType.Email) {
                        value(email)
                        onInput { email = it.value }
                        attr("autocomplete", "email")
                        attr("autocapitalize", "off")
                        attr("required", "")
                        attr("maxlength", Rules.MAX_EMAIL.toString())
                        ref { element ->
                            element.focus()
                            onDispose { }
                        }
                    }
                }
                Label(attrs = { classes("field") }) {
                    Span { Text("Password") }
                    Input(InputType.Password) {
                        value(password)
                        onInput { password = it.value }
                        attr("autocomplete", if (creating) "new-password" else "current-password")
                        attr("required", "")
                        if (creating) attr("minlength", Rules.MIN_PASSWORD.toString())
                        attr("maxlength", Rules.MAX_PASSWORD.toString())
                    }
                    if (creating) Span({ classes("field-hint") }) { Text("At least ${Rules.MIN_PASSWORD} characters.") }
                }
                Div({ classes("auth-actions") }) {
                    Button({
                        classes("button")
                        attr("type", "submit")
                        attr("data-busy", busy.toString())
                        if (busy) disabled()
                    }) {
                        Text(
                            when {
                                busy && creating -> "Creating account"
                                busy -> "Signing in"
                                creating -> "Create account"
                                else -> "Sign in"
                            },
                        )
                    }
                    Anchor(signinPath(create = !creating, next), { classes("act") }) {
                        Text(if (creating) "I have an account" else "Create an account")
                    }
                }
                if (error != null) Div({ classes("notice"); attr("role", "alert") }) { Text(error!!) }
            }
        }
    }
}

private const val DEFAULT_NEXT = "/new"
private val SAFE_NEXT = Regex("^/(?!/)[A-Za-z0-9/_-]*(\\?[A-Za-z0-9=_-]*)?$")

fun signinPath(create: Boolean = false, next: String = DEFAULT_NEXT): String {
    val params = listOfNotNull(
        "mode=create".takeIf { create },
        "next=$next".takeIf { next != DEFAULT_NEXT && SAFE_NEXT.matches(next) },
    )
    return if (params.isEmpty()) "/signin" else "/signin?" + params.joinToString("&")
}
