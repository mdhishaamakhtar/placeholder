package dev.placeholder.shared

/** Rules both the browser and the server agree on, so the UI never promises something the API rejects. */
object Rules {
    const val MAX_CONTENT_BYTES = 512 * 1024
    const val MAX_LINK_LENGTH = 2048
    const val SLUG_LENGTH = 7
    const val SLUG_ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    const val MIN_PASSWORD = 10
    const val MAX_PASSWORD = 256
    const val MAX_EMAIL = 254

    /** How long a link may live, in seconds. `null` means forever. */
    val EXPIRY_OPTIONS: List<Long?> = listOf(null, 60L * 60, 24L * 60 * 60, 7L * 24 * 60 * 60, 30L * 24 * 60 * 60)

    /** Languages the server can reformat. Keys are highlight.js language names. */
    val PRETTIFIABLE = setOf("json", "sql", "xml", "html")

    private val slugPattern = Regex("^[${SLUG_ALPHABET}]{$SLUG_LENGTH}$")
    private val languagePattern = Regex("^[a-z0-9+#-]{1,32}$")
    private val linkPattern = Regex("^https?://[^\\s/$.?#][^\\s]*$", RegexOption.IGNORE_CASE)

    private val emailPattern = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun isSlug(value: String): Boolean = slugPattern.matches(value)

    /** Deliberately loose: the only real test of an address is whether mail arrives. */
    fun isEmail(value: String): Boolean = value.length <= MAX_EMAIL && emailPattern.matches(value)

    fun normalizeEmail(value: String): String = value.trim().lowercase()

    fun isLanguage(value: String): Boolean = languagePattern.matches(value)

    /** A paste is a link only when the whole thing is a single http(s) URL. */
    fun asLink(content: String): String? {
        val trimmed = content.trim()
        if (trimmed.length > MAX_LINK_LENGTH || '\n' in trimmed) return null
        return trimmed.takeIf { linkPattern.matches(it) }
    }

    fun isPlain(language: String?): Boolean =
        language == null || language == "plaintext" || language == "text"

    fun lineCount(content: String): Int = if (content.isEmpty()) 0 else content.trimEnd('\n').count { it == '\n' } + 1
}
