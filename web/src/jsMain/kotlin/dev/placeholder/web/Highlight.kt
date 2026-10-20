package dev.placeholder.web

import dev.placeholder.shared.Rules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.js.json

@JsModule("highlight.js/lib/common")
@JsNonModule
private external object HighlightJs {
    fun highlight(code: String, options: dynamic): HighlightResult
    fun highlightAuto(code: String, languageSubset: Array<String>): HighlightResult
    fun getLanguage(name: String): Any?
}

private external interface HighlightResult {
    val value: String
    val language: String?
    val relevance: Int
}

/** What the composer thinks the paste is. */
data class Detection(val language: String?, val isLink: Boolean) {
    val label: String get() = when {
        isLink -> "Link"
        language == null -> "Text"
        else -> LABELS[language] ?: language.replaceFirstChar { it.uppercase() }
    }

    /** The language name the server's formatter understands, if it can format this at all. */
    fun prettifyAs(content: String): String? = when (language) {
        "xml" -> if (HTML_HINT.containsMatchIn(content)) "html" else "xml"
        in Rules.PRETTIFIABLE -> language
        else -> null
    }
}

private val HTML_HINT = Regex("<!doctype|<html|<(div|span|p|a|body|head|section|main|ul|li|img|button)[\\s>]", RegexOption.IGNORE_CASE)

private val LABELS = mapOf(
    "json" to "JSON", "sql" to "SQL", "xml" to "HTML / XML", "css" to "CSS", "javascript" to "JavaScript",
    "typescript" to "TypeScript", "kotlin" to "Kotlin", "java" to "Java", "python" to "Python", "go" to "Go",
    "rust" to "Rust", "bash" to "Shell", "shell" to "Shell", "yaml" to "YAML", "markdown" to "Markdown",
    "cpp" to "C++", "c" to "C", "csharp" to "C#", "php" to "PHP", "ruby" to "Ruby", "swift" to "Swift",
    "ini" to "Config", "dockerfile" to "Dockerfile", "diff" to "Diff", "graphql" to "GraphQL",
)

private val SUBSET = arrayOf(
    "sql", "json", "kotlin", "java", "python", "javascript", "typescript", "xml", "css", "bash", "go", "rust",
    "yaml", "markdown", "c", "cpp", "csharp", "php", "ruby", "swift", "ini", "diff", "graphql",
)

private const val MIN_RELEVANCE = 6
private const val SAMPLE_CHARS = 6000

fun detect(content: String): Detection? {
    if (content.isBlank()) return null
    if (Rules.asLink(content) != null) return Detection(null, isLink = true)
    if (looksLikeJson(content)) return Detection("json", isLink = false)
    // Checked before highlight.js, which happily scores English words like "let", "use", and "for" as code.
    if (looksLikeProse(content)) return Detection(null, isLink = false)
    val result = HighlightJs.highlightAuto(content.take(SAMPLE_CHARS), SUBSET)
    val language = result.language?.takeIf { result.relevance >= MIN_RELEVANCE } ?: startsLike(content)
    return Detection(language, isLink = false)
}

private val SQL_START = Regex("^(select|insert|update|delete|with|create|alter|drop)\\b", RegexOption.IGNORE_CASE)
private val MARKDOWN_LINE = Regex("^(#{1,6} |[-*+] |\\d+\\. |> )", RegexOption.MULTILINE)
private val SHELL_START = Regex("^(\\$ )?(sudo|git|npm|npx|yarn|pnpm|bun|docker|kubectl|curl|wget|cd|ls|echo|brew|ssh|make|\\./)")

/** One-liners give highlight.js too little to go on; how the line starts is often enough. */
private fun startsLike(content: String): String? {
    val first = content.trimStart()
    return when {
        SQL_START.containsMatchIn(first) -> "sql"
        SHELL_START.containsMatchIn(first) -> "bash"
        // A heading, or a couple of list or quote lines, is Markdown.
        first.startsWith("#") && MARKDOWN_LINE.containsMatchIn(first) -> "markdown"
        MARKDOWN_LINE.findAll(content).count() >= 2 -> "markdown"
        else -> null
    }
}

