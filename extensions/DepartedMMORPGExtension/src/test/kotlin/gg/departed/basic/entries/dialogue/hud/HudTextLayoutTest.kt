package gg.departed.basic.entries.dialogue.hud

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HudTextLayoutTest {
    private val metrics = HudGlyphMetrics(mapOf('W'.code to 8, 'i'.code to 2, ' '.code to 3, 0x1F600 to 9))
    private val layout = HudTextLayout(metrics)

    @Test
    fun `wrap uses pixel width and splits an oversized word without losing letters`() {
        assertEquals(listOf("Wi", "WW", "WW"), layout.wrap(Component.text("Wi WWWW"), 16).map { it.text })
        assertTrue(layout.wrap(Component.text("Wi WWWW"), 16).all { it.width <= 16 })
    }

    @Test
    fun `newlines reserve rows and supplementary characters count once`() {
        val glyphs = layout.glyphs(Component.text("W😀\n\ni"))
        assertEquals(5, glyphs.size)
        assertEquals(listOf(0, 1, 2, 3, 4), glyphs.map { it.sourceIndex })
        assertEquals(listOf("W😀", "", "i"), layout.wrap(glyphs, 100).map { it.text })
        assertEquals(9, glyphs[1].advance)
    }

    @Test
    fun `nested style inheritance contributes bold width while children can disable it`() {
        val component = Component.text().decorate(TextDecoration.BOLD).color(NamedTextColor.GOLD)
            .append(Component.text("W"))
            .append(Component.text("i").decoration(TextDecoration.BOLD, false))
            .build()
        val glyphs = layout.glyphs(component)
        assertEquals(listOf(9, 2), glyphs.map { it.advance })
        assertTrue(glyphs[0].style.hasDecoration(TextDecoration.BOLD))
        assertFalse(glyphs[1].style.hasDecoration(TextDecoration.BOLD))
        assertTrue(glyphs.all { it.style.color() == NamedTextColor.GOLD })
    }

    @Test
    fun `reveal preserves the fully wrapped word position`() {
        val lines = layout.wrap(Component.text("WW Wi"), 18)
        assertEquals(listOf("WW", "Wi"), lines.map { it.text })
        assertEquals(listOf(3, 4), lines[1].glyphs.map { it.sourceIndex })
        assertEquals("W", HudLine(lines[1].glyphs.filter { it.sourceIndex < 4 }).text)
    }

    @Test
    fun `body paging advances only when the next page starts revealing and keeps the final page`() {
        val lines = layout.wrap(Component.text("a\nb\nc\nd\ne\nf"), 280)
        assertEquals(0, layout.bodyPage(lines, 8).index)
        assertEquals(1, layout.bodyPage(lines, 9).index)
        val last = layout.bodyPage(lines, Int.MAX_VALUE)
        assertEquals(listOf("e", "f"), last.lines.map { it.text })
        assertEquals(2, last.count)
    }

    @Test
    fun `ellipsis removes enough glyphs to remain inside its row`() {
        val shortened = layout.ellipsize(HudLine(layout.glyphs(Component.text("WWW"))), 16)
        assertEquals("W…", shortened.text)
        assertEquals(14, shortened.width)
    }

    @Test
    fun `choice window keeps original indices and includes the selected response`() {
        val choices = (0..8).map { HudChoice(Component.text("Response $it"), it * 10) }
        assertEquals(listOf(30, 40, 50, 60), DialogueHudRenderer.visibleChoices(choices, 40, 4).map { it.index })
        assertEquals(listOf(50, 60, 70, 80), DialogueHudRenderer.visibleChoices(choices, 80, 4).map { it.index })
        assertTrue(DialogueHudRenderer.visibleChoices(choices, 40, 0).isEmpty())
    }

    @Test
    fun `partial choice reveal keeps a stable window near the end of the list`() {
        val choices = (0..8).map { HudChoice(Component.text("Response $it"), it) }
        val fullWindow = listOf(5, 6, 7, 8)
        for (shown in 1..4) {
            assertEquals(fullWindow.take(shown),
                DialogueHudRenderer.visibleChoices(choices, 8, shown).map { it.index })
        }
    }

    @Test
    fun `all painted rows and panels have zero total advance during reveal and choice changes`() {
        val bundled = HudGlyphMetrics.bundled()
        for (revealed in listOf(0, 4, Int.MAX_VALUE)) {
            for (selected in 0..3) {
                val component = DialogueHudRenderer.render(
                    Component.text("Elder Rowan"),
                    Component.text("The ruins are dangerous. Take care, traveller."),
                    revealed,
                    (0..3).map { HudChoice(Component.text("Response $it"), it) },
                    selected,
                    hint = Component.text("W/S choose · Space confirm"),
                )
                component.children().forEach { part ->
                    val advance = HudTextLayout(bundled).glyphs(part).sumOf { glyph ->
                        val font = glyph.style.font().toString()
                        when {
                            font.endsWith("/space") -> when (glyph.codePoint) {
                                in 0xE100..0xE10B -> -(1 shl (glyph.codePoint - 0xE100))
                                else -> 1 shl (glyph.codePoint - 0xE110)
                            }
                            font.endsWith("/panels") -> when (glyph.codePoint) {
                                0xE020 -> 203
                                else -> 226
                            }
                            else -> glyph.advance
                        }
                    }
                    assertEquals(0, advance)
                }
            }
        }
    }

    @Test
    fun `renderer overrides authored fonts events and unsupported decorations`() {
        val text = Component.text("Rowan").font(Key.key("minecraft:default"))
            .clickEvent(ClickEvent.runCommand("/example"))
            .decorate(TextDecoration.BOLD, TextDecoration.UNDERLINED)
        val rendered = DialogueHudRenderer.render(text, Component.empty())
        val glyphs = layout.glyphs(rendered).filter { it.codePoint == 'R'.code }
        assertEquals(1, glyphs.size)
        val style = glyphs.single().style
        assertEquals(Key.key("departed:dialogue/row_0"), style.font())
        assertEquals(null, style.clickEvent())
        assertTrue(style.hasDecoration(TextDecoration.BOLD))
        assertFalse(style.hasDecoration(TextDecoration.UNDERLINED))
    }

    @Test
    fun `spoken only dialogue uses compact panel and continue hint row`() {
        val rendered = DialogueHudRenderer.render(Component.text("Rowan"), Component.text("Hello"),
            hint = Component.text("Space continue"))
        val glyphs = layout.glyphs(rendered)
        assertTrue(glyphs.any { it.codePoint == 0xE005 })
        assertFalse(glyphs.any { it.codePoint == 0xE000 })
        assertTrue(glyphs.any { it.style.font() == Key.key("departed:dialogue/row_15") })
        assertFalse(glyphs.any { it.style.font() == Key.key("departed:dialogue/row_5") })
    }

    @Test
    fun `pending choices reserve their full frame without leaking responses during body animation`() {
        val rendered = DialogueHudRenderer.render(
            Component.text("Rowan"), Component.text("Hello"), revealedCharacters = 2,
            choices = listOf(HudChoice(Component.text("Secret response"), 0)), shownChoices = 0,
        )
        val glyphs = layout.glyphs(rendered)
        assertTrue(glyphs.any { it.codePoint == 0xE010 })
        assertFalse(glyphs.any { it.style.font() == Key.key("departed:dialogue/row_5") })
        assertFalse(glyphs.any { it.style.font() == Key.key("departed:dialogue/row_6") })
    }

    @Test
    fun `frame and footer match the number of choices while their reveal is pending`() {
        for (count in 1..6) {
            val capacity = minOf(4, count)
            val rendered = DialogueHudRenderer.render(
                Component.text("Rowan"), Component.text("Hello"),
                choices = (0 until count).map { HudChoice(Component.text("Response $it"), it) },
                shownChoices = 0, hint = Component.text("Continue"),
            )
            val glyphs = layout.glyphs(rendered)
            assertTrue(glyphs.any { it.codePoint == 0xE00F + capacity })
            assertTrue(glyphs.any { it.style.font() == Key.key("departed:dialogue/row_${15 + capacity}") })
            assertFalse(glyphs.any { it.style.font() == Key.key("departed:dialogue/row_5") })
        }
    }

    @Test
    fun `long selected responses rotate complete two row pages and wrap back after six seconds`() {
        val label = "A".repeat(40) + "B".repeat(40) + "C".repeat(40) + "D".repeat(40)
        fun displayed(elapsed: Long): String {
            val rendered = DialogueHudRenderer.render(
                Component.text("Rowan"), Component.text("Hello"),
                choices = listOf(HudChoice(Component.text(label), 0)), elapsedMillis = elapsed,
            )
            return buildString {
                layout.glyphs(rendered).filter {
                    it.style.font() == Key.key("departed:dialogue/row_6") ||
                        it.style.font() == Key.key("departed:dialogue/row_7")
                }.filter { it.codePoint != '>'.code }.forEach { appendCodePoint(it.codePoint) }
            }
        }
        val first = displayed(0)
        val second = displayed(3_000)
        assertEquals(label, first + second)
        assertEquals(first, displayed(2_999))
        assertEquals(first, displayed(6_000))
        assertEquals(first, displayed(-1))
    }

    @Test
    fun `instant or skipped long bodies cycle every complete page without dropping earlier text`() {
        val body = "a\nb\nc\nd\ne\nf\ng\nh"
        fun displayed(elapsed: Long, revealed: Int = Int.MAX_VALUE): String {
            val rendered = DialogueHudRenderer.render(
                Component.text("Rowan"), Component.text(body), revealedCharacters = revealed,
                completedBodyMillis = elapsed,
            )
            return buildString {
                layout.glyphs(rendered).filter {
                    it.style.font() in (1..4).map { row -> Key.key("departed:dialogue/row_$row") }
                }.forEach { appendCodePoint(it.codePoint) }
            }
        }
        assertEquals("abcd", displayed(0))
        assertEquals("abcd", displayed(3_999))
        assertEquals("efgh", displayed(4_000))
        assertEquals("abcd", displayed(8_000))
        assertEquals("abcd", displayed(-1))
        assertEquals("abcdefgh", displayed(0, body.length) + displayed(4_000, body.length))
        assertEquals("e", displayed(8_000, 9))
    }
}
