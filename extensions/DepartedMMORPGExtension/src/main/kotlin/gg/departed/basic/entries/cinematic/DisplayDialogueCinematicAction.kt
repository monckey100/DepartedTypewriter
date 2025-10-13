package gg.departed.basic.entries.cinematic

import com.typewritermc.core.extension.annotations.Colored
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.MultiLine
import com.typewritermc.core.extension.annotations.Placeholder
import com.typewritermc.core.utils.switchContext
import com.typewritermc.engine.paper.entry.dialogue.playSpeakerSound
import com.typewritermc.engine.paper.entry.entries.*
import com.typewritermc.engine.paper.extensions.placeholderapi.parsePlaceholders
import com.typewritermc.engine.paper.utils.GenericPlayerStateProvider.EXP
import com.typewritermc.engine.paper.utils.GenericPlayerStateProvider.LEVEL
import com.typewritermc.engine.paper.utils.PlayerState
import com.typewritermc.engine.paper.utils.Sync
import com.typewritermc.engine.paper.utils.restore
import com.typewritermc.engine.paper.utils.state
import kotlinx.coroutines.Dispatchers
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player

data class SingleLineDisplayDialogueSegment(
    override val startFrame: Int = 0,
    override val endFrame: Int = 0,
    override val text: Var<String> = ConstVar(""),
    @Help("Voice Actor for the Departed RPG engine")
    override val voice: Var<String> = ConstVar(""),
    @Help("Voice Text, if empty it will use the regular text.")
    override val voicetext: Var<String> = ConstVar(""),
    @Help("Stop previous voices from playing.")
    override val stopvoice: Var<Boolean> = ConstVar(true),
) : DisplayDialogueSegment

data class MultiLineDisplayDialogueSegment(
    override val startFrame: Int = 0,
    override val endFrame: Int = 0,
    @MultiLine
    override val text: Var<String> = ConstVar(""),
    @Help("Voice Actor for the Departed RPG engine")
    override val voice: Var<String> = ConstVar(""),
    @Help("Voice Text, if empty it will use the regular text.")
    override val voicetext: Var<String> = ConstVar(""),
    @Help("Stop previous voices from playing.")
    override val stopvoice: Var<Boolean> = ConstVar(true),
) : DisplayDialogueSegment

interface DisplayDialogueSegment : Segment {
    @Placeholder
    @Colored
    @Help("The text to display to the player.")
    val text: Var<String>

    @Help("Voice Actor for the Departed RPG engine")
    val voice: Var<String>

    @Help("Voice Text, if empty it will use the regular text.")
    val voicetext: Var<String>

    @Help("Stop previous voices from playing.")
    val stopvoice: Var<Boolean>
}

class DisplayDialogueCinematicAction(
    val player: Player,
    val speaker: SpeakerEntry?,
    private val segments: List<DisplayDialogueSegment>,
    private val splitPercentage: Double,
    private val setup: (Player.() -> Unit)? = null,
    private val teardown: (Player.() -> Unit)? = null,
    private val reset: (Player.() -> Unit)? = null,
    val display: (Player, String, String, Double) -> Unit,
) : CinematicAction {

    private var previousSegment: DisplayDialogueSegment? = null
    private var state: PlayerState? = null
    private var displayText = ""

    override suspend fun setup() {
        super.setup()
        state = player.state(EXP, LEVEL)
        setup?.invoke(player)
    }

    override suspend fun tick(frame: Int) {
        super.tick(frame)
        val segment = (segments activeSegmentAt frame)

        if (segment == null) {
            if (previousSegment != null) {
                player.exp = 0f
                player.level = 0
                reset?.invoke(player)
                displayText = ""
                previousSegment = null
            }
            return
        }

        if (previousSegment != segment) {
            // new segment started
            player.level = 0
            player.exp = 1f
            player.playSpeakerSound(speaker)
            previousSegment = segment
            displayText = segment.text.get(player).parsePlaceholders(player)

            // --- Voice control: fire ONCE at segment start ---
            val voiceActor = segment.voice.get(player)
            val voiceOverride = segment.voicetext.get(player)
            val stopVoice = segment.stopvoice.get(player)

            // choose voice line: voicetext if non-blank, else the displayed text
            val rawVoiceLine = if (!voiceOverride.isNullOrBlank()) voiceOverride else displayText
            val chosenVoiceLine = ChatColor.stripColor(rawVoiceLine) ?: rawVoiceLine

            Dispatchers.Sync.switchContext {
                if (stopVoice == true) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "stoptalk ${player.name}")
                }
                if (!voiceActor.isNullOrBlank() && chosenVoiceLine.isNotBlank()) {
                    Bukkit.dispatchCommand(
                        Bukkit.getConsoleSender(),
                        "talkchar $voiceActor ${player.name} $chosenVoiceLine"
                    )
                }
            }
        }

        val percentage = segment percentageAt frame
        player.level = 0
        player.exp = 1 - percentage.toFloat()

        // The percentage of the dialogue that should be displayed.
        val displayPercentage = percentage / splitPercentage

        // Avoid spamming once fully displayed
        if (displayPercentage > 1.1) {
            val needsDisplay = (frame - segment.startFrame) % 20 == 0
            if (!needsDisplay) return
        }

        display(
            player,
            speaker?.displayName?.get(player)?.parsePlaceholders(player) ?: "",
            displayText,
            displayPercentage
        )
    }

    override suspend fun teardown() {
        super.teardown()
        teardown?.invoke(player)
        reset?.invoke(player)
        Dispatchers.Sync.switchContext {
            player.restore(state)
        }
    }

    override fun canFinish(frame: Int): Boolean = segments canFinishAt frame
}
