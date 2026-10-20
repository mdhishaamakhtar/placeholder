package dev.placeholder.server.http

import dev.placeholder.server.ForbiddenException
import dev.placeholder.server.UnauthorizedException
import dev.placeholder.server.account.AccountService
import dev.placeholder.server.account.SignedIn
import dev.placeholder.server.account.User
import io.ktor.http.Cookie
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.util.AttributeKey
import java.net.URI

const val SESSION_COOKIE = "sid"

private val CurrentUser = AttributeKey<User>("CurrentUser")

class SessionAuthConfig {
    lateinit var accounts: AccountService
    lateinit var publicBaseUrl: String
}

/**
 * Resolves the session cookie into a [User] for every request, and blocks cross-site writes:
 * a request that carries our cookie and changes something must come from our own origin.
 * (SameSite=Lax already stops most of these; the Origin check closes the rest.)
 */
val SessionAuth = createApplicationPlugin("SessionAuth", ::SessionAuthConfig) {
    val accounts = pluginConfig.accounts
    val ownOrigin = URI(pluginConfig.publicBaseUrl).let { "${it.scheme}://${it.authority}" }
    val safeMethods = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)

    onCall { call ->
        val token = call.request.cookies[SESSION_COOKIE] ?: return@onCall
        if (call.request.local.method !in safeMethods) {
            val origin = call.request.headers["Origin"]
            if (origin != null && origin != ownOrigin) throw ForbiddenException("That request came from another site.")
        }
        accounts.resolve(token)?.let { call.attributes.put(CurrentUser, it) }
    }
}

val ApplicationCall.user: User? get() = attributes.getOrNull(CurrentUser)

fun ApplicationCall.requireUser(): User = user ?: throw UnauthorizedException("Sign in to do that.")

fun ApplicationCall.setSessionCookie(session: SignedIn, secure: Boolean) {
    response.cookies.append(sessionCookie(session.token, AccountService.SESSION_LIFETIME.seconds.toInt(), secure))
}

fun ApplicationCall.clearSessionCookie(secure: Boolean) {
    response.cookies.append(sessionCookie("", 0, secure))
}

private fun sessionCookie(value: String, maxAge: Int, secure: Boolean) = Cookie(
    name = SESSION_COOKIE,
    value = value,
    maxAge = maxAge,
    path = "/",
    secure = secure,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Lax"),
)
