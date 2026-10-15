package gg.departed.basic.entries.dialogue.hud

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.format.TextColor

/** The label may retain authored colors and bold, while [index] remains the existing WASD option index. */
data class HudChoice(val label: Component, val index: Int)

/**
 * Builds a vanilla action-bar component without owning dialogue state, input, or player lifecycle.
 * Send the returned component through Typewriter's action-bar ownership mechanism.
 */
object DialogueHudRenderer {
    private const val BODY_WIDTH = 280
    private const val OPTION_WIDTH = 366
    private const val FOOTER_WIDTH = 380
    private const val OPTION_PAGE_MILLIS = 3_000L
    private const val BODY_PAGE_MILLIS = 4_000L
    private val layout by lazy { HudTextLayout(HudGlyphMetrics.bundled()) }
    private val spaceFont = Key.key("departed:dialogue/space")
    private val panelFont = Key.key("departed:dialogue/panels")
    private val rowFonts = (0..19).map { Key.key("departed:dialogue/row_$it") }
    private val parchmentInk = TextColor.color(0x3A2418)

    /**
     * [revealedCharacters] counts Unicode code points in the fully parsed body, including spaces/newlines.
     * Pass all available [choices]; [shownChoices] is zero while their normal typewriter animation is pending.
     * Long selected responses rotate in two-row pages without changing the existing confirmation behavior.
     * [completedBodyMillis] is time since the body finished or was skipped; complete overflow text cycles all pages.
     */
    fun render(
        speaker: Component,
        body: Component,
        revealedCharacters: Int = Int.MAX_VALUE,
        choices: List<HudChoice> = emptyList(),
        selectedIndex: Int = 0,
        shownChoices: Int = 4,
        hint: Component = Component.empty(),
        elapsedMillis: Long = 0L,
        responseHeader: Component = Component.text("YOUR RESPONSE", NamedTextColor.GOLD),
        completedBodyMillis: Long = 0L,
    ): Component {
        val output = Component.text()
        val capacity = minOf(4, choices.size)
        output.append(panel(if (capacity == 0) 0xE005 else 0xE00F + capacity, -210, 226))
        output.append(panel(0xE020, -86, 203))
        val name = fitOneLine(speaker, BODY_WIDTH)
        output.append(row(0, name, -86 + (BODY_WIDTH - name.width) / 2, NamedTextColor.GOLD))

        val bodyGlyphs = layout.glyphs(body)
        val bodyLines = layout.wrap(bodyGlyphs, BODY_WIDTH)
        val bodyPages = bodyLines.chunked(4)
        val bodyPage = if (revealedCharacters >= bodyGlyphs.size && bodyPages.size > 1) {
            val page = ((completedBodyMillis.coerceAtLeast(0L) / BODY_PAGE_MILLIS) % bodyPages.size).toInt()
            HudBodyPage(bodyPages[page], page, bodyPages.size)
        } else layout.bodyPage(bodyLines, revealedCharacters)
        bodyPage.lines.forEachIndexed { index, line ->
            output.append(row(index + 1, HudLine(line.glyphs.filter { it.sourceIndex < revealedCharacters }),
                -86, parchmentInk, parchment = true))
        }

        val visible = visibleChoices(choices, selectedIndex, shownChoices.coerceIn(0, 4))
        var optionPageHint: String? = null
        if (visible.isNotEmpty()) {
            val selectedPosition = choices.indexOfFirst { it.index == selectedIndex }.coerceAtLeast(0)
            val range = if (choices.size > visible.size) "  ${selectedPosition + 1}/${choices.size}" else ""
            output.append(row(5, fitOneLine(responseHeader.append(Component.text(range, NamedTextColor.GOLD)),
                FOOTER_WIDTH), -190, NamedTextColor.GOLD))
            visible.forEachIndexed { slot, choice ->
                val selected = choice.index == selectedIndex
                if (selected) {
                    output.append(panel(0xE001 + slot, -190, 226))
                    output.append(row(6 + slot * 2, fitOneLine(Component.text(">", NamedTextColor.GOLD), 12), -190))
                }
                val optionLines = layout.wrap(choice.label, OPTION_WIDTH)
                val pageCount = (optionLines.size + 1) / 2
                val pageIndex = if (selected && pageCount > 1) {
                    ((elapsedMillis.coerceAtLeast(0L) / OPTION_PAGE_MILLIS) % pageCount).toInt()
                } else 0
                val lines = optionLines.drop(pageIndex * 2).take(2).toMutableList()
                if (!selected && optionLines.size > 2) lines[1] = layout.ellipsize(lines[1], OPTION_WIDTH)
                if (selected && pageCount > 1) optionPageHint = "response ${pageIndex + 1}/$pageCount"
                lines.forEachIndexed { index, line -> output.append(row(6 + slot * 2 + index, line, -176)) }
            }
        }

        val pageHints = buildList {
            if (bodyPage.count > 1) add("text ${bodyPage.index + 1}/${bodyPage.count}")
            optionPageHint?.let { add(it) }
        }
        val completeHint = if (pageHints.isEmpty()) hint else Component.text()
            .append(hint)
            .append(Component.text("  •  ${pageHints.joinToString("  •  ")}", NamedTextColor.GRAY))
            .build()
        val footer = fitOneLine(completeHint, FOOTER_WIDTH)
        output.append(row(15 + capacity, footer, -190 + (FOOTER_WIDTH - footer.width) / 2))
        return output.build()
    }

