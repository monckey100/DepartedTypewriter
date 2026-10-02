package com.typewritermc.departednpcs.entries.visibility

import com.typewritermc.core.entries.Query
import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.departednpcs.departedNpcApi
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.server
import org.bukkit.scheduler.BukkitTask

/**
 * Continuously reconciles the visibility of every DepartedNPC referenced by a
 * [DepartedNpcCriteriaVisibilityEntry]. Polling is required because DepartedNPC re-spawns NPCs by
 * proximity; a one-off hide would be undone as soon as a blocked player walked up. Uses the transient
 * visibility layer so nothing is written to disk.
 */
@Singleton
class DepartedNpcVisibilityRuntime : Initializable {
    private var task: BukkitTask? = null
    private val managed = HashSet<String>()

    override suspend fun initialize() {
        // A throw out of tick() would cancel the repeating task and silently freeze every NPC's
        // criteria visibility at its last state for the rest of the session (e.g. a quest NPC
        // staying visible after its hide-fact flipped). Contain and log instead, throttled so a
        // persistently broken fact read doesn't flood the console.
        task = server.scheduler.runTaskTimer(plugin, Runnable {
            try {
                tick()
            } catch (t: Throwable) {
                val now = System.currentTimeMillis()
                if (now - lastErrorLoggedAt > 10_000) {
                    lastErrorLoggedAt = now
                    plugin.logger.warning("NPC criteria-visibility tick failed (visibility is stale until this is fixed): $t")
                    t.stackTrace.take(6).forEach { plugin.logger.warning("  at $it") }
                }
            }
        }, POLL_INTERVAL_TICKS, POLL_INTERVAL_TICKS)
    }

    private var lastErrorLoggedAt = 0L

    override suspend fun shutdown() {
        task?.cancel()
        task = null
    }

    private fun tick() {
        val api = departedNpcApi() ?: return
        val entries = Query.find<DepartedNpcCriteriaVisibilityEntry>().toList()

        // Group entries by the actual NPC id so several entries for one NPC combine with OR semantics.
        val byNpc = HashMap<String, MutableList<DepartedNpcCriteriaVisibilityEntry>>()
        entries.forEach { entry ->
            val id = entry.resolveId() ?: return@forEach
            byNpc.getOrPut(id) { mutableListOf() }.add(entry)
        }

        // Clear dynamic overrides for NPCs no longer managed (criteria entry removed).
        val stale = managed - byNpc.keys
        if (stale.isNotEmpty()) {
            server.onlinePlayers.forEach { player ->
                stale.forEach { id -> api.setDynamicVisibility(player, id, null) }
            }
            managed.removeAll(stale)
        }

        byNpc.forEach { (id, npcEntries) ->
            managed.add(id)
            server.onlinePlayers.forEach { player ->
                val shouldShow = npcEntries.any { it.isVisibleFor(player) }
                api.setDynamicVisibility(player, id, shouldShow)
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_TICKS = 10L
    }
}
