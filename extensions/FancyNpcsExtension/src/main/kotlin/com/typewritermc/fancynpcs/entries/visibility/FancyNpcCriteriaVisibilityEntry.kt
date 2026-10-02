package com.typewritermc.fancynpcs.entries.visibility

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.ContentEditor
import com.typewritermc.core.extension.annotations.Default
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Tags
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.StaticEntry
import com.typewritermc.engine.paper.entry.matches
import com.typewritermc.fancynpcs.content.SelectFancyNpcContentMode
import com.typewritermc.fancynpcs.entries.entity.ReferenceFancyNpcEntry
import com.typewritermc.fancynpcs.findFancyNpc
import de.oliver.fancynpcs.api.Npc
import org.bukkit.entity.Player

@Tags("fancynpc_criteria_visibility")
@Entry(
    "fancynpc_criteria_visibility",
    "Reveal or hide a FancyNpc based on criteria",
    Colors.ORANGE,
    "fa6-solid:eye-slash",
)
/**
 * The `FancyNpc Criteria Visibility` entry hides a FancyNpcs NPC and only reveals it to players
 * whose facts satisfy the given criteria. This lets you gate an NPC behind quest progress, for
 * example only showing "Henry" once a player has reached a certain point in a quest.
 *
 * The NPC is only managed once at least one visibility entry references it. When multiple entries
 * point at the same NPC, the NPC is shown as soon as **any** of them match (logical OR), which makes
 * it easy to reveal an NPC across several different quest stages.
 *
 * ## How could this be used?
 * Reveal a quest-giver only when a player's `quest_status_fact` reports the quest is active, or hide
 * a traumatized villager until an earlier quest has been completed.
 */
class FancyNpcCriteriaVisibilityEntry(
    override val id: String = "",
    override val name: String = "",
    @Help("The FancyNpcs name or id. Click the capture button and click an NPC in-game to fill this in. Takes priority over the NPC reference below.")
    @ContentEditor(SelectFancyNpcContentMode::class)
    val npcIdentifier: String = "",
    @Help("A reference NPC entry. Only used when the identifier above is left empty.")
    val npc: Ref<ReferenceFancyNpcEntry> = emptyRef(),
    @Help("The NPC is only revealed to players who match all of these criteria (e.g. a quest-progress fact).")
    val criteria: List<Criteria> = emptyList(),
    @Help("Invert the check: reveal the NPC to everyone EXCEPT players who match the criteria.")
    @Default("false")
    val inverted: Boolean = false,
) : StaticEntry {
    fun findNpc(): Npc? =
        npcIdentifier.takeIf { it.isNotBlank() }?.let { findFancyNpc(it) }
            ?: npc.get()?.findNpc()

    fun isVisibleFor(player: Player): Boolean {
        val matches = criteria.matches(player)
        return if (inverted) !matches else matches
    }
}
