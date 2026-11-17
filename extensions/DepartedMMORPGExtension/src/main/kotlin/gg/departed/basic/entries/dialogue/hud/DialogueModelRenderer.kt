package gg.departed.basic.entries.dialogue.hud

import com.ticxo.modelengine.api.animation.ModelState
import com.ticxo.modelengine.api.model.ActiveModel
import com.ticxo.modelengine.api.model.bone.BoneBehaviorTypes
import com.ticxo.modelengine.api.model.bone.ModelBone
import com.ticxo.modelengine.api.model.render.DisplayBone
import com.ticxo.modelengine.core.animation.handler.PriorityHandler
import com.ticxo.modelengine.core.model.render.DisplayRendererImpl
import org.bukkit.entity.Display
import org.joml.Matrix4f
import org.joml.Vector3f
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Encodes only this clone's display transforms; its culling origins remain beside the viewer.
 * Models without player limbs are also enlarged [SCALE] times under [SCALED_MARKER]: the shader divides the
 * enlargement back out, which shrinks the third-person camera's offset from the eye anchor to nothing.
 * Player-limb models keep real scale because their part index rides on 1024-block Y steps.
 * Constants must match departed_dialogue_model_vsh.glsl.
 *
 * Framing: every tick the cubes of [geometry] are put through the bones' live matrices and the shader's view, so
 * the window is aimed at what the model looks like right now (see [DialogueModelFraming]) instead of at its
 * hitbox. The model's own scale does the zoom; the bones are moved so the framed point sits in the window's centre.
 */
