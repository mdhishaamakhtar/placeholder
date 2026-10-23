package dev.placeholder.server

import dev.placeholder.server.config.AppConfig
import dev.placeholder.server.infra.Database
import dev.placeholder.server.infra.Redis
import dev.placeholder.shared.ApiError
import dev.placeholder.shared.CreateSnipRequest
import dev.placeholder.shared.CreateSnipResponse
import dev.placeholder.shared.Credentials
import dev.placeholder.shared.PrettifyRequest
import dev.placeholder.shared.PrettifyResponse
import dev.placeholder.shared.Rules
import dev.placeholder.shared.SnipDto
import dev.placeholder.shared.SnipKind
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.lettuce.core.RedisClient
import org.jsoup.Jsoup
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiTest {
    private fun withApp(
        config: AppConfig = TestEnv.config(),
        useRedis: Boolean = true,
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) = testApplication {
        val components =
            AppComponents(config, Database(config), if (useRedis) Redis(config.redisUrl!!) else null, TestEnv.kafka())
        // Use an empty config so the test doesn't also boot the module listed in application.yaml.
        environment { this.config = MapApplicationConfig() }
        application { configure(components) }
        val client = createClient {
            followRedirects = false
            install(ContentNegotiation) { json() }
            install(HttpCookies)
        }
        // Making links needs an account, so every test starts signed in.
        client.post("/api/account/signup") {
            contentType(ContentType.Application.Json)
            setBody(Credentials("api-${UUID.randomUUID()}@example.com", "correct horse battery"))
        }
        try {
            block(client)
        } finally {
            components.close()
        }
    }

    private suspend fun HttpClient.create(content: String, language: String? = null) = post("/api/snips") {
        contentType(ContentType.Application.Json)
        setBody(CreateSnipRequest(content, language))
    }

    @Test
    fun linkRedirects() = withApp { client ->
        val response = client.create("  https://example.com/some/long/path?q=1  ")
        assertEquals(HttpStatusCode.Created, response.status)
        val created = response.body<CreateSnipResponse>()
        assertEquals(SnipKind.LINK, created.kind)
        assertTrue(Rules.isSlug(created.slug))
        assertEquals("http://test.local/${created.slug}", created.url)

        val redirect = client.get("/${created.slug}")
        assertEquals(HttpStatusCode.Found, redirect.status)
        assertEquals("https://example.com/some/long/path?q=1", redirect.headers[HttpHeaders.Location])
    }

    @Test
    fun codeIsStoredAndReadBack() = withApp { client ->
        val sql = "select u.name, count(o.id)\nfrom users u join orders o on o.user_id = u.id\ngroup by u.name;"
        val created = client.create(sql, "sql").body<CreateSnipResponse>()
        assertEquals(SnipKind.CODE, created.kind)

        val snip = client.get("/api/snips/${created.slug}").body<SnipDto>()
        assertEquals(sql, snip.content)
        assertEquals("sql", snip.language)
        assertEquals(3, snip.lines)

        val raw = client.get("/${created.slug}/raw")
        assertEquals(sql, raw.bodyAsText())
        assertEquals(HttpStatusCode.OK, client.get("/${created.slug}").status)
    }

    @Test
    fun javascriptUrlIsTextNotALink() = withApp { client ->
        val created = client.create("javascript:alert(1)").body<CreateSnipResponse>()
        assertEquals(SnipKind.TEXT, created.kind)
        assertEquals(HttpStatusCode.OK, client.get("/${created.slug}").status)
    }

    @Test
    fun blankAndOversizedAreRejected() = withApp { client ->
        assertEquals(HttpStatusCode.BadRequest, client.create("   \n").status)
        val tooBig = "x".repeat(Rules.MAX_CONTENT_BYTES + 1)
        assertEquals(HttpStatusCode.BadRequest, client.create(tooBig).status)
    }

    @Test
    fun unknownSlugIs404() = withApp { client ->
        val response = client.get("/api/snips/abcdefg")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("Nothing lives at this link.", response.body<ApiError>().message)
        assertEquals(HttpStatusCode.NotFound, client.get("/abcdefg").status)
    }

    @Test
    fun prettifiesJsonAndRejectsInvalidJson() = withApp { client ->
        val ok = client.post("/api/prettify") {
            contentType(ContentType.Application.Json)
            setBody(PrettifyRequest("""{"a":1,"b":[1,2]}""", "json"))
        }.body<PrettifyResponse>()
        assertEquals("{\n  \"a\": 1,\n  \"b\": [\n    1,\n    2\n  ]\n}", ok.content)
        assertTrue(ok.changed)

        val bad = client.post("/api/prettify") {
            contentType(ContentType.Application.Json)
            setBody(PrettifyRequest("{nope", "json"))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, bad.status)
    }

    @Test
    fun rateLimitsCreates() = withApp(TestEnv.config(createsPerMinute = 3)) { client ->
        // Other tests share this Redis and client address, so start from an empty counter.
        RedisClient.create(TestEnv.config().redisUrl!!).use {
            it.connect().use { conn -> conn.sync().del(*conn.sync().keys("rl:create:*").toTypedArray()) }
        }
        val statuses = (1..4).map { client.create("note $it").status }
        assertEquals(
            listOf(
                HttpStatusCode.Created,
                HttpStatusCode.Created,
                HttpStatusCode.Created,
                HttpStatusCode.TooManyRequests,
            ),
            statuses,
        )
    }

    @Test
    fun worksWithoutRedis() = withApp(useRedis = false) { client ->
        val created = client.create("https://kotlinlang.org").body<CreateSnipResponse>()
        assertEquals(HttpStatusCode.Found, client.get("/${created.slug}").status)
    }

    @Test
    fun unknownPagesGetTheAppWithA404EvenWhenGzipped() = withApp { client ->
        for (path in listOf("/abcdefg", "/some/where")) {
            val response = client.get(path) {
                header(HttpHeaders.Accept, "text/html")
                header(HttpHeaders.AcceptEncoding, "gzip")
            }
            assertEquals(HttpStatusCode.NotFound, response.status, path)
            assertEquals(
                1,
                response.headers.getAll(HttpHeaders.ContentEncoding)?.size ?: 0,
                "single encoding for $path",
            )
        }
    }

    @Test
    fun themeCookieIsRenderedIntoThePage() = withApp { client ->
        val dark = Jsoup.parse(client.get("/").bodyAsText())
        assertEquals("dark", dark.selectFirst("html")!!.attr("data-theme"))
        assertEquals("#000000", dark.selectFirst("meta[name=theme-color]")!!.attr("content"))

        val light = Jsoup.parse(client.get("/") { header(HttpHeaders.Cookie, "theme=light") }.bodyAsText())
        assertEquals("light", light.selectFirst("html")!!.attr("data-theme"))
        assertEquals("#FFFFFF", light.selectFirst("meta[name=theme-color]")!!.attr("content"))
        assertEquals(1, light.select("title").size)
        assertEquals(1, light.select("meta[name=theme-color]").size)
    }

    @Test
    fun pastedContentCannotEscapeTheMetaTags() = withApp { client ->
        val hostile = "\"><script>alert(1)</script><meta name=\"x"
        val created = client.create(hostile, "html").body<CreateSnipResponse>()
        val page = client.get("/${created.slug}").bodyAsText()
        val doc = Jsoup.parse(page)
        assertTrue(doc.select("script:containsData(alert(1))").isEmpty(), "no script injected")
        assertEquals(hostile, doc.selectFirst("meta[name=description]")!!.attr("content"))
        assertEquals(
            "/${created.slug}/og.png",
            doc.selectFirst("meta[property=og:image]")!!.attr("content").substringAfter("test.local"),
        )
    }

    @Test
    fun creatingNeedsAnAccount() = testApplication {
        val config = TestEnv.config()
        val components = AppComponents(config, Database(config), null, TestEnv.kafka())
        environment { this.config = MapApplicationConfig() }
        application { configure(components) }
        val anonymous = createClient { install(ContentNegotiation) { json() } }
        try {
            assertEquals(HttpStatusCode.Unauthorized, anonymous.create("https://example.com").status)
            val prettify = anonymous.post("/api/prettify") {
                contentType(ContentType.Application.Json)
                setBody(PrettifyRequest("{}", "json"))
            }
            assertEquals(HttpStatusCode.Unauthorized, prettify.status)
        } finally {
            components.close()
        }
    }
}
