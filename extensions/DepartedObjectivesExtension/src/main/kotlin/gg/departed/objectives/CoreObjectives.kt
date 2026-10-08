package gg.departed.objectives

import com.typewritermc.basic.itemcore.CoreItemQueries
import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Query
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.EntryListener
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.MaterialProperties
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.core.extension.annotations.MaterialProperty
import com.typewritermc.core.utils.point.Position
import com.typewritermc.core.utils.point.isInRange
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.item.Item
import com.typewritermc.engine.paper.utils.server
import com.typewritermc.engine.paper.utils.toPosition
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.FurnaceExtractEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import dev.departed.itemcore.api.ItemCore
import dev.departed.itemcore.api.drops.DropOrigin
import dev.departed.itemcore.api.event.ItemPickupEvent
import dev.departed.itemcore.api.item.ItemView
import org.koin.java.KoinJavaComponent.get
import java.util.Optional

@Entry("kill_vanilla_mob_objective", "Kill vanilla mobs", Colors.YELLOW, "fa6-solid:skull")
class KillVanillaMobObjectiveEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    override val questId: String = "",
    override val order: Int = 0,
    override val instruction: Var<String> = ConstVar("Kill {remaining} more mobs."),
    override val amount: Var<Int> = ConstVar(1),
    override val completeDepartedQuestWhenDone: Boolean = true,
    val entityType: Optional<EntityType> = Optional.empty(),
) : DepartedObjectiveEntry

@EntryListener(KillVanillaMobObjectiveEntry::class)
fun onKillVanillaMobObjective(event: EntityDeathEvent, query: Query<KillVanillaMobObjectiveEntry>) {
    val killer = event.entity.killer ?: return
    query.findWhere { it.entityType.map { type -> type == event.entityType }.orElse(true) }
        .forEach { objectives().increment(killer, it) }
}

@Entry("visit_dimension_objective", "Visit a world or dimension", Colors.BLUE, "mdi:earth")
class VisitDimensionObjectiveEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    override val questId: String = "",
    override val order: Int = 0,
    override val instruction: Var<String> = ConstVar("Travel to the target dimension."),
    override val amount: Var<Int> = ConstVar(1),
    override val completeDepartedQuestWhenDone: Boolean = true,
    @Help("Bukkit world name. Leave empty to match any world with the selected environment.")
    val worldName: String = "",
    val environment: Optional<World.Environment> = Optional.empty(),
) : DepartedObjectiveEntry

@EntryListener(VisitDimensionObjectiveEntry::class)
fun onVisitDimensionChangedWorld(event: PlayerChangedWorldEvent, query: Query<VisitDimensionObjectiveEntry>) {
    checkVisitDimension(event.player, query)
}

@EntryListener(VisitDimensionObjectiveEntry::class)
fun onVisitDimensionJoin(event: PlayerJoinEvent, query: Query<VisitDimensionObjectiveEntry>) {
    checkVisitDimension(event.player, query)
}

@EntryListener(VisitDimensionObjectiveEntry::class)
fun onVisitDimensionTeleport(event: PlayerTeleportEvent, query: Query<VisitDimensionObjectiveEntry>) {
    checkVisitDimension(event.player, query)
}

private fun checkVisitDimension(player: Player, query: Query<VisitDimensionObjectiveEntry>) {
    query.findWhere { entry ->
        val worldMatches = entry.worldName.isBlank() || player.world.name.equals(entry.worldName, ignoreCase = true)
        val environmentMatches = entry.environment.map { it == player.world.environment }.orElse(true)
        worldMatches && environmentMatches
    }.forEach { objectives().increment(player, it) }
}

@Entry("reach_location_objective", "Reach a location", Colors.BLUE, "mdi:map-marker-radius")
class ReachLocationObjectiveEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    override val questId: String = "",
    override val order: Int = 0,
    override val instruction: Var<String> = ConstVar("Reach the marked location."),
    override val amount: Var<Int> = ConstVar(1),
    override val completeDepartedQuestWhenDone: Boolean = true,
    val location: Var<Position> = ConstVar(Position.ORIGIN),
    val radius: Var<Double> = ConstVar(4.0),
) : DepartedObjectiveEntry

// Reach-location is polled on a timer instead of PlayerMoveEvent (which fires ~20x/sec per player).
// See ReachLocationPoller below. Teleports are checked immediately since they skip the poll window.
@EntryListener(ReachLocationObjectiveEntry::class)
fun onReachLocationTeleport(event: PlayerTeleportEvent, query: Query<ReachLocationObjectiveEntry>) {
    checkReachLocation(event.player, query.find().toList())
}

internal fun checkReachLocation(player: Player, entries: List<ReachLocationObjectiveEntry>) {
    if (entries.isEmpty()) return
    val manager = objectives()
    val playerPosition = player.location.toPosition()
    entries.forEach { entry ->
        if (manager.isComplete(player, entry)) return@forEach
        if (!manager.isTracking(player, entry)) return@forEach
        if (playerPosition.isInRange(entry.location.get(player), entry.radius.get(player))) {
            manager.increment(player, entry)
        }
    }
}

