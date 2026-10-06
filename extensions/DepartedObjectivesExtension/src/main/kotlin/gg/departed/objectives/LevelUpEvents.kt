package gg.departed.objectives

import com.monckey100.departedrpg.api.ClassLevelUpEvent
import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Query
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.EntryListener
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.interaction.context
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.EventEntry
import com.typewritermc.engine.paper.entry.triggerAllFor

/**
 * Fires when DepartedRPG raises a player's class level (ClassLevelUpEvent). One gain can cross
 * several levels at once, so `level` matches when it lies anywhere in (old, new].
 */
@Entry("departed_level_up_event", "When the player's class level goes up", Colors.YELLOW, "mdi:arrow-up-bold-circle")
class DepartedLevelUpEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("Only fire when this level is reached. 0 fires on every level up.")
    val level: Int = 0,
) : EventEntry

@EntryListener(DepartedLevelUpEventEntry::class)
fun onDepartedLevelUp(event: ClassLevelUpEvent, query: Query<DepartedLevelUpEventEntry>) {
    query.findWhere { it.level <= 0 || it.level in (event.oldLevel + 1)..event.newLevel }
        .triggerAllFor(event.player, context())
}
