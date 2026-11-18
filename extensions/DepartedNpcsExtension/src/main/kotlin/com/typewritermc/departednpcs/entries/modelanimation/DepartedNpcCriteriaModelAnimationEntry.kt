package com.typewritermc.departednpcs.entries.modelanimation

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

@Tags("departednpc_criteria_model_animation")
@Entry(
    "departednpc_criteria_model_animation",
    "Show a DepartedNPC model NPC in a model animation based on criteria",
    Colors.ORANGE,
    "fa6-solid:person-falling",
)
/**
 * Shows a DepartedNPC model NPC in one of its model's own animations to the players whose facts satisfy the given
 * criteria, and only to them — one NPC that stands for one player and lies on the floor for another, with no
 * second NPC. Needs DepartedModels (ModelEngine shows everyone the same model).
 *
 * Uses DepartedNPC's in-memory per-player model animation, so nothing persists: the entry re-applies the right
 * animation within ticks of a login or character switch, which is what keeps it correct per character. An animation
 * that holds is put back already on its last frame, so a fallen NPC is lying there, it does not fall over again.
 *
 * When multiple entries point at the same NPC, the matching entry with the highest priority wins; players who match
 * no entry see what everyone sees.
 */
class DepartedNpcCriteriaModelAnimationEntry(
    override val id: String = "",
    override val name: String = "",
    @Help("The DepartedNPC id. Takes priority over the NPC reference below.")
    val npcIdentifier: String = "",
    @Help("A reference NPC entry. Only used when the identifier above is left empty.")
    val npc: Ref<ReferenceDepartedNpcEntry> = emptyRef(),
    @Help("The name of an animation of the NPC's model (/npc modelanim list <npc>).")
    val animation: String = "",
    @Help("How it ends: HOLD stays on the last frame, LOOP repeats, ONCE plays and returns to what everyone sees.")
    @Default("\"HOLD\"")
    val loop: String = "HOLD",
    @Help("Hide the NPC's name plate from those players while they see the animation (a body has no name tag).")
    @Default("false")
    val hideName: Boolean = false,
    @Help("Players who match all of these criteria see the NPC in the animation.")
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
