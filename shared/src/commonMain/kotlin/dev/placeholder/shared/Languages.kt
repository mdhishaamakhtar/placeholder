package dev.placeholder.shared

/**
 * How languages are named to people. Keys are highlight.js language ids, which is also what the API stores.
 * Shared so the editor, the link page, page titles, and preview cards always say the same thing.
 */
object Languages {
    private val NAMES =
        mapOf(
            "json" to "JSON",
            "sql" to "SQL",
            "xml" to "HTML / XML",
            "css" to "CSS",
            "javascript" to "JavaScript",
            "typescript" to "TypeScript",
            "kotlin" to "Kotlin",
            "java" to "Java",
            "python" to "Python",
            "go" to "Go",
            "rust" to "Rust",
            "bash" to "Shell",
            "shell" to "Shell",
            "yaml" to "YAML",
            "markdown" to "Markdown",
            "cpp" to "C++",
            "c" to "C",
            "csharp" to "C#",
            "php" to "PHP",
            "ruby" to "Ruby",
            "swift" to "Swift",
            "ini" to "Config",
            "dockerfile" to "Dockerfile",
            "diff" to "Diff",
            "graphql" to "GraphQL",
        )

    /** What someone can switch a paste to when detection guesses wrong. `null` is plain text. */
    val CHOICES: List<String?> =
        listOf(
            null,
            "markdown",
            "json",
            "sql",
            "kotlin",
            "java",
            "python",
            "javascript",
            "typescript",
            "go",
            "rust",
            "c",
            "cpp",
            "csharp",
            "php",
            "ruby",
            "swift",
            "xml",
            "css",
            "yaml",
            "bash",
            "ini",
            "diff",
            "graphql",
        )

    /** "SQL", "Kotlin", or "Text" for plain text. Unknown ids get a capitalized fallback rather than failing. */
    fun name(language: String?): String =
        if (language == null) "Text" else NAMES[language] ?: language.replaceFirstChar { it.uppercase() }
}
