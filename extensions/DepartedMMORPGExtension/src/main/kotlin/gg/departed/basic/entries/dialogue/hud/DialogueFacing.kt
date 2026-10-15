package gg.departed.basic.entries.dialogue.hud

import com.typewritermc.engine.paper.plugin

/**
 * Portrait facing in degrees: 0 looks at the viewer, positive turns toward screen right (toward the speech).
 * A line's portraitFacing beats dialogue.hud.facing.npcs.<npcId>, which beats dialogue.hud.facing.default.
 */
object DialogueFacing {
    const val RIGHT = 30.0
    const val MAX = 90.0

    fun resolve(lineOverride: String?, npcId: String?): Double {
        parse(lineOverride)?.let { return it }
        val config = plugin.config
        npcId?.trim()?.takeIf(String::isNotEmpty)?.let { id ->
            val npcs = config.getConfigurationSection("dialogue.hud.facing.npcs")
            val key = npcs?.getKeys(false)?.firstOrNull { it.equals(id, ignoreCase = true) }
            if (key != null) parse(npcs.getString(key))?.let { return it }
        }
        return parse(config.getString("dialogue.hud.facing.default")) ?: RIGHT
    }

    fun parse(value: String?): Double? {
        val text = value?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
        val degrees = when (text) {
            "right" -> RIGHT
            "left" -> -RIGHT
            "front", "center", "centre", "forward" -> 0.0
            else -> text.removeSuffix("deg").trim().toDoubleOrNull() ?: return null
        }
        return degrees.coerceIn(-MAX, MAX)
    }
}
