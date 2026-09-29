package gg.departed.basic.entries.dialogue.messengers.spoken

import gg.departed.basic.entries.dialogue.DepartedLang
import gg.departed.basic.entries.dialogue.DialogueMountControl
import gg.departed.basic.entries.dialogue.LastSpeakerTracker
import gg.departed.basic.entries.dialogue.NpcNodController
import gg.departed.basic.entries.dialogue.SpokenDialogueEntry
import gg.departed.basic.entries.dialogue.TalkIndicator
import com.typewritermc.core.interaction.InteractionContext
import org.bukkit.event.EventHandler
import com.typewritermc.engine.paper.entry.dialogue.*
import com.typewritermc.engine.paper.extensions.placeholderapi.parsePlaceholders
import com.typewritermc.engine.paper.interaction.chatHistory
import com.typewritermc.engine.paper.snippets.snippet
import com.typewritermc.engine.paper.utils.*
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.entity.Player
import java.time.Duration

import org.bukkit.plugin.java.JavaPlugin
import kotlinx.coroutines.Dispatchers
import com.typewritermc.core.utils.switchContext
import org.bukkit.Bukkit

// PacketEvents 2.x — confirm-only input while the player is seated on the dialogue mount (a seated
// player can't fire PlayerJumpEvent, so the JUMP confirmation key must be read from the steer packet).
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSteerVehicle

val spokenFormat: String by snippet(
    "dialogue.spoken.format",
    """
		|<gray><st>${" ".repeat(60)}</st>
		|
		|<gray><padding>[ <bold><speaker></bold><reset><gray> ]
		|
		|<message>
		|
		|<next_color>${" ".repeat(20)} Press<white> <confirmation_key> </white>to <finish_text>
		|<gray><st>${" ".repeat(60)}</st>
		""".trimMargin()
)

val spokenInstructionNextText: String by snippet("dialogue.spoken.instruction.next", "continue")
val spokenInstructionFinishText: String by snippet("dialogue.spoken.instruction.finish", "finish")
val spokenInstructionBaseColor: String by snippet("dialogue.spoken.instruction.color.base", "<gray>")
val spokenInstructionHighlightColor: String by snippet("dialogue.spoken.instruction.color.highlight", "<red>")
val spokenPadding: String by snippet("dialogue.spoken.padding", "    ")
val spokenMinLines: Int by snippet("dialogue.spoken.minLines", 3)
val spokenMaxLineLength: Int by snippet("dialogue.spoken.maxLineLength", 40)
val spokenInstructionTicksHighlighted: Long by snippet("dialogue.spoken.instruction.ticks.highlighted", 10)
val spokenInstructionTicksBase: Long by snippet("dialogue.spoken.instruction.ticks.base", 30)

