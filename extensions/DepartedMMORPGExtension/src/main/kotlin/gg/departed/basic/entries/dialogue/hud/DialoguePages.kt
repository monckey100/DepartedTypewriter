package gg.departed.basic.entries.dialogue.hud

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.minimessage.MiniMessage
import java.text.BreakIterator
import java.time.Duration
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** One readable box; [previous] is context and is never included in [voice]. */
internal data class DialoguePage(
    val fresh: Component,
    val previous: Component,
    val body: Component,
    val voice: String,
    val freshCharacters: Int,
    val contextCharacters: Int,
) {
    val text: String get() = MiniMessage.miniMessage().serialize(fresh)
    val recap: String get() = MiniMessage.miniMessage().serialize(previous)
    val displayText: String get() = MiniMessage.miniMessage().serialize(body)

    fun present(revealed: Component): Component = if (contextCharacters == 0) revealed else {
        Component.join(JoinConfiguration.noSeparators(), listOf(previous, Component.newline(), revealed))
    }

    fun revealedCharacters(percentage: Double): Int =
        contextCharacters + (freshCharacters * percentage.coerceIn(0.0, 1.0)).toInt()
}

/** Tracks boxes inside one authored entry. Advancing it must not finish the entry or apply its modifiers. */
internal class DialoguePages(val pages: List<DialoguePage>) {
    init {
        require(pages.isNotEmpty())
    }

    var index: Int = 0
        private set
    val current: DialoguePage get() = pages[index]
    val hasNext: Boolean get() = index < pages.lastIndex
    val hint: String get() = if (pages.size > 1) "  ${index + 1}/${pages.size}" else ""

    fun advance(): Boolean {
        if (!hasNext) return false
        index++
        return true
    }

    fun duration(authored: Duration): Duration {
        if (authored.isZero || pages.size == 1) return authored
        val total = pages.sumOf { it.freshCharacters }.coerceAtLeast(1)
        val nanos = (authored.toNanos().toDouble() * current.freshCharacters / total).roundToLong()
        return Duration.ofNanos(nanos.coerceAtLeast(1L))
    }

    companion object {
        private val paginator by lazy { DialoguePaginator(HudTextLayout(HudGlyphMetrics.bundled())) }

        fun create(body: Component, voice: String, portrait: Boolean): DialoguePages =
            DialoguePages(paginator.split(body, voice, if (portrait) 280 else 368))
    }
}

/** Balances newly read text at sentence boundaries, including the previous sentence in each box's budget. */
internal class DialoguePaginator(private val layout: HudTextLayout) {
    private data class Span(val start: Int, val end: Int)

    fun split(body: Component, voice: String = "", width: Int = 280, rows: Int = 4): List<DialoguePage> {
        require(width > 0 && rows > 1)
        val glyphs = layout.glyphs(body)
        if (layout.wrap(glyphs, width).size <= rows) {
            return listOf(DialoguePage(body, Component.empty(), body, voice, glyphs.size, 0))
        }
        var units = sentences(plain(glyphs))
        var partition = partition(glyphs, units, width, rows)
        if (partition == null) {
            units = units.flatMap { fragments(glyphs, it, width, rows / 2) }
            partition = checkNotNull(partition(glyphs, units, width, rows)) {
                "Unable to fit dialogue fragments into the text box"
            }
        }
        val fresh = partition.map { (start, end) -> glyphs.subList(units[start].start, units[end - 1].end) }
        val voices = voicePages(voice, plain(glyphs), fresh.map(::plain))
        return partition.mapIndexed { index, (start, _) ->
            val previous = if (start == 0) emptyList() else glyphs.subList(units[start - 1].start, units[start - 1].end)
            page(fresh[index], previous, voices[index])
        }
    }

    private fun partition(glyphs: List<HudGlyph>, units: List<Span>, width: Int, rows: Int): List<Pair<Int, Int>>? {
        val count = units.size
        if (count == 0) return emptyList()
        val ends = Array(count) { mutableListOf<Int>() }
        for (start in 0 until count) {
            val previous = if (start == 0) emptyList() else glyphs.subList(units[start - 1].start, units[start - 1].end)
            for (end in start + 1..count) {
                val fresh = glyphs.subList(units[start].start, units[end - 1].end)
                if (layout.wrap(withContext(fresh, previous), width).size > rows) break
                ends[start].add(end)
            }
        }
        val minimum = IntArray(count + 1) { count + 1 }
        minimum[count] = 0
        for (start in count - 1 downTo 0) {
            minimum[start] = ends[start].minOfOrNull { minimum[it] + 1 } ?: count + 1
        }
        if (minimum[0] > count) return null
        val pageCount = minimum[0]
        val weights = DoubleArray(count + 1)
        units.forEachIndexed { index, span ->
            weights[index + 1] = weights[index] + glyphs.subList(span.start, span.end).sumOf { it.advance }
        }
        val target = weights[count] / pageCount
        val cost = Array(pageCount + 1) { DoubleArray(count + 1) { Double.POSITIVE_INFINITY } }
        val next = Array(pageCount + 1) { IntArray(count + 1) { -1 } }
        cost[0][count] = 0.0
        for (remaining in 1..pageCount) {
            for (start in count - 1 downTo 0) {
                for (end in ends[start]) {
                    if (!cost[remaining - 1][end].isFinite()) continue
                    val difference = weights[end] - weights[start] - target
                    val candidate = difference * difference + cost[remaining - 1][end]
                    if (candidate < cost[remaining][start]) {
                        cost[remaining][start] = candidate
                        next[remaining][start] = end
                    }
                }
            }
        }
        val result = mutableListOf<Pair<Int, Int>>()
        var start = 0
        for (remaining in pageCount downTo 1) {
            val end = next[remaining][start]
            if (end <= start) return null
            result += start to end
            start = end
        }
        return result.takeIf { start == count }
    }

