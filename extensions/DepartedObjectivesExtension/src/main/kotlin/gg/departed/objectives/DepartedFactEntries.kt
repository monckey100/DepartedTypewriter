package gg.departed.objectives

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.engine.paper.entry.entries.GroupEntry
import com.typewritermc.engine.paper.entry.entries.ReadableFactEntry
import com.typewritermc.engine.paper.entry.entries.WritableFactEntry
import com.typewritermc.engine.paper.facts.FactData
import com.typewritermc.engine.paper.facts.FactId
import com.typewritermc.engine.paper.facts.ProfileScope
import org.bukkit.entity.Player

// MySQL-backed drop-in replacements for Typewriter's flat-file permanent facts. Both entries are
// readable AND writable, so existing criteria, modifiers, and "tw facts set" commands keep
// working when a permanent_fact entry's type is swapped to one of these — only the entry itself
// changes, every reference stays untouched.
//
// Reads never touch the database: they come from DepartedRPG's quest cache, which is primed on
// join/character switch and synchronously on every write (so a criterion later in the same
// dialogue chain sees the value a modifier just wrote — the property the old pay-guard facts
// relied on). Writes are one synchronous MySQL statement, only on actual state changes.

@Entry(
    "departed_quest_path_value_fact",
    "A persistent fact stored in a Departed quest's path array",
    Colors.PURPLE,
    "mdi:source-branch-check",
)
/**
 * One fact value stored in a 100-wide slice of the quest's path array (MySQL quests.path):
 * the slice holds at most one number, `offset + value`. Reading yields that value (0 when the
 * slice is empty); writing replaces the slice (0 empties it) — exactly permanent-fact semantics.
 *
 * Give each fact of a quest its own offset (0, 100, 200, ...). The path array travels with the
 * quest row: wiped on start/cancel/reset, kept on completion, and readable from quests.yml lore
 * via %departed_quest_pathval_<questId>_<offset>%.
 */
class DepartedQuestPathValueFact(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "",
    override val group: Ref<GroupEntry> = emptyRef(),
    @Help("The DepartedRPG quest id whose path array stores this fact ('-1' = the story pseudo-quest).")
    val questId: String = "",
    @Help("Start of this fact's 100-wide slice in the path array (0, 100, 200, ...).")
    val offset: Int = 0,
) : ReadableFactEntry, WritableFactEntry {
    override fun readSinglePlayer(player: Player): FactData {
        var best = 0
        for (p in DepartedRpgBridge.cachedQuestPathValues(player, questId)) {
            if (p in offset until offset + SPAN) best = maxOf(best, p - offset)
        }
        return FactData(best)
    }

    override fun write(player: Player, value: Int) {
        DepartedRpgBridge.setQuestPathRangeValue(player, questId, offset, SPAN, value)
    }

    override fun write(id: FactId, value: Int) {
        val player = ProfileScope.playerFor(id.groupId) ?: return
        write(player, value)
    }

    private companion object {
        const val SPAN = 100
    }
}

@Entry(
    "departed_quest_data_value_fact",
    "A persistent fact stored in a Departed quest's extra data",
    Colors.PURPLE,
    "mdi:database-check",
)
/**
 * A fact backed by one key of the quest's extra-data blob (MySQL quests.data). Reading parses
 * the stored value as a number (0 when unset or non-numeric); writing stores it (0 removes the
 * key). Use for ad-hoc state and counters; step/branch facts that gate frequently-evaluated
 * criteria belong in [DepartedQuestPathValueFact] instead.
 *
 * Readable from quests.yml lore via %departed_quest_data_<questId>_<key>%.
 */
class DepartedQuestDataValueFact(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "",
    override val group: Ref<GroupEntry> = emptyRef(),
    @Help("The DepartedRPG quest id whose extra data stores this fact.")
    val questId: String = "",
    @Help("The data key (case-insensitive).")
    val key: String = "",
) : ReadableFactEntry, WritableFactEntry {
    override fun readSinglePlayer(player: Player): FactData {
        val raw = DepartedRpgBridge.cachedQuestDataValue(player, questId, key)
        return FactData(raw.trim().toIntOrNull() ?: 0)
    }

    override fun write(player: Player, value: Int) {
        DepartedRpgBridge.setQuestDataValue(player, questId, key, if (value == 0) null else value.toString())
    }

    override fun write(id: FactId, value: Int) {
        val player = ProfileScope.playerFor(id.groupId) ?: return
        write(player, value)
    }
}
