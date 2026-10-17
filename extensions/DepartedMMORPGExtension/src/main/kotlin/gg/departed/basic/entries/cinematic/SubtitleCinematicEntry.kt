package gg.departed.basic.entries.cinematic

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput
import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Colored
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Placeholder
import com.typewritermc.core.extension.annotations.Segments
import com.typewritermc.core.utils.switchContext
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.entries.CinematicAction
import com.typewritermc.engine.paper.entry.entries.CinematicEntry
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.SpeakerEntry
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.entry.entries.activeSegmentAt
import com.typewritermc.engine.paper.entry.entries.canFinishAt
import com.typewritermc.engine.paper.entry.temporal.TemporalSetFrameTrigger
import com.typewritermc.engine.paper.entry.triggerFor
import com.typewritermc.engine.paper.extensions.placeholderapi.parsePlaceholders
import com.typewritermc.engine.paper.interaction.interactionContext
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.snippets.snippet
import com.typewritermc.engine.paper.utils.Sync
import com.typewritermc.engine.paper.utils.asMini
import com.typewritermc.engine.paper.utils.server
import com.typewritermc.engine.paper.utils.stripped
import gg.departed.basic.entries.dialogue.DepartedLang
import gg.departed.basic.entries.dialogue.hud.SubtitleHudRenderer
import gg.departed.basic.entries.dialogue.hud.clearDialogueHud
import gg.departed.basic.entries.dialogue.hud.revealedHudCharacters
import gg.departed.basic.entries.dialogue.hud.showDialogueHud
import kotlinx.coroutines.Dispatchers
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerToggleSneakEvent

@Entry("departed_subtitle_cinematic", "Cutscene subtitle on the dialogue HUD band", Colors.CYAN, "mingcute:subtitle-fill")
/**
 * The `Subtitle Cinematic` shows cutscene dialogue on the bottom-anchored HUD band (no chat, no action-bar
 * overflow). Each segment types out at the subtitle pace; Jump or Shift completes the sentence, and a second
 * press jumps the cinematic to the next line (so every command between still fires). A 250 ms grace after each
 * press ignores a fat-fingered double tap. There is deliberately no whole-cutscene skip.
 *
 * ## How could this be used?
 *
 * Any camera cinematic with spoken lines: tutorial outro, Colette's trapeze act, the giant's rise.
 */
class SubtitleCinematicEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    val speaker: Ref<SpeakerEntry> = emptyRef(),
    @Colored
    @Placeholder
    @Help("Name shown above the lines when no speaker is linked. Empty = narration (no name row).")
    val speakerName: Var<String> = ConstVar(""),
    @Help("Center the lines (cutscene style). Off = left aligned.")
    val centered: Boolean = true,
    @Segments(icon = "mingcute:subtitle-fill")
    val segments: List<MultiLineDisplayDialogueSegment> = emptyList(),
) : CinematicEntry {
    override fun create(player: Player): CinematicAction = SubtitleCinematicAction(player, this)
}

/** Typing pace of subtitle lines in milliseconds per character (barks use it too). */
val subtitleMillisPerCharacter: Int by snippet("cinematic.subtitle.millisPerCharacter", 30)
/** Presses closer together than this are ignored, so a double tap never skips a line unread. */
val subtitleGraceMillis: Long by snippet("cinematic.subtitle.graceMillis", 250L)
private val subtitleHintNext: String by snippet("cinematic.subtitle.hint.next", "<gold>Jump / Shift</gold><gray> next line</gray>")
private val subtitleHintComplete: String by snippet("cinematic.subtitle.hint.complete", "<gold>Jump / Shift</gold><gray> show full line</gray>")

