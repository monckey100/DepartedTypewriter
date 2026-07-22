package com.typewritermc.departednpcs

import com.monckey100.departedrpg.api.DepartedRpgAPI
import com.monckey100.departedrpg.api.QuestActivationBlockedEvent
import com.monckey100.departedrpg.quests.QuestStatuses
import com.typewritermc.basic.entries.action.ConsoleCommandActionEntry
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.interaction.InteractionContext
import com.typewritermc.departednpcs.entries.event.DepartedNpcInteractEventEntry
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.DialogueEntry
import com.typewritermc.engine.paper.entry.matches
import gg.departed.basic.entries.dialogue.OptionDialogueEntry
import gg.departed.basic.entries.dialogue.SpokenDialogueEntry
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * Pre-dialogue gate for quest-giving NPCs. When the conversation branch an NPC click would start
 * leads to a quest activation (`/dequest set|start ... WIP`) that DepartedRPG's concurrency policy
 * rejects, the conversation is skipped entirely and the NPC delivers the "you're busy" rejection
 * immediately — instead of the full offer dialogue playing out only for the activation to be
 * rejected by the command at the end.
 *
 * The walk follows entry triggers (and WASD option triggers) from the interact event's targets,
 * pruning at dialogue entries whose criteria don't currently match: those criteria select the
 * conversation branch and are keyed on stable facts like quest status. Criteria on non-dialogue
 * entries are intentionally NOT evaluated — they often depend on facts set mid-conversation (e.g.
 * a reward choice) that are still unset at click time, and a missed activation there would only
 * fall back to the old late rejection. False blocks are prevented by the concurrency check itself:
 * activations of the player's own active quest are never rejected, so turn-in and reminder
 * branches always play.
 */
object QuestOfferGate {

    class BlockedOffer(
        val questId: String,
        val blockingQuestId: String,
        val speakerNpcId: String?,
        val speakerDisplayName: String,
        val speakerVoice: String,
    )

    private class Speaker(val npcId: String?, val displayName: String, val voice: String)

    // Matches one dispatched line, e.g. "departedrpg:dequest set %player_name% 18 side WIP".
    private val ACTIVATION =
        Regex("""^(?:[\w.-]+:)?dequest\s+(set|start)\s+\S+\s+(\S+)(?:\s+(\S+)\s+(\S+))?""", RegexOption.IGNORE_CASE)

    /**
     * The blocked quest activation the player would run into by starting this interaction's
     * dialogue, or null when the conversation may proceed. Call on the main thread.
     */
    fun findBlockedOffer(
        player: Player,
        roots: List<DepartedNpcInteractEventEntry>,
        context: InteractionContext,
    ): BlockedOffer? {
        val api = Bukkit.getServicesManager().load(DepartedRpgAPI::class.java) ?: return null
        val visited = mutableSetOf<String>()
        for (root in roots) {
            walkAll(root.triggers, player, context, api, visited, null)?.let { return it }
        }
        return null
    }

    /**
     * Fires [QuestActivationBlockedEvent] carrying the offer's speaker so the NPC can voice the
     * rejection; falls back to DepartedRPG's plain chat line when nothing handles the event.
     */
    fun notifyBlocked(player: Player, blocked: BlockedOffer) {
        val event = QuestActivationBlockedEvent(
            player,
            blocked.questId,
            blocked.blockingQuestId,
            blocked.speakerNpcId,
            blocked.speakerDisplayName,
            blocked.speakerVoice,
        )
        Bukkit.getPluginManager().callEvent(event)
        if (!event.isHandled) {
            player.sendMessage("§eFinish or cancel your current quest first.")
        }
    }

    private fun walkAll(
        refs: List<Ref<out TriggerableEntry>>,
        player: Player,
        context: InteractionContext,
        api: DepartedRpgAPI,
        visited: MutableSet<String>,
        speaker: Speaker?,
    ): BlockedOffer? {
        for (ref in refs) {
            walk(ref, player, context, api, visited, speaker)?.let { return it }
        }
        return null
    }

    private fun walk(
        ref: Ref<out TriggerableEntry>,
        player: Player,
        context: InteractionContext,
        api: DepartedRpgAPI,
        visited: MutableSet<String>,
        speaker: Speaker?,
    ): BlockedOffer? {
        val entry = ref.get() ?: return null
        if (!visited.add(entry.id)) return null

        var currentSpeaker = speaker
        if (entry is DialogueEntry) {
            if (!entry.criteria.matches(player, context)) return null
            currentSpeaker = entry.speakerHint(player, context) ?: currentSpeaker
        }

        if (entry is ConsoleCommandActionEntry) {
            blockedActivation(entry, player, context, api, currentSpeaker)?.let { return it }
        }

        walkAll(entry.triggers, player, context, api, visited, currentSpeaker)?.let { return it }

        if (entry is OptionDialogueEntry) {
            for (option in entry.options) {
                if (!option.criteria.matches(player, context)) continue
                walkAll(option.triggers, player, context, api, visited, currentSpeaker)?.let { return it }
            }
        }
        return null
    }

    private fun DialogueEntry.speakerHint(player: Player, context: InteractionContext): Speaker? {
        val (npcId, voice) = when (this) {
            is SpokenDialogueEntry -> npcSpeaker to voice.get(player, context)
            is OptionDialogueEntry -> npcSpeaker to voice.get(player, context)
            else -> return null
        }
        val displayName = speakerDisplayName.get(player, context)
        if (npcId.isBlank() && displayName.isBlank()) return null
        return Speaker(npcId.ifBlank { null }, displayName, voice)
    }

    private fun blockedActivation(
        entry: ConsoleCommandActionEntry,
        player: Player,
        context: InteractionContext,
        api: DepartedRpgAPI,
        speaker: Speaker?,
    ): BlockedOffer? {
        for (line in entry.command.get(player, context).lines()) {
            val match = ACTIVATION.find(line.trim().removePrefix("/")) ?: continue
            val subcommand = match.groupValues[1].lowercase()
            val questId = match.groupValues[2]
            val status = match.groupValues[4]
            // "set" only activates when it sets an active status; "start" always activates.
            if (subcommand == "set" && !QuestStatuses.isActive(status)) continue
            val blocking = api.getBlockingQuestId(player, questId).orElse(null) ?: continue
            return BlockedOffer(
                questId,
                blocking,
                speaker?.npcId,
                speaker?.displayName ?: "",
                speaker?.voice ?: "",
            )
        }
        return null
    }
}
