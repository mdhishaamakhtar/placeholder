package dev.placeholder.server.og

import dev.placeholder.server.snip.Snip
import dev.placeholder.shared.Languages
import dev.placeholder.shared.SnipKind
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.TextAttribute
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * The 1200×630 cards link previews show (Slack, iMessage, X, LinkedIn), drawn at 2x with Java2D in the site's own
 * type and dark palette. One for the site, and one per snip that shows the start of its actual content.
 */
class PreviewCards(private val host: String) {
    private val light = load("hanken-grotesk-300")
    private val regular = load("hanken-grotesk-400")
    private val medium = load("hanken-grotesk-500")
    private val mono = load("chivo-mono-400")

    val site: ByteArray by lazy { render(::drawSite) }

    fun snip(snip: Snip): ByteArray = render { g -> drawSnip(g, snip) }

    private fun drawSite(g: Graphics2D) {
        header(g, right = host)
        g.font = tracked(light, 88f, -0.04f)
        g.color = CodeColors.INK
        g.drawString("Short links for", PAD, 250)
        g.drawString("links, code, and text.", PAD, 342)

        val rows = listOf(
            Triple("SQL", "select name, count(*) from orders group by 1;", "sql") to "M3pZ8Lk",
            Triple("JSON", """{ "service": "billing", "retries": 3 }""", "json") to "R9tA2cd",
        )
        var y = 438
        rule(g, y, CodeColors.INK)
        rows.forEach { (source, slug) ->
            val (kind, code, language) = source
            g.font = regular.deriveFont(18f)
            g.color = MUTED
            g.drawString(kind, PAD, y + 50)
            g.font = mono.deriveFont(22f)
            drawRuns(g, CodeColors.line(code, language), PAD + 90, y + 50, maxX = WIDTH - PAD - 300)
            slugAt(g, slug, y + 50, 22f)
            y += 78
            rule(g, y, HAIRLINE)
        }
    }

    private fun drawSnip(g: Graphics2D, snip: Snip) {
        val label = Languages.name(snip.language) +
            ", " + if (snip.lines == 1) "1 line" else "${snip.lines} lines"
        header(g, right = label)
        rule(g, 120, HAIRLINE)

        val lines = snip.content.lineSequence().take(MAX_LINES).toList()
        val code = snip.kind == SnipKind.CODE
        var y = 186
        lines.forEachIndexed { index, line ->
            if (code) {
                g.font = mono.deriveFont(24f)
                g.color = GUTTER
                val number = (index + 1).toString()
                g.drawString(number, PAD + 36 - g.fontMetrics.stringWidth(number), y)
                drawRuns(g, CodeColors.line(line.replace("\t", "  "), snip.language), PAD + 64, y, maxX = WIDTH - PAD)
            } else {
                g.font = regular.deriveFont(28f)
                g.color = CodeColors.INK
                drawRuns(g, listOf(CodeColors.Run(line, CodeColors.INK)), PAD, y, maxX = WIDTH - PAD)
            }
            y += LINE_HEIGHT
        }
        // When content runs off the bottom of the card, fade it into the page rather than cutting mid-line.
        if (snip.lines > FULLY_VISIBLE_LINES) {
            g.paint = GradientPaint(0f, 400f, Color(0, 0, 0, 0), 0f, 500f, Color.BLACK)
            g.fillRect(0, 400, WIDTH, 110)
        }

        rule(g, 512, HAIRLINE)
        g.font = regular.deriveFont(24f)
        g.color = MUTED
        val prefix = "$host/"
        g.drawString(prefix, PAD, 572)
        g.font = tracked(light, 52f, -0.03f)
        g.color = CodeColors.INK
        g.drawString(snip.slug, PAD + g.getFontMetrics(regular.deriveFont(24f)).stringWidth(prefix) + 6, 574)
    }

    private fun header(g: Graphics2D, right: String) {
        g.font = medium.deriveFont(26f)
        g.color = CodeColors.INK
        g.drawString("placeholder", PAD, 84)
        g.font = regular.deriveFont(22f)
        g.color = MUTED
        g.drawString(right, WIDTH - PAD - g.fontMetrics.stringWidth(right), 84)
    }

    private fun slugAt(g: Graphics2D, slug: String, y: Int, size: Float) {
        g.font = regular.deriveFont(size)
        val prefix = "$host/"
        val prefixWidth = g.fontMetrics.stringWidth(prefix)
        g.font = medium.deriveFont(size)
        val slugWidth = g.fontMetrics.stringWidth(slug)
        val x = WIDTH - PAD - slugWidth - prefixWidth
        g.font = regular.deriveFont(size)
        g.color = MUTED
        g.drawString(prefix, x, y)
        g.font = medium.deriveFont(size)
        g.color = CodeColors.INK
        g.drawString(slug, x + prefixWidth, y)
    }

    private fun drawRuns(g: Graphics2D, runs: List<CodeColors.Run>, startX: Int, y: Int, maxX: Int) {
        var x = startX
        for (run in runs) {
            g.color = run.color
            for (ch in run.text) {
                val w = g.fontMetrics.charWidth(ch)
                if (x + w > maxX) return
                g.drawString(ch.toString(), x, y)
                x += w
            }
        }
    }

    private fun rule(g: Graphics2D, y: Int, color: Color) {
        g.color = color
        g.fillRect(PAD, y, WIDTH - 2 * PAD, 1)
    }

    private fun render(draw: (Graphics2D) -> Unit): ByteArray {
        val image = BufferedImage(PIXEL_WIDTH, PIXEL_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            // Layout is in 1200 x 630 points; every point is drawn as SCALE x SCALE pixels.
            g.scale(SCALE.toDouble(), SCALE.toDouble())
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.composite = AlphaComposite.SrcOver
            g.color = Color.BLACK
            g.fillRect(0, 0, WIDTH, HEIGHT)
            draw(g)
        } finally {
            g.dispose()
        }
        return ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }
    }

    private fun tracked(font: Font, size: Float, tracking: Float): Font =
        font.deriveFont(mapOf(TextAttribute.SIZE to size, TextAttribute.TRACKING to tracking))

    private fun load(name: String): Font =
        PreviewCards::class.java.getResourceAsStream("/fonts/$name.ttf").use { stream ->
            Font.createFont(Font.TRUETYPE_FONT, requireNotNull(stream) { "Missing font $name" })
        }

    companion object {
        /** The card's layout size, in points: the standard link-preview shape. */
        private const val WIDTH = 1200
        private const val HEIGHT = 630

        /**
         * Pixels per point. Drawn at 2x, a card stays sharp on high-density screens (in chat apps and in the README)
         * where a 1x card would be stretched and soft. Crawlers accept this size and scale it to fit.
         */
        private const val SCALE = 2
        const val PIXEL_WIDTH = WIDTH * SCALE
        const val PIXEL_HEIGHT = HEIGHT * SCALE
        private const val PAD = 64
        private const val LINE_HEIGHT = 40
        private const val MAX_LINES = 8
        private const val FULLY_VISIBLE_LINES = 7
        private val MUTED = Color(0x8A, 0x8A, 0x8A)
        private val HAIRLINE = Color(0x26, 0x26, 0x26)
        private val GUTTER = Color(0x4D, 0x4D, 0x4D)
    }
}
