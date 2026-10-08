package com.typewritermc.basic.entries.action

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
import com.typewritermc.engine.paper.extensions.placeholderapi.parsePlaceholders
import com.typewritermc.engine.paper.snippets.snippet
import com.typewritermc.engine.paper.utils.Sync
import com.typewritermc.engine.paper.utils.asMini
import com.typewritermc.engine.paper.utils.item.Item
import kotlinx.coroutines.Dispatchers

private val dropMessage by snippet(
    "give_item.drop",
    "<gray>Some items have been dropped because your inventory is full"
)

private val refusedMessage by snippet(
    "give_item.refused",
    "<gray>Some items could not be given to you right now"
)

@Entry("give_item", "Give an item to the player", Colors.RED, "streamline:give-gift-solid")
/**
 * The `Give Item Action` is an action that gives a player an item. This action provides you with the ability to give an item with a specified Minecraft material, amount, display name, and lore.
 *
 * ## How could this be used?
 *
 * This action can be useful in a variety of situations. You can use it to give players rewards for completing quests, unlockables for reaching certain milestones, or any other custom items you want to give players. The possibilities are endless!
 *
 * With DepartedItemCore in custody mode the item goes through the core's give (inventory, overflow backpack,
 * delivery queue) and is never dropped on the ground.
 */
class GiveItemActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    val item: Var<Item> = ConstVar(Item.Empty),
) : ActionEntry {
    override fun ActionTrigger.execute() {
        // Build the item before modifying fact or continuing other triggers to ensure that the item is built with the correct state and context.
        val itemStack = item.get(player, context).build(player, context)
        Dispatchers.Sync.launch {
            if (ItemCoreBridge.active) {
                val result = ItemCoreBridge.give(player, itemStack, "quest:typewriter/give_item/$id")
                if (result.notPlaced > 0) {
                    player.sendMessage(refusedMessage.parsePlaceholders(player).asMini())
                }
                return@launch
            }
            val leftOver = player.inventory.addItem(itemStack)
            leftOver.values.forEach {
                player.world.dropItemNaturally(player.location, it)
            }
            if (leftOver.isNotEmpty()) {
                player.sendMessage(dropMessage.parsePlaceholders(player).asMini())
            }
        }
    }
}