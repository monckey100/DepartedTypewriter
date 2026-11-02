package gg.departed.basic.entries.dialogue

import com.monckey100.departedrpg.api.DepartedRpgAPI
import com.typewritermc.engine.paper.utils.asMini
import com.typewritermc.engine.paper.utils.stripped
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
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

    /**
     * Story barks (`dsay` without `p:0`, `departed_bark`) also go to the ALL chat view (owner 2026-11-01).
     * The line is sent untagged: DepartedRPG files it as SYSTEM, which shows in ALL and stays out of
     * /story, where [record] already put the story copy. Same look as DepartedRPG's story lines.
     */
    fun showInAll(player: Player, speaker: String, text: String) {
        if (text.isBlank()) return
        runCatching {
            val name = speaker.asMini()
            val body = text.asMini()
            val line = if (speaker.stripped().isNotBlank())
                Component.text("")
                    .append(name.colorIfAbsent(TextColor.color(0xFFD365)))
                    .append(Component.text(": ", NamedTextColor.GRAY))
                    .append(body.colorIfAbsent(TextColor.color(0xF0E8D6)))
            else body.colorIfAbsent(NamedTextColor.GRAY)
            player.sendMessage(line)
        }
    }
}
