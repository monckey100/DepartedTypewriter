package gg.departed.basic.entries.dialogue.messengers.actionbar

import gg.departed.basic.entries.dialogue.DepartedLang
import gg.departed.basic.entries.dialogue.ActionBarDialogueEntry
import gg.departed.basic.entries.dialogue.TalkIndicator
import gg.departed.basic.entries.dialogue.NpcNodController
import gg.departed.basic.entries.dialogue.useDialogueHud
import gg.departed.basic.entries.dialogue.hud.DialogueFacing
import gg.departed.basic.entries.dialogue.hud.DialogueHudRenderer
import gg.departed.basic.entries.dialogue.hud.DialogueNpcPortrait
import gg.departed.basic.entries.dialogue.hud.clearDialogueHud
import gg.departed.basic.entries.dialogue.hud.revealedHudCharacters
import gg.departed.basic.entries.dialogue.hud.showDialogueHud
import com.typewritermc.core.interaction.InteractionContext
import com.typewritermc.engine.paper.entry.dialogue.*
import com.typewritermc.engine.paper.extensions.placeholderapi.parsePlaceholders
import com.typewritermc.engine.paper.interaction.acceptActionBarMessage
import com.typewritermc.engine.paper.interaction.chatHistory
import com.typewritermc.engine.paper.snippets.snippet
import com.typewritermc.engine.paper.utils.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.entity.Player
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

private val actionBarFormat: String by snippet(
    "dialogue.actionbar.format",
    "<bold><speaker></bold><reset><gray>: <white><message> <gray>[<confirmation_key>]</gray><padding>",
)

