package gg.departed.basic.entries.dialogue

import com.monckey100.departedrpg.api.QuestActivationBlockedEvent
import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.core.interaction.Interaction
import com.typewritermc.core.interaction.context
import com.typewritermc.engine.paper.entry.dialogue.DialogueInteraction
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.Event
import com.typewritermc.engine.paper.entry.entries.EventTrigger
import com.typewritermc.engine.paper.entry.triggerFor
import com.typewritermc.engine.paper.interaction.TriggerContinuation
import com.typewritermc.engine.paper.interaction.TriggerHandler
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.snippets.snippet
import com.typewritermc.engine.paper.utils.server
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val questBusyText: String by snippet(
    "quest.busy.spoken.text",
    "Sorry, it seems like you're busy with another quest..."
)

// Repeated rejections inside this window are swallowed entirely (no line, no chat fallback) so
// spam-clicking a quest giver doesn't flood the chat with busy lines.
private val questBusyCooldownMillis: Long by snippet("quest.busy.spoken.cooldown_millis", 3000L)

/**
 * When DepartedRPG's concurrency policy rejects a quest activation coming from NPC dialogue (the
 * dialogue dispatches `/dequest set ... WIP`), the NPC the player was just talking to says the
 * rejection line in their own voice instead of the plugin's plain chat message. The speaker is
 * whoever last delivered a spoken dialogue line to this player (see [LastSpeakerTracker]); when
 * nobody spoke recently — e.g. the activation came from somewhere other than dialogue — the event
 * is left unhandled so DepartedRPG falls back to its chat message.
 *
 * Rejections raised before any dialogue ran (DepartedNpcsExtension's NPC-click quest-offer gate)
 * carry their own speaker on the event.
 *
 * The line is delivered as a real [SpokenDialogueEntry] dialogue interaction — not a hand-formatted
 * chat message — so it types out like every other spoken line, darkens and later restores the chat
 * history, plays the voice line, and nods the NPC via the normal messenger. It also can't be
 * double-printed by the chat-history resend that happens when the previous conversation tears down.
 */
@Singleton
class QuestBusySpeech : Initializable, Listener {

    private val lastSpokenAt = ConcurrentHashMap<UUID, Long>()

    override suspend fun initialize() {
        server.pluginManager.registerEvents(this, plugin)
    }

    override suspend fun shutdown() {
        HandlerList.unregisterAll(this)
    }

    @EventHandler
    fun onQuestActivationBlocked(event: QuestActivationBlockedEvent) {
        val player = event.player

        // Pre-dialogue rejection (the NPC-click quest-offer gate) carries the speaker that would
        // have delivered the offer; post-dialogue rejections use whoever spoke last.
        val speaker =
            if (!event.speakerDisplayName.isNullOrBlank() || !event.speakerNpcId.isNullOrBlank()) {
                LastSpeakerTracker.SpeakerInfo(
                    event.speakerDisplayName.orEmpty(),
                    event.speakerVoice.orEmpty(),
                    event.speakerNpcId,
                    System.currentTimeMillis(),
                )
            } else {
                LastSpeakerTracker.recent(player.uniqueId, MAX_SPEAKER_AGE_MILLIS) ?: return
            }

        event.isHandled = true

        val now = System.currentTimeMillis()
        val last = lastSpokenAt[player.uniqueId]
        if (last != null && now - last < questBusyCooldownMillis) return
        lastSpokenAt[player.uniqueId] = now

        // Let a just-finished conversation tear down first (the final line's confirm is what
        // triggered the quest command), so the busy dialogue starts cleanly after it.
        server.scheduler.runTaskLater(
            plugin,
            Runnable {
                if (!player.isOnline) return@Runnable
                val entry = SpokenDialogueEntry(
                    id = "departed.quest_busy",
                    name = "quest_busy",
                    speakerName = ConstVar(speaker.displayName),
                    npcSpeaker = speaker.npcId.orEmpty(),
                    text = ConstVar(questBusyText),
                    voice = ConstVar(speaker.voice),
                )
                QuestBusyDialogueTrigger(entry).triggerFor(player, context())
            },
            DELAY_TICKS
        )
    }

    private companion object {
        // The speaker is recorded when their line starts displaying; a player can idle on the
        // conversation's last line before confirming, so keep the window generous.
        const val MAX_SPEAKER_AGE_MILLIS = 10 * 60 * 1000L
        const val DELAY_TICKS = 5L
    }
}

/** Carries the synthetic quest-busy [SpokenDialogueEntry] through the trigger pipeline. */
class QuestBusyDialogueTrigger(val entry: SpokenDialogueEntry) : EventTrigger {
    override val id: String = "departed.quest_busy_dialogue"
}

/**
 * Starts a [DialogueInteraction] for the synthetic quest-busy entry. The entry never lives in the
 * entry database, so the engine's [com.typewritermc.engine.paper.entry.dialogue.DialogueHandler]
 * (which resolves dialogue from entry refs) can't start it — this handler does it directly.
 */
@Singleton
class QuestBusyDialogueHandler : TriggerHandler {
    override suspend fun trigger(event: Event, currentInteraction: Interaction?): TriggerContinuation {
        val trigger = event.triggers.filterIsInstance<QuestBusyDialogueTrigger>().firstOrNull()
            ?: return TriggerContinuation.Nothing
        return TriggerContinuation.StartInteraction(
            DialogueInteraction(event.player, event.context, trigger.entry)
        )
    }
}
