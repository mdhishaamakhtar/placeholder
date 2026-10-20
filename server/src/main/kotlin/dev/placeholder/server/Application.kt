package dev.placeholder.server

import dev.placeholder.server.account.AccountRepository
import dev.placeholder.server.account.AccountService
import dev.placeholder.server.config.AppConfig
import dev.placeholder.server.http.AppShell
import dev.placeholder.server.http.SessionAuth
import dev.placeholder.server.http.accountRoutes
import dev.placeholder.server.http.installPlugins
import dev.placeholder.server.http.snipRoutes
import dev.placeholder.server.http.systemRoutes
import dev.placeholder.server.http.webRoutes
import dev.placeholder.server.infra.Database
import dev.placeholder.server.infra.InvalidationBus
import dev.placeholder.server.infra.Maintenance
import dev.placeholder.server.infra.Metrics
import dev.placeholder.server.infra.RateLimiter
import dev.placeholder.server.infra.Redis
import dev.placeholder.server.og.PreviewCards
import dev.placeholder.server.snip.SnipCache
import dev.placeholder.server.snip.SnipRepository
import dev.placeholder.server.snip.SnipService
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.routing.routing
import java.net.URI

/** Everything the app needs, wired by hand. Small enough that a DI framework would only add indirection. */
class AppComponents(
    val config: AppConfig,
    val database: Database,
    val redis: Redis?,
) : AutoCloseable {
    val metrics = Metrics()
    val bus = InvalidationBus(redis)
    val accounts = AccountService(AccountRepository(database), bus)
    private val snipRepository = SnipRepository(database)
    val snips = SnipService(SnipCache(snipRepository, redis, bus), snipRepository)
    val rateLimiter = RateLimiter(redis, config.createsPerMinute)
    val maintenance = Maintenance(snips, accounts, redis)
    val appShell = AppShell(config.webRoot, config.publicBaseUrl)
    val previewCards = PreviewCards(host = URI(config.publicBaseUrl).authority)

    override fun close() {
        redis?.close()
        database.close()
    }

    companion object {
        fun create(config: AppConfig) = AppComponents(config, Database(config), config.redisUrl?.let(::Redis))
    }
}

/** Entry point named in application.yaml; Ktor's EngineMain loads the file and calls this. */
fun Application.module() {
    // Preview cards are drawn with Java2D; there's no display on a server.
    System.setProperty("java.awt.headless", "true")
    val components = AppComponents.create(AppConfig.from(environment.config))
    monitor.subscribe(ApplicationStopped) { components.close() }
    log.info("Serving web app from {}", components.config.webRoot?.absolutePath ?: "nowhere (API only)")
    configure(components)
    components.maintenance.start(this)
}

fun Application.configure(components: AppComponents) {
    installPlugins(components.config, components.metrics, components.appShell)
    install(SessionAuth) {
        accounts = components.accounts
        publicBaseUrl = components.config.publicBaseUrl
    }
    routing {
        systemRoutes(components.database, components.redis, components.metrics)
        accountRoutes(components.accounts, components.rateLimiter, components.config.authPerMinute, components.config.secureCookies)
        snipRoutes(components.snips, components.rateLimiter, components.config.publicBaseUrl)
        webRoutes(components.snips, components.config.webRoot, components.appShell, components.previewCards)
    }
}
