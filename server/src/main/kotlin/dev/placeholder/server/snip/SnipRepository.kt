package dev.placeholder.server.snip

import dev.placeholder.server.account.Users
import dev.placeholder.server.infra.Database
import dev.placeholder.shared.SnipKind
import dev.placeholder.shared.SnipSummary
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import kotlin.uuid.Uuid

object Snips : Table("snips") {
    val slug = varchar("slug", 16)
    val kind = varchar("kind", 8)
    val language = varchar("language", 32).nullable()
    val content = text("content")
    val lines = integer("lines")
    val bytes = integer("bytes")
    val createdAt = timestamp("created_at")
    val ownerId = uuid("owner_id").references(Users.id).nullable()
    val expiresAt = timestamp("expires_at").nullable()
    val updatedAt = timestamp("updated_at").nullable()
    val version = integer("version")

    override val primaryKey = PrimaryKey(slug)
}

/** Where a page of "your links" starts: strictly older than this (created_at, slug) pair. */
data class Cursor(
    val createdAt: Long,
    val slug: String,
) {
    override fun toString() = "${createdAt}_$slug"

    companion object {
        fun parse(value: String?): Cursor? {
            val (time, slug) = value?.split('_', limit = 2)?.takeIf { it.size == 2 } ?: return null
            return time.toLongOrNull()?.let { Cursor(it, slug) }
        }
    }
}

class SnipRepository(
    private val db: Database,
) {
    /** Returns false when the slug is already taken, so the caller can roll a new one. */
    suspend fun insert(snip: Snip): Boolean =
        db.query {
            Snips
                .insertIgnore {
                    it[slug] = snip.slug
                    it[kind] = snip.kind.name.lowercase()
                    it[language] = snip.language
                    it[content] = snip.content
                    it[lines] = snip.lines
                    it[bytes] = snip.bytes
                    it[createdAt] = Instant.ofEpochMilli(snip.createdAt)
                    it[ownerId] = snip.ownerId
                    it[expiresAt] = snip.expiresAt?.let(Instant::ofEpochMilli)
                    it[version] = snip.version
                }.insertedCount == 1
        }

    suspend fun find(slug: String): Snip? =
        db.query {
            Snips
                .selectAll()
                .where { Snips.slug eq slug }
                .limit(1)
                .firstOrNull()
                ?.toSnip()
        }

    /** Replaces the content of a snip the owner holds. Returns null if it doesn't exist or isn't theirs. */
    suspend fun update(
        slug: String,
        owner: Uuid,
        change: (Snip) -> Snip,
        now: Instant,
    ): Snip? =
        db.query {
            val current =
                Snips
                    .selectAll()
                    .where { (Snips.slug eq slug) and (Snips.ownerId eq owner) }
                    .forUpdate()
                    .firstOrNull()
                    ?.toSnip()
                    ?: return@query null
            val next = change(current).copy(updatedAt = now.toEpochMilli(), version = current.version + 1)
            Snips.update({ Snips.slug eq slug }) {
                it[kind] = next.kind.name.lowercase()
                it[language] = next.language
                it[content] = next.content
                it[lines] = next.lines
                it[bytes] = next.bytes
                it[updatedAt] = now
                it[version] = next.version
            }
            next
        }

    suspend fun listByOwner(
        owner: Uuid,
        before: Cursor?,
        limit: Int,
    ): List<SnipSummary> =
        db.query {
            Snips
                .selectAll()
                .where {
                    val mine = Snips.ownerId eq owner
                    if (before == null) mine else mine and olderThan(before)
                }.orderBy(Snips.createdAt to SortOrder.DESC, Snips.slug to SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    val snip = row.toSnip()
                    SnipSummary(
                        slug = snip.slug,
                        kind = snip.kind,
                        language = snip.language,
                        preview = preview(snip.content),
                        createdAt = snip.createdAt,
                        updatedAt = snip.updatedAt,
                        expiresAt = snip.expiresAt,
                    )
                }
        }

    /** Removes snips that expired more than a day ago (the grace period keeps "this link expired" answers stable). */
    suspend fun deleteExpired(before: Instant): Int =
        db.query {
            Snips.deleteWhere { Snips.expiresAt less before }
        }

    /** Strictly before [cursor] in "newest first" order: older, or the same instant and a smaller slug. */
    private fun olderThan(cursor: Cursor): Op<Boolean> {
        val at = Instant.ofEpochMilli(cursor.createdAt)
        return (Snips.createdAt less at) or ((Snips.createdAt eq at) and (Snips.slug less cursor.slug))
    }

    /** The start of a snip on one line. Only a little past the limit is collapsed, so a huge paste costs nothing. */
    private fun preview(content: String): String {
        val start = content.take(PREVIEW_CHARS * 4)
        return start.replace(WHITESPACE, " ").trim().take(PREVIEW_CHARS)
    }

    private fun ResultRow.toSnip() =
        Snip(
            slug = this[Snips.slug],
            kind = SnipKind.valueOf(this[Snips.kind].uppercase()),
            language = this[Snips.language],
            content = this[Snips.content],
            createdAt = this[Snips.createdAt].toEpochMilli(),
            lines = this[Snips.lines],
            bytes = this[Snips.bytes],
            updatedAt = this[Snips.updatedAt]?.toEpochMilli(),
            expiresAt = this[Snips.expiresAt]?.toEpochMilli(),
            ownerId = this[Snips.ownerId],
            version = this[Snips.version],
        )

    private companion object {
        const val PREVIEW_CHARS = 140
        val WHITESPACE = Regex("\\s+")
    }
}
