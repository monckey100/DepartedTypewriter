package gg.departed.basic.entries.dialogue.hud

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DialogueModelFramingTest {
    private val pixel = 1f / 16f

    private fun box(x0: Float, y0: Float, x1: Float, y1: Float) =
        PortraitBounds(x0 * pixel, y0 * pixel, -0.25f, x1 * pixel, y1 * pixel, 0.25f)

    private fun assertNear(expected: Float, actual: Float, message: String) =
        assertTrue(abs(expected - actual) < 1e-4f, "$message: expected $expected, got $actual")

    @Test
    fun `a person is framed as a bust the size of the skin portraits`() {
        // 16 wide, 32 tall, an 8 pixel head on top.
        val frame = DialogueModelFraming.frame(PortraitSample(box(-8f, 0f, 8f, 32f), box(-4f, 24f, 4f, 32f)))
        assertNear(23f * pixel, frame.side, "23 skin pixels across")
        assertNear(0f, frame.x, "centred on the head")
        // 2.76 px of room above the head: the window's top edge is 0.12 sides over the head's top.
        assertNear((32f + 0.12f * 23f - 11.5f) * pixel, frame.y, "head near the top")
        assertTrue(frame.y - frame.side / 2f > 0f, "the legs are out of the shot")
    }

    @Test
    fun `a creature that is mostly head is framed whole and centred`() {
        // Ziggy: 7.5 x 10.6 with a cap as wide as the body for a head.
        val body = box(-3.75f, 0f, 3.75f, 10.6f)
        val frame = DialogueModelFraming.frame(PortraitSample(body, box(-3.75f, 4.8f, 3.75f, 10.6f)))
        assertNear(10.6f / 0.86f * pixel, frame.side, "the whole body with a margin")
        assertNear(5.3f * pixel, frame.y, "centred on the body")
    }

    @Test
    fun `a model without a head is framed whole and off-centre parts move the frame`() {
        val frame = DialogueModelFraming.frame(PortraitSample(box(-10f, 0f, 30f, 20f), null))
        assertNear(40f / 0.86f * pixel, frame.side, "the wider side decides")
        assertNear(10f * pixel, frame.x, "centred on what is drawn, not on the origin")
        assertNear(10f * pixel, frame.y, "centred vertically")
    }

    @Test
    fun `a hat counts as head and the bust still contains it`() {
        // A jester: 8 px face under 22 px of horns.
        val head = box(-11f, 24f, 11f, 36f)
        val frame = DialogueModelFraming.frame(PortraitSample(box(-11f, 0f, 11f, 36f), head))
        assertTrue(frame.side >= head.width / 0.9f - 1e-4f, "the horns fit")
        assertTrue(frame.side < 36f / 0.86f * pixel, "closer than the whole body")
        assertTrue(frame.y + frame.side / 2f >= head.maxY, "nothing cut off above")
    }

    @Test
    fun `a lying body is not framed below the floor`() {
        // Head on the ground at one end of a 60 x 12 body.
        val body = box(-30f, 0f, 30f, 12f)
        val frame = DialogueModelFraming.frame(PortraitSample(body, box(18f, 0f, 30f, 12f)))
        assertNear(24f * pixel, frame.x, "aimed at the head")
        assertNear(6f * pixel, frame.y, "centred on the body's height when all of it fits")
    }

    @Test
    fun `focus overrides the automatic shot`() {
        val sample = PortraitSample(box(-8f, 0f, 8f, 32f), box(-4f, 24f, 4f, 32f))
        val body = DialogueModelFraming.frame(sample, PortraitFocus.BODY)
        assertNear(32f / 0.86f * pixel, body.side, "whole body on request")
        val mostlyHead = PortraitSample(box(-4f, 0f, 4f, 10f), box(-4f, 4f, 4f, 10f))
        assertTrue(DialogueModelFraming.frame(mostlyHead, PortraitFocus.HEAD).x == 0f)
    }

    @Test
    fun `a loop keeps what it swept through and easing lands on the target`() {
        val low = PortraitSample(box(-4f, 0f, 4f, 10f), null)
        val high = PortraitSample(box(-4f, 6f, 4f, 16f), null)
        assertEquals(box(-4f, 0f, 4f, 16f), low.union(high).body)
        var frame = DialogueModelFraming.frame(low)
        val target = DialogueModelFraming.frame(low.union(high))
        repeat(40) { frame = frame.ease(target) }
        assertEquals(target, frame)
    }

    @Test
    fun `cubes are read around their group pivot in ModelEngine space from both file layouts`() {
        val head = UUID.randomUUID()
        val body = UUID.randomUUID()
        val hat = UUID.randomUUID()
        fun cube(id: String, from: String, to: String, extra: String = "") =
            """{"uuid":"$id","type":"cube","from":$from,"to":$to,"origin":[0,0,0]$extra}"""
        val elements = listOf(
            cube("e-body", "[-4,0,-2]", "[4,24,2]"),
            cube("e-head", "[-4,24,-4]", "[4,32,4]"),
            cube("e-hat", "[-1,32,-1]", "[1,34,1]", ""","inflate":1,"rotation":[0,90,0]"""),
        ).joinToString(",")
        val nested = """{"elements":[$elements],"outliner":[
            {"name":"body","uuid":"$body","origin":[0,12,0],"children":["e-body",
              {"name":"h_head","uuid":"$head","origin":[0,24,0],"children":["e-head",
                {"name":"hat","uuid":"$hat","origin":[0,32,0],"children":["e-hat"]}]}]}]}"""
        val listed = """{"elements":[$elements],"groups":[
            {"name":"body","uuid":"$body","origin":[0,12,0]},
            {"name":"h_head","uuid":"$head","origin":[0,24,0]},
            {"name":"hat","uuid":"$hat","origin":[0,32,0]}],"outliner":[
            {"uuid":"$body","children":["e-body",{"uuid":"$head","children":["e-head",{"uuid":"$hat","children":["e-hat"]}]}]}]}"""
        for (json in listOf(nested, listed)) {
            val geometry = DialogueModelGeometry.parse(Gson().fromJson(json, JsonObject::class.java))
            assertEquals(setOf(head, hat), geometry.head)
            val corners = geometry.corners.getValue(body)
            assertEquals(24, corners.size)
            // Blockbench x and z are negated; the pivot (0, 12, 0) is the origin.
            assertNear(4f * pixel, corners[0], "x of the first corner")
            assertNear(-12f * pixel, corners[1], "y of the first corner")
            assertNear(2f * pixel, corners[2], "z of the first corner")
            // Inflated by 1 and turned a quarter about y around (0, 0, 0): still a 4 x 4 x 4 box on the pivot.
            val hatCorners = geometry.corners.getValue(hat)
            for (axis in 0..2) {
                val values = hatCorners.filterIndexed { index, _ -> index % 3 == axis }
                val (low, high) = if (axis == 1) -1f to 3f else -2f to 2f
                assertNear(low * pixel, values.min(), "hat axis $axis low")
                assertNear(high * pixel, values.max(), "hat axis $axis high")
            }
        }
    }
}
