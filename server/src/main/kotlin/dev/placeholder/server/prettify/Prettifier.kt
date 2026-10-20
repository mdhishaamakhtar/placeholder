package dev.placeholder.server.prettify

import dev.placeholder.server.PrettifyException
import com.github.vertical_blank.sqlformatter.SqlFormatter
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

/** Reformats code with JVM-native formatters. Pure and CPU-bound, so callers should run it off the event loop. */
object Prettifier {
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun format(content: String, language: String): String = when (language) {
        "json" -> formatJson(content)
        "sql" -> SqlFormatter.format(content, "  ").trim()
        "xml" -> formatXml(content)
        "html" -> formatHtml(content)
        else -> throw PrettifyException("Prettify doesn't support $language yet.")
    }

    private fun formatJson(content: String): String = try {
        json.encodeToString(JsonElement.serializer(), json.parseToJsonElement(content))
    } catch (e: SerializationException) {
        throw PrettifyException("That isn't valid JSON, so it can't be prettified. ${e.message?.lineSequence()?.firstOrNull().orEmpty()}".trim())
    }

    private fun formatXml(content: String): String {
        val doc = Jsoup.parse(content, "", Parser.xmlParser())
        doc.outputSettings().prettyPrint(true).indentAmount(2).outline(false)
        return doc.outerHtml().trim()
    }

    private fun formatHtml(content: String): String {
        val isFullDocument = Regex("<!doctype|<html", RegexOption.IGNORE_CASE).containsMatchIn(content)
        val doc: Document = if (isFullDocument) Jsoup.parse(content) else Jsoup.parseBodyFragment(content)
        doc.outputSettings().prettyPrint(true).indentAmount(2)
        return (if (isFullDocument) doc.outerHtml() else doc.body().html()).trim()
    }
}
