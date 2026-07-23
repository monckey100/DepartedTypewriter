package gg.departed.basic.entries.zone

import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCollectItem
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity
import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.MaterialProperties
import com.typewritermc.core.extension.annotations.MaterialProperty
import com.typewritermc.core.interaction.context
import com.typewritermc.core.utils.point.Position
import com.typewritermc.core.utils.point.toBlockPosition
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.AudienceDisplay
import com.typewritermc.engine.paper.entry.entries.AudienceEntry
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.entry.triggerFor
import com.typewritermc.engine.paper.extensions.packetevents.sendPacketTo
import com.typewritermc.engine.paper.interaction.interactionContext
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.Sound
import com.typewritermc.engine.paper.utils.item.Item
import com.typewritermc.engine.paper.utils.playSound
import com.typewritermc.engine.paper.utils.toPacketLocation
import com.typewritermc.engine.paper.utils.toPosition
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import me.tofaa.entitylib.EntityLib
import me.tofaa.entitylib.meta.EntityMeta
import me.tofaa.entitylib.meta.projectile.ItemEntityMeta
import me.tofaa.entitylib.wrapper.WrapperEntity
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

@Entry("resource_node_zone", "A player-specific resource harvesting zone", Colors.GREEN, "mdi:mushroom")
/**
 * The `Resource Node Zone` is an audience entry that turns a cube of blocks into player-specific
 * harvest nodes. While a player is in the audience (e.g. gated by a quest fact), breaking a matching
 * block inside the cube with the required tool is cancelled so the block stays for everyone else, but
 * the breaking player privately sees it turn into a temporary block for a few seconds, hears a sound,
 * and receives their own loot that no one else can see.
 *
 * ## How could this be used?
 * A level 1 human talks to a chicken NPC and must axe mushroom blocks for resources. The mushrooms
 * persist for other players, and every player collects their own private loot.
 */
class ResourceNodeZoneAudienceEntry(
    override val id: String = "",
    override val name: String = "",
    @Help("One corner of the harvest cube.")
    val corner1: Var<Position> = ConstVar(Position.ORIGIN),
    @Help("The opposite corner of the harvest cube.")
    val corner2: Var<Position> = ConstVar(Position.ORIGIN),
    @MaterialProperties(MaterialProperty.BLOCK)
    @Help("The block types that can be harvested. Leave empty to allow any block in the cube.")
    val blocks: List<Material> = emptyList(),
    @Help("The item the player must be holding to harvest. Leave empty for no requirement.")
    val itemInHand: Var<Item> = ConstVar(Item.Empty),
    @Help("The hand the required item must be held in.")
    val hand: HoldingHand = HoldingHand.BOTH,
    @Help("The private loot given to the player on a successful harvest.")
    val loot: Var<Item> = ConstVar(Item.Empty),
    @MaterialProperties(MaterialProperty.BLOCK)
    @Help("The block the player privately sees while the node is on cooldown.")
    val temporaryBlock: Var<Material> = ConstVar(Material.BARRIER),
    @Help("How long (in seconds) the temporary block is shown before the real block returns for the player.")
    val respawnSeconds: Var<Double> = ConstVar(5.0),
    @Help("The sound played to the player when they harvest a node.")
    val breakSound: Var<Sound> = ConstVar(Sound.EMPTY),
    @Help("Fired for the player on every successful harvest (e.g. to progress an objective).")
    val triggers: List<Ref<TriggerableEntry>> = emptyList(),
) : AudienceEntry {
    override suspend fun display(): AudienceDisplay = ResourceNodeZoneDisplay(this)
}

