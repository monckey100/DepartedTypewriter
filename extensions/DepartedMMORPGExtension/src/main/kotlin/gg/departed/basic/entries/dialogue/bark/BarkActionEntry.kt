package gg.departed.basic.entries.dialogue.bark

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Colored
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.MultiLine
import com.typewritermc.core.extension.annotations.Placeholder
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.Modifier
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.ActionEntry
import com.typewritermc.engine.paper.entry.entries.ActionTrigger
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.SpeakerEntry
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.extensions.placeholderapi.parsePlaceholders
import com.typewritermc.engine.paper.utils.Sync
import com.typewritermc.engine.paper.utils.asMini
import com.typewritermc.engine.paper.utils.stripped
import gg.departed.basic.entries.dialogue.DepartedLang
import kotlinx.coroutines.Dispatchers
import com.typewritermc.core.utils.launch
import org.bukkit.Bukkit
import java.time.Duration

@Entry("departed_bark", "Auto-dismissing subtitle line (no mount, no input)", Colors.CYAN, "mingcute:subtitle-fill")
/**
 * The `Bark` shows one line on the HUD band that dismisses on its own: an NPC calling out as you pass,
 * a warning while you fight, a reaction after an event. The player keeps full control and nothing goes to chat.
 *
 * ## How could this be used?
 *
 * A guard shouting as the player runs past, a boss taunt, an NPC reacting to a quest completion.
 */
class BarkActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    val speaker: Ref<SpeakerEntry> = emptyRef(),
    @Colored
    @Placeholder
    @Help("Name shown above the line when no speaker is linked. Empty = narration (no name row).")
    val speakerName: Var<String> = ConstVar(""),
    @Placeholder
    @Colored
    @MultiLine
    val text: Var<String> = ConstVar(""),
    @Help("How long the line stays after it finished typing.")
    val hold: Var<Duration> = ConstVar(Duration.ofSeconds(4)),
    @Help("Voice Actor for the Departed RPG engine")
    val voice: Var<String> = ConstVar(""),
    @Help("Voice Text, if empty it will use the regular text.")
    val voicetext: Var<String> = ConstVar(""),
) : ActionEntry {
    override fun ActionTrigger.execute() {
        val linked = speaker.get()?.displayName?.get(player)
        val nameRaw = if (!linked.isNullOrBlank()) linked else speakerName.get(player)
        val name = DepartedLang.tr(player, nameRaw).parsePlaceholders(player)
        val source = text.get(player)
        val local = DepartedLang.tr(player, source)
        val body = local.parsePlaceholders(player)
        val voiceActor = voice.get(player)
        val line = DepartedLang.voice(player, voicetext.get(player), source, local).parsePlaceholders(player).stripped()
        val holdMillis = hold.get(player).toMillis()
        Dispatchers.Sync.launch {
            SubtitleBarks.show(player, name.asMini(), body, holdMillis)
            if (!voiceActor.isNullOrBlank() && line.isNotBlank()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "talkchar -follow $voiceActor ${player.name} $line")
            }
        }
    }
}