@Singleton
class ReachLocationPoller : Initializable {
    private var task: BukkitTask? = null

    override suspend fun initialize() {
        task = server.scheduler.runTaskTimer(plugin, Runnable {
            val entries = Query.find<ReachLocationObjectiveEntry>().toList()
            if (entries.isEmpty()) return@Runnable
            server.onlinePlayers.forEach { checkReachLocation(it, entries) }
        }, REACH_POLL_INTERVAL_TICKS, REACH_POLL_INTERVAL_TICKS)
    }

    override suspend fun shutdown() {
        task?.cancel()
        task = null
    }

    private companion object {
        const val REACH_POLL_INTERVAL_TICKS = 10L // twice per second
    }
}

@Entry("break_block_objective", "Break blocks", Colors.YELLOW, "mingcute:pickax-fill")
class BreakBlockObjectiveEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    override val questId: String = "",
    override val order: Int = 0,
    override val instruction: Var<String> = ConstVar("Break {remaining} more blocks."),
    override val amount: Var<Int> = ConstVar(1),
    override val completeDepartedQuestWhenDone: Boolean = true,
    @MaterialProperties(MaterialProperty.BLOCK)
    val block: Optional<Material> = Optional.empty(),
) : DepartedObjectiveEntry

@EntryListener(BreakBlockObjectiveEntry::class, ignoreCancelled = true)
fun onBreakBlockObjective(event: BlockBreakEvent, query: Query<BreakBlockObjectiveEntry>) {
    query.findWhere { it.block.map { block -> block == event.block.type }.orElse(true) }
        .forEach { objectives().increment(event.player, it) }
}

@Entry("place_block_objective", "Place blocks", Colors.YELLOW, "mdi:cube-outline")
class PlaceBlockObjectiveEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    override val questId: String = "",
    override val order: Int = 0,
    override val instruction: Var<String> = ConstVar("Place {remaining} more blocks."),
    override val amount: Var<Int> = ConstVar(1),
    override val completeDepartedQuestWhenDone: Boolean = true,
    @MaterialProperties(MaterialProperty.BLOCK)
    val block: Optional<Material> = Optional.empty(),
) : DepartedObjectiveEntry

@EntryListener(PlaceBlockObjectiveEntry::class, ignoreCancelled = true)
fun onPlaceBlockObjective(event: BlockPlaceEvent, query: Query<PlaceBlockObjectiveEntry>) {
    query.findWhere { it.block.map { block -> block == event.block.type }.orElse(true) }
        .forEach { objectives().increment(event.player, it) }
}

@Entry("fish_objective", "Catch fish or items", Colors.BLUE, "mdi:fish")
class FishObjectiveEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    override val questId: String = "",
    override val order: Int = 0,
    override val instruction: Var<String> = ConstVar("Catch {remaining} more fish."),
    override val amount: Var<Int> = ConstVar(1),
    override val completeDepartedQuestWhenDone: Boolean = true,
) : DepartedObjectiveEntry

@EntryListener(FishObjectiveEntry::class, ignoreCancelled = true)
fun onFishObjective(event: PlayerFishEvent, query: Query<FishObjectiveEntry>) {
    if (event.state != PlayerFishEvent.State.CAUGHT_FISH) return
    query.find().forEach { objectives().increment(event.player, it) }
}

@Entry("craft_item_objective", "Craft items", Colors.YELLOW, "mdi:hammer-wrench")
class CraftItemObjectiveEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    override val questId: String = "",
    override val order: Int = 0,
    override val instruction: Var<String> = ConstVar("Craft {remaining} more items."),
    override val amount: Var<Int> = ConstVar(1),
    override val completeDepartedQuestWhenDone: Boolean = true,
    val item: Var<Item> = ConstVar(Item.Empty),
) : DepartedObjectiveEntry

@EntryListener(CraftItemObjectiveEntry::class, ignoreCancelled = true)
fun onCraftItemObjective(event: CraftItemEvent, query: Query<CraftItemObjectiveEntry>) {
    val player = event.whoClicked as? Player ?: return
    val result = event.currentItem ?: event.recipe.result
    query.findWhere { it.item.get(player).isSameAs(player, result) }
        .forEach { objectives().increment(player, it, result.amount.coerceAtLeast(1)) }
}

@Entry("smelt_item_objective", "Smelt items", Colors.YELLOW, "fa6-solid:fire-burner")
class SmeltItemObjectiveEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    override val questId: String = "",
    override val order: Int = 0,
    override val instruction: Var<String> = ConstVar("Smelt {remaining} more items."),
    override val amount: Var<Int> = ConstVar(1),
    override val completeDepartedQuestWhenDone: Boolean = true,
    val item: Var<Item> = ConstVar(Item.Empty),
) : DepartedObjectiveEntry

