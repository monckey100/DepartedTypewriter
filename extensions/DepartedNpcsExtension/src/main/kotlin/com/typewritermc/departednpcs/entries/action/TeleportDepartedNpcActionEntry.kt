package com.typewritermc.departednpcs.entries.action

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Placeholder
import com.typewritermc.core.extension.annotations.WithRotation
import com.typewritermc.core.utils.launch
import com.typewritermc.core.utils.point.Position
import com.typewritermc.departednpcs.departedNpcApi
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.Modifier
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.ActionEntry
import com.typewritermc.engine.paper.entry.entries.ActionTrigger
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.extensions.placeholderapi.parsePlaceholders
import com.typewritermc.engine.paper.utils.Sync
import com.typewritermc.engine.paper.utils.toBukkitLocation
import kotlinx.coroutines.Dispatchers

@Entry("teleport_departednpc", "Teleport a DepartedNPC NPC", Colors.BLUE, "fa6-solid:location-dot")
/**
 * Moves a DepartedNPC NPC to a Typewriter position (persisted).
 */
class TeleportDepartedNpcActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Placeholder
    private val npcIdentifier: Var<String> = ConstVar(""),
    @WithRotation
    private val location: Var<Position> = ConstVar(Position.ORIGIN),
) : ActionEntry {
    override fun ActionTrigger.execute() {
        val api = departedNpcApi() ?: return
        val id = npcIdentifier.get(player, context).parsePlaceholders(player).trim()
        if (id.isBlank()) return
        val nextLocation = location.get(player, context).toBukkitLocation()
        Dispatchers.Sync.launch {
            api.teleport(id, nextLocation)
        }
    }
}
