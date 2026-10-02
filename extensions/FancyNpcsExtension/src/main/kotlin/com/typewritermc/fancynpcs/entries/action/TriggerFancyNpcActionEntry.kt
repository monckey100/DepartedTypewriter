package com.typewritermc.fancynpcs.entries.action

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Placeholder
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.Modifier
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.ActionEntry
import com.typewritermc.engine.paper.entry.entries.ActionTrigger
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.extensions.placeholderapi.parsePlaceholders
import com.typewritermc.fancynpcs.FancyNpcInteractionType
import com.typewritermc.fancynpcs.findFancyNpc

@Entry("trigger_fancynpc_actions", "Trigger FancyNpcs actions", Colors.RED, "fa6-solid:bolt")
/**
 * The `Trigger FancyNpcs Actions` action runs the configured FancyNpcs click actions for an NPC.
 */
class TriggerFancyNpcActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Placeholder
    private val npcIdentifier: Var<String> = ConstVar(""),
    private val interactionType: FancyNpcInteractionType = FancyNpcInteractionType.RIGHT_CLICK,
) : ActionEntry {
    override fun ActionTrigger.execute() {
        val npc = findFancyNpc(npcIdentifier.get(player, context).parsePlaceholders(player)) ?: return

        npc.interact(player, interactionType.toFancyTrigger())
    }
}