@EntryListener(SmeltItemObjectiveEntry::class)
fun onSmeltItemObjective(event: FurnaceExtractEvent, query: Query<SmeltItemObjectiveEntry>) {
    val result = ItemStack(event.itemType, event.itemAmount.coerceAtLeast(1))
    query.findWhere { it.item.get(event.player).isSameAs(event.player, result) }
        .forEach { objectives().increment(event.player, it, event.itemAmount.coerceAtLeast(1)) }
}

@Entry("collect_item_objective", "Collect items", Colors.YELLOW, "fa6-solid:bag-shopping")
/**
 * Counts items the player gathers from the world: every DepartedItemCore pickup (block, mob, farm and collectible
 * drops, ore magnets, resource nodes) of a matching item adds the picked-up amount. Items bought, given by quests
 * or commands, or dropped by a player and picked up again never count (the owner's rule: objectives count events,
 * never possession). Progress is stored in the quest's data under [progressVariable] (or `obj_<entry id>`), so it
 * survives restarts and is wiped when the quest is started again.
 */
class CollectItemObjectiveEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    override val questId: String = "",
    override val order: Int = 0,
    override val instruction: Var<String> = ConstVar("Collect {remaining} more items."),
    override val amount: Var<Int> = ConstVar(1),
    override val completeDepartedQuestWhenDone: Boolean = true,
    override val progressVariable: String = "",
    val item: Var<Item> = ConstVar(Item.Empty),
    @Help("If set, any item of these materials counts (item matcher is ignored).")
    val anyOfMaterials: List<Material> = emptyList(),
) : DepartedObjectiveEntry, QuestVariableObjectiveEntry

fun CollectItemObjectiveEntry.matchesCollectItem(player: Player, view: ItemView): Boolean =
    if (anyOfMaterials.isNotEmpty()) view.material() in anyOfMaterials
    else CoreItemQueries.of(player, item.get(player), player.contextOrEmpty()).test(view)

/** The quest-data key holding this objective's gathered count. */
val CollectItemObjectiveEntry.dataKey: String
    get() = progressVariable.trim().ifBlank { "obj_$id" }

fun CollectItemObjectiveEntry.storedProgress(player: Player): Int =
    DepartedRpgBridge.cachedQuestDataValue(player, questId, dataKey).trim().toIntOrNull() ?: 0

@EntryListener(CollectItemObjectiveEntry::class)
fun onCollectItemPickup(event: ItemPickupEvent, query: Query<CollectItemObjectiveEntry>) {
    if (event.phase() != ItemPickupEvent.Phase.POST || event.consumed()) return
    if (event.origin() == DropOrigin.PLAYER_DROP) return
    val gathered = event.taken()
    if (gathered <= 0) return
    val player = event.player
    val view = ItemCore.get().items().view(event.stack())
    val manager = objectives()
    query.findWhere { it.matchesCollectItem(player, view) }
        .filter { manager.isTracking(player, it) && !manager.isComplete(player, it) }
        .forEach { objective ->
            val total = objective.storedProgress(player) + gathered
            DepartedRpgBridge.setQuestDataValue(player, objective.questId, objective.dataKey, total.toString())
            manager.setProgress(player, objective, total)
        }
}

@EntryListener(CollectItemObjectiveEntry::class)
fun onCollectItemJoin(event: PlayerJoinEvent, query: Query<CollectItemObjectiveEntry>) {
    val player = event.player
    server.scheduler.runTaskLater(com.typewritermc.engine.paper.plugin, Runnable {
        if (player.isOnline) objectives().restoreCollectObjectives(player)
    }, COLLECT_RESTORE_DELAY_TICKS)
}

/** Quest data is cached a moment after join; restoring earlier would read an empty row. */
private const val COLLECT_RESTORE_DELAY_TICKS = 60L

@Entry("consume_item_objective", "Consume items", Colors.YELLOW, "game-icons:eating")
class ConsumeItemObjectiveEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    override val questId: String = "",
    override val order: Int = 0,
    override val instruction: Var<String> = ConstVar("Consume {remaining} more items."),
    override val amount: Var<Int> = ConstVar(1),
    override val completeDepartedQuestWhenDone: Boolean = true,
    val item: Var<Item> = ConstVar(Item.Empty),
) : DepartedObjectiveEntry

@EntryListener(ConsumeItemObjectiveEntry::class, ignoreCancelled = true)
fun onConsumeItemObjective(event: PlayerItemConsumeEvent, query: Query<ConsumeItemObjectiveEntry>) {
    query.findWhere { it.item.get(event.player).isSameAs(event.player, event.item) }
        .forEach { objectives().increment(event.player, it) }
}

private fun objectives(): DepartedObjectiveManager = get(DepartedObjectiveManager::class.java)
