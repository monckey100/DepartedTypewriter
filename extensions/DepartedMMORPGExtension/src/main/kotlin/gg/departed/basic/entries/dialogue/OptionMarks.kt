package gg.departed.basic.entries.dialogue

import com.monckey100.departedrpg.api.DepartedRpgAPI
import com.typewritermc.core.entries.Ref
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.snippets.snippet
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.Collections
import java.util.WeakHashMap

private val pickedMark: String by snippet(
    "dialogue.option.picked",
    "<#78ff85>✔</#78ff85> ",
    "Put in front of an option the player has already picked before."
)
private val optionalMark: String by snippet(
    "dialogue.option.optional",
    " <#5d6c78>(Optional)",
    "Put behind an option that only loops back to the same choice instead of progressing the story."
)
private val pickedMarkBedrock: String by snippet("dialogue.option.bedrock.picked", "✔ ")
private val optionalMarkBedrock: String by snippet("dialogue.option.bedrock.optional", " (Optional)")

/** Whether an option gets the "(Optional)" tag. */
enum class OptionalTag {
    /** Tag it when its branch only leads back to this choice (see [OptionMarks.isOptional]). */
    AUTO,
    ALWAYS,
    NEVER,
}

/**
 * The two decorations on dialogue options: a checkmark on options the player has picked before,
 * and "(Optional)" on side branches that do not progress the story.
 */
object OptionMarks {
    // Picked options live on a DepartedRPG pseudo-quest row (MySQL quests.data), one key per
    // option, so they are scoped per character like the rest of the quest log and are readable
    // from the write-through cache without a database round trip. "-1" is alignment, "-2" the
    // tutorial talk gates.
    private const val PICKED_QUEST = "-3"

    private val api: DepartedRpgAPI?
        get() = try {
            Bukkit.getServicesManager().load(DepartedRpgAPI::class.java)
        } catch (_: LinkageError) {
            null
        }

    /** The option text with its marks, as MiniMessage. [text] is the already translated text. */
    fun decorate(player: Player, text: String, picked: Boolean, optional: Boolean): String =
        (if (picked) pickedMark else "") + text + (if (optional) DepartedLang.tr(player, optionalMark) else "")

    fun decorateBedrock(player: Player, text: String, picked: Boolean, optional: Boolean): String =
        (if (picked) pickedMarkBedrock else "") + text +
                (if (optional) DepartedLang.tr(player, optionalMarkBedrock) else "")

    // ------------------------------------------------------------------ picked

    // Keyed by the authored text rather than the index, so reordering or adding options keeps
    // the marks on the right lines.
    private fun key(entry: OptionDialogueEntry, option: Option, player: Player): String {
        val hash = (entry.id + "|" + option.text.get(player)).hashCode()
        return "o" + Integer.toHexString(hash)
    }

    /** The options of [options] this character has picked before. */
    fun picked(player: Player, entry: OptionDialogueEntry, options: List<Option>): Set<Option> {
        val api = api ?: return emptySet()
        return try {
            options.filterTo(HashSet()) {
                api.getQuestDataCachedValue(player, PICKED_QUEST, key(entry, it, player)).isNotEmpty()
            }
        } catch (_: Throwable) {
            emptySet()
        }
    }

    fun markPicked(player: Player, entry: OptionDialogueEntry, option: Option) {
        val api = api ?: return
        val key = key(entry, option, player)
        try {
            if (api.getQuestDataCachedValue(player, PICKED_QUEST, key).isNotEmpty()) return
            api.setQuestDataValue(player, PICKED_QUEST, key, "1")
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- optional

    private const val WALK_BUDGET = 4000

    // Entries are rebuilt on every reload, so weak keys drop the stale results with them.
    private val optionalCache = Collections.synchronizedMap(WeakHashMap<OptionDialogueEntry, Set<Option>>())

    /**
     * An option is optional when it is a detour: whatever the player does after picking it, the
     * conversation comes back to this same choice (or to a later menu still offering the
     * sibling), while a sibling option leads somewhere it cannot come back from. "Tell me about
     * X" in a question hub is optional; the option that moves on is not. Options that close the
     * dialogue (no triggers) are never tagged and never count as the story branch.
     */
    fun isOptional(entry: OptionDialogueEntry, option: Option): Boolean = when (option.optional) {
        OptionalTag.ALWAYS -> true
        OptionalTag.NEVER -> false
        OptionalTag.AUTO -> option in optionalCache.getOrPut(entry) { detours(entry) }
    }

    private fun detours(entry: OptionDialogueEntry): Set<Option> {
        val options = entry.options
        val ids = options.map { o -> o.triggers.mapTo(HashSet()) { it.id } }
        val out = HashSet<Option>()
        for (i in options.indices) {
            if (ids[i].isEmpty()) continue
            for (j in options.indices) {
                if (j == i || ids[j].isEmpty() || ids[j] == ids[i]) continue
                if (Walk(entry.id, ids[j]).returns(options[i].triggers) &&
                    !Walk(entry.id, ids[i]).returns(options[j].triggers)
                ) {
                    out += options[i]
                    break
                }
            }
        }
        return out
    }

    /**
     * Does a branch always come back to the choice [hub], or to a later menu that still offers
     * the sibling whose triggers are [sibling]?
     */
    private class Walk(private val hub: String, private val sibling: Set<String>) {
        private var budget = WALK_BUDGET
        private val visiting = HashSet<String>()

        // An entry fires all of its triggers, so one returning is enough.
        fun returns(refs: List<Ref<TriggerableEntry>>): Boolean = refs.any { returns(it) }

        private fun returns(ref: Ref<TriggerableEntry>): Boolean {
            if (ref.id == hub) return true
            if (--budget < 0) return false
            // A loop that never leaves through a dead end is still a detour.
            if (!visiting.add(ref.id)) return true
            try {
                val entry = ref.get() ?: return false
                if (returns(entry.triggers)) return true
                if (entry !is OptionDialogueEntry) return false
                // The player picks one option, so every one of them has to return.
                return entry.options.isNotEmpty() && entry.options.all { o ->
                    o.triggers.mapTo(HashSet()) { it.id } == sibling || returns(o.triggers)
                }
            } finally {
                visiting.remove(ref.id)
            }
        }
    }
}
