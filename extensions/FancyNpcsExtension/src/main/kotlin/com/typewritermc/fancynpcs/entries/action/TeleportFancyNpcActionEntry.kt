package com.typewritermc.fancynpcs.entries.action

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Placeholder
import com.typewritermc.core.extension.annotations.WithRotation
import com.typewritermc.core.utils.launch
import com.typewritermc.core.utils.point.Position
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
import com.typewritermc.fancynpcs.findFancyNpc
import kotlinx.coroutines.Dispatchers

@Entry("teleport_fancynpc", "Teleport a FancyNpcs NPC", Colors.BLUE, "fa6-solid:location-dot")
/**
 * The `Teleport FancyNpcs NPC` action moves an existing FancyNpcs NPC to a Typewriter position.
 */
class TeleportFancyNpcActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Placeholder
    private val npcIdentifier: Var<String> = ConstVar(""),
    @WithRotation
    private val location: Var<Position> = ConstVar(Position.ORIGIN),
    private val target: FancyNpcActionTarget = FancyNpcActionTarget.ALL_PLAYERS,
) : ActionEntry {
    override fun ActionTrigger.execute() {
        val npc = findFancyNpc(npcIdentifier.get(player, context).parsePlaceholders(player)) ?: return
        val nextLocation = location.get(player, context).toBukkitLocation()

        Dispatchers.Sync.launch {
            val previousWorld = npc.data.location.world
            npc.data.setLocation(nextLocation)

            when (target) {
                FancyNpcActionTarget.TRIGGERING_PLAYER -> {
                    if (previousWorld == nextLocation.world) {
                        npc.update(player)
                    } else {
                        npc.remove(player)
                        npc.spawn(player)
                    }
                }

                FancyNpcActionTarget.ALL_PLAYERS -> {
                    if (previousWorld == nextLocation.world) {
                        npc.updateForAll()
                    } else {
                        npc.removeForAll()
                        npc.spawnForAll()
                    }
                }
            }
        }
    }
}
