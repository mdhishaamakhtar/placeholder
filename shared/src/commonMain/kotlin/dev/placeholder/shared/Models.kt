package dev.placeholder.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SnipKind {
    @SerialName("link")
    LINK,

    @SerialName("code")
    CODE,

    @SerialName("text")
    TEXT,
}

@Serializable
data class CreateSnipRequest(
    val content: String,
    /** Language the client detected. Ignored for links; `null` or "plaintext" means plain text. */
    val language: String? = null,
    /** One of [Rules.EXPIRY_OPTIONS]; `null` keeps the link forever. */
    val expiresInSeconds: Long? = null,
)

@Serializable
data class UpdateSnipRequest(
    val content: String,
    val language: String? = null,
)

@Serializable
data class CreateSnipResponse(
    val slug: String,
    val kind: SnipKind,
    val url: String,
)

@Serializable
data class SnipDto(
    val slug: String,
    val kind: SnipKind,
    val language: String?,
    val content: String,
    val createdAt: Long,
    val lines: Int,
    val bytes: Int,
    val updatedAt: Long? = null,
    val expiresAt: Long? = null,
    /** True when the person asking owns this snip and may edit it. */
    val owned: Boolean = false,
)

/** One row in "Your links": enough to list it, not the whole content. */
@Serializable
data class SnipSummary(
    val slug: String,
    val kind: SnipKind,
    val language: String?,
    val preview: String,
    val createdAt: Long,
    val updatedAt: Long?,
    val expiresAt: Long?,
    /** All-time visits to the short link, not counting bots. Lands a moment after each visit. */
    val visits: Long = 0,
)

@Serializable
data class SnipPage(
    val items: List<SnipSummary>,
    /** Pass back as `before` to get the next page; null when there are no more. */
    val nextCursor: String?,
)

/** Visits to one link, for its owner. */
@Serializable
data class LinkStats(
    /** All-time visits, not counting bots. */
    val visits: Long,
    /** Distinct visitors across [days]. An estimate, within about 2% (HyperLogLog). */
    val visitors: Long,
    /** The last 30 days, oldest first, one entry per UTC day including days with no visits. */
    val days: List<DayStats>,
)

@Serializable
data class DayStats(
    /** ISO date, e.g. "2026-10-23". */
    val day: String,
    val visits: Long,
    val visitors: Long,
)

@Serializable
data class Credentials(
    val email: String,
    val password: String,
)

@Serializable
data class Me(
    val email: String,
)

@Serializable
data class PrettifyRequest(
    val content: String,
    val language: String,
)

@Serializable
data class PrettifyResponse(
    val content: String,
    val changed: Boolean,
)

@Serializable
data class ApiError(
    val message: String,
)