    private fun sentences(text: String): List<Span> {
        if (text.isEmpty()) return emptyList()
        val iterator = BreakIterator.getSentenceInstance(Locale.ROOT)
        iterator.setText(text)
        val result = mutableListOf<Span>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val part = text.substring(start, end).trimEnd()
            val abbreviation = Regex("(?i)(?:\\b(?:mr|mrs|ms|dr|prof|sr|jr|st|vs)|e\\.g|i\\.e)\\.$")
            if (end == text.length || !abbreviation.containsMatchIn(part)) {
                result += Span(text.codePointCount(0, start), text.codePointCount(0, end))
                start = end
            }
            end = iterator.next()
        }
        return result.ifEmpty { listOf(Span(0, text.codePointCount(0, text.length))) }
    }

    private fun fragments(glyphs: List<HudGlyph>, span: Span, width: Int, rows: Int): List<Span> {
        if (layout.wrap(trim(glyphs.subList(span.start, span.end)), width).size <= rows) return listOf(span)
        val result = mutableListOf<Span>()
        var start = span.start
        while (start < span.end) {
            var end = start + 1
            while (end <= span.end && layout.wrap(trim(glyphs.subList(start, end)), width).size <= rows) end++
            val limit = (end - 1).coerceAtLeast(start + 1)
            if (limit == span.end) {
                result += Span(start, limit)
                break
            }
            val clause = (start + 1..limit).lastOrNull {
                glyphs[it - 1].codePoint in listOf(','.code, ';'.code, ':'.code, '\n'.code)
            }
            val word = (start + 1..limit).lastOrNull { Character.isWhitespace(glyphs[it - 1].codePoint) }
            val cut = clause ?: word ?: limit
            result += Span(start, cut)
            start = cut
        }
        return result
    }

    private fun withContext(fresh: List<HudGlyph>, previous: List<HudGlyph>): List<HudGlyph> {
        val context = trim(previous)
        val content = trim(fresh)
        if (context.isEmpty()) return content
        return recap(context) +
            HudGlyph('\n'.code, Style.empty(), -1, 0) + content
    }

    private fun page(fresh: List<HudGlyph>, previous: List<HudGlyph>, voice: String): DialoguePage {
        val context = recap(previous)
        val content = trim(fresh)
        return DialoguePage(
            component(content), component(context), component(withContext(content, context)), voice,
            content.size, if (context.isEmpty()) 0 else context.size + 1,
        )
    }

    private fun component(glyphs: List<HudGlyph>): Component {
        val parts = mutableListOf<Component>()
        var style: Style? = null
        val text = StringBuilder()
        fun flush() {
            if (text.isEmpty()) return
            parts += Component.text(text.toString()).style(style ?: Style.empty())
            text.setLength(0)
        }
        for (glyph in glyphs) {
            if (style != glyph.style) {
                flush()
                style = glyph.style
            }
            text.appendCodePoint(glyph.codePoint)
        }
        flush()
        return Component.join(JoinConfiguration.noSeparators(), parts)
    }

    private fun voicePages(voice: String, original: String, fresh: List<String>): List<String> {
        if (voice.isBlank()) return List(fresh.size) { "" }
        if (voice.trim() == original.trim()) return fresh.map(String::trim)
        if (fresh.size == 1) return listOf(voice)
        val sentenceEnds = sentences(voice).map { it.end }.toMutableList()
        val codePoints = voice.codePoints().toArray()
        val boundaries = if (sentenceEnds.size >= fresh.size) sentenceEnds else {
            codePoints.indices.filter { Character.isWhitespace(codePoints[it]) }.map { it + 1 } + codePoints.size
        }
        val result = mutableListOf<String>()
        val totalWeight = fresh.sumOf { it.codePointCount(0, it.length) }.coerceAtLeast(1)
        var weight = 0
        var start = 0
        for (index in fresh.indices) {
            weight += fresh[index].codePointCount(0, fresh[index].length)
            val target = codePoints.size.toDouble() * weight / totalWeight
            val remaining = fresh.lastIndex - index
            val candidates = boundaries.filter { boundary ->
                boundary > start && (boundaries.size < fresh.size ||
                    boundaries.count { next -> next > boundary } >= remaining)
            }
            val end = if (index == fresh.lastIndex) codePoints.size else {
                candidates.minByOrNull { abs(it - target) } ?: start
            }
            result += buildString { for (position in start until end) appendCodePoint(codePoints[position]) }.trim()
            start = end
        }
        return result
    }

    private fun recap(glyphs: List<HudGlyph>): List<HudGlyph> = trim(glyphs).map {
        it.copy(style = it.style.color(NamedTextColor.DARK_GRAY).clickEvent(null).hoverEvent(null).insertion(null))
    }

    private fun trim(glyphs: List<HudGlyph>): List<HudGlyph> =
        glyphs.dropWhile { Character.isWhitespace(it.codePoint) }.dropLastWhile { Character.isWhitespace(it.codePoint) }

    private fun plain(glyphs: List<HudGlyph>): String =
        buildString { glyphs.forEach { appendCodePoint(it.codePoint) } }
}
