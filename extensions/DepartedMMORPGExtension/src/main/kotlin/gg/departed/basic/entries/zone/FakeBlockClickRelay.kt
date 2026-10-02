package gg.departed.basic.entries.zone

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.DiggingAction
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging
import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.core.utils.point.Position
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.server
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.github.retrooper.packetevents.protocol.world.BlockFace as PacketBlockFace
import org.bukkit.block.BlockFace as BukkitBlockFace

/** A single block, identified by world and integer coordinates so lookups never compare doubles. */
private data class BlockKey(val world: String, val x: Int, val y: Int, val z: Int)

private fun Position.toKey() = BlockKey(world.identifier, blockX, blockY, blockZ)

/**
 * Tracks which block positions are currently faked for which player, so [FakeBlockClickRelay] knows
 * whether a click landed on a packet block or on the real world.
 *
 * [FakeBlockZoneDisplay] owns the entries here: it adds a player's positions when they enter the
 * audience and drops them when they leave, so the set always matches what the client is being shown.
 */
object FakeBlockTracker {
    private val faked = ConcurrentHashMap<UUID, MutableSet<BlockKey>>()

    fun track(playerId: UUID, positions: Collection<Position>) {
        if (positions.isEmpty()) return
        faked.computeIfAbsent(playerId) { ConcurrentHashMap.newKeySet() }
            .addAll(positions.map { it.toKey() })
    }

    fun untrack(playerId: UUID, positions: Collection<Position>) {
        val tracked = faked[playerId] ?: return
        tracked.removeAll(positions.map { it.toKey() }.toSet())
        // Zones come and go; don't leave an empty set per player behind for every one they clear.
        if (tracked.isEmpty()) faked.remove(playerId)
    }

    fun forget(playerId: UUID) {
        faked.remove(playerId)
    }

    fun isFaked(player: Player, x: Int, y: Int, z: Int): Boolean =
        faked[player.uniqueId]?.contains(BlockKey(player.world.uid.toString(), x, y, z)) == true
}

/**
 * Makes a fake block clickable.
 *
 * A [FakeBlockZoneAudienceEntry] block only exists on the client, so the server sees air there. Air
 * cannot be right-clicked and cannot be mined, which means none of the interact entries would ever
 * fire on one: hitting a packet wall does nothing at all. That leaves no way to let a player open a
 * fake gate themselves.
 *
 * This relay closes the gap. When a player starts to break a block that is faked for them, the client
 * sends a dig packet naming the exact position it thinks it hit. We turn that into a normal
 * [PlayerInteractEvent] (`RIGHT_CLICK_BLOCK`), so from Typewriter's side a left click on a fake block
 * reads as interacting with it, and the ordinary entries work:
 *
 *  - `interact_event_entry` with the position set. This is the one to reach for: it filters on
 *    location only, so there is no material to line up.
 *  - `on_interact_with_block` also works, but its `block` field is matched against the REAL block at
 *    that position, which for a walk-through fake wall is `AIR`, not the material being displayed.
 *
 * A right click on a fake block is left alone: the client sends no packet the server can place a
 * position on, and vanilla already reports it as `RIGHT_CLICK_AIR`.
 *
 * The relay only ever fires on positions that are currently faked for the clicking player, so it
 * cannot manufacture interactions anywhere else in the world.
 */
@Singleton
class FakeBlockClickRelay : Initializable, Listener {

    private var listener: PacketListenerAbstract? = null

    // Holding the mouse down re-sends START_DIGGING as the client restarts its break animation on a
    // block that never breaks. Without this, one held click would fire the interaction repeatedly.
    private val lastRelay = ConcurrentHashMap<UUID, Long>()

    override suspend fun initialize() {
        val api = runCatching { PacketEvents.getAPI() }.getOrNull() ?: return
        listener = object : PacketListenerAbstract(PacketListenerPriority.NORMAL) {
            override fun onPacketReceive(event: PacketReceiveEvent) {
                if (event.packetType != PacketType.Play.Client.PLAYER_DIGGING) return
                val wrapper = WrapperPlayClientPlayerDigging(event)
                // Only the start of a swing. The client also sends FINISHED/CANCELLED for the same
                // click, and both would relay a second interaction for one player action.
                if (wrapper.action != DiggingAction.START_DIGGING) return
                val uuid = event.user?.uuid ?: return
                val position = wrapper.blockPosition ?: return
                val face = wrapper.blockFace

                // Packet thread: everything below touches the world and the event bus.
                server.scheduler.runTask(plugin, Runnable {
                    relay(uuid, position.x, position.y, position.z, face)
                })
            }
        }
        api.eventManager.registerListener(listener!!)
        server.pluginManager.registerEvents(this, plugin)
    }

    override suspend fun shutdown() {
        HandlerList.unregisterAll(this)
        listener?.let { runCatching { PacketEvents.getAPI().eventManager.unregisterListener(it) } }
        listener = null
        lastRelay.clear()
    }

    // Belt and braces: the audience drops the player on quit, but a display that failed to clean up
    // would otherwise leave their positions tracked forever.
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        FakeBlockTracker.forget(event.player.uniqueId)
        lastRelay.remove(event.player.uniqueId)
    }

    private fun relay(uuid: UUID, x: Int, y: Int, z: Int, face: PacketBlockFace?) {
        val player = Bukkit.getPlayer(uuid) ?: return
        if (!FakeBlockTracker.isFaked(player, x, y, z)) return

        val now = System.currentTimeMillis()
        if (now - (lastRelay[uuid] ?: 0L) < RELAY_COOLDOWN_MS) return
        lastRelay[uuid] = now

        val block = player.world.getBlockAt(x, y, z)
        val clickedFace = face?.let {
            runCatching { BukkitBlockFace.valueOf(it.name) }.getOrNull()
        } ?: BukkitBlockFace.SELF

        Bukkit.getPluginManager().callEvent(
            PlayerInteractEvent(
                player,
                Action.RIGHT_CLICK_BLOCK,
                player.inventory.itemInMainHand,
                block,
                clickedFace,
                EquipmentSlot.HAND,
            )
        )
    }

    companion object {
        private const val RELAY_COOLDOWN_MS = 400L
    }
}
