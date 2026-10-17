package gg.departed.basic.entries.dialogue.hud

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration

/**
 * The subtitle band: a bottom-anchored line used by cutscenes (tap to complete / advance) and by
 * auto-dismissing barks (mob speech, out-of-conversation NPC lines). Same glyph pipeline as the
 * parchment HUD, different shader ids: rows 24..31 and strips U+E021..U+E026 are placed by
 * departed_dialogue_hud_vsh.glsl just above the hotbar, scaled like the parchment panel.
 *
 * Layout (panel coordinates, 0..420 wide): strip x 12..408; body x 20..400 (380 wide, at most 3 lines,
 * bottom aligned); speaker row 12 px above the first body line; footer row (key hint or dismiss timer)
 * under the last body line.
 */
internal object SubtitleHudRenderer {
    const val BODY_WIDTH = 380
    private const val BODY_X = -190
    private const val STRIP_X = -198
    private const val STRIP_ADVANCE = 227
    private const val MAX_LINES = 3
    private const val FOOTER_ROW = 24
    private val BODY_ROWS = listOf(25, 26, 27)
    private val SPEAKER_ROW_BY_LINES = mapOf(1 to 31, 2 to 30, 3 to 29)
    private val layout by lazy { HudTextLayout(HudGlyphMetrics.bundled()) }
    private val spaceFont = Key.key("departed:dialogue/space")
    private val panelFont = Key.key("departed:dialogue/panels")
    private val rowFonts = (0..31).map { Key.key("departed:dialogue/row_$it") }
    private val bodyInk = TextColor.color(0xF0E8D6)
    private val speakerInk = TextColor.color(0xFFD365)
    private val hintInk = TextColor.color(0xAAAAAA)
    private val timerInk = TextColor.color(0xFFD365)
    private val timerTrack = TextColor.color(0x5A4632)
    private val shadow = ShadowColor.shadowColor(0xFF140E08.toInt())

    sealed interface Footer {
        data class Hint(val text: Component) : Footer
        /** [fraction] of the dismiss time still left (1 = full bar). */
        data class Timer(val fraction: Double) : Footer
        data object None : Footer
    }

    /**
     * [speaker] empty = narration (no name row, shorter strip). [revealedCharacters] counts code points of the
     * parsed body. [centered] = cutscene style; false = left aligned (barks). [trailing] is appended after the
     * band at the natural action-bar position (a foreign action-bar message passed through during a bark).
     */
    fun render(
        speaker: Component,
        body: Component,
        revealedCharacters: Int = Int.MAX_VALUE,
        footer: Footer = Footer.None,
        centered: Boolean = true,
        trailing: Component? = null,
    ): Component {
        val output = mutableListOf<Component>()
        val bodyGlyphs = layout.glyphs(body)
        val allLines = layout.wrap(bodyGlyphs, BODY_WIDTH).ifEmpty { listOf(HudLine(emptyList())) }
        val page = layout.bodyPage(allLines, revealedCharacters, MAX_LINES)
        val lines = page.lines.ifEmpty { listOf(HudLine(emptyList())) }
        val count = lines.size.coerceIn(1, MAX_LINES)
        val hasSpeaker = layout.glyphs(speaker).any { !Character.isWhitespace(it.codePoint) }

        output.add(panel((if (hasSpeaker) 0xE021 else 0xE024) + count - 1, STRIP_X, STRIP_ADVANCE))
        if (hasSpeaker) {
            val name = fitOneLine(speaker, BODY_WIDTH)
            output.add(row(SPEAKER_ROW_BY_LINES.getValue(count), name, x(name, centered), speakerInk))
        }
        val rows = BODY_ROWS.takeLast(count)
        lines.forEachIndexed { index, line ->
            val visible = HudLine(line.glyphs.filter { it.sourceIndex < revealedCharacters })
            // Center on the full line so letters never slide while the line types out.
            output.add(row(rows[index], visible, x(line, centered), bodyInk, parchment = true))
        }
        when (footer) {
            is Footer.Hint -> {
                val hint = fitOneLine(footer.text, BODY_WIDTH)
                output.add(row(FOOTER_ROW, hint, BODY_X + (BODY_WIDTH - hint.width) / 2, hintInk))
            }
            is Footer.Timer -> {
                output.add(bar(BODY_X, BODY_WIDTH, timerTrack))
                val filled = (BODY_WIDTH * footer.fraction.coerceIn(0.0, 1.0)).toInt()
                if (filled > 0) output.add(bar(BODY_X, filled, timerInk))
            }
            Footer.None -> {}
        }
        if (page.count > 1) {
            val marker = fitOneLine(Component.text("${page.index + 1}/${page.count}", hintInk), 40)
            output.add(row(FOOTER_ROW, marker, BODY_X + BODY_WIDTH - marker.width, hintInk))
        }
        trailing?.let { output.add(it) }
        return children(output)
    }

