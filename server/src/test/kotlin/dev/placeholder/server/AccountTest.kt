package dev.placeholder.server

import dev.placeholder.server.infra.Database
import dev.placeholder.server.infra.Redis
import dev.placeholder.server.snip.Lookup
import dev.placeholder.shared.ApiError
import dev.placeholder.shared.CreateSnipRequest
import dev.placeholder.shared.CreateSnipResponse
import dev.placeholder.shared.Credentials
import dev.placeholder.shared.Me
import dev.placeholder.shared.SnipDto
import dev.placeholder.shared.SnipPage
import dev.placeholder.shared.UpdateSnipRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountTest {
    private fun withApp(block: suspend ApplicationTestBuilder.(newClient: () -> HttpClient) -> Unit) = testApplication {
        val config = TestEnv.config()
        val components = AppComponents(config, Database(config), Redis(config.redisUrl!!))
        environment { this.config = MapApplicationConfig() }
        application { configure(components) }
        val newClient = {
            createClient {
                install(ContentNegotiation) { json() }
                install(HttpCookies)
            }
        }
        try {
            block(newClient)
        } finally {
            components.close()
        }
    }

    private fun email() = "person-${UUID.randomUUID()}@example.com"

    private suspend fun HttpClient.signUp(email: String, password: String = "correct horse battery"): HttpResponse =
        post("/api/account/signup") {
            contentType(ContentType.Application.Json)
            setBody(Credentials(email, password))
        }

    private suspend fun HttpClient.signIn(email: String, password: String): HttpResponse =
        post("/api/account/signin") {
            contentType(ContentType.Application.Json)
            setBody(Credentials(email, password))
        }

    private suspend fun HttpClient.create(content: String, expiresIn: Long? = null) =
        post("/api/snips") {
            contentType(ContentType.Application.Json)
            setBody(CreateSnipRequest(content, "kotlin", expiresIn))
        }.body<CreateSnipResponse>()

    private suspend fun HttpClient.edit(slug: String, content: String) =
        put("/api/snips/$slug") {
            contentType(ContentType.Application.Json)
            setBody(UpdateSnipRequest(content, "kotlin"))
        }

    @Test
    fun signUpSignInAndSignOut() = withApp { newClient ->
        val client = newClient()
        val address = email()
        assertEquals(HttpStatusCode.Created, client.signUp(address.uppercase()).status)
        assertEquals(address, client.get("/api/account/me").body<Me>().email)

        client.post("/api/account/signout")
        assertEquals(HttpStatusCode.NoContent, client.get("/api/account/me").status)

        assertEquals(HttpStatusCode.OK, client.signIn(address, "correct horse battery").status)
        assertEquals(address, client.get("/api/account/me").body<Me>().email)
    }

    @Test
    fun signOutRevokesTheSessionServerSide() = withApp { newClient ->
        val client = newClient()
        val cookie = client.signUp(email()).headers.getAll(HttpHeaders.SetCookie)!!.first { it.startsWith("sid=") }
        val sid = cookie.substringAfter("sid=").substringBefore(';')
        client.post("/api/account/signout")

        // Replaying the old cookie must not work once signed out.
        val replay = newClient().get("/api/account/me") { header(HttpHeaders.Cookie, "sid=$sid") }
        assertEquals(HttpStatusCode.NoContent, replay.status)
    }

    @Test
    fun rejectsDuplicatesWeakPasswordsAndWrongCredentials() = withApp { newClient ->
        val client = newClient()
        val address = email()
        client.signUp(address)
        assertEquals(HttpStatusCode.Conflict, newClient().signUp(address).status)
        assertEquals(HttpStatusCode.BadRequest, newClient().signUp(email(), "short").status)
        assertEquals(HttpStatusCode.BadRequest, newClient().signUp("not-an-email").status)

        val wrongPassword = newClient().signIn(address, "wrong password!!")
        val unknownEmail = newClient().signIn(email(), "wrong password!!")
        assertEquals(HttpStatusCode.Unauthorized, wrongPassword.status)
        // Same answer either way, so nobody can find out which emails have accounts.
        assertEquals(wrongPassword.body<ApiError>(), unknownEmail.body<ApiError>())
    }

    @Test
    fun ownersSeeTheirLinksAndCanEditThem() = withApp { newClient ->
        val owner = newClient().also { it.signUp(email()) }
        val created = owner.create("val x = 1")

        assertTrue(owner.get("/api/snips/${created.slug}").body<SnipDto>().owned)
        assertFalse(newClient().get("/api/snips/${created.slug}").body<SnipDto>().owned)
        assertEquals(listOf(created.slug), owner.get("/api/me/snips").body<SnipPage>().items.map { it.slug })

        assertEquals(HttpStatusCode.OK, owner.edit(created.slug, "val x = 2").status)
        // Read through every cache layer: the edit must be visible immediately.
        assertEquals("val x = 2", newClient().get("/api/snips/${created.slug}").body<SnipDto>().content)
    }

    @Test
    fun onlyTheOwnerCanEdit() = withApp { newClient ->
        val owner = newClient().also { it.signUp(email()) }
        val stranger = newClient().also { it.signUp(email()) }
        val owned = owner.create("val x = 1")
        // Links made before accounts existed have no owner; nobody may edit those.
        val config = TestEnv.config()
        val legacy = AppComponents(config, Database(config), null)
        val anonymous = runBlocking { legacy.snips.create(CreateSnipRequest("val y = 1", "kotlin"), null) }
        legacy.close()

        assertEquals(HttpStatusCode.Forbidden, stranger.edit(owned.slug, "hacked").status)
        assertEquals(HttpStatusCode.Forbidden, owner.edit(anonymous.slug, "hacked").status)
        assertEquals(HttpStatusCode.Unauthorized, newClient().edit(owned.slug, "hacked").status)
        assertEquals("val x = 1", newClient().get("/api/snips/${owned.slug}").body<SnipDto>().content)
    }

    @Test
    fun crossSiteWritesWithACookieAreBlocked() = withApp { newClient ->
        val client = newClient().also { it.signUp(email()) }
        val created = client.create("val x = 1")
        val response = client.put("/api/snips/${created.slug}") {
            header(HttpHeaders.Origin, "https://evil.example")
            contentType(ContentType.Application.Json)
            setBody(UpdateSnipRequest("hacked", "kotlin"))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        val sameSite = client.edit(created.slug, "val x = 2")
        assertEquals(HttpStatusCode.OK, sameSite.status)
    }

    @Test
    fun expiredLinksAreGone() {
        val config = TestEnv.config()
        val components = AppComponents(config, Database(config), Redis(config.redisUrl!!))
        val twoHoursAgo = System.currentTimeMillis() - 2 * 60 * 60 * 1000
        val snip = runBlocking {
            components.snips.create(CreateSnipRequest("https://example.com", null, 3600), null, now = twoHoursAgo)
        }
        components.close()

        withApp { newClient ->
            val client = newClient()
            assertEquals(HttpStatusCode.Gone, client.get("/api/snips/${snip.slug}").status)
            assertEquals(HttpStatusCode.Gone, client.get("/${snip.slug}").status)
        }
    }

    @Test
    fun anEditOnOneInstanceReachesTheOthers() = runBlocking {
        val config = TestEnv.config()
        val a = AppComponents(config, Database(config), Redis(config.redisUrl!!))
        val b = AppComponents(config, Database(config), Redis(config.redisUrl))
        try {
            val user = a.accounts.signUp(Credentials(email(), "correct horse battery")).user
            val snip = a.snips.create(CreateSnipRequest("v1", "kotlin"), user)
            // Warm instance B's in-memory cache with the old version.
            assertEquals("v1", (b.snips.lookup(snip.slug) as Lookup.Found).snip.content)

            a.snips.update(snip.slug, UpdateSnipRequest("v2", "kotlin"), user)
            delay(300) // pub/sub delivery
            assertEquals("v2", (b.snips.lookup(snip.slug) as Lookup.Found).snip.content)
        } finally {
            a.close()
            b.close()
        }
    }
}
