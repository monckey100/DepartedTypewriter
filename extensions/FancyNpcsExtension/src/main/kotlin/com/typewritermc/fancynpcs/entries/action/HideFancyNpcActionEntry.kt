package com.typewritermc.fancynpcs.entries.action

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Placeholder
import com.typewritermc.core.utils.launch
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.Modifier
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.ActionEntry
import com.typewritermc.engine.paper.entry.entries.ActionTrigger
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.extensions.placeholderapi.parsePlaceholders
import com.typewritermc.engine.paper.utils.Sync
import com.typewritermc.fancynpcs.findFancyNpc
import kotlinx.coroutines.Dispatchers

@Entry("hide_fancynpc", "Hide a FancyNpcs NPC", Colors.ORANGE, "fa6-solid:eye-slash")
/**
 * The `Hide FancyNpcs NPC` action hides an existing FancyNpcs NPC for the triggering player or all players.
 */
class HideFancyNpcActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Placeholder
    private val npcIdentifier: Var<String> = ConstVar(""),
    private val target: FancyNpcActionTarget = FancyNpcActionTarget.TRIGGERING_PLAYER,
) : ActionEntry {
    override fun ActionTrigger.execute() {
        val npc = findFancyNpc(npcIdentifier.get(player, context).parsePlaceholders(player)) ?: return

        Dispatchers.Sync.launch {
            when (target) {
                FancyNpcActionTarget.TRIGGERING_PLAYER -> npc.remove(player)
                FancyNpcActionTarget.ALL_PLAYERS -> npc.removeForAll()
            }
        }
    }
}
