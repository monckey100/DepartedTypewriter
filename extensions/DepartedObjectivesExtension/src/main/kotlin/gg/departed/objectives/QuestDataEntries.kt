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
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.extensions.placeholderapi.parsePlaceholders

// Persistent free-form key/value data on the DepartedRPG quest row (MySQL quests.data): kill
// counts, items collected, a name the player chose, etc. Survives restarts, unlike questvar.
// Read it back with %departed_quest_data_<questId>_<key>% (or <questId>:<key> when the key
// contains underscores), or through DepartedRpgAPI.

@Entry(
    "set_departed_quest_data",
    "Store an extra key/value on a Departed quest",
    Colors.RED,
    "mdi:database-edit",
)
/**
 * Sets one extra-data key on the quest row. The value supports placeholders, so
 * `%player_name%` or another PAPI value can be captured at the moment the action fires.
 * An empty value removes the key.
 *
 * The data is wiped when the quest is (re)started or cancelled, and kept on completion.
 */
class SetDepartedQuestDataActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("The DepartedRPG quest id (as in quests.yml).")
    val questId: String = "",
    @Help("The data key (case-insensitive), e.g. 'name_chosen' or 'kills'.")
    val key: String = "",
    @Help("The value to store. Placeholders are resolved. Empty removes the key.")
    val value: Var<String> = ConstVar(""),
) : ActionEntry {
    override fun ActionTrigger.execute() {
        val resolved = value.get(player, context).parsePlaceholders(player)
        DepartedRpgBridge.setQuestDataValue(player, questId, key, resolved)
    }
}

@Entry(
    "clear_departed_quest_data",
    "Clear all extra data stored on a Departed quest",
    Colors.RED,
    "mdi:database-remove",
)
/**
 * Wipes every extra-data key on the quest row. Rarely needed — starting or cancelling the
 * quest already clears it — but useful when a storyline restarts its bookkeeping mid-quest.
 */
class ClearDepartedQuestDataActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("The DepartedRPG quest id (as in quests.yml).")
    val questId: String = "",
) : ActionEntry {
    override fun ActionTrigger.execute() {
        DepartedRpgBridge.clearQuestData(player, questId)
    }
}