class JavaActionBarDialogueDialogueMessenger(
    player: Player,
    context: InteractionContext,
    entry: ActionBarDialogueEntry
) :
    DialogueMessenger<ActionBarDialogueEntry>(player, context, entry) {

    private val hudEnabled = useDialogueHud(entry)
    private val hudPortrait = if (hudEnabled) DialogueNpcPortrait(player) else null
    override val capturesChat: Boolean get() = !hudEnabled

    private var confirmationKeyHandler: ConfirmationKeyHandler? = null
    private val talkIndicator = TalkIndicator(player, context, entry.talkIndicator)

    private var speakerDisplayName = ""
    private var text = ""
    private var typingDuration = Duration.ZERO
    private var playTime = Duration.ZERO
    private var lastHudReveal = -1
    private var lastHudSentAt = Duration.ZERO

    override var animationComplete: Boolean
        get() = playTime >= typingDuration
        set(value) {
            playTime = if (!value) Duration.ZERO
            else typingDuration
        }

    override fun init() {
        super.init()
        talkIndicator.init()
        speakerDisplayName = DepartedLang.tr(player, entry.speakerDisplayName.get(player)).parsePlaceholders(player)
        val sourceText = entry.text.get(player)
        val localText = DepartedLang.tr(player, sourceText)
        text = localText.parsePlaceholders(player)
        typingDuration = typingDurationType.totalDuration(text.stripped(), entry.duration.get(player))

        confirmationKeyHandler = confirmationKey.handler(player) {
            completeOrFinish()
        }
        // in init(), replace the NEW: voice control block with this
        val voiceActor = entry.voice.get(player)           // Voice actor
        val voiceText  = entry.voicetext.get(player)       // Override voice text
        val stopVoice  = entry.stopvoice.get(player)       // Stop previous talking

        // Choose the line to speak: voicetext (if not blank) else the regular text
        val chosenVoiceLineRaw = DepartedLang.voice(player, voiceText, sourceText, localText)
        val chosenVoiceLine = chosenVoiceLineRaw
            .parsePlaceholders(player)
            .stripped()


        if (stopVoice == true) {
           runSync { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "stoptalk ${player.name}") }
        }
        if (!voiceActor.isNullOrBlank() && chosenVoiceLine.isNotBlank()) {
            runSync {
                // Action-bar dialogue doesn't lock the player in place, so the voice follows them
                // (-follow) instead of anchoring to a spot they may walk away from.
                Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(),
                    "talkchar -follow $voiceActor ${player.name} $chosenVoiceLine"
                )
            }
        }


        // The player might have had something before this. So we want to clean the chat before sending our message.
        if (hudEnabled) {
            runSync {
                val npcId = NpcNodController.resolveSpeakerId("", entry.speaker.get(), player)
                hudPortrait?.show(npcId, DialogueFacing.resolve(null, npcId))
            }
        } else player.chatHistory.resendMessages(player)
    }

    private fun pluginHost(): JavaPlugin {
        (Bukkit.getPluginManager().getPlugin("PacketEvents") as? JavaPlugin)?.let { return it }
        (Bukkit.getPluginManager().getPlugin("packetevents") as? JavaPlugin)?.let { return it }
        Bukkit.getPluginManager().plugins.firstOrNull { it is JavaPlugin }?.let { return it as JavaPlugin }
        throw IllegalStateException("No JavaPlugin found to schedule tasks")
    }

    private fun runSync(task: () -> Unit) {
        Bukkit.getScheduler().runTask(pluginHost(), Runnable { task() })
    }
    override fun tick(context: TickContext) {
        talkIndicator.tick()
        if (state != MessengerState.RUNNING) return
        playTime += context.deltaTime

        val rawText = text.stripped()
        val percentage = typingDurationType.calculatePercentage(playTime, entry.duration.get(player), rawText)

        if (hudEnabled) {
            hudPortrait?.tick(!animationComplete)
            val body = text.asMini()
            val revealed = revealedHudCharacters(body, percentage)
            if (animationComplete && revealed == lastHudReveal &&
                (playTime - lastHudSentAt).toMillis() < 1_000L) return
            val key = when (confirmationKey) {
                ConfirmationKey.JUMP -> "Jump"
                ConfirmationKey.SNEAK -> "Shift"
                ConfirmationKey.SWAP_HANDS -> "Swap hands"
                ConfirmationKey.LEFT_CLICK -> "Left click"
                ConfirmationKey.RIGHT_CLICK -> "Right click"
            }
            val action = if (eventTriggers.isEmpty()) "finish" else "continue"
            player.showDialogueHud(DialogueHudRenderer.render(
                speaker = speakerDisplayName.asMini(),
                body = body,
                revealedCharacters = revealed,
                hint = "<gold>$key</gold><gray> to $action</gray>".asMini(),
                completedBodyMillis = (playTime - typingDuration).toMillis().coerceAtLeast(0L),
                portrait = hudPortrait?.shown ?: true,
            ))
            lastHudReveal = revealed
            lastHudSentAt = playTime
            return
        }

        // After the message is finished typing, we don't need to send it as often anymore.
        if (percentage > 1.1 && playTime.toTicks() % 40 > 0) {
            return
        }

        val message = text.asMini()
            .splitPercentage(percentage)

        // Find out how much padding is needed to fill the rest of the action bar.
        // As the action bar is centered, adding padding to the end of the message
        // will make the message appear stationary.
        val paddingSize = text.stripped().length - message.plainText().length
        val padding = " ".repeat(paddingSize)

        val component = actionBarFormat.asMiniWithResolvers(
            Placeholder.parsed("speaker", speakerDisplayName),
            Placeholder.component("message", message),
            Placeholder.unparsed("padding", padding),
        )

        player.acceptActionBarMessage(component)
        player.sendActionBar(component)
    }

    override fun end() {
        // Do nothing as we don't need to resend the messages.
    }

    override fun dispose() {
        super.dispose()
        talkIndicator.dispose()
        hudPortrait?.dispose()
        if (hudEnabled) player.clearDialogueHud() else {
            val component = Component.empty()
            player.acceptActionBarMessage(component)
            player.sendActionBar(component)
        }
        confirmationKeyHandler?.dispose()
        confirmationKeyHandler = null
    }
}
