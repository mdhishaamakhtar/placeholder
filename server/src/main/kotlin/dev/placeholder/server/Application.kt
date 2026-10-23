package dev.placeholder.server

import dev.placeholder.server.account.AccountRepository
import dev.placeholder.server.account.AccountService
import dev.placeholder.server.analytics.ClickStore
import dev.placeholder.server.analytics.Clicks
import dev.placeholder.server.analytics.Visitors
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
import dev.placeholder.server.infra.Kafka
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import kotlin.time.Duration.Companion.seconds

/** Everything the app needs, wired by hand. Small enough that a DI framework would only add indirection. */
class AppComponents(
    val config: AppConfig,
    val database: Database,
    val redis: Redis?,
    kafka: Kafka,
    val metrics: Metrics = Metrics(),
) : AutoCloseable {
    /** Work that outlives the request that started it, such as a cache lookup other requests are waiting on. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val bus = InvalidationBus(redis)
    val accounts = AccountService(AccountRepository(database), bus)
    private val snipRepository = SnipRepository(database)
    val snips = SnipService(SnipCache(snipRepository, redis, bus, metrics.registry, background), snipRepository)
    private val clickStore = ClickStore(database)
    val clicks = Clicks(kafka, clickStore, Visitors(config.visitorKey), metrics.registry)
    val rateLimiter = RateLimiter(redis, config.createsPerMinute)
    val maintenance = Maintenance(snips, accounts, clickStore, redis)
    val appShell = AppShell(config.webRoot, config.publicBaseUrl)
    val previewCards = PreviewCards(host = URI(config.publicBaseUrl).authority)

    override fun close() {
        background.cancel()
        clicks.close()
        redis?.close()
        database.close()
    }

    companion object {
        fun create(config: AppConfig): AppComponents {
            val metrics = Metrics()
            return AppComponents(
                config = config,
                database = Database(config, metrics.registry),
                redis = config.redisUrl?.let(::Redis),
                kafka = Kafka(config.kafkaBootstrapServers, config.kafkaReplicationFactor),
                metrics = metrics,
            )
        }
    }
}

/** Entry point named in application.yaml; Ktor's EngineMain loads the file and calls this. */
suspend fun Application.module() {
    // Preview cards are drawn with Java2D; there's no display on a server.
    System.setProperty("java.awt.headless", "true")
    val components = AppComponents.create(AppConfig.from(environment.config))
    log.info("Serving web app from {}", components.config.webRoot?.absolutePath ?: "nowhere (API only)")
    configure(components)
    components.maintenance.start(this)
    val clicks = components.clicks.start(this)
    // Ktor starts taking requests when this function returns: give Kafka a moment so the first visits are counted.
    if (!components.clicks.awaitReady(KAFKA_GRACE)) log.warn("Kafka not reachable yet; visits are counted once it is")
    monitor.subscribe(ApplicationStopped) {
        // Stopping cancels the click consumer mid-batch at worst (the batch is read again later). Let it wind down
        // before closing the connections, then the producer sends what it still holds, but not for long.
        runBlocking { withTimeoutOrNull(SHUTDOWN_GRACE) { clicks.join() } }
        components.close()
    }
}

private val SHUTDOWN_GRACE = 5.seconds
private val KAFKA_GRACE = 10.seconds

fun Application.configure(components: AppComponents) {
    installPlugins(components.config, components.metrics, components.appShell)
    install(SessionAuth) {
        accounts = components.accounts
        publicBaseUrl = components.config.publicBaseUrl
    }
    routing {
        systemRoutes(components.database, components.redis, components.metrics)
        accountRoutes(
            components.accounts,
            components.rateLimiter,
            components.config.authPerMinute,
            components.config.secureCookies,
        )
        snipRoutes(components.snips, components.clicks, components.rateLimiter, components.config.publicBaseUrl)
        webRoutes(
            components.snips,
            components.clicks,
            components.config.webRoot,
            components.appShell,
            components.previewCards,
        )
    }
}
