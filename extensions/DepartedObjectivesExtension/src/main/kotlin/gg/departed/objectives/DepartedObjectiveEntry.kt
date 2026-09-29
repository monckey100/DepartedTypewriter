package gg.departed.objectives

import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Tags
import com.typewritermc.core.interaction.InteractionContext
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.EventEntry
import com.typewritermc.engine.paper.entry.entries.Var
import org.bukkit.entity.Player
import kotlin.math.max

@Tags("departed_objective")
interface DepartedObjectiveEntry : EventEntry {
    @Help("The DepartedRPG quest id this objective belongs to.")
    val questId: String

    @Help("Lower values are shown first by %objective%.")
    val order: Int

    @Help("The player-facing objective text. Supports {progress}, {amount}, {remaining}, and PAPI placeholders.")
    val instruction: Var<String>

    @Help("The required amount before the objective completes.")
    val amount: Var<Int>

    @Help("Complete the DepartedRPG quest when every Typewriter objective for this quest is complete.")
    val completeDepartedQuestWhenDone: Boolean

    @Help("Entries fired once when this objective is completed.")
    override val triggers: List<Ref<TriggerableEntry>>

    fun targetAmount(player: Player, context: InteractionContext? = null): Int = max(1, amount.get(player, context))
}

/**
 * An objective whose live progress is mirrored into DepartedRPG's in-memory quest variables.
 */
interface QuestVariableObjectiveEntry {
    @Help("DepartedRPG quest variable receiving this objective's live progress. Blank disables syncing.")
    val progressVariable: String
}
