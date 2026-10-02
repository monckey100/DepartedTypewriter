package com.typewritermc.fancynpcs.entries.visibility

import com.typewritermc.core.entries.Query
import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.server
import de.oliver.fancynpcs.api.Npc
import de.oliver.fancynpcs.api.data.property.NpcVisibility
import org.bukkit.scheduler.BukkitTask

/**
 * Continuously reconciles the visibility of every FancyNpc referenced by a
 * [FancyNpcCriteriaVisibilityEntry]. Polling is required because FancyNpcs re-spawns NPCs to
 * players by proximity, so a purely event-driven hide would be undone the moment a blocked player
 * walked up to the NPC.
 */
@Singleton
class FancyNpcVisibilityRuntime : Initializable {
    private var task: BukkitTask? = null

    override suspend fun initialize() {
        task = server.scheduler.runTaskTimer(plugin, Runnable { tick() }, POLL_INTERVAL_TICKS, POLL_INTERVAL_TICKS)
    }

    override suspend fun shutdown() {
        task?.cancel()
        task = null
    }

    private fun tick() {
        val entries = Query.find<FancyNpcCriteriaVisibilityEntry>().toList()
        if (entries.isEmpty()) return

        // Resolve the NPC per entry and group by the actual NPC so that several entries pointing at
        // the same NPC combine with OR semantics (visible if any entry allows it).
        val byNpc = HashMap<String, Pair<Npc, MutableList<FancyNpcCriteriaVisibilityEntry>>>()
        entries.forEach { entry ->
            val npc = entry.findNpc() ?: return@forEach
            byNpc.getOrPut(npc.data.id) { npc to mutableListOf() }.second.add(entry)
        }

        byNpc.values.forEach { (npc, npcEntries) ->
            // Take control of this NPC's visibility away from FancyNpcs. With the default `ALL`
            // visibility, the FancyNpcs proximity tracker keeps re-spawning the NPC for nearby
            // players, which fights our per-player spawn/remove below and makes the NPC flicker.
            // `MANUAL` disables that tracker so only our explicit spawn/remove calls take effect.
            if (npc.data.visibility != NpcVisibility.MANUAL) {
                npc.data.visibility = NpcVisibility.MANUAL
            }

            server.onlinePlayers.forEach { player ->
                val shouldShow = npcEntries.any { it.isVisibleFor(player) }
                if (shouldShow) {
                    if (!npc.isShownFor(player)) npc.spawn(player)
                } else if (npc.isShownFor(player)) {
                    npc.remove(player)
                }
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_TICKS = 10L
    }
}