internal class DialogueModelRenderer(
    private val activeModel: ActiveModel,
    private val geometry: DialogueModelGeometry?,
    private val track: DialoguePortraitTrack,
    private val tuning: DialoguePortraitTuning,
) : DisplayRendererImpl(activeModel) {
    private val unmarked = IdentityHashMap<DisplayBone, Vector3f>()
    private val unscaled = IdentityHashMap<DisplayBone, Vector3f>()
    private var enlarge = 1f
    private var frame: PortraitFrame? = null
    /** False until the bones have been posed at the framed scale: nothing is drawn unframed. */
    private var revealed = false

    override fun initialize() {
        enlarge = if (hasPlayerLimbs()) 1f else SCALE
        super.initialize()
        place(activeModel.scale.y())
    }

    override fun readModelData() {
        if (!isInitialized) return
        unmarked.forEach { (bone, position) -> bone.position.set(Vector3f(position)) }
        unscaled.forEach { (bone, size) -> bone.scale.set(Vector3f(size)) }
        unmarked.clear()
        unscaled.clear()
        super.readModelData()
        // The scale this tick's bones were posed with; reframe() may ask for another one for the next tick.
        val posedAt = activeModel.scale.y()
        reframe(posedAt)
        place(posedAt)
    }

    /** Runs right after the bones were posed for this tick, on the thread that posed them. */
    private fun reframe(posedAt: Float) {
        val measured = measure() ?: return
        val target = DialogueModelFraming.frame(track.add(measured), tuning.focus)
        val next = frame?.ease(target) ?: target
        frame = next
        val fit = tuning.fit(next)
        if (abs(posedAt - fit) <= fit * 0.01f) revealed = true
        // Takes effect when the bones are posed next tick; an eased zoom trails by that one tick.
        if (abs(posedAt - fit) > fit * 1e-5f) activeModel.setScale(fit.toDouble())
    }

    private fun measure(): PortraitSample? {
        val view = Matrix4f().rotateY(VIEW_YAW).rotateX(VIEW_PITCH).rotateY(pivot.yaw.get() ?: return null)
        val body = Extent()
        val head = Extent()
        val matrix = Matrix4f()
        val point = Vector3f()
        if (geometry != null) for (bone in activeModel.bones.values) {
            if (!bone.isRenderer || !bone.isVisible || bone.isEffectivelyInvisible) continue
            val id = bone.blueprintBone.uuid
            val corners = geometry.corners[id] ?: continue
            val isHead = id in geometry.head
            view.mul(bone.transformMatrix, matrix)
            for (at in corners.indices step 3) {
                matrix.transformPosition(corners[at], corners[at + 1], corners[at + 2], point)
                body.add(point)
                if (isHead) head.add(point)
            }
        }
        if (body.empty) {
            // Nothing readable (no .bbmodel, or every cube hidden): frame the blueprint's hitbox instead.
            val hitbox = activeModel.blueprint.mainHitbox
            val halfWidth = hitbox.width.toFloat() / 2f
            val halfDepth = hitbox.depth.toFloat() / 2f
            for (corner in 0 until 8) {
                view.transformPosition(
                    if (corner and 1 == 0) -halfWidth else halfWidth,
                    if (corner and 2 == 0) 0f else hitbox.height.toFloat(),
                    if (corner and 4 == 0) -halfDepth else halfDepth,
                    point,
                )
                body.add(point)
            }
        }
        val bounds = body.bounds()?.takeIf(PortraitBounds::finite) ?: return null
        return PortraitSample(bounds, head.bounds()?.takeIf(PortraitBounds::finite))
    }

    private fun place(scale: Float) {
        val framed = frame
        // ModelEngine hangs the bones under a pivot raised by the mount offset and takes that offset back out of
        // every bone; added back here, the bones are measured from the model's feet again.
        val raised = Vector3f(pivot.getMountOffset(activeModel.blueprint.mainHitbox.eyeHeight.toFloat() * scale))
        val centre = Vector3f()
        if (framed != null) {
            // Window offsets are in box sizes; one box is BOX_BLOCKS scaled blocks, so unscaled it is that / fit.
            // Back out of the view (the inverse of Ry * Rx) into the space the bone positions are in.
            val box = DialogueModelFraming.BOX_BLOCKS / tuning.fit(framed)
            centre.set(framed.x - tuning.offsetX.toFloat() * box, framed.y - tuning.offsetY.toFloat() * box, framed.z)
                .rotateY(-VIEW_YAW).rotateX(-VIEW_PITCH).mul(scale)
        }
        val marker = if (enlarge == 1f) MARKER else SCALED_MARKER
        val bones = (spawnQueue.values + rendered.values).distinct()
        bones.forEach { bone ->
            val position = Vector3f(bone.position.get())
            unmarked[bone] = position
            bone.position.set(Vector3f(position).add(raised).sub(centre).mul(enlarge).sub(raised).add(0f, marker, 0f))
            val size = Vector3f(bone.scale.get())
            unscaled[bone] = size
            bone.scale.set(if (revealed) Vector3f(size).mul(enlarge) else Vector3f())
            bone.billboard.set(Display.Billboard.FIXED)
            bone.brightness.set(0x00F000F0)
            bone.step.set(true)
        }
    }

    private fun hasPlayerLimbs(): Boolean {
        fun limb(bone: ModelBone): Boolean =
            bone.getBoneBehavior(BoneBehaviorTypes.PLAYER_LIMB).isPresent || bone.children.values.any(::limb)
        return activeModel.bones.values.any(::limb)
    }

    private class Extent {
        private var minX = Float.POSITIVE_INFINITY
        private var minY = Float.POSITIVE_INFINITY
        private var minZ = Float.POSITIVE_INFINITY
        private var maxX = Float.NEGATIVE_INFINITY
        private var maxY = Float.NEGATIVE_INFINITY
        private var maxZ = Float.NEGATIVE_INFINITY
        val empty get() = minX > maxX

        fun add(point: Vector3f) {
            minX = min(minX, point.x); minY = min(minY, point.y); minZ = min(minZ, point.z)
            maxX = max(maxX, point.x); maxY = max(maxY, point.y); maxZ = max(maxZ, point.z)
        }

        fun bounds(): PortraitBounds? = if (empty) null else PortraitBounds(minX, minY, minZ, maxX, maxY, maxZ)
    }

    companion object {
        const val MARKER = -32768f
        const val SCALED_MARKER = -2097152f
        const val SCALE = 1024f
        /** The fixed turn in departed_dialogue_model_vsh.glsl's projection: Ry(VIEW_YAW) * Rx(VIEW_PITCH). */
        const val VIEW_YAW = -0.23f
        const val VIEW_PITCH = 0.10f
    }
}

/**
 * Preview animation advances bone transforms while skipping global script and effect keyframes.
 * The default SPAWN state never plays: the portrait should just be there, not pop in on every line.
 */
internal class DialoguePortraitAnimationHandler(model: ActiveModel) : PriorityHandler(model) {
    override fun tickGlobal() = Unit

    override fun playState(state: ModelState) {
        if (state != ModelState.SPAWN) super.playState(state)
    }
}
