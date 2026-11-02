package dev.placeholder.server.infra

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.placeholder.server.config.AppConfig
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.SQLException
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

/** Owns the connection pool and schema. Repositories borrow transactions from it. */
class Database(
    config: AppConfig,
    registry: MeterRegistry? = null,
) : AutoCloseable {
    private val dataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.databaseUrl
                username = config.databaseUser
                password = config.databasePassword
                maximumPoolSize = config.databasePoolSize
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_READ_COMMITTED"
                // Fail fast when Postgres is gone: a request that can't get a connection in 2s won't get one in 30s
                // (Hikari's default) either, and waiting only piles up more requests behind it.
                connectionTimeout = 2_000
                metricRegistry = registry
                // Sends a JDBC batch of inserts as multi-row INSERTs instead of one statement per row: several times
                // faster for the click ingest, which inserts a thousand rows a batch.
                addDataSourceProperty("reWriteBatchedInserts", "true")
            },
        )
    private val exposed: ExposedDatabase

    init {
        Flyway
            .configure()
            .dataSource(dataSource)
            .load()
            .migrate()
        exposed =
            ExposedDatabase.connect(
                dataSource,
                databaseConfig =
                    DatabaseConfig {
                        // A transaction that fails on an SQLException is run again, up to this many times in all.
                        // The click ingest counts on it: two consumers racing on the same click ids resolve on the
                        // retry, where the loser finds them already counted. Stated here so a change in Exposed's
                        // default can't quietly take that away.
                        defaultMaxAttempts = TRANSACTION_ATTEMPTS
                    },
            )
    }

    /** Runs blocking JDBC work on the IO dispatcher so it never holds up the event loop. */
    suspend fun <T> query(block: JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            transaction(exposed) { block() }
        }

    suspend fun isHealthy(): Boolean =
        withContext(Dispatchers.IO) {
            try {
                dataSource.connection.use { it.isValid(1) }
            } catch (_: SQLException) {
                false
            }
        }

    override fun close() = dataSource.close()

    private companion object {
        const val TRANSACTION_ATTEMPTS = 3
    }
}
