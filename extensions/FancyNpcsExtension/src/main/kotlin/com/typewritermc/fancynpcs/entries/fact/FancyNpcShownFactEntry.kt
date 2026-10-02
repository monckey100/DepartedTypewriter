package com.typewritermc.fancynpcs.entries.fact

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.ContentEditor
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.engine.paper.entry.entries.GroupEntry
import com.typewritermc.engine.paper.entry.entries.ReadableFactEntry
import com.typewritermc.engine.paper.facts.FactData
import com.typewritermc.fancynpcs.content.SelectFancyNpcContentMode
import com.typewritermc.fancynpcs.entries.entity.ReferenceFancyNpcEntry
import com.typewritermc.fancynpcs.findFancyNpc
import de.oliver.fancynpcs.api.Npc
import org.bukkit.entity.Player

@Entry("fancynpc_shown_fact", "Whether a FancyNpcs NPC is shown", Colors.PURPLE, "mingcute:eye-2-fill")
/**
 * A read-only fact that returns 1 when a referenced FancyNpcs NPC is shown for the player, otherwise 0.
 */
class FancyNpcShownFactEntry(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "",
    override val group: Ref<GroupEntry> = emptyRef(),
    @Help("The FancyNpcs name or id. Click the capture button and click an NPC in-game to fill this in. Takes priority over the NPC reference below.")
    @ContentEditor(SelectFancyNpcContentMode::class)
    val npcIdentifier: String = "",
    @Help("A reference NPC entry. Only used when the identifier above is left empty.")
    val npc: Ref<ReferenceFancyNpcEntry> = emptyRef(),
) : ReadableFactEntry {
    override fun readSinglePlayer(player: Player): FactData {
        val fancyNpc = findNpc() ?: return FactData(0)
        return FactData(if (fancyNpc.isShownFor(player)) 1 else 0)
    }

    private fun findNpc(): Npc? =
        npcIdentifier.takeIf { it.isNotBlank() }?.let { findFancyNpc(it) }
            ?: npc.get()?.findNpc()
}
