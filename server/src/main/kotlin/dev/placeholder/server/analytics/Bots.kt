package dev.placeholder.server.analytics

/**
 * Link-preview fetchers and crawlers. Pasting a link into Slack makes Slack fetch it, and that isn't a visit, so
 * these aren't counted. Matching the user agent is the industry's usual answer: honest bots say who they are,
 * and the rest can't be told apart from people at this layer anyway.
 */
object Bots {
    private val PATTERN = Regex(
        "bot|crawl|spider|slurp|preview|facebookexternalhit|embedly|whatsapp|telegram|discord|slack|skype|vkshare|bitly",
        RegexOption.IGNORE_CASE,
    )

    fun isBot(userAgent: String?): Boolean = userAgent.isNullOrBlank() || PATTERN.containsMatchIn(userAgent)
}