class SubtitleCinematicAction(
    private val player: Player,
    private val entry: SubtitleCinematicEntry,
) : CinematicAction, Listener {
    private val segments get() = entry.segments

    @Volatile private var current: MultiLineDisplayDialogueSegment? = null
    private var currentFrame = 0
    private var body = ""
    private var totalCharacters = 0
    private var typingMillis = 0L
    @Volatile private var forcedComplete = false
    @Volatile private var pendingAdvance = false
    @Volatile private var lastPressAt = 0L
    private var lastRenderFrame = Int.MIN_VALUE
    private var lastRevealed = -1
    private var heldJump = false
    private var heldShift = false
    private var packetListener: PacketListenerAbstract? = null

    override suspend fun setup() {
        super.setup()
        Dispatchers.Sync.switchContext {
            server.pluginManager.registerEvents(this@SubtitleCinematicAction, plugin)
            registerPacketListener()
        }
    }

    override suspend fun tick(frame: Int) {
        super.tick(frame)
        currentFrame = frame
        val segment = segments activeSegmentAt frame
        if (segment == null) {
            if (current != null) {
                current = null
                player.clearDialogueHud()
            }
            return
        }
        if (segment !== current) startSegment(segment, frame)

        if (pendingAdvance) {
            pendingAdvance = false
            jumpToNextLine(segment, frame)
            return
        }

        val elapsed = (frame - segment.startFrame) * 50L
        val complete = forcedComplete || elapsed >= typingMillis
        val revealed = if (complete) totalCharacters else
            revealedHudCharacters(body.asMini(), elapsed.toDouble() / typingMillis.coerceAtLeast(1))
        // Typing: redraw every frame. Idle: refresh every half second (the client fades an action bar out
        // after three seconds) unless a press changed what is shown.
        if (complete && revealed == lastRevealed && frame - lastRenderFrame < 10) return
        val hint = (if (complete) subtitleHintNext else subtitleHintComplete).asMini()
        player.showDialogueHud(SubtitleHudRenderer.render(
            speaker = speakerName().asMini(),
            body = body.asMini(),
            revealedCharacters = revealed,
            footer = SubtitleHudRenderer.Footer.Hint(hint),
            centered = entry.centered,
        ))
        lastRevealed = revealed
        lastRenderFrame = frame
    }

    private suspend fun startSegment(segment: MultiLineDisplayDialogueSegment, frame: Int) {
        current = segment
        forcedComplete = false
        pendingAdvance = false
        lastRevealed = -1
        lastRenderFrame = Int.MIN_VALUE
        // The advance itself counts as a press: the new line gets the same grace before it can be skipped.
        lastPressAt = System.currentTimeMillis()
        val source = segment.text.get(player)
        val local = DepartedLang.tr(player, source)
        body = local.parsePlaceholders(player)
        val plain = body.stripped()
        totalCharacters = plain.codePointCount(0, plain.length)
        typingMillis = totalCharacters.toLong() * subtitleMillisPerCharacter

        val voiceActor = segment.voice.get(player)
        val voiceOverride = segment.voicetext.get(player)
        val stopVoice = segment.stopvoice.get(player)
        val line = DepartedLang.voice(player, voiceOverride, source, local).parsePlaceholders(player).stripped()
        Dispatchers.Sync.switchContext {
            if (stopVoice == true) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "stoptalk ${player.name}")
            if (!voiceActor.isNullOrBlank() && line.isNotBlank()) {
                // The camera moves the player along a path, so the voice follows them.
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "talkchar -follow $voiceActor ${player.name} $line")
            }
        }
    }

    private fun speakerName(): String {
        val linked = entry.speaker.get()?.displayName?.get(player)
        val raw = if (!linked.isNullOrBlank()) linked else entry.speakerName.get(player)
        return DepartedLang.tr(player, raw).parsePlaceholders(player)
    }

    /** Jump/Shift: complete the sentence if it is still typing, otherwise go to the next line. */
    private fun press() {
        val segment = current ?: return
        val now = System.currentTimeMillis()
        if (now - lastPressAt < subtitleGraceMillis) return
        lastPressAt = now
        val elapsed = (currentFrame - segment.startFrame) * 50L
        if (!forcedComplete && elapsed < typingMillis) {
            forcedComplete = true
            lastRevealed = -1
        } else {
            pendingAdvance = true
        }
    }

    private fun jumpToNextLine(segment: MultiLineDisplayDialogueSegment, frame: Int) {
        val next = segments.filter { it.startFrame > segment.startFrame }.minByOrNull { it.startFrame }
        val target = next?.startFrame ?: (segment.endFrame + 1)
        if (target <= frame) return
        val context = player.interactionContext ?: return
        TemporalSetFrameTrigger(target).triggerFor(player, context)
    }

    private fun registerPacketListener() {
        if (packetListener != null) return
        packetListener = object : PacketListenerAbstract(PacketListenerPriority.NORMAL) {
            override fun onPacketReceive(event: PacketReceiveEvent) {
                if (event.packetType != PacketType.Play.Client.PLAYER_INPUT) return
                if (event.user?.uuid != player.uniqueId) return
                val input = WrapperPlayClientPlayerInput(event)
                val jump = input.isJump
                val shift = input.isShift
                val pressed = (jump && !heldJump) || (shift && !heldShift)
                heldJump = jump
                heldShift = shift
                if (pressed) press()
            }
        }
        PacketEvents.getAPI().eventManager.registerListener(packetListener!!)
    }

    // Clients older than 1.21.2 send no PLAYER_INPUT packet; their shift still arrives as a sneak toggle.
    @EventHandler
    fun onSneak(event: PlayerToggleSneakEvent) {
        if (event.player.uniqueId != player.uniqueId || !event.isSneaking) return
        if (heldShift) return
        press()
    }

    override suspend fun teardown() {
        super.teardown()
        current = null
        Dispatchers.Sync.switchContext {
            HandlerList.unregisterAll(this@SubtitleCinematicAction)
            packetListener?.let { runCatching { PacketEvents.getAPI().eventManager.unregisterListener(it) } }
            packetListener = null
            player.clearDialogueHud()
        }
    }

    override fun canFinish(frame: Int): Boolean = segments canFinishAt frame
}
