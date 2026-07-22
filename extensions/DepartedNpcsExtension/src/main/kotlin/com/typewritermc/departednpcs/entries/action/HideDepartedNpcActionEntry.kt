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
import com.typewritermc.engine.paper.utils.server

@Entry("hide_departednpc", "Hide a DepartedNPC NPC", Colors.ORANGE, "fa6-solid:eye-slash")
/**
 * Persistently hides a DepartedNPC NPC for the triggering player or all players (survives relogs).
 */
class HideDepartedNpcActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Placeholder
    private val npcIdentifier: Var<String> = ConstVar(""),
    private val target: DepartedNpcActionTarget = DepartedNpcActionTarget.TRIGGERING_PLAYER,
) : ActionEntry {
    override fun ActionTrigger.execute() {
        val api = departedNpcApi() ?: return
        val id = npcIdentifier.get(player, context).parsePlaceholders(player).trim()
        if (id.isBlank()) return
        when (target) {
            DepartedNpcActionTarget.TRIGGERING_PLAYER -> api.hide(player, id)
            DepartedNpcActionTarget.ALL_PLAYERS -> server.onlinePlayers.forEach { api.hide(it, id) }
        }
    }
}