private const val CODE_SYMBOLS = "{}[]();=<>/\\|&*_`$#@^~%+"
private val SENTENCE_BREAK = Regex("[a-z][.!?]\\s+[A-Z]")
private val SENTENCE_END = Regex("[A-Za-z][.!?][\"')]?$")
// Case-sensitive on purpose: code keywords are lowercase (SQL is often all caps), while a sentence like
// "If we ship Friday" or "Use the new one" starts with a capital.
private val CODE_START = Regex(
    "^(select|insert|update|delete|with|create|alter|drop|import|package|from|def|class|fun|val|var|let|const|" +
        "function|func|fn|pub|use|impl|struct|enum|interface|type|export|return|if|for|while|sudo|git|npm|" +
        "docker|kubectl|curl|cd|ls|echo|SELECT|INSERT|UPDATE|DELETE|WITH|CREATE|ALTER|DROP)\\b|^#include|^#!",
)

/**
 * Natural language: plenty of words, sentence punctuation, and almost none of the symbols code is made of.
 * Dictated or written notes land here even when every word is also a keyword in some language.
 */
private fun looksLikeProse(content: String): Boolean {
    val text = content.trim()
    val words = text.split(Regex("\\s+")).size
    if (words < 6) return false
    val visible = text.count { !it.isWhitespace() }
    val symbols = text.count { it in CODE_SYMBOLS }
    val density = symbols.toDouble() / visible
    if (density >= 0.02 || CODE_START.containsMatchIn(text)) return false
    val averageLine = text.length / text.lines().size
    return SENTENCE_END.containsMatchIn(text) || SENTENCE_BREAK.containsMatchIn(text) || averageLine > 80
}

private fun looksLikeJson(content: String): Boolean {
    val trimmed = content.trim()
    if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return false
    return runCatching { Json.parseToJsonElement(trimmed) }.getOrNull().let { it is JsonObject || it is JsonArray }
}

/**
 * Highlighted HTML split into one fragment per source line, so each line can wrap on its own and keep its
 * line number. Spans that cross a line break (multi-line strings and comments) are closed at the end of the
 * line and reopened at the start of the next, so every fragment is balanced.
 */
fun highlightLines(content: String, language: String?): List<String>? {
    val html = highlightHtml(content.trimEnd('\n'), language) ?: return null
    val lines = mutableListOf<String>()
    val open = ArrayDeque<String>()
    val line = StringBuilder()
    var i = 0
    while (i < html.length) {
        val c = html[i]
        when {
            c == '<' -> {
                val end = html.indexOf('>', i)
                val tag = html.substring(i, end + 1)
                if (tag.startsWith("</")) open.removeLastOrNull() else open.addLast(tag)
                line.append(tag)
                i = end + 1
            }
            c == '\n' -> {
                repeat(open.size) { line.append("</span>") }
                lines += line.toString()
                line.clear()
                open.forEach { line.append(it) }
                i++
            }
            else -> {
                line.append(c)
                i++
            }
        }
    }
    repeat(open.size) { line.append("</span>") }
    lines += line.toString()
    return lines
}

/** Every language the editor can be told a paste is, for when detection guesses wrong. */
val LANGUAGE_CHOICES: List<Pair<String?, String>> = listOf(null to "Text", "markdown" to "Markdown") +
    listOf(
        "json", "sql", "kotlin", "java", "python", "javascript", "typescript", "go", "rust", "c", "cpp", "csharp",
        "php", "ruby", "swift", "xml", "css", "yaml", "bash", "ini", "diff", "graphql",
    ).map { it to (LABELS[it] ?: it.replaceFirstChar(Char::uppercase)) }

/** Highlighted HTML for display. highlight.js escapes the source, so the result is safe to set as innerHTML. */
fun highlightHtml(content: String, language: String?): String? {
    if (language == null || HighlightJs.getLanguage(language) == null) return null
    return HighlightJs.highlight(content, json("language" to language, "ignoreIllegals" to true)).value
}
