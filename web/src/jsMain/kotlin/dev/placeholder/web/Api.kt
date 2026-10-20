package dev.placeholder.web

import dev.placeholder.shared.ApiError
import dev.placeholder.shared.CreateSnipRequest
import dev.placeholder.shared.CreateSnipResponse
import dev.placeholder.shared.Credentials
import dev.placeholder.shared.Me
import dev.placeholder.shared.PrettifyRequest
import dev.placeholder.shared.PrettifyResponse
import dev.placeholder.shared.SnipDto
import dev.placeholder.shared.SnipPage
import dev.placeholder.shared.UpdateSnipRequest
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import kotlin.js.json

/** A failure with a sentence that is safe and useful to show the person. */
class ApiException(val status: Int, message: String) : RuntimeException(message)

object Api {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun create(content: String, language: String?, expiresInSeconds: Long?): CreateSnipResponse =
        send("POST", "/api/snips", json.encodeToString(CreateSnipRequest.serializer(), CreateSnipRequest(content, language, expiresInSeconds)))
            .let { json.decodeFromString(CreateSnipResponse.serializer(), it) }

    suspend fun update(slug: String, content: String, language: String?): SnipDto =
        send("PUT", "/api/snips/$slug", json.encodeToString(UpdateSnipRequest.serializer(), UpdateSnipRequest(content, language)))
            .let { json.decodeFromString(SnipDto.serializer(), it) }

    suspend fun mySnips(before: String?): SnipPage =
        get("/api/me/snips" + (before?.let { "?before=$it" } ?: "")).let { json.decodeFromString(SnipPage.serializer(), it) }

    suspend fun signUp(email: String, password: String): Me =
        send("POST", "/api/account/signup", json.encodeToString(Credentials.serializer(), Credentials(email, password)))
            .let { json.decodeFromString(Me.serializer(), it) }

    suspend fun signIn(email: String, password: String): Me =
        send("POST", "/api/account/signin", json.encodeToString(Credentials.serializer(), Credentials(email, password)))
            .let { json.decodeFromString(Me.serializer(), it) }

    suspend fun signOut() {
        send("POST", "/api/account/signout", "{}")
    }

    /** Null when nobody is signed in. */
    suspend fun me(): Me? {
        val response = request { window.fetch("/api/account/me").await() }
        if (response.status.toInt() == 204) return null
        return json.decodeFromString(Me.serializer(), response.ensureOk())
    }

    suspend fun prettify(content: String, language: String): PrettifyResponse =
        send("POST", "/api/prettify", json.encodeToString(PrettifyRequest.serializer(), PrettifyRequest(content, language)))
            .let { json.decodeFromString(PrettifyResponse.serializer(), it) }

    /** Returns null when nothing lives at the slug. Expired links throw an [ApiException] with status 410. */
    suspend fun snip(slug: String): SnipDto? {
        val response = request { window.fetch("/api/snips/$slug").await() }
        if (response.status.toInt() == 404) return null
        return json.decodeFromString(SnipDto.serializer(), response.ensureOk())
    }

    private suspend fun get(path: String): String = request { window.fetch(path).await() }.ensureOk()

    private suspend fun send(method: String, path: String, body: String): String {
        val response = request {
            window.fetch(
                path,
                RequestInit(method = method, headers = json("Content-Type" to "application/json"), body = body),
            ).await()
        }
        return response.ensureOk()
    }

    private suspend fun request(block: suspend () -> Response): Response = try {
        block()
    } catch (e: Throwable) {
        throw ApiException(0, "Couldn't reach the server. Check your connection and try again.")
    }

    private suspend fun Response.ensureOk(): String {
        val text = text().await()
        if (ok) return text
        val message = runCatching { json.decodeFromString(ApiError.serializer(), text).message }.getOrNull()
            ?: "Something broke on our side. Try again in a moment."
        throw ApiException(status.toInt(), message)
    }
}
