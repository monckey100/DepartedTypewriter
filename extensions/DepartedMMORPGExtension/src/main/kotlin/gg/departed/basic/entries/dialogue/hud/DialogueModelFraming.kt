package gg.departed.basic.entries.dialogue.hud

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** An extent in the portrait's view: x right, y up, z toward the viewer, in unscaled model blocks. */
internal data class PortraitBounds(
    val minX: Float, val minY: Float, val minZ: Float,
    val maxX: Float, val maxY: Float, val maxZ: Float,
) {
    val width get() = maxX - minX
    val height get() = maxY - minY
    val finite get() = floatArrayOf(minX, minY, minZ, maxX, maxY, maxZ).all(Float::isFinite) &&
        minX <= maxX && minY <= maxY && minZ <= maxZ

    fun union(other: PortraitBounds) = PortraitBounds(
        min(minX, other.minX), min(minY, other.minY), min(minZ, other.minZ),
        max(maxX, other.maxX), max(maxY, other.maxY), max(maxZ, other.maxZ),
    )
}

/** What one look at a posed model found: everything it draws, and its head when it has one. */
internal data class PortraitSample(val body: PortraitBounds, val head: PortraitBounds?) {
    fun union(other: PortraitSample) = PortraitSample(
        body.union(other.body),
        if (head != null && other.head != null) head.union(other.head) else head ?: other.head,
    )
}

/** The square of the view the portrait window shows: its centre and the length of its side. */
internal data class PortraitFrame(val x: Float, val y: Float, val z: Float, val side: Float) {
    /** A step toward [target]; lands on it once the rest would be a fraction of a pixel. */
    fun ease(target: PortraitFrame): PortraitFrame {
        val near = side * 0.004f
        if (abs(target.x - x) < near && abs(target.y - y) < near && abs(target.side - side) < near) return target
        fun step(from: Float, to: Float) = from + (to - from) * 0.35f
        return PortraitFrame(step(x, target.x), step(y, target.y), step(z, target.z), step(side, target.side))
    }
}

internal enum class PortraitFocus { AUTO, HEAD, BODY }

internal object DialogueModelFraming {
    /** The portrait window in blocks at scale 1: 96 px at 46 px per block (departed_dialogue_model_vsh.glsl). */
    const val BOX_BLOCKS = 96f / 46f
    /** How much of the window a whole body may fill; the rest is margin. */
    private const val FILL = 0.86f
    /** A bust is this many heads across: the skin portraits' 23 skin pixels around an 8 pixel head. */
    private const val BUST = 2.875f
    /** Hats, horns and crowns count as head; at most this much of a bust's window. */
    private const val HEAD_FILL = 0.9f
    /** Room above the head in a bust, in window sides (the skin portraits' 2.8 of 23 pixels). */
    private const val HEADROOM = 0.12f

    /**
     * Aims at the head when that is a closer shot than the whole body - a person - and otherwise centres the
     * body: no head, or a head that is most of the creature. Both shots are the same size where they meet, so a
     * model near the line looks alike either way.
     */
    fun frame(sample: PortraitSample, focus: PortraitFocus = PortraitFocus.AUTO): PortraitFrame {
        val body = sample.body
        val whole = max(max(body.width, body.height) / FILL, 0.05f)
        val centreZ = (body.minZ + body.maxZ) / 2f
        val head = sample.head.takeIf { focus != PortraitFocus.BODY }
        val bust = head?.let {
            max(max(BUST * min(it.width, it.height), max(it.width, it.height) / HEAD_FILL), 0.05f)
        }
        if (head == null || bust == null || (bust >= whole && focus != PortraitFocus.HEAD)) {
            return PortraitFrame((body.minX + body.maxX) / 2f, (body.minY + body.maxY) / 2f, centreZ, whole)
        }
        val margin = bust * (1f - FILL) / 2f
        val headroom = min(HEADROOM * bust, (bust - head.height) / 2f)
        val y = if (body.height + margin * 2f <= bust) (body.minY + body.maxY) / 2f
        // Head near the top of the window, but never looking below the feet while there is body above the window.
        else max(head.maxY + headroom - bust / 2f, body.minY - margin + bust / 2f)
        return PortraitFrame((head.minX + head.maxX) / 2f, y, centreZ, bust)
    }
}

/**
 * What every portrait of one model, animation and facing has measured so far. A portrait is rebuilt for each line
 * of a conversation, so the framing has to be there before the first frame instead of settling again every line.
 * A looping animation keeps everything it has swept through (an idle sway never leaves the window, and the window
 * does not follow it); a held pose is framed where it is now.
 */
internal class DialoguePortraitTrack private constructor(private val accumulate: Boolean) {
    @Volatile
    var sample: PortraitSample? = null
        private set

    @Synchronized
    fun add(measured: PortraitSample): PortraitSample {
        val merged = if (accumulate) sample?.union(measured) ?: measured else measured
        sample = merged
        return merged
    }

    companion object {
        private data class Key(val model: String, val stamp: Long, val animation: String?, val facing: Int)

        private val tracks = ConcurrentHashMap<Key, DialoguePortraitTrack>()

        fun of(model: String, stamp: Long, animation: String?, looping: Boolean, facingDegrees: Double) =
            tracks.computeIfAbsent(Key(model, stamp, animation, Math.round(facingDegrees * 10.0).toInt())) {
                DialoguePortraitTrack(looping)
            }
    }
}
