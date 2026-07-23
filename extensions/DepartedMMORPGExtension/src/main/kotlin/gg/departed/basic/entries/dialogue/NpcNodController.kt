package gg.departed.basic.entries.dialogue

import com.typewritermc.engine.paper.entry.entries.SoundSourceEntry
import com.typewritermc.engine.paper.entry.entries.SpeakerEntry
import com.typewritermc.engine.paper.plugin
import dev.departed.departednpc.api.DepartedNpcApi
import dev.departed.departednpc.api.DepartedNpcProvider
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Drives the per-player "talking" head-bob on a DepartedNPC while a dialogue line is spoken. DepartedNPC
 * renders the bob natively and per-viewer, so this just toggles talking on for the line's duration and
 * schedules it off — replacing the old FancyNpcs pitch-oscillation approach.
 */
object NpcNodController {

    private fun api(): DepartedNpcApi? = DepartedNpcProvider.get()

    // key: "<playerUuid>|<npcId>" -> scheduled stop task
    private val stopTasks = ConcurrentHashMap<String, BukkitTask>()

    private fun key(playerId: UUID, npcId: String) = "$playerId|${npcId.lowercase()}"

    /** Resolve the speaking NPC id: prefer the inline [identifier], else derive it from the [speaker]. */
    fun resolveSpeakerId(identifier: String, speaker: SpeakerEntry?, player: Player): String? {
        val trimmed = identifier.trim()
        if (trimmed.isNotBlank()) return trimmed
        return speakerNpcId(speaker, player)
    }

    /** Map a dialogue speaker (via its sound-emitter entity id) back to a DepartedNPC id, or null. */
    fun speakerNpcId(speaker: SpeakerEntry?, player: Player): String? {
        val api = api() ?: return null
        val source = speaker as? SoundSourceEntry ?: return null
        return try {
            val emitterId = source.getEmitter(player).entityId
            api.npcIds().firstOrNull { api.getEntityId(it) == emitterId }
        } catch (_: Throwable) {
            null
        }
    }

    /** Start the talking bob on [npcId] for [player], lasting [durationTicks]. Main thread. */
    fun start(npcId: String?, player: Player, durationTicks: Int) {
        val id = npcId?.trim()?.takeIf { it.isNotBlank() } ?: return
        if (durationTicks <= 0) return
        val api = api() ?: return
        api.setTalking(player, id, true)
        val k = key(player.uniqueId, id)
        stopTasks.remove(k)?.cancel()
        stopTasks[k] = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            stopTasks.remove(k)
            api.setTalking(player, id, false)
        }, durationTicks.toLong())
    }

    /** Stop the talking bob on [npcId] for [player] immediately. Main thread. */
    fun stop(npcId: String?, player: Player) {
        val id = npcId?.trim()?.takeIf { it.isNotBlank() } ?: return
        val k = key(player.uniqueId, id)
        stopTasks.remove(k)?.cancel()
        api()?.setTalking(player, id, false)
    }
}
