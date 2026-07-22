package com.typewritermc.departednpcs.entries.entity

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Tags
import com.typewritermc.departednpcs.departedNpcApi
import com.typewritermc.departednpcs.matchesDepartedNpc
import com.typewritermc.engine.paper.entry.StaticEntry
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.SoundEmitter
import com.typewritermc.engine.paper.entry.entries.SoundSourceEntry
import com.typewritermc.engine.paper.entry.entries.SpeakerEntry
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.utils.Sound
import org.bukkit.entity.Player

@Tags("reference_npc", "reference_departednpc")
@Entry("reference_departednpc", "Reference a DepartedNPC NPC", Colors.ORANGE, "fa-solid:user-tie")
/**
 * A static entry that references an NPC managed by DepartedNPC (by its id).
 */
class ReferenceDepartedNpcEntry(
    override val id: String = "",
    override val name: String = "",
    override val displayName: Var<String> = ConstVar(""),
    override val sound: Var<Sound> = ConstVar(Sound.EMPTY),
    @Help("The DepartedNPC id (as used in /npc). This is the file name under plugins/DepartedNPC/npcs.")
    val npcIdentifier: String = "",
) : SoundSourceEntry, SpeakerEntry, StaticEntry {

    fun matches(npcId: String): Boolean = matchesDepartedNpc(npcId, npcIdentifier)

    override fun getEmitter(player: Player): SoundEmitter {
        val entityId = departedNpcApi()?.getEntityId(npcIdentifier.trim()) ?: -1
        return if (entityId > 0) SoundEmitter(entityId) else SoundEmitter(player.entityId)
    }
}
