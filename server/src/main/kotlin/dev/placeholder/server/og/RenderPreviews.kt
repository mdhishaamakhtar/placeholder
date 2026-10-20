package dev.placeholder.server.og

import dev.placeholder.server.snip.Snip
import dev.placeholder.shared.SnipKind
import java.io.File

/**
 * Writes sample preview cards to disk: `./gradlew :server:renderPreviews`.
 * The site card doubles as the README's header image.
 */
fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val out = File(args.firstOrNull() ?: "build/previews").apply { mkdirs() }
    val cards = PreviewCards(host = args.getOrNull(1) ?: "placeholder.hishaam.dev")

    out.resolve("placeholder-preview.png").writeBytes(cards.site)
    val sample = """
        select u.name, count(o.id) as orders
        from users u
        join orders o on o.user_id = u.id
        where o.created_at > now() - interval '7 days' -- last week
        group by u.name
        order by orders desc
        limit 20;
    """.trimIndent()
    val snip = Snip(
        slug = "M3pZ8Lk",
        kind = SnipKind.CODE,
        language = "sql",
        content = sample,
        createdAt = System.currentTimeMillis(),
        lines = sample.lines().size,
        bytes = sample.length,
    )
    out.resolve("snip-preview.png").writeBytes(cards.snip(snip))
    println("Wrote ${out.absolutePath}/placeholder-preview.png and snip-preview.png")
}
