package com.typewritermc.departednpcs.entries.position

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Default
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Tags
import com.typewritermc.departednpcs.entries.entity.ReferenceDepartedNpcEntry
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.StaticEntry
import com.typewritermc.engine.paper.entry.matches
import org.bukkit.entity.Player

@Tags("departednpc_criteria_position")
@Entry(
    "departednpc_criteria_position",
    "Move a DepartedNPC to a position variant based on criteria",
    Colors.ORANGE,
    "fa6-solid:location-dot",
)
/**
 * Puts a DepartedNPC NPC at one of its named `positions:` variants for players whose facts satisfy the
 * given criteria — one NPC, different spots per quest state, no clone NPCs. Uses DepartedNPC's
 * transient position layer, so nothing persists: the entry re-applies the right variant within ticks
 * of a login or character switch, which is what keeps it correct per character.
 *
 * When multiple entries point at the same NPC, the matching entry with the highest priority wins;
 * players who match no entry see the NPC at its normal configured location.
 */
class DepartedNpcCriteriaPositionEntry(
    override val id: String = "",
    override val name: String = "",
    @Help("The DepartedNPC id. Takes priority over the NPC reference below.")
    val npcIdentifier: String = "",
    @Help("A reference NPC entry. Only used when the identifier above is left empty.")
    val npc: Ref<ReferenceDepartedNpcEntry> = emptyRef(),
    @Help("The name of a position variant from the NPC's yml (positions: section).")
    val variant: String = "",
    @Help("The NPC stands at the variant for players who match all of these criteria.")
    val criteria: List<Criteria> = emptyList(),
    @Help("When several entries match for the same NPC, the highest priority wins.")
    @Default("0")
    val priority: Int = 0,
) : StaticEntry {
    fun resolveId(): String? =
        npcIdentifier.takeIf { it.isNotBlank() }?.trim()
            ?: npc.get()?.npcIdentifier?.takeIf { it.isNotBlank() }?.trim()

    fun matchesFor(player: Player): Boolean = criteria.matches(player)
}
