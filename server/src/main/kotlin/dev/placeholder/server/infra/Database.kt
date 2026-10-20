package dev.placeholder.server.infra

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.placeholder.server.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

/** Owns the connection pool and schema. Repositories borrow transactions from it. */
class Database(config: AppConfig) : AutoCloseable {
    private val dataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.databaseUrl
            username = config.databaseUser
            password = config.databasePassword
            maximumPoolSize = config.databasePoolSize
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
        },
    )
    private val exposed: ExposedDatabase

    init {
        Flyway.configure().dataSource(dataSource).load().migrate()
        exposed = ExposedDatabase.connect(dataSource)
    }

    /** Runs blocking JDBC work on the IO dispatcher so it never holds up the event loop. */
    suspend fun <T> query(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) {
        transaction(exposed) { block() }
    }

    suspend fun isHealthy(): Boolean = withContext(Dispatchers.IO) {
        runCatching { dataSource.connection.use { it.isValid(1) } }.getOrDefault(false)
    }

    override fun close() = dataSource.close()
}
