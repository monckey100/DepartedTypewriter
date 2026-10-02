package com.typewritermc.departednpcs.entries.position

import com.typewritermc.core.entries.Query
import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.departednpcs.departedNpcApi
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.server
import org.bukkit.scheduler.BukkitTask

/**
 * Continuously reconciles the per-player position of every DepartedNPC referenced by a
 * [DepartedNpcCriteriaPositionEntry] — the position twin of [DepartedNpcVisibilityRuntime]. Polling is
 * what makes the layer survive relogs and character switches without persisting anything: DepartedNPC
 * keeps positions in memory only, and this re-applies the right variant within ticks.
 */
@Singleton
class DepartedNpcPositionRuntime : Initializable {
    private var task: BukkitTask? = null
    private val managed = HashSet<String>()

    override suspend fun initialize() {
        task = server.scheduler.runTaskTimer(plugin, Runnable { tick() }, POLL_INTERVAL_TICKS, POLL_INTERVAL_TICKS)
    }

    override suspend fun shutdown() {
        task?.cancel()
        task = null
    }

    private fun tick() {
        val api = departedNpcApi() ?: return
        val entries = Query.find<DepartedNpcCriteriaPositionEntry>().toList()

        // Group by the actual NPC id; the highest-priority matching entry decides the variant.
        val byNpc = HashMap<String, MutableList<DepartedNpcCriteriaPositionEntry>>()
        entries.forEach { entry ->
            val id = entry.resolveId() ?: return@forEach
            byNpc.getOrPut(id) { mutableListOf() }.add(entry)
        }

        // Clear overrides for NPCs no longer managed (criteria entry removed).
        val stale = managed - byNpc.keys
        if (stale.isNotEmpty()) {
            server.onlinePlayers.forEach { player ->
                stale.forEach { id -> api.setDynamicPosition(player, id, null) }
            }
            managed.removeAll(stale)
        }

        byNpc.forEach { (id, npcEntries) ->
            managed.add(id)
            val sorted = npcEntries.sortedByDescending { it.priority }
            server.onlinePlayers.forEach { player ->
                val variant = sorted.firstOrNull { it.variant.isNotBlank() && it.matchesFor(player) }?.variant
                api.setDynamicPosition(player, id, variant)
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_TICKS = 10L
    }
}
