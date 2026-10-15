package gg.departed.basic.entries.dialogue.hud

import com.ticxo.modelengine.api.animation.ModelState
import com.ticxo.modelengine.api.model.ActiveModel
import com.ticxo.modelengine.api.model.bone.BoneBehaviorTypes
import com.ticxo.modelengine.api.model.bone.ModelBone
import com.ticxo.modelengine.api.model.render.DisplayBone
import com.ticxo.modelengine.core.animation.handler.PriorityHandler
import com.ticxo.modelengine.core.model.render.DisplayRendererImpl
import org.bukkit.entity.Display
import org.joml.Vector3f
import java.util.IdentityHashMap

/**
 * Encodes only this clone's display transforms; its culling origins remain beside the viewer.
 * Models without player limbs are also enlarged [SCALE] times under [SCALED_MARKER]: the shader divides the
 * enlargement back out, which shrinks the third-person camera's offset from the eye anchor to nothing.
 * Player-limb models keep real scale because their part index rides on 1024-block Y steps.
 * Constants must match departed_dialogue_model_vsh.glsl.
 */
internal class DialogueModelRenderer(
    private val activeModel: ActiveModel,
    private val centerY: Float,
) : DisplayRendererImpl(activeModel) {
    private val unmarked = IdentityHashMap<DisplayBone, Vector3f>()
    private val unscaled = IdentityHashMap<DisplayBone, Vector3f>()
    private var scale = 1f

    override fun initialize() {
        scale = if (hasPlayerLimbs()) 1f else SCALE
        super.initialize()
        markPositions()
    }

    override fun readModelData() {
        unmarked.forEach { (bone, position) -> bone.position.set(Vector3f(position)) }
        unscaled.forEach { (bone, size) -> bone.scale.set(Vector3f(size)) }
        unmarked.clear()
        unscaled.clear()
        super.readModelData()
        markPositions()
    }

    private fun markPositions() {
        val marker = if (scale == 1f) MARKER else SCALED_MARKER
        val bones = (spawnQueue.values + rendered.values).distinct()
        bones.forEach { bone ->
            val position = Vector3f(bone.position.get())
            unmarked[bone] = position
            bone.position.set(Vector3f(position).mul(scale).add(0f, marker - centerY * scale, 0f))
            if (scale != 1f) {
                val size = Vector3f(bone.scale.get())
                unscaled[bone] = size
                bone.scale.set(Vector3f(size).mul(scale))
            }
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

    companion object {
        const val MARKER = -32768f
        const val SCALED_MARKER = -2097152f
        const val SCALE = 1024f
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
