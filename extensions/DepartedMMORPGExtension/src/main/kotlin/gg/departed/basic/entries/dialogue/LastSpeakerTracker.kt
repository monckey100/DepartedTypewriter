package gg.departed.basic.entries.dialogue

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers the most recent spoken-dialogue speaker per player so out-of-band NPC speech (e.g.
 * the quest-busy rejection line in [QuestBusySpeech]) can be delivered in the voice of whoever
 * the player was just talking to, without every page needing an explicit rejection branch.
 */
object LastSpeakerTracker {
    data class SpeakerInfo(
        val displayName: String,
        val voice: String,
        val npcId: String?,
        val atMillis: Long,
    )

    private val speakers = ConcurrentHashMap<UUID, SpeakerInfo>()

    fun record(playerId: UUID, displayName: String, voice: String, npcId: String?) {
        speakers[playerId] = SpeakerInfo(displayName, voice, npcId, System.currentTimeMillis())
    }

    fun recent(playerId: UUID, maxAgeMillis: Long): SpeakerInfo? {
        val info = speakers[playerId] ?: return null
        if (System.currentTimeMillis() - info.atMillis > maxAgeMillis) return null
        return info
    }
}
