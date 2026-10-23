package dev.placeholder.web

import dev.placeholder.shared.ApiError
import dev.placeholder.shared.CreateSnipRequest
import dev.placeholder.shared.CreateSnipResponse
import dev.placeholder.shared.Credentials
import dev.placeholder.shared.Me
import dev.placeholder.shared.Messages
import dev.placeholder.shared.PrettifyRequest
import dev.placeholder.shared.PrettifyResponse
import dev.placeholder.shared.SnipDto
import dev.placeholder.shared.SnipPage
import dev.placeholder.shared.UpdateSnipRequest
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import kotlin.js.json

/** A failure with a sentence that is safe and useful to show the person. */
class ApiException(val status: Int, message: String) : RuntimeException(message) {
    /** The link existed but has expired (410 Gone). */
    val isExpired: Boolean get() = status == GONE
}

private const val NO_CONTENT = 204
private const val NOT_FOUND = 404
private const val GONE = 410

object Api {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun create(content: String, language: String?, expiresInSeconds: Long?): CreateSnipResponse = send(
        "POST",
        "/api/snips",
        json.encodeToString(CreateSnipRequest.serializer(), CreateSnipRequest(content, language, expiresInSeconds)),
    )
        .let { json.decodeFromString(CreateSnipResponse.serializer(), it) }

    suspend fun update(slug: String, content: String, language: String?): SnipDto = send(
        "PUT",
        "/api/snips/$slug",
        json.encodeToString(UpdateSnipRequest.serializer(), UpdateSnipRequest(content, language)),
    )
        .let { json.decodeFromString(SnipDto.serializer(), it) }

    suspend fun mySnips(before: String?): SnipPage = get(
        "/api/me/snips" + (
            before?.let {
                "?before=$it"
            } ?: ""
            ),
    ).let { json.decodeFromString(SnipPage.serializer(), it) }

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
        if (response.status.toInt() == NO_CONTENT) return null
        return json.decodeFromString(Me.serializer(), response.ensureOk())
    }

    suspend fun prettify(content: String, language: String): PrettifyResponse = send(
        "POST",
        "/api/prettify",
        json.encodeToString(PrettifyRequest.serializer(), PrettifyRequest(content, language)),
    )
        .let { json.decodeFromString(PrettifyResponse.serializer(), it) }

    /** Returns null when nothing lives at the slug. Expired links throw an [ApiException] with status 410. */
    suspend fun snip(slug: String): SnipDto? {
        val response = request { window.fetch("/api/snips/$slug").await() }
        if (response.status.toInt() == NOT_FOUND) return null
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

    /** fetch() rejects only when the request never got an answer (offline, DNS, CORS); that's what this reports. */
    private suspend fun request(block: suspend () -> Response): Response = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw ApiException(0, "Couldn't reach the server. Check your connection and try again.")
    }

    private suspend fun Response.ensureOk(): String {
        val text = text().await()
        if (ok) return text
        val message = try {
            json.decodeFromString(ApiError.serializer(), text).message
        } catch (_: SerializationException) {
            // Not one of our error bodies (a proxy's error page, say): say so plainly instead.
            Messages.SERVER_ERROR
        }
        throw ApiException(status.toInt(), message)
    }
}
