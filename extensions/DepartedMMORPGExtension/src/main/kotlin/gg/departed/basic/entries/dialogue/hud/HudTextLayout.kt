package gg.departed.basic.entries.dialogue.hud

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.flattener.ComponentFlattener
import net.kyori.adventure.text.flattener.FlattenerListener
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextDecoration

/** Widths are generated from the same bitmap atlases as the dialogue resource pack. */
internal class HudGlyphMetrics(
    private val advances: Map<Int, Int>,
    private val defaultAdvance: Int = 6,
) {
    fun advance(codePoint: Int, bold: Boolean = false): Int {
        if (codePoint == '\n'.code || codePoint == '\r'.code) return 0
        return (advances[codePoint] ?: defaultAdvance) + if (bold) 1 else 0
    }

    companion object {
        fun bundled(): HudGlyphMetrics {
            val stream = HudGlyphMetrics::class.java.getResourceAsStream("/dialogue-hud/widths.json")
                ?: error("Missing dialogue-hud/widths.json; rebuild the Departed dialogue pack and extension together")
            val json = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val default = Regex("\"default\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toInt()
                ?: error("Missing default glyph advance in dialogue-hud/widths.json")
            val widths = Regex("\"(\\d+)\"\\s*:\\s*(\\d+)").findAll(json)
                .associate { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            check(widths.isNotEmpty()) { "Empty dialogue glyph width table" }
            return HudGlyphMetrics(widths, default)
        }
    }
}

internal data class HudGlyph(val codePoint: Int, val style: Style, val sourceIndex: Int, val advance: Int)

internal data class HudLine(val glyphs: List<HudGlyph>) {
    val width: Int get() = glyphs.sumOf { it.advance }
    val text: String get() = buildString { glyphs.forEach { appendCodePoint(it.codePoint) } }
}

internal data class HudBodyPage(val lines: List<HudLine>, val index: Int, val count: Int)

/** Lay out the complete text before revealing letters, so partial words never move between rows. */
internal class HudTextLayout(private val metrics: HudGlyphMetrics) {
    fun glyphs(component: Component): List<HudGlyph> {
        val result = mutableListOf<HudGlyph>()
        val styles = mutableListOf(Style.empty())
        ComponentFlattener.basic().flatten(component, object : FlattenerListener {
            override fun pushStyle(style: Style) {
                styles += styles.last().merge(style, Style.Merge.Strategy.ALWAYS)
            }

            override fun component(text: String) {
                val style = styles.last()
                text.codePoints().forEach { codePoint ->
                    result += HudGlyph(
                        codePoint,
                        style,
                        result.size,
                        metrics.advance(codePoint, style.hasDecoration(TextDecoration.BOLD)),
                    )
                }
            }

            override fun popStyle(style: Style) {
                styles.removeAt(styles.lastIndex)
            }
        })
        return result
    }

    fun wrap(component: Component, maxWidth: Int): List<HudLine> = wrap(glyphs(component), maxWidth)

    fun wrap(glyphs: List<HudGlyph>, maxWidth: Int): List<HudLine> {
        require(maxWidth > 0)
        val lines = mutableListOf<HudLine>()
        var current = mutableListOf<HudGlyph>()
        for (glyph in glyphs) {
            if (glyph.codePoint == '\r'.code) continue
            if (glyph.codePoint == '\n'.code) {
                lines += HudLine(current.trimEnd())
                current = mutableListOf()
                continue
            }
            current += glyph
            if (current.sumOf { it.advance } <= maxWidth || current.size == 1) continue

            val breakAt = current.indexOfLast { it.isWhitespace() }
            if (breakAt > 0) {
                lines += HudLine(current.take(breakAt).trimEnd())
                current = current.drop(breakAt + 1).dropWhile { it.isWhitespace() }.toMutableList()
            } else {
                lines += HudLine(current.dropLast(1).trimEnd())
                current = mutableListOf(current.last())
            }
        }
        lines += HudLine(current.trimEnd())
        return lines
    }

    fun bodyPage(lines: List<HudLine>, revealedCharacters: Int, rows: Int = 4): HudBodyPage {
        require(rows > 0)
        val pages = lines.chunked(rows).ifEmpty { listOf(emptyList()) }
        val lastRevealed = revealedCharacters.coerceAtLeast(0).toLong() - 1
        val pageIndex = pages.indexOfLast { page ->
            page.firstNotNullOfOrNull { it.glyphs.firstOrNull()?.sourceIndex }?.let { it <= lastRevealed } ?: false
        }.coerceAtLeast(0)
        return HudBodyPage(pages[pageIndex], pageIndex, pages.size)
    }

    fun ellipsize(line: HudLine, maxWidth: Int): HudLine {
        val style = line.glyphs.lastOrNull()?.style ?: Style.empty()
        val ellipsis = glyphs(Component.text("…").style(style)).single()
        var visible = line.glyphs
        while (visible.isNotEmpty() && visible.sumOf { it.advance } + ellipsis.advance > maxWidth) {
            visible = visible.dropLast(1)
        }
        return HudLine(visible.trimEnd() + ellipsis)
    }

    private fun List<HudGlyph>.trimEnd(): List<HudGlyph> = dropLastWhile { it.isWhitespace() }

    private fun HudGlyph.isWhitespace(): Boolean = Character.isWhitespace(codePoint)
}
