package gg.departed.basic.entries.dialogue

import com.monckey100.departedrpg.api.DepartedRpgAPI
import com.typewritermc.engine.paper.utils.asMini
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/** Dialogue the player saw goes to DepartedRPG's story chat channel (the /story view). */
object StoryLog {
    private val api: DepartedRpgAPI? by lazy {
        runCatching { Bukkit.getServicesManager().load(DepartedRpgAPI::class.java) }.getOrNull()
    }

    /** [speaker] and [text] are MiniMessage as authored (already placeholder-parsed). */
    fun record(player: Player, speaker: String, text: String) {
        if (text.isBlank()) return
        val a = api ?: return
        runCatching {
            val legacy = LegacyComponentSerializer.legacySection()
            a.recordStory(player, legacy.serialize(speaker.asMini()), legacy.serialize(text.asMini()))
        }
    }
}
