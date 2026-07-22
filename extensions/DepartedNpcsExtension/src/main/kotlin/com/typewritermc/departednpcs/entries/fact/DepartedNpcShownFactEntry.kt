package com.typewritermc.departednpcs.entries.fact

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.departednpcs.departedNpcApi
import com.typewritermc.departednpcs.entries.entity.ReferenceDepartedNpcEntry
import com.typewritermc.engine.paper.entry.entries.GroupEntry
import com.typewritermc.engine.paper.entry.entries.ReadableFactEntry
import com.typewritermc.engine.paper.facts.FactData
import org.bukkit.entity.Player

@Entry("departednpc_shown_fact", "Whether a DepartedNPC NPC is shown", Colors.PURPLE, "mingcute:eye-2-fill")
/**
 * A read-only fact that returns 1 when a referenced DepartedNPC NPC is visible for the player, else 0.
 */
class DepartedNpcShownFactEntry(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "",
    override val group: Ref<GroupEntry> = emptyRef(),
    @Help("The DepartedNPC id. Takes priority over the NPC reference below.")
    val npcIdentifier: String = "",
    @Help("A reference NPC entry. Only used when the identifier above is left empty.")
    val npc: Ref<ReferenceDepartedNpcEntry> = emptyRef(),
) : ReadableFactEntry {
    override fun readSinglePlayer(player: Player): FactData {
        val api = departedNpcApi() ?: return FactData(0)
        val id = resolveId() ?: return FactData(0)
        return FactData(if (api.isVisible(player, id)) 1 else 0)
    }

    private fun resolveId(): String? =
        npcIdentifier.takeIf { it.isNotBlank() }?.trim()
            ?: npc.get()?.npcIdentifier?.takeIf { it.isNotBlank() }?.trim()
}
