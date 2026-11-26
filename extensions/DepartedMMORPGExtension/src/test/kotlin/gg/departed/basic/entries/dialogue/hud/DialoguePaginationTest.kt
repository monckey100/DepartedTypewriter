package gg.departed.basic.entries.dialogue.hud

import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DialoguePaginationTest {
    private val layout = HudTextLayout(HudGlyphMetrics(emptyMap(), 1))
    private val paginator = DialoguePaginator(layout)

    private fun plain(component: Component): String =
        buildString { layout.glyphs(component).forEach { appendCodePoint(it.codePoint) } }

    private fun normalized(text: String): String = text.trim().replace(Regex("\\s+"), " ")

    @Test
    fun `eight equal sentences balance four and four instead of seven and one`() {
        val text = (1..8).joinToString("\n") { "Sentence $it." }
        val pages = paginator.split(Component.text(text), text, width = 80, rows = 7)
        assertEquals(2, pages.size)
        assertEquals(4, plain(pages[0].fresh).lines().size)
        assertEquals(4, plain(pages[1].fresh).lines().size)
        assertEquals("Sentence 4.", plain(pages[1].previous))
        assertEquals(5, layout.wrap(pages[1].body, 80).size)
    }

    @Test
    fun `context is grey but new authored colours and bold survive`() {
        val parts = (1..8).map {
            Component.text("Sentence $it.${if (it < 8) "\n" else ""}")
                .color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
        }
        val body = Component.join(JoinConfiguration.noSeparators(), parts)
        val pages = paginator.split(body, width = 80, rows = 7)
        assertEquals(2, pages.size)
        assertTrue(layout.glyphs(pages[1].previous).all { it.style.color() == NamedTextColor.DARK_GRAY })
        assertTrue(layout.glyphs(pages[1].fresh).all { it.style.color() == NamedTextColor.GOLD })
        assertTrue(layout.glyphs(pages[1].fresh).all { it.style.hasDecoration(TextDecoration.BOLD) })
    }

    @Test
    fun `carryover is context only while fresh text retains authored click actions`() {
        val click = ClickEvent.runCommand("/example")
        val text = (1..8).joinToString("\n") { "Sentence $it." }
        val pages = paginator.split(Component.text(text).clickEvent(click), width = 80, rows = 7)
        assertTrue(layout.glyphs(pages[1].previous).all { it.style.clickEvent() == null })
        assertTrue(layout.glyphs(pages[1].fresh).all { it.style.clickEvent() == click })
    }

    @Test
    fun `previous sentence is visible immediately and is never voiced twice`() {
        val text = (1..8).joinToString("\n") { "Sentence $it." }
        val pages = paginator.split(Component.text(text), text, width = 80, rows = 7)
        val next = pages[1]
        assertEquals(next.contextCharacters, next.revealedCharacters(0.0))
        assertEquals(layout.glyphs(next.body).size, next.revealedCharacters(1.0))
        assertEquals(normalized(text), normalized(pages.joinToString(" ") { it.voice }))
        assertFalse(next.voice.contains("Sentence 4."))
        assertTrue(next.text.contains("Sentence 5."))
        assertFalse(next.text.contains("Sentence 4."))
    }

    @Test
    fun `every box fits including the carryover and no new words are lost`() {
        val text = "First we observe every possibility. Every vowel, every turn, and every sudden event matters. " +
            "Atoms carry possible destinations. Choosing a timeline determines which destination you observe. " +
            "The river must still flow forwards. You will learn this one day."
        val pages = paginator.split(Component.text(text), text, width = 45, rows = 4)
        assertTrue(pages.size > 1)
        assertTrue(pages.all { layout.wrap(it.body, 45).size <= 4 })
        assertEquals(normalized(text), normalized(pages.joinToString(" ") { plain(it.fresh) }))
        assertEquals(normalized(text), normalized(pages.joinToString(" ") { it.voice }))
    }

    @Test
    fun `long sentences fall back to clauses and words without breaking Unicode`() {
        val text = "Before you decide, observe the river 😀 carefully, and follow every possible turning " +
            "through the verylongwordwithoutanyspaceswhatsoever until you can finally choose a destination."
        val pages = paginator.split(Component.text(text), text, width = 22, rows = 4)
        assertTrue(pages.size > 1)
        assertTrue(pages.all { layout.wrap(it.body, 22).size <= 4 })
        assertEquals(text.filterNot(Char::isWhitespace),
            pages.joinToString("") { plain(it.fresh) }.filterNot(Char::isWhitespace))
        assertFalse(pages.any { '\uFFFD' in plain(it.body) })
    }

    @Test
    fun `custom voice override is distributed once without carrying its context over`() {
        val body = (1..8).joinToString("\n") { "Sentence $it." }
        for (voice in listOf("A distinct first answer. A distinct second answer.", "Only three words")) {
            val pages = paginator.split(Component.text(body), voice, width = 80, rows = 7)
            assertEquals(normalized(voice), normalized(pages.joinToString(" ") { it.voice }))
        }
    }

    @Test
    fun `a short voice override is not delayed until the final page`() {
        val body = (1..8).joinToString("\n") { "Sentence $it." }
        val pages = paginator.split(Component.text(body), "Hello", width = 80, rows = 3)
        assertTrue(pages.size > 2)
        assertEquals("Hello", pages.first().voice)
        assertTrue(pages.drop(1).all { it.voice.isEmpty() })
    }

    @Test
    fun `short text and intentional silence keep the original presentation`() {
        val body = Component.text("  A short line.  ").color(NamedTextColor.AQUA)
        val pages = paginator.split(body)
        assertEquals(1, pages.size)
        assertEquals(body, pages.single().body)
        assertEquals(body, pages.single().fresh)
        assertEquals("", pages.single().voice)
        assertEquals("", plain(pages.single().previous))
    }

    @Test
    fun `abbreviations do not become the carryover sentence on their own`() {
        val text = "Dr. Henry waits.\nMr. Benny watches.\nProf. Miranda speaks.\nThe King listens."
        val pages = paginator.split(Component.text(text), text, width = 80, rows = 3)
        assertTrue(pages.size > 1)
        assertTrue(pages.drop(1).none { plain(it.previous) in listOf("Dr.", "Mr.", "Prof.") })
        assertEquals(normalized(text), normalized(pages.joinToString(" ") { plain(it.fresh) }))
    }

    @Test
    fun `page cursor stays within the authored entry until the final box`() {
        val text = (1..8).joinToString("\n") { "Sentence $it." }
        val cursor = DialoguePages(paginator.split(Component.text(text), text, width = 80, rows = 7))
        assertTrue(cursor.hasNext)
        assertEquals("  1/2", cursor.hint)
        assertTrue(cursor.advance())
        assertFalse(cursor.hasNext)
        assertEquals("  2/2", cursor.hint)
        assertFalse(cursor.advance())
        assertEquals(1, cursor.index)
    }

    @Test
    fun `authored animation duration is shared between new text only`() {
        val text = (1..8).joinToString("\n") { "Sentence $it." }
        val cursor = DialoguePages(paginator.split(Component.text(text), text, width = 80, rows = 7))
        val first = cursor.duration(Duration.ofSeconds(4))
        assertEquals(Duration.ZERO, cursor.duration(Duration.ZERO))
        cursor.advance()
        assertEquals(Duration.ofSeconds(4), first + cursor.duration(Duration.ofSeconds(4)))
    }
}
