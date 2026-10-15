package gg.departed.basic.entries.dialogue.hud

import com.typewritermc.engine.paper.plugin

/**
 * Framing tweaks for ModelEngine portraits from dialogue.hud.models: offset-y moves the model in portrait-box
 * heights (+ up, -0.5 = half a box lower) and scale multiplies the auto-fit size. npcs.<npcId> overrides either.
 */
internal data class DialoguePortraitTuning(val offsetY: Double, val scale: Double) {
    companion object {
        const val DEFAULT_OFFSET_Y = -0.5

        fun resolve(npcId: String): DialoguePortraitTuning {
            val config = plugin.config
            val npcs = config.getConfigurationSection("dialogue.hud.models.npcs")
            val key = npcs?.getKeys(false)?.firstOrNull { it.equals(npcId, ignoreCase = true) }
            val npc = key?.let { npcs.getConfigurationSection(it) }
            val offsetY = npc?.takeIf { it.isSet("offset-y") }?.getDouble("offset-y")
                ?: config.getDouble("dialogue.hud.models.offset-y", DEFAULT_OFFSET_Y)
            val scale = npc?.takeIf { it.isSet("scale") }?.getDouble("scale")
                ?: config.getDouble("dialogue.hud.models.scale", 1.0)
            return DialoguePortraitTuning(offsetY.coerceIn(-5.0, 5.0), scale.coerceIn(0.05, 20.0))
        }
    }
}
