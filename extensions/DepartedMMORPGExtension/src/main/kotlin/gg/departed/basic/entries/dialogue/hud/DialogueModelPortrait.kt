package gg.departed.basic.entries.dialogue.hud

import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * A viewer-only clone of a model NPC's model, projected into the dialogue HUD by the pack's shader, without changing
 * the NPC's shared model. Drawn by DepartedModels (the ModelEngine replacement) when it is installed
 * ([DialogueModelPortraitOwn]), by ModelEngine otherwise ([DialogueModelPortraitMe]); each of the two links against
 * its engine's API, so only the one in use is ever loaded.
 */
internal class DialogueModelPortrait(private val player: Player) {
    interface Backend {
        fun show(npcId: String?, facingDegrees: Double): Boolean
        fun tick(talking: Boolean)
        fun dispose()
    }

    private var backend: Backend? = null

    /** Resolve the NPC's model and create a private preview. Call on the Bukkit main thread. */
    fun show(npcId: String?, facingDegrees: Double = DialogueFacing.RIGHT): Boolean {
        val chosen = backend ?: when {
            Bukkit.getPluginManager().isPluginEnabled("DepartedModels") -> DialogueModelPortraitOwn(player)
            Bukkit.getPluginManager().isPluginEnabled("ModelEngine") -> DialogueModelPortraitMe(player)
            else -> return false
        }.also { backend = it }
        return chosen.show(npcId, facingDegrees)
    }

    fun tick(talking: Boolean) {
        backend?.tick(talking)
    }

    fun dispose() {
        backend?.dispose()
    }
}
