package gg.departed.objectives

import com.typewritermc.basic.itemcore.ItemCoreBridge
import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Query
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.Default
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.EntryListener
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.utils.item.Item
import dev.departed.departednpc.api.event.DepartedNpcInteractEvent
import org.koin.java.KoinJavaComponent.get

/**
 * DepartedNPC-native equivalents of the FancyNpc quest objectives. The floating quest marker is
 * rendered by DepartedNPC itself (native item-display "?"/"!") via [DepartedNpcQuestRuntime], so these
 * entries only carry the marker's custom-model-data settings.
 */
interface DepartedNpcObjectiveEntry : DepartedObjectiveEntry {
    val npcIdentifier: String
    val marker: DepartedNpcQuestMarkerSettings
}

data class DepartedNpcQuestMarkerSettings(
    @Help("Whether this objective shows a floating quest marker above the DepartedNPC.")
    @Default("true")
    val enabled: Boolean = true,
    @Help("Custom model data (PAPER) for the marker when the quest can be started.")
    @Default("49261")
    val readyCustomModelData: Int = 49261,
    @Help("Custom model data (PAPER) for the marker while the quest is in progress. 0 = reuse 'ready'.")
    @Default("49505")
    val currentCustomModelData: Int = 49505,
) {
    fun effectiveCurrentModelData(): Int =
        if (currentCustomModelData > 0) currentCustomModelData else readyCustomModelData
}

@Entry("talk_to_departednpc_objective", "Talk to a DepartedNPC NPC", Colors.YELLOW, "fa-solid:user-tie")
class TalkToDepartedNpcObjectiveEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    override val questId: String = "",
    override val order: Int = 0,
    override val instruction: Var<String> = ConstVar("Talk to the marked NPC."),
    override val amount: Var<Int> = ConstVar(1),
    override val completeDepartedQuestWhenDone: Boolean = true,
    @Help("DepartedNPC id. Leave empty to match any NPC.")
    override val npcIdentifier: String = "",
    val interactionType: DepartedNpcClickType = DepartedNpcClickType.ANY_CLICK,
    override val marker: DepartedNpcQuestMarkerSettings = DepartedNpcQuestMarkerSettings(),
) : DepartedNpcObjectiveEntry

@EntryListener(TalkToDepartedNpcObjectiveEntry::class)
fun onTalkToDepartedNpcObjective(event: DepartedNpcInteractEvent, query: Query<TalkToDepartedNpcObjectiveEntry>) {
    query.findWhere { it.interactionType.matches(event.isLeftClick) && matchesDepartedNpcId(event.npcId, it.npcIdentifier) }
        .forEach { departedObjectives().increment(event.player, it) }
}

@Entry("deliver_item_to_departednpc_objective", "Deliver items to a DepartedNPC NPC", Colors.YELLOW, "fa6-solid:hand-holding")
/**
 * Clicking the NPC hands over matching items from the hotbar and bag in one all-or-nothing DepartedItemCore take
 * (as many as are still needed and held); the objective advances by what was taken.
 */
class DeliverItemToDepartedNpcObjectiveEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    override val questId: String = "",
    override val order: Int = 0,
    override val instruction: Var<String> = ConstVar("Deliver {remaining} more items."),
    override val amount: Var<Int> = ConstVar(1),
    override val completeDepartedQuestWhenDone: Boolean = true,
    @Help("DepartedNPC id. Leave empty to match any NPC.")
    override val npcIdentifier: String = "",
    val interactionType: DepartedNpcClickType = DepartedNpcClickType.ANY_CLICK,
    val item: Var<Item> = ConstVar(Item.Empty),
    override val marker: DepartedNpcQuestMarkerSettings = DepartedNpcQuestMarkerSettings(),
) : DepartedNpcObjectiveEntry

@EntryListener(DeliverItemToDepartedNpcObjectiveEntry::class)
fun onDeliverItemToDepartedNpcObjective(event: DepartedNpcInteractEvent, query: Query<DeliverItemToDepartedNpcObjectiveEntry>) {
    val manager = departedObjectives()
    query.findWhere { it.interactionType.matches(event.isLeftClick) && matchesDepartedNpcId(event.npcId, it.npcIdentifier) }
        .filter { manager.isTracking(event.player, it) }
        .forEach { objective ->
            val player = event.player
            val context = player.contextOrEmpty()
            val item = objective.item.get(player, context)
            val remaining = objective.targetAmount(player, context) - manager.progress(player, objective)
            val handOver = minOf(remaining, ItemCoreBridge.available(player, item, context))
            val source = "quest:typewriter/deliver/${objective.id}"
            val delivered = ItemCoreBridge.consume(player, item, context, handOver, source)
            if (delivered > 0) {
                manager.increment(event.player, objective, delivered)
            }
        }
}

enum class DepartedNpcClickType {
    ANY_CLICK,
    LEFT_CLICK,
    RIGHT_CLICK;

    fun matches(leftClick: Boolean): Boolean = when (this) {
        ANY_CLICK -> true
        LEFT_CLICK -> leftClick
        RIGHT_CLICK -> !leftClick
    }
}

internal fun matchesDepartedNpcId(npcId: String, identifier: String): Boolean {
    val trimmed = identifier.trim()
    if (trimmed.isBlank()) return true
    return npcId.equals(trimmed, ignoreCase = true)
}

private fun departedObjectives(): DepartedObjectiveManager = get(DepartedObjectiveManager::class.java)
