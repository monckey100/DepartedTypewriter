package com.typewritermc.departednpcs.entries.visibility

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

@Tags("departednpc_criteria_visibility")
@Entry(
    "departednpc_criteria_visibility",
    "Reveal or hide a DepartedNPC based on criteria",
    Colors.ORANGE,
    "fa6-solid:eye-slash",
)
/**
 * Hides a DepartedNPC NPC and only reveals it to players whose facts satisfy the given criteria — for
 * gating a quest-giver behind quest progress. When multiple entries point at the same NPC, the NPC is
 * shown as soon as any of them match (logical OR). This uses DepartedNPC's transient visibility layer,
 * so it never persists and doesn't fight explicit show/hide quest reveals.
 */
class DepartedNpcCriteriaVisibilityEntry(
    override val id: String = "",
    override val name: String = "",
    @Help("The DepartedNPC id. Takes priority over the NPC reference below.")
    val npcIdentifier: String = "",
    @Help("A reference NPC entry. Only used when the identifier above is left empty.")
    val npc: Ref<ReferenceDepartedNpcEntry> = emptyRef(),
    @Help("The NPC is only revealed to players who match all of these criteria (e.g. a quest-progress fact).")
    val criteria: List<Criteria> = emptyList(),
    @Help("Invert the check: reveal the NPC to everyone EXCEPT players who match the criteria.")
    @Default("false")
    val inverted: Boolean = false,
) : StaticEntry {
    fun resolveId(): String? =
        npcIdentifier.takeIf { it.isNotBlank() }?.trim()
            ?: npc.get()?.npcIdentifier?.takeIf { it.isNotBlank() }?.trim()

    fun isVisibleFor(player: Player): Boolean {
        val matches = criteria.matches(player)
        return if (inverted) !matches else matches
    }
}
