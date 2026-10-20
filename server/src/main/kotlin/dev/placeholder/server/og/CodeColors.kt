package dev.placeholder.server.og

import java.awt.Color

/**
 * Just enough highlighting for a preview card: comments, strings, numbers, keywords, and JSON keys.
 * The page itself uses highlight.js; this only has to look right at a glance in a link preview.
 */
object CodeColors {
    // The dark theme's syntax palette from styles.css, by value.
    val INK = Color(0xED, 0xED, 0xED)
    val KEYWORD = Color(0xFF, 0x7A, 0xB2)
    val STRING = Color(0xFC, 0x6A, 0x5D)
    val NUMBER = Color(0xD9, 0xC9, 0x7C)
    val COMMENT = Color(0x7F, 0x8C, 0x98)
    val ATTR = Color(0xB2, 0x81, 0xEB)

    data class Run(val text: String, val color: Color)

    private val KEYWORDS = setOf(
        // SQL
        "select", "from", "where", "join", "left", "right", "inner", "outer", "on", "group", "by", "order", "having",
        "limit", "offset", "insert", "into", "values", "update", "set", "delete", "create", "table", "index", "as",
        "and", "or", "not", "null", "is", "in", "distinct", "union", "with", "case", "when", "then", "else", "end",
        "desc", "asc", "primary", "key", "references", "alter", "add", "drop", "interval",
        // C-family, Kotlin, Java, JS, Go, Rust, Python
        "fun", "val", "var", "class", "object", "interface", "data", "sealed", "enum", "return", "if", "for", "while",
        "do", "break", "continue", "import", "package", "private", "public", "protected", "internal", "override",
        "suspend", "const", "let", "function", "async", "await", "new", "this", "true", "false", "def", "self",
        "lambda", "pass", "None", "True", "False", "elif", "func", "go", "defer", "struct", "type", "impl", "fn",
        "pub", "mut", "match", "static", "final", "void", "int", "string", "bool", "try", "catch", "throw", "finally",
        "export", "default", "extends", "implements", "companion", "init", "typealias", "yield", "use", "mod",
    )

    private fun pattern(language: String?): Regex {
        val comment = when (language) {
            "sql" -> "--.*$"
            "python", "bash", "shell", "yaml", "ruby", "ini", "dockerfile" -> "#.*$"
            "json" -> "(?!)"
            else -> "//.*$"
        }
        return Regex(
            "(?<comment>$comment)|(?<string>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`[^`]*`)" +
                "|(?<number>\\b\\d+(?:\\.\\d+)?\\b)|(?<word>\\b[A-Za-z_][A-Za-z0-9_]*\\b)",
        )
    }

    fun line(text: String, language: String?): List<Run> {
        val runs = mutableListOf<Run>()
        var last = 0
        val sql = language == "sql"
        for (m in pattern(language).findAll(text)) {
            if (m.range.first > last) runs += Run(text.substring(last, m.range.first), INK)
            val groups = m.groups
            val color = when {
                groups["comment"] != null -> COMMENT
                groups["string"] != null -> {
                    // A JSON or YAML-ish key: a string followed by a colon.
                    val rest = text.substring(m.range.last + 1).trimStart()
                    if (rest.startsWith(":")) ATTR else STRING
                }
                groups["number"] != null -> NUMBER
                else -> if ((if (sql) m.value.lowercase() else m.value) in KEYWORDS) KEYWORD else INK
            }
            runs += Run(m.value, color)
            last = m.range.last + 1
        }
        if (last < text.length) runs += Run(text.substring(last), INK)
        return runs
    }
}
