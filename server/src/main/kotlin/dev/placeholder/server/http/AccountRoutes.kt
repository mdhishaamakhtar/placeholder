package dev.placeholder.server.http

import dev.placeholder.server.account.AccountService
import dev.placeholder.server.infra.RateLimiter
import dev.placeholder.shared.Credentials
import dev.placeholder.shared.Me
import dev.placeholder.shared.Rules
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** Sign up, sign in, sign out, and "who am I". */
fun Route.accountRoutes(accounts: AccountService, limiter: RateLimiter, authPerMinute: Long, secureCookies: Boolean) {
    route("/api/account") {
        post("/signup") {
            limiter.check("signup", call.request.origin.remoteHost, limit = authPerMinute)
            val session = accounts.signUp(call.receive<Credentials>())
            call.setSessionCookie(session, secureCookies)
            call.respond(HttpStatusCode.Created, Me(session.user.email))
        }

        post("/signin") {
            val credentials = call.receive<Credentials>()
            // Two budgets: per IP slows one client spraying many accounts; per email slows guessing one password.
            limiter.check("signin-ip", call.request.origin.remoteHost, limit = authPerMinute * 3)
            limiter.check("signin-email", Rules.normalizeEmail(credentials.email), limit = authPerMinute)
            val session = accounts.signIn(credentials)
            call.setSessionCookie(session, secureCookies)
            call.respond(Me(session.user.email))
        }

        post("/signout") {
            call.request.cookies[SESSION_COOKIE]?.let { accounts.signOut(it) }
            call.clearSessionCookie(secureCookies)
            call.respond(HttpStatusCode.NoContent)
        }

        // Being signed out is a normal state, not an error: answer 204 so browsers don't log a failed request.
        get("/me") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val user = call.user
            if (user == null) call.respond(HttpStatusCode.NoContent) else call.respond(Me(user.email))
        }
    }
}
