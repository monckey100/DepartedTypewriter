package com.typewritermc.departednpcs.entries.modelanimation

import com.typewritermc.core.entries.Query
import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.departednpcs.departedNpcApi
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.server
import org.bukkit.scheduler.BukkitTask

/**
 * Continuously reconciles the per-player model animation of every DepartedNPC referenced by a
 * [DepartedNpcCriteriaModelAnimationEntry] — the model animation twin of the position runtime. Polling is what makes
 * the layer survive relogs and character switches without persisting anything: DepartedNPC keeps a player's own
 * animation in memory only (and puts it back itself whenever the NPC's model comes back), and this starts the right
 * one within ticks. An animation is only started when it differs from the one the player already sees, so a poll
 * never restarts it.
 */
@Singleton
class DepartedNpcModelAnimationRuntime : Initializable {
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
        val entries = Query.find<DepartedNpcCriteriaModelAnimationEntry>().toList()
        if (entries.isEmpty() && managed.isEmpty()) return

        // Group by the actual NPC id; the highest-priority matching entry decides the animation.
        val byNpc = HashMap<String, MutableList<DepartedNpcCriteriaModelAnimationEntry>>()
        entries.forEach { entry ->
            val id = entry.resolveId() ?: return@forEach
            byNpc.getOrPut(id) { mutableListOf() }.add(entry)
        }

        // Clear the animations of NPCs no longer managed (criteria entry removed).
        val stale = managed - byNpc.keys
        if (stale.isNotEmpty()) {
            server.onlinePlayers.forEach { player ->
                stale.forEach { id -> if (api.getModelAnimation(player, id) != null) api.stopModelAnimation(player, id) }
            }
            managed.removeAll(stale)
        }

        byNpc.forEach { (id, npcEntries) ->
            managed.add(id)
            val sorted = npcEntries.sortedByDescending { it.priority }
            server.onlinePlayers.forEach { player ->
                val wanted = sorted.firstOrNull { it.animation.isNotBlank() && it.matchesFor(player) }
                val seen = api.getModelAnimation(player, id)
                when {
                    wanted == null -> if (seen != null) api.stopModelAnimation(player, id)
                    wanted.animation != seen -> api.playModelAnimation(player, id, wanted.animation, wanted.loop)
                }
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_TICKS = 10L
    }
}
