package com.typewritermc.departednpcs.entries.action

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Placeholder
import com.typewritermc.departednpcs.departedNpcApi
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.Modifier
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.ActionEntry
import com.typewritermc.engine.paper.entry.entries.ActionTrigger
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.extensions.placeholderapi.parsePlaceholders

@Entry("trigger_departednpc_actions", "Trigger DepartedNPC actions", Colors.RED, "fa6-solid:bolt")
/**
 * Runs a DepartedNPC's configured click actions for the triggering player.
 */
class TriggerDepartedNpcActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Placeholder
    private val npcIdentifier: Var<String> = ConstVar(""),
    private val leftClick: Boolean = false,
) : ActionEntry {
    override fun ActionTrigger.execute() {
        val api = departedNpcApi() ?: return
        val id = npcIdentifier.get(player, context).parsePlaceholders(player).trim()
        if (id.isBlank()) return
        api.interact(player, id, leftClick)
    }
}
