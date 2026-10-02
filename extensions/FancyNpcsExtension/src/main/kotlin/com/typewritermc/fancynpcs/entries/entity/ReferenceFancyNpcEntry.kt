package com.typewritermc.fancynpcs.entries.entity

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.extension.annotations.ContentEditor
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Tags
import com.typewritermc.engine.paper.entry.StaticEntry
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.SoundEmitter
import com.typewritermc.engine.paper.entry.entries.SoundSourceEntry
import com.typewritermc.engine.paper.entry.entries.SpeakerEntry
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.utils.Sound
import com.typewritermc.fancynpcs.content.SelectFancyNpcContentMode
import com.typewritermc.fancynpcs.findFancyNpc
import com.typewritermc.fancynpcs.matchesFancyIdentifier
import de.oliver.fancynpcs.api.Npc
import org.bukkit.entity.Player

@Tags("reference_npc", "reference_fancynpc")
@Entry("reference_fancynpc", "Reference a FancyNpcs NPC", Colors.ORANGE, "fa-solid:user-tie")
/**
 * A static entry that references an NPC managed by FancyNpcs.
 */
class ReferenceFancyNpcEntry(
    override val id: String = "",
    override val name: String = "",
    override val displayName: Var<String> = ConstVar(""),
    override val sound: Var<Sound> = ConstVar(Sound.EMPTY),
    @Help("The FancyNpcs name, id, or entity id. Click the capture button and click an NPC in-game to fill this in.")
    @ContentEditor(SelectFancyNpcContentMode::class)
    val npcIdentifier: String = "",
) : SoundSourceEntry, SpeakerEntry, StaticEntry {
    fun findNpc(): Npc? = findFancyNpc(npcIdentifier)

    fun matches(npc: Npc): Boolean = npc.matchesFancyIdentifier(npcIdentifier)

    override fun getEmitter(player: Player): SoundEmitter {
        val npc = findNpc() ?: return SoundEmitter(player.entityId)
        return SoundEmitter(npc.entityId)
    }
}
