package dev.placeholder.server.snip

import dev.placeholder.server.ForbiddenException
import dev.placeholder.server.InvalidInputException
import dev.placeholder.server.account.User
import dev.placeholder.shared.CreateSnipRequest
import dev.placeholder.shared.Rules
import dev.placeholder.shared.SnipKind
import dev.placeholder.shared.SnipPage
import dev.placeholder.shared.UpdateSnipRequest
import java.time.Instant

class SnipService(
    private val cache: SnipCache,
    private val repository: SnipRepository,
    private val slugs: Slugs = Slugs(),
) {
    suspend fun create(
        request: CreateSnipRequest,
        owner: User?,
        now: Long = System.currentTimeMillis(),
    ): Snip {
        val expiresIn = request.expiresInSeconds
        if (expiresIn !in Rules.EXPIRY_OPTIONS) throw InvalidInputException("That expiry isn't one of the options.")
        val body = parse(request.content, request.language)

        repeat(MAX_SLUG_ATTEMPTS) {
            val snip =
                Snip(
                    slug = slugs.next(),
                    kind = body.kind,
                    language = body.language,
                    content = body.content,
                    createdAt = now,
                    lines = Rules.lineCount(body.content),
                    bytes = body.content.encodeToByteArray().size,
                    expiresAt = expiresIn?.let { now + it * 1000 },
                    ownerId = owner?.id,
                )
            if (cache.insert(snip, now)) return snip
        }
        error("Could not find a free slug after $MAX_SLUG_ATTEMPTS attempts")
    }

    suspend fun lookup(
        slug: String,
        now: Long = System.currentTimeMillis(),
    ): Lookup {
        if (!Rules.isSlug(slug)) return Lookup.Missing
        val snip = cache.find(slug, now) ?: return Lookup.Missing
        return if (snip.isExpired(now)) Lookup.Expired else Lookup.Found(snip)
    }

    suspend fun update(
        slug: String,
        request: UpdateSnipRequest,
        user: User,
        now: Long = System.currentTimeMillis(),
    ): Snip {
        val body = parse(request.content, request.language)
        val updated =
            repository.update(
                slug = slug,
                owner = user.id,
                change = { current ->
                    if (current.isExpired(now)) throw InvalidInputException(EXPIRED_EDIT)
                    current.copy(
                        kind = body.kind,
                        language = body.language,
                        content = body.content,
                        lines = Rules.lineCount(body.content),
                        bytes = body.content.encodeToByteArray().size,
                    )
                },
                now = Instant.ofEpochMilli(now),
            ) ?: throw ForbiddenException("Only the person who made this link can edit it.")
        cache.evict(slug)
        return updated
    }

    suspend fun mine(
        user: User,
        before: String?,
    ): SnipPage {
        val items = repository.listByOwner(user.id, Cursor.parse(before), PAGE_SIZE + 1)
        val page = items.take(PAGE_SIZE)
        val next = if (items.size > PAGE_SIZE) page.last().let { Cursor(it.createdAt, it.slug).toString() } else null
        return SnipPage(page, next)
    }

    suspend fun deleteExpired(now: Instant = Instant.now()): Int =
        repository.deleteExpired(now.minusSeconds(EXPIRED_GRACE_SECONDS))

    private data class Body(
        val kind: SnipKind,
        val language: String?,
        val content: String,
    )

    private fun parse(
        content: String,
        requestedLanguage: String?,
    ): Body {
        if (content.isBlank()) {
            throw InvalidInputException(
                "There's nothing to save. Paste a link, code, or text first.",
            )
        }
        val bytes = content.encodeToByteArray().size
        if (bytes > Rules.MAX_CONTENT_BYTES) {
            throw InvalidInputException("That's ${bytes / 1024} KB. The limit is ${Rules.MAX_CONTENT_BYTES / 1024} KB.")
        }
        val link = Rules.asLink(content)
        val language = requestedLanguage?.lowercase()?.takeIf { link == null && !Rules.isPlain(it) }
        if (language != null && !Rules.isLanguage(language)) throw InvalidInputException("Unknown language: $language")
        val kind =
            when {
                link != null -> SnipKind.LINK
                language != null && language != "markdown" -> SnipKind.CODE
                else -> SnipKind.TEXT
            }
        return Body(kind, language, link ?: content)
    }

    private companion object {
        const val EXPIRED_EDIT = "This link has expired, so it can't be edited."
        const val MAX_SLUG_ATTEMPTS = 5
        const val PAGE_SIZE = 50
        const val EXPIRED_GRACE_SECONDS = 24 * 60 * 60L
    }
}
