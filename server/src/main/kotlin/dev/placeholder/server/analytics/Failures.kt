package dev.placeholder.server.analytics

import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException

/**
 * Whether a failure will pass on its own if the same work is tried again later: Postgres unreachable or shutting
 * down, the pool exhausted, a deadlock or serialization conflict. Anything else (a constraint violation, a row
 * that can't be read, a bug) fails the same way every time, and retrying it only stalls whatever is behind it.
 *
 * Exposed and the pool wrap the driver's exception, so the whole cause chain is examined.
 */
internal fun Throwable.isTransient(): Boolean =
    generateSequence(this) { it.cause?.takeIf { cause -> cause !== it } }
        .any { it is SQLException && it.isTransient() }

private fun SQLException.isTransient(): Boolean =
    this is SQLTransientException ||
        this is SQLRecoverableException ||
        this is SQLNonTransientConnectionException ||
        sqlState?.take(2) in TRANSIENT_SQL_STATE_CLASSES

/**
 * SQLSTATE classes that describe the connection or the moment, not the statement: 08 connection exception,
 * 40 transaction rollback (deadlock, serialization failure), 53 insufficient resources, 57 operator intervention
 * (shutdown, cancelled), 58 system error (disk, I/O).
 */
private val TRANSIENT_SQL_STATE_CLASSES = setOf("08", "40", "53", "57", "58")
