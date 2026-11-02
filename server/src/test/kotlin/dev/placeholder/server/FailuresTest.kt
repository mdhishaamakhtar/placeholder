package dev.placeholder.server

import dev.placeholder.server.analytics.isTransient
import org.apache.datasketches.common.SketchesArgumentException
import org.postgresql.util.PSQLException
import org.postgresql.util.PSQLState
import java.sql.SQLTransientConnectionException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which failures the click consumer should wait out, and which it should give up on. */
class FailuresTest {
    @Test
    fun aPoolTimeoutIsTransient() {
        assertTrue(SQLTransientConnectionException("pool exhausted").isTransient())
    }

    @Test
    fun aLostConnectionIsTransientEvenWhenWrapped() {
        val lost = PSQLException("connection reset", PSQLState.CONNECTION_FAILURE)
        assertTrue(RuntimeException("transaction failed", lost).isTransient())
    }

    @Test
    fun aDeadlockIsTransient() {
        assertTrue(PSQLException("deadlock detected", PSQLState.DEADLOCK_DETECTED).isTransient())
    }

    @Test
    fun aConstraintViolationIsNot() {
        assertFalse(PSQLException("duplicate key", PSQLState.UNIQUE_VIOLATION).isTransient())
    }

    @Test
    fun aCorruptSketchIsNot() {
        assertFalse(SketchesArgumentException("not a sketch").isTransient())
    }
}
