package dev.placeholder.server.account

import dev.placeholder.server.infra.Database
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.util.UUID

object Users : Table("users") {
    val id = javaUUID("id")
    val email = varchar("email", 254)
    val passwordHash = text("password_hash")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

object Sessions : Table("sessions") {
    val tokenHash = varchar("token_hash", 64)
    val userId = javaUUID("user_id").references(Users.id)
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at")

    override val primaryKey = PrimaryKey(tokenHash)
}

data class User(val id: UUID, val email: String)

data class StoredUser(val user: User, val passwordHash: String)

class AccountRepository(private val db: Database) {
    /** Returns null when the email is already taken. */
    suspend fun createUser(email: String, passwordHash: String, now: Instant): User? = db.query {
        val id = UUID.randomUUID()
        val inserted = Users.insertIgnore {
            it[Users.id] = id
            it[Users.email] = email
            it[Users.passwordHash] = passwordHash
            it[createdAt] = now
        }.insertedCount == 1
        if (inserted) User(id, email) else null
    }

    suspend fun findByEmail(email: String): StoredUser? = db.query {
        Users.selectAll().where { Users.email eq email }.limit(1).firstOrNull()?.let {
            StoredUser(User(it[Users.id], it[Users.email]), it[Users.passwordHash])
        }
    }

    suspend fun createSession(tokenHash: String, userId: UUID, now: Instant, expiresAt: Instant) {
        db.query {
            Sessions.insert {
                it[Sessions.tokenHash] = tokenHash
                it[Sessions.userId] = userId
                it[createdAt] = now
                it[Sessions.expiresAt] = expiresAt
            }
        }
    }

    suspend fun findSessionUser(tokenHash: String, now: Instant): User? = db.query {
        (Sessions innerJoin Users)
            .selectAll()
            .where { (Sessions.tokenHash eq tokenHash) and (Sessions.expiresAt greater now) }
            .limit(1)
            .firstOrNull()
            ?.let { User(it[Users.id], it[Users.email]) }
    }

    suspend fun deleteSession(tokenHash: String) {
        db.query { Sessions.deleteWhere { Sessions.tokenHash eq tokenHash } }
    }

    suspend fun deleteExpiredSessions(now: Instant): Int = db.query {
        Sessions.deleteWhere { Sessions.expiresAt less now }
    }
}