class ResourceNodeZoneDisplay(
    private val entry: ResourceNodeZoneAudienceEntry,
) : AudienceDisplay() {
    // Per player: the block positions currently showing the temporary block, with their restore task.
    private val pendingRestores = ConcurrentHashMap<UUID, MutableMap<Position, BukkitTask>>()

    override fun onPlayerAdd(player: Player) {}

    override fun onPlayerRemove(player: Player) {
        val pending = pendingRestores.remove(player.uniqueId) ?: return
        pending.forEach { (position, task) ->
            task.cancel()
            player.restoreRealBlock(position)
        }
    }

    // LOWEST, and deliberately NOT ignoreCancelled: harvest nodes usually sit inside a region that a
    // protection plugin (e.g. WorldGuard) denies block-breaking in, to stop griefing. Such plugins
    // cancel with ignoreCancelled = true, so by running first and cancelling ourselves we take the
    // event out of their hands entirely: the node is never really broken, they never see the event, and
    // the player gets loot instead of a "you can't break blocks here" message. Blocks outside the node
    // cuboid are left untouched and stay protected as normal.
    @EventHandler(priority = EventPriority.LOWEST)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val context = player.interactionContext
        val cuboid = BlockCuboid.of(entry.corner1.get(player, context), entry.corner2.get(player, context))
        val blockPosition = event.block.location.toPosition().toBlockPosition()
        if (!cuboid.contains(blockPosition)) return
        if (entry.blocks.isNotEmpty() && event.block.type !in entry.blocks) return

        // Node blocks are shared scenery. Cancel before any player-specific check, otherwise a player
        // who is out of the audience, on cooldown, or holding the wrong tool would really break the
        // block: it would vanish for everyone and drop a world-visible item.
        event.isCancelled = true

        if (player !in this) return
        if (!hasItemInHand(player, entry.hand, entry.itemInHand.get(player, context), context)) return

        val pending = pendingRestores.getOrPut(player.uniqueId) { ConcurrentHashMap() }
        // Already on cooldown for this player: ignore so they cannot double-harvest.
        if (pending.containsKey(blockPosition)) return

        // Feedback + private loot.
        player.playSound(entry.breakSound.get(player, context), context)
        giveHarvestLoot(player, blockPosition, entry.loot.get(player, context).build(player, context))
        entry.triggers.forEach { it.triggerFor(player, context ?: context()) }

        // Show the temporary block next tick (after Bukkit's own cancel-revert lands), then restore.
        val temporaryData = entry.temporaryBlock.get(player, context).createBlockData()
        val ticks = max(1L, (entry.respawnSeconds.get(player, context) * 20.0).toLong())
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (player.isOnline) player.sendFakeBlock(blockPosition, temporaryData)
        })
        val restore = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            pending.remove(blockPosition)
            if (player.isOnline) player.restoreRealBlock(blockPosition)
        }, ticks)
        pending[blockPosition] = restore
    }

    /** Spawns a fake dropped item only the harvesting player can see, then collects it into their inventory. */
    private fun giveHarvestLoot(player: Player, blockPosition: Position, stack: ItemStack) {
        if (stack.type == Material.AIR || stack.amount <= 0) return

        val type = EntityTypes.ITEM
        val uuid = EntityLib.getPlatform().entityUuidProvider.provide(type)
        val entityId = EntityLib.getPlatform().entityIdProvider.provide(uuid, type)
        val meta = EntityMeta.createMeta(entityId, type)
        (meta as? ItemEntityMeta)?.item = SpigotConversionUtil.fromBukkitItemStack(stack)
        val entity = WrapperEntity(entityId, uuid, type, meta)

        entity.spawn(blockPosition.center().toPacketLocation())
        entity.addViewer(player.uniqueId)
        WrapperPlayServerEntityVelocity(entityId, Vector3d(0.0, 0.25, 0.0)) sendPacketTo player

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (player.isOnline) {
                WrapperPlayServerCollectItem(entityId, player.entityId, stack.amount) sendPacketTo player
            }
            entity.despawn()
            entity.remove()
            if (player.isOnline) {
                val leftover = player.inventory.addItem(stack)
                leftover.values.forEach { player.dropPrivately(it) }
            }
        }, 10L)
    }

    /**
     * Drops [stack] as a real item that only [this] player can see or pick up, for loot that did not
     * fit in their inventory. A plain world drop would be visible to, and collectable by, everyone.
     */
    private fun Player.dropPrivately(stack: ItemStack) {
        val item = world.dropItemNaturally(location, stack) { spawned ->
            spawned.isVisibleByDefault = false
            spawned.owner = uniqueId
        }
        showEntity(plugin, item)
    }
}
