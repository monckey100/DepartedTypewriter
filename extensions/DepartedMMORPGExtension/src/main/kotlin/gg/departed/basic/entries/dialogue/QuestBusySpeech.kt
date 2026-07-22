package gg.departed.basic.entries.dialogue

import com.monckey100.departedrpg.api.QuestActivationBlockedEvent
import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.snippets.snippet
import com.typewritermc.engine.paper.utils.asMiniWithResolvers
import com.typewritermc.engine.paper.utils.asPartialFormattedMini
import com.typewritermc.engine.paper.utils.server
import com.typewritermc.engine.paper.utils.stripped
import gg.departed.basic.entries.dialogue.messengers.spoken.spokenMaxLineLength
import gg.departed.basic.entries.dialogue.messengers.spoken.spokenMinLines
import gg.departed.basic.entries.dialogue.messengers.spoken.spokenPadding
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener

private val questBusyText: String by snippet(
    "quest.busy.spoken.text",
    "Sorry, it seems like you're busy with another quest..."
)

// The spoken dialogue frame without the "Press [key] to continue" instruction line — this line is
// informational, there is nothing to advance.
private val questBusyFormat: String by snippet(
    "quest.busy.spoken.format",
    """
    |<gray><st>${" ".repeat(60)}</st>
    |
    |<gray><padding>[ <bold><speaker></bold><reset><gray> ]
    |
    |<message>
    |
    |<gray><st>${" ".repeat(60)}</st>
    """.trimMargin()
)

/**
 * When DepartedRPG's concurrency policy rejects a quest activation coming from NPC dialogue (the
 * dialogue dispatches `/dequest set ... WIP`), the NPC the player was just talking to says the
 * rejection line in their own voice instead of the plugin's plain chat message. The speaker is
 * whoever last delivered a spoken dialogue line to this player (see [LastSpeakerTracker]); when
 * nobody spoke recently — e.g. the activation came from somewhere other than dialogue — the event
 * is left unhandled so DepartedRPG falls back to its chat message.
 *
 * Rejections raised before any dialogue ran (DepartedNpcsExtension's NPC-click quest-offer gate)
 * carry their own speaker on the event and are spoken immediately, since there is no conversation
 * to wait out.
 */
@Singleton
class QuestBusySpeech : Initializable, Listener {

    override suspend fun initialize() {
        server.pluginManager.registerEvents(this, plugin)
    }

    override suspend fun shutdown() {
        HandlerList.unregisterAll(this)
    }

    @EventHandler
    fun onQuestActivationBlocked(event: QuestActivationBlockedEvent) {
        val player = event.player

        // Pre-dialogue rejection (the NPC-click quest-offer gate): the event names the speaker
        // that would have delivered the offer, and no conversation ran, so speak immediately.
        if (!event.speakerDisplayName.isNullOrBlank() || !event.speakerNpcId.isNullOrBlank()) {
            event.isHandled = true
            speakBusyLine(
                player,
                LastSpeakerTracker.SpeakerInfo(
                    event.speakerDisplayName.orEmpty(),
                    event.speakerVoice.orEmpty(),
                    event.speakerNpcId,
                    System.currentTimeMillis(),
                )
            )
            return
        }

        val speaker =
            LastSpeakerTracker.recent(player.uniqueId, MAX_SPEAKER_AGE_MILLIS) ?: return
        event.isHandled = true
        // Let the conversation finish tearing down first (the final line's confirm is what
        // triggered the quest command), so the busy line lands after it instead of under it.
        server.scheduler.runTaskLater(
            plugin,
            Runnable {
                if (player.isOnline) speakBusyLine(player, speaker)
            },
            DELAY_TICKS
        )
    }

    private fun speakBusyLine(player: Player, speaker: LastSpeakerTracker.SpeakerInfo) {
        if (speaker.voice.isNotBlank()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "stoptalk ${player.name}")
            Bukkit.dispatchCommand(
                Bukkit.getConsoleSender(),
                "talkchar ${speaker.voice} ${player.name} ${questBusyText.stripped()}"
            )
        }
        NpcNodController.start(speaker.npcId, player, NOD_TICKS)
        player.sendMessage(
            questBusyFormat.asMiniWithResolvers(
                Placeholder.parsed("speaker", speaker.displayName),
                Placeholder.component(
                    "message",
                    questBusyText.asPartialFormattedMini(
                        1.0,
                        padding = spokenPadding,
                        minLines = spokenMinLines,
                        maxLineLength = spokenMaxLineLength,
                    )
                ),
                Placeholder.parsed("padding", spokenPadding),
            )
        )
    }

    private companion object {
        // The speaker is recorded when their line starts displaying; a player can idle on the
        // conversation's last line before confirming, so keep the window generous.
        const val MAX_SPEAKER_AGE_MILLIS = 10 * 60 * 1000L
        const val DELAY_TICKS = 5L
        const val NOD_TICKS = 40
    }
}
