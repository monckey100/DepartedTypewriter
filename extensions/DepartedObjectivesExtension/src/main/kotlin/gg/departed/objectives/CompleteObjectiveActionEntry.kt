package gg.departed.objectives

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.Modifier
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.ActionEntry
import com.typewritermc.engine.paper.entry.entries.ActionTrigger
import org.koin.java.KoinJavaComponent.get

@Entry(
    "complete_departed_objective",
    "Force-complete Departed objectives for the player",
    Colors.RED,
    "mdi:check-decagram",
)
/**
 * Waives the referenced objectives: they read as complete and the scoreboard advances to the next
 * objective in order. Deliberately unconditional — it works even in the same tick as the quest
 * being started (no async race), and it does NOT fire the objectives' completion triggers or
 * their complete-quest-when-done flag, so firing it for a player who already finished the quest
 * changes nothing. Idempotent; safe on every interact.
 *
 * Use for skip paths — e.g. a player who bypassed the tutorial ship gets its kill objectives
 * waived when the town dialogue picks the quest up instead.
 */
class CompleteDepartedObjectiveActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("The objectives to mark complete.")
    val objectives: List<Ref<DepartedObjectiveEntry>> = emptyList(),
) : ActionEntry {
    override fun ActionTrigger.execute() {
        val manager: DepartedObjectiveManager = get(DepartedObjectiveManager::class.java)
        for (ref in objectives) {
            val objective = ref.get() ?: continue
            manager.waive(player, objective)
        }
    }
}