    internal fun visibleChoices(choices: List<HudChoice>, selectedIndex: Int, count: Int): List<HudChoice> {
        if (count <= 0 || choices.isEmpty()) return emptyList()
        val selected = choices.indexOfFirst { it.index == selectedIndex }.coerceAtLeast(0)
        val windowSize = minOf(4, choices.size)
        val start = (selected - 1).coerceIn(0, choices.size - windowSize)
        return choices.drop(start).take(count)
    }

    private fun fitOneLine(component: Component, width: Int): HudLine {
        val lines = layout.wrap(component, width)
        return if (lines.size > 1) layout.ellipsize(lines.first(), width) else lines.first()
    }

    private fun row(
        index: Int,
        line: HudLine,
        x: Int,
        defaultColor: TextColor = NamedTextColor.WHITE,
        parchment: Boolean = false,
    ): Component {
        val output = Component.text().append(spacing(x))
        var runStyle: Style? = null
        val run = StringBuilder()
        fun flush() {
            if (run.isEmpty()) return
            output.append(Component.text(run.toString()).style(runStyle!!))
            run.setLength(0)
        }
        line.glyphs.forEach { glyph ->
            val style = Style.style()
                .font(rowFonts[index])
                .color(if (parchment && glyph.style.color() in listOf(null, NamedTextColor.WHITE, NamedTextColor.GRAY)) {
                    defaultColor
                } else glyph.style.color() ?: defaultColor)
                .decoration(TextDecoration.BOLD, glyph.style.hasDecoration(TextDecoration.BOLD))
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.UNDERLINED, false)
                .decoration(TextDecoration.STRIKETHROUGH, false)
                .decoration(TextDecoration.OBFUSCATED, false)
                .shadowColor(ShadowColor.none())
                .build()
            if (runStyle != style) {
                flush()
                runStyle = style
            }
            run.appendCodePoint(glyph.codePoint)
        }
        flush()
        return output.append(spacing(-x - line.width)).build()
    }

    private fun panel(glyph: Int, x: Int, advance: Int): Component = Component.text()
        .append(spacing(x))
        .append(Component.text(String(Character.toChars(glyph))).font(panelFont)
            .color(NamedTextColor.WHITE).shadowColor(ShadowColor.none()))
        .append(spacing(-x - advance))
        .build()

    private fun spacing(pixels: Int): Component {
        var remaining = kotlin.math.abs(pixels)
        val text = buildString {
            while (remaining > 0) {
                val bit = (31 - Integer.numberOfLeadingZeros(remaining)).coerceAtMost(11)
                appendCodePoint((if (pixels < 0) 0xE100 else 0xE110) + bit)
                remaining -= 1 shl bit
            }
        }
        return Component.text(text).font(spaceFont).shadowColor(ShadowColor.none())
    }
}
