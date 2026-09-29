package gg.departed.objectives

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.Modifier
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.ActionEntry
import com.typewritermc.engine.paper.entry.entries.ActionTrigger
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.GroupEntry
import com.typewritermc.engine.paper.entry.entries.ReadableFactEntry
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.facts.FactData
import org.bukkit.entity.Player

// Multi-path quest tracking. The path a player takes through a branching quest is stored on the
// DepartedRPG quest row itself (MySQL quests.path, a comma-separated int array), so reading the
// quest back tells you the route without external state. Other plugins read it through
// %departed_quest_path_<questId>% / %departed_quest_haspath_<questId>_<n>% or DepartedRpgAPI.

@Entry(
    "add_departed_quest_path",
    "Record which path the player took through a Departed quest",
    Colors.RED,
    "mdi:source-branch",
)
/**
 * Appends a path number to the quest's stored path array (duplicates are ignored).
 * Fire this on the dialogue branch/decision that commits the player to a route.
 *
 * The array is wiped when the quest is (re)started or cancelled, and kept on completion —
 * that's the record of the route the player took.
 */
class AddDepartedQuestPathActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("The DepartedRPG quest id (as in quests.yml).")
    val questId: String = "",
    @Help("The path number to record for this quest.")
    val path: Var<Int> = ConstVar(1),
) : ActionEntry {
    override fun ActionTrigger.execute() {
        DepartedRpgBridge.addQuestPath(player, questId, path.get(player, context))
    }
}

@Entry(
    "clear_departed_quest_path",
    "Clear the recorded path of a Departed quest",
    Colors.RED,
    "mdi:source-branch-remove",
)
/**
 * Wipes the quest's stored path array. Rarely needed — starting or cancelling the quest
 * already clears it — but useful when a storyline lets the player re-decide mid-quest.
 */
class ClearDepartedQuestPathActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("The DepartedRPG quest id (as in quests.yml).")
    val questId: String = "",
) : ActionEntry {
    override fun ActionTrigger.execute() {
        DepartedRpgBridge.clearQuestPath(player, questId)
    }
}

@Entry(
    "departed_quest_path_fact",
    "Whether the player took a specific path through a Departed quest",
    Colors.PURPLE,
    "mdi:source-branch-check",
)
/**
 * Reads `1` when the given path number is in the quest's recorded path array, otherwise `0`.
 * Use it as a criterion to branch WIP and completed dialogue on the route the player took.
 */
class DepartedQuestPathFact(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "",
    override val group: Ref<GroupEntry> = emptyRef(),
    @Help("The DepartedRPG quest id (as in quests.yml).")
    val questId: String = "",
    @Help("The path number to check for.")
    val path: Int = 1,
) : ReadableFactEntry {
    override fun readSinglePlayer(player: Player): FactData {
        return FactData(if (DepartedRpgBridge.hasQuestPath(player, questId, path)) 1 else 0)
    }
}
