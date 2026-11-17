package gg.departed.basic.entries.dialogue.hud

import com.typewritermc.engine.paper.plugin
import org.bukkit.configuration.ConfigurationSection

/**
 * Optional nudges on top of the automatic framing of ModelEngine portraits, from dialogue.hud.framing: offset-x and
 * offset-y move the model in portrait-box sizes (+ right / + up), scale multiplies the automatic size, and focus
 * picks the shot (auto, head or body). npcs.<npcId> overrides any of them.
 * The section is not the older dialogue.hud.models: those values corrected the hitbox framing and would misplace
 * this one if a config rendered before the change were read.
 */
internal data class DialoguePortraitTuning(
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
    val scale: Double = 1.0,
    val focus: PortraitFocus = PortraitFocus.AUTO,
) {
    /** The model scale that fits [frame] into the portrait window. */
    fun fit(frame: PortraitFrame): Float = (DialogueModelFraming.BOX_BLOCKS / frame.side * scale).toFloat()

    companion object {
        private const val ROOT = "dialogue.hud.framing"

        fun resolve(npcId: String): DialoguePortraitTuning {
            val config = plugin.config
            val npcs = config.getConfigurationSection("$ROOT.npcs")
            val key = npcs?.getKeys(false)?.firstOrNull { it.equals(npcId, ignoreCase = true) }
            val npc = key?.let { npcs.getConfigurationSection(it) }
            fun number(name: String, default: Double) =
                npc?.takeIf { it.isSet(name) }?.getDouble(name) ?: config.getDouble("$ROOT.$name", default)
            return DialoguePortraitTuning(
                number("offset-x", 0.0).coerceIn(-5.0, 5.0),
                number("offset-y", 0.0).coerceIn(-5.0, 5.0),
                number("scale", 1.0).coerceIn(0.05, 20.0),
                focus(npc) ?: focus(config.getConfigurationSection(ROOT)) ?: PortraitFocus.AUTO,
            )
        }

        private fun focus(section: ConfigurationSection?): PortraitFocus? =
            section?.getString("focus")?.let { name -> PortraitFocus.entries.firstOrNull { it.name.equals(name, true) } }
    }
}
