package gg.departed.basic.entries.dialogue.hud

import com.ticxo.modelengine.api.animation.ModelState
import com.ticxo.modelengine.api.model.ActiveModel
import com.ticxo.modelengine.api.model.render.DisplayBone
import com.ticxo.modelengine.core.animation.handler.PriorityHandler
import com.ticxo.modelengine.core.model.render.DisplayRendererImpl
import org.bukkit.entity.Display
import org.joml.Vector3f
import java.util.IdentityHashMap

/** Encodes only this clone's display transforms; its culling origins remain beside the viewer. */
internal class DialogueModelRenderer(model: ActiveModel, private val centerY: Float) : DisplayRendererImpl(model) {
    private val unmarked = IdentityHashMap<DisplayBone, Vector3f>()

    override fun initialize() {
        super.initialize()
        markPositions()
    }

    override fun readModelData() {
        unmarked.forEach { (bone, position) -> bone.position.set(Vector3f(position)) }
        unmarked.clear()
        super.readModelData()
        markPositions()
    }

    private fun markPositions() {
        val bones = (spawnQueue.values + rendered.values).distinct()
        bones.forEach { bone ->
            val position = Vector3f(bone.position.get())
            unmarked[bone] = position
            bone.position.set(Vector3f(position).add(0f, MARKER - centerY, 0f))
            bone.billboard.set(Display.Billboard.FIXED)
            bone.brightness.set(0x00F000F0)
            bone.step.set(true)
        }
    }

    companion object {
        const val MARKER = -32768f
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
