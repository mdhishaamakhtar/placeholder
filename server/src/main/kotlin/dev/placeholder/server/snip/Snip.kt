package dev.placeholder.server.snip

import dev.placeholder.shared.SnipDto
import dev.placeholder.shared.SnipKind
import kotlinx.serialization.Serializable
import java.util.UUID

/** A snip as the server sees it, including who owns it. [SnipDto] is the public view of it. */
@Serializable
data class Snip(
    val slug: String,
    val kind: SnipKind,
    val language: String?,
    val content: String,
    val createdAt: Long,
    val lines: Int,
    val bytes: Int,
    val updatedAt: Long? = null,
    val expiresAt: Long? = null,
    val ownerId: String? = null,
    val version: Int = 1,
) {
    /** Anonymous snips have no owner, so nobody can ever change them. */
    val isImmutable: Boolean get() = ownerId == null

    fun isOwnedBy(userId: UUID?): Boolean = userId != null && ownerId == userId.toString()

    fun isExpired(now: Long): Boolean = expiresAt != null && expiresAt <= now

    fun toDto(viewer: UUID?) =
        SnipDto(
            slug = slug,
            kind = kind,
            language = language,
            content = content,
            createdAt = createdAt,
            lines = lines,
            bytes = bytes,
            updatedAt = updatedAt,
            expiresAt = expiresAt,
            owned = isOwnedBy(viewer),
        )
}

sealed interface Lookup {
    data class Found(
        val snip: Snip,
    ) : Lookup

    data object Missing : Lookup

    data object Expired : Lookup
}
