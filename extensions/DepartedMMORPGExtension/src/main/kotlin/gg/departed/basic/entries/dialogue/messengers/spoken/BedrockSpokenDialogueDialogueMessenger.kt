package gg.departed.basic.entries.dialogue.messengers.spoken

import gg.departed.basic.entries.dialogue.DepartedLang
import gg.departed.basic.entries.dialogue.LastSpeakerTracker
import gg.departed.basic.entries.dialogue.SpokenDialogueEntry
import gg.departed.basic.entries.dialogue.TalkIndicator
import com.typewritermc.core.interaction.InteractionBoundState
import com.typewritermc.core.interaction.InteractionContext
import com.typewritermc.engine.paper.entry.dialogue.DialogueMessenger
import com.typewritermc.engine.paper.entry.dialogue.MessengerState
import com.typewritermc.engine.paper.extensions.placeholderapi.parsePlaceholders
import com.typewritermc.engine.paper.interaction.boundState
import com.typewritermc.engine.paper.snippets.snippet
import com.typewritermc.engine.paper.utils.legacy
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.entity.Player

private val spokenTitle: String by snippet("dialogue.spoken.bedrock.title", "<bold><speaker></bold>")
private val spokenContent: String by snippet("dialogue.spoken.bedrock.content", "<message>\n\n")
private val spokenButton: String by snippet("dialogue.spoken.bedrock.button", "Continue")

class BedrockSpokenDialogueDialogueMessenger(player: Player, context: InteractionContext, entry: SpokenDialogueEntry) :
    DialogueMessenger<SpokenDialogueEntry>(player, context, entry) {
    private val talkIndicator = TalkIndicator(player, context, entry.talkIndicator)

    override fun init() {
        super.init()
        talkIndicator.init()
        // Remember who is talking so out-of-band NPC speech (quest-busy line) can use them.
        LastSpeakerTracker.record(
            player.uniqueId,
            DepartedLang.tr(player, entry.speakerDisplayName.get(player)).parsePlaceholders(player),
            entry.voice.get(player),
            null
        )
        sendForm()
    }

    override fun tick(context: com.typewritermc.engine.paper.entry.dialogue.TickContext) {
        talkIndicator.tick()
    }

    fun sendForm() {
        org.geysermc.floodgate.api.FloodgateApi.getInstance().sendForm(
            player.uniqueId,
            org.geysermc.cumulus.form.SimpleForm.builder()
                .title(
                    spokenTitle.parsePlaceholders(player).legacy(
                        Placeholder.parsed("speaker", DepartedLang.tr(player, entry.speakerDisplayName.get(player)).parsePlaceholders(player))
                    )
                )
                .content(
                    spokenContent.parsePlaceholders(player).legacy(
                        Placeholder.parsed("message", DepartedLang.tr(player, entry.text.get(player)).parsePlaceholders(player))
                    )
                )
                .button(spokenButton.parsePlaceholders(player).legacy())
                .closedOrInvalidResultHandler { _, _ ->
                    when (player.boundState) {
                        InteractionBoundState.BLOCKING -> sendForm()
                        else -> state = MessengerState.CANCELLED
                    }
                }
                .validResultHandler { _, _ ->
                    state = MessengerState.FINISHED
                }
        )
    }

    override fun end() {
        // Do nothing as we don't need to resend the messages.
    }

    override fun dispose() {
        super.dispose()
        talkIndicator.dispose()
    }
}
