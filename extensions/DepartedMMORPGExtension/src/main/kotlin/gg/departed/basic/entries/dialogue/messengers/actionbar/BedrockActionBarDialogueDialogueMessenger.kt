package gg.departed.basic.entries.dialogue.messengers.actionbar

import gg.departed.basic.entries.dialogue.DepartedLang
import gg.departed.basic.entries.dialogue.ActionBarDialogueEntry
import gg.departed.basic.entries.dialogue.TalkIndicator
import com.typewritermc.core.interaction.InteractionBoundState
import com.typewritermc.core.interaction.InteractionContext
import com.typewritermc.engine.paper.entry.dialogue.DialogueMessenger
import com.typewritermc.engine.paper.entry.dialogue.MessengerState
import com.typewritermc.engine.paper.entry.dialogue.TickContext
import com.typewritermc.engine.paper.extensions.placeholderapi.parsePlaceholders
import com.typewritermc.engine.paper.interaction.boundState
import com.typewritermc.engine.paper.snippets.snippet
import com.typewritermc.engine.paper.utils.legacy
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.entity.Player

private val actionBarTitle: String by snippet("dialogue.actionbar.bedrock.title", "<bold><speaker></bold>")
private val actionBarContent: String by snippet("dialogue.actionbar.bedrock.content", "<message>\n\n")
private val actionBarButton: String by snippet("dialogue.actionbar.bedrock.button", "Continue")

class BedrockActionBarDialogueDialogueMessenger(
    player: Player,
    context: InteractionContext,
    entry: ActionBarDialogueEntry
) : DialogueMessenger<ActionBarDialogueEntry>(player, context, entry) {
    private val talkIndicator = TalkIndicator(player, context, entry.talkIndicator)

    override fun init() {
        super.init()
        talkIndicator.init()
        sendForm()
    }

    override fun tick(context: TickContext) {
        talkIndicator.tick()
    }

    fun sendForm() {
        org.geysermc.floodgate.api.FloodgateApi.getInstance().sendForm(
            player.uniqueId,
            org.geysermc.cumulus.form.SimpleForm.builder()
                .title(
                    actionBarTitle.parsePlaceholders(player).legacy(
                        Placeholder.parsed(
                            "speaker",
                            DepartedLang.tr(player, entry.speakerDisplayName.get(player)).parsePlaceholders(player)
                        )
                    )
                )
                .content(
                    actionBarContent.parsePlaceholders(player).legacy(
                        Placeholder.parsed("message", DepartedLang.tr(player, entry.text.get(player)).parsePlaceholders(player))
                    )
                )
                .button(actionBarButton.parsePlaceholders(player).legacy())
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