    private fun x(line: HudLine, centered: Boolean): Int =
        if (centered) BODY_X + (BODY_WIDTH - line.width) / 2 else BODY_X

    private fun fitOneLine(component: Component, width: Int): HudLine {
        val lines = layout.wrap(component, width)
        return if (lines.size > 1) layout.ellipsize(lines.first(), width) else lines.first()
    }

    private fun row(index: Int, line: HudLine, x: Int, defaultColor: TextColor, parchment: Boolean = false): Component {
        val output = mutableListOf(spacing(x))
        var runStyle: Style? = null
        val run = StringBuilder()
        fun flush() {
            if (run.isEmpty()) return
            output.add(Component.text(run.toString()).style(runStyle!!))
            run.setLength(0)
        }
        line.glyphs.forEach { glyph ->
            val authored = glyph.style.color()
            val color = if (parchment && authored in listOf(null, NamedTextColor.WHITE, NamedTextColor.GRAY)) {
                defaultColor
            } else authored ?: defaultColor
            val style = Style.style()
                .font(rowFonts[index])
                .color(color)
                .decoration(TextDecoration.BOLD, glyph.style.hasDecoration(TextDecoration.BOLD))
                .decoration(TextDecoration.ITALIC, glyph.style.hasDecoration(TextDecoration.ITALIC))
                .decoration(TextDecoration.UNDERLINED, false)
                .decoration(TextDecoration.STRIKETHROUGH, false)
                .decoration(TextDecoration.OBFUSCATED, glyph.style.hasDecoration(TextDecoration.OBFUSCATED))
                .shadowColor(shadow)
                .build()
            if (runStyle != style) {
                flush()
                runStyle = style
            }
            run.appendCodePoint(glyph.codePoint)
        }
        flush()
        output.add(spacing(-x - line.width))
        return children(output)
    }

    /** A [width] px wide, 2 px tall bar on the footer row, from binary-width pieces U+E200..U+E208. */
    private fun bar(x: Int, width: Int, color: TextColor): Component {
        var remaining = width.coerceIn(0, 511)
        if (remaining == 0) return Component.empty()
        val parts = mutableListOf(spacing(x))
        val style = Style.style().font(rowFonts[FOOTER_ROW]).color(color).shadowColor(ShadowColor.none()).build()
        while (remaining > 0) {
            val bit = (31 - Integer.numberOfLeadingZeros(remaining)).coerceAtMost(8)
            // Every bitmap glyph advances its width + 1 px of spacing; pull that pixel back right away so the
            // pieces butt together with no notch between them.
            parts.add(Component.text(String(Character.toChars(0xE200 + bit))).style(style))
            parts.add(spacing(-1))
            remaining -= 1 shl bit
        }
        parts.add(spacing(-x - width))
        return children(parts)
    }

    private fun panel(glyph: Int, x: Int, advance: Int): Component = children(listOf(
        spacing(x),
        Component.text(String(Character.toChars(glyph))).font(panelFont)
            .color(NamedTextColor.WHITE).shadowColor(ShadowColor.none()),
        spacing(-x - advance),
    ))

    private fun children(parts: List<Component>): Component = Component.join(JoinConfiguration.noSeparators(), parts)

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

    /** True for components this renderer produced (any font in the departed namespace under dialogue/). */
    fun isDialogueHud(component: Component): Boolean {
        val font = component.font()
        if (font != null && font.namespace() == "departed" && font.value().startsWith("dialogue/")) return true
        return component.children().any { isDialogueHud(it) }
    }
}