class JavaSpokenDialogueDialogueMessenger(player: Player, context: InteractionContext, entry: SpokenDialogueEntry) :
    DialogueMessenger<SpokenDialogueEntry>(player, context, entry) {
    private var confirmationKeyHandler: ConfirmationKeyHandler? = null
    private val talkIndicator = TalkIndicator(player, context, entry.talkIndicator)

    private var speakerDisplayName = ""
    private var text = ""
    private var typingDuration = Duration.ZERO
    private var playedTime = Duration.ZERO

    // Whether to stop the current voice line when this dialogue is advanced/finished.
    private var stopVoiceOnAdvance = false
    private var nodNpcId: String? = null
    private var packetListener: PacketListenerAbstract? = null
    private val hasPacketEvents by lazy {
        Bukkit.getPluginManager().getPlugin("packetevents") != null ||
                Bukkit.getPluginManager().getPlugin("PacketEvents") != null
    }

    override var animationComplete: Boolean
        get() = playedTime >= typingDuration
        set(value) {
            playedTime = if (!value) Duration.ZERO
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

        // voice settings (locals cannot be 'private')
        val voiceActor = entry.voice.get(player)      // Voice actor
        val voiceText  = entry.voicetext.get(player)  // Override voice text (optional)
        val stopVoice  = entry.stopvoice.get(player)  // Stop previous talking first?
        val cutVoice = entry.cutvoice.get(player)
        // Choose line: voicetext if non-blank, else the regular parsed 'text'
        val chosenVoiceLine = DepartedLang.voice(player, voiceText, sourceText, localText)
            .parsePlaceholders(player)
            .stripped()

        stopVoiceOnAdvance = (stopVoice == true)
        if (stopVoice == true) {
           runSync {  Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "stoptalk ${player.name}") }
        }
        if (!voiceActor.isNullOrBlank() && chosenVoiceLine.isNotBlank()) {
           runSync {
               Bukkit.dispatchCommand(
                   Bukkit.getConsoleSender(),
                   "talkchar $voiceActor ${player.name} $chosenVoiceLine"
               )
           }
        }


        confirmationKeyHandler = confirmationKey.handler(player) { advance() }

        // Hold the shared dialogue mount so spoken (option-less) lines anchor the player too, and
        // so a conversation moving spoken <-> option keeps the SAME mount (no dismount/remount bob).
        // Because the seated player can't fire PlayerJumpEvent, also read confirm from the steer packet.
        runSync {
            DialogueMountControl.claim(player)
            registerConfirmListener()
            // Nod the speaking NPC's head for roughly as long as the line takes to type out.
            nodNpcId = NpcNodController.resolveSpeakerId(entry.npcSpeaker, entry.speaker.get(), player)
            NpcNodController.start(nodNpcId, player, (typingDuration.toMillis() / 50L).toInt().coerceAtLeast(1))
            // Remember who is talking so out-of-band NPC speech (quest-busy line) can use them.
            LastSpeakerTracker.record(player.uniqueId, speakerDisplayName, voiceActor ?: "", nodNpcId)
        }
    }

    /** Advance/finish this spoken line (shared by the confirmation key and the seated-mount packet). */
    private fun advance() {
        if (state != MessengerState.RUNNING) return
        if (stopVoiceOnAdvance) {
            runSync { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "stoptalk ${player.name}") }
        }
        completeOrFinish()
    }

    private fun registerConfirmListener() {
        if (packetListener != null || !hasPacketEvents) return
        packetListener = object : PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
            override fun onPacketReceive(event: PacketReceiveEvent) {
                val type = event.packetType
                if (type != PacketType.Play.Client.STEER_VEHICLE &&
                    type != PacketType.Play.Client.PLAYER_INPUT
                ) return
                val user = event.user ?: return
                if (user.uuid != player.uniqueId) return
                if (state != MessengerState.RUNNING) return
                val held = DialogueMountControl.heldStand(player) ?: return
                if (player.vehicle != held) return

                val confirm = when (type) {
                    PacketType.Play.Client.STEER_VEHICLE -> {
                        val w = WrapperPlayClientSteerVehicle(event)
                        w.isJump || w.isUnmount // SPACE or SHIFT
                    }
                    PacketType.Play.Client.PLAYER_INPUT -> {
                        val w = WrapperPlayClientPlayerInput(event)
                        w.isJump || w.isShift // SPACE or SHIFT
                    }
                    else -> false
                }

                // Always cancel steer/jump/unmount packets while seated so the player stays put.
                event.isCancelled = true
                if (confirm) runSync { advance() }
            }
        }
        PacketEvents.getAPI().eventManager.registerListener(packetListener!!)
    }

    private fun unregisterConfirmListener() {
        packetListener?.let {
            try { PacketEvents.getAPI().eventManager.unregisterListener(it) } catch (_: Throwable) {}
        }
        packetListener = null
    }

    // Veto the vanilla shift-to-dismount while the dialogue mount is held, so the player can't
    // throw themselves off mid-conversation. See the option messenger for the full rationale.
    @EventHandler
    private fun onDismount(e: org.bukkit.event.entity.EntityDismountEvent) {
        if (e.entity.uniqueId != player.uniqueId) return
        val held = DialogueMountControl.heldStand(player) ?: return
        if (e.dismounted == held) e.isCancelled = true
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
        playedTime += context.deltaTime
        player.sendSpokenDialogue(
            text,
            speakerDisplayName,
            entry.duration.get(player),
            playedTime,
            eventTriggers.isEmpty()
        )
    }

    override fun dispose() {
        super.dispose()
        talkIndicator.dispose()
        confirmationKeyHandler?.dispose()
        confirmationKeyHandler = null
        unregisterConfirmListener()
        NpcNodController.stop(nodNpcId, player)
        // Deferred release: if the next node (option or spoken) re-claims within a few ticks the
        // mount is kept; only the final node of the conversation actually tears it down.
        runSync { DialogueMountControl.release(player) }
    }
}


fun Player.sendSpokenDialogue(
    text: String,
    speakerDisplayName: String,
    duration: Duration,
    playTime: Duration,
    canFinish: Boolean
) {
    val rawText = text.stripped()
    val playedTicks = playTime.toTicks()
    val durationInTicks = typingDurationType.totalDuration(rawText, duration).toTicks()

    val percentage = typingDurationType.calculatePercentage(playTime, duration, rawText)

    val totalInstructionDuration = spokenInstructionTicksHighlighted + spokenInstructionTicksBase
    val instructionCycle = playedTicks % totalInstructionDuration
    // When the messages is send we don't want to keep sending the message every tick.
    // However, we only start reducing after the message has been displayed fully.
    if (percentage > 1.1) {
        // Change in highlight color
        val shouldDisplay = instructionCycle == 0L || instructionCycle == spokenInstructionTicksHighlighted
        if (!shouldDisplay) return
    }

    val highlightStarted = playedTicks > durationInTicks * 2.5
    val needsHighlight = instructionCycle < spokenInstructionTicksHighlighted
    val nextColor =
        if (highlightStarted && needsHighlight) spokenInstructionHighlightColor else spokenInstructionBaseColor

    val continueOrFinish = if (canFinish) spokenInstructionFinishText else spokenInstructionNextText

    val resultingLines = rawText.limitLineLength(spokenMaxLineLength).lineCount

    val message = text.asPartialFormattedMini(
        percentage,
        padding = spokenPadding,
        minLines = spokenMinLines.coerceAtLeast(resultingLines),
        maxLineLength = spokenMaxLineLength
    )

    val component = spokenFormat.asMiniWithResolvers(
        Placeholder.parsed("speaker", speakerDisplayName),
        Placeholder.component("message", message),
        Placeholder.parsed("next_color", nextColor),
        Placeholder.parsed("finish_text", continueOrFinish),
        Placeholder.parsed("padding", spokenPadding)
    )

    val componentWithDarkMessages = chatHistory.composeDarkMessage(component)
    sendMessage(componentWithDarkMessages)
}
