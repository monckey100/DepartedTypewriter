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
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.entry.matches
import com.typewritermc.engine.paper.utils.Sync
import com.typewritermc.engine.paper.utils.item.CustomItem
import com.typewritermc.engine.paper.utils.item.Item
import com.typewritermc.engine.paper.utils.item.SerializedItem
import com.typewritermc.engine.paper.utils.item.components.ItemAmountComponent
import kotlinx.coroutines.Dispatchers

/** Hands over a complete quest requirement before applying modifiers or triggering its reward. */
@Entry("quest_item_handoff", "Hand over all required items before continuing a quest", Colors.YELLOW, "fa6-solid:hand-holding")
class QuestItemHandoffActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    val item: Var<Item> = ConstVar(Item.Empty),
    val failureTriggers: List<Ref<TriggerableEntry>> = emptyList(),
) : ActionEntry {
    override fun ActionTrigger.execute() {
        disableAutomaticTriggering()
        Dispatchers.Sync.launch {
            if (!criteria.matches(player, context)) return@launch
            val requiredItem = item.get(player, context)
            val required = when (requiredItem) {
                is SerializedItem -> requiredItem.build(player, context).amount
                is CustomItem -> requiredItem.components<ItemAmountComponent>()
                    .takeIf { it.isNotEmpty() }
                    ?.sumOf { it.amount.get(player, context) } ?: 0
            }
            if (required <= 0 || !ItemCoreBridge.active) {
                failureTriggers.triggerFor(player)
                return@launch
            }
            val removed = ItemCoreBridge.consume(player, requiredItem, context, required, "quest:handoff/$id")
            if (removed != required) {
                failureTriggers.triggerFor(player)
                return@launch
            }
            applyModifiers()
            triggers.triggerFor(player)
        }
    }
}
