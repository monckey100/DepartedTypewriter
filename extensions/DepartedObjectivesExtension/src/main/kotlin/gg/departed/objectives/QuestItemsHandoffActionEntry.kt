package gg.departed.objectives

import com.typewritermc.basic.itemcore.ItemCoreBridge
import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.utils.launch
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.Modifier
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.ActionEntry
import com.typewritermc.engine.paper.entry.entries.ActionTrigger
import com.typewritermc.engine.paper.entry.matches
import com.typewritermc.engine.paper.utils.Sync
import com.typewritermc.engine.paper.utils.plainText
import com.typewritermc.engine.paper.utils.stripped
import dev.departed.itemcore.api.ItemCore
import dev.departed.itemcore.api.item.ItemId
import dev.departed.itemcore.api.item.ItemQuery
import dev.departed.itemcore.api.stock.ConsumeResult
import dev.departed.itemcore.api.stock.Requirement
import dev.departed.itemcore.api.stock.StockSources
import kotlinx.coroutines.Dispatchers

/** Hands over a complete quest requirement before applying modifiers or triggering its reward. */
@Entry("quest_items_handoff", "Hand over multiple item types atomically before continuing a quest", Colors.YELLOW, "fa6-solid:hand-holding")
class QuestItemsHandoffActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    val requirements: List<QuestHandoffRequirement> = emptyList(),
    val failureTriggers: List<Ref<TriggerableEntry>> = emptyList(),
) : ActionEntry {
    override fun ActionTrigger.execute() {
        disableAutomaticTriggering()
        Dispatchers.Sync.launch {
            if (!criteria.matches(player, context)) return@launch
            if (!ItemCoreBridge.active || requirements.isEmpty() || requirements.any { it.amount <= 0 }) {
                failureTriggers.triggerFor(player)
                return@launch
            }
            val requested = runCatching {
                requirements.map { Requirement(it.query(), it.amount) }
            }.getOrNull()
            if (requested == null) {
                failureTriggers.triggerFor(player)
                return@launch
            }
            val result = ItemCore.get().stock().consume(
                player, requested, StockSources.INVENTORY, "quest:handoff/$id"
            )
            if (result !is ConsumeResult.Consumed) {
                failureTriggers.triggerFor(player)
                return@launch
            }
            applyModifiers()
            triggers.triggerFor(player)
        }
    }
}

/**
 * Required units for a mixed quest delivery: an exact ItemCore [itemId], or, when [name] is set, any item whose plain
 * display name equals it (dynamic items such as brewed potions have no catalog id).
 */
data class QuestHandoffRequirement(val itemId: String = "", val amount: Int = 1, val name: String = "") {
    fun query(): ItemQuery {
        if (name.isBlank()) return ItemQuery.id(ItemId.of(itemId))
        val wanted = name.stripped()
        return ItemQuery(null, null, null) { it.stack()?.effectiveName()?.plainText() == wanted }
    }
}
